package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> overview() {
        long requests = queryLong("SELECT COUNT(*) FROM logs");
        long success = queryLong("SELECT COUNT(*) FROM logs WHERE UPPER(status) = 'SUCCESS'");
        long failed = queryLong("SELECT COUNT(*) FROM logs WHERE UPPER(status) = 'FAILED'");
        long tokens = queryLong("SELECT COALESCE(SUM(total_tokens), 0) FROM logs");
        long revenue = queryLong("SELECT COALESCE(SUM(CASE WHEN sale_amount > 0 THEN sale_amount ELSE cost END), 0) FROM logs WHERE UPPER(status) = 'SUCCESS'");
        long cost = queryLong("SELECT COALESCE(SUM(cost_amount), 0) FROM logs WHERE UPPER(status) = 'SUCCESS'");
        long activeUsers = queryLong("SELECT COUNT(*) FROM users WHERE COALESCE(status, 'ACTIVE') = 'ACTIVE'");
        long pendingOrders = queryLong("SELECT COUNT(*) FROM service_orders WHERE status IN ('PENDING', 'CONFIRMED')");

        List<Map<String, Object>> channelHealth = jdbcTemplate.queryForList("""
                SELECT id, name, type, enabled, health_status, cooldown_until, weight, rpm_limit, tpm_limit
                FROM channels
                ORDER BY enabled DESC, health_status ASC, id DESC
                LIMIT 20
                """);
        List<Map<String, Object>> riskQueue = jdbcTemplate.queryForList("""
                SELECT 'CHANNEL' AS type, name AS title, health_status AS severity
                FROM channels
                WHERE enabled = TRUE AND health_status <> 'HEALTHY'
                ORDER BY id DESC
                LIMIT 10
                """);

        return Map.of(
                "metrics", Map.of(
                        "requests", requests,
                        "successRate", requests == 0 ? 0 : success * 100.0 / requests,
                        "failedRequests", failed,
                        "consumedTokens", tokens,
                        "revenue", revenue,
                        "cost", cost,
                        "grossMargin", revenue == 0 ? 0 : (revenue - cost) * 100.0 / revenue,
                        "activeUsers", activeUsers,
                        "pendingOrders", pendingOrders
                ),
                "channelHealth", channelHealth,
                "riskQueue", riskQueue,
                "generatedAt", LocalDateTime.now()
        );
    }

    private long queryLong(String sql) {
        Number number = jdbcTemplate.queryForObject(sql, Number.class);
        return number == null ? 0 : number.longValue();
    }
}
