package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.MoneyAmount;
import com.transit.dto.RechargeOrderRequest;
import com.transit.mapper.WalletRechargeOrderMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.User;
import com.transit.model.WalletRechargeOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RechargeOrderService {
    private static final long WALLET_SCALE = 10_000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final WalletRechargeOrderMapper mapper;
    private final JdbcTemplate jdbcTemplate;
    private final PaymentIntentService paymentIntentService;
    private final AccountVerificationPolicy verificationPolicy;

    @Value("${payment.documents.merchant.legal-name:}") private String merchantName;
    @Value("${payment.documents.merchant.address-line-1:}") private String merchantAddress1;
    @Value("${payment.documents.merchant.address-line-2:}") private String merchantAddress2;
    @Value("${payment.documents.merchant.country:}") private String merchantCountry;
    @Value("${payment.documents.merchant.contact-email:}") private String merchantEmail;
    @Value("${payment.documents.time-zone:Asia/Shanghai}") private String receiptTimeZone;
    @Value("${payment.documents.font-path:}") private String receiptFontPath;

    @Transactional
    public WalletRechargeOrder create(User user, RechargeOrderRequest request) {
        requireUser(user);
        verificationPolicy.requireComplete(user, "充值");
        if (request == null) throw badRequest("Recharge request is required");
        boolean custom = request.getCustomAmount() != null;
        if (custom && request.getPlanId() != null) throw badRequest("Choose either planId or customAmount");
        if (!custom && request.getPlanId() == null) throw badRequest("planId or customAmount is required");

        long base;
        BigDecimal bonusPercent;
        String planName;
        Long planId;
        if (custom) {
            BigDecimal amount;
            try {
                amount = request.getCustomAmount().setScale(2, RoundingMode.UNNECESSARY);
                base = amount.multiply(BigDecimal.valueOf(WALLET_SCALE)).longValueExact();
            } catch (ArithmeticException exception) {
                throw badRequest("customAmount must have at most 2 decimal places and be within the supported range");
            }
            if (amount.signum() <= 0 || base <= 0) throw badRequest("customAmount must be greater than 0");
            bonusPercent = BigDecimal.ZERO.setScale(3);
            planName = "自定义充值";
            planId = 0L;
        } else {
            List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT id,name,amount,bonus_percent FROM recharge_plans WHERE id=? AND enabled=TRUE", request.getPlanId());
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recharge plan not found or unavailable");
            Map<String,Object> plan = rows.get(0);
            base = ((Number)plan.get("amount")).longValue();
            bonusPercent = new BigDecimal(plan.get("bonus_percent").toString()).setScale(3, RoundingMode.UNNECESSARY);
            planName = String.valueOf(plan.get("name"));
            planId = request.getPlanId();
        }
        if (base <= 0 || bonusPercent.signum() < 0) throw conflict("Recharge plan amount is invalid");
        long bonus = BigDecimal.valueOf(base).multiply(bonusPercent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
        long total;
        try { total = Math.addExact(base, bonus); } catch (ArithmeticException overflow) { throw conflict("Recharge plan amount is outside the supported range"); }
        LocalDateTime now = now();
        String orderNo = "RCG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        WalletRechargeOrder order = new WalletRechargeOrder();
        order.setOrderNo(orderNo); order.setUserId(user.getId()); order.setPlanId(planId);
        order.setPlanName(planName); order.setPaymentAmountUnits(base); order.setBaseCreditUnits(base);
        order.setBonusPercent(bonusPercent); order.setBonusCreditUnits(bonus); order.setTotalCreditUnits(total);
        order.setStatus("PENDING"); order.setPaymentMethod(paymentMethod(request.getPaymentMethod()));
        boolean invoiceRequested = Boolean.TRUE.equals(request.getNeedInvoice())
                || (request.getNeedInvoice() == null && request.getBillingName() != null && !request.getBillingName().isBlank());
        if (invoiceRequested && !user.isInvoiceEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invoice access has not been enabled for this user");
        }
        order.setInvoiceRequested(invoiceRequested);
        order.setInvoiceNumber(orderNo); order.setReceiptNumber(receiptNumber());
        String fallbackEmail = user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail() : "billing@linknux.com";
        order.setContactEmail(invoiceRequested ? email(request.getContactEmail()) : emailOrFallback(request.getContactEmail(), fallbackEmail));
        order.setBillingName(invoiceRequested ? required(request.getBillingName(),"billingName",160) : optional(request.getBillingName(), "个人用户", 160));
        order.setBillingAddressLine1(invoiceRequested ? required(request.getBillingAddressLine1(),"billingAddressLine1",255) : optional(request.getBillingAddressLine1(), "未提供", 255));
        order.setBillingDistrict(invoiceRequested ? required(request.getBillingDistrict(),"billingDistrict",120) : optional(request.getBillingDistrict(), "未提供", 120));
        order.setBillingCity(invoiceRequested ? required(request.getBillingCity(),"billingCity",120) : optional(request.getBillingCity(), "未提供", 120));
        order.setBillingProvince(invoiceRequested ? required(request.getBillingProvince(),"billingProvince",120) : optional(request.getBillingProvince(), "未提供", 120));
        order.setBillingPostalCode(invoiceRequested ? required(request.getBillingPostalCode(),"billingPostalCode",20) : optional(request.getBillingPostalCode(), "000000", 20));
        order.setBillingCountry(invoiceRequested ? required(request.getBillingCountry(),"billingCountry",120) : optional(request.getBillingCountry(), "China", 120));
        order.setCreatedAt(now); order.setUpdatedAt(now); mapper.insert(order);
        PaymentIntent intent = paymentIntentService.create(user.getId(), PaymentBusinessSettlementService.WALLET_RECHARGE,
                order.getId(), orderNo, "Wallet recharge - " + order.getPlanName(),
                new MoneyAmount(base, "CNY", WALLET_SCALE), order.getPaymentMethod(), now.plusMinutes(15));
        order.setPaymentIntent(intent);
        return enrich(order);
    }

    public List<WalletRechargeOrder> list(User user) {
        requireUser(user);
        return mapper.selectList(new LambdaQueryWrapper<WalletRechargeOrder>().eq(WalletRechargeOrder::getUserId,user.getId())
                .orderByDesc(WalletRechargeOrder::getCreatedAt)).stream().map(this::enrich).toList();
    }

    public WalletRechargeOrder get(User user, Long id) {
        requireUser(user); WalletRechargeOrder order=mapper.selectById(id);
        if(order==null||!Objects.equals(order.getUserId(),user.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Recharge order not found");
        return enrich(order);
    }

    public byte[] invoice(User user, Long id) {
        WalletRechargeOrder order = get(user,id);
        if (!Boolean.TRUE.equals(order.getInvoiceRequested())) throw conflict("This order did not request an invoice");
        return document(order, false);
    }
    public byte[] receipt(User user, Long id) {
        WalletRechargeOrder order=get(user,id);
        if(!List.of("PAID","REFUNDED").contains(order.getStatus())||order.getPaidAt()==null) throw conflict("A receipt is available only after verified payment");
        return document(order,true);
    }

    @Scheduled(fixedDelayString = "${recharge-orders.expiry-scan-ms:60000}",
            initialDelayString = "${recharge-orders.expiry-initial-delay-ms:60000}")
    public void expirePendingOrders() {
        mapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WalletRechargeOrder>()
                .set(WalletRechargeOrder::getStatus,"EXPIRED").set(WalletRechargeOrder::getUpdatedAt,now())
                .eq(WalletRechargeOrder::getStatus,"PENDING").lt(WalletRechargeOrder::getCreatedAt,now().minusMinutes(15)));
    }

    private byte[] document(WalletRechargeOrder order, boolean receipt) {
        LocalDateTime date=receipt&&order.getPaidAt()!=null?order.getPaidAt():order.getCreatedAt();
        String formatted=date.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.of(receiptTimeZone))
                .format(DateTimeFormatter.ofPattern("MMMM d, uuuu",Locale.ENGLISH));
        String amount=BigDecimal.valueOf(order.getPaymentAmountUnits()).divide(BigDecimal.valueOf(WALLET_SCALE),2,RoundingMode.HALF_UP).toPlainString()+" CNY";
        String description="Wallet recharge: "+order.getPlanName();
        BillingPdfRenderer.DocumentData data=new BillingPdfRenderer.DocumentData(order.getInvoiceNumber(),order.getReceiptNumber(),formatted,
                merchantName,merchantAddress1,merchantAddress2,merchantCountry,merchantEmail,
                order.getBillingName(),order.getBillingAddressLine1(),order.getBillingDistrict(),order.getBillingCity(),order.getBillingProvince(),
                order.getBillingPostalCode(),order.getBillingCountry(),order.getContactEmail(),description,amount,
                "alipay".equals(order.getPaymentMethod())?"Alipay":"WeChat Pay",
                order.getBonusCreditUnits()>0?"Bonus wallet credit (not charged)":"",
                order.getBonusCreditUnits()>0?moneyUnits(order.getBonusCreditUnits())+" CNY":"");
        BillingPdfRenderer renderer=new BillingPdfRenderer(receiptFontPath);
        return receipt?renderer.renderReceipt(data):renderer.renderInvoice(data);
    }

    private WalletRechargeOrder enrich(WalletRechargeOrder order){
        order.setPaymentMoney(new MoneyAmount(order.getPaymentAmountUnits(),"CNY",WALLET_SCALE));
        order.setBaseCreditMoney(new MoneyAmount(order.getBaseCreditUnits(),"CNY",WALLET_SCALE));
        order.setBonusCreditMoney(new MoneyAmount(order.getBonusCreditUnits(),"CNY",WALLET_SCALE));
        order.setTotalCreditMoney(new MoneyAmount(order.getTotalCreditUnits(),"CNY",WALLET_SCALE));
        try { order.setPaymentIntent(paymentIntentService.getByBusiness(PaymentBusinessSettlementService.WALLET_RECHARGE,order.getId())); }
        catch(ResponseStatusException ignored){ order.setPaymentIntent(null); }
        return order;
    }
    private String moneyUnits(long value){return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(WALLET_SCALE),4,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    private String receiptNumber(){return String.format(Locale.ROOT,"%04d-%04d-%04d",RANDOM.nextInt(10000),RANDOM.nextInt(10000),RANDOM.nextInt(10000));}
    private String paymentMethod(String raw){String v=required(raw,"paymentMethod",20).toLowerCase(Locale.ROOT);if(!v.equals("alipay")&&!v.equals("wxpay"))throw badRequest("paymentMethod must be alipay or wxpay");return v;}
    private String email(String raw){String v=required(raw,"contactEmail",255);if(!v.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))throw badRequest("contactEmail is invalid");return v;}
    private String emailOrFallback(String raw,String fallback){return raw==null||raw.isBlank()?email(fallback):email(raw);}
    private String optional(String raw,String fallback,int max){if(raw==null||raw.isBlank())return fallback;String v=raw.trim();if(v.length()>max)throw badRequest("billing field is too long");return v;}
    private String required(String raw,String field,int max){if(raw==null||raw.isBlank())throw badRequest(field+" is required");String v=raw.trim();if(v.length()>max)throw badRequest(field+" is too long");return v;}
    private void requireUser(User user){if(user==null||user.getId()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authenticated user is required");}
    private LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC);}
    private ResponseStatusException badRequest(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
}
