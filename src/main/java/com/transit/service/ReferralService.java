package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.dto.PageResponse;
import com.transit.model.ServiceOrder;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReferralService {
    private final JdbcTemplate jdbc;
    private final ChannelSecretService secrets;
    private final SecretHashService hashes;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final AdminAuditService audit;
    private final ServiceCouponAdminService coupons;
    @Value("${billing.amount-scale:10000}") private long amountScale;
    @Value("${features.linknux.agent.enabled:false}") private boolean agentsEnabled;
    @Value("${features.linknux.agent.freeze-days:7}") private int agentFreezeDays;
    private static final String AGENT_FIXED = "AGENT_FIXED";

    public record Rule(boolean enabled, String mode, long fixedCents, int rateBps, long costCents, String displayText) {
        public Rule { displayText=displayText==null?"":displayText.trim(); }
        public Rule(boolean enabled, String mode, long fixedCents, int rateBps, long costCents) {
            this(enabled,mode,fixedCents,rateBps,costCents,"");
        }
    }
    public record RulePreview(long version, Rule rule, long merchandiseCents, int quantity) {}
    public record Account(String channel, String account, String name) {}
    public record Withdrawal(long amountCents, String requestKey, String accountToken) {}
    public record Review(List<Long> ids, String action, String note) {}
    public record Payment(long amountCents, String reference, LocalDateTime paidAt) {}
    public record Refund(long amountCents, Long merchandiseCents, String reference, LocalDateTime refundedAt) {}
    public record Settings(boolean enabled, int freezeDays, long minimumCents, String serviceNotice, long version,
                           Long firstPurchaseCouponCents) {
        public Settings(boolean enabled, int freezeDays, long minimumCents, String serviceNotice, long version) {
            this(enabled, freezeDays, minimumCents, serviceNotice, version, null);
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private long number(Map<String,Object> row, String key) { return row.get(key) == null ? 0 : ((Number)row.get(key)).longValue(); }
    private boolean flag(Map<String,Object> row, String key) { return Boolean.TRUE.equals(row.get(key)) || "1".equals(String.valueOf(row.get(key))); }
    private String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid("请填写有效内容");
        return value.trim();
    }
    private Map<String,Object> first(String sql, Object... args) {
        return jdbc.queryForList(sql,args).stream().findFirst().orElse(null);
    }
    private long sum(String sql, Object... args) { return Objects.requireNonNullElse(jdbc.queryForObject(sql,Long.class,args),0L); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Unable to encode referral record",e); }
    }
    private void lockUser(long id) {
        if (first("SELECT id FROM users WHERE id=? FOR UPDATE",id)==null) throw invalid("用户不存在");
    }
    private void verified(long id) {
        if (first("SELECT id FROM users WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL",id)==null)
            throw conflict("请先完成邮箱验证");
    }
    public Map<String,Object> settings() { return jdbc.queryForMap("SELECT * FROM referral_settings WHERE id=1"); }
    public Map<String,Object> publicOffer(Long serviceId) {
        if (!agentsEnabled && !flag(settings(),"enabled")) return Map.of();
        Map<String,Object> result=first("""
                SELECT mode,fixed_cents,rate_bps,display_text FROM referral_rules
                WHERE service_id=? AND enabled=TRUE AND mode='FIXED' AND fixed_cents>0
                """,serviceId);
        return result==null?Map.of():result;
    }
    public Long orderCommission(Long orderId) {
        Map<String,Object> row=first("SELECT commission_cents,status FROM referral_orders WHERE order_id=?",orderId);
        return row==null || "INELIGIBLE".equals(row.get("status"))?null:number(row,"commission_cents");
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> saveSettings(User admin, Settings value) {
        if (value.freezeDays()<0 || value.freezeDays()>365 || value.minimumCents()<1 || value.minimumCents()>100000000)
            throw invalid("冻结天数或提现门槛无效");
        long couponCents = value.firstPurchaseCouponCents() == null
                ? number(settings(), "first_purchase_coupon_cents") : value.firstPurchaseCouponCents();
        if (couponCents < 0 || couponCents > 100_000_000) throw invalid("首购券面额无效");
        String notice=required(value.serviceNotice(),500);
        if (jdbc.update("""
                UPDATE referral_settings SET enabled=?,freeze_days=?,minimum_cents=?,service_notice=?,
                    coupon_started_at=CASE WHEN ?=TRUE AND ?>0 THEN COALESCE(coupon_started_at,?) ELSE NULL END,
                    first_purchase_coupon_cents=?,version=version+1
                WHERE id=1 AND version=?
                """, value.enabled(),value.freezeDays(),value.minimumCents(),notice,value.enabled(),couponCents,now(),couponCents,value.version())!=1)
            throw conflict("配置已更新，请刷新");
        audit.record(admin,"REFERRAL_SETTINGS","REFERRAL",1,null,value,null);
        return settings();
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> enroll(long userId) {
        lockUser(userId);
        verified(userId);
        if (!flag(settings(),"enabled")) throw conflict("邀请活动尚未开启");
        if (first("SELECT user_id FROM agent_profiles WHERE user_id=?",userId)==null) {
            jdbc.update("INSERT INTO agent_profiles(user_id,tier_code,invite_code,status,requested_rebate_bps) VALUES (?,'BASIC',?,'INVITER',0)",
                    userId,"RF"+UUID.randomUUID().toString().replace("-","").substring(0,20));
        }
        if (first("SELECT user_id FROM referral_members WHERE user_id=?",userId)==null)
            jdbc.update("INSERT INTO referral_members(user_id,status) VALUES (?,'ACTIVE')",userId);
        return summary(userId);
    }

    public boolean validInvite(String code) {
        if (code==null || code.isBlank() || code.length()>32) return false;
        return first("""
                SELECT p.user_id FROM agent_profiles p JOIN users u ON u.id=p.user_id
                LEFT JOIN referral_members m ON m.user_id=p.user_id
                WHERE UPPER(p.invite_code)=UPPER(?) AND u.status='ACTIVE'
                AND (m.status IS NULL OR m.status='ACTIVE')
                AND (p.status='ACTIVE' OR (m.status='ACTIVE' AND u.email_verified_at IS NOT NULL))
                """,code.trim())!=null;
    }

    /** Only called during registration. Invalid links must not prevent account creation. */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean bindInvite(long userId, String code, String source) {
        if (!flag(settings(),"enabled") || !validInvite(code)) return false;
        lockUser(userId);
        Map<String,Object> inviter=first("SELECT user_id FROM agent_profiles WHERE UPPER(invite_code)=UPPER(?)",code.trim());
        long inviterId=number(inviter,"user_id");
        if (inviterId==userId) return false;
        if (first("SELECT id FROM agent_customer_bindings WHERE customer_user_id=?",userId)!=null) return true;
        jdbc.update("INSERT INTO agent_customer_bindings(customer_user_id,agent_user_id,invite_code,customer_rebate_bps,binding_source) VALUES (?,?,?,0,?)",
                userId,inviterId,code.trim(),source);
        return true;
    }

    public static long calculate(long sale, long cost, int quantity, Rule rule) {
        if (sale<0 || cost<0 || quantity<1) throw new IllegalArgumentException("Invalid money inputs");
        return Math.min(configuredCommission(sale,quantity,rule),Math.max(0,sale-cost)/2);
    }
    private static long configuredCommission(long sale, int quantity, Rule rule) {
        BigDecimal configured="FIXED".equals(rule.mode())
                ? BigDecimal.valueOf(rule.fixedCents()).multiply(BigDecimal.valueOf(quantity))
                : BigDecimal.valueOf(sale).multiply(BigDecimal.valueOf(rule.rateBps())).divide(BigDecimal.valueOf(10000),0,RoundingMode.DOWN);
        return configured.longValueExact();
    }
    private void validateRule(Rule rule) {
        if (rule==null || rule.mode()==null || !Set.of("FIXED","PERCENT").contains(rule.mode()) || rule.fixedCents()<0 || rule.fixedCents()>100000000
                || rule.rateBps()<0 || rule.rateBps()>10000 || rule.costCents()<0 || rule.costCents()>100000000)
            throw invalid("返佣规则无效");
        if (rule.displayText().length()>500) throw invalid("返佣展示文案最多500字");
    }
    private Rule rule(Map<String,Object> row) {
        return new Rule(flag(row,"enabled"),String.valueOf(row.get("mode")),number(row,"fixed_cents"),(int)number(row,"rate_bps"),number(row,"cost_cents"),(String)row.get("display_text"));
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> preview(User admin,long serviceId,RulePreview request) {
        validateRule(request.rule());
        if (request.quantity()<1 || request.quantity()>10000 || request.merchandiseCents()<0 || request.merchandiseCents()>10000000000L)
            throw invalid("预览金额或数量无效");
        if (first("SELECT id FROM other_services WHERE id=?",serviceId)==null) throw invalid("商品不存在");
        Map<String,Object> existing=first("SELECT * FROM referral_rules WHERE service_id=?",serviceId);
        if ((existing==null?0:number(existing,"version"))!=request.version()) throw conflict("规则已更新，请刷新");
        String token=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO referral_previews(token,admin_id,service_id,version,payload,expires_at) VALUES (?,?,?,?,?,?)",
                token,admin.getId(),serviceId,request.version(),encode(request.rule()),now().plusMinutes(15));
        long cost=Math.multiplyExact(request.rule().costCents(),request.quantity());
        long gross=request.merchandiseCents()-cost;
        long commission = !request.rule().enabled() ? 0 : "FIXED".equals(request.rule().mode())
                ? configuredCommission(request.merchandiseCents(),request.quantity(),request.rule())
                : calculate(request.merchandiseCents(),cost,request.quantity(),request.rule());
        return Map.of("token",token,"commissionCents",commission,
                "grossCents",gross,"version",request.version(),"merchandiseCents",request.merchandiseCents(),
                "totalCostCents",cost,"configuredCommissionCents",configuredCommission(request.merchandiseCents(),request.quantity(),request.rule()),
                "profitCapCents",Math.max(0,gross)/2,"enabled",request.rule().enabled());
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void applyRule(User admin,long serviceId,String token) {
        Map<String,Object> p=first("SELECT * FROM referral_previews WHERE token=? AND admin_id=? AND service_id=? AND expires_at>? FOR UPDATE",
                required(token,64),admin.getId(),serviceId,now());
        if (p==null) throw conflict("预览已过期，请重新预览");
        jdbc.queryForList("SELECT id FROM other_services WHERE id=? FOR UPDATE",serviceId);
        Map<String,Object> existing=first("SELECT * FROM referral_rules WHERE service_id=?",serviceId);
        if ((existing==null?0:number(existing,"version"))!=number(p,"version")) throw conflict("规则已更新，请重新预览");
        Rule r;
        try { r=json.readValue(String.valueOf(p.get("payload")),Rule.class); } catch(Exception e) { throw new IllegalStateException(e); }
        if (existing==null) jdbc.update("INSERT INTO referral_rules(service_id,enabled,mode,fixed_cents,rate_bps,cost_cents,display_text) VALUES (?,?,?,?,?,?,?)",
                serviceId,r.enabled(),r.mode(),r.fixedCents(),r.rateBps(),r.costCents(),r.displayText());
        else jdbc.update("UPDATE referral_rules SET enabled=?,mode=?,fixed_cents=?,rate_bps=?,cost_cents=?,display_text=?,version=version+1,updated_at=? WHERE service_id=?",
                r.enabled(),r.mode(),r.fixedCents(),r.rateBps(),r.costCents(),r.displayText(),now(),serviceId);
        jdbc.update("DELETE FROM referral_previews WHERE token=?",token);
        audit.record(admin,"REFERRAL_RULE","SERVICE",serviceId,existing,r,null);
    }

    /** The order transaction snapshots ineligible orders too, excluding them from legacy settlement. */
    public void snapshot(ServiceOrder order, Long resaleAgent, Long resaleBaseCents) {
        Map<String,Object> binding=first("SELECT agent_user_id FROM agent_customer_bindings WHERE customer_user_id=?",order.getUserId());
        Long inviter=binding==null?null:number(binding,"agent_user_id");
        Map<String,Object> r=first("SELECT * FROM referral_rules WHERE service_id=?",order.getServiceId());
        Map<String,Object> config=settings();
        boolean agentEligible=agentsEnabled && inviter!=null && !inviter.equals(order.getUserId())
                && r!=null && flag(r,"enabled") && "FIXED".equals(r.get("mode"))
                && first("""
                    SELECT p.user_id FROM agent_profiles p JOIN agent_tiers t ON t.tier_code=p.tier_code AND t.enabled=TRUE
                    JOIN users u ON u.id=p.user_id AND u.status='ACTIVE'
                    WHERE p.user_id=? AND p.status='ACTIVE'
                    """, inviter)!=null;
        boolean memberEligible=inviter!=null && !inviter.equals(order.getUserId()) && flag(config,"enabled")
                && r!=null && flag(r,"enabled") && first("""
                    SELECT m.user_id FROM referral_members m JOIN users u ON u.id=m.user_id
                    WHERE m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
                    """,inviter)!=null;
        boolean eligible=agentEligible || memberEligible;
        long sale=BigDecimal.valueOf(Math.max(0,order.getAmountCents()-order.getServiceFeeCents()))
                .multiply(order.getExchangeRate()).setScale(0,RoundingMode.DOWN).longValueExact();
        Rule rule=r==null?new Rule(false,"FIXED",0,0,0):rule(r);
        long cost=Math.multiplyExact(rule.costCents(),order.getQuantity());
        long amount=!eligible?0:agentEligible || "FIXED".equals(rule.mode())
                ? configuredCommission(sale,order.getQuantity(),rule):calculate(sale,cost,order.getQuantity(),rule);
        long resale = 0;
        if (resaleAgent != null) {
            long base = Math.multiplyExact(resaleBaseCents, order.getQuantity());
            long netMerchandise = order.getAmountCents() - order.getServiceFeeCents();
            resale = BigDecimal.valueOf(Math.max(0, netMerchandise - base))
                    .multiply(order.getExchangeRate()).setScale(0, RoundingMode.DOWN).longValueExact();
        }
        jdbc.update("""
                INSERT INTO referral_orders(order_id,inviter_id,customer_id,rule_version,mode,fixed_cents,rate_bps,sale_cents,cost_cents,commission_cents,freeze_days,status,
                    resale_agent_id,resale_base_cents,resale_cents)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,order.getId(),inviter,order.getUserId(),r==null?0:number(r,"version"),agentEligible?AGENT_FIXED:rule.mode(),rule.fixedCents(),agentEligible?0:rule.rateBps(),sale,cost,amount,
                Math.max(0, agentEligible?agentFreezeDays:number(config,"freeze_days")),amount>0 || resale>0?"AWAITING_PAYMENT":"INELIGIBLE",
                resaleAgent,resaleBaseCents,resale);
        order.setReferralCommissionCents(eligible?amount:null);
    }

    private long units(long cents) {
        if (amountScale<100 || amountScale%100!=0) throw new IllegalStateException("Unsupported commission scale");
        return Math.multiplyExact(cents,amountScale/100);
    }
    private void entry(String key,long inviter,long customer,long cents,String type) {
        entry(key,inviter,customer,cents,type,"订阅邀请返佣");
    }

    private void entry(String key,long inviter,long customer,long cents,String type,String note) {
        if (first("SELECT id FROM agent_commission_events WHERE business_event_id=?",key)!=null) return;
        jdbc.update("""
                INSERT INTO agent_commission_events(business_event_id,business_type,customer_user_id,agent_user_id,sale_amount,cost_amount,gross_profit,commission_pool,customer_rebate_amount,agent_commission_amount,amount_scale,status)
                VALUES (?,'REFERRAL',?,?,0,0,0,0,0,?,?,'SETTLED')
                """,key,customer,inviter,units(cents),amountScale);
        long id=jdbc.queryForObject("SELECT id FROM agent_commission_events WHERE business_event_id=?",Long.class,key);
        jdbc.update("""
                INSERT INTO agent_commission_ledger(event_id,business_event_id,beneficiary_user_id,agent_user_id,customer_user_id,entry_type,amount,status,note)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',?)
                """,id,key,inviter,inviter,customer,type,units(cents),note);
    }

    private void lockOrderBeneficiaries(Map<String,Object> row) {
        java.util.stream.Stream.of(row.get("inviter_id"),row.get("resale_agent_id"))
                .filter(Objects::nonNull).map(value -> ((Number)value).longValue())
                .distinct().sorted().forEach(this::lockUser);
    }
    private long rawBalance(long userId) {
        return sum("""
                SELECT COALESCE(SUM(l.amount),0) FROM agent_commission_ledger l
                WHERE beneficiary_user_id=? AND entry_type IN ('REFERRAL_CREDIT','REFERRAL_REVERSAL','REFERRAL_HOLD','REFERRAL_RELEASE')
                """,userId)/(amountScale/100);
    }
    @Scheduled(fixedDelayString="${referral.scan-ms:60000}",initialDelayString="${referral.scan-initial-ms:60000}")
    public void settleDue() {
        awardFirstPurchaseCoupons();
        // Read fresh ledger entries after waiting for the user lock on MySQL.
        TransactionTemplate settlementTransaction=new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        settlementTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        long cursor=0;
        List<Long> batch;
        do {
            batch=jdbc.queryForList("""
                    SELECT r.order_id FROM referral_orders r JOIN service_orders o ON o.id=r.order_id
                    WHERE r.order_id>? AND r.status IN ('AWAITING_PAYMENT','AWAITING_DELIVERY','FROZEN','VOID')
                    AND (o.status IN ('PAID','FULFILLED') OR (r.status<>'VOID' AND o.status IN ('FAILED','CANCELLED','EXPIRED')))
                    ORDER BY r.order_id LIMIT 100
                    """,Long.class,cursor);
            for(long id:batch) {
                try { settlementTransaction.executeWithoutResult(tx -> settleOne(id)); }
                catch (RuntimeException failure) { log.warn("Referral settlement failed for order {}; retrying on next scan",id); }
                cursor=id;
            }
        } while(batch.size()==100);
    }

    void awardFirstPurchaseCoupons() {
        Map<String,Object> config = settings();
        long cents = number(config,"first_purchase_coupon_cents");
        if (!flag(config,"enabled") || cents == 0) return;
        long cursor = 0;
        List<Map<String,Object>> batch;
        do {
            batch = jdbc.queryForList("""
                    SELECT o.id,o.user_id,b.agent_user_id
                    FROM service_orders o
                    JOIN agent_customer_bindings b ON b.customer_user_id=o.user_id
                    JOIN users u ON u.id=b.agent_user_id AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
                    JOIN agent_profiles p ON p.user_id=b.agent_user_id
                    LEFT JOIN referral_members m ON m.user_id=b.agent_user_id
                    WHERE o.id>? AND o.service_id IS NOT NULL AND o.status='FULFILLED'
                      AND o.fulfillment_status='COMPLETED' AND o.paid_at>=?
                      AND b.agent_user_id<>o.user_id
                      AND (m.status='ACTIVE' OR (m.status IS NULL AND p.status='ACTIVE'))
                      AND COALESCE((SELECT SUM(f.amount_cents) FROM referral_refunds f WHERE f.order_id=o.id),0)
                          <o.payment_amount_cents
                      AND NOT EXISTS (SELECT 1 FROM service_coupons c WHERE c.rewarded_customer_id=o.user_id)
                      AND NOT EXISTS (
                          SELECT 1 FROM service_orders earlier
                          WHERE earlier.user_id=o.user_id AND earlier.service_id IS NOT NULL
                            AND earlier.status IN ('PAID','FULFILLED')
                            AND (earlier.paid_at<o.paid_at OR (earlier.paid_at=o.paid_at AND earlier.id<o.id))
                            AND COALESCE((SELECT SUM(f.amount_cents) FROM referral_refunds f WHERE f.order_id=earlier.id),0)
                                <COALESCE(earlier.payment_amount_cents,earlier.amount_cents)
                      )
                    ORDER BY o.id LIMIT 100
                    """, cursor, config.get("coupon_started_at"));
            for (Map<String,Object> row : batch) {
                cursor = number(row,"id");
                try {
                    coupons.grantPersonal(number(row,"agent_user_id"), cents, number(row,"user_id"));
                } catch (org.springframework.dao.DuplicateKeyException concurrentAward) {
                    // The unique customer key prevents duplicate rewards across workers and retries.
                } catch (RuntimeException failure) {
                    log.warn("First purchase coupon failed for order {}; retrying on next scan", cursor);
                }
            }
        } while (batch.size() == 100);
    }
    void settleOne(long id) {
        Map<String,Object> initial=first("SELECT inviter_id,resale_agent_id FROM referral_orders WHERE order_id=?",id);
        if (initial==null) return;
        lockOrderBeneficiaries(initial);
        Map<String,Object> r=jdbc.queryForMap("SELECT * FROM referral_orders WHERE order_id=? FOR UPDATE",id);
        if (!Set.of("AWAITING_PAYMENT","AWAITING_DELIVERY","FROZEN","VOID").contains(r.get("status"))) return;
        Map<String,Object> order=first("SELECT status,fulfilled_at,order_no FROM service_orders WHERE id=?",id);
        if(order==null || Set.of("FAILED","CANCELLED","EXPIRED").contains(order.get("status"))) {
            jdbc.update("UPDATE referral_orders SET status='VOID' WHERE order_id=?",id); return;
        }
        // Cancelled or expired checkouts may still receive a valid late payment.
        if (!Set.of("PAID","FULFILLED").contains(order.get("status"))) return;
        if (!"FULFILLED".equals(order.get("status")) || order.get("fulfilled_at")==null) {
            jdbc.update("UPDATE referral_orders SET status='AWAITING_DELIVERY' WHERE order_id=?",id); return;
        }
        LocalDateTime due=r.get("available_at")==null?timestamp(order.get("fulfilled_at")).plusDays(number(r,"freeze_days")):timestamp(r.get("available_at"));
        if(due.isAfter(now())) {
            jdbc.update("UPDATE referral_orders SET status='FROZEN',available_at=? WHERE order_id=?",due,id); return;
        }
        long net=number(r,"commission_cents")-number(r,"reversed_cents");
        if (net>0) entry("REFERRAL_ORDER:"+id,number(r,"inviter_id"),number(r,"customer_id"),net,
                AGENT_FIXED.equals(r.get("mode"))?"AGENT_COMMISSION":"REFERRAL_CREDIT");
        long resaleNet=number(r,"resale_cents")-number(r,"resale_reversed_cents");
        if (resaleNet>0) entry("SUBSCRIPTION_RESALE:"+id,number(r,"resale_agent_id"),number(r,"customer_id"),
                resaleNet,"AGENT_RESALE","订阅差价 · "+order.get("order_no"));
        jdbc.update("UPDATE referral_orders SET status=?,available_at=? WHERE order_id=?",net>0 || resaleNet>0?"AVAILABLE":"REVERSED",due,id);
    }
    public Map<String,Object> summary(long userId) {
        Map<String,Object> result=new LinkedHashMap<>(settings());
        Map<String,Object> p=first("SELECT p.invite_code,COALESCE(m.status,'ACTIVE') status FROM agent_profiles p LEFT JOIN referral_members m ON m.user_id=p.user_id WHERE p.user_id=? AND (p.status='ACTIVE' OR m.user_id IS NOT NULL)",userId);
        result.put("profile",p==null?Map.of():p);
        result.put("verified",first("SELECT id FROM users WHERE id=? AND email_verified_at IS NOT NULL",userId)!=null);
        result.put("invited",sum("SELECT COUNT(*) FROM agent_customer_bindings WHERE agent_user_id=?",userId));
        result.put("earnedCents",sum("SELECT COALESCE(SUM(r.commission_cents-r.reversed_cents),0) FROM referral_orders r JOIN service_orders o ON o.id=r.order_id WHERE r.inviter_id=? AND r.mode<>'AGENT_FIXED' AND o.status IN ('PAID','FULFILLED')",userId));
        result.put("pendingCents",sum("SELECT COALESCE(SUM(r.commission_cents-r.reversed_cents),0) FROM referral_orders r JOIN service_orders o ON o.id=r.order_id WHERE r.inviter_id=? AND r.mode<>'AGENT_FIXED' AND r.status IN ('AWAITING_PAYMENT','AWAITING_DELIVERY','FROZEN') AND o.status IN ('PAID','FULFILLED')",userId));
        long balance=rawBalance(userId);
        result.put("availableCents",Math.max(0,balance));
        result.put("debtCents",Math.max(0,-balance));
        result.put("withdrawingCents",sum("SELECT COALESCE(SUM(amount_cents),0) FROM referral_withdrawals WHERE user_id=? AND status IN ('PENDING','APPROVED','PAYING')",userId));
        result.put("paidCents",sum("SELECT COALESCE(SUM(amount_cents),0) FROM referral_withdrawals WHERE user_id=? AND status='PAID'",userId));
        return result;
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void saveAccount(long userId,Account value) {
        lockUser(userId); verified(userId);
        if(value.channel()==null || !Set.of("ALIPAY","WECHAT").contains(value.channel())) throw invalid("请选择支付宝或微信");
        Account account=new Account(value.channel(),required(value.account(),160),required(value.name(),80));
        String hash=hashes.hash(account.channel()+":"+account.account().toLowerCase(Locale.ROOT));
        String encrypted=secrets.encryptForPurpose("referral-payout",encode(account));
        jdbc.update("DELETE FROM referral_accounts WHERE user_id=?",userId);
        jdbc.update("INSERT INTO referral_accounts(user_id,channel,encrypted_details,account_hash,masked_account) VALUES (?,?,?,?,?)",
                userId,account.channel(),encrypted,hash,mask(account.account()));
    }
    public Map<String,Object> account(long userId) {
        Map<String,Object> account=first("SELECT channel,masked_account,encrypted_details FROM referral_accounts WHERE user_id=?",userId);
        if(account==null) return Map.of();
        account.put("accountToken",hashes.hash(String.valueOf(account.remove("encrypted_details"))));
        return account;
    }
    private String mask(String value) {
        if(value==null || value.isBlank()) return "";
        int at=value.indexOf('@');
        if(at>=0) return value.substring(0,1)+"***"+value.substring(at);
        return value.substring(0,1)+"***"+(value.length()>4?value.substring(value.length()-2):"");
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> withdraw(long userId,Withdrawal request) {
        lockUser(userId); verified(userId);
        String key=required(request.requestKey(),80);
        Map<String,Object> existing=first("SELECT id,amount_cents,status,encrypted_details FROM referral_withdrawals WHERE user_id=? AND request_key=?",userId,key);
        if(existing!=null) {
            if(number(existing,"amount_cents")!=request.amountCents()
                    || !hashes.hash(String.valueOf(existing.remove("encrypted_details"))).equals(request.accountToken()))
                throw conflict("重复请求金额或收款信息不一致");
            return existing;
        }
        long minimum=number(settings(),"minimum_cents");
        if(request.amountCents()<minimum || request.amountCents()>rawBalance(userId)) throw invalid("提现金额未达到门槛或可提现余额不足");
        Map<String,Object> account=first("SELECT * FROM referral_accounts WHERE user_id=?",userId);
        if(account==null) throw invalid("请先保存收款信息");
        if(!hashes.hash(String.valueOf(account.get("encrypted_details"))).equals(request.accountToken()))
            throw conflict("收款信息已更新，请重新确认");
        jdbc.update("INSERT INTO referral_withdrawals(user_id,request_key,amount_cents,channel,encrypted_details,account_hash,masked_account) VALUES (?,?,?,?,?,?,?)",
                userId,key,request.amountCents(),account.get("channel"),account.get("encrypted_details"),account.get("account_hash"),account.get("masked_account"));
        long id=jdbc.queryForObject("SELECT id FROM referral_withdrawals WHERE user_id=? AND request_key=?",Long.class,userId,key);
        entry("REFERRAL_WITHDRAW:"+id,userId,userId,-request.amountCents(),"REFERRAL_HOLD");
        return Map.of("id",id,"status","PENDING");
    }
    private Map<String,Object> lockedWithdrawal(long id) {
        Map<String,Object> row=first("SELECT user_id FROM referral_withdrawals WHERE id=?",id);
        if(row==null) throw invalid("提现申请不存在");
        lockUser(number(row,"user_id"));
        return jdbc.queryForMap("SELECT * FROM referral_withdrawals WHERE id=? FOR UPDATE",id);
    }
    private void release(Map<String,Object> row,String status,String note) {
        long id=number(row,"id"),user=number(row,"user_id");
        entry("REFERRAL_RELEASE:"+id,user,user,number(row,"amount_cents"),"REFERRAL_RELEASE");
        jdbc.update("UPDATE referral_withdrawals SET status=?,note=?,updated_at=? WHERE id=?",status,note,now(),id);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cancel(long userId,long id) {
        Map<String,Object> row=lockedWithdrawal(id);
        if(number(row,"user_id")!=userId) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"提现申请不存在");
        if("CANCELLED".equals(row.get("status"))) return;
        if(!"PENDING".equals(row.get("status"))) throw conflict("仅待审核申请可以取消");
        release(row,"CANCELLED","用户取消");
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void review(User admin,Review request) {
        if(request.ids()==null || request.ids().isEmpty() || request.ids().size()>100 || request.ids().stream().anyMatch(Objects::isNull))
            throw invalid("请选择 1 至 100 笔申请");
        if(request.action()==null || !Set.of("APPROVED","REJECTED").contains(request.action())) throw invalid("审核操作无效");
        String note="REJECTED".equals(request.action())?required(request.note(),500):"审核通过";
        // All financial operations acquire the user lock before withdrawal locks.
        List<Long> users=jdbc.queryForList("SELECT DISTINCT user_id FROM referral_withdrawals WHERE id IN ("+
                String.join(",",Collections.nCopies(request.ids().size(),"?"))+") ORDER BY user_id",Long.class,request.ids().toArray());
        users.forEach(this::lockUser);
        for(long id:request.ids().stream().distinct().sorted().toList()) {
            Map<String,Object> row=lockedWithdrawal(id);
            if(request.action().equals(row.get("status"))) continue;
            if(!"PENDING".equals(row.get("status")) && !("REJECTED".equals(request.action()) && "APPROVED".equals(row.get("status"))))
                throw conflict("申请状态已变化，请刷新");
            if("REJECTED".equals(request.action())) release(row,"REJECTED",note);
            else {
                if(rawBalance(number(row,"user_id"))<0) throw conflict("账户存在待抵扣佣金");
                jdbc.update("UPDATE referral_withdrawals SET status='APPROVED',note=?,reviewed_by=?,updated_at=? WHERE id=?",note,admin.getId(),now(),id);
            }
            audit.record(admin,"REFERRAL_"+request.action(),"WITHDRAWAL",id,null,Map.of("note",note),null);
        }
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void claim(User admin,long id) {
        Map<String,Object> row=lockedWithdrawal(id);
        if("PAYING".equals(row.get("status")) && number(row,"claimed_by")==admin.getId()) return;
        if(!"APPROVED".equals(row.get("status"))) throw conflict("仅待打款申请可以领取");
        if(rawBalance(number(row,"user_id"))<0) throw conflict("账户存在待抵扣佣金");
        jdbc.update("UPDATE referral_withdrawals SET status='PAYING',claimed_by=?,updated_at=? WHERE id=?",admin.getId(),now(),id);
        audit.record(admin,"REFERRAL_CLAIM","WITHDRAWAL",id,null,null,null);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Account reveal(User admin,long id) {
        Map<String,Object> row=lockedWithdrawal(id);
        if(!"PAYING".equals(row.get("status")) || number(row,"claimed_by")!=admin.getId())
            throw conflict("请先领取此打款任务");
        audit.record(admin,"REFERRAL_REVEAL","WITHDRAWAL",id,null,null,null);
        try { return json.readValue(secrets.decryptForPurpose("referral-payout",String.valueOf(row.get("encrypted_details"))),Account.class); }
        catch(Exception e) { throw new IllegalStateException("Unable to read payout details",e); }
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void paid(User admin,long id,Payment request) {
        Map<String,Object> row=lockedWithdrawal(id);
        String reference=required(request.reference(),160);
        if("PAID".equals(row.get("status")) && reference.equals(row.get("payment_reference")) && request.amountCents()==number(row,"amount_cents")) return;
        if(!"PAYING".equals(row.get("status")) || number(row,"claimed_by")!=admin.getId()) throw conflict("请先领取此打款任务");
        if(request.amountCents()!=number(row,"amount_cents") || request.paidAt()==null || request.paidAt().isAfter(now().plusMinutes(5)))
            throw invalid("打款金额或时间无效");
        if(first("SELECT id FROM referral_withdrawals WHERE channel=? AND payment_reference=?",row.get("channel"),reference)!=null)
            throw conflict("该渠道交易号已登记");
        jdbc.update("UPDATE referral_withdrawals SET status='PAID',payment_reference=?,paid_at=?,updated_at=? WHERE id=?",reference,request.paidAt(),now(),id);
        audit.record(admin,"REFERRAL_PAID","WITHDRAWAL",id,null,request,null);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void paymentFailed(User admin,long id,String note) {
        Map<String,Object> row=lockedWithdrawal(id);
        if(!"PAYING".equals(row.get("status")) || number(row,"claimed_by")!=admin.getId()) throw conflict("只能处理本人领取的打款任务");
        release(row,"REJECTED",required(note,500));
        audit.record(admin,"REFERRAL_TRANSFER_FAILED","WITHDRAWAL",id,null,note,null);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void refund(User admin,long orderId,Refund request) {
        String reference=required(request.reference(),160);
        Map<String,Object> initial=first("SELECT inviter_id,resale_agent_id FROM referral_orders WHERE order_id=?",orderId);
        if(initial!=null) lockOrderBeneficiaries(initial);
        Map<String,Object> order=first("SELECT * FROM service_orders WHERE id=? FOR UPDATE",orderId);
        if(order==null || !Set.of("PAID","FULFILLED").contains(order.get("status"))) throw conflict("只能登记已付款订单的退款");
        Map<String,Object> previous=first("SELECT * FROM referral_refunds WHERE reference=?",reference);
        if(previous!=null) {
            if(number(previous,"order_id")!=orderId || number(previous,"amount_cents")!=request.amountCents()
                    || !Objects.equals(number(previous,"merchandise_cents"),request.merchandiseCents())) throw conflict("退款凭证已用于其他记录");
            return;
        }
        long prior=sum("SELECT COALESCE(SUM(amount_cents),0) FROM referral_refunds WHERE order_id=?",orderId);
        long total=Math.addExact(prior,request.amountCents());
        long paid=number(order,"payment_amount_cents");
        if(request.amountCents()<=0 || total>paid || request.refundedAt()==null || request.refundedAt().isAfter(now().plusMinutes(5))) throw invalid("退款金额或时间无效");
        long sale=initial==null
                ? BigDecimal.valueOf(number(order,"amount_cents")-number(order,"service_fee_cents"))
                    .multiply(new BigDecimal(String.valueOf(order.get("exchange_rate")))).setScale(0,RoundingMode.DOWN).longValueExact()
                : sum("SELECT sale_cents FROM referral_orders WHERE order_id=?",orderId);
        long priorMerchandise=sum("SELECT COALESCE(SUM(merchandise_cents),0) FROM referral_refunds WHERE order_id=?",orderId);
        if(request.merchandiseCents()==null || request.merchandiseCents()<0 || request.merchandiseCents()>request.amountCents()
                || request.merchandiseCents()>sale-priorMerchandise) throw invalid("商品退款金额无效");
        long merchandiseTotal=priorMerchandise+request.merchandiseCents();
        if(total-merchandiseTotal>paid-sale || (total==paid && merchandiseTotal!=sale))
            throw invalid("商品与服务费退款金额不匹配");
        jdbc.update("INSERT INTO referral_refunds(order_id,amount_cents,merchandise_cents,reference,refunded_at,admin_id) VALUES (?,?,?,?,?,?)",
                orderId,request.amountCents(),request.merchandiseCents(),reference,request.refundedAt(),admin.getId());
        if(initial!=null) {
            Map<String,Object> r=jdbc.queryForMap("SELECT * FROM referral_orders WHERE order_id=? FOR UPDATE",orderId);
            // Only the explicitly refunded merchandise participates in reversal, never fees or payment adjustments.
            boolean agentOrder=AGENT_FIXED.equals(r.get("mode"));
            long reversed=total==paid ? number(r,"commission_cents")
                    : sale==0?0:BigDecimal.valueOf(number(r,"commission_cents")).multiply(BigDecimal.valueOf(merchandiseTotal))
                    .divide(BigDecimal.valueOf(sale),0,RoundingMode.DOWN).longValueExact();
            long delta=reversed-number(r,"reversed_cents");
            long resale = number(r,"resale_cents");
            long resaleReversed = total==paid ? resale : sale==0 ? 0 : BigDecimal.valueOf(resale)
                    .multiply(BigDecimal.valueOf(merchandiseTotal)).divide(BigDecimal.valueOf(sale),0,RoundingMode.DOWN).longValueExact();
            long resaleDelta = resaleReversed-number(r,"resale_reversed_cents");
            jdbc.update("UPDATE referral_orders SET refunded_cents=?,reversed_cents=?,resale_reversed_cents=? WHERE order_id=?",
                    total,reversed,resaleReversed,orderId);
            if(resaleDelta>0 && "AVAILABLE".equals(r.get("status"))) {
                long refundId=jdbc.queryForObject("SELECT id FROM referral_refunds WHERE reference=?",Long.class,reference);
                entry("SUBSCRIPTION_RESALE_REFUND:"+refundId,number(r,"resale_agent_id"),number(r,"customer_id"),
                        -resaleDelta,"AGENT_RESALE_REVERSAL","订阅差价退款冲回 · "+order.get("order_no"));
            }
            if("AVAILABLE".equals(r.get("status")) && reversed==number(r,"commission_cents") && resaleReversed==resale) {
                jdbc.update("UPDATE referral_orders SET status='REVERSED' WHERE order_id=?",orderId);
            }
            if(delta>0 && Set.of("AVAILABLE","REVERSED").contains(r.get("status"))) {
                long inviter=number(r,"inviter_id");
                entry("REFERRAL_REFUND:"+jdbc.queryForObject("SELECT id FROM referral_refunds WHERE reference=?",Long.class,reference),
                        inviter,number(r,"customer_id"),-delta,agentOrder?"AGENT_COMMISSION_REVERSAL":"REFERRAL_REVERSAL");
                if(!agentOrder && rawBalance(inviter)<0) {
                    for(Map<String,Object> w:jdbc.queryForList("SELECT * FROM referral_withdrawals WHERE user_id=? AND status IN ('PENDING','APPROVED') ORDER BY id FOR UPDATE",inviter)) {
                        release(w,"CANCELLED","关联订单退款，提现已取消，请核对余额后重新申请");
                        if(rawBalance(inviter)>=0) break;
                    }
                }
            }
        }
        audit.record(admin,"REFERRAL_REFUND","SERVICE_ORDER",orderId,null,request,null);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void memberStatus(User admin,long userId,String status) {
        if(status==null || !Set.of("ACTIVE","SUSPENDED").contains(status)) throw invalid("推广状态无效");
        lockUser(userId);
        if(first("SELECT user_id FROM referral_members WHERE user_id=?",userId)==null)
            jdbc.update("INSERT INTO referral_members(user_id,status) VALUES (?,?)",userId,status);
        else jdbc.update("UPDATE referral_members SET status=? WHERE user_id=?",status,userId);
        audit.record(admin,"REFERRAL_MEMBER","USER",userId,null,status,null);
    }

    public Map<String,Object> overview() {
        return Map.of("invited",sum("SELECT COUNT(*) FROM agent_customer_bindings"),
                "salesCents",sum("SELECT COALESCE(SUM(r.sale_cents-COALESCE((SELECT SUM(f.merchandise_cents) FROM referral_refunds f WHERE f.order_id=r.order_id),0)),0) FROM referral_orders r JOIN service_orders o ON o.id=r.order_id WHERE o.status IN ('PAID','FULFILLED') AND r.status<>'INELIGIBLE'"),
                "commissionCents",sum("SELECT COALESCE(SUM(r.commission_cents-r.reversed_cents),0) FROM referral_orders r JOIN service_orders o ON o.id=r.order_id WHERE o.status IN ('PAID','FULFILLED')"),
                "frozenCents",sum("SELECT COALESCE(SUM(r.commission_cents-r.reversed_cents),0) FROM referral_orders r JOIN service_orders o ON o.id=r.order_id WHERE r.status IN ('AWAITING_PAYMENT','AWAITING_DELIVERY','FROZEN') AND o.status IN ('PAID','FULFILLED')"),
                "availableCents",sum("SELECT COALESCE(SUM(CASE WHEN balance>0 THEN balance ELSE 0 END),0) FROM (SELECT SUM(amount) balance FROM agent_commission_ledger WHERE entry_type IN ('REFERRAL_CREDIT','REFERRAL_REVERSAL','REFERRAL_HOLD','REFERRAL_RELEASE') GROUP BY beneficiary_user_id) balances")/(amountScale/100),
                "pendingPayoutCents",sum("SELECT COALESCE(SUM(amount_cents),0) FROM referral_withdrawals WHERE status='PENDING'"),
                "approvedPayoutCents",sum("SELECT COALESCE(SUM(amount_cents),0) FROM referral_withdrawals WHERE status='APPROVED'"),
                "payingCents",sum("SELECT COALESCE(SUM(amount_cents),0) FROM referral_withdrawals WHERE status='PAYING'"),
                "paidCents",sum("SELECT COALESCE(SUM(amount_cents),0) FROM referral_withdrawals WHERE status='PAID'"));
    }

    public PageResponse<Map<String,Object>> list(String kind,Long owner,int page,int size,String query,String status,String from,String to) {
        if(page<1 || page>1000000 || !List.of(10,20,50,100).contains(size)) throw invalid("分页参数无效");
        List<Object> args=new ArrayList<>();
        String select,table,where=" WHERE 1=1",sort;
        switch(kind) {
            case "products" -> {
                select="s.id,s.name,s.price_cents,s.currency,COALESCE(r.enabled,FALSE) enabled,r.mode,r.fixed_cents,r.rate_bps,"
                        +(owner==null?"r.cost_cents,":"")+"r.display_text,COALESCE(r.version,0) version";
                table="other_services s LEFT JOIN referral_rules r ON r.service_id=s.id";
                sort="s.id DESC";
                if(owner!=null) where+=" AND s.enabled=TRUE AND r.enabled=TRUE AND EXISTS (SELECT 1 FROM referral_settings WHERE id=1 AND enabled=TRUE)";
                if(query!=null && !query.isBlank()) { where+=" AND s.name LIKE ?"; args.add("%"+query.trim()+"%"); }
            }
            case "invitations" -> {
                select="b.id,b.agent_user_id,b.customer_user_id,a.email inviter_email,u.email customer_email,u.created_at,b.bound_at,b.binding_source,COALESCE(m.status,'ACTIVE') status,(SELECT COUNT(*) FROM service_orders o WHERE o.user_id=b.customer_user_id AND o.status IN ('PAID','FULFILLED') AND COALESCE((SELECT SUM(f.amount_cents) FROM referral_refunds f WHERE f.order_id=o.id),0)<COALESCE(o.payment_amount_cents,o.amount_cents)) purchases";
                table="agent_customer_bindings b JOIN users a ON a.id=b.agent_user_id JOIN users u ON u.id=b.customer_user_id LEFT JOIN referral_members m ON m.user_id=b.agent_user_id";
                sort="b.id DESC";
                if(owner!=null) {where+=" AND b.agent_user_id=?";args.add(owner);}
                if(query!=null && !query.isBlank()) {where+=" AND (a.email LIKE ? OR u.email LIKE ?)";args.add("%"+query.trim()+"%");args.add("%"+query.trim()+"%");}
            }
            case "commissions" -> {
                select="r.*,o.order_no,o.product_name,o.quantity,o.status order_status,u.email inviter_email";
                table="referral_orders r JOIN service_orders o ON o.id=r.order_id JOIN users u ON u.id=r.inviter_id";
                where+=" AND r.status<>'INELIGIBLE'";
                sort="r.order_id DESC";
                if(owner!=null) {where+=" AND r.inviter_id=? AND r.mode<>'AGENT_FIXED'";args.add(owner);}
                if(status!=null && !status.isBlank()) {where+=" AND r.status=?";args.add(status);}
                if(query!=null && !query.isBlank()) {where+=" AND (u.email LIKE ? OR o.order_no LIKE ? OR o.product_name LIKE ?)";for(int i=0;i<3;i++) args.add("%"+query.trim()+"%");}
                if(from!=null && !from.isBlank()) {where+=" AND r.created_at>=?";args.add(parseDate(from));}
                if(to!=null && !to.isBlank()) {where+=" AND r.created_at<?";args.add(parseDate(to).plusDays(1));}
            }
            case "withdrawals" -> {
                select="w.id,w.user_id,w.amount_cents,w.channel,w.masked_account,w.status,w.note,w.claimed_by,w.payment_reference,w.paid_at,w.created_at,u.email,(SELECT COUNT(DISTINCT x.user_id) FROM referral_withdrawals x WHERE x.account_hash=w.account_hash) account_users";
                table="referral_withdrawals w JOIN users u ON u.id=w.user_id"; sort="w.id DESC";
                if(owner!=null) {where+=" AND w.user_id=?";args.add(owner);}
                if(status!=null && !status.isBlank()) {where+=" AND w.status=?";args.add(status);}
                if(query!=null && !query.isBlank()) {where+=" AND u.email LIKE ?";args.add("%"+query.trim()+"%");}
            }
            case "refunds" -> {
                if(owner!=null) throw invalid("列表类型无效");
                select="r.*,o.order_no"; table="referral_refunds r JOIN service_orders o ON o.id=r.order_id"; sort="r.id DESC";
                if(query!=null && !query.isBlank()) {where+=" AND o.order_no=?";args.add(query.trim());}
            }
            default -> throw invalid("列表类型无效");
        }
        long total=sum("SELECT COUNT(*) FROM "+table+where,args.toArray());
        List<Object> paged=new ArrayList<>(args);paged.add(size);paged.add((page-1L)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT "+select+" FROM "+table+where+" ORDER BY "+sort+" LIMIT ? OFFSET ?",paged.toArray());
        if(owner!=null) for(Map<String,Object> row:rows) {
            if(row.containsKey("customer_email")) row.put("customer_email",mask((String)row.get("customer_email")));
            row.remove("customer_user_id");row.remove("customer_id");row.remove("account_users");row.remove("claimed_by");
            row.remove("cost_cents");row.remove("sale_cents");row.remove("inviter_email");
        }
        PageResponse<Map<String,Object>> result=new PageResponse<>();
        result.setPage(page);result.setSize(size);result.setTotal(total);result.setItems(rows);
        return result;
    }

    private LocalDateTime timestamp(Object value) {
        return value instanceof LocalDateTime date?date:((java.sql.Timestamp)value).toLocalDateTime();
    }

    private LocalDateTime parseDate(String value) {
        try { return java.time.LocalDate.parse(value).atStartOfDay(); }
        catch(java.time.DateTimeException invalidDate) { throw invalid("日期格式无效"); }
    }
}
