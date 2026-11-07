package com.transit.service;

import com.transit.mapper.UserMapper;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.mapper.WalletRechargeOrderMapper;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaymentEmailWorker {
    private final JdbcTemplate jdbc;
    private final UserMapper users;
    private final ServiceOrderMapper orders;
    private final WalletRechargeOrderMapper recharges;
    private final ServiceOrderService serviceOrders;
    private final RechargeOrderService rechargeOrders;
    private final PaymentExperienceService content;
    private final ObjectProvider<JavaMailSender> mail;
    @Value("${verification.email.sender-address:linknux@linknux.com}") private String sender;
    @Value("${spring.mail.host:}") private String mailHost;
    @Value("${payment.local-test-mode:false}") private boolean localTest;
    @Value("${payment.email.enabled:true}") private boolean enabled;

    @Scheduled(fixedDelayString="${payment.email.scan-ms:15000}", initialDelayString="${payment.email.initial-delay-ms:60000}")
    public void scan() {
        if (!enabled || localTest) return;
        // SMTP may have accepted a message before a crash. Never automatically resend an uncertain delivery.
        jdbc.update("UPDATE payment_email_outbox SET status='UNKNOWN',last_error='Delivery interrupted; review before retry' WHERE status='SENDING' AND claimed_at<?", now().minusMinutes(15));
        List<Map<String,Object>> tasks = jdbc.queryForList("SELECT id,business_type,business_id,attempts FROM payment_email_outbox WHERE status IN ('PENDING','RETRY') AND next_attempt_at<=? ORDER BY id LIMIT 10", now());
        for (var task: tasks) {
            long id = ((Number)task.get("id")).longValue();
            int claimed = jdbc.update("UPDATE payment_email_outbox SET status='SENDING',attempts=attempts+1,claimed_at=? WHERE id=? AND status IN ('PENDING','RETRY') AND next_attempt_at<=?",now(),id,now());
            if (claimed == 1) deliver(id, String.valueOf(task.get("business_type")), ((Number)task.get("business_id")).longValue(), ((Number)task.get("attempts")).intValue()+1);
        }
    }
    private void deliver(long taskId, String type, long businessId, int attempt) {
        boolean sending = false;
        try {
            JavaMailSender transport = mail.getIfAvailable();
            if (transport == null || mailHost == null || mailHost.isBlank()) throw new IllegalStateException("Mail unavailable");
            User user;
            String orderNo, product, amount, method, reference, paidAt, invoiceNo, receiptNo, link;
            byte[] invoice = null, receipt;
            if (PaymentBusinessSettlementService.SERVICE_ORDER.equals(type)) {
                var order = orders.selectById(businessId);
                if (order == null || order.getPaidAt() == null) throw new IllegalStateException("Payment evidence unavailable");
                user = users.selectById(order.getUserId());
                var pdfs = serviceOrders.emailDocuments(user,businessId);
                invoice=pdfs[0]; receipt=pdfs[1];
                orderNo=order.getOrderNo(); product=order.getProductName();
                amount=BigDecimal.valueOf(order.getPaymentAmountCents(),2).toPlainString()+" "+order.getPaymentCurrency();
                method=order.getPaymentMethod(); reference=order.getPaymentReference(); paidAt=order.getPaidAt()+" UTC";
                invoiceNo=order.getInvoiceNumber(); receiptNo=order.getReceiptNumber(); link=content.downloadUrl("service",businessId);
            } else if (PaymentBusinessSettlementService.WALLET_RECHARGE.equals(type)) {
                var order=recharges.selectById(businessId);
                if (order == null || order.getPaidAt() == null) throw new IllegalStateException("Payment evidence unavailable");
                user=users.selectById(order.getUserId());
                if (Boolean.TRUE.equals(order.getInvoiceRequested())) invoice=rechargeOrders.invoice(user,businessId);
                receipt=rechargeOrders.receipt(user,businessId);
                orderNo=order.getOrderNo(); product=order.getPlanName();
                amount=BigDecimal.valueOf(order.getPaymentAmountUnits(),4).toPlainString()+" CNY";
                var detailed = rechargeOrders.get(user,businessId);
                var intent = detailed == null ? null : detailed.getPaymentIntent();
                if (intent != null) amount=BigDecimal.valueOf(intent.getSettlementAmountCents(),2).toPlainString()+" "+intent.getSettlementCurrency();
                method=order.getPaymentMethod(); reference=intent == null ? "" : intent.getProviderTradeNo(); paidAt=order.getPaidAt()+" UTC";
                invoiceNo=order.getInvoiceNumber(); receiptNo=order.getReceiptNumber(); link=content.downloadUrl("recharge",businessId);
            } else throw new IllegalStateException("Unsupported business");
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) throw new IllegalStateException("Account email unavailable");
            var copy=content.read();
            var message=transport.createMimeMessage();
            var helper=new MimeMessageHelper(message,true,"UTF-8");
            helper.setFrom(sender); helper.setTo(user.getEmail());
            helper.setSubject(copy.get("emailSubject").replace("{orderNo}",orderNo));
            helper.setText(copy.get("emailBody")+"\n\nOrder: "+orderNo+"\nProduct: "+product+"\nAmount paid: "+amount
                +"\nPayment method: "+method+"\nPayment reference: "+(reference==null?"":reference)+"\nPaid at: "+paidAt
                +"\nInvoice: "+invoiceNo+"\nReceipt: "+receiptNo+"\n\nDownload (sign in to your account): "+link
                +(invoice==null?"\nNo invoice was requested for this recharge. Receipt attached.":""));
            if(invoice!=null) helper.addAttachment("invoice-"+orderNo+".pdf",new ByteArrayResource(invoice),"application/pdf");
            helper.addAttachment("receipt-"+orderNo+".pdf",new ByteArrayResource(receipt),"application/pdf");
            sending=true;
            transport.send(message);
            jdbc.update("UPDATE payment_email_outbox SET status='SENT',sent_at=?,last_error=NULL WHERE id=? AND status='SENDING'",now(),taskId);
        } catch(Exception failure) {
            // Do not expose addresses, SMTP credentials or message bodies in logs.
            String state=sending?"UNKNOWN":attempt>=5?"FAILED":"RETRY";
            jdbc.update("UPDATE payment_email_outbox SET status=?,last_error=?,next_attempt_at=? WHERE id=? AND status='SENDING'",
                state,sending?"SMTP result uncertain; review before retry":"Message preparation failed",now().plusMinutes(Math.min(60,attempt*attempt)),taskId);
        }
    }
    private LocalDateTime now() {return LocalDateTime.now(ZoneOffset.UTC);}
}
