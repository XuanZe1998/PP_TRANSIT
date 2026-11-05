package com.transit.service;

import com.transit.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentDistributionService {
    private final JdbcTemplate jdbc;
    private final WalletBalanceService walletBalanceService;
    private final ReferralService referrals;

    @Value("${features.linknux.agent.enabled:false}")
    private boolean enabled;
    @Value("${billing.amount-scale:10000}")
    private long amountScale;
    @Value("${features.linknux.agent.freeze-days:7}")
    private int freezeDays;
    @Value("${features.linknux.agent.min-withdrawal-cny:0.01}")
    private BigDecimal minWithdrawalCny;

    public Map<String, Object> featureStatus() {
        return Map.of("enabled", enabled, "freezeDays", freezeDays,
                "minimumWithdrawal", minimumWithdrawal(), "amountScale", amountScale);
    }

    @Transactional
    public Map<String, Object> apply(Long userId, int requestedRebateBps) {
        requireEnabled();
        if (jdbc.update("""
                UPDATE agent_profiles SET status='PENDING',requested_rebate_bps=0,updated_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND status='INVITER'
                """, userId) == 1) return profile(userId);
        String code = uniqueInviteCode();
        try {
            jdbc.update("INSERT INTO agent_profiles(user_id,tier_code,invite_code,status,requested_rebate_bps,created_at,updated_at) VALUES (?,'BASIC',?,'PENDING',?,?,?)",
                    userId, code, 0, LocalDateTime.now(), LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已提交过代理申请");
        }
        return profile(userId);
    }

    @Transactional
    public void bindByInvite(Long customerUserId, String inviteCode, String source) {
        if (inviteCode == null || inviteCode.isBlank()) return;
        if (referrals.bindInvite(customerUserId, inviteCode, source)) return;
        if (!enabled) return;
        Map<String, Object> agent = jdbc.queryForList("""
                SELECT ap.user_id,ap.invite_code
                FROM agent_profiles ap JOIN agent_tiers t ON t.tier_code=ap.tier_code
                WHERE UPPER(ap.invite_code)=UPPER(?) AND ap.status='ACTIVE' AND t.enabled=TRUE
                """, inviteCode.trim()).stream().findFirst()
                .orElse(null);
        if (agent == null) return;
        long agentUserId = ((Number) agent.get("user_id")).longValue();
        if (agentUserId == customerUserId) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能绑定自己的邀请码");
        try {
            jdbc.update("INSERT INTO agent_customer_bindings(customer_user_id,agent_user_id,invite_code,customer_rebate_bps,binding_source,bound_at) VALUES (?,?,?,?,?,?)",
                    customerUserId, agentUserId, String.valueOf(agent.get("invite_code")), 0, source, LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) {
            // Registration/OAuth callbacks can repeat. The original immutable relationship wins.
        }
    }

    @Transactional
    public void settleApiUsage(String businessEventId, Long customerUserId, long saleAmount, long costAmount) {
        if (!enabled || customerUserId == null || saleAmount <= 0) return;
        Long agentUserId = jdbc.queryForList("""
                SELECT b.agent_user_id FROM agent_customer_bindings b
                JOIN agent_profiles p ON p.user_id=b.agent_user_id AND p.status='ACTIVE'
                JOIN agent_tiers t ON t.tier_code=p.tier_code AND t.enabled=TRUE
                JOIN users u ON u.id=p.user_id AND u.status='ACTIVE'
                WHERE b.customer_user_id=? AND b.agent_user_id<>b.customer_user_id
                """, Long.class, customerUserId).stream().findFirst().orElse(null);
        if (agentUserId == null) return;

        long cost = Math.max(0, costAmount);
        long gross = Math.max(0, saleAmount - cost);
        int remainder = 0;
        if (saleAmount % 100 != 0) {
            jdbc.update("""
                    INSERT INTO agent_api_commission_remainders(agent_user_id) VALUES (?)
                    ON DUPLICATE KEY UPDATE agent_user_id=agent_user_id
                    """, agentUserId);
            remainder = jdbc.queryForObject("""
                    SELECT remainder_units FROM agent_api_commission_remainders WHERE agent_user_id=? FOR UPDATE
                    """, Integer.class, agentUserId);
        }
        long fractional = saleAmount % 100 + remainder;
        long commission = saleAmount / 100 + fractional / 100;
        try {
            jdbc.update("""
                    INSERT INTO agent_commission_events
                    (business_event_id,business_type,customer_user_id,agent_user_id,sale_amount,cost_amount,
                     gross_profit,commission_pool,customer_rebate_amount,agent_commission_amount,amount_scale,status,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,'SETTLED',?)
                    """, businessEventId, "API_USAGE", customerUserId, agentUserId, saleAmount, cost, gross, commission,
                    0, commission, amountScale, LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        if (saleAmount % 100 != 0) {
            jdbc.update("UPDATE agent_api_commission_remainders SET remainder_units=? WHERE agent_user_id=?",
                    fractional % 100, agentUserId);
        }
        if (commission == 0) return;
        Long eventId = jdbc.queryForObject("SELECT id FROM agent_commission_events WHERE business_event_id=?", Long.class, businessEventId);
        ledger(eventId, businessEventId, agentUserId, agentUserId, customerUserId,
                "AGENT_COMMISSION", commission, "FROZEN", LocalDateTime.now().plusDays(Math.max(0, freezeDays)), "API 消费佣金");
    }

    @Scheduled(fixedDelayString = "${features.linknux.agent.unfreeze-ms:60000}", initialDelayString = "${features.linknux.agent.unfreeze-ms:60000}")
    public void unfreezeDue() {
        if (enabled) jdbc.update("UPDATE agent_commission_ledger SET status='AVAILABLE' WHERE entry_type='AGENT_COMMISSION' AND status='FROZEN' AND available_at<=CURRENT_TIMESTAMP");
    }

    @Transactional
    public Map<String, Object> transferToBalance(Long userId, long amount, String eventKey) {
        requireEnabled();
        if (amount <= 0 || eventKey == null || eventKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额和业务事件不能为空");
        String businessId = "TRANSFER:" + eventKey.trim();
        // Serialize distinct transfers for one agent before checking available commission.
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE", Long.class, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "代理账户不可用");
        }
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT agent_user_id,agent_commission_amount FROM agent_commission_events WHERE business_event_id=? AND business_type='TRANSFER'", businessId);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (((Number) row.get("agent_user_id")).longValue() != userId || ((Number) row.get("agent_commission_amount")).longValue() != amount) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "业务事件已被其他转账使用");
            }
            return summary(userId);
        }
        long available = availableCommission(userId);
        if (amount > available) throw new ResponseStatusException(HttpStatus.CONFLICT, "可用佣金不足");
        try {
            jdbc.update("INSERT INTO agent_commission_events(business_event_id,business_type,customer_user_id,agent_user_id,sale_amount,cost_amount,gross_profit,commission_pool,customer_rebate_amount,agent_commission_amount,amount_scale,status,created_at) VALUES (?,'TRANSFER',?,?,0,0,0,0,0,?,?,'SETTLED',?)",
                    businessId, userId, userId, amount, amountScale, LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) { throw new ResponseStatusException(HttpStatus.CONFLICT, "业务事件已被使用"); }
        Long transferEventId = jdbc.queryForObject("SELECT id FROM agent_commission_events WHERE business_event_id=?", Long.class, businessId);
        ledger(transferEventId, businessId, userId, userId, userId, "TRANSFER_OUT", -amount,
                "AVAILABLE", LocalDateTime.now(), "佣金转入平台余额");
        long balance = walletBalanceService.credit(userId, amount).balance();
        jdbc.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark,created_at) VALUES (?,'AGENT_TRANSFER',?,?, 'agent',?,?)",
                userId, amount, balance, businessId, LocalDateTime.now());
        return summary(userId);
    }

    @Transactional
    public Map<String, Object> requestWithdrawal(Long userId, long amount, String destinationType, String destinationEncrypted) {
        requireEnabled();
        long minimum = minimumWithdrawal();
        if (amount < minimum) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现金额不得低于 ¥" + minWithdrawalCny);
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE", Long.class, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "代理账户不可用");
        }
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
        return summary(userId, true);
    }

    public Map<String, Object> summary(Long userId, boolean includeLegacyWithdrawals) {
        Map<String, Object> profile = profileOrEmpty(userId);
        Map<String, Object> customerBinding = jdbc.queryForList("SELECT 0 AS customer_rebate_bps,p.status,t.display_name FROM agent_customer_bindings b JOIN agent_profiles p ON p.user_id=b.agent_user_id JOIN agent_tiers t ON t.tier_code=p.tier_code WHERE b.customer_user_id=?", userId).stream().findFirst().orElse(Map.of());
        return Map.of("feature", featureStatus(), "profile", profile, "customerBinding", customerBinding,
                "customerCount", count("SELECT COUNT(*) FROM agent_customer_bindings WHERE agent_user_id=?", userId),
                "frozenCommission", sum("SELECT COALESCE(SUM(amount),0) FROM agent_commission_ledger WHERE beneficiary_user_id=? AND entry_type='AGENT_COMMISSION' AND status='FROZEN'", userId)
                        + sum("""
                        SELECT COALESCE(SUM(r.commission_cents-r.reversed_cents),0)
                        FROM referral_orders r JOIN service_orders o ON o.id=r.order_id
                        WHERE r.inviter_id=? AND r.mode='AGENT_FIXED'
                          AND r.status IN ('AWAITING_PAYMENT','AWAITING_DELIVERY','FROZEN')
                          AND o.status IN ('PAID','FULFILLED')
                        """, userId) * (amountScale / 100)
                        + sum("""
                        SELECT COALESCE(SUM(r.resale_cents-r.resale_reversed_cents),0)
                        FROM referral_orders r JOIN service_orders o ON o.id=r.order_id
                        WHERE r.resale_agent_id=? AND r.status IN ('AWAITING_PAYMENT','AWAITING_DELIVERY','FROZEN')
                          AND o.status IN ('PAID','FULFILLED')
                        """, userId) * (amountScale / 100),
                "availableCommission", Math.max(0, availableCommission(userId)),
                "withdrawals", includeLegacyWithdrawals
                        ? jdbc.queryForList("SELECT request_no,amount,destination_type,status,audit_note,created_at,reviewed_at FROM agent_withdrawals WHERE agent_user_id=? ORDER BY id DESC LIMIT 50", userId)
                        : List.of());
    }

    private static final String AGENTS_FROM = " FROM agent_profiles p JOIN users u ON u.id=p.user_id WHERE p.status<>'INVITER'";
    private static final String AGENTS_SELECT = """
            SELECT p.id,p.user_id,u.username,u.display_name,p.tier_code,p.invite_code,p.status,
                   p.requested_rebate_bps,p.created_at,p.approved_at,
                   (SELECT COUNT(*) FROM agent_customer_bindings b WHERE b.agent_user_id=p.user_id) customer_count,
                   (SELECT COALESCE(SUM(l.amount),0) FROM agent_commission_ledger l WHERE l.beneficiary_user_id=p.user_id AND l.entry_type='AGENT_COMMISSION' AND l.status='AVAILABLE') available_commission
            """;
    private static final String WITHDRAWALS_FROM = " FROM agent_withdrawals w JOIN users u ON u.id=w.agent_user_id";
    private static final String WITHDRAWALS_SELECT = """
            SELECT w.id,w.request_no,w.agent_user_id,u.username,u.display_name,w.amount,
                   w.destination_type,w.status,w.audit_note,w.reviewed_by,w.reviewed_at,w.created_at
            """;
    private static final String WITHDRAWALS_ORDER = " ORDER BY CASE w.status WHEN 'PENDING' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END,w.id DESC";

    public List<Map<String, Object>> adminAgents() {
        return jdbc.queryForList(AGENTS_SELECT + AGENTS_FROM + " ORDER BY p.id DESC");
    }

    public PageResponse<Map<String, Object>> adminAgentsPage(int page, int size) {
        return listPage(AGENTS_SELECT, AGENTS_FROM, " ORDER BY p.id DESC", page, size);
    }

    public List<Map<String, Object>> serviceCosts() {
        return jdbc.queryForList("SELECT id,name,cost_cents,commission_refund_window_days,enabled FROM other_services ORDER BY sort_order,id");
    }

    public List<Map<String, Object>> adminWithdrawals() {
        return jdbc.queryForList(WITHDRAWALS_SELECT + WITHDRAWALS_FROM + WITHDRAWALS_ORDER);
    }

    public PageResponse<Map<String, Object>> adminWithdrawalsPage(int page, int size) {
        return listPage(WITHDRAWALS_SELECT, WITHDRAWALS_FROM, WITHDRAWALS_ORDER, page, size);
    }

    public PageResponse<Map<String, Object>> userWithdrawalsPage(long userId, int page, int size) {
        return listPage("SELECT request_no,amount,destination_type,status,audit_note,created_at,reviewed_at",
                " FROM agent_withdrawals WHERE agent_user_id=?", " ORDER BY id DESC", page, size, userId);
    }

    private PageResponse<Map<String, Object>> listPage(String select, String from, String order,
                                                       int page, int size, Object... filters) {
        if (page < 1 || page > 1_000_000 || !List.of(10, 20, 50, 100).contains(size)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数无效");
        }
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, filters);
        var args = new java.util.ArrayList<>(java.util.Arrays.asList(filters));
        args.add(size);
        args.add((page - 1L) * size);
        PageResponse<Map<String, Object>> result = new PageResponse<>();
        result.setPage(page); result.setSize(size); result.setTotal(total);
        result.setItems(jdbc.queryForList(select + from + order + " LIMIT ? OFFSET ?", args.toArray()));
        return result;
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
        int updated = jdbc.update("UPDATE agent_profiles SET status=?,tier_code=COALESCE(?,tier_code),approved_at=CASE WHEN ?='ACTIVE' THEN CURRENT_TIMESTAMP ELSE approved_at END,approved_by=?,suspended_at=CASE WHEN ?='SUSPENDED' THEN CURRENT_TIMESTAMP ELSE NULL END,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status<>'INVITER'",
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

    private long availableCommission(Long userId) { return sum("SELECT COALESCE(SUM(amount),0) FROM agent_commission_ledger WHERE beneficiary_user_id=? AND status='AVAILABLE' AND entry_type IN ('AGENT_COMMISSION','AGENT_COMMISSION_REVERSAL','AGENT_RESALE','AGENT_RESALE_REVERSAL','TRANSFER_OUT','WITHDRAWAL_HOLD','WITHDRAWAL_RELEASE')", userId); }
    private long sum(String sql, Long userId) { Long value = jdbc.queryForObject(sql, Long.class, userId); return value == null ? 0 : value; }
    private long count(String sql, Long userId) { Long value = jdbc.queryForObject(sql, Long.class, userId); return value == null ? 0 : value; }
    private long minimumWithdrawal() {
        return Math.max(1, minWithdrawalCny.multiply(BigDecimal.valueOf(amountScale))
                .setScale(0, RoundingMode.CEILING).longValueExact());
    }
    private void requireEnabled() { if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "代理分销功能尚未启用"); }
    private Map<String, Object> profile(Long userId) { return jdbc.queryForMap("SELECT p.*,t.display_name,t.commission_bps,t.max_customer_rebate_bps,t.min_platform_margin_bps FROM agent_profiles p JOIN agent_tiers t ON t.tier_code=p.tier_code WHERE p.user_id=?", userId); }
    private Map<String, Object> profileOrEmpty(Long userId) { return jdbc.queryForList("SELECT p.*,t.display_name,t.commission_bps,t.max_customer_rebate_bps,t.min_platform_margin_bps FROM agent_profiles p JOIN agent_tiers t ON t.tier_code=p.tier_code WHERE p.user_id=?", userId).stream().findFirst().orElse(Map.of()); }
    private String uniqueInviteCode() { return "LX" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(); }
}
