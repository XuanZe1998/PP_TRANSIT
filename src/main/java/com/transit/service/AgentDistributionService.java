package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentDistributionService {
    private final JdbcTemplate jdbc;

    @Value("${features.linknux.agent.enabled:false}")
    private boolean enabled;
    @Value("${billing.amount-scale:10000}")
    private long amountScale;
    @Value("${features.linknux.agent.freeze-days:7}")
    private int freezeDays;
    @Value("${features.linknux.agent.min-withdrawal-cny:100}")
    private long minWithdrawalCny;

    public Map<String, Object> featureStatus() {
        return Map.of("enabled", enabled, "freezeDays", freezeDays,
                "minimumWithdrawal", Math.multiplyExact(minWithdrawalCny, amountScale), "amountScale", amountScale);
    }

    public boolean requiresReliableCost(Long userId) {
        if (!enabled || userId == null) return false;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_customer_bindings WHERE customer_user_id=?", Long.class, userId);
        return count != null && count > 0;
    }

    @Transactional
    public Map<String, Object> apply(Long userId, int requestedRebateBps) {
        requireEnabled();
        if (requestedRebateBps < 0 || requestedRebateBps > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基础等级客户返利不能超过 2%");
        }
        String code = uniqueInviteCode();
        try {
            jdbc.update("INSERT INTO agent_profiles(user_id,tier_code,invite_code,status,requested_rebate_bps,created_at,updated_at) VALUES (?,'BASIC',?,'PENDING',?,?,?)",
                    userId, code, requestedRebateBps, LocalDateTime.now(), LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已提交过代理申请");
        }
        return profile(userId);
    }

    @Transactional
    public void bindByInvite(Long customerUserId, String inviteCode, String source) {
        if (!enabled || inviteCode == null || inviteCode.isBlank()) return;
        Map<String, Object> agent = jdbc.queryForList("""
                SELECT ap.user_id,ap.invite_code,ap.requested_rebate_bps,t.max_customer_rebate_bps
                FROM agent_profiles ap JOIN agent_tiers t ON t.tier_code=ap.tier_code
                WHERE UPPER(ap.invite_code)=UPPER(?) AND ap.status='ACTIVE' AND t.enabled=TRUE
                """, inviteCode.trim()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "邀请码无效或代理不可用"));
        long agentUserId = ((Number) agent.get("user_id")).longValue();
        if (agentUserId == customerUserId) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能绑定自己的邀请码");
        int requested = ((Number) agent.get("requested_rebate_bps")).intValue();
        int maximum = ((Number) agent.get("max_customer_rebate_bps")).intValue();
        try {
            jdbc.update("INSERT INTO agent_customer_bindings(customer_user_id,agent_user_id,invite_code,customer_rebate_bps,binding_source,bound_at) VALUES (?,?,?,?,?,?)",
                    customerUserId, agentUserId, String.valueOf(agent.get("invite_code")), Math.min(requested, maximum), source, LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) {
            // Registration/OAuth callbacks can repeat. The original immutable relationship wins.
        }
    }

    /** Called only when the provider cost snapshot is reliable. All arithmetic is integer/basis-point based. */
    @Transactional
    public void settleApiUsage(String businessEventId, Long customerUserId, long saleAmount, long costAmount) {
        settleGrossProfit(businessEventId, "API_USAGE", customerUserId, saleAmount, costAmount);
    }

    @Transactional
    public void settleServiceOrder(String orderNo, Long customerUserId, long saleAmount, long costAmount) {
        settleGrossProfit("SERVICE_ORDER:" + orderNo, "SERVICE_ORDER", customerUserId, saleAmount, costAmount);
    }

    private void settleGrossProfit(String businessEventId, String businessType, Long customerUserId, long saleAmount, long costAmount) {
        if (!enabled || customerUserId == null || saleAmount <= 0 || costAmount < 0) return;
        Map<String, Object> binding = jdbc.queryForList("""
                SELECT b.agent_user_id,b.customer_rebate_bps,t.commission_bps,t.max_customer_rebate_bps,
                       t.min_platform_margin_bps
                FROM agent_customer_bindings b
                JOIN agent_profiles p ON p.user_id=b.agent_user_id AND p.status='ACTIVE'
                JOIN agent_tiers t ON t.tier_code=p.tier_code AND t.enabled=TRUE
                WHERE b.customer_user_id=?
                """, customerUserId).stream().findFirst().orElse(null);
        if (binding == null) return;
        long agentUserId = ((Number) binding.get("agent_user_id")).longValue();
        if (agentUserId == customerUserId) return;
        long gross = Math.max(0, saleAmount - costAmount);
        int commissionBps = ((Number) binding.get("commission_bps")).intValue();
        int minMarginBps = ((Number) binding.get("min_platform_margin_bps")).intValue();
        int rebateBps = Math.min(((Number) binding.get("customer_rebate_bps")).intValue(),
                ((Number) binding.get("max_customer_rebate_bps")).intValue());
        long ratePool = bps(gross, commissionBps);
        long retainedMargin = Math.max(0, gross - bps(saleAmount, minMarginBps));
        long pool = Math.max(0, Math.min(ratePool, retainedMargin));
        long rebate = Math.min(pool, bps(saleAmount, rebateBps));
        long commission = Math.max(0, pool - rebate);
        if (rebate == 0 && commission == 0) return;

        try {
            jdbc.update("""
                    INSERT INTO agent_commission_events
                    (business_event_id,business_type,customer_user_id,agent_user_id,sale_amount,cost_amount,
                     gross_profit,commission_pool,customer_rebate_amount,agent_commission_amount,amount_scale,status,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,'SETTLED',?)
                    """, businessEventId, businessType, customerUserId, agentUserId, saleAmount, costAmount, gross, pool,
                    rebate, commission, amountScale, LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        Long eventId = jdbc.queryForObject("SELECT id FROM agent_commission_events WHERE business_event_id=?", Long.class, businessEventId);
        if (rebate > 0) {
            int credited = jdbc.update("UPDATE users SET balance=balance+? WHERE id=? AND status='ACTIVE'", rebate, customerUserId);
            if (credited != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "返利账户不可用");
            long balance = jdbc.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, customerUserId);
            jdbc.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark,created_at) VALUES (?,'AGENT_REBATE',?,?, 'agent',?,?)",
                    customerUserId, rebate, balance, "消费返利 " + businessEventId, LocalDateTime.now());
            ledger(eventId, businessEventId, customerUserId, agentUserId, customerUserId,
                    "CUSTOMER_REBATE", rebate, "AVAILABLE", LocalDateTime.now(), "API 消费即时返利");
        }
        if (commission > 0) ledger(eventId, businessEventId, agentUserId, agentUserId, customerUserId,
                "AGENT_COMMISSION", commission, "FROZEN", LocalDateTime.now().plusDays(Math.max(1, freezeDays)), "API 消费佣金");
    }

    @Scheduled(fixedDelayString = "${features.linknux.agent.unfreeze-ms:60000}", initialDelayString = "${features.linknux.agent.unfreeze-ms:60000}")
    public void unfreezeDue() {
        if (enabled) jdbc.update("UPDATE agent_commission_ledger SET status='AVAILABLE' WHERE entry_type='AGENT_COMMISSION' AND status='FROZEN' AND available_at<=CURRENT_TIMESTAMP");
    }

    @Scheduled(fixedDelayString = "${features.linknux.agent.service-settlement-ms:3600000}", initialDelayString = "${features.linknux.agent.service-settlement-initial-ms:300000}")
    public void settleEligibleServiceOrders() {
        if (!enabled) return;
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT so.order_no,so.user_id,so.payment_amount_cents,so.amount_cents,so.quantity,so.fulfilled_at,
                       os.cost_cents,os.commission_refund_window_days
                FROM service_orders so JOIN other_services os ON os.id=so.service_id
                WHERE so.status='FULFILLED' AND so.fulfillment_status='COMPLETED' AND so.fulfilled_at IS NOT NULL
                  AND os.cost_cents IS NOT NULL AND os.cost_cents>0
                  AND NOT EXISTS (SELECT 1 FROM agent_commission_events e WHERE e.business_event_id=CONCAT('SERVICE_ORDER:',so.order_no))
                ORDER BY so.id LIMIT 500
                """)) {
            Object fulfilledValue = row.get("fulfilled_at");
            LocalDateTime fulfilledAt = fulfilledValue instanceof LocalDateTime value ? value
                    : ((java.sql.Timestamp) fulfilledValue).toLocalDateTime();
            int refundDays = row.get("commission_refund_window_days") == null ? 7 : ((Number) row.get("commission_refund_window_days")).intValue();
            if (fulfilledAt.plusDays(Math.max(0, refundDays)).isAfter(LocalDateTime.now())) continue;
            long cents = row.get("payment_amount_cents") == null ? ((Number) row.get("amount_cents")).longValue() : ((Number) row.get("payment_amount_cents")).longValue();
            long quantity = Math.max(1, ((Number) row.get("quantity")).longValue());
            settleServiceOrder(String.valueOf(row.get("order_no")), ((Number) row.get("user_id")).longValue(),
                    Math.multiplyExact(cents, amountScale / 100), Math.multiplyExact(Math.multiplyExact(((Number) row.get("cost_cents")).longValue(), quantity), amountScale / 100));
        }
    }

    @Transactional
    public Map<String, Object> transferToBalance(Long userId, long amount, String eventKey) {
        requireEnabled();
        if (amount <= 0 || eventKey == null || eventKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额和业务事件不能为空");
        long available = availableCommission(userId);
        if (amount > available) throw new ResponseStatusException(HttpStatus.CONFLICT, "可用佣金不足");
        String businessId = "TRANSFER:" + eventKey.trim();
        try {
            jdbc.update("INSERT INTO agent_commission_events(business_event_id,business_type,customer_user_id,agent_user_id,sale_amount,cost_amount,gross_profit,commission_pool,customer_rebate_amount,agent_commission_amount,amount_scale,status,created_at) VALUES (?,'TRANSFER',?,?,0,0,0,0,0,?,?,'SETTLED',?)",
                    businessId, userId, userId, amount, amountScale, LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) { return summary(userId); }
        Long transferEventId = jdbc.queryForObject("SELECT id FROM agent_commission_events WHERE business_event_id=?", Long.class, businessId);
        ledger(transferEventId, businessId, userId, userId, userId, "TRANSFER_OUT", -amount,
                "AVAILABLE", LocalDateTime.now(), "佣金转入平台余额");
        jdbc.update("UPDATE users SET balance=balance+? WHERE id=?", amount, userId);
        long balance = jdbc.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, userId);
        jdbc.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark,created_at) VALUES (?,'AGENT_TRANSFER',?,?, 'agent',?,?)",
                userId, amount, balance, businessId, LocalDateTime.now());
        return summary(userId);
    }

    @Transactional
    public Map<String, Object> requestWithdrawal(Long userId, long amount, String destinationType, String destinationEncrypted) {
        requireEnabled();
        long minimum = Math.multiplyExact(minWithdrawalCny, amountScale);
        if (amount < minimum) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现金额不得低于 ¥" + minWithdrawalCny);
        if (amount > availableCommission(userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "可用佣金不足");
        String no = "WD" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        String businessId = "WITHDRAWAL:" + no;
        jdbc.update("INSERT INTO agent_commission_events(business_event_id,business_type,customer_user_id,agent_user_id,sale_amount,cost_amount,gross_profit,commission_pool,customer_rebate_amount,agent_commission_amount,amount_scale,status,created_at) VALUES (?,'WITHDRAWAL',?,?,0,0,0,0,0,?,?,'SETTLED',?)",
                businessId, userId, userId, amount, amountScale, LocalDateTime.now());
        Long eventId = jdbc.queryForObject("SELECT id FROM agent_commission_events WHERE business_event_id=?", Long.class, businessId);
        ledger(eventId, businessId, userId, userId, userId, "WITHDRAWAL_HOLD", -amount,
                "AVAILABLE", LocalDateTime.now(), "人工提现冻结");
        jdbc.update("INSERT INTO agent_withdrawals(request_no,agent_user_id,amount,destination_type,destination_encrypted,status,created_at,updated_at) VALUES (?,?,?,?,?,'PENDING',?,?)",
                no, userId, amount, destinationType == null ? "MANUAL" : destinationType, destinationEncrypted,
                LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForMap("SELECT request_no,amount,status,created_at FROM agent_withdrawals WHERE request_no=?", no);
    }

    public Map<String, Object> summary(Long userId) {
        Map<String, Object> profile = profileOrEmpty(userId);
        Map<String, Object> customerBinding = jdbc.queryForList("SELECT b.customer_rebate_bps,p.status,t.display_name FROM agent_customer_bindings b JOIN agent_profiles p ON p.user_id=b.agent_user_id JOIN agent_tiers t ON t.tier_code=p.tier_code WHERE b.customer_user_id=?", userId).stream().findFirst().orElse(Map.of());
        return Map.of("feature", featureStatus(), "profile", profile, "customerBinding", customerBinding,
                "customerCount", count("SELECT COUNT(*) FROM agent_customer_bindings WHERE agent_user_id=?", userId),
                "frozenCommission", sum("SELECT COALESCE(SUM(amount),0) FROM agent_commission_ledger WHERE beneficiary_user_id=? AND entry_type='AGENT_COMMISSION' AND status='FROZEN'", userId),
                "availableCommission", availableCommission(userId),
                "withdrawals", jdbc.queryForList("SELECT request_no,amount,destination_type,status,audit_note,created_at,reviewed_at FROM agent_withdrawals WHERE agent_user_id=? ORDER BY id DESC LIMIT 50", userId));
    }

    public List<Map<String, Object>> adminAgents() {
        return jdbc.queryForList("""
                SELECT p.id,p.user_id,u.username,u.display_name,p.tier_code,p.invite_code,p.status,
                       p.requested_rebate_bps,p.created_at,p.approved_at,
                       (SELECT COUNT(*) FROM agent_customer_bindings b WHERE b.agent_user_id=p.user_id) customer_count,
                       (SELECT COALESCE(SUM(l.amount),0) FROM agent_commission_ledger l WHERE l.beneficiary_user_id=p.user_id AND l.entry_type='AGENT_COMMISSION' AND l.status='AVAILABLE') available_commission
                FROM agent_profiles p JOIN users u ON u.id=p.user_id ORDER BY p.id DESC
                """);
    }

    public List<Map<String, Object>> serviceCosts() {
        return jdbc.queryForList("SELECT id,name,cost_cents,commission_refund_window_days,enabled FROM other_services ORDER BY sort_order,id");
    }

    public List<Map<String, Object>> adminWithdrawals() {
        return jdbc.queryForList("""
                SELECT w.id,w.request_no,w.agent_user_id,u.username,u.display_name,w.amount,
                       w.destination_type,w.status,w.audit_note,w.reviewed_by,w.reviewed_at,w.created_at
                FROM agent_withdrawals w JOIN users u ON u.id=w.agent_user_id
                ORDER BY CASE w.status WHEN 'PENDING' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END,w.id DESC
                LIMIT 500
                """);
    }

    public Map<String, Object> updateServiceCost(Long id, Long costCents, int refundWindowDays) {
        if (costCents != null && costCents < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成本价不能为负数");
        if (refundWindowDays < 0 || refundWindowDays > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款观察期需为 0–365 天");
        int updated = jdbc.update("UPDATE other_services SET cost_cents=?,commission_refund_window_days=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", costCents, refundWindowDays, id);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "服务不存在");
        return jdbc.queryForMap("SELECT id,name,cost_cents,commission_refund_window_days FROM other_services WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> reviewAgent(Long adminId, Long profileId, String status, String tierCode) {
        String normalized = String.valueOf(status).toUpperCase();
        if (!List.of("ACTIVE", "SUSPENDED", "REJECTED").contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代理状态无效");
        int updated = jdbc.update("UPDATE agent_profiles SET status=?,tier_code=COALESCE(?,tier_code),approved_at=CASE WHEN ?='ACTIVE' THEN CURRENT_TIMESTAMP ELSE approved_at END,approved_by=?,suspended_at=CASE WHEN ?='SUSPENDED' THEN CURRENT_TIMESTAMP ELSE NULL END,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                normalized, tierCode, normalized, adminId, normalized, profileId);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "代理资料不存在");
        return jdbc.queryForMap("SELECT * FROM agent_profiles WHERE id=?", profileId);
    }

    @Transactional
    public Map<String, Object> reviewWithdrawal(Long adminId, Long id, String status, String note) {
        String normalized = String.valueOf(status).toUpperCase();
        if (!List.of("APPROVED", "PAID", "REJECTED").contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现状态无效");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM agent_withdrawals WHERE id=? FOR UPDATE", id);
        String current = String.valueOf(row.get("status"));
        if (!"PENDING".equals(current) && !"APPROVED".equals(current)) return row;
        jdbc.update("UPDATE agent_withdrawals SET status=?,audit_note=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                normalized, note, adminId, id);
        if ("REJECTED".equals(normalized)) {
            long userId = ((Number) row.get("agent_user_id")).longValue();
            long amount = ((Number) row.get("amount")).longValue();
            String businessId = "WITHDRAWAL_RELEASE:" + row.get("request_no");
            try {
                jdbc.update("INSERT INTO agent_commission_events(business_event_id,business_type,customer_user_id,agent_user_id,sale_amount,cost_amount,gross_profit,commission_pool,customer_rebate_amount,agent_commission_amount,amount_scale,status,created_at) VALUES (?,'REVERSAL',?,?,0,0,0,0,0,?,?,'SETTLED',?)",
                        businessId, userId, userId, amount, amountScale, LocalDateTime.now());
                Long eventId = jdbc.queryForObject("SELECT id FROM agent_commission_events WHERE business_event_id=?", Long.class, businessId);
                ledger(eventId, businessId, userId, userId, userId, "WITHDRAWAL_RELEASE", amount,
                        "AVAILABLE", LocalDateTime.now(), "提现驳回释放");
            } catch (DuplicateKeyException ignored) { /* idempotent review */ }
        }
        return jdbc.queryForMap("SELECT * FROM agent_withdrawals WHERE id=?", id);
    }

    private void ledger(Long eventId, String businessId, long beneficiary, long agent, long customer,
                        String type, long amount, String status, LocalDateTime availableAt, String note) {
        jdbc.update("INSERT INTO agent_commission_ledger(event_id,business_event_id,beneficiary_user_id,agent_user_id,customer_user_id,entry_type,amount,status,available_at,note,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                eventId, businessId, beneficiary, agent, customer, type, amount, status, availableAt, note, LocalDateTime.now());
    }

    private long availableCommission(Long userId) { return sum("SELECT COALESCE(SUM(amount),0) FROM agent_commission_ledger WHERE beneficiary_user_id=? AND status='AVAILABLE' AND entry_type IN ('AGENT_COMMISSION','TRANSFER_OUT','WITHDRAWAL_HOLD','WITHDRAWAL_RELEASE')", userId); }
    private long sum(String sql, Long userId) { Long value = jdbc.queryForObject(sql, Long.class, userId); return value == null ? 0 : value; }
    private long count(String sql, Long userId) { Long value = jdbc.queryForObject(sql, Long.class, userId); return value == null ? 0 : value; }
    private long bps(long value, int basisPoints) { return Math.multiplyExact(value, Math.max(0, basisPoints)) / 10_000L; }
    private void requireEnabled() { if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "代理分销功能尚未启用"); }
    private Map<String, Object> profile(Long userId) { return jdbc.queryForMap("SELECT p.*,t.display_name,t.commission_bps,t.max_customer_rebate_bps,t.min_platform_margin_bps FROM agent_profiles p JOIN agent_tiers t ON t.tier_code=p.tier_code WHERE p.user_id=?", userId); }
    private Map<String, Object> profileOrEmpty(Long userId) { return jdbc.queryForList("SELECT p.*,t.display_name,t.commission_bps,t.max_customer_rebate_bps,t.min_platform_margin_bps FROM agent_profiles p JOIN agent_tiers t ON t.tier_code=p.tier_code WHERE p.user_id=?", userId).stream().findFirst().orElse(Map.of()); }
    private String uniqueInviteCode() { return "LX" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(); }
}
