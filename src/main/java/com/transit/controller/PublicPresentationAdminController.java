package com.transit.controller;

import com.transit.model.ModelContextPricingPolicy;
import com.transit.model.UpstreamDisplayMapping;
import com.transit.service.CurrentUserService;
import com.transit.service.ModelContextPricingService;
import com.transit.service.UpstreamDisplayMappingService;
import com.transit.service.VerificationDeliveryService;
import com.transit.service.AccountVerificationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class PublicPresentationAdminController {
    private final CurrentUserService currentUserService;
    private final UpstreamDisplayMappingService upstreamDisplayMappingService;
    private final ModelContextPricingService modelContextPricingService;
    private final VerificationDeliveryService verificationDeliveryService;
    private final AccountVerificationPolicy verificationPolicy;

    @GetMapping("/account-verification-status")
    public Mono<Map<String,Object>> verificationStatus(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        currentUserService.requireAdmin(authorization);
        boolean email = verificationDeliveryService.emailConfigured();
        boolean sms = verificationDeliveryService.smsConfigured();
        return Mono.just(Map.of(
                "mode", verificationPolicy.mode().name(),
                "emailConfigured", email,
                "smsConfigured", sms,
                "registrationReady", verificationPolicy.registrationReady(verificationDeliveryService)
        ));
    }

    @GetMapping("/upstream-display-mappings")
    public Mono<List<Map<String, Object>>> mappings(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        currentUserService.requireAdmin(authorization);
        return Mono.fromCallable(upstreamDisplayMappingService::list);
    }

    @PutMapping("/upstream-display-mappings/{channelId}")
    public Mono<UpstreamDisplayMapping> saveMapping(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                     @PathVariable Long channelId,
                                                     @RequestBody UpstreamDisplayMapping request) {
        currentUserService.requireAdmin(authorization);
        return Mono.fromCallable(() -> upstreamDisplayMappingService.save(channelId, request));
    }

    @GetMapping("/models/{publicName}/context-pricing")
    public Mono<ModelContextPricingPolicy> contextPricing(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                           @PathVariable String publicName) {
        currentUserService.requireAdmin(authorization);
        return Mono.justOrEmpty(modelContextPricingService.find(publicName));
    }

    @GetMapping("/models/context-pricing")
    public Mono<ModelContextPricingPolicy> contextPricingQuery(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                                @RequestParam String publicName) {
        currentUserService.requireAdmin(authorization);
        return Mono.justOrEmpty(modelContextPricingService.find(publicName));
    }

    @PutMapping("/models/context-pricing")
    public Mono<ModelContextPricingPolicy> saveContextPricingQuery(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                                    @RequestParam String publicName,
                                                                    @RequestBody ModelContextPricingPolicy request) {
        currentUserService.requireAdmin(authorization);
        return Mono.fromCallable(() -> modelContextPricingService.save(publicName, request));
    }

    @PutMapping("/models/{publicName}/context-pricing")
    public Mono<ModelContextPricingPolicy> saveContextPricing(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                               @PathVariable String publicName,
                                                               @RequestBody ModelContextPricingPolicy request) {
        currentUserService.requireAdmin(authorization);
        return Mono.fromCallable(() -> modelContextPricingService.save(publicName, request));
    }
}
