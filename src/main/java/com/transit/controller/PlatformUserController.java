package com.transit.controller;

import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.PlatformOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/platform/user")
@RequiredArgsConstructor
public class PlatformUserController {

    private final CurrentUserService currentUserService;
    private final PlatformOperationsService platformOperationsService;

    @GetMapping("/wallet")
    public Mono<Map<String, Object>> wallet(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                            @RequestParam(value = "page", defaultValue = "1") int page,
                                            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.userWallet(user, page, pageSize));
    }

    @PostMapping("/wallet/recharge")
    public Mono<Map<String, Object>> recharge(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                              @RequestBody Map<String, Object> request) {
        currentUserService.requireUser(authHeader);
        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "Direct balance recharge is disabled; use a verified payment or redeem code"
        ));
    }

    @PostMapping("/wallet/redeem")
    public Mono<Map<String, Object>> redeem(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                            @RequestBody Map<String, Object> request) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.redeem(user, string(request.get("code"), "")));
    }

    @GetMapping("/security")
    public Mono<Map<String, Object>> security(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.userSecurity(user));
    }

    @GetMapping("/integrations/export")
    public Mono<Map<String, Object>> integrationExport(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                       @RequestParam(value = "clientType", required = false) String clientType) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> platformOperationsService.integrationExport(clientType, user));
    }

    @GetMapping("/docs")
    public Mono<Map<String, Object>> docs() {
        return Mono.fromCallable(platformOperationsService::docsMetadata);
    }

    private String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
