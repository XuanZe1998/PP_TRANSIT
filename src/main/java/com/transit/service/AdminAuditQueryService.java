package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAuditQueryService {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> requestLogs() {
        return jdbcTemplate.queryForList("""
                SELECT l.id, l.trace_id, l.user_id, u.username, l.token_key, l.model, l.channel_id, c.name AS channel_name,
                       l.prompt_tokens, l.completion_tokens, l.cached_tokens,
                       l.cache_read_tokens, l.cache_write_tokens, l.total_tokens, l.cost,
                       l.input_amount, l.output_amount, l.cached_amount,
                       l.cache_read_amount, l.cache_write_amount, l.total_amount,
                       l.sale_amount, l.cost_amount, l.input_cost_amount, l.output_cost_amount,
                       l.cached_cost_amount, l.cache_read_cost_amount, l.cache_write_cost_amount, l.gross_profit,
                       l.latency_ms, l.status, l.error_message, l.created_at
                FROM logs l
                LEFT JOIN users u ON u.id = l.user_id
                LEFT JOIN channels c ON c.id = l.channel_id
                ORDER BY l.created_at DESC
                LIMIT 500
                """);
    }

    public List<Map<String, Object>> adminLogs() {
        return jdbcTemplate.queryForList("""
                SELECT id, admin_id, admin_name, action, target_type, target_id, before_data, after_data, ip_address, result, created_at
                FROM admin_audit_logs
                ORDER BY created_at DESC
                LIMIT 500
                """);
    }
}
