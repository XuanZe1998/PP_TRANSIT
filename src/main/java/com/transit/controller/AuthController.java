package com.transit.controller;

import com.transit.service.AuthService;
import com.transit.service.AccountVerificationPolicy;
import com.transit.service.VerificationDeliveryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import com.transit.service.ClientIpResolver;
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
    private final ClientIpResolver clientIps;

    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        return authService.register(new AuthService.Registration(request.getAccountType(), request.getCompanyName(),
                request.contactName(), request.getPhone(), request.getEmail(), request.getEmailCode(), request.getPhoneCode(),
                request.getPassword(), request.getConfirmPassword(), request.getTermsVersion(), request.getPrivacyVersion(),
                Boolean.TRUE.equals(request.getAcceptedAgreements())), clientIps.resolve(servletRequest));
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
    public Mono<Map<String, Object>> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return authService.login(request.identifier(), request.getPassword(), clientIps.resolve(servletRequest));
    }

    @PostMapping("/login/ip-verify")
    public Mono<Map<String,Object>> verifyIp(@RequestBody IpVerifyRequest request, HttpServletRequest servletRequest) {
        return authService.verifyLoginIp(request.getChallengeId(), request.getCode(), clientIps.resolve(servletRequest));
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
        private String accountType = "PERSONAL";
        private String companyName;
        private String contactName;
        private String confirmPassword;
        private String termsVersion;
        private String privacyVersion;
        private Boolean acceptedAgreements;

        public String contactName() { return contactName == null || contactName.isBlank() ? displayName : contactName; }
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

    @Data
    public static class IpVerifyRequest { private String challengeId; private String code; }
}
