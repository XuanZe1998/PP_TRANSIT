package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.AdminMapper;
import com.transit.model.Admin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminMapper adminMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecretHashService secretHashService;
    private final AuthenticationThrottle authenticationThrottle;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${security.admin-session-expiry:1800}")
    private long adminSessionExpiry;

    public Mono<Map<String, Object>> login(String username, String password) {
        return Mono.fromCallable(() -> {
            String normalized = username == null ? "" : username.trim();
            authenticationThrottle.checkAllowed("admin:" + normalized);
            Admin admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, normalized));
            if (admin == null || !admin.isEnabled() || !passwordEncoder.matches(password == null ? "" : password, admin.getPassword())) {
                if (admin == null) passwordEncoder.encode(password == null ? "" : password);
                authenticationThrottle.failure("admin:" + normalized);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin username or password");
            }

            String accessToken = generateSecureToken();
            LocalDateTime now = LocalDateTime.now();
            admin.setAccessToken(secretHashService.hash(accessToken));
            admin.setTokenExpiresAt(now.plusSeconds(Math.max(300, adminSessionExpiry)));
            admin.setLastLoginAt(now);
            adminMapper.updateById(admin);
            authenticationThrottle.success("admin:" + normalized);

            Map<String, Object> response = new HashMap<>();
            response.put("access_token", accessToken);
            response.put("token_type", "Bearer");
            response.put("expires_in", Math.max(300, adminSessionExpiry));
            response.put("admin_id", admin.getId());
            response.put("username", admin.getUsername());
            response.put("display_name", admin.getDisplayName());
            response.put("role", "ADMIN");
            return response;
        });
    }

    public Admin getAdminFromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing admin token");
        }
        String digest = secretHashService.hash(token);
        Admin admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>().eq(Admin::getAccessToken, digest));
        if (admin == null) {
            admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>().eq(Admin::getAccessToken, token));
            if (admin != null) {
                admin.setAccessToken(digest);
                adminMapper.updateById(admin);
            }
        }
        if (admin == null || !admin.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin token");
        }
        if (admin.getTokenExpiresAt() != null && admin.getTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin token expired");
        }
        return admin;
    }

    public Mono<Map<String, Object>> logout(String token) {
        return Mono.fromCallable(() -> {
            if (token != null && !token.isBlank()) {
                Admin admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>()
                        .eq(Admin::getAccessToken, secretHashService.hash(token)));
                if (admin == null) {
                    admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>().eq(Admin::getAccessToken, token));
                }
                if (admin != null) {
                    admin.setAccessToken(null);
                    admin.setTokenExpiresAt(null);
                    adminMapper.updateById(admin);
                }
            }
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Logged out successfully");
            return response;
        });
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
