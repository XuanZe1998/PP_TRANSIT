package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.transit.dto.MoneyAmount;
import com.transit.mapper.PaymentIntentMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.ServiceOrder;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentIntentService {
    private final PaymentIntentMapper mapper;
    private final MoneyService moneyService;
    private final AnyiPayClient anyiPayClient;
    private final PaymentBusinessSettlementService settlementService;

    @Value("${payment.local-test-mode:false}")
    private boolean localTestMode;

    @Transactional
    public PaymentIntent ensureServiceIntent(ServiceOrder order) {
        PaymentIntent existing = findByBusiness(PaymentBusinessSettlementService.SERVICE_ORDER, order.getId());
        if (existing != null) return enrich(existing);
        MoneyAmount source = new MoneyAmount(order.getAmountCents(), order.getCurrency(), 100);
        return create(order.getUserId(), PaymentBusinessSettlementService.SERVICE_ORDER, order.getId(),
                order.getOrderNo(), order.getProductName(), source, order.getPaymentMethod(), order.getReservationExpiresAt());
    }

    @Transactional
    public PaymentIntent create(Long userId, String businessType, Long businessId, String orderNo,
                                String description, MoneyAmount source, String paymentMethod,
                                LocalDateTime expiresAt) {
        PaymentIntent existing = findByBusiness(businessType, businessId);
        if (existing != null) return enrich(existing);
        MoneyService.SettlementQuote quote = moneyService.settlementQuote(source);
        PaymentIntent intent = new PaymentIntent();
        intent.setOrderNo(required(orderNo, "orderNo", 80));
        intent.setUserId(Objects.requireNonNull(userId));
        intent.setBusinessType(required(businessType, "businessType", 40));
        intent.setBusinessId(Objects.requireNonNull(businessId));
        intent.setDescription(required(description, "description", 160));
        intent.setSourceAmount(source.amount()); intent.setSourceCurrency(source.currency()); intent.setSourceScale(source.scale());
        intent.setSettlementAmountCents(quote.money().amount()); intent.setSettlementCurrency("CNY");
        intent.setExchangeRate(quote.exchangeRate()); intent.setPaymentMethod(paymentMethod(paymentMethod));
        intent.setStatus("PENDING"); intent.setPaymentProvider("ANYIPAY"); intent.setRefundStatus("NONE");
        intent.setExpiresAt(expiresAt); intent.setCreatedAt(now()); intent.setUpdatedAt(now());
        mapper.insert(intent);
        return enrich(intent);
    }

    public PaymentIntent getUserIntent(User user, Long id) {
        if (user == null || user.getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is required");
        PaymentIntent intent = mapper.selectById(id);
        if (intent == null || !Objects.equals(intent.getUserId(), user.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment intent not found");
        return enrich(intent);
    }

    public PaymentIntent getByBusiness(String type, Long businessId) {
        PaymentIntent intent = findByBusiness(type, businessId);
        if (intent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment intent not found");
        return enrich(intent);
    }

    @Transactional
    public PaymentIntent start(User user, Long id) {
        PaymentIntent intent = getUserIntent(user, id);
        if ("PAID".equals(intent.getStatus()) || "REFUNDED".equals(intent.getStatus())) return intent;
        if (!"PENDING".equals(intent.getStatus())) throw conflict("Payment cannot be started while intent status is " + intent.getStatus());
        if (localTestMode) return markPaid(intent, "LOCAL-" + intent.getOrderNo(), intent.getPaymentMethod(), "LOCAL_TEST");
        if (!anyiPayClient.isEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AnyiPay is disabled");
        String url = anyiPayClient.createPagePaymentUrl(intent.getOrderNo(), intent.getDescription(),
                moneyService.gatewayMoney(intent.getSettlementAmountCents()), "payment-intent:" + intent.getId(), intent.getPaymentMethod());
        intent.setPaymentType("page"); intent.setPaymentUrl(url); intent.setUpdatedAt(now()); mapper.updateById(intent);
        return enrich(intent);
    }

    @Transactional
    public PaymentIntent query(User user, Long id) {
        PaymentIntent intent = getUserIntent(user, id);
        if ("PAID".equals(intent.getStatus()) || "REFUNDED".equals(intent.getStatus())) return intent;
        JsonNode result = anyiPayClient.queryPayment(intent.getProviderTradeNo(), blank(intent.getProviderTradeNo()) ? intent.getOrderNo() : null);
        validateFacts(intent, result.path("out_trade_no").asText(), result.path("money").asText(),
                result.path("param").asText(), result.path("type").asText());
        if (result.path("status").asInt(0) == 1) {
            return markPaid(intent, required(result.path("trade_no").asText(), "trade_no", 120),
                    result.path("type").asText(), "QUERY");
        }
        return enrich(intent);
    }

    @Transactional
    public void receiveNotification(Map<String, String> callback) {
        if (callback == null || !"TRADE_SUCCESS".equals(callback.get("trade_status"))) throw badRequest("Unsupported payment notification status");
        String orderNo = required(callback.get("out_trade_no"), "out_trade_no", 80);
        PaymentIntent intent = mapper.selectOne(new LambdaQueryWrapper<PaymentIntent>().eq(PaymentIntent::getOrderNo, orderNo).last("LIMIT 1"));
        if (intent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment intent not found");
        validateFacts(intent, orderNo, callback.get("money"), callback.get("param"), callback.get("type"));
        markPaid(intent, required(callback.get("trade_no"), "trade_no", 120), callback.get("type"), "WEBHOOK");
    }

    public PaymentIntent refund(Long id, String reason) {
        PaymentIntent intent = mapper.selectById(id);
        if (intent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment intent not found");
        String refundState=intent.getRefundStatus()==null?"NONE":intent.getRefundStatus();
        if (!"PAID".equals(intent.getStatus()) || !("NONE".equals(refundState) || "FAILED".equals(refundState))) throw conflict("Only an unrefunded paid intent can be refunded");
        String safeReason = required(reason, "reason", 500);
        if (!localTestMode && !anyiPayClient.isMoneyMutationsEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Refunds require ANYIPAY_ALLOW_MONEY_MUTATIONS=true");
        }
        String refundNo = "RFD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        LocalDateTime claimTime=now();
        int claimed=mapper.update(null,new LambdaUpdateWrapper<PaymentIntent>()
                .set(PaymentIntent::getRefundStatus,"PREPARING").set(PaymentIntent::getRefundNo,refundNo)
                .set(PaymentIntent::getRefundReason,safeReason).set(PaymentIntent::getUpdatedAt,claimTime)
                .eq(PaymentIntent::getId,intent.getId()).eq(PaymentIntent::getStatus,"PAID")
                .and(state->state.isNull(PaymentIntent::getRefundStatus).or().in(PaymentIntent::getRefundStatus,"NONE","FAILED")));
        if(claimed!=1) throw conflict("Refund is already being processed");
        intent.setRefundStatus("PREPARING"); intent.setRefundNo(refundNo); intent.setRefundReason(safeReason); intent.setUpdatedAt(claimTime);
        try {
            settlementService.prepareRefund(intent);
        } catch (RuntimeException preparationFailure) {
            intent.setRefundStatus("FAILED"); intent.setUpdatedAt(now()); mapper.updateById(intent);
            throw preparationFailure;
        }
        intent.setRefundStatus("REFUND_PENDING"); intent.setUpdatedAt(now()); mapper.updateById(intent);
        try {
            String providerRefundNo = "LOCAL_TEST";
            if (!localTestMode) {
                JsonNode result = anyiPayClient.refund(intent.getProviderTradeNo(), blank(intent.getProviderTradeNo()) ? intent.getOrderNo() : null,
                        moneyService.gatewayMoney(intent.getSettlementAmountCents()), refundNo);
                providerRefundNo = result.path("refund_no").asText(result.path("out_refund_no").asText(refundNo));
            }
            intent.setProviderRefundNo(providerRefundNo); intent.setRefundStatus("REFUNDED"); intent.setStatus("REFUNDED");
            intent.setRefundedAt(now()); intent.setUpdatedAt(now());
            settlementService.completeRefund(intent); mapper.updateById(intent);
        } catch (RuntimeException failure) {
            settlementService.compensateRefund(intent);
            intent.setRefundStatus("FAILED"); intent.setUpdatedAt(now()); mapper.updateById(intent);
            throw failure;
        }
        return enrich(intent);
    }

    public boolean refundsEnabled() { return localTestMode || anyiPayClient.isMoneyMutationsEnabled(); }
    public List<PaymentIntent> listAll() { return mapper.selectList(new LambdaQueryWrapper<PaymentIntent>().orderByDesc(PaymentIntent::getCreatedAt).last("LIMIT 500")).stream().map(this::enrich).toList(); }

    private PaymentIntent markPaid(PaymentIntent intent, String tradeNo, String type, String source) {
        PaymentIntent latest = mapper.selectById(intent.getId());
        if ("PAID".equals(latest.getStatus()) || "REFUNDED".equals(latest.getStatus())) {
            if (!blank(latest.getProviderTradeNo()) && !latest.getProviderTradeNo().equals(tradeNo)) throw conflict("Payment evidence does not match the settled intent");
            return enrich(latest);
        }
        if (!"PENDING".equals(latest.getStatus())) throw conflict("Payment intent cannot be settled from " + latest.getStatus());
        latest.setProviderTradeNo(tradeNo); latest.setPaymentType(blank(type) ? latest.getPaymentMethod() : type);
        latest.setStatus("PAID"); latest.setPaidAt(now()); latest.setUpdatedAt(now()); latest.setInternalState(source);
        int changed = mapper.update(latest, new LambdaUpdateWrapper<PaymentIntent>().eq(PaymentIntent::getId, latest.getId()).eq(PaymentIntent::getStatus, "PENDING"));
        if (changed != 1) return enrich(mapper.selectById(latest.getId()));
        settlementService.settle(latest);
        return enrich(latest);
    }

    private void validateFacts(PaymentIntent intent, String orderNo, String rawMoney, String param, String type) {
        if (!intent.getOrderNo().equals(orderNo)) throw badRequest("Gateway order number does not match payment intent");
        try {
            long cents = new BigDecimal(required(rawMoney, "money", 40)).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            if (cents != intent.getSettlementAmountCents()) throw badRequest("Gateway amount does not match payment intent");
        } catch (ArithmeticException | NumberFormatException exception) { throw badRequest("Gateway amount is invalid"); }
        boolean current=("payment-intent:"+intent.getId()).equals(param);
        if (!current) throw badRequest("Gateway business namespace does not match payment intent");
        if (!blank(type) && !intent.getPaymentMethod().equals(type)) throw badRequest("Gateway payment method does not match payment intent");
    }

    private PaymentIntent findByBusiness(String type, Long id) {
        return mapper.selectOne(new LambdaQueryWrapper<PaymentIntent>().eq(PaymentIntent::getBusinessType, type).eq(PaymentIntent::getBusinessId, id).last("LIMIT 1"));
    }
    public PaymentIntent enrich(PaymentIntent intent) {
        if (intent != null) {
            intent.setSourceMoney(new MoneyAmount(intent.getSourceAmount(), intent.getSourceCurrency(), intent.getSourceScale()));
            intent.setSettlementMoney(MoneyAmount.cents(intent.getSettlementAmountCents(), intent.getSettlementCurrency()));
        }
        return intent;
    }
    private String paymentMethod(String raw) { String value=required(raw,"paymentMethod",20).toLowerCase(Locale.ROOT); if(!value.equals("alipay")&&!value.equals("wxpay")) throw badRequest("paymentMethod must be alipay or wxpay"); return value; }
    private String required(String raw,String field,int max){ if(raw==null||raw.isBlank()) throw badRequest(field+" is required"); String v=raw.trim(); if(v.length()>max) throw badRequest(field+" is too long"); return v; }
    private boolean blank(String value){ return value==null||value.isBlank(); }
    private LocalDateTime now(){ return LocalDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException badRequest(String message){ return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message){ return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
