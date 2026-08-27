package com.transit.controller;

import com.transit.dto.ServiceOrderResponse;
import com.transit.dto.ServiceOrderRequest;
import com.transit.dto.ServiceOrderQuoteRequest;
import com.transit.dto.ServiceOrderQuoteResponse;
import com.transit.model.ServiceOrder;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.ServiceOrderService;
import com.transit.service.ServiceCommerceService;
import com.transit.service.ServiceCouponAdminService;
import com.transit.service.IdempotencyService;
import com.transit.model.ServiceCoupon;
import com.transit.model.ServiceInventoryItem;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequiredArgsConstructor
public class ServiceOrderController {

    private final ServiceOrderService serviceOrderService;
    private final CurrentUserService currentUserService;
    private final ServiceCommerceService serviceCommerceService;
    private final ServiceCouponAdminService couponAdminService;
    private final IdempotencyService idempotencyService;

    @Value("${gateway.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    @PostMapping("/service-orders")
    public Mono<Object> createServiceOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @RequestBody ServiceOrderRequest request) {
        User user = currentUserService.requireUser(authHeader);
        if (request == null || request.getServiceId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "serviceId is required");
        }
        com.transit.dto.ServiceOrderCreateRequest createRequest = new com.transit.dto.ServiceOrderCreateRequest();
        org.springframework.beans.BeanUtils.copyProperties(request, createRequest);
        return Mono.fromCallable(() -> {
            IdempotencyService.Claim claim = idempotencyService.claim(
                    "USER", user.getId(), "CREATE_SERVICE_ORDER", idempotencyKey, request, true);
            if (claim.replay()) return claim.response();
            try {
                ServiceOrderResponse response = serviceOrderService.createOrder(user, createRequest);
                Object orderId = response.getOrder() == null ? null : response.getOrder().getId();
                idempotencyService.complete(claim, 200, response, "SERVICE_ORDER", orderId);
                return response;
            } catch (RuntimeException exception) {
                idempotencyService.fail(claim, exception);
                throw exception;
            }
        });
    }

    @PostMapping("/service-orders/quote")
    public Mono<ServiceOrderQuoteResponse> quote(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @RequestBody ServiceOrderQuoteRequest request) {
        currentUserService.requireUser(authHeader);
        if (request == null || request.getServiceId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "serviceId is required");
        }
        return Mono.fromCallable(() -> serviceCommerceService.quote(request.getServiceId(), request.getQuantity(), request.getCouponCode()));
    }

    @PostMapping("/service-orders/{id}/payment")
    public Mono<ServiceOrderResponse> startPayment(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                @PathVariable Long id,
                                                HttpServletRequest httpRequest) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> serviceOrderService.startPayment(user, id, clientIp(httpRequest)));
    }

    @PostMapping("/service-orders/{id}/payment/query")
    public Mono<ServiceOrderResponse> queryPayment(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> serviceOrderService.queryPayment(user, id));
    }

    @GetMapping("/service-orders")
    public Flux<ServiceOrder> userOrders(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(serviceOrderService.listUserOrders(user));
    }

    @GetMapping("/service-orders/{id}")
    public Mono<ServiceOrder> userOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                     @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> serviceOrderService.getUserOrder(user, id));
    }

    @GetMapping({"/service-orders/{id}/download", "/service-orders/{id}/receipt"})
    public Mono<ResponseEntity<byte[]>> downloadReceipt(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            byte[] bytes = serviceOrderService.buildReceiptDownload(user, id);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("receipt-" + id + ".pdf")
                            .build()
                            .toString())
                    .body(bytes);
        });
    }

    @GetMapping("/service-orders/{id}/invoice")
    public Mono<ResponseEntity<byte[]>> downloadInvoice(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            byte[] bytes = serviceOrderService.buildInvoiceDownload(user, id);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("invoice-" + id + ".pdf")
                            .build()
                            .toString())
                    .body(bytes);
        });
    }

    @GetMapping("/service-orders/admin/orders")
    public Flux<ServiceOrder> adminOrders(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(serviceOrderService.listAllOrders());
    }

    @GetMapping("/service-orders/admin/orders/{id}")
    public Mono<ServiceOrder> adminOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                      @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> serviceOrderService.getAdminOrder(id));
    }

    @PutMapping("/service-orders/admin/orders/{id}")
    public Mono<ServiceOrder> fulfillOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                        @PathVariable Long id,
                                        @RequestBody FulfillRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> serviceOrderService.fulfillOrder(
                id,
                request.getStatus(),
                request.getFulfillmentNote(),
                request.getPaymentReference(),
                request.getFulfillmentReference()
        ));
    }

    @PostMapping("/service-orders/admin/orders/{id}/complete")
    public Mono<ServiceOrder> completeManualOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                               @PathVariable Long id,
                                               @RequestBody ManualDeliveryRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> serviceOrderService.completeManualOrder(id, request.getDeliveryContent(), request.getNote()));
    }

    @PostMapping("/service-orders/admin/orders/{id}/retry-automatic")
    public Mono<ServiceOrder> retryAutomatic(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                          @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> serviceOrderService.retryAutomaticFulfillment(id));
    }

    @PostMapping("/service-orders/admin/services/{serviceId}/inventory/import")
    public Mono<java.util.Map<String, Integer>> importInventory(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                                @PathVariable Long serviceId,
                                                                @RequestBody InventoryImportRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> java.util.Map.of("imported", serviceCommerceService.importInventory(serviceId, request.getContent())));
    }

    @GetMapping("/service-orders/admin/services/{serviceId}/inventory")
    public Flux<ServiceInventoryItem> inventory(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                @PathVariable Long serviceId,
                                                @RequestParam(required = false) String status) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(serviceCommerceService.listInventory(serviceId, status));
    }

    @GetMapping("/service-orders/admin/services/{serviceId}/inventory/stats")
    public Mono<java.util.Map<String, Long>> inventoryStats(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                            @PathVariable Long serviceId) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> serviceCommerceService.inventoryStats(serviceId));
    }

    @DeleteMapping("/service-orders/admin/services/{serviceId}/inventory/{inventoryId}")
    public Mono<Void> deleteInventory(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                      @PathVariable Long serviceId,
                                      @PathVariable Long inventoryId) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> serviceCommerceService.deleteAvailableInventory(serviceId, inventoryId));
    }

    @GetMapping("/service-orders/admin/coupons")
    public Flux<ServiceCoupon> coupons(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(couponAdminService.list());
    }

    @PostMapping("/service-orders/admin/coupons")
    public Mono<ServiceCoupon> createCoupon(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                            @RequestBody ServiceCoupon request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> couponAdminService.save(null, request));
    }

    @PutMapping("/service-orders/admin/coupons/{id}")
    public Mono<ServiceCoupon> updateCoupon(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                            @PathVariable Long id,
                                            @RequestBody ServiceCoupon request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> couponAdminService.save(id, request));
    }

    @DeleteMapping("/service-orders/admin/coupons/{id}")
    public Mono<Void> disableCoupon(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                    @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> couponAdminService.delete(id));
    }

    @DeleteMapping("/service-orders/admin/orders/{id}")
    public Mono<Void> deleteOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                  @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> serviceOrderService.deleteOrder(id));
    }

    @Data
    public static class FulfillRequest {
        private String status;
        private String fulfillmentNote;
        private String paymentReference;
        private String fulfillmentReference;
    }

    @Data
    public static class ManualDeliveryRequest {
        private String deliveryContent;
        private String note;
    }

    @Data
    public static class InventoryImportRequest {
        private String content;
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",", 2)[0].trim();
                if (!first.isBlank() && first.length() <= 64) return first;
            }
        }
        return request.getRemoteAddr();
    }
}
