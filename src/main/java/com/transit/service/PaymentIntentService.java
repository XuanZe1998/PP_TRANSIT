package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.transit.dto.MoneyAmount;
import com.transit.dto.PageResponse;
import com.transit.mapper.PaymentIntentMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.ServiceOrder;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentIntentService {
    private final PaymentIntentMapper mapper;
    private final MoneyService moneyService;
    private final MaPayClient maPayClient;
    private final PaymentBusinessSettlementService settlementService;
    private final TransactionTemplate transactionTemplate;

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
        intent.setSourceAmount(source.amount());
        intent.setSourceCurrency(source.currency());
        intent.setSourceScale(source.scale());
        intent.setSettlementAmountCents(quote.money().amount());
        intent.setSettlementCurrency("CNY");
        intent.setExchangeRate(quote.exchangeRate());
        intent.setPaymentMethod(paymentMethod(paymentMethod));
        intent.setStatus("PENDING");
        intent.setPaymentProvider("MAPAY");
        intent.setExpiresAt(expiresAt);
        intent.setCreatedAt(now());
        intent.setUpdatedAt(now());
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

    public StartResponse start(User user, Long id, String clientIp, String device) {
        PaymentIntent intent = getUserIntent(user, id);
        if ("PAID".equals(intent.getStatus())) return response(intent);
        if (!"PENDING".equals(intent.getStatus())) throw conflict("Payment cannot be started while intent status is " + intent.getStatus());
        if (!"MAPAY".equals(intent.getPaymentProvider())) throw conflict("Legacy payment intents cannot be restarted");
        if (intent.getExpiresAt() != null && intent.getExpiresAt().isBefore(now())) {
            expire(intent);
            throw conflict("Payment intent has expired");
        }
        if (!blank(intent.getPaymentUrl()) && !blank(intent.getPaymentActionType())) return response(intent);
        if (localTestMode) {
            return response(markPaid(intent, "LOCAL-" + intent.getOrderNo(), intent.getPaymentMethod(), "LOCAL_TEST"));
        }
        MaPayClient.PaymentStart started = maPayClient.start(intent.getOrderNo(), intent.getDescription(),
                moneyService.gatewayMoney(intent.getSettlementAmountCents()), namespace(intent),
                intent.getPaymentMethod(), clientIp, normalizeDevice(device));
        validateMoney(intent, started.money());
        intent.setProviderTradeNo(required(started.tradeNo(), "trade_no", 120));
        intent.setPaymentActionType(started.action().type());
        intent.setPaymentType(intent.getPaymentMethod());
        intent.setPaymentUrl(started.action().url());
        intent.setLastError(null);
        intent.setUpdatedAt(now());
        mapper.updateById(intent);
        return response(enrich(intent));
    }

    public PaymentIntent status(User user, Long id) {
        return getUserIntent(user, id);
    }

    @Transactional
    public PaymentIntent query(User user, Long id) {
        return query(getUserIntent(user, id));
    }

    @Transactional
    public PaymentIntent queryAdmin(Long id) {
        PaymentIntent intent = mapper.selectById(id);
        if (intent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment intent not found");
        return query(intent);
    }

    @Transactional
    public void receiveNotification(java.util.Map<String, String> callback) {
        if (callback == null || !"TRADE_SUCCESS".equals(callback.get("trade_status"))) throw badRequest("Unsupported payment notification status");
        String orderNo = required(callback.get("out_trade_no"), "out_trade_no", 80);
        PaymentIntent intent = mapper.selectOne(new LambdaQueryWrapper<PaymentIntent>()
                .eq(PaymentIntent::getOrderNo, orderNo).last("LIMIT 1"));
        if (intent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment intent not found");
        validateFacts(intent, orderNo, callback.get("money"), callback.get("param"), callback.get("type"));
        markPaid(intent, required(callback.get("trade_no"), "trade_no", 120), callback.get("type"), "WEBHOOK");
    }

    public PageResponse<PaymentIntent> listPage(int page, int size, String query, String status) {
        validatePage(page, size);
        long total = mapper.selectCount(filter(query, status));
        List<PaymentIntent> items = mapper.selectList(filter(query, status)
                        .orderByDesc(PaymentIntent::getCreatedAt)
                        .last("LIMIT " + size + " OFFSET " + ((page - 1L) * size)))
                .stream().map(this::enrich).toList();
        PageResponse<PaymentIntent> response = new PageResponse<>();
        response.setPage(page);
        response.setSize(size);
        response.setTotal(total);
        response.setItems(items);
        return response;
    }

    @Scheduled(fixedDelayString = "${mapay.reconcile-interval-ms:30000}",
            initialDelayString = "${mapay.reconcile-initial-delay-ms:30000}")
    public void reconcilePending() {
        if (localTestMode || !maPayClient.isEnabled()) return;
        List<PaymentIntent> pending = mapper.selectList(new LambdaQueryWrapper<PaymentIntent>()
                .eq(PaymentIntent::getPaymentProvider, "MAPAY")
                .in(PaymentIntent::getStatus, "PENDING", "EXPIRED")
                .isNotNull(PaymentIntent::getProviderTradeNo)
                .gt(PaymentIntent::getCreatedAt, now().minusDays(7))
                .orderByAsc(PaymentIntent::getUpdatedAt).last("LIMIT 20"));
        for (PaymentIntent intent : pending) {
            try {
                transactionTemplate.executeWithoutResult(ignored -> query(intent));
            } catch (RuntimeException exception) {
                String message = safeError(exception);
                mapper.update(null, new LambdaUpdateWrapper<PaymentIntent>()
                        .set(PaymentIntent::getLastError, message)
                        .set(PaymentIntent::getLastQueriedAt, now())
                        .eq(PaymentIntent::getId, intent.getId())
                        .in(PaymentIntent::getStatus, "PENDING", "EXPIRED"));
                log.warn("MaPay reconciliation failed for payment intent {}: {}", intent.getId(), message);
            }
        }
    }

    @Scheduled(fixedDelayString = "${mapay.expiry-scan-ms:60000}",
            initialDelayString = "${mapay.expiry-initial-delay-ms:60000}")
    public void expirePending() {
        mapper.update(null, new LambdaUpdateWrapper<PaymentIntent>()
                .set(PaymentIntent::getStatus, "EXPIRED")
                .set(PaymentIntent::getUpdatedAt, now())
                .eq(PaymentIntent::getStatus, "PENDING")
                .lt(PaymentIntent::getExpiresAt, now()));
    }

    private PaymentIntent query(PaymentIntent intent) {
        if ("PAID".equals(intent.getStatus())) return enrich(intent);
        if (!List.of("PENDING", "EXPIRED").contains(intent.getStatus())) return enrich(intent);
        if (!"MAPAY".equals(intent.getPaymentProvider())) throw conflict("Legacy payment intents cannot be queried through MaPay");
        MaPayClient.OrderResult result = maPayClient.query(intent.getOrderNo());
        validateFacts(intent, result.outTradeNo(), result.money(), result.param(), result.type());
        intent.setLastQueriedAt(now());
        intent.setLastError(null);
        mapper.updateById(intent);
        if (result.paid()) return markPaid(intent, required(result.tradeNo(), "trade_no", 120), result.type(), "QUERY");
        return enrich(intent);
    }

    private PaymentIntent markPaid(PaymentIntent intent, String tradeNo, String type, String source) {
        PaymentIntent latest = mapper.selectById(intent.getId());
        if ("PAID".equals(latest.getStatus())) {
            if (!blank(latest.getProviderTradeNo()) && !latest.getProviderTradeNo().equals(tradeNo)) throw conflict("Payment evidence does not match the settled intent");
            return enrich(latest);
        }
        if (!List.of("PENDING", "EXPIRED").contains(latest.getStatus())) throw conflict("Payment intent cannot be settled from " + latest.getStatus());
        PaymentIntent duplicate = mapper.selectOne(new LambdaQueryWrapper<PaymentIntent>()
                .eq(PaymentIntent::getProviderTradeNo, tradeNo)
                .ne(PaymentIntent::getId, latest.getId()).last("LIMIT 1"));
        if (duplicate != null) throw conflict("Provider transaction is already attached to another payment intent");
        String prior = latest.getStatus();
        latest.setProviderTradeNo(tradeNo);
        latest.setPaymentType(blank(type) ? latest.getPaymentMethod() : type);
        latest.setStatus("PAID");
        latest.setPaidAt(now());
        latest.setUpdatedAt(now());
        latest.setLastError(null);
        latest.setInternalState(source);
        int changed = mapper.update(latest, new LambdaUpdateWrapper<PaymentIntent>()
                .eq(PaymentIntent::getId, latest.getId()).eq(PaymentIntent::getStatus, prior));
        if (changed != 1) return enrich(mapper.selectById(latest.getId()));
        settlementService.settle(latest);
        return enrich(latest);
    }

    private void validateFacts(PaymentIntent intent, String orderNo, String rawMoney, String param, String type) {
        if (!intent.getOrderNo().equals(orderNo)) throw badRequest("Gateway order number does not match payment intent");
        validateMoney(intent, rawMoney);
        if (!namespace(intent).equals(param)) throw badRequest("Gateway business namespace does not match payment intent");
        if (!blank(type) && !intent.getPaymentMethod().equals(type)) throw badRequest("Gateway payment method does not match payment intent");
    }

    private void validateMoney(PaymentIntent intent, String rawMoney) {
        try {
            long cents = new BigDecimal(required(rawMoney, "money", 40)).movePointRight(2)
                    .setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            if (cents != intent.getSettlementAmountCents()) throw badRequest("Gateway amount does not match payment intent");
        } catch (ArithmeticException | NumberFormatException exception) {
            throw badRequest("Gateway amount is invalid");
        }
    }

    private LambdaQueryWrapper<PaymentIntent> filter(String query, String status) {
        LambdaQueryWrapper<PaymentIntent> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) wrapper.eq(PaymentIntent::getStatus, required(status, "status", 32).toUpperCase(Locale.ROOT));
        if (query != null && !query.isBlank()) {
            String needle = required(query, "query", 160);
            wrapper.and(group -> group.like(PaymentIntent::getOrderNo, needle)
                    .or().like(PaymentIntent::getProviderTradeNo, needle)
                    .or().like(PaymentIntent::getDescription, needle));
        }
        return wrapper;
    }

    private void validatePage(int page, int size) {
        if (page < 1 || page > 1_000_000) throw badRequest("page is out of range");
        if (!List.of(10, 20, 50, 100).contains(size)) throw badRequest("size must be one of 10, 20, 50, 100");
    }

    private PaymentIntent findByBusiness(String type, Long id) {
        return mapper.selectOne(new LambdaQueryWrapper<PaymentIntent>()
                .eq(PaymentIntent::getBusinessType, type).eq(PaymentIntent::getBusinessId, id).last("LIMIT 1"));
    }

    public PaymentIntent enrich(PaymentIntent intent) {
        if (intent != null) {
            intent.setSourceMoney(new MoneyAmount(intent.getSourceAmount(), intent.getSourceCurrency(), intent.getSourceScale()));
            intent.setSettlementMoney(MoneyAmount.cents(intent.getSettlementAmountCents(), intent.getSettlementCurrency()));
        }
        return intent;
    }

    private StartResponse response(PaymentIntent intent) {
        PaymentAction action = blank(intent.getPaymentActionType()) || blank(intent.getPaymentUrl())
                ? null : new PaymentAction(intent.getPaymentActionType(), intent.getPaymentUrl());
        return new StartResponse(enrich(intent), action, intent.getExpiresAt());
    }

    private void expire(PaymentIntent intent) {
        mapper.update(null, new LambdaUpdateWrapper<PaymentIntent>()
                .set(PaymentIntent::getStatus, "EXPIRED").set(PaymentIntent::getUpdatedAt, now())
                .eq(PaymentIntent::getId, intent.getId()).eq(PaymentIntent::getStatus, "PENDING"));
        intent.setStatus("EXPIRED");
    }

    private String namespace(PaymentIntent intent) { return "payment-intent:" + intent.getId(); }
    private String normalizeDevice(String device) {
        if (device == null || device.isBlank()) return "pc";
        String value = device.trim().toLowerCase(Locale.ROOT);
        return List.of("pc", "mobile", "qq", "wechat", "alipay").contains(value) ? value : "pc";
    }
    private String paymentMethod(String raw) {
        String value = required(raw, "paymentMethod", 20).toLowerCase(Locale.ROOT);
        if (!value.equals("alipay") && !value.equals("wxpay")) throw badRequest("paymentMethod must be alipay or wxpay");
        return value;
    }
    private String required(String raw, String field, int max) {
        if (raw == null || raw.isBlank()) throw badRequest(field + " is required");
        String value = raw.trim();
        if (value.length() > max) throw badRequest(field + " is too long");
        return value;
    }
    private String safeError(RuntimeException exception) {
        String value = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        value = value.replaceAll("[\\r\\n\\t]", " ");
        return value.substring(0, Math.min(500, value.length()));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record PaymentAction(String type, String url) {}
    public record StartResponse(PaymentIntent intent, PaymentAction action, LocalDateTime expiresAt) {}
}
