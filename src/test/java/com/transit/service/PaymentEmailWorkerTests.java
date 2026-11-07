package com.transit.service;
import com.transit.mapper.*;
import com.transit.model.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PaymentEmailWorkerTests {
    JdbcTemplate jdbc; JavaMailSender sender; PaymentEmailWorker worker; ServiceOrderService documents; WalletRechargeOrderMapper recharges; RechargeOrderService rechargeDocuments;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:email"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V43__payment_email_outbox.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);
        var users=mock(UserMapper.class); var orders=mock(ServiceOrderMapper.class);
        var user=new User();user.setId(1L);user.setEmail("buyer@example.com");when(users.selectById(1L)).thenReturn(user);
        var order=new ServiceOrder();order.setId(7L);order.setUserId(1L);order.setOrderNo("ORDER-7");order.setPaidAt(java.time.LocalDateTime.now());
        order.setPaymentAmountCents(125001L);order.setPaymentCurrency("CNY");order.setPaymentMethod("wxpay");order.setProductName("Product");
        when(orders.selectById(7L)).thenReturn(order);
        documents=mock(ServiceOrderService.class);when(documents.emailDocuments(user,7L)).thenReturn(new byte[][]{"invoice".getBytes(),"receipt".getBytes()});
        var content=mock(PaymentExperienceService.class);when(content.read()).thenReturn(Map.of("emailSubject","Paid {orderNo}","emailBody","Payment confirmed"));when(content.downloadUrl("service",7L)).thenReturn("https://linknux.com/billing/service/7");
        sender=mock(JavaMailSender.class);when(sender.createMimeMessage()).thenAnswer(i->new MimeMessage(Session.getInstance(new Properties())));
        ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(sender);
        recharges=mock(WalletRechargeOrderMapper.class);rechargeDocuments=mock(RechargeOrderService.class);
        worker=new PaymentEmailWorker(jdbc,users,orders,recharges,documents,rechargeDocuments,content,provider);
        ReflectionTestUtils.setField(worker,"mailHost","smtp.example.com");
        ReflectionTestUtils.setField(worker,"sender","billing@example.com");ReflectionTestUtils.setField(worker,"enabled",true);
        jdbc.update("INSERT INTO payment_email_outbox(business_type,business_id,next_attempt_at,created_at) VALUES ('SERVICE_ORDER',7,?,?)", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }
    String state(){return jdbc.queryForObject("SELECT status FROM payment_email_outbox",String.class);}
    @Test void sendsTwoAttachmentsAndOrderDetailsOnlyOnce() throws Exception {
        worker.scan();worker.scan();assertThat(state()).isEqualTo("SENT");
        var capture=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(sender).send(capture.capture());
        var message=capture.getValue();message.saveChanges();
        assertThat(bodyText(message)).contains("ORDER-7", "1250.01 CNY", "https://linknux.com/billing/service/7");
        assertThat(message.getSubject()).isEqualTo("Paid ORDER-7");assertThat(message.getAllRecipients()[0].toString()).isEqualTo("buyer@example.com");
        var output=new java.io.ByteArrayOutputStream();message.writeTo(output);String raw=output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(raw).contains("invoice-ORDER-7.pdf","receipt-ORDER-7.pdf");
    }
    @Test void retriesPreparationFailureWithoutLosingPaymentOrSending() {
        when(documents.emailDocuments(any(),eq(7L))).thenThrow(new IllegalStateException());worker.scan();assertThat(state()).isEqualTo("RETRY");verify(sender,never()).send(any(MimeMessage.class));
    }
    @Test void uncertainSmtpResultIsNotAutomaticallyResent() {
        doThrow(new org.springframework.mail.MailSendException("uncertain")).when(sender).send(any(MimeMessage.class));worker.scan();worker.scan();assertThat(state()).isEqualTo("UNKNOWN");verify(sender,times(1)).send(any(MimeMessage.class));
    }
    @Test void rechargeWithoutInvoiceRequestAttachesOnlyReceipt() throws Exception {
        jdbc.update("UPDATE payment_email_outbox SET business_type='WALLET_RECHARGE'");
        var order=new WalletRechargeOrder();order.setUserId(1L);order.setOrderNo("RCG-7");order.setPaidAt(java.time.LocalDateTime.now());order.setPaymentAmountUnits(100000L);order.setInvoiceRequested(false);
        when(recharges.selectById(7L)).thenReturn(order);when(rechargeDocuments.receipt(any(),eq(7L))).thenReturn("receipt".getBytes());
        worker.scan();assertThat(state()).isEqualTo("SENT");verify(rechargeDocuments,never()).invoice(any(),any());
        var capture=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);verify(sender).send(capture.capture());
        var output=new java.io.ByteArrayOutputStream();capture.getValue().writeTo(output);assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("receipt-RCG-7.pdf").doesNotContain("invoice-RCG-7.pdf");
    }
    @Test void missingSmtpConfigurationIsRetriedWithoutSending() {ReflectionTestUtils.setField(worker,"mailHost","");worker.scan();assertThat(state()).isEqualTo("RETRY");verify(sender,never()).send(any(MimeMessage.class));}
    private String bodyText(jakarta.mail.Part part) throws Exception {
        Object body=part.getContent();if(body instanceof String text)return text;
        if(body instanceof jakarta.mail.Multipart parts){StringBuilder text=new StringBuilder();for(int i=0;i<parts.getCount();i++)text.append(bodyText(parts.getBodyPart(i)));return text.toString();}return "";
    }
    @Test void localSimulationNeverSends() {ReflectionTestUtils.setField(worker,"localTest",true);worker.scan();assertThat(state()).isEqualTo("PENDING");verifyNoInteractions(sender);}
}
