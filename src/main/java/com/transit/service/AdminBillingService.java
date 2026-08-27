package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import com.transit.dto.MoneyAmount;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AdminBillingService {

    private final JdbcTemplate jdbcTemplate;
    private final RedeemCodeService redeemCodeService;

    public List<Map<String, Object>> transactions() {
        List<Map<String,Object>> rows=jdbcTemplate.queryForList("""
                SELECT wt.id, wt.user_id, u.username, wt.type, wt.amount, wt.balance_after, wt.channel, wt.remark, wt.created_at
                FROM wallet_transactions wt
                LEFT JOIN users u ON u.id = wt.user_id
                ORDER BY wt.created_at DESC
                LIMIT 500
                """);
        rows.forEach(row->{row.put("amountMoney",new MoneyAmount(((Number)row.get("amount")).longValue(),"CNY",10000));row.put("balanceAfterMoney",new MoneyAmount(((Number)row.get("balance_after")).longValue(),"CNY",10000));});
        return rows;
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
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("income",income);result.put("spending",spending);result.put("userBalance",balance);result.put("activeRedeemCodes",codes);
        result.put("incomeMoney",new MoneyAmount(income,"CNY",10000));result.put("spendingMoney",new MoneyAmount(spending,"CNY",10000));result.put("userBalanceMoney",new MoneyAmount(balance,"CNY",10000));
        return result;
    }

    public List<Map<String, Object>> rechargePlans() {
        return jdbcTemplate.queryForList("""
                SELECT id,name,amount,bonus_percent,enabled,sort_order
                FROM recharge_plans ORDER BY sort_order,id
                """);
    }

    public Map<String, Object> createRechargePlan(Map<String, Object> request) {
        String name = requiredName(request);
        long amount = planAmount(request);
        int bonus = bonusPercent(request);
        int sortOrder = intValue(request, "sortOrder", 100);
        boolean enabled = booleanValue(request.get("enabled"), true);
        jdbcTemplate.update("INSERT INTO recharge_plans(name,amount,bonus_percent,enabled,sort_order) VALUES (?,?,?,?,?)",
                name, amount, bonus, enabled, sortOrder);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM recharge_plans WHERE name=?", Long.class, name);
        return rechargePlan(id);
    }

    public Map<String, Object> updateRechargePlan(long id, Map<String, Object> request) {
        rechargePlan(id);
        jdbcTemplate.update("UPDATE recharge_plans SET name=?,amount=?,bonus_percent=?,enabled=?,sort_order=? WHERE id=?",
                requiredName(request), planAmount(request), bonusPercent(request),
                booleanValue(request.get("enabled"), true), intValue(request, "sortOrder", 100), id);
        return rechargePlan(id);
    }

    public void deleteRechargePlan(long id) {
        if (jdbcTemplate.update("DELETE FROM recharge_plans WHERE id=?", id) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recharge plan not found");
        }
    }

    private Map<String, Object> rechargePlan(Long id) {
        return jdbcTemplate.queryForList("SELECT id,name,amount,bonus_percent,enabled,sort_order FROM recharge_plans WHERE id=?", id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recharge plan not found"));
    }

    private String requiredName(Map<String, Object> request) {
        String name = stringValue(request, "name", "");
        if (name.isBlank() || name.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "套餐名称不能为空且不能超过120字符");
        return name;
    }

    private long planAmount(Map<String, Object> request) {
        long amount = longValue(request, "amount", 0);
        if (amount <= 0 || amount > 100_000_000_000L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "套餐金额超出范围");
        return amount;
    }

    private int bonusPercent(Map<String, Object> request) {
        int bonus = intValue(request, "bonusPercent", intValue(request, "bonus_percent", 0));
        if (bonus < 0 || bonus > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠送比例必须在0到1000之间");
        return bonus;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
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
