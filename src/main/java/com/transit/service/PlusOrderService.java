package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.transit.dto.PlusOrderRequest;
import com.transit.dto.PlusOrderResponse;
import com.transit.mapper.PlusOrderMapper;
import com.transit.mapper.PlusProductMapper;
import com.transit.model.PlusOrder;
import com.transit.model.PlusProduct;
import com.transit.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PlusOrderService {

    private static final String PENDING = "PENDING";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String PAID = "PAID";
    private static final String FULFILLED = "FULFILLED";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";
    private static final Set<String> KNOWN_STATUSES = Set.of(PENDING, CONFIRMED, PAID, FULFILLED, FAILED, CANCELLED);
    private static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.of(
            PENDING, Set.of(CONFIRMED, PAID, FAILED, CANCELLED),
            CONFIRMED, Set.of(PAID, FAILED, CANCELLED),
            PAID, Set.of(FULFILLED),
            FULFILLED, Set.of(),
            FAILED, Set.of(),
            CANCELLED, Set.of()
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private final PlusProductMapper productMapper;
    private final PlusOrderMapper orderMapper;
    private final AnyiPayClient anyiPayClient;

    @Autowired
    public PlusOrderService(PlusProductMapper productMapper,
                            PlusOrderMapper orderMapper,
                            AnyiPayClient anyiPayClient) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.anyiPayClient = anyiPayClient;
    }

    // Focused unit tests that do not exercise an external payment provider use this constructor.
    PlusOrderService(PlusProductMapper productMapper, PlusOrderMapper orderMapper) {
        this(productMapper, orderMapper, null);
    }

    @Value("${plus.receipt.merchant.display-name:}")
    private String merchantDisplayName;

    @Value("${plus.receipt.merchant.legal-name:}")
    private String merchantLegalName;

    @Value("${plus.receipt.merchant.address-line-1:}")
    private String merchantAddressLine1;

    @Value("${plus.receipt.merchant.address-line-2:}")
    private String merchantAddressLine2;

    @Value("${plus.receipt.merchant.contact-email:}")
    private String merchantContactEmail;

    @Value("${plus.receipt.merchant.registration-id:}")
    private String merchantRegistrationId;

    @Value("${plus.receipt.time-zone:UTC}")
    private String receiptTimeZone;

    @Value("${plus.payment.local-test-mode:false}")
    private boolean localTestPaymentMode;

    @Value("${anyipay.usd-cny-rate:6.76693506}")
    private BigDecimal usdCnyPaymentRate = new BigDecimal("6.76693506");

    public List<PlusProduct> listEnabledProducts() {
        return productMapper.selectList(new LambdaQueryWrapper<PlusProduct>()
                .eq(PlusProduct::getEnabled, true)
                .orderByDesc(PlusProduct::getCreatedAt));
    }

    public List<PlusProduct> listAllProducts() {
        return productMapper.selectList(new LambdaQueryWrapper<PlusProduct>()
                .orderByDesc(PlusProduct::getCreatedAt));
    }

    public PlusProduct createProduct(PlusProduct request) {
        if (request == null) {
            throw badRequest("Product body is required");
        }
        PlusProduct product = PlusProduct.builder()
                .name(requiredText(request.getName(), "name", 160))
                .description(optionalText(request.getDescription(), "description", 1000))
                .imageUrl(optionalHttpUrl(request.getImageUrl()))
                .priceCents(nonNegative(request.getPriceCents(), "priceCents"))
                .serviceFeeCents(nonNegative(request.getServiceFeeCents(), "serviceFeeCents"))
                .currency(normalizeCurrency(request.getCurrency()))
                .enabled(request.getEnabled() == null || request.getEnabled())
                .createdAt(nowUtc())
                .build();
        productMapper.insert(product);
        return product;
    }

    public PlusProduct updateProduct(Long id, PlusProduct request) {
        PlusProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        if (request == null) {
            throw badRequest("Product body is required");
        }
        product.setName(requiredText(request.getName(), "name", 160));
        product.setDescription(optionalText(request.getDescription(), "description", 1000));
        product.setImageUrl(optionalHttpUrl(request.getImageUrl()));
        product.setPriceCents(nonNegative(request.getPriceCents(), "priceCents"));
        product.setServiceFeeCents(nonNegative(request.getServiceFeeCents(), "serviceFeeCents"));
        product.setCurrency(normalizeCurrency(request.getCurrency() == null ? product.getCurrency() : request.getCurrency()));
        if (request.getEnabled() != null) {
            product.setEnabled(request.getEnabled());
        }
        productMapper.updateById(product);
        return product;
    }

    public void deleteProduct(Long id) {
        PlusProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        productMapper.deleteById(id);
    }

    public PlusOrderResponse createOrder(User user, PlusOrderRequest request) {
        if (user == null || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is required");
        }
        if (request == null || request.getProductId() == null) {
            throw badRequest("productId is required");
        }
        PlusProduct product = productMapper.selectById(request.getProductId());
        if (product == null || !Boolean.TRUE.equals(product.getEnabled())) {
            // Do not reveal whether a disabled commercial product still exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found or unavailable");
        }

        long unitPriceCents = nonNegative(product.getPriceCents(), "product.priceCents");
        long serviceFeeCents = nonNegative(product.getServiceFeeCents(), "product.serviceFeeCents");
        long amountCents;
        try {
            amountCents = Math.addExact(unitPriceCents, serviceFeeCents);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product price is outside the supported range");
        }
        String contactEmail = normalizeContactEmail(request.getContactEmail(), user);
        String orderCurrency = normalizeCurrency(product.getCurrency());
        PaymentQuote paymentQuote = paymentQuote(amountCents, orderCurrency);
        LocalDateTime now = nowUtc();
        PlusOrder order = PlusOrder.builder()
                .orderNo(generateOrderNo())
                .userId(user.getId())
                .productId(product.getId())
                .productName(requiredText(product.getName(), "product.name", 160))
                .unitPriceCents(unitPriceCents)
                .serviceFeeCents(serviceFeeCents)
                .amountCents(amountCents)
                .currency(orderCurrency)
                .paymentAmountCents(paymentQuote.amountCents())
                .paymentCurrency(paymentQuote.currency())
                .exchangeRate(paymentQuote.exchangeRate())
                .status(PENDING)
                .contactEmail(contactEmail)
                .contactNote(optionalText(request.getContactNote(), "contactNote", 1000))
                .fulfillmentNote("Order created; payment has not yet been verified.")
                .createdAt(now)
                .updatedAt(now)
                .build();
        orderMapper.insert(order);

        return PlusOrderResponse.builder()
                .order(order)
                .message("待支付订单已创建，请在订单列表点击支付")
                .build();
    }

    public PlusOrderResponse startPayment(User user, Long id, String clientIp) {
        PlusOrder order = getUserOrder(user, id);
        String status = normalizeStoredStatus(order.getStatus());
        if (!Set.of(PENDING, CONFIRMED).contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment cannot be started while order status is " + status);
        }
        if (localTestPaymentMode) {
            String reference = "LOCAL-" + requiredText(order.getOrderNo(), "order.orderNo", 80);
            PlusOrder paid = markPaid(order, reference, "local-test", "LOCAL_TEST");
            return paymentResponse(paid, "本地模拟支付成功");
        }
        if (anyiPayClient == null || !anyiPayClient.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "支付通道未启用，请先配置聚合支付商户信息");
        }
        PaymentQuote paymentQuote = ensurePaymentQuote(order);
        String paymentUrl = anyiPayClient.createPagePaymentUrl(
                order.getOrderNo(),
                order.getProductName(),
                money(paymentQuote.amountCents()),
                "plus-order:" + order.getId());
        order.setPaymentProvider("ANYIPAY");
        order.setProviderTradeNo(null);
        order.setPaymentType("page");
        order.setPaymentUrl(optionalHttpUrl(paymentUrl, "paymentUrl", 2000));
        order.setUpdatedAt(nowUtc());
        orderMapper.updateById(order);
        return paymentResponse(order, "请前往支付页完成付款");
    }

    public PlusOrderResponse queryPayment(User user, Long id) {
        if (anyiPayClient == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AnyiPay is unavailable");
        }
        PlusOrder order = getUserOrder(user, id);
        JsonNode provider = anyiPayClient.queryPayment(order.getProviderTradeNo(),
                isBlank(order.getProviderTradeNo()) ? order.getOrderNo() : null);
        validateGatewayOrderFacts(order, provider.path("pid").asText(), provider.path("out_trade_no").asText(),
                provider.path("money").asText(), provider.path("param").asText());
        if (provider.path("status").asInt(0) == 1) {
            order = markPaid(order,
                    requiredProviderText(provider, "trade_no", 120),
                    provider.path("type").asText(null));
        }
        return paymentResponse(order, provider.path("status").asInt(0) == 1
                ? "支付已确认" : "尚未查询到成功付款");
    }

    public void receivePaymentNotification(Map<String, String> callback) {
        if (callback == null || !"TRADE_SUCCESS".equals(callback.get("trade_status"))) {
            throw badRequest("Unsupported payment notification status");
        }
        String orderNo = requiredText(callback.get("out_trade_no"), "out_trade_no", 80);
        PlusOrder order = orderMapper.selectOne(new LambdaQueryWrapper<PlusOrder>()
                .eq(PlusOrder::getOrderNo, orderNo)
                .last("LIMIT 1"));
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        validateGatewayOrderFacts(order, callback.get("pid"), orderNo, callback.get("money"), callback.get("param"));
        markPaid(order,
                requiredText(callback.get("trade_no"), "trade_no", 120),
                optionalText(callback.get("type"), "type", 40));
    }

    public List<PlusOrder> listUserOrders(User user) {
        requireUserId(user);
        return orderMapper.selectList(new LambdaQueryWrapper<PlusOrder>()
                .eq(PlusOrder::getUserId, user.getId())
                .orderByDesc(PlusOrder::getCreatedAt));
    }

    public List<PlusOrder> listAllOrders() {
        return orderMapper.selectList(new LambdaQueryWrapper<PlusOrder>().orderByDesc(PlusOrder::getCreatedAt));
    }

    public PlusOrder getUserOrder(User user, Long id) {
        requireUserId(user);
        PlusOrder order = orderMapper.selectById(id);
        if (order == null || !Objects.equals(order.getUserId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return order;
    }

    /**
     * Compatibility entry point used by the existing admin APIs. For PAID and
     * FULFILLED transitions, the note is also treated as the corresponding
     * evidence reference so legacy clients cannot create an evidence-free state.
     */
    public PlusOrder fulfillOrder(Long id, String status, String fulfillmentNote) {
        String normalized = normalizeStatus(status);
        String paymentReference = PAID.equals(normalized) ? fulfillmentNote : null;
        String fulfillmentReference = FULFILLED.equals(normalized) ? fulfillmentNote : null;
        return fulfillOrder(id, status, fulfillmentNote, paymentReference, fulfillmentReference);
    }

    public PlusOrder fulfillOrder(Long id,
                                  String status,
                                  String fulfillmentNote,
                                  String paymentReference,
                                  String fulfillmentReference) {
        PlusOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        String current = normalizeStoredStatus(order.getStatus());
        String target = status == null || status.isBlank() ? current : normalizeStatus(status);
        if (!current.equals(target) && !STATUS_TRANSITIONS.get(current).contains(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Illegal order status transition: " + current + " -> " + target);
        }

        String note = optionalText(fulfillmentNote, "fulfillmentNote", 1000);
        String paymentEvidence = optionalText(paymentReference, "paymentReference", 255);
        String fulfillmentEvidence = optionalText(fulfillmentReference, "fulfillmentReference", 255);
        LocalDateTime now = nowUtc();

        if (PAID.equals(target)) {
            paymentEvidence = firstNonBlank(paymentEvidence, order.getPaymentReference());
            if (paymentEvidence == null) {
                throw badRequest("paymentReference is required before an order can be marked PAID");
            }
            if (PAID.equals(current) && order.getPaymentReference() != null
                    && !order.getPaymentReference().equals(paymentEvidence)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment evidence is immutable after verification");
            }
            order.setPaymentReference(paymentEvidence);
            if (order.getPaidAt() == null) {
                order.setPaidAt(now);
            }
        }

        if (FULFILLED.equals(target)) {
            if (!hasPaymentEvidence(order)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Verified payment evidence is required before fulfillment");
            }
            fulfillmentEvidence = firstNonBlank(fulfillmentEvidence, order.getFulfillmentReference());
            if (fulfillmentEvidence == null) {
                throw badRequest("fulfillmentReference is required before an order can be marked FULFILLED");
            }
            if (FULFILLED.equals(current) && order.getFulfillmentReference() != null
                    && !order.getFulfillmentReference().equals(fulfillmentEvidence)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Fulfillment evidence is immutable after verification");
            }
            order.setFulfillmentReference(fulfillmentEvidence);
            if (order.getFulfilledAt() == null) {
                order.setFulfilledAt(now);
            }
        }

        order.setStatus(target);
        if (fulfillmentNote != null) {
            order.setFulfillmentNote(note);
        }
        order.setUpdatedAt(now);
        int updated = orderMapper.update(order, new LambdaUpdateWrapper<PlusOrder>()
                .eq(PlusOrder::getId, id)
                .eq(PlusOrder::getStatus, current));
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order changed concurrently; reload it before applying another status transition");
        }
        return order;
    }

    public void deleteOrder(Long id) {
        PlusOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        String status = normalizeStoredStatus(order.getStatus());
        if (PAID.equals(status) || FULFILLED.equals(status) || hasPaymentEvidence(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Paid or fulfilled orders must be retained for audit; cancel or refund them through a verified workflow");
        }
        orderMapper.deleteById(id);
    }

    public byte[] buildDownload(User user, Long id) {
        PlusOrder order = getUserOrder(user, id);
        validateReceiptEligibility(order);
        MerchantInfo merchant = requireMerchantInfo();
        byte[] receipt = buildReceiptPdf(user, order, merchant);
        order.setDownloadedAt(nowUtc());
        order.setUpdatedAt(nowUtc());
        orderMapper.updateById(order);
        return receipt;
    }

    private void validateReceiptEligibility(PlusOrder order) {
        String status = normalizeStoredStatus(order.getStatus());
        if (!PAID.equals(status) && !FULFILLED.equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A paid receipt is unavailable while order status is " + status);
        }
        if (!hasPaymentEvidence(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A paid receipt requires a verified payment reference and payment timestamp");
        }
        if (FULFILLED.equals(status) && !hasFulfillmentEvidence(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A fulfilled receipt requires a fulfillment reference and fulfillment timestamp");
        }
        long unit = requireNonNegativeSnapshot(order.getUnitPriceCents(), "unit price");
        long fee = requireNonNegativeSnapshot(order.getServiceFeeCents(), "service fee");
        long amount = requireNonNegativeSnapshot(order.getAmountCents(), "total amount");
        try {
            if (Math.addExact(unit, fee) != amount) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order pricing evidence is inconsistent");
            }
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order pricing evidence is outside the supported range");
        }
        normalizeCurrency(order.getCurrency());
    }

    private MerchantInfo requireMerchantInfo() {
        List<String> missing = new ArrayList<>();
        if (isBlank(merchantLegalName)) missing.add("plus.receipt.merchant.legal-name");
        if (isBlank(merchantAddressLine1)) missing.add("plus.receipt.merchant.address-line-1");
        if (isBlank(merchantContactEmail)) missing.add("plus.receipt.merchant.contact-email");
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt merchant configuration is incomplete: " + String.join(", ", missing));
        }
        String contactEmail = merchantContactEmail.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(contactEmail).matches()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt merchant configuration has an invalid plus.receipt.merchant.contact-email");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(receiptTimeZone == null || receiptTimeZone.isBlank() ? "UTC" : receiptTimeZone.trim());
        } catch (DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt configuration has an invalid plus.receipt.time-zone");
        }
        return new MerchantInfo(
                firstNonBlank(merchantDisplayName, merchantLegalName),
                merchantLegalName.trim(),
                merchantAddressLine1.trim(),
                optionalText(merchantAddressLine2, "merchantAddressLine2", 255),
                contactEmail,
                optionalText(merchantRegistrationId, "merchantRegistrationId", 120),
                zone
        );
    }

    private byte[] buildReceiptPdf(User user, PlusOrder order, MerchantInfo merchant) {
        String receiptNumber = ascii(requiredText(order.getOrderNo(), "order.orderNo", 80));
        String paidDate = formatReceiptDate(order.getPaidAt(), merchant.timeZone());
        String currency = normalizeCurrency(order.getCurrency());
        String unitPrice = formatMoney(order.getUnitPriceCents(), currency);
        String fee = formatMoney(order.getServiceFeeCents(), currency);
        String amount = formatMoney(order.getAmountCents(), currency);
        String buyerName = ascii(firstNonBlank(user.getUsername(), order.getContactEmail()));
        String buyerEmail = ascii(firstNonBlank(order.getContactEmail(), user.getEmail()));
        String productName = ascii(requiredText(order.getProductName(), "order.productName", 160));

        PdfCanvas canvas = new PdfCanvas();
        canvas.text("Receipt", 54, 792, 26, true);

        canvas.text("Receipt number", 54, 752, 9, false);
        canvas.text(receiptNumber, 154, 752, 9, false);
        canvas.text("Order status", 54, 736, 9, false);
        canvas.text(ascii(order.getStatus()), 154, 736, 9, false);
        canvas.text("Date paid", 54, 720, 9, false);
        canvas.text(paidDate, 154, 720, 9, false);

        canvas.text(ascii(merchant.displayName()), 54, 660, 10, true);
        canvas.text(ascii(merchant.legalName()), 54, 642, 9, false);
        canvas.text(ascii(merchant.addressLine1()), 54, 628, 9, false);
        if (merchant.addressLine2() != null) canvas.text(ascii(merchant.addressLine2()), 54, 614, 9, false);
        canvas.text(ascii(merchant.contactEmail()), 54, 600, 9, false);
        if (merchant.registrationId() != null) {
            canvas.text("Registration: " + ascii(merchant.registrationId()), 54, 586, 9, false);
        }

        canvas.text("Bill to", 334, 660, 10, true);
        canvas.text(buyerName, 334, 642, 9, false);
        canvas.text(buyerEmail, 334, 628, 9, false);

        canvas.text(amount + " paid on " + paidDate, 54, 526, 18, true);

        canvas.line(54, 474, 542, 474);
        canvas.text("Description", 54, 456, 9, true);
        canvas.text("Qty", 310, 456, 9, true);
        canvas.text("Unit price", 354, 456, 9, true);
        canvas.text("Service fee", 432, 456, 9, true);
        canvas.text("Amount", 506, 456, 9, true);
        canvas.line(54, 444, 542, 444);

        canvas.text(productName, 54, 424, 9, false);
        canvas.text("1", 314, 424, 9, false);
        canvas.text(unitPrice, 354, 424, 9, false);
        canvas.text(fee, 432, 424, 9, false);
        canvas.text(amount, 506, 424, 9, false);
        canvas.line(54, 394, 542, 394);

        canvas.text("Total paid", 392, 356, 10, true);
        canvas.text(amount, 492, 356, 10, true);
        canvas.text("Payment reference", 54, 330, 9, true);
        canvas.text(ascii(order.getPaymentReference()), 176, 330, 9, false);
        if (FULFILLED.equals(normalizeStoredStatus(order.getStatus()))) {
            canvas.text("Fulfillment reference", 54, 310, 9, true);
            canvas.text(ascii(order.getFulfillmentReference()), 176, 310, 9, false);
            canvas.text("Fulfilled at", 54, 290, 9, true);
            canvas.text(formatReceiptDate(order.getFulfilledAt(), merchant.timeZone()), 176, 290, 9, false);
        }

        canvas.text(receiptNumber + " - Page 1 of 1", 54, 36, 9, false);
        return canvas.toPdf();
    }

    private String generateOrderNo() {
        return "PLUS" + nowUtc().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private void requireUserId(User user) {
        if (user == null || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is required");
        }
    }

    private String normalizeContactEmail(String requestedEmail, User user) {
        String candidate = firstNonBlank(requestedEmail, user.getEmail());
        if (candidate == null || !EMAIL_PATTERN.matcher(candidate.trim()).matches()) {
            throw badRequest("A valid contactEmail is required for fulfillment");
        }
        return candidate.trim().toLowerCase(Locale.ROOT);
    }

    private long nonNegative(Long value, String field) {
        long normalized = value == null ? 0 : value;
        if (normalized < 0) {
            throw badRequest(field + " must be non-negative");
        }
        return normalized;
    }

    private long requireNonNegativeSnapshot(Long value, String field) {
        if (value == null || value < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order " + field + " is missing or invalid");
        }
        return value;
    }

    private String normalizeCurrency(String value) {
        String currency = value == null || value.isBlank() ? "USD" : value.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY_PATTERN.matcher(currency).matches()) {
            throw badRequest("currency must be a three-letter ISO 4217 code");
        }
        return currency;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw badRequest("status is required");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_STATUSES.contains(normalized)) {
            throw badRequest("Unsupported order status: " + normalized);
        }
        return normalized;
    }

    private String normalizeStoredStatus(String status) {
        String normalized = normalizeStatus(status);
        if (!STATUS_TRANSITIONS.containsKey(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stored order status is invalid");
        }
        return normalized;
    }

    private PlusOrder markPaid(PlusOrder order, String providerTradeNo, String paymentType) {
        return markPaid(order, providerTradeNo, paymentType, "ANYIPAY");
    }

    private PlusOrder markPaid(PlusOrder order,
                               String providerTradeNo,
                               String paymentType,
                               String paymentProvider) {
        String current = normalizeStoredStatus(order.getStatus());
        String reference = requiredText(providerTradeNo, "providerTradeNo", 120);
        String provider = requiredText(paymentProvider, "paymentProvider", 40);
        if (Set.of(PAID, FULFILLED).contains(current)) {
            if (!isBlank(order.getProviderTradeNo()) && !order.getProviderTradeNo().equals(reference)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Paid order references a different provider transaction");
            }
            return order;
        }
        if (!Set.of(PENDING, CONFIRMED).contains(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment cannot be applied while order status is " + current);
        }
        LocalDateTime now = nowUtc();
        order.setStatus(PAID);
        order.setPaymentProvider(provider);
        order.setProviderTradeNo(reference);
        order.setPaymentReference(reference);
        order.setPaymentType(optionalText(paymentType, "paymentType", 40));
        order.setPaidAt(now);
        order.setUpdatedAt(now);
        int updated = orderMapper.update(order, new LambdaUpdateWrapper<PlusOrder>()
                .eq(PlusOrder::getId, order.getId())
                .eq(PlusOrder::getStatus, current));
        if (updated != 1) {
            PlusOrder latest = orderMapper.selectById(order.getId());
            if (latest != null && Set.of(PAID, FULFILLED).contains(normalizeStoredStatus(latest.getStatus()))
                    && reference.equals(latest.getProviderTradeNo())) {
                return latest;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order changed concurrently while applying payment");
        }
        return order;
    }

    private void validateGatewayOrderFacts(PlusOrder order,
                                           String callbackMerchantId,
                                           String callbackOrderNo,
                                           String callbackMoney,
                                           String callbackParam) {
        if (anyiPayClient == null || !anyiPayClient.merchantId().equals(callbackMerchantId)) {
            throw badRequest("Payment merchant does not match");
        }
        if (!Objects.equals(order.getOrderNo(), callbackOrderNo)) {
            throw badRequest("Payment order number does not match");
        }
        PaymentQuote paymentQuote = ensurePaymentQuote(order);
        if (!"CNY".equals(paymentQuote.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment settlement currency is not CNY");
        }
        if (!money(paymentQuote.amountCents()).equals(callbackMoney)) {
            throw badRequest("Payment amount does not match the order");
        }
        if (!("plus-order:" + order.getId()).equals(callbackParam)) {
            throw badRequest("Payment callback does not belong to the Plus order namespace");
        }
    }

    private PlusOrderResponse paymentResponse(PlusOrder order, String message) {
        return PlusOrderResponse.builder()
                .order(order)
                .message(message)
                .payType(order.getPaymentType())
                .paymentUrl(order.getPaymentUrl())
                .providerTradeNo(order.getProviderTradeNo())
                .build();
    }

    private String requiredProviderText(JsonNode response, String field, int maxLength) {
        return requiredText(response == null ? null : response.path(field).asText(null), field, maxLength);
    }

    private String money(Long cents) {
        long normalized = requireNonNegativeSnapshot(cents, "amount");
        if (normalized == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment amount must be positive");
        return BigDecimal.valueOf(normalized, 2).setScale(2).toPlainString();
    }

    private PaymentQuote ensurePaymentQuote(PlusOrder order) {
        if (order.getPaymentAmountCents() != null
                && order.getPaymentAmountCents() > 0
                && "CNY".equals(normalizeCurrency(order.getPaymentCurrency()))
                && order.getExchangeRate() != null
                && order.getExchangeRate().signum() > 0) {
            return new PaymentQuote(order.getPaymentAmountCents(), "CNY", order.getExchangeRate());
        }
        PaymentQuote quote = paymentQuote(requireNonNegativeSnapshot(order.getAmountCents(), "amount"),
                normalizeCurrency(order.getCurrency()));
        order.setPaymentAmountCents(quote.amountCents());
        order.setPaymentCurrency(quote.currency());
        order.setExchangeRate(quote.exchangeRate());
        return quote;
    }

    private PaymentQuote paymentQuote(long amountCents, String sourceCurrency) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment amount must be positive");
        }
        if ("CNY".equals(sourceCurrency)) {
            return new PaymentQuote(amountCents, "CNY", BigDecimal.ONE.setScale(8));
        }
        if (!"USD".equals(sourceCurrency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "AnyiPay payment supports CNY services or USD services with a configured USD/CNY rate");
        }
        BigDecimal rate = usdCnyPaymentRate == null
                ? null : usdCnyPaymentRate.setScale(8, RoundingMode.HALF_UP);
        if (rate == null || rate.compareTo(new BigDecimal("0.01")) < 0
                || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "USD/CNY payment rate is not configured correctly");
        }
        try {
            long convertedCents = BigDecimal.valueOf(amountCents)
                    .multiply(rate)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            if (convertedCents <= 0) throw new ArithmeticException("converted amount is zero");
            return new PaymentQuote(convertedCents, "CNY", rate);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Converted payment amount is outside the supported range", exception);
        }
    }

    private record PaymentQuote(long amountCents, String currency, BigDecimal exchangeRate) {
    }

    private boolean hasPaymentEvidence(PlusOrder order) {
        return !isBlank(order.getPaymentReference()) && order.getPaidAt() != null;
    }

    private boolean hasFulfillmentEvidence(PlusOrder order) {
        return !isBlank(order.getFulfillmentReference()) && order.getFulfilledAt() != null;
    }

    private String requiredText(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        if (normalized == null) {
            throw badRequest(field + " is required");
        }
        return normalized;
    }

    private String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest(field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private String optionalHttpUrl(String value) {
        return optionalHttpUrl(value, "imageUrl", 1000);
    }

    private String optionalHttpUrl(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        if (normalized == null) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("not an absolute HTTP(S) URL");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " must be an absolute HTTP(S) URL");
        }
    }

    private String firstNonBlank(String first, String second) {
        if (!isBlank(first)) return first.trim();
        if (!isBlank(second)) return second.trim();
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String formatMoney(Long cents, String currency) {
        return currency + " " + String.format(Locale.US, "%.2f", cents / 100.0);
    }

    private String formatReceiptDate(LocalDateTime timestamp, ZoneId zoneId) {
        if (timestamp == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt timestamp is missing");
        }
        ZonedDateTime date = timestamp.atZone(ZoneOffset.UTC).withZoneSameInstant(zoneId);
        return date.format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z", Locale.US));
    }

    private String ascii(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private record MerchantInfo(String displayName,
                                String legalName,
                                String addressLine1,
                                String addressLine2,
                                String contactEmail,
                                String registrationId,
                                ZoneId timeZone) {
    }

    private static class PdfCanvas {
        private final StringBuilder content = new StringBuilder();

        void text(String value, int x, int y, int size, boolean bold) {
            content.append("BT /")
                    .append(bold ? "F2" : "F1")
                    .append(" ")
                    .append(size)
                    .append(" Tf ")
                    .append(x)
                    .append(" ")
                    .append(y)
                    .append(" Td (")
                    .append(escape(value))
                    .append(") Tj ET\n");
        }

        void line(int x1, int y1, int x2, int y2) {
            content.append("0.82 0.86 0.9 RG 0.8 w ")
                    .append(x1)
                    .append(" ")
                    .append(y1)
                    .append(" m ")
                    .append(x2)
                    .append(" ")
                    .append(y2)
                    .append(" l S\n");
        }

        byte[] toPdf() {
            byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
            List<byte[]> objects = new ArrayList<>();
            objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.ISO_8859_1));
            objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.ISO_8859_1));
            objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595.92 841.92] "
                    + "/Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>")
                    .getBytes(StandardCharsets.ISO_8859_1));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".getBytes(StandardCharsets.ISO_8859_1));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>".getBytes(StandardCharsets.ISO_8859_1));
            objects.add(("<< /Length " + stream.length + " >>\nstream\n" + content + "endstream")
                    .getBytes(StandardCharsets.ISO_8859_1));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            write(out, "%PDF-1.4\n");
            for (int i = 0; i < objects.size(); i++) {
                offsets.add(out.size());
                write(out, (i + 1) + " 0 obj\n");
                write(out, objects.get(i));
                write(out, "\nendobj\n");
            }
            int xref = out.size();
            write(out, "xref\n0 " + (objects.size() + 1) + "\n");
            write(out, "0000000000 65535 f \n");
            for (Integer offset : offsets) {
                write(out, String.format(Locale.US, "%010d 00000 n \n", offset));
            }
            write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref
                    + "\n%%EOF\n");
            return out.toByteArray();
        }

        private static String escape(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        }

        private static void write(ByteArrayOutputStream out, String value) {
            write(out, value.getBytes(StandardCharsets.ISO_8859_1));
        }

        private static void write(ByteArrayOutputStream out, byte[] value) {
            out.write(value, 0, value.length);
        }
    }
}
