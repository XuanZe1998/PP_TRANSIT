package com.transit.controller;

import com.transit.service.CurrentUserService;
import com.transit.service.PlatformOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/platform/admin")
@RequiredArgsConstructor
public class PlatformAdminController {

    private final CurrentUserService currentUserService;
    private final PlatformOperationsService platformOperationsService;

    @GetMapping("/dashboard")
    public Mono<Map<String, Object>> dashboard(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(platformOperationsService::adminDashboard);
    }

    @GetMapping("/users")
    public Flux<Map<String, Object>> users(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(platformOperationsService.adminUsers());
    }

    @PostMapping("/user-groups")
    public Mono<Map<String, Object>> createUserGroup(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                     @RequestBody Map<String, Object> request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.createUserGroup(request));
    }

    @GetMapping("/channels/governance")
    public Mono<Map<String, Object>> channelGovernance(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(platformOperationsService::channelGovernance);
    }

    @GetMapping("/models/pricing")
    public Flux<Map<String, Object>> modelPricing(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(platformOperationsService.modelPricing());
    }

    @PostMapping("/models/pricing")
    public Mono<Map<String, Object>> createModelPricing(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @RequestBody Map<String, Object> request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.upsertModelPricing(null, request));
    }

    @PutMapping("/models/pricing/{id}")
    public Mono<Map<String, Object>> updateModelPricing(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @PathVariable Long id,
                                                        @RequestBody Map<String, Object> request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.upsertModelPricing(id, request));
    }

    @GetMapping("/finance/transactions")
    public Flux<Map<String, Object>> financeTransactions(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(platformOperationsService.financeTransactions());
    }

    @GetMapping("/finance/redeem-codes")
    public Flux<Map<String, Object>> redeemCodes(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(platformOperationsService.redeemCodes());
    }

    @PostMapping("/finance/redeem-codes")
    public Mono<Map<String, Object>> createRedeemCode(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                      @RequestBody Map<String, Object> request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.createRedeemCode(request));
    }

    @GetMapping("/settings")
    public Flux<Map<String, Object>> settings(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(platformOperationsService.settings());
    }

    @PutMapping("/settings")
    public Mono<Map<String, Object>> updateSetting(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                   @RequestBody Map<String, Object> request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.updateSetting(request));
    }

    @GetMapping("/security/policies")
    public Flux<Map<String, Object>> securityPolicies(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(platformOperationsService.securityPolicies());
    }

    @PostMapping("/security/policies")
    public Mono<Map<String, Object>> saveSecurityPolicy(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @RequestBody Map<String, Object> request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.saveSecurityPolicy(request));
    }

    @GetMapping("/reports")
    public Mono<Map<String, Object>> reports(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(platformOperationsService::reports);
    }
}
