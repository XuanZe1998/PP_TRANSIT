package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UsageAnalyticsService {
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> analytics(Long ownerUserId, Long filterUserId, String from, String to,
                                         String model, Long tokenId, String status) {
        return analytics(ownerUserId, filterUserId, from, to, model, tokenId, status, null, null);
    }

    public Map<String, Object> analytics(Long ownerUserId, Long filterUserId, String from, String to,
                                         String model, Long tokenId, String status,
                                         String audienceType, Long organizationId) {
        LocalDate end = date(to, LocalDate.now());
        LocalDate start = date(from, end.minusDays(29));
        if (start.isAfter(end) || start.isBefore(end.minusDays(365))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usage analytics range must be 1 to 366 days");
        }
        StringBuilder where = new StringBuilder(" WHERE l.created_at>=? AND l.created_at<?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(start.atStartOfDay());
        parameters.add(end.plusDays(1).atStartOfDay());
        Long effectiveUserId = ownerUserId != null ? ownerUserId : filterUserId;
        if (effectiveUserId != null) { where.append(" AND l.user_id=?"); parameters.add(effectiveUserId); }
        if (organizationId != null) { where.append(" AND l.organization_id=?"); parameters.add(organizationId); }
        String audience = audienceType == null ? "" : audienceType.trim().toUpperCase();
        if (!audience.isBlank() && !List.of("PERSONAL", "COMPANY").contains(audience)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audienceType must be PERSONAL or COMPANY");
        }
        if ("PERSONAL".equals(audience)) {
            where.append(" AND COALESCE(o.organization_type,'PERSONAL')='PERSONAL'");
        } else if ("COMPANY".equals(audience)) {
            where.append(" AND o.organization_type='COMPANY'");
        }
        if (model != null && !model.isBlank()) { where.append(" AND l.model=?"); parameters.add(model.trim()); }
        if (tokenId != null) { where.append(" AND l.token_id=?"); parameters.add(tokenId); }
        if (status != null && !status.isBlank()) { where.append(" AND UPPER(l.status)=?"); parameters.add(status.trim().toUpperCase()); }

        String aggregates = """
                COUNT(*) request_count,
                COALESCE(SUM(l.prompt_tokens),0) prompt_tokens,
                COALESCE(SUM(l.completion_tokens),0) completion_tokens,
                COALESCE(SUM(l.cache_read_tokens),0) cache_read_tokens,
                COALESCE(SUM(l.cache_write_tokens),0) cache_write_tokens,
                COALESCE(SUM(l.cache_miss_tokens),0) cache_miss_tokens,
                COALESCE(SUM(l.total_tokens),0) total_tokens,
                COALESCE(SUM(l.total_amount),0) total_amount,
                COALESCE(SUM(l.cost_amount),0) cost_amount,
                COALESCE(SUM(l.total_amount-l.cost_amount),0) profit_amount,
                COALESCE(SUM(l.settlement_amount),0) settlement_amount
                """;
        String fromLogs = " FROM logs l LEFT JOIN organizations o ON o.id=l.organization_id";
        List<Map<String, Object>> daily = jdbcTemplate.queryForList(
                "SELECT CAST(l.created_at AS DATE) usage_day," + aggregates + fromLogs + where
                        + " GROUP BY CAST(l.created_at AS DATE) ORDER BY usage_day", parameters.toArray());
        Map<String, Object> totals = jdbcTemplate.queryForMap(
                "SELECT " + aggregates + fromLogs + where, parameters.toArray());
        List<Map<String, Object>> byModel = jdbcTemplate.queryForList(
                "SELECT l.model," + aggregates + fromLogs + where
                        + " GROUP BY l.model ORDER BY total_tokens DESC LIMIT 20", parameters.toArray());
        List<Map<String, Object>> dailyByModel = jdbcTemplate.queryForList(
                "SELECT CAST(l.created_at AS DATE) usage_day,l.model," + aggregates + fromLogs + where
                        + " GROUP BY CAST(l.created_at AS DATE),l.model ORDER BY usage_day,l.model",
                parameters.toArray());
        addMargin(totals);
        daily.forEach(this::addMargin);
        byModel.forEach(this::addMargin);
        dailyByModel.forEach(this::addMargin);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("from", start.toString());
        response.put("to", end.toString());
        response.put("currency", "USD");
        response.put("amountScale", 10_000);
        response.put("settlementCurrency", "CNY");
        response.put("daily", daily);
        response.put("dailyByModel", dailyByModel);
        response.put("totals", totals);
        response.put("byModel", byModel);
        response.put("tokenComposition", List.of(
                slice("输入未命中", value(totals, "cache_miss_tokens")),
                slice("缓存命中", value(totals, "cache_read_tokens")),
                slice("缓存写入", value(totals, "cache_write_tokens")),
                slice("输出", value(totals, "completion_tokens"))));
        return response;
    }

    private void addMargin(Map<String, Object> row) {
        double revenue = number(value(row, "total_amount"));
        double profit = number(value(row, "profit_amount"));
        row.put("profit_margin", revenue == 0 ? 0D : profit * 100D / revenue);
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0D;
    }

    private Map<String, Object> slice(String name, Object value) {
        return Map.of("name", name, "value", value == null ? 0 : value);
    }

    private Object value(Map<String, Object> row, String key) {
        if (row.containsKey(key)) return row.get(key);
        return row.get(key.toUpperCase(java.util.Locale.ROOT));
    }

    private LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value.trim()); }
        catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date must use YYYY-MM-DD"); }
    }
}
