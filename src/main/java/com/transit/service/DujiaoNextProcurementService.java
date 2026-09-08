package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.DujiaoNextProperties;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.model.OtherService;
import com.transit.model.ServiceOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class DujiaoNextProcurementService {
    public static final String SUPPLIER = OtherServiceCatalogService.DUJIAO_NEXT;
    private static final List<String> DELIVERED = List.of("delivered", "completed");

    private final DujiaoNextClient client;
    private final DujiaoNextProperties properties;
    private final ServiceOrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChannelSecretService secretService;

    @Transactional
    public ServiceOrder enqueue(ServiceOrder order, OtherService service) {
        if (order == null || order.getId() == null || service == null
                || !SUPPLIER.equals(normalize(service.getSupplierType()))) return order;
        Long productId = order.getSupplierProductId() == null ? service.getSupplierProductId() : order.getSupplierProductId();
        Long skuId = order.getSupplierSkuId() == null ? service.getSupplierSkuId() : order.getSupplierSkuId();
        if (productId == null || productId < 1 || skuId == null || skuId < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dujiao-Next product mapping is incomplete");
        }
        order.setSupplierType(SUPPLIER);
        order.setSupplierProductId(productId);
        order.setSupplierSkuId(skuId);
        order.setProcurementAttempts(order.getProcurementAttempts() == null ? 0 : order.getProcurementAttempts());
        order.setNextProcurementAt(now());
        order.setSupplierError(null);
        if (order.getSupplierOrderId() == null) {
            order.setFulfillmentStatus("PROCUREMENT_PENDING");
            order.setFulfillmentNote("Payment verified; queued for Dujiao-Next procurement.");
        } else {
            order.setFulfillmentStatus("PROCUREMENT_ACCEPTED");
            order.setFulfillmentNote("Dujiao-Next order accepted; waiting for delivery.");
        }
        order.setUpdatedAt(now());
        orderMapper.updateById(order);
        return order;
    }

    @Scheduled(fixedDelayString = "${dujiao-next.worker-interval-ms:2000}",
            initialDelayString = "${dujiao-next.worker-initial-delay-ms:5000}")
    public void processDueOrders() {
        if (!properties.purchasesEnabled()) return;
        recoverAbandonedClaims();
        int limit = Math.max(1, Math.min(100, properties.getBatchSize()));
        LocalDateTime current = now();
        List<Long> creates = jdbcTemplate.queryForList("""
                SELECT id FROM service_orders
                WHERE supplier_type=? AND status='PAID' AND supplier_order_id IS NULL
                  AND fulfillment_status IN ('PROCUREMENT_PENDING','PROCUREMENT_RETRY')
                  AND (next_procurement_at IS NULL OR next_procurement_at<=?)
                ORDER BY id LIMIT ?
                """, Long.class, SUPPLIER, current, limit);
        for (Long id : creates) create(id);

        List<Long> polls = jdbcTemplate.queryForList("""
                SELECT id FROM service_orders
                WHERE supplier_type=? AND status='PAID' AND supplier_order_id IS NOT NULL
                  AND fulfillment_status IN ('PROCUREMENT_ACCEPTED','PROCUREMENT_POLL_RETRY')
                  AND (next_procurement_at IS NULL OR next_procurement_at<=?)
                ORDER BY id LIMIT ?
                """, Long.class, SUPPLIER, current, limit);
        for (Long id : polls) poll(id);
    }

    public void handleCallback(JsonNode callback) {
        if (callback == null || !callback.isObject()) throw badRequest("Invalid Dujiao-Next callback body");
        String downstreamOrderNo = text(callback.path("downstream_order_no"));
        if (downstreamOrderNo == null) throw badRequest("Dujiao-Next callback is missing downstream_order_no");
        ServiceOrder order = orderMapper.selectOne(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderNo, downstreamOrderNo).last("LIMIT 1"));
        if (order == null || !SUPPLIER.equals(normalize(order.getSupplierType()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dujiao-Next service order not found");
        }
        Long callbackOrderId = positiveLong(callback.path("order_id"));
        if (order.getSupplierOrderId() != null && callbackOrderId != null
                && !Objects.equals(order.getSupplierOrderId(), callbackOrderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dujiao-Next callback order id does not match");
        }
        applySupplierState(order, callback);
    }

    /** Cancels or fences upstream work before the local payment refund starts. */
    public void prepareRefund(ServiceOrder order) {
        if (order == null || !SUPPLIER.equals(normalize(order.getSupplierType()))) return;
        if ("COMPLETED".equals(order.getFulfillmentStatus()) || "FULFILLED".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivered Dujiao-Next orders cannot be refunded automatically");
        }
        if (order.getSupplierOrderId() == null) {
            int fenced = jdbcTemplate.update("""
                    UPDATE service_orders SET fulfillment_status='PROCUREMENT_CANCELLED',next_procurement_at=NULL,updated_at=?
                    WHERE id=? AND supplier_order_id IS NULL
                      AND fulfillment_status IN ('PROCUREMENT_PENDING','PROCUREMENT_RETRY','FAILED')
                    """, now(), order.getId());
            if (fenced != 1 && !"PROCUREMENT_CANCELLED".equals(order.getFulfillmentStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Dujiao-Next procurement is currently being submitted; retry the refund shortly");
            }
            order.setFulfillmentStatus("PROCUREMENT_CANCELLED");
            order.setNextProcurementAt(null);
            return;
        }
        if ("canceled".equals(normalizeLower(order.getSupplierStatus()))) return;
        JsonNode canceled = client.cancel(order.getSupplierOrderId());
        String status = normalizeLower(canceled.path("status").asText());
        if (!"canceled".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dujiao-Next order was not canceled; local refund stopped");
        }
        order.setSupplierStatus(status);
        order.setFulfillmentStatus("PROCUREMENT_CANCELLED");
        order.setNextProcurementAt(null);
        order.setUpdatedAt(now());
        orderMapper.updateById(order);
    }

    private void create(Long id) {
        int claimed = jdbcTemplate.update("""
                UPDATE service_orders SET fulfillment_status='PROCUREMENT_PROCESSING',updated_at=?
                WHERE id=? AND status='PAID' AND supplier_order_id IS NULL
                  AND fulfillment_status IN ('PROCUREMENT_PENDING','PROCUREMENT_RETRY')
                """, now(), id);
        if (claimed != 1) return;
        ServiceOrder order = orderMapper.selectById(id);
        try {
            JsonNode response = client.createOrder(order.getSupplierSkuId(), order.getQuantity(), order.getOrderNo(),
                    manualForm(order), "service-order-" + order.getId());
            Long upstreamId = positiveLong(response.path("order_id"));
            if (upstreamId == null) throw new DujiaoNextApiException("invalid_response",
                    "Dujiao-Next create order response is missing order_id", 502, false);
            ServiceOrder latest = orderMapper.selectById(id);
            if (latest == null || !"PAID".equals(latest.getStatus())) return;
            latest.setSupplierOrderId(upstreamId);
            latest.setSupplierOrderNo(text(response.path("order_no")));
            latest.setSupplierStatus(normalizeLower(response.path("status").asText()));
            latest.setSupplierAmount(text(response.path("amount")));
            latest.setSupplierCurrency(upper(text(response.path("currency"))));
            latest.setSupplierError(null);
            latest.setProcurementAttempts((latest.getProcurementAttempts() == null ? 0 : latest.getProcurementAttempts()) + 1);
            latest.setFulfillmentStatus("PROCUREMENT_ACCEPTED");
            latest.setFulfillmentNote("Dujiao-Next order accepted; waiting for delivery.");
            latest.setNextProcurementAt(now().plusSeconds(Math.max(5, properties.getPollIntervalSeconds())));
            latest.setUpdatedAt(now());
            orderMapper.updateById(latest);
            pollAccepted(latest);
        } catch (RuntimeException exception) {
            failCreate(order, exception);
        }
    }

    private void poll(Long id) {
        int claimed = jdbcTemplate.update("""
                UPDATE service_orders SET fulfillment_status='PROCUREMENT_POLLING',updated_at=?
                WHERE id=? AND status='PAID' AND supplier_order_id IS NOT NULL
                  AND fulfillment_status IN ('PROCUREMENT_ACCEPTED','PROCUREMENT_POLL_RETRY')
                """, now(), id);
        if (claimed != 1) return;
        ServiceOrder order = orderMapper.selectById(id);
        pollAccepted(order);
    }

    private void pollAccepted(ServiceOrder order) {
        try {
            JsonNode response = client.order(order.getSupplierOrderId());
            applySupplierState(orderMapper.selectById(order.getId()), response);
        } catch (RuntimeException exception) {
            ServiceOrder latest = orderMapper.selectById(order.getId());
            if (latest == null || !"PAID".equals(latest.getStatus())) return;
            latest.setFulfillmentStatus("PROCUREMENT_POLL_RETRY");
            latest.setSupplierError(safeMessage(exception));
            latest.setFulfillmentNote("Dujiao-Next order accepted, but status polling failed; retry scheduled.");
            latest.setNextProcurementAt(now().plusSeconds(backoff(latest.getProcurementAttempts() == null ? 1 : latest.getProcurementAttempts())));
            latest.setUpdatedAt(now());
            orderMapper.updateById(latest);
        }
    }

    private void applySupplierState(ServiceOrder order, JsonNode response) {
        if (order == null || "REFUNDED".equals(order.getStatus()) || "REFUND_PENDING".equals(order.getStatus())) return;
        if ("FULFILLED".equals(order.getStatus())) return;
        if (!"PAID".equals(order.getStatus())) return;
        Long upstreamId = positiveLong(response.path("order_id"));
        if (order.getSupplierOrderId() == null) order.setSupplierOrderId(upstreamId);
        order.setSupplierOrderNo(first(text(response.path("order_no")), order.getSupplierOrderNo()));
        String status = normalizeLower(response.path("status").asText());
        order.setSupplierStatus(status);
        order.setSupplierAmount(first(text(response.path("amount")), order.getSupplierAmount()));
        order.setSupplierCurrency(first(upper(text(response.path("currency"))), order.getSupplierCurrency()));
        order.setSupplierError(null);
        order.setUpdatedAt(now());
        if (DELIVERED.contains(status)) {
            String delivery = delivery(response.path("fulfillment"));
            if (delivery == null) {
                order.setFulfillmentStatus("PROCUREMENT_POLL_RETRY");
                order.setFulfillmentNote("Dujiao-Next reported completion without delivery content; polling again.");
                order.setNextProcurementAt(now().plusSeconds(Math.max(5, properties.getPollIntervalSeconds())));
            } else {
                order.setDeliveryContentEncrypted(secretService.encrypt(writeJson(List.of(delivery))));
                order.setDeliveryItems(List.of(delivery));
                order.setFulfillmentStatus("COMPLETED");
                order.setStatus("FULFILLED");
                order.setFulfillmentReference("DUJIAO-" + first(order.getSupplierOrderNo(), String.valueOf(order.getSupplierOrderId())));
                order.setFulfillmentNote("Dujiao-Next delivery completed.");
                order.setFulfilledAt(now());
                order.setNextProcurementAt(null);
            }
        } else if ("canceled".equals(status)) {
            order.setFulfillmentStatus("FAILED");
            order.setFulfillmentNote("Dujiao-Next canceled the procurement order; customer payment requires administrator review/refund.");
            order.setNextProcurementAt(null);
        } else {
            order.setFulfillmentStatus("PROCUREMENT_ACCEPTED");
            order.setFulfillmentNote("Dujiao-Next order is " + (status.isBlank() ? "processing" : status) + "; waiting for delivery.");
            order.setNextProcurementAt(now().plusSeconds(Math.max(5, properties.getPollIntervalSeconds())));
        }
        orderMapper.updateById(order);
    }

    private void failCreate(ServiceOrder claimed, RuntimeException exception) {
        ServiceOrder order = orderMapper.selectById(claimed.getId());
        if (order == null || !"PAID".equals(order.getStatus()) || order.getSupplierOrderId() != null) return;
        int attempts = (order.getProcurementAttempts() == null ? 0 : order.getProcurementAttempts()) + 1;
        boolean retryable = !(exception instanceof DujiaoNextApiException api) || api.isRetryable();
        boolean retry = retryable && attempts < Math.max(1, properties.getMaxAttempts());
        order.setProcurementAttempts(attempts);
        order.setSupplierError(safeMessage(exception));
        order.setFulfillmentStatus(retry ? "PROCUREMENT_RETRY" : "FAILED");
        order.setFulfillmentNote(retry
                ? "Dujiao-Next procurement failed temporarily; retry scheduled."
                : "Dujiao-Next procurement failed; administrator review/refund required.");
        order.setNextProcurementAt(retry ? now().plusSeconds(backoff(attempts)) : null);
        order.setUpdatedAt(now());
        orderMapper.updateById(order);
        log.warn("Dujiao-Next procurement failed for service order {}: {}", order.getOrderNo(), safeMessage(exception));
    }

    private void recoverAbandonedClaims() {
        LocalDateTime cutoff = now().minusMinutes(5);
        jdbcTemplate.update("""
                UPDATE service_orders SET fulfillment_status=CASE WHEN supplier_order_id IS NULL THEN 'PROCUREMENT_RETRY' ELSE 'PROCUREMENT_POLL_RETRY' END,
                    next_procurement_at=?,updated_at=?
                WHERE supplier_type=? AND status='PAID'
                  AND fulfillment_status IN ('PROCUREMENT_PROCESSING','PROCUREMENT_POLLING') AND updated_at<?
                """, now(), now(), SUPPLIER, cutoff);
    }

    private Map<String, String> manualForm(ServiceOrder order) {
        if (order.getCustomInputJson() == null || order.getCustomInputJson().isBlank()) return Map.of();
        try {
            return objectMapper.readValue(order.getCustomInputJson(), new TypeReference<>() {});
        } catch (Exception invalid) {
            throw new DujiaoNextApiException("invalid_manual_form", "Stored buyer form is invalid", 400, false);
        }
    }

    private String delivery(JsonNode fulfillment) {
        if (fulfillment == null || fulfillment.isMissingNode() || fulfillment.isNull()) return null;
        String payload = text(fulfillment.path("payload"));
        if (payload != null) return payload;
        JsonNode structured = fulfillment.path("delivery_data");
        if (!structured.isMissingNode() && !structured.isNull()) return structured.toString();
        return null;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to store Dujiao-Next delivery", exception); }
    }

    private long backoff(int attempts) { return Math.min(300, Math.max(2, 1L << Math.min(8, attempts))); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private Long positiveLong(JsonNode node) { return node != null && node.canConvertToLong() && node.asLong() > 0 ? node.asLong() : null; }
    private String text(JsonNode node) { return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText().trim(); }
    private String first(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String upper(String value) { return value == null ? null : value.toUpperCase(Locale.ROOT); }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String normalizeLower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private String safeMessage(Throwable error) {
        String message = error == null || error.getMessage() == null ? "Unknown Dujiao-Next error" : error.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
