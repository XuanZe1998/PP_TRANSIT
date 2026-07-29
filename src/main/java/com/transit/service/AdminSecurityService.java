package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminSecurityService {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> policies() {
        return jdbcTemplate.queryForList("""
                SELECT id, name, scope, action, threshold_value, enabled, created_at
                FROM security_policies
                ORDER BY id DESC
                """);
    }

    public Map<String, Object> savePolicy(Map<String, Object> request) {
        Long id = nullableLong(request.get("id"));
        String name = stringValue(request, "name", "New policy");
        String scope = stringValue(request, "scope", "global");
        String action = stringValue(request, "action", "WARN");
        String threshold = stringValue(request, "threshold", stringValue(request, "thresholdValue", ""));
        boolean enabled = boolValue(request, "enabled", true);
        if (id == null) {
            jdbcTemplate.update(
                    "INSERT INTO security_policies(name, scope, action, threshold_value, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    name, scope, action, threshold, enabled, LocalDateTime.now()
            );
            return Map.of("created", true, "name", name);
        }
        jdbcTemplate.update(
                "UPDATE security_policies SET name = ?, scope = ?, action = ?, threshold_value = ?, enabled = ? WHERE id = ?",
                name, scope, action, threshold, enabled, id
        );
        return Map.of("updated", true, "id", id);
    }

    private String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private boolean boolValue(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) return bool;
        if (value == null || value.toString().isBlank()) return fallback;
        return Boolean.parseBoolean(value.toString());
    }

    private Long nullableLong(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(value.toString());
    }
}
