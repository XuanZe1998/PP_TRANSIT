package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.transit.dto.ServiceOrderCreateRequest;
import com.transit.dto.ServiceOrderResponse;
import com.transit.dto.ServiceOrderQuoteResponse;
import com.transit.dto.MoneyAmount;
import com.transit.dto.ShopGptCheckoutRequest;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.model.ServiceOrder;
import com.transit.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
public class ServiceOrderService {

    private static final String PENDING = "PENDING";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String PAID = "PAID";
    private static final String FULFILLED = "FULFILLED";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";
    private static final String EXPIRED = "EXPIRED";
    private static final Set<String> KNOWN_STATUSES = Set.of(PENDING, CONFIRMED, PAID, FULFILLED, FAILED, CANCELLED, EXPIRED);
    private static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.of(
            PENDING, Set.of(CONFIRMED, PAID, FAILED, CANCELLED),
            CONFIRMED, Set.of(PAID, FAILED, CANCELLED),
            PAID, Set.of(FULFILLED),
            FULFILLED, Set.of(),
            FAILED, Set.of(),
            CANCELLED, Set.of(),
            EXPIRED, Set.of(PAID)
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern MAINLAND_POSTAL_CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final Set<String> PAYMENT_METHODS = Set.of("alipay", "wxpay");
    private static final SecureRandom RECEIPT_NUMBER_RANDOM = new SecureRandom();

    private final ServiceOrderMapper orderMapper;
    private final AnyiPayClient anyiPayClient;
    private final ServiceCommerceService serviceCommerceService;
    private final PaymentIntentService paymentIntentService;
    private final AccountVerificationPolicy verificationPolicy;

    @Autowired
    public ServiceOrderService(ServiceOrderMapper orderMapper,
                            AnyiPayClient anyiPayClient,
                            ServiceCommerceService serviceCommerceService,
                            PaymentIntentService paymentIntentService,
                            AccountVerificationPolicy verificationPolicy) {
        this.orderMapper = orderMapper;
        this.anyiPayClient = anyiPayClient;
        this.serviceCommerceService = serviceCommerceService;
        this.paymentIntentService = paymentIntentService;
        this.verificationPolicy = verificationPolicy;
    }

    ServiceOrderService(ServiceOrderMapper orderMapper,
                     AnyiPayClient anyiPayClient, ServiceCommerceService serviceCommerceService) {
        this(orderMapper, anyiPayClient, serviceCommerceService, null,
                new AccountVerificationPolicy("EMAIL_AND_PHONE"));
    }

    // Focused unit tests that do not exercise an external payment provider use this constructor.
    ServiceOrderService(ServiceOrderMapper orderMapper) {
        this(orderMapper, null, null, null, new AccountVerificationPolicy("EMAIL_AND_PHONE"));
    }

    ServiceOrderService(ServiceOrderMapper orderMapper, AnyiPayClient anyiPayClient) {
        this(orderMapper, anyiPayClient, null, null, new AccountVerificationPolicy("EMAIL_AND_PHONE"));
    }

    @Value("${payment.documents.merchant.legal-name:}")
    private String merchantLegalName;

    @Value("${payment.documents.merchant.address-line-1:}")
    private String merchantAddressLine1;

    @Value("${payment.documents.merchant.address-line-2:}")
    private String merchantAddressLine2;

    @Value("${payment.documents.merchant.country:}")
    private String merchantCountry;

    @Value("${payment.documents.merchant.contact-email:}")
    private String merchantContactEmail;

    @Value("${payment.documents.time-zone:Asia/Shanghai}")
    private String receiptTimeZone;

    @Value("${payment.documents.font-path:}")
    private String receiptFontPath;

    @Value("${payment.local-test-mode:false}")
    private boolean localTestPaymentMode;

    @Value("${payment.usd-cny-rate:6.76693506}")
    private BigDecimal usdCnyPaymentRate = new BigDecimal("6.76693506");

    @Transactional
    public ServiceOrderResponse createOrder(User user, ServiceOrderCreateRequest request) {
        if (user == null || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is required");
        }
        if (request == null || request.getServiceId() == null) {
            throw badRequest("serviceId is required");
        }

        ServiceOrderQuoteResponse serviceQuote = requireCommerce().quote(
                request.getServiceId(), request.getQuantity(), request.getCouponCode());
        verificationPolicy.requireComplete(user, "购买");
        if (ServiceCommerceService.AUTOMATIC.equals(serviceQuote.getFulfillmentMode())
                && paymentIntentService != null && !paymentIntentService.refundsEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "自动发货商品暂停结账：管理员尚未配置支付退款能力");
        }
        long unitPriceCents = serviceQuote.getListUnitPriceCents();
        long effectiveUnitPriceCents = serviceQuote.getEffectiveUnitPriceCents();
        int quantity = serviceQuote.getQuantity();
        long merchandiseSubtotalCents = serviceQuote.getMerchandiseSubtotalCents();
        long wholesaleDiscountCents = serviceQuote.getWholesaleDiscountCents();
        long couponDiscountCents = serviceQuote.getCouponDiscountCents();
        long serviceFeeCents = serviceQuote.getServiceFeeCents();
        long amountCents = serviceQuote.getAmountCents();
        String contactEmail = normalizeContactEmail(request.getContactEmail(), user);
        String billingCountry = requiredText(request.getBillingCountry(), "billingCountry", 120);
        String billingPostalCode = normalizePostalCode(request.getBillingPostalCode(), billingCountry);
        String paymentMethod = normalizePaymentMethod(request.getPaymentMethod());
        String orderCurrency = normalizeCurrency(serviceQuote.getCurrency());
        PaymentQuote paymentQuote = paymentQuote(amountCents, orderCurrency);
        LocalDateTime now = nowUtc();
        String orderNo = generateOrderNo();
        ServiceOrder order = ServiceOrder.builder()
                .orderNo(orderNo)
                .userId(user.getId())
                .serviceId(request.getServiceId())
                .productName(requiredText(serviceQuote.getServiceName(), "service.name", 160))
                .quantity(quantity)
                .fulfillmentMode(serviceQuote.getFulfillmentMode())
                .unitPriceCents(unitPriceCents)
                .effectiveUnitPriceCents(effectiveUnitPriceCents)
                .merchandiseSubtotalCents(merchandiseSubtotalCents)
                .wholesaleDiscountCents(wholesaleDiscountCents)
                .couponId(serviceQuote.getCouponId())
                .couponCode(serviceQuote.getCouponCode())
                .couponDiscountCents(couponDiscountCents)
                .serviceFeeCents(serviceFeeCents)
                .amountCents(amountCents)
                .currency(orderCurrency)
                .paymentAmountCents(paymentQuote.amountCents())
                .paymentCurrency(paymentQuote.currency())
                .exchangeRate(paymentQuote.exchangeRate())
                .status(PENDING)
                .contactEmail(contactEmail)
                .contactNote(optionalText(request.getContactNote(), "contactNote", 1000))
                .invoiceNumber(orderNo)
                .receiptNumber(generateReceiptNumber())
                .billingName(requiredText(request.getBillingName(), "billingName", 160))
                .billingAddressLine1(requiredText(request.getBillingAddressLine1(), "billingAddressLine1", 255))
                .billingDistrict(requiredText(request.getBillingDistrict(), "billingDistrict", 120))
                .billingCity(requiredText(request.getBillingCity(), "billingCity", 120))
                .billingProvince(requiredText(request.getBillingProvince(), "billingProvince", 120))
                .billingPostalCode(billingPostalCode)
                .billingCountry(billingCountry)
                .paymentMethod(paymentMethod)
                .purchasePrompt(serviceQuote.getPurchasePrompt())
                .fulfillmentStatus(ServiceCommerceService.AUTOMATIC.equals(serviceQuote.getFulfillmentMode()) ? "PENDING" : "RESERVED")
                .fulfillmentNote("Order created; payment has not yet been verified.")
                .createdAt(now)
                .updatedAt(now)
                .build();
        orderMapper.insert(order);
        if (!Boolean.TRUE.equals(serviceQuote.getAvailable())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient stock");
        }
        requireCommerce().reserve(order, request.getCustomFields());

        com.transit.model.PaymentIntent paymentIntent = paymentIntentService == null ? null
                : paymentIntentService.ensureServiceIntent(order);

        return ServiceOrderResponse.builder()
                .order(order)
                .paymentIntent(paymentIntent)
                .message("待支付订单已创建，请在订单列表点击支付")
                .build();
    }

    /** Creates a manual order from a server-side supplier quote; no client amount is accepted. */
    @Transactional
    public ServiceOrderResponse createExternallyQuotedManualOrder(User user, String productName, int quantity,
                                                                long totalCents, String supplierQuoteJson,
                                                                ShopGptCheckoutRequest request) {
        requireUserId(user);
        if (request == null) throw badRequest("checkout body is required");
        if (quantity < 1 || totalCents <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Supplier quote is invalid");
        String country=requiredText(request.getBillingCountry(),"billingCountry",120);
        String postal=normalizePostalCode(request.getBillingPostalCode(),country);
        String paymentMethod=normalizePaymentMethod(request.getPaymentMethod());
        long unit=BigDecimal.valueOf(totalCents).divide(BigDecimal.valueOf(quantity),0,RoundingMode.HALF_UP).longValueExact();
        PaymentQuote paymentQuote=paymentQuote(totalCents,"CNY"); LocalDateTime now=nowUtc(); String orderNo=generateOrderNo();
        ServiceOrder order=ServiceOrder.builder().orderNo(orderNo).userId(user.getId()).productName(requiredText(productName,"productName",160))
                .quantity(quantity).fulfillmentMode(ServiceCommerceService.MANUAL).unitPriceCents(unit).effectiveUnitPriceCents(unit)
                .merchandiseSubtotalCents(totalCents).wholesaleDiscountCents(0L).couponDiscountCents(0L).serviceFeeCents(0L)
                .amountCents(totalCents).currency("CNY").paymentAmountCents(paymentQuote.amountCents()).paymentCurrency("CNY")
                .exchangeRate(paymentQuote.exchangeRate()).status(PENDING).contactEmail(normalizeContactEmail(request.getContactEmail(),user))
                .invoiceNumber(orderNo).receiptNumber(generateReceiptNumber()).billingName(requiredText(request.getBillingName(),"billingName",160))
                .billingAddressLine1(requiredText(request.getBillingAddressLine1(),"billingAddressLine1",255))
                .billingDistrict(requiredText(request.getBillingDistrict(),"billingDistrict",120)).billingCity(requiredText(request.getBillingCity(),"billingCity",120))
                .billingProvince(requiredText(request.getBillingProvince(),"billingProvince",120)).billingPostalCode(postal).billingCountry(country)
                .paymentMethod(paymentMethod).supplierQuoteJson(supplierQuoteJson).purchasePrompt("Supplier price was snapshotted at checkout; fulfillment is handled manually by an administrator.")
                .fulfillmentStatus("PENDING").fulfillmentNote("Awaiting verified payment.").createdAt(now).updatedAt(now).build();
        orderMapper.insert(order);
        com.transit.model.PaymentIntent intent=paymentIntentService.ensureServiceIntent(order);
        return ServiceOrderResponse.builder().order(enrichOrderMoney(order)).paymentIntent(intent).message("Order created from fresh supplier quote").build();
    }

    public ServiceOrderResponse startPayment(User user, Long id, String clientIp) {
        ServiceOrder order = getUserOrder(user, id);
        if (paymentIntentService != null) {
            com.transit.model.PaymentIntent intent = paymentIntentService.getByBusiness(PaymentBusinessSettlementService.SERVICE_ORDER, order.getId());
            intent = paymentIntentService.start(user, intent.getId());
            ServiceOrder latest = getUserOrder(user, id);
            return ServiceOrderResponse.builder().order(latest).paymentIntent(intent)
                    .payType(intent.getPaymentType()).paymentUrl(intent.getPaymentUrl())
                    .providerTradeNo(intent.getProviderTradeNo()).message("Payment intent started").build();
        }
        String status = normalizeStoredStatus(order.getStatus());
        if (!Set.of(PENDING, CONFIRMED).contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment cannot be started while order status is " + status);
        }
        if (localTestPaymentMode) {
            String reference = "LOCAL-" + requiredText(order.getOrderNo(), "order.orderNo", 80);
            ServiceOrder paid = markPaid(order, reference, "local-test", "LOCAL_TEST");
            return paymentResponse(paid, "本地模拟支付成功");
        }
        if (anyiPayClient == null || !anyiPayClient.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "支付通道未启用，请先配置聚合支付商户信息");
        }
        PaymentQuote paymentQuote = ensurePaymentQuote(order);
        String paymentMethod = normalizePaymentMethod(order.getPaymentMethod());
        String paymentUrl = anyiPayClient.createPagePaymentUrl(
                order.getOrderNo(),
                order.getProductName(),
                money(paymentQuote.amountCents()),
                "service-order:" + order.getId(),
                paymentMethod);
        order.setPaymentProvider("ANYIPAY");
        order.setProviderTradeNo(null);
        order.setPaymentType("page");
        order.setPaymentUrl(optionalHttpUrl(paymentUrl, "paymentUrl", 2000));
        order.setUpdatedAt(nowUtc());
        orderMapper.updateById(order);
        return paymentResponse(order, "请前往支付页完成付款");
    }

    public ServiceOrderResponse queryPayment(User user, Long id) {
        ServiceOrder owned = getUserOrder(user, id);
        if (paymentIntentService != null) {
            com.transit.model.PaymentIntent intent = paymentIntentService.getByBusiness(PaymentBusinessSettlementService.SERVICE_ORDER, owned.getId());
            intent = paymentIntentService.query(user, intent.getId());
            return ServiceOrderResponse.builder().order(getUserOrder(user, id)).paymentIntent(intent)
                    .payType(intent.getPaymentType()).paymentUrl(intent.getPaymentUrl())
                    .providerTradeNo(intent.getProviderTradeNo()).message("Payment status refreshed").build();
        }
        if (anyiPayClient == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AnyiPay is unavailable");
        }
        ServiceOrder order = getUserOrder(user, id);
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
        if (paymentIntentService != null) {
            paymentIntentService.receiveNotification(callback);
            return;
        }
        if (callback == null || !"TRADE_SUCCESS".equals(callback.get("trade_status"))) {
            throw badRequest("Unsupported payment notification status");
        }
        String orderNo = requiredText(callback.get("out_trade_no"), "out_trade_no", 80);
        ServiceOrder order = orderMapper.selectOne(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderNo, orderNo)
                .last("LIMIT 1"));
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        validateGatewayOrderFacts(order, callback.get("pid"), orderNo, callback.get("money"), callback.get("param"));
        markPaid(order,
                requiredText(callback.get("trade_no"), "trade_no", 120),
                optionalText(callback.get("type"), "type", 40));
    }

    public List<ServiceOrder> listUserOrders(User user) {
        requireUserId(user);
        return orderMapper.selectList(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getUserId, user.getId())
                .orderByDesc(ServiceOrder::getCreatedAt)).stream().map(this::enrichOrderMoney).toList();
    }

    public List<ServiceOrder> listAllOrders() {
        return orderMapper.selectList(new LambdaQueryWrapper<ServiceOrder>().orderByDesc(ServiceOrder::getCreatedAt))
                .stream().map(this::enrichOrderMoney).toList();
    }

    public ServiceOrder getUserOrder(User user, Long id) {
        requireUserId(user);
        ServiceOrder order = orderMapper.selectById(id);
        if (order == null || !Objects.equals(order.getUserId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return enrichOrderMoney(serviceCommerceService == null ? order : serviceCommerceService.revealDelivery(order));
    }

    /**
     * Compatibility entry point used by the existing admin APIs. For PAID and
     * FULFILLED transitions, the note is also treated as the corresponding
     * evidence reference so legacy clients cannot create an evidence-free state.
     */
    public ServiceOrder fulfillOrder(Long id, String status, String fulfillmentNote) {
        String normalized = normalizeStatus(status);
        String paymentReference = PAID.equals(normalized) ? fulfillmentNote : null;
        String fulfillmentReference = FULFILLED.equals(normalized) ? fulfillmentNote : null;
        return fulfillOrder(id, status, fulfillmentNote, paymentReference, fulfillmentReference);
    }

    @Transactional
    public ServiceOrder fulfillOrder(Long id,
                                  String status,
                                  String fulfillmentNote,
                                  String paymentReference,
                                  String fulfillmentReference) {
        ServiceOrder order = orderMapper.selectById(id);
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
        int updated = orderMapper.update(order, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, id)
                .eq(ServiceOrder::getStatus, current));
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order changed concurrently; reload it before applying another status transition");
        }
        if (serviceCommerceService != null && order.getServiceId() != null
                && Set.of(FAILED, CANCELLED).contains(target)
                && Set.of(PENDING, CONFIRMED).contains(current)) {
            serviceCommerceService.release(order, false);
        }
        if (serviceCommerceService != null && order.getServiceId() != null && PAID.equals(target)) {
            return serviceCommerceService.settlePaid(order);
        }
        return order;
    }

    @Transactional
    public void deleteOrder(Long id) {
        ServiceOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        String status = normalizeStoredStatus(order.getStatus());
        if (PAID.equals(status) || FULFILLED.equals(status) || hasPaymentEvidence(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Paid or fulfilled orders must be retained for audit; cancel or refund them through a verified workflow");
        }
        if (serviceCommerceService != null && order.getServiceId() != null
                && Set.of(PENDING, CONFIRMED).contains(status)) {
            serviceCommerceService.release(order, false);
        }
        orderMapper.deleteById(id);
    }

    public byte[] buildDownload(User user, Long id) {
        return buildReceiptDownload(user, id);
    }

    public byte[] buildReceiptDownload(User user, Long id) {
        ServiceOrder order = getUserOrder(user, id);
        validateReceiptEligibility(order);
        MerchantInfo merchant = requireMerchantInfo();
        byte[] receipt = new BillingPdfRenderer(receiptFontPath)
                .renderReceipt(documentData(order, merchant));
        order.setDownloadedAt(nowUtc());
        order.setUpdatedAt(nowUtc());
        orderMapper.updateById(order);
        return receipt;
    }

    public byte[] buildInvoiceDownload(User user, Long id) {
        ServiceOrder order = getUserOrder(user, id);
        validateDocumentSnapshot(order);
        MerchantInfo merchant = requireMerchantInfo();
        byte[] invoice = new BillingPdfRenderer(receiptFontPath)
                .renderInvoice(documentData(order, merchant));
        order.setDownloadedAt(nowUtc());
        order.setUpdatedAt(nowUtc());
        orderMapper.updateById(order);
        return invoice;
    }

    private void validateReceiptEligibility(ServiceOrder order) {
        String status = normalizeStoredStatus(order.getStatus());
        if (!PAID.equals(status) && !FULFILLED.equals(status) && !"REFUNDED".equals(status)) {
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
        validateDocumentSnapshot(order);
    }

    private void validateDocumentSnapshot(ServiceOrder order) {
        long unit = requireNonNegativeSnapshot(order.getUnitPriceCents(), "unit price");
        long fee = requireNonNegativeSnapshot(order.getServiceFeeCents(), "service fee");
        long amount = requireNonNegativeSnapshot(order.getAmountCents(), "total amount");
        long coupon = requireNonNegativeSnapshot(order.getCouponDiscountCents() == null ? 0L : order.getCouponDiscountCents(), "coupon discount");
        try {
            long subtotal = order.getMerchandiseSubtotalCents() == null
                    ? Math.multiplyExact(unit, order.getQuantity() == null ? 1 : order.getQuantity())
                    : requireNonNegativeSnapshot(order.getMerchandiseSubtotalCents(), "merchandise subtotal");
            if (Math.addExact(Math.subtractExact(subtotal, coupon), fee) != amount) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order pricing evidence is inconsistent");
            }
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order pricing evidence is outside the supported range");
        }
        normalizeCurrency(order.getCurrency());
        requiredSnapshotText(firstNonBlank(order.getInvoiceNumber(), order.getOrderNo()), "invoice number");
        requiredSnapshotText(order.getReceiptNumber(), "receipt number");
        if (!order.getReceiptNumber().matches("^\\d{4}-\\d{4}-\\d{4}$")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order receipt number is invalid");
        }
        requiredSnapshotText(order.getBillingName(), "billing name");
        requiredSnapshotText(order.getBillingAddressLine1(), "billing address");
        requiredSnapshotText(order.getBillingDistrict(), "billing district");
        requiredSnapshotText(order.getBillingCity(), "billing city");
        requiredSnapshotText(order.getBillingProvince(), "billing province");
        requiredSnapshotText(order.getBillingPostalCode(), "billing postal code");
        requiredSnapshotText(order.getBillingCountry(), "billing country");
        String email = requiredSnapshotText(order.getContactEmail(), "billing email");
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order billing email is invalid");
        }
        normalizePaymentMethod(order.getPaymentMethod());
    }

    private MerchantInfo requireMerchantInfo() {
        List<String> missing = new ArrayList<>();
        if (isBlank(merchantLegalName)) missing.add("payment.documents.merchant.legal-name");
        if (isBlank(merchantAddressLine1)) missing.add("payment.documents.merchant.address-line-1");
        if (isBlank(merchantAddressLine2)) missing.add("payment.documents.merchant.address-line-2");
        if (isBlank(merchantCountry)) missing.add("payment.documents.merchant.country");
        if (isBlank(merchantContactEmail)) missing.add("payment.documents.merchant.contact-email");
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt merchant configuration is incomplete: " + String.join(", ", missing));
        }
        String contactEmail = merchantContactEmail.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(contactEmail).matches()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt merchant configuration has an invalid payment.documents.merchant.contact-email");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(receiptTimeZone == null || receiptTimeZone.isBlank() ? "UTC" : receiptTimeZone.trim());
        } catch (DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt configuration has an invalid payment.documents.time-zone");
        }
        return new MerchantInfo(
                merchantLegalName.trim(),
                merchantAddressLine1.trim(),
                merchantAddressLine2.trim(),
                merchantCountry.trim(),
                contactEmail,
                zone
        );
    }

    private BillingPdfRenderer.DocumentData documentData(ServiceOrder order, MerchantInfo merchant) {
        String documentDate = LocalDate.now(merchant.timeZone())
                .format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US));
        String amount = formatMoney(order.getAmountCents(), normalizeCurrency(order.getCurrency()));
        return new BillingPdfRenderer.DocumentData(
                firstNonBlank(order.getInvoiceNumber(), order.getOrderNo()),
                order.getReceiptNumber(),
                documentDate,
                merchant.name(),
                merchant.addressLine1(),
                merchant.addressLine2(),
                merchant.country(),
                merchant.contactEmail(),
                order.getBillingName(),
                order.getBillingAddressLine1(),
                order.getBillingDistrict(),
                order.getBillingCity(),
                order.getBillingProvince(),
                order.getBillingPostalCode(),
                order.getBillingCountry(),
                order.getContactEmail(),
                requiredText(order.getProductName(), "order.productName", 160),
                amount,
                paymentMethodLabel(order.getPaymentMethod())
        );
    }

    private String generateOrderNo() {
        return "PLUS" + nowUtc().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String generateReceiptNumber() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = String.format(Locale.ROOT, "%04d-%04d-%04d",
                    RECEIPT_NUMBER_RANDOM.nextInt(10_000),
                    RECEIPT_NUMBER_RANDOM.nextInt(10_000),
                    RECEIPT_NUMBER_RANDOM.nextInt(10_000));
            Long matches = orderMapper.selectCount(new LambdaQueryWrapper<ServiceOrder>()
                    .eq(ServiceOrder::getReceiptNumber, candidate));
            if (matches == null || matches == 0) return candidate;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to allocate a unique receipt number");
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

    private String normalizePostalCode(String value, String country) {
        String postalCode = requiredText(value, "billingPostalCode", 20);
        String normalizedCountry = country.trim().toLowerCase(Locale.ROOT);
        if (Set.of("china", "中国", "people's republic of china", "prc").contains(normalizedCountry)
                && !MAINLAND_POSTAL_CODE_PATTERN.matcher(postalCode).matches()) {
            throw badRequest("billingPostalCode must contain 6 digits for a mainland China address");
        }
        return postalCode;
    }

    private String normalizePaymentMethod(String value) {
        String normalized = requiredText(value, "paymentMethod", 20).toLowerCase(Locale.ROOT);
        if (!PAYMENT_METHODS.contains(normalized)) {
            throw badRequest("paymentMethod must be alipay or wxpay");
        }
        return normalized;
    }

    private String paymentMethodLabel(String value) {
        return "wxpay".equals(normalizePaymentMethod(value)) ? "WeChat Pay" : "Alipay";
    }

    private String requiredSnapshotText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order " + field + " is missing");
        }
        return value.trim();
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

    private ServiceOrder markPaid(ServiceOrder order, String providerTradeNo, String paymentType) {
        return markPaid(order, providerTradeNo, paymentType, "ANYIPAY");
    }

    private ServiceOrder markPaid(ServiceOrder order,
                               String providerTradeNo,
                               String paymentType,
                               String paymentProvider) {
        String current = normalizeStoredStatus(order.getStatus());
        String reference = requiredText(providerTradeNo, "providerTradeNo", 120);
        String provider = requiredText(paymentProvider, "paymentProvider", 40);
        if ("ANYIPAY".equals(provider)) {
            String actualMethod = normalizePaymentMethod(paymentType);
            if (!actualMethod.equals(normalizePaymentMethod(order.getPaymentMethod()))) {
                throw badRequest("Payment method does not match the method selected for this order");
            }
        }
        if (Set.of(PAID, FULFILLED).contains(current)) {
            if (!isBlank(order.getProviderTradeNo()) && !order.getProviderTradeNo().equals(reference)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Paid order references a different provider transaction");
            }
            if (PAID.equals(current) && serviceCommerceService != null && order.getServiceId() != null
                    && !"COMPLETED".equals(order.getFulfillmentStatus())) {
                return serviceCommerceService.settlePaid(order);
            }
            return order;
        }
        if (!Set.of(PENDING, CONFIRMED, EXPIRED).contains(current)) {
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
        int updated = orderMapper.update(order, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getStatus, current));
        if (updated != 1) {
            ServiceOrder latest = orderMapper.selectById(order.getId());
            if (latest != null && Set.of(PAID, FULFILLED).contains(normalizeStoredStatus(latest.getStatus()))
                    && reference.equals(latest.getProviderTradeNo())) {
                return latest;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order changed concurrently while applying payment");
        }
        if (serviceCommerceService != null && order.getServiceId() != null) {
            return serviceCommerceService.settlePaid(order);
        }
        return order;
    }

    public ServiceOrder completeManualOrder(Long id, String deliveryContent, String note) {
        return requireCommerce().completeManual(id, deliveryContent, note);
    }

    public ServiceOrder retryAutomaticFulfillment(Long id) {
        ServiceOrder order = orderMapper.selectById(id);
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        if (!ServiceCommerceService.AUTOMATIC.equals(order.getFulfillmentMode())
                || !PAID.equals(normalizeStoredStatus(order.getStatus()))
                || !"FAILED".equals(order.getFulfillmentStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a paid automatic-delivery order with failed fulfillment can be retried");
        }
        return requireCommerce().settlePaid(order);
    }

    public ServiceOrder getAdminOrder(Long id) {
        ServiceOrder order = orderMapper.selectById(id);
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return serviceCommerceService == null ? order : serviceCommerceService.revealDelivery(order);
    }

    private ServiceCommerceService requireCommerce() {
        if (serviceCommerceService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service commerce is unavailable");
        }
        return serviceCommerceService;
    }

    private void validateGatewayOrderFacts(ServiceOrder order,
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
        if (!("service-order:" + order.getId()).equals(callbackParam)) {
            throw badRequest("Payment callback does not belong to the service order namespace");
        }
    }

    private ServiceOrderResponse paymentResponse(ServiceOrder order, String message) {
        return ServiceOrderResponse.builder()
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

    private PaymentQuote ensurePaymentQuote(ServiceOrder order) {
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

    private boolean hasPaymentEvidence(ServiceOrder order) {
        return !isBlank(order.getPaymentReference()) && order.getPaidAt() != null;
    }

    private ServiceOrder enrichOrderMoney(ServiceOrder order) {
        if (order == null) return null;
        String currency = order.getCurrency() == null ? "CNY" : order.getCurrency();
        order.setUnitPriceMoney(MoneyAmount.cents(value(order.getUnitPriceCents()), currency));
        order.setEffectiveUnitPriceMoney(MoneyAmount.cents(value(order.getEffectiveUnitPriceCents()), currency));
        order.setMerchandiseSubtotalMoney(MoneyAmount.cents(value(order.getMerchandiseSubtotalCents()), currency));
        order.setWholesaleDiscountMoney(MoneyAmount.cents(value(order.getWholesaleDiscountCents()), currency));
        order.setCouponDiscountMoney(MoneyAmount.cents(value(order.getCouponDiscountCents()), currency));
        order.setServiceFeeMoney(MoneyAmount.cents(value(order.getServiceFeeCents()), currency));
        order.setAmountMoney(MoneyAmount.cents(value(order.getAmountCents()), currency));
        order.setSettlementMoney(MoneyAmount.cents(value(order.getPaymentAmountCents()), order.getPaymentCurrency() == null ? "CNY" : order.getPaymentCurrency()));
        return order;
    }

    private long value(Long amount) { return amount == null ? 0 : amount; }

    private boolean hasFulfillmentEvidence(ServiceOrder order) {
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

    private record MerchantInfo(String name,
                                String addressLine1,
                                String addressLine2,
                                String country,
                                String contactEmail,
                                ZoneId timeZone) {
    }
}
