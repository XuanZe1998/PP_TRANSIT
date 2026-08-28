package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.dto.ServiceOrderQuoteResponse;
import com.transit.dto.MoneyAmount;
import com.transit.mapper.OtherServiceMapper;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.mapper.ServiceCouponMapper;
import com.transit.mapper.ServiceInventoryItemMapper;
import com.transit.mapper.PaymentRefundJobMapper;
import com.transit.model.OtherService;
import com.transit.model.ServiceOrder;
import com.transit.model.ServiceCoupon;
import com.transit.model.ServiceInventoryItem;
import com.transit.model.PaymentRefundJob;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ServiceCommerceService {
    public static final String AUTOMATIC = "AUTOMATIC_DELIVERY";
    public static final String MANUAL = "MANUAL_PROCESSING";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String RESERVED = "RESERVED";
    public static final String DELIVERED = "DELIVERED";
    private static final java.util.regex.Pattern INVENTORY_DELIMITER = java.util.regex.Pattern.compile("[\\s,，、]+");
    private static final int MAX_INVENTORY_IMPORT_ITEMS = 10_000;

    private final OtherServiceCatalogService catalogService;
    private final OtherServiceMapper otherServiceMapper;
    private final ServiceOrderMapper orderMapper;
    private final ServiceInventoryItemMapper inventoryMapper;
    private final ServiceCouponMapper couponMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChannelSecretService secretService;
    private final PaymentRefundJobMapper refundJobMapper;

    @Value("${service-orders.reservation-minutes:15}")
    private long reservationMinutes;

    public ServiceOrderQuoteResponse quote(Long serviceId, Integer requestedQuantity, String couponCode) {
        OtherService service = catalogService.requirePurchasableService(serviceId);
        int quantity = requestedQuantity == null ? 1 : requestedQuantity;
        int maximum = service.getMaxPurchaseQuantity() == null ? 1 : service.getMaxPurchaseQuantity();
        if (quantity < 1 || quantity > maximum) {
            throw badRequest("quantity must be between 1 and " + maximum);
        }
        long listUnit = nonNegative(service.getPriceCents(), "service.priceCents");
        long effectiveUnit = tierPrice(service.getWholesaleTiersJson(), quantity, listUnit);
        long listSubtotal = multiply(listUnit, quantity);
        long merchandiseSubtotal = multiply(effectiveUnit, quantity);
        long wholesaleDiscount = listSubtotal - merchandiseSubtotal;
        ServiceCoupon coupon = resolveCoupon(serviceId, couponCode, false);
        long couponDiscount = coupon == null ? 0 : Math.min(merchandiseSubtotal, nonNegative(coupon.getDiscountCents(), "coupon.discountCents"));
        long serviceFee = nonNegative(service.getServiceFeeCents(), "service.serviceFeeCents");
        long amount = add(merchandiseSubtotal - couponDiscount, serviceFee);
        if (amount <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "The final payment amount must be positive");
        Integer stock = availableStock(service);
        boolean available = stock == null || stock >= quantity;
        return ServiceOrderQuoteResponse.builder()
                .serviceId(service.getId()).serviceName(service.getName())
                .fulfillmentMode(normalizeMode(service.getFulfillmentMode())).quantity(quantity)
                .listUnitPriceCents(listUnit).effectiveUnitPriceCents(effectiveUnit)
                .merchandiseSubtotalCents(merchandiseSubtotal).wholesaleDiscountCents(wholesaleDiscount)
                .couponDiscountCents(couponDiscount).serviceFeeCents(serviceFee).amountCents(amount)
                .currency(service.getCurrency()).availableStock(stock).available(available)
                .purchasePrompt(service.getPurchasePrompt()).inputFields(inputFields(service.getInputSchemaJson()))
                .couponId(coupon == null ? null : coupon.getId()).couponCode(coupon == null ? null : coupon.getCode())
                .listUnitPriceMoney(MoneyAmount.cents(listUnit, service.getCurrency()))
                .effectiveUnitPriceMoney(MoneyAmount.cents(effectiveUnit, service.getCurrency()))
                .merchandiseSubtotalMoney(MoneyAmount.cents(merchandiseSubtotal, service.getCurrency()))
                .wholesaleDiscountMoney(MoneyAmount.cents(wholesaleDiscount, service.getCurrency()))
                .couponDiscountMoney(MoneyAmount.cents(couponDiscount, service.getCurrency()))
                .serviceFeeMoney(MoneyAmount.cents(serviceFee, service.getCurrency()))
                .amountMoney(MoneyAmount.cents(amount, service.getCurrency()))
                .build();
    }

    @Transactional
    public void reserve(ServiceOrder order, Map<String, String> customFields) {
        if (order.getServiceId() == null) return;
        validateCustomFields(order.getServiceId(), customFields);
        LocalDateTime expiresAt = now().plusMinutes(Math.max(1, reservationMinutes));
        order.setReservationExpiresAt(expiresAt);
        order.setCustomInputJson(writeJson(customFields == null ? Map.of() : customFields));
        if (!AUTOMATIC.equals(order.getFulfillmentMode())) {
            int updated = jdbcTemplate.update("UPDATE other_services SET manual_reserved=manual_reserved+? WHERE id=? AND (manual_stock IS NULL OR manual_stock-manual_reserved>=?)",
                    order.getQuantity(), order.getServiceId(), order.getQuantity());
            if (updated != 1) throw conflict("Insufficient manual-processing stock");
        }
        if (order.getCouponId() != null) {
            int updated = jdbcTemplate.update("UPDATE service_coupons SET remaining_uses=remaining_uses-1, reserved_uses=reserved_uses+1, updated_at=? WHERE id=? AND enabled=TRUE AND remaining_uses>0",
                    now(), order.getCouponId());
            if (updated != 1) throw conflict("Coupon is no longer available");
            order.setCouponReservationActive(true);
        }
        orderMapper.updateById(order);
    }

    @Transactional
    public ServiceOrder settlePaid(ServiceOrder order) {
        if (order.getServiceId() == null || "COMPLETED".equals(order.getFulfillmentStatus())) return order;
        int claimed = jdbcTemplate.update("UPDATE service_orders SET fulfillment_status='SETTLING', updated_at=? WHERE id=? AND (fulfillment_status IS NULL OR fulfillment_status IN ('RESERVED','RELEASED','FAILED','PENDING'))",
                now(), order.getId());
        if (claimed != 1) {
            ServiceOrder latest = orderMapper.selectById(order.getId());
            return latest == null ? order : revealDelivery(latest);
        }
        order.setFulfillmentStatus("SETTLING");
        if (AUTOMATIC.equals(order.getFulfillmentMode())) {
            List<ServiceInventoryItem> items = inventoryMapper.selectList(new LambdaQueryWrapper<ServiceInventoryItem>()
                    .eq(ServiceInventoryItem::getReservedOrderId, order.getId()).eq(ServiceInventoryItem::getStatus, RESERVED)
                    .orderByAsc(ServiceInventoryItem::getId));
            if (items.size() != order.getQuantity()) {
                try {
                    List<Long> allocatedIds = reserveLateAutomatic(order);
                    // The allocation uses JdbcTemplate while the first empty read
                    // uses MyBatis. Querying by a distinct statement key avoids a
                    // stale first-level MyBatis cache entry in this transaction.
                    items = inventoryMapper.selectBatchIds(allocatedIds).stream()
                            .filter(item -> Objects.equals(item.getReservedOrderId(), order.getId()))
                            .filter(item -> RESERVED.equals(item.getStatus()))
                            .sorted(Comparator.comparing(ServiceInventoryItem::getId))
                            .toList();
                } catch (ResponseStatusException unavailable) {
                    jdbcTemplate.update("UPDATE service_inventory_items SET status='AVAILABLE', reserved_order_id=NULL, reserved_until=NULL WHERE reserved_order_id=? AND status='RESERVED'", order.getId());
                    order.setStatus("REFUND_PENDING");
                    order.setFulfillmentStatus("REFUND_PENDING");
                    order.setFulfillmentNote("付款已确认，但并发分配时库存已售罄，系统正在自动退款。");
                    order.setUpdatedAt(now());
                    consumeCouponReservation(order);
                    orderMapper.updateById(order);
                    enqueueRefund(order, "Paid automatic-delivery order could not allocate stock");
                    return order;
                }
            }
            List<String> delivery = items.stream().map(i -> secretService.decrypt(i.getContentEncrypted())).toList();
            LocalDateTime now = now();
            int delivered = jdbcTemplate.update("UPDATE service_inventory_items SET status='DELIVERED', delivered_at=?, reserved_until=NULL WHERE reserved_order_id=? AND status='RESERVED'",
                    now, order.getId());
            if (delivered != order.getQuantity()) throw conflict("Automatic delivery changed concurrently");
            order.setDeliveryContentEncrypted(secretService.encrypt(writeJson(delivery)));
            order.setDeliveryItems(delivery);
            order.setFulfillmentStatus("COMPLETED");
            order.setStatus("FULFILLED");
            order.setFulfillmentReference("AUTO-" + order.getOrderNo());
            order.setFulfillmentNote("Automatic delivery completed.");
            order.setFulfilledAt(now);
        } else {
            if (consumeManualReservation(order)) {
                order.setFulfillmentStatus("PENDING");
                order.setFulfillmentNote("Payment verified; awaiting manual processing.");
            }
        }
        consumeCouponReservation(order);
        order.setReservationExpiresAt(null);
        order.setFulfillmentStatus(order.getFulfillmentStatus() == null ? "FAILED" : order.getFulfillmentStatus());
        order.setUpdatedAt(now());
        orderMapper.updateById(order);
        return order;
    }

    @Transactional
    public ServiceOrder completeManual(Long orderId, String deliveryContent, String note) {
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        if (!MANUAL.equals(order.getFulfillmentMode()) || !"PAID".equals(order.getStatus())) {
            throw conflict("Only a paid manual-processing order can be completed");
        }
        String content = requiredText(deliveryContent, "deliveryContent", 20000);
        order.setDeliveryContentEncrypted(secretService.encrypt(writeJson(List.of(content))));
        order.setDeliveryItems(List.of(content));
        order.setFulfillmentStatus("COMPLETED");
        order.setStatus("FULFILLED");
        order.setFulfillmentReference("MANUAL-" + order.getOrderNo());
        order.setFulfillmentNote(note == null || note.isBlank() ? "Manual delivery completed." : note.trim());
        order.setFulfilledAt(now());
        order.setUpdatedAt(now());
        orderMapper.updateById(order);
        return order;
    }

    public ServiceOrder revealDelivery(ServiceOrder order) {
        if (order != null && order.getCustomInputJson() != null) {
            try {
                order.setCustomFields(objectMapper.readValue(order.getCustomInputJson(), new TypeReference<>() {}));
            } catch (Exception invalid) {
                throw new IllegalStateException("Unable to read custom field snapshot", invalid);
            }
        }
        if (order != null && order.getDeliveryContentEncrypted() != null && "COMPLETED".equals(order.getFulfillmentStatus())) {
            try {
                order.setDeliveryItems(objectMapper.readValue(secretService.decrypt(order.getDeliveryContentEncrypted()), new TypeReference<>() {}));
            } catch (Exception invalid) {
                throw new IllegalStateException("Unable to read delivered content", invalid);
            }
        }
        return order;
    }

    @Scheduled(fixedDelayString = "${service-orders.expiry-scan-ms:60000}",
            initialDelayString = "${service-orders.expiry-initial-delay-ms:60000}")
    @Transactional
    public void expirePendingOrders() {
        List<ServiceOrder> expired = orderMapper.selectList(new LambdaQueryWrapper<ServiceOrder>()
                .in(ServiceOrder::getStatus, "PENDING", "CONFIRMED")
                .lt(ServiceOrder::getReservationExpiresAt, now()));
        for (ServiceOrder order : expired) release(order, true);
    }

    @Transactional
    public void release(ServiceOrder order, boolean markExpired) {
        if (order.getServiceId() == null) return;
        int stock = jdbcTemplate.update("UPDATE service_inventory_items SET status='AVAILABLE', reserved_order_id=NULL, reserved_until=NULL WHERE reserved_order_id=? AND status='RESERVED'", order.getId());
        if (MANUAL.equals(order.getFulfillmentMode()) && stock == 0) {
            jdbcTemplate.update("UPDATE other_services SET manual_reserved=CASE WHEN manual_reserved>=? THEN manual_reserved-? ELSE 0 END WHERE id=?",
                    order.getQuantity(), order.getQuantity(), order.getServiceId());
        }
        if (order.getCouponId() != null && Boolean.TRUE.equals(order.getCouponReservationActive())) {
            jdbcTemplate.update("UPDATE service_coupons SET remaining_uses=remaining_uses+1, reserved_uses=CASE WHEN reserved_uses>0 THEN reserved_uses-1 ELSE 0 END WHERE id=? AND reserved_uses>0", order.getCouponId());
            order.setCouponReservationActive(false);
        }
        order.setReservationExpiresAt(null);
        order.setFulfillmentStatus("RELEASED");
        if (markExpired) order.setStatus("EXPIRED");
        order.setUpdatedAt(now());
        orderMapper.updateById(order);
    }

    /** Restores only resources that can safely be reused after a paid order is refunded. */
    @Transactional
    public void releasePaidResourcesForRefund(ServiceOrder order) {
        if (order == null || order.getServiceId() == null || Boolean.TRUE.equals(order.getRefundResourcesReleased())) return;
        if ("COMPLETED".equals(order.getFulfillmentStatus()) || "FULFILLED".equals(order.getStatus())) {
            throw conflict("Delivered orders cannot restore inventory");
        }
        int restoredAutomatic = jdbcTemplate.update(
                "UPDATE service_inventory_items SET status='AVAILABLE', reserved_order_id=NULL, reserved_until=NULL WHERE reserved_order_id=? AND status='RESERVED'",
                order.getId());
        if (MANUAL.equals(order.getFulfillmentMode()) && restoredAutomatic == 0 && "PENDING".equals(order.getFulfillmentStatus())) {
            jdbcTemplate.update("UPDATE other_services SET manual_stock=CASE WHEN manual_stock IS NULL THEN NULL ELSE manual_stock+? END WHERE id=?",
                    order.getQuantity(), order.getServiceId());
        }
        if (order.getCouponId() != null) {
            jdbcTemplate.update("UPDATE service_coupons SET remaining_uses=remaining_uses+1, updated_at=? WHERE id=?", now(), order.getCouponId());
        }
        order.setRefundResourcesReleased(true);
        order.setReservationExpiresAt(null);
        orderMapper.updateById(order);
    }

    @Transactional
    public int importInventory(Long serviceId, String content) {
        OtherService service = otherServiceMapper.selectById(serviceId);
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        if (!AUTOMATIC.equals(normalizeMode(service.getFulfillmentMode()))) throw conflict("Inventory can only be imported for automatic-delivery services");
        if (!secretService.isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory encryption is not configured");
        int inserted = 0;
        for (String itemContent : parseInventoryItems(content)) {
            if (itemContent.length() > 10000) throw badRequest("Each inventory item must be at most 10000 characters");
            ServiceInventoryItem item = new ServiceInventoryItem();
            item.setServiceId(serviceId); item.setContentEncrypted(secretService.encrypt(itemContent)); item.setContentFingerprint(fingerprint(itemContent));
            item.setSecretPreview(secretPreview(itemContent));
            item.setStatus(AVAILABLE); item.setCreatedAt(now());
            try { inventoryMapper.insert(item); inserted++; } catch (RuntimeException duplicate) { /* duplicate import is ignored */ }
        }
        return inserted;
    }

    static List<String> parseInventoryItems(String content) {
        if (content == null || content.isBlank()) return List.of();
        List<String> items = Arrays.stream(INVENTORY_DELIMITER.split(content.trim()))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
        if (items.size() > MAX_INVENTORY_IMPORT_ITEMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At most " + MAX_INVENTORY_IMPORT_ITEMS + " inventory items can be imported at once");
        }
        return items;
    }

    private List<Long> reserveLateAutomatic(ServiceOrder order) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM service_inventory_items WHERE service_id=? AND status='AVAILABLE' ORDER BY id LIMIT ? FOR UPDATE",
                Long.class, order.getServiceId(), order.getQuantity());
        if (ids.size() != order.getQuantity()) throw conflict("Insufficient stock for a late payment");
        for (Long id : ids) {
            int changed=jdbcTemplate.update("UPDATE service_inventory_items SET status='RESERVED', reserved_order_id=? WHERE id=? AND status='AVAILABLE'", order.getId(), id);
            if(changed!=1)throw conflict("Inventory changed concurrently during paid allocation");
        }
        return ids;
    }

    private void enqueueRefund(ServiceOrder order, String reason) {
        List<Long> intents=jdbcTemplate.queryForList("SELECT id FROM payment_intents WHERE business_type='SERVICE_ORDER' AND business_id=? ORDER BY id DESC LIMIT 1",Long.class,order.getId());
        if(intents.isEmpty())return;
        Long intentId=intents.get(0);
        if(refundJobMapper.selectCount(new LambdaQueryWrapper<PaymentRefundJob>().eq(PaymentRefundJob::getPaymentIntentId,intentId))>0)return;
        PaymentRefundJob job=new PaymentRefundJob();LocalDateTime now=now();job.setPaymentIntentId(intentId);job.setServiceOrderId(order.getId());
        job.setReason(reason);job.setStatus("PENDING");job.setAttempts(0);job.setNextAttemptAt(now);job.setCreatedAt(now);job.setUpdatedAt(now);refundJobMapper.insert(job);
    }

    private boolean consumeManualReservation(ServiceOrder order) {
        int changed = jdbcTemplate.update("UPDATE other_services SET manual_reserved=CASE WHEN manual_reserved>=? THEN manual_reserved-? ELSE 0 END, manual_stock=CASE WHEN manual_stock IS NULL THEN NULL ELSE manual_stock-? END WHERE id=? AND (manual_stock IS NULL OR manual_stock>=?)",
                order.getQuantity(), order.getQuantity(), order.getQuantity(), order.getServiceId(), order.getQuantity());
        if (changed != 1) {
            order.setFulfillmentStatus("FAILED");
            order.setFulfillmentNote("Payment received, but manual stock is unavailable. Administrator action is required.");
            return false;
        }
        return true;
    }

    public Map<String, Long> inventoryStats(Long serviceId) {
        if (otherServiceMapper.selectById(serviceId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : List.of(AVAILABLE, RESERVED, DELIVERED)) {
            result.put(status, inventoryMapper.selectCount(new LambdaQueryWrapper<ServiceInventoryItem>()
                    .eq(ServiceInventoryItem::getServiceId, serviceId).eq(ServiceInventoryItem::getStatus, status)));
        }
        return result;
    }

    public List<ServiceInventoryItem> listInventory(Long serviceId, String status) {
        LambdaQueryWrapper<ServiceInventoryItem> query = new LambdaQueryWrapper<ServiceInventoryItem>()
                .eq(ServiceInventoryItem::getServiceId, serviceId).orderByDesc(ServiceInventoryItem::getId).last("LIMIT 500");
        if (status != null && !status.isBlank()) query.eq(ServiceInventoryItem::getStatus, status.trim().toUpperCase(Locale.ROOT));
        return inventoryMapper.selectList(query);
    }

    public void deleteAvailableInventory(Long serviceId, Long inventoryId) {
        int deleted = inventoryMapper.delete(new LambdaQueryWrapper<ServiceInventoryItem>()
                .eq(ServiceInventoryItem::getId, inventoryId).eq(ServiceInventoryItem::getServiceId, serviceId)
                .eq(ServiceInventoryItem::getStatus, AVAILABLE));
        if (deleted != 1) throw conflict("Only an unsold available inventory item can be deleted");
    }

    public ServiceInventoryItem replaceAvailableInventory(Long serviceId, Long inventoryId, String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank() || normalized.length() > 10000) throw badRequest("卡密内容需为 1–10000 个字符");
        if (!secretService.isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory encryption is not configured");
        try {
            int changed = jdbcTemplate.update("UPDATE service_inventory_items SET content_encrypted=?,content_fingerprint=?,secret_preview=? WHERE id=? AND service_id=? AND status='AVAILABLE'",
                    secretService.encrypt(normalized), fingerprint(normalized), secretPreview(normalized), inventoryId, serviceId);
            if (changed != 1) throw conflict("只能替换未售出的可用卡密");
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw conflict("该卡密已存在于库存中");
        }
        return inventoryMapper.selectById(inventoryId);
    }

    private String secretPreview(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= 4) return "****";
        return "****" + normalized.substring(normalized.length() - 4);
    }

    private void consumeCouponReservation(ServiceOrder order) {
        if (order.getCouponId() != null && Boolean.TRUE.equals(order.getCouponReservationActive())) {
            jdbcTemplate.update("UPDATE service_coupons SET reserved_uses=CASE WHEN reserved_uses>0 THEN reserved_uses-1 ELSE 0 END WHERE id=?", order.getCouponId());
            order.setCouponReservationActive(false);
        }
    }

    private ServiceCoupon resolveCoupon(Long serviceId, String code, boolean lock) {
        if (code == null || code.isBlank()) return null;
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        List<ServiceCoupon> found = jdbcTemplate.query("SELECT c.* FROM service_coupons c JOIN service_coupon_services cs ON cs.coupon_id=c.id WHERE c.code=? AND cs.service_id=? AND c.enabled=TRUE AND c.remaining_uses>0" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> { ServiceCoupon c = new ServiceCoupon(); c.setId(rs.getLong("id")); c.setCode(rs.getString("code")); c.setDiscountCents(rs.getLong("discount_cents")); c.setRemainingUses(rs.getInt("remaining_uses")); c.setReservedUses(rs.getInt("reserved_uses")); c.setEnabled(rs.getBoolean("enabled")); return c; }, normalized, serviceId);
        if (found.isEmpty()) throw badRequest("Coupon is invalid, disabled, exhausted, or not applicable to this service");
        return found.get(0);
    }

    private Integer availableStock(OtherService service) {
        if (AUTOMATIC.equals(normalizeMode(service.getFulfillmentMode()))) return Math.toIntExact(inventoryMapper.selectCount(new LambdaQueryWrapper<ServiceInventoryItem>().eq(ServiceInventoryItem::getServiceId, service.getId()).eq(ServiceInventoryItem::getStatus, AVAILABLE)));
        if (service.getManualStock() == null) return null;
        return Math.max(0, service.getManualStock() - (service.getManualReserved() == null ? 0 : service.getManualReserved()));
    }

    private List<ServiceOrderQuoteResponse.InputField> inputFields(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ServiceOrderQuoteResponse.InputField> result = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(json)) result.add(new ServiceOrderQuoteResponse.InputField(node.path("key").asText(), node.path("label").asText(), node.path("required").asBoolean(false), node.path("maxLength").asInt(200)));
            return result;
        } catch (Exception invalid) { throw conflict("Stored custom field definition is invalid"); }
    }

    private void validateCustomFields(Long serviceId, Map<String, String> values) {
        OtherService service = otherServiceMapper.selectById(serviceId);
        Map<String, String> safe = values == null ? Map.of() : values;
        Set<String> allowed = new HashSet<>();
        for (ServiceOrderQuoteResponse.InputField field : inputFields(service.getInputSchemaJson())) {
            allowed.add(field.key()); String value = safe.get(field.key());
            if (field.required() && (value == null || value.isBlank())) throw badRequest(field.label() + " is required");
            if (value != null && value.length() > field.maxLength()) throw badRequest(field.label() + " is too long");
        }
        if (!allowed.containsAll(safe.keySet())) throw badRequest("customFields contains an unknown field");
    }

    private long tierPrice(String json, int quantity, long defaultPrice) {
        long price = defaultPrice;
        if (json == null || json.isBlank()) return price;
        try { for (JsonNode tier : objectMapper.readTree(json)) if (quantity >= tier.path("minQuantity").asInt()) price = Math.min(defaultPrice, tier.path("unitPriceCents").asLong()); }
        catch (Exception invalid) { throw conflict("Stored wholesale tiers are invalid"); }
        return price;
    }

    private String normalizeMode(String value) { return AUTOMATIC.equals(value) ? AUTOMATIC : MANUAL; }
    private long nonNegative(Long value, String field) { if (value == null || value < 0) throw conflict(field + " is invalid"); return value; }
    private long multiply(long value, int quantity) { try { return Math.multiplyExact(value, quantity); } catch (ArithmeticException e) { throw conflict("Price is outside the supported range"); } }
    private long add(long a, long b) { try { return Math.addExact(a, b); } catch (ArithmeticException e) { throw conflict("Price is outside the supported range"); } }
    private String writeJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Unable to store order snapshot", e); } }
    private String fingerprint(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String requiredText(String value, String field, int max) { if (value == null || value.isBlank()) throw badRequest(field + " is required"); String result=value.trim(); if(result.length()>max) throw badRequest(field+" is too long"); return result; }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
