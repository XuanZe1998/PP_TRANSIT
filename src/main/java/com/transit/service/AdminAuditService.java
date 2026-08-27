package com.transit.service;

import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private static final int MAX_AUDIT_DATA_CODE_POINTS = 4_000;
    private static final String TRUNCATION_SUFFIX = "...[truncated]";

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
                bounded(targetId, 80),
                auditData(beforeData),
                auditData(afterData),
                bounded(ipAddress, 80),
                LocalDateTime.now()
        );
    }

    private String auditData(Object value) {
        return bounded(value, MAX_AUDIT_DATA_CODE_POINTS);
    }

    private String bounded(Object value, int maxCodePoints) {
        if (value == null) return null;
        String text = value.toString();
        int count = text.codePointCount(0, text.length());
        if (count <= maxCodePoints) return text;
        int suffixLength = TRUNCATION_SUFFIX.codePointCount(0, TRUNCATION_SUFFIX.length());
        int end = text.offsetByCodePoints(0, Math.max(0, maxCodePoints - suffixLength));
        return text.substring(0, end) + TRUNCATION_SUFFIX;
    }
}
