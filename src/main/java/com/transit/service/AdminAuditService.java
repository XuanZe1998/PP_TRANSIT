package com.transit.service;

import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final JdbcTemplate jdbcTemplate;

    public void record(User admin, String action, String targetType, Object targetId, Object beforeData, Object afterData, String ipAddress) {
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs(admin_id, admin_name, action, target_type, target_id, before_data, after_data, ip_address, result, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', ?)
                """,
                admin == null ? null : admin.getId(),
                admin == null ? null : admin.getUsername(),
                action,
                targetType,
                targetId == null ? null : targetId.toString(),
                beforeData == null ? null : beforeData.toString(),
                afterData == null ? null : afterData.toString(),
                ipAddress,
                LocalDateTime.now()
        );
    }
}
