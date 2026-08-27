package com.transit.controller;

import com.transit.service.AuthService;
import com.transit.service.AccountVerificationPolicy;
import com.transit.service.VerificationDeliveryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final com.transit.service.VerificationCodeService verificationCodeService;
    private final AccountVerificationPolicy verificationPolicy;
    private final VerificationDeliveryService verificationDeliveryService;

    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        return authService.register(request.getEmail(), request.getEmailCode(), request.getPhone(), request.getPhoneCode(),
                request.getPassword(), request.getDisplayName());
    }

    @GetMapping("/verification/policy")
    public Mono<Map<String, Object>> verificationPolicy() {
        return Mono.just(Map.of(
                "mode", verificationPolicy.mode().name(),
                "registrationReady", verificationPolicy.registrationReady(verificationDeliveryService)
        ));
    }

    @PostMapping("/verification/email/send")
    public Mono<Map<String, Object>> sendEmailCode(@RequestBody VerificationRequest request) {
        return Mono.fromCallable(() -> verificationCodeService.send("EMAIL", request.getRecipient(), request.getPurpose()));
    }

    @PostMapping("/verification/phone/send")
    public Mono<Map<String, Object>> sendPhoneCode(@RequestBody VerificationRequest request) {
        return Mono.fromCallable(() -> verificationCodeService.send("PHONE", request.getRecipient(), request.getPurpose()));
    }

    @GetMapping("/validate-identifier")
    public Mono<Map<String, Object>> validateIdentifier(@RequestParam("identifier") String identifier) {
        return Mono.fromCallable(() -> authService.validateIdentifier(identifier));
    }

    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody LoginRequest request) {
        return authService.login(request.identifier(), request.getPassword());
    }

    @PostMapping("/logout")
    public Mono<Map<String, Object>> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        String token = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7) : null;
        return authService.logout(token);
    }

    @PostMapping("/refresh")
    public Mono<Map<String, Object>> refresh(@RequestBody RefreshRequest request) {
        return Mono.fromCallable(() -> authService.refresh(request.getRefreshToken()));
    }

    @Data
    public static class RegisterRequest {
        private String identifier;
        private String username;
        private String password;
        private String email;
        private String emailCode;
        private String phone;
        private String phoneCode;
        private String displayName;
    }

    @Data
    public static class VerificationRequest {
        private String recipient;
        private String purpose = "REGISTER";
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String identifier;
        private String account;
        private String password;

        public String identifier() {
            if (username != null && !username.isBlank()) {
                return username;
            }
            if (identifier != null && !identifier.isBlank()) {
                return identifier;
            }
            return account;
        }
    }

    @Data
    public static class RefreshRequest {
        private String refreshToken;
    }
}
