package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.model.PaymentIntent;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.IdempotencyService;
import com.transit.service.PaymentIntentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class PaymentIntentController {
    private final CurrentUserService currentUserService;
    private final PaymentIntentService paymentIntentService;
    private final IdempotencyService idempotencyService;

    @Value("${gateway.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    @PostMapping("/payment-intents/{id}/start")
    public Mono<Object> start(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @PathVariable Long id,
                              HttpServletRequest request) {
        User user = currentUserService.requireUser(auth);
        return Mono.fromCallable(() -> {
            IdempotencyService.Claim claim = idempotencyService.claim(
                    "USER", user.getId(), "START_PAYMENT_INTENT:" + id, idempotencyKey, java.util.Map.of("id", id), true);
            if (claim.replay()) return claim.response();
            try {
                PaymentIntentService.StartResponse response = paymentIntentService.start(
                        user, id, clientIp(request), device(request));
                idempotencyService.complete(claim, 200, response, "PAYMENT_INTENT", id);
                return response;
            } catch (RuntimeException exception) {
                idempotencyService.fail(claim, exception);
                throw exception;
            }
        });
    }

    @GetMapping("/payment-intents/{id}")
    public Mono<PaymentIntent> status(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) {
        return Mono.fromCallable(() -> paymentIntentService.status(currentUserService.requireUser(auth), id));
    }

    @PostMapping("/payment-intents/{id}/query")
    public Mono<PaymentIntent> query(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) {
        return Mono.fromCallable(() -> paymentIntentService.query(currentUserService.requireUser(auth), id));
    }

    @GetMapping("/admin/payment-intents")
    public Mono<PageResponse<PaymentIntent>> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size,
                                                   @RequestParam(required = false) String query,
                                                   @RequestParam(required = false) String status) {
        currentUserService.requireAdmin(auth);
        return Mono.fromCallable(() -> paymentIntentService.listPage(page, size, query, status));
    }

    @GetMapping("/admin/payment-intents/business/{type}/{businessId}")
    public Mono<PaymentIntent> byBusiness(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                          @PathVariable String type, @PathVariable Long businessId) {
        currentUserService.requireAdmin(auth);
        return Mono.fromCallable(() -> paymentIntentService.getByBusiness(type, businessId));
    }

    @PostMapping("/admin/payment-intents/{id}/query")
    public Mono<PaymentIntent> queryAdmin(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                          @PathVariable Long id) {
        currentUserService.requireAdmin(auth);
        return Mono.fromCallable(() -> paymentIntentService.queryAdmin(id));
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String value = request.getHeader("X-Forwarded-For");
            if (value != null && !value.isBlank()) return value.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String device(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent == null) return "pc";
        String value = agent.toLowerCase();
        if (value.contains("micromessenger")) return "wechat";
        if (value.contains("alipayclient")) return "alipay";
        if (value.contains("mobile") || value.contains("android") || value.contains("iphone")) return "mobile";
        return "pc";
    }
}
