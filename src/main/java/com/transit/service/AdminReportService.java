package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> reports() {
        long revenue = queryLong("SELECT COALESCE(SUM(CASE WHEN sale_amount > 0 THEN sale_amount ELSE cost END), 0) FROM logs WHERE UPPER(status) = 'SUCCESS'");
        long cost = queryLong("SELECT COALESCE(SUM(cost_amount), 0) FROM logs WHERE UPPER(status) = 'SUCCESS'");
        long failures = queryLong("SELECT COUNT(*) FROM logs WHERE UPPER(status) = 'FAILED'");
        long p95Fallback = queryLong("SELECT COALESCE(MAX(latency_ms), 0) FROM logs");
        List<Map<String, Object>> modelRows = jdbcTemplate.queryForList("""
                SELECT model, COUNT(*) AS requests, COALESCE(SUM(total_tokens), 0) AS tokens,
                       COALESCE(SUM(CASE WHEN sale_amount > 0 THEN sale_amount ELSE cost END), 0) AS revenue,
                       COALESCE(SUM(cost_amount), 0) AS cost
                FROM logs
                GROUP BY model
                ORDER BY requests DESC
                LIMIT 50
                """);
        List<Map<String, Object>> channelRows = jdbcTemplate.queryForList("""
                SELECT c.name AS channel_name, COUNT(l.id) AS requests,
                       COALESCE(SUM(CASE WHEN UPPER(l.status) = 'FAILED' THEN 1 ELSE 0 END), 0) AS failures,
                       COALESCE(AVG(l.latency_ms), 0) AS avg_latency_ms
                FROM logs l
                LEFT JOIN channels c ON c.id = l.channel_id
                GROUP BY c.name
                ORDER BY requests DESC
                LIMIT 50
                """);
        return Map.of(
                "grossMargin", revenue == 0 ? 0 : (revenue - cost) * 100.0 / revenue,
                "revenue", revenue,
                "cost", cost,
                "failureCost", failures,
                "p95LatencyMs", p95Fallback,
                "models", modelRows,
                "channels", channelRows
        );
    }

    public List<Map<String, Object>> settings() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT setting_key, setting_value, description, updated_at FROM system_settings ORDER BY setting_key");
        rows.forEach(this::redactSetting);
        return rows;
    }

    public Map<String, Object> saveSetting(Map<String, Object> request) {
        String key = request.getOrDefault("key", "custom.setting").toString();
        rejectCreativeOrSecretSetting(key);
        String value = request.getOrDefault("value", "").toString();
        String description = request.getOrDefault("description", "").toString();
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_settings WHERE setting_key = ?", Integer.class, key);
        if (count == null || count == 0) {
            jdbcTemplate.update("INSERT INTO system_settings(setting_key, setting_value, description, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)", key, value, description);
        } else {
            jdbcTemplate.update("UPDATE system_settings SET setting_value = ?, description = ?, updated_at = CURRENT_TIMESTAMP WHERE setting_key = ?", value, description, key);
        }
        return Map.of("key", key, "value", value, "description", description);
    }

    private void rejectCreativeOrSecretSetting(String key) {
        String normalized = key.toLowerCase();
        if (normalized.startsWith("creative.") || normalized.contains("api-key") || normalized.contains("secret") || normalized.contains("access-key")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "敏感配置不能写入明文 system_settings，请使用对应的专用配置页面");
        }
    }
    private void redactSetting(Map<String, Object> row) {
        String key = String.valueOf(row.get("setting_key")).toLowerCase();
        if (key.contains("key") || key.contains("secret") || key.contains("password") || key.contains("token")) row.put("setting_value", "****");
    }

    private long queryLong(String sql) {
        Number number = jdbcTemplate.queryForObject(sql, Number.class);
        return number == null ? 0 : number.longValue();
    }
}
