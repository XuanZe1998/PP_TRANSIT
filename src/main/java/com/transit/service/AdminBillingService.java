package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import com.transit.dto.MoneyAmount;
import com.transit.dto.PageResponse;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;

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

    public PageResponse<Map<String, Object>> transactionsPage(int page, int size, String query) {
        if (page < 1 || page > 1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is out of range");
        if (size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100");
        String needle = query == null ? "" : query.trim().toLowerCase();
        if (needle.length() > 160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is too long");
        String filter = needle.isBlank() ? "" : """
                 WHERE LOWER(COALESCE(u.username,'')) LIKE ?
                    OR LOWER(COALESCE(wt.type,'')) LIKE ?
                    OR LOWER(COALESCE(wt.channel,'')) LIKE ?
                    OR LOWER(COALESCE(wt.remark,'')) LIKE ?
                """;
        List<Object> params = new java.util.ArrayList<>();
        if (!needle.isBlank()) {
            String like = "%" + needle + "%";
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        Number count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wallet_transactions wt LEFT JOIN users u ON u.id=wt.user_id
                """ + filter, Number.class, params.toArray());
        List<Object> itemParams = new java.util.ArrayList<>(params);
        itemParams.add(size);
        itemParams.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT wt.id, wt.user_id, u.username, wt.type, wt.amount, wt.balance_after,
                       wt.channel, wt.remark, wt.created_at
                  FROM wallet_transactions wt
                  LEFT JOIN users u ON u.id=wt.user_id
                """ + filter + " ORDER BY wt.created_at DESC, wt.id DESC LIMIT ? OFFSET ?", itemParams.toArray());
        rows.forEach(this::addTransactionMoney);
        PageResponse<Map<String, Object>> response = new PageResponse<>();
        response.setPage(page); response.setSize(size); response.setTotal(count == null ? 0 : count.longValue()); response.setItems(rows);
        return response;
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
        long rechargeAmount = queryLong("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions WHERE type IN ('RECHARGE', 'REDEEM') AND amount > 0");
        long spending = queryLong("SELECT ABS(COALESCE(SUM(amount), 0)) FROM wallet_transactions WHERE type = 'CONSUME'");
        long balance = queryLong("SELECT COALESCE(SUM(balance), 0) FROM users");
        long codes = queryLong("SELECT COUNT(*) FROM redeem_codes WHERE enabled = TRUE");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("rechargeAmount",rechargeAmount);result.put("spending",spending);result.put("userBalance",balance);result.put("activeRedeemCodes",codes);
        result.put("rechargeAmountMoney",new MoneyAmount(rechargeAmount,"CNY",10000));result.put("spendingMoney",new MoneyAmount(spending,"CNY",10000));result.put("userBalanceMoney",new MoneyAmount(balance,"CNY",10000));
        return result;
    }

    private void addTransactionMoney(Map<String, Object> row) {
        row.put("amountMoney", new MoneyAmount(((Number) row.get("amount")).longValue(), "CNY", 10000));
        row.put("balanceAfterMoney", new MoneyAmount(((Number) row.get("balance_after")).longValue(), "CNY", 10000));
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
        BigDecimal bonus = bonusPercent(request);
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

    private BigDecimal bonusPercent(Map<String, Object> request) {
        Object raw = request.containsKey("bonusPercent") ? request.get("bonusPercent") : request.get("bonus_percent");
        BigDecimal bonus;
        try { bonus = raw == null || raw.toString().isBlank() ? BigDecimal.ZERO : new BigDecimal(raw.toString()).setScale(3, RoundingMode.UNNECESSARY); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠送比例最多保留三位小数"); }
        if (bonus.signum() < 0 || bonus.compareTo(new BigDecimal("1000.000")) > 0
                || (bonus.signum() > 0 && bonus.compareTo(new BigDecimal("0.001")) < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠送比例必须为 0 或 0.001%–1000.000%");
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
