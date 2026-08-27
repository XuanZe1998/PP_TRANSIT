package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAuditQueryService {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> requestLogs() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) requestLogs(
                null, null, null, null, null, null, null, 1, 500).get("items");
        return items;
    }

    public Map<String, Object> requestLogs(String audienceType, Long organizationId, Long userId,
                                            String model, String from, String to, String query,
                                            int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(10, Math.min(200, pageSize));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, audienceType, organizationId, userId, model, from, to);
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(l.trace_id) LIKE ? OR LOWER(COALESCE(u.username,'')) LIKE ? OR LOWER(l.model) LIKE ?)");
            String needle = "%" + query.trim().toLowerCase() + "%";
            args.add(needle); args.add(needle); args.add(needle);
        }
        String fromClause = " FROM logs l LEFT JOIN users u ON u.id=l.user_id"
                + " LEFT JOIN channels c ON c.id=l.channel_id LEFT JOIN organizations o ON o.id=l.organization_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + fromClause + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT l.id, l.trace_id, l.user_id, u.username, l.token_key, l.model, l.channel_id, c.name AS channel_name,
                       l.prompt_tokens, l.completion_tokens, l.cached_tokens,
                       l.cache_read_tokens, l.cache_write_tokens, l.cache_miss_tokens,l.total_tokens, l.cost,
                       l.input_amount, l.output_amount, l.cached_amount,
                       l.cache_read_amount, l.cache_write_amount, l.total_amount,
                       l.sale_amount, l.cost_amount, l.input_cost_amount, l.output_cost_amount,
                       l.cached_cost_amount, l.cache_read_cost_amount, l.cache_write_cost_amount, l.gross_profit,
                       l.model_currency,l.model_amount_scale,l.settlement_amount,l.settlement_currency,l.exchange_rate,
                       l.latency_ms, l.status, l.error_message, l.created_at
                """ + fromClause + where + " ORDER BY l.created_at DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    public Map<String, Object> filterOptions(String audienceType, Long organizationId, String query) {
        String audience = normalizeAudience(audienceType);
        String needle = "%" + (query == null ? "" : query.trim().toLowerCase()) + "%";
        List<Map<String, Object>> organizations = jdbcTemplate.queryForList("""
                SELECT id,name,organization_type FROM organizations
                WHERE organization_type='COMPANY' AND status='ACTIVE' AND LOWER(name) LIKE ?
                ORDER BY name LIMIT 50
                """, needle);
        StringBuilder userSql = new StringBuilder("""
                SELECT DISTINCT u.id,u.username,u.email FROM users u
                JOIN organization_members om ON om.user_id=u.id AND om.status='ACTIVE'
                JOIN organizations o ON o.id=om.organization_id AND o.status='ACTIVE'
                WHERE (LOWER(u.username) LIKE ? OR LOWER(COALESCE(u.email,'')) LIKE ?)
                """);
        List<Object> userArgs = new ArrayList<>(List.of(needle, needle));
        if (organizationId != null) {
            userSql.append(" AND o.id=?"); userArgs.add(organizationId);
        } else if ("COMPANY".equals(audience)) {
            userSql.append(" AND o.organization_type='COMPANY'");
        } else if ("PERSONAL".equals(audience)) {
            userSql.append(" AND o.organization_type='PERSONAL'");
        }
        userSql.append(" ORDER BY u.username LIMIT 50");
        List<Map<String, Object>> users = jdbcTemplate.queryForList(userSql.toString(), userArgs.toArray());

        StringBuilder modelWhere = new StringBuilder(" WHERE LOWER(l.model) LIKE ?");
        List<Object> modelArgs = new ArrayList<>(List.of(needle));
        appendAudience(modelWhere, modelArgs, audience, organizationId);
        List<Map<String, Object>> models = jdbcTemplate.queryForList(
                "SELECT DISTINCT l.model FROM logs l LEFT JOIN organizations o ON o.id=l.organization_id"
                        + modelWhere + " ORDER BY l.model LIMIT 100", modelArgs.toArray());
        return Map.of("organizations", organizations, "users", users, "models", models);
    }

    public List<Map<String, Object>> adminLogs() {
        return jdbcTemplate.queryForList("""
                SELECT id, admin_id, admin_name, action, target_type, target_id, before_data, after_data, ip_address, result, created_at
                FROM admin_audit_logs
                ORDER BY created_at DESC
                LIMIT 500
                """);
    }

    private void appendFilters(StringBuilder where, List<Object> args, String audienceType,
                               Long organizationId, Long userId, String model, String from, String to) {
        appendAudience(where, args, normalizeAudience(audienceType), organizationId);
        if (userId != null) { where.append(" AND l.user_id=?"); args.add(userId); }
        if (model != null && !model.isBlank()) { where.append(" AND l.model=?"); args.add(model.trim()); }
        if (from != null && !from.isBlank()) { where.append(" AND l.created_at>=?"); args.add(date(from).atStartOfDay()); }
        if (to != null && !to.isBlank()) { where.append(" AND l.created_at<?"); args.add(date(to).plusDays(1).atStartOfDay()); }
    }

    private void appendAudience(StringBuilder where, List<Object> args, String audience, Long organizationId) {
        if (organizationId != null) { where.append(" AND l.organization_id=?"); args.add(organizationId); }
        if ("COMPANY".equals(audience)) where.append(" AND o.organization_type='COMPANY'");
        if ("PERSONAL".equals(audience)) where.append(" AND COALESCE(o.organization_type,'PERSONAL')='PERSONAL'");
    }

    private String normalizeAudience(String value) {
        String audience = value == null ? "" : value.trim().toUpperCase();
        if (!audience.isBlank() && !List.of("PERSONAL", "COMPANY").contains(audience)) {
            throw new IllegalArgumentException("audienceType must be PERSONAL or COMPANY");
        }
        return audience;
    }

    private LocalDate date(String value) {
        try { return LocalDate.parse(value.trim()); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Date must use YYYY-MM-DD"); }
    }
}
