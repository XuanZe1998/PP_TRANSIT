package com.transit.controller;

import com.transit.dto.PlusOrderRequest;
import com.transit.dto.PlusOrderResponse;
import com.transit.model.PlusOrder;
import com.transit.model.PlusProduct;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.PlusOrderService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class PlusOrderController {

    private final PlusOrderService plusOrderService;
    private final CurrentUserService currentUserService;

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

    @GetMapping("/plus/orders")
    public Flux<PlusOrder> userOrders(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(plusOrderService.listUserOrders(user));
    }

    @GetMapping("/plus/orders/{id}")
    public Mono<PlusOrder> userOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                     @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> plusOrderService.getUserOrder(user, id));
    }

    @GetMapping("/plus/orders/{id}/download")
    public Mono<ResponseEntity<byte[]>> downloadOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                      @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            byte[] bytes = plusOrderService.buildDownload(user, id);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("receipt-" + id + ".pdf")
                            .build()
                            .toString())
                    .body(bytes);
        });
    }

    @GetMapping("/plus/admin/orders")
    public Flux<PlusOrder> adminOrders(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(plusOrderService.listAllOrders());
    }

    @PutMapping("/plus/admin/orders/{id}")
    public Mono<PlusOrder> fulfillOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                        @PathVariable Long id,
                                        @RequestBody FulfillRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> plusOrderService.fulfillOrder(id, request.getStatus(), request.getFulfillmentNote()));
    }

    @Data
    public static class FulfillRequest {
        private String status;
        private String fulfillmentNote;
    }
}
