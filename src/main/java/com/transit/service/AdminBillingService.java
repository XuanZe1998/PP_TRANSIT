package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminBillingService {

    private final JdbcTemplate jdbcTemplate;
    private final RedeemCodeService redeemCodeService;

    public List<Map<String, Object>> transactions() {
        return jdbcTemplate.queryForList("""
                SELECT wt.id, wt.user_id, u.username, wt.type, wt.amount, wt.balance_after, wt.channel, wt.remark, wt.created_at
                FROM wallet_transactions wt
                LEFT JOIN users u ON u.id = wt.user_id
                ORDER BY wt.created_at DESC
                LIMIT 500
                """);
    }

    public List<Map<String, Object>> redeemCodes() {
        return redeemCodeService.list();
    }

    public Map<String, Object> createRedeemCode(Map<String, Object> request) {
        String code = stringValue(request, "code", null);
        long amount = longValue(request, "amount", 200000L);
        int maxUses = intValue(request, "maxUses", 1);
        return redeemCodeService.issue(code, amount, maxUses);
    }

    public Map<String, Object> financeSummary() {
        long income = queryLong("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions WHERE type IN ('RECHARGE', 'REDEEM', 'ADJUSTMENT') AND amount > 0");
        long spending = queryLong("SELECT ABS(COALESCE(SUM(amount), 0)) FROM wallet_transactions WHERE type = 'CONSUME'");
        long balance = queryLong("SELECT COALESCE(SUM(balance), 0) FROM users");
        long codes = queryLong("SELECT COUNT(*) FROM redeem_codes WHERE enabled = TRUE");
        return Map.of("income", income, "spending", spending, "userBalance", balance, "activeRedeemCodes", codes);
    }

    private long queryLong(String sql) {
        Number number = jdbcTemplate.queryForObject(sql, Number.class);
        return number == null ? 0 : number.longValue();
    }

    private String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Long.parseLong(value.toString());
    }

    private int intValue(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Integer.parseInt(value.toString());
    }
}
