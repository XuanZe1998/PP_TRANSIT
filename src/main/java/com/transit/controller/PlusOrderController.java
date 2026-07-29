package com.transit.controller;

import com.transit.dto.PlusOrderRequest;
import com.transit.dto.PlusOrderResponse;
import com.transit.dto.ServiceOrderRequest;
import com.transit.model.PlusOrder;
import com.transit.model.PlusProduct;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.OtherServiceCatalogService;
import com.transit.service.PlusOrderService;
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
public class PlusOrderController {

    private final PlusOrderService plusOrderService;
    private final CurrentUserService currentUserService;
    private final OtherServiceCatalogService otherServiceCatalogService;

    @Value("${gateway.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    @GetMapping("/plus/products")
    public Flux<PlusProduct> products() {
        return Flux.fromIterable(plusOrderService.listEnabledProducts());
    }

    @GetMapping("/plus/admin/products")
    public Flux<PlusProduct> adminProducts(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(plusOrderService.listAllProducts());
    }

    @PostMapping("/plus/admin/products")
    public Mono<PlusProduct> createProduct(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                           @RequestBody PlusProduct request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> plusOrderService.createProduct(request));
    }

    @PutMapping("/plus/admin/products/{id}")
    public Mono<PlusProduct> updateProduct(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                           @PathVariable Long id,
                                           @RequestBody PlusProduct request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> plusOrderService.updateProduct(id, request));
    }

    @DeleteMapping("/plus/admin/products/{id}")
    public Mono<Void> deleteProduct(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                    @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> plusOrderService.deleteProduct(id));
    }

    @PostMapping("/plus/orders")
    public Mono<PlusOrderResponse> createOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                               @RequestBody PlusOrderRequest request) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> plusOrderService.createOrder(user, request));
    }

    @PostMapping("/service-orders")
    public Mono<PlusOrderResponse> createServiceOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                      @RequestBody ServiceOrderRequest request) {
        User user = currentUserService.requireUser(authHeader);
        if (request == null || request.getServiceId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "serviceId is required");
        }
        PlusOrderRequest legacyRequest = new PlusOrderRequest();
        legacyRequest.setProductId(otherServiceCatalogService.resolveProductIdForOrder(request.getServiceId()));
        legacyRequest.setContactEmail(request.getContactEmail());
        legacyRequest.setContactNote(request.getContactNote());
        return Mono.fromCallable(() -> plusOrderService.createOrder(user, legacyRequest));
    }

    @PostMapping({"/plus/orders/{id}/payment", "/service-orders/{id}/payment"})
    public Mono<PlusOrderResponse> startPayment(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                @PathVariable Long id,
                                                HttpServletRequest httpRequest) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> plusOrderService.startPayment(user, id, clientIp(httpRequest)));
    }

    @PostMapping({"/plus/orders/{id}/payment/query", "/service-orders/{id}/payment/query"})
    public Mono<PlusOrderResponse> queryPayment(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> plusOrderService.queryPayment(user, id));
    }

    @GetMapping({"/plus/orders", "/service-orders"})
    public Flux<PlusOrder> userOrders(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(plusOrderService.listUserOrders(user));
    }

    @GetMapping({"/plus/orders/{id}", "/service-orders/{id}"})
    public Mono<PlusOrder> userOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                     @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> plusOrderService.getUserOrder(user, id));
    }

    @GetMapping({"/plus/orders/{id}/download", "/service-orders/{id}/download"})
    public Mono<ResponseEntity<byte[]>> downloadOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                      @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            byte[] bytes = plusOrderService.buildDownload(user, id);
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

    @GetMapping({"/plus/admin/orders", "/service-orders/admin/orders"})
    public Flux<PlusOrder> adminOrders(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(plusOrderService.listAllOrders());
    }

    @PutMapping({"/plus/admin/orders/{id}", "/service-orders/admin/orders/{id}"})
    public Mono<PlusOrder> fulfillOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                        @PathVariable Long id,
                                        @RequestBody FulfillRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> plusOrderService.fulfillOrder(
                id,
                request.getStatus(),
                request.getFulfillmentNote(),
                request.getPaymentReference(),
                request.getFulfillmentReference()
        ));
    }

    @DeleteMapping({"/plus/admin/orders/{id}", "/service-orders/admin/orders/{id}"})
    public Mono<Void> deleteOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                  @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> plusOrderService.deleteOrder(id));
    }

    @Data
    public static class FulfillRequest {
        private String status;
        private String fulfillmentNote;
        private String paymentReference;
        private String fulfillmentReference;
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
