package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> overview() {
        return overview(null, null);
    }

    public Map<String, Object> overview(String from, String to) {
        LocalDate end = date(to, LocalDate.now());
        LocalDate start = date(from, end.minusDays(29));
        if (start.isAfter(end) || start.isBefore(end.minusDays(365))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dashboard range must be 1 to 366 days");
        }
        Object[] period = {start.atStartOfDay(), end.plusDays(1).atStartOfDay()};
        String where = " WHERE created_at>=? AND created_at<?";
        String successful = "UPPER(COALESCE(status,'')) IN ('SUCCESS','SUCCESS_ESTIMATED')";
        long requests = queryLong("SELECT COUNT(*) FROM logs" + where, period);
        long success = queryLong("SELECT COUNT(*) FROM logs" + where + " AND " + successful, period);
        long failed = queryLong("SELECT COUNT(*) FROM logs" + where + " AND UPPER(COALESCE(status,''))='FAILED'", period);
        long unknown = Math.max(0, requests - success - failed);
        long tokens = queryLong("SELECT COALESCE(SUM(total_tokens),0) FROM logs" + where, period);
        long revenue = queryLong("SELECT COALESCE(SUM(CASE WHEN COALESCE(sale_amount,0)>0 THEN sale_amount ELSE COALESCE(total_amount,cost,0) END),0) FROM logs" + where + " AND " + successful, period);
        long cost = queryLong("SELECT COALESCE(SUM(cost_amount),0) FROM logs" + where + " AND " + successful, period);
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
                "metrics", Map.ofEntries(
                        Map.entry("requests", requests),
                        Map.entry("successRequests", success),
                        Map.entry("failedRequests", failed),
                        Map.entry("unknownRequests", unknown),
                        Map.entry("successRate", requests == 0 ? 0 : success * 100.0 / requests),
                        Map.entry("consumedTokens", tokens),
                        Map.entry("revenue", revenue),
                        Map.entry("cost", cost),
                        Map.entry("grossMargin", revenue == 0 ? 0 : (revenue - cost) * 100.0 / revenue),
                        Map.entry("activeUsers", activeUsers),
                        Map.entry("pendingOrders", pendingOrders)
                ),
                "channelHealth", channelHealth,
                "riskQueue", riskQueue,
                "generatedAt", LocalDateTime.now(),
                "from", start.toString(),
                "to", end.toString()
        );
    }

    private long queryLong(String sql, Object... args) {
        Number number = jdbcTemplate.queryForObject(sql, Number.class, args);
        return number == null ? 0 : number.longValue();
    }

    private LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value.trim()); }
        catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date must use YYYY-MM-DD"); }
    }
}
