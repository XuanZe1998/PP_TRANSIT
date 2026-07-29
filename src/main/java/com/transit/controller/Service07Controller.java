package com.transit.controller;

import com.transit.dto.PlusOrderResponse;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.Service07FulfillmentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class Service07Controller {
    private final CurrentUserService currentUserService;
    private final Service07FulfillmentService fulfillmentService;

    @PostMapping("/service-07/order")
    public PlusOrderResponse currentOrCreateOrder(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return fulfillmentService.currentOrCreateOrder(user);
    }

    @PostMapping("/service-orders/{orderId}/service-07-fulfillment")
    public ResponseEntity<Service07FulfillmentService.FulfillmentView> start(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long orderId,
            @RequestBody SessionRequest request) {
        User user = currentUserService.requireUser(authHeader);
        return ResponseEntity.accepted()
                .body(fulfillmentService.start(
                        user,
                        orderId,
                        request == null ? null : request.getSession()));
    }

    @GetMapping("/service-orders/{orderId}/service-07-fulfillment")
    public Service07FulfillmentService.FulfillmentView status(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long orderId) {
        User user = currentUserService.requireUser(authHeader);
        return fulfillmentService.status(user, orderId);
    }

    @Data
    public static class SessionRequest {
        private String session;
    }
}
