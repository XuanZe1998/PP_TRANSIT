package com.transit.controller;

import com.transit.service.AuthService;
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

    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        String identifier = request.getIdentifier() != null ? request.getIdentifier() : request.getUsername();
        return authService.register(identifier, request.getPassword());
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

    @Data
    public static class RegisterRequest {
        private String identifier;
        private String username;
        private String password;
        private String email;
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
}
