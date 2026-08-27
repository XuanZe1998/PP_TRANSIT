package com.transit.controller;

import com.transit.service.AdminAuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody LoginRequest request) {
        return adminAuthService.login(request.getUsername(), request.getPassword());
    }

    @PostMapping("/logout")
    public Mono<Map<String, Object>> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String token = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7) : null;
        return adminAuthService.logout(token);
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
