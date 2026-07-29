package com.transit.service;

import com.transit.dto.PlusOrderRequest;
import com.transit.dto.PlusOrderResponse;
import com.transit.model.PlusOrder;
import com.transit.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Service
public class Service07FulfillmentService {
    private static final int MAX_SESSION_LENGTH = 12_000;
    private static final List<String> REUSABLE_ORDER_STATUSES =
            List.of("PENDING", "CONFIRMED", "PAID");

    private final PlusOrderService plusOrderService;
    private final OtherServiceCatalogService catalogService;
    private final VmCardClientService vmCardClientService;
    private final Service07PythonClient pythonClient;
    private final JdbcTemplate jdbcTemplate;
    private final TaskExecutor taskExecutor;

    public Service07FulfillmentService(
            PlusOrderService plusOrderService,
            OtherServiceCatalogService catalogService,
            VmCardClientService vmCardClientService,
            Service07PythonClient pythonClient,
            JdbcTemplate jdbcTemplate,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.plusOrderService = plusOrderService;
        this.catalogService = catalogService;
        this.vmCardClientService = vmCardClientService;
        this.pythonClient = pythonClient;
        this.jdbcTemplate = jdbcTemplate;
        this.taskExecutor = taskExecutor;
    }

    @Value("${service-07.service-id:7}")
    private long serviceId;

    @Value("${service-07.vmcard.first-name:Service}")
    private String firstName;
    @Value("${service-07.vmcard.last-name:User}")
    private String lastName;
    @Value("${service-07.vmcard.amount:20}")
    private int amount;
    @Value("${service-07.vmcard.address-line-one:123 Test Street}")
    private String addressLineOne;
    @Value("${service-07.vmcard.address-line-two:}")
    private String addressLineTwo;
    @Value("${service-07.vmcard.city:New York}")
    private String city;
    @Value("${service-07.vmcard.state:NY}")
    private String state;
    @Value("${service-07.vmcard.country:US}")
    private String country;
    @Value("${service-07.vmcard.post-code:10001}")
    private String postCode;
    @Value("${service-07.vmcard.area-code:+1}")
    private String areaCode;
    @Value("${service-07.vmcard.mobile:2025550100}")
    private String mobile;
    @Value("${service-07.vmcard.email:}")
    private String configuredEmail;

    public PlusOrderResponse currentOrCreateOrder(User user) {
        Long productId = catalogService.resolveProductIdForOrder(serviceId);
        PlusOrder reusable = plusOrderService.listUserOrders(user).stream()
                .filter(order -> Objects.equals(order.getProductId(), productId))
                .filter(order -> REUSABLE_ORDER_STATUSES.contains(normalize(order.getStatus())))
                .findFirst()
                .orElse(null);
        if (reusable != null) {
            return PlusOrderResponse.builder()
                    .order(reusable)
                    .paymentUrl(reusable.getPaymentUrl())
                    .payType(reusable.getPaymentType())
                    .providerTradeNo(reusable.getProviderTradeNo())
                    .message("已恢复未完成的 Service 07 订单")
                    .build();
        }
        PlusOrderRequest request = new PlusOrderRequest();
        request.setProductId(productId);
        request.setContactEmail(user.getEmail());
        return plusOrderService.createOrder(user, request);
    }

    public synchronized FulfillmentView start(User user, Long orderId, String rawSession) {
        String session = normalizeSession(rawSession);
        PlusOrder order = requirePaidServiceOrder(user, orderId);
        ensureWorkflowRow(order);
        FulfillmentRow row = findRow(orderId);
        if ("SUCCEEDED".equals(row.status())) return row.toView();

        LocalDateTime staleBefore = now().minusMinutes(10);
        if ("RUNNING".equals(row.status())
                || "CARD_CREATED".equals(row.status())
                || "AUTOFILL_RUNNING".equals(row.status())) {
            if (row.updatedAt() != null && row.updatedAt().isAfter(staleBefore)) {
                return row.toView();
            }
        }

        int claimed = jdbcTemplate.update("""
                UPDATE service07_fulfillments
                SET status = 'RUNNING',
                    attempt_count = attempt_count + 1,
                    error_message = NULL,
                    updated_at = ?
                WHERE order_id = ?
                  AND status <> 'SUCCEEDED'
                  AND (
                      status NOT IN ('RUNNING', 'CARD_CREATED', 'AUTOFILL_RUNNING')
                      OR updated_at IS NULL
                      OR updated_at < ?
                  )
                """, now(), orderId, staleBefore);
        if (claimed != 1) return findRow(orderId).toView();

        // Session is deliberately retained only by this in-memory task closure.
        try {
            taskExecutor.execute(() -> runWorkflow(user, order, session));
        } catch (RuntimeException exception) {
            jdbcTemplate.update("""
                    UPDATE service07_fulfillments
                    SET status = 'FAILED', error_message = ?, updated_at = ?
                    WHERE order_id = ?
                    """, "后台任务队列暂不可用，请重试", now(), orderId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "后台任务队列暂不可用，请重试", exception);
        }
        return findRow(orderId).toView();
    }

    public FulfillmentView status(User user, Long orderId) {
        requireServiceOrder(user, orderId);
        FulfillmentRow row = findRowOrNull(orderId);
        return row == null
                ? new FulfillmentView(orderId, "NOT_STARTED", null, null, null)
                : row.toView();
    }

    private void runWorkflow(User user, PlusOrder order, String session) {
        Map<String, Object> cardRequest = buildCardRequest(user, order);
        CompletableFuture<String> checkoutLink = CompletableFuture.supplyAsync(
                () -> pythonClient.generateCheckoutLink(session, country));
        try {
            FulfillmentRow row = findRow(order.getId());
            String cardId = row.cardId();
            if (cardId == null || cardId.isBlank()) {
                cardId = vmCardClientService.createCheckoutCard(cardRequest);
                jdbcTemplate.update("""
                        UPDATE service07_fulfillments
                        SET vmcard_card_id = ?, status = 'CARD_CREATED', updated_at = ?
                        WHERE order_id = ?
                        """, cardId, now(), order.getId());
            }

            VmCardCheckoutCard card = vmCardClientService.getCheckoutCardDetail(cardId, cardRequest);
            String link = checkoutLink.join();
            jdbcTemplate.update("""
                    UPDATE service07_fulfillments
                    SET status = 'AUTOFILL_RUNNING', updated_at = ?
                    WHERE order_id = ?
                    """, now(), order.getId());

            String result = pythonClient.autoFill(session, link, card);
            String reference = "service07:" + order.getId() + ":" + cardId;
            plusOrderService.fulfillOrder(
                    order.getId(),
                    "FULFILLED",
                    "Service 07 自动订阅已完成：" + result,
                    order.getPaymentReference(),
                    reference);
            jdbcTemplate.update("""
                    UPDATE service07_fulfillments
                    SET status = 'SUCCEEDED', error_message = NULL,
                        updated_at = ?, completed_at = ?
                    WHERE order_id = ?
                    """, now(), now(), order.getId());
        } catch (RuntimeException exception) {
            RuntimeException failure = exception instanceof CompletionException completion
                    && completion.getCause() instanceof RuntimeException cause ? cause : exception;
            jdbcTemplate.update("""
                    UPDATE service07_fulfillments
                    SET status = 'FAILED', error_message = ?, updated_at = ?
                    WHERE order_id = ?
                    """, safeError(failure), now(), order.getId());
        } finally {
            checkoutLink.cancel(true);
        }
    }

    private void ensureWorkflowRow(PlusOrder order) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO service07_fulfillments(
                        order_id, user_id, status, attempt_count, created_at, updated_at
                    ) VALUES (?, ?, 'PENDING', 0, ?, ?)
                    """, order.getId(), order.getUserId(), now(), now());
        } catch (DuplicateKeyException ignored) {
            // The unique order id is the idempotency key for this workflow.
        }
    }

    private PlusOrder requirePaidServiceOrder(User user, Long orderId) {
        PlusOrder order = requireServiceOrder(user, orderId);
        if (!List.of("PAID", "FULFILLED").contains(normalize(order.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "订单支付成功后才能执行订阅");
        }
        return order;
    }

    private PlusOrder requireServiceOrder(User user, Long orderId) {
        if (orderId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId is required");
        }
        PlusOrder order = plusOrderService.getUserOrder(user, orderId);
        Long expectedProductId = catalogService.resolveProductIdForOrder(serviceId);
        if (!Objects.equals(order.getProductId(), expectedProductId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service 07 order not found");
        }
        return order;
    }

    private Map<String, Object> buildCardRequest(User user, PlusOrder order) {
        String email = configuredEmail == null || configuredEmail.isBlank()
                ? user.getEmail() : configuredEmail.trim();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Service 07 VMCard email is not configured and the user has no email");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("first_name", requiredConfig(firstName, "first-name"));
        request.put("last_name", requiredConfig(lastName, "last-name"));
        request.put("amount", Math.max(amount, 1));
        request.put("single_limit", 0);
        request.put("day_limit", 0);
        request.put("month_limit", 0);
        request.put("label", "service07-" + order.getOrderNo());
        request.put("card_address", Map.of(
                "address_line_one", requiredConfig(addressLineOne, "address-line-one"),
                "address_line_two", addressLineTwo == null ? "" : addressLineTwo.trim(),
                "city", requiredConfig(city, "city"),
                "state", requiredConfig(state, "state"),
                "country", requiredConfig(country, "country").toUpperCase(Locale.ROOT),
                "post_code", requiredConfig(postCode, "post-code")));
        request.put("area_code", requiredConfig(areaCode, "area-code"));
        request.put("mobile", requiredConfig(mobile, "mobile"));
        request.put("email", email.trim());
        return request;
    }

    private String normalizeSession(String value) {
        String session = value == null ? "" : value.trim();
        if (session.length() <= 50 || session.length() > MAX_SESSION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Session 格式无效，长度应为 51 到 " + MAX_SESSION_LENGTH + " 个字符");
        }
        return session;
    }

    private String requiredConfig(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Service 07 VMCard " + field + " is not configured");
        }
        return result;
    }

    private String safeError(RuntimeException exception) {
        String message = exception instanceof ResponseStatusException status && status.getReason() != null
                ? status.getReason() : exception.getMessage();
        if (message == null || message.isBlank()) message = "Service 07 fulfillment failed";
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.substring(0, Math.min(message.length(), 500));
    }

    private FulfillmentRow findRow(Long orderId) {
        FulfillmentRow row = findRowOrNull(orderId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fulfillment state not found");
        }
        return row;
    }

    private FulfillmentRow findRowOrNull(Long orderId) {
        List<FulfillmentRow> rows = jdbcTemplate.query("""
                SELECT id, order_id, status, vmcard_card_id, attempt_count,
                       error_message, updated_at, completed_at
                FROM service07_fulfillments
                WHERE order_id = ?
                """, (rs, index) -> new FulfillmentRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("status"),
                rs.getString("vmcard_card_id"),
                rs.getInt("attempt_count"),
                rs.getString("error_message"),
                rs.getTimestamp("updated_at") == null
                        ? null : rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") == null
                        ? null : rs.getTimestamp("completed_at").toLocalDateTime()
        ), orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public record FulfillmentView(
            Long orderId,
            String status,
            Integer attemptCount,
            String errorMessage,
            LocalDateTime completedAt
    ) {
    }

    private record FulfillmentRow(
            Long id,
            Long orderId,
            String status,
            String cardId,
            int attemptCount,
            String errorMessage,
            LocalDateTime updatedAt,
            LocalDateTime completedAt
    ) {
        FulfillmentView toView() {
            return new FulfillmentView(orderId, status, attemptCount, errorMessage, completedAt);
        }
    }
}
