package com.transit.controller;

import com.transit.model.PaymentIntent;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.PaymentIntentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
public class PaymentIntentController {
    private final CurrentUserService currentUserService;
    private final PaymentIntentService paymentIntentService;

    @PostMapping("/payment-intents/{id}/start")
    public Mono<PaymentIntent> start(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,@PathVariable Long id){
        User user=currentUserService.requireUser(auth);return Mono.fromCallable(()->paymentIntentService.start(user,id));
    }
    @PostMapping("/payment-intents/{id}/query")
    public Mono<PaymentIntent> query(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,@PathVariable Long id){
        User user=currentUserService.requireUser(auth);return Mono.fromCallable(()->paymentIntentService.query(user,id));
    }
    @GetMapping("/admin/payment-intents/refund-capability")
    public Mono<Map<String,Object>> capability(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth){
        currentUserService.requireAdmin(auth);return Mono.just(Map.of("enabled",paymentIntentService.refundsEnabled(),"reason",paymentIntentService.refundsEnabled()?"":"Set ANYIPAY_ALLOW_MONEY_MUTATIONS=true"));
    }
    @GetMapping("/admin/payment-intents")
    public Flux<PaymentIntent> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth){currentUserService.requireAdmin(auth);return Flux.fromIterable(paymentIntentService.listAll());}
    @PostMapping("/admin/payment-intents/{id}/refund")
    public Mono<PaymentIntent> refund(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,@PathVariable Long id,@RequestBody RefundRequest request){
        currentUserService.requireAdmin(auth);return Mono.fromCallable(()->paymentIntentService.refund(id,request.getReason()));
    }
    @GetMapping("/admin/payment-intents/business/{type}/{businessId}")
    public Mono<PaymentIntent> byBusiness(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,@PathVariable String type,@PathVariable Long businessId){
        currentUserService.requireAdmin(auth);return Mono.fromCallable(()->paymentIntentService.getByBusiness(type,businessId));
    }
    @Data public static class RefundRequest{private String reason;}
}
