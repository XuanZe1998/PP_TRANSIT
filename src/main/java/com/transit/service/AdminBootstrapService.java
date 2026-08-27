package com.transit.service;

import com.transit.mapper.AdminMapper;
import com.transit.model.Admin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class AdminBootstrapService implements ApplicationRunner {
    private final AdminMapper adminMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${security.bootstrap-admin.username:}")
    private String username;

    @Value("${security.bootstrap-admin.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (adminMapper.selectCount(null) > 0) return;
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("No administrator exists. Configure security.bootstrap-admin.username/password for one-time bootstrap");
            return;
        }
        if (password.length() < 14 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Bootstrap admin password must contain 14 to 72 bytes");
        }
        Admin admin = Admin.builder()
                .username(username.trim())
                .password(passwordEncoder.encode(password))
                .displayName("Platform Administrator")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
        adminMapper.insert(admin);
        log.info("Bootstrapped the first administrator account; remove bootstrap credentials before the next start");
    }
}
