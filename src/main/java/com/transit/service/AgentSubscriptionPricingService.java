package com.transit.service;

import com.transit.dto.PageResponse;
import com.transit.dto.MoneyAmount;
import com.transit.model.OtherService;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentSubscriptionPricingService {
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    @Value("${features.linknux.agent.enabled:false}")
    private boolean enabled;

    public record Price(Long agentId, long baseCents, long unitCents) {}
    public record PriceChange(Long cents, long version) {}
    private record ConfiguredPrice(String currency, long base, Long retail) {}

    private static final String ACTIVE_AGENT = """
            FROM agent_profiles p
            JOIN agent_tiers t ON t.tier_code=p.tier_code AND t.enabled=TRUE
            JOIN users u ON u.id=p.user_id AND u.status='ACTIVE'
            WHERE p.status='ACTIVE'
            """;

    public Long agentForCode(String code) {
        if (!enabled || code == null || code.isBlank()) return null;
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,32}")) return null;
        return jdbc.queryForList("SELECT p.user_id " + ACTIVE_AGENT + " AND UPPER(p.invite_code)=?", Long.class, normalized)
                .stream().findFirst().orElse(null);
    }

    public Price price(OtherService service, Long buyer, Long linkedAgent) {
        return catalogPrices(List.of(service), buyer, linkedAgent).get(service.getId());
    }

    private Map<Long, Price> catalogPrices(List<OtherService> services, Long buyer, Long linkedAgent) {
        Long agent = linkedAgent == null ? buyer : linkedAgent;
        if (!enabled || agent == null || services.isEmpty()) return Map.of();
        boolean ownPrice = agent.equals(buyer);
        List<Object> args = new ArrayList<>(List.of(agent));
        services.forEach(service -> args.add(service.getId()));
        String placeholders = String.join(",", Collections.nCopies(services.size(), "?"));
        Map<Long, ConfiguredPrice> configured = jdbc.query("""
                SELECT x.service_id,x.currency,x.base_cents,x.retail_cents
                FROM agent_subscription_prices x
                JOIN agent_profiles p ON p.user_id=x.agent_user_id AND p.status='ACTIVE'
                JOIN agent_tiers t ON t.tier_code=p.tier_code AND t.enabled=TRUE
                JOIN users u ON u.id=p.user_id AND u.status='ACTIVE'
                WHERE x.agent_user_id=? AND x.service_id IN (
                """ + placeholders + ")", rs -> {
            Map<Long, ConfiguredPrice> rows = new java.util.HashMap<>();
            while (rs.next()) rows.put(rs.getLong(1),new ConfiguredPrice(rs.getString(2),rs.getLong(3),rs.getObject(4,Long.class)));
            return rows;
        }, args.toArray());
        Map<Long, Price> prices = new java.util.HashMap<>();
        for (OtherService service : services) {
            ConfiguredPrice row = configured.get(service.getId());
            if (row == null || !Boolean.TRUE.equals(service.getPurchaseEnabled())
                    || !row.currency().equals(service.getCurrency()) || service.getPriceCents() == null) continue;
            Long unit = ownPrice ? Long.valueOf(row.base()) : row.retail();
            if (unit != null && unit >= row.base() && unit <= service.getPriceCents()) {
                prices.put(service.getId(), new Price(ownPrice ? null : agent, row.base(), unit));
            }
        }
        return prices;
    }

    public void applyCatalogPrices(List<OtherService> services, Long buyer, String code) {
        Map<Long, Price> prices = catalogPrices(services, buyer, agentForCode(code));
        for (OtherService service : services) {
            Price price = prices.get(service.getId());
            if (price == null) continue;
            long amount = Math.addExact(price.unitCents(), service.getServiceFeeCents() == null ? 0 : service.getServiceFeeCents());
            service.setPriceCents(price.unitCents());
            service.setPriceMoney(MoneyAmount.cents(price.unitCents(), service.getCurrency()));
            service.setAmountCents(amount);
            service.setAmountMoney(MoneyAmount.cents(amount, service.getCurrency()));
            service.setWholesaleTiersJson("[]");
        }
    }

    public PageResponse<Map<String, Object>> prices(long agent, boolean admin, int page, int size, String query) {
        requireActive(agent);
        String from = """
                FROM other_services s LEFT JOIN agent_subscription_prices x ON x.service_id=s.id AND x.agent_user_id=?
                WHERE s.enabled=TRUE AND s.purchase_enabled=TRUE AND s.currency IN ('CNY','USD')
                """;
        if (!admin) from += " AND x.service_id IS NOT NULL";
        List<Object> args = new ArrayList<>(List.of(agent));
        if (query != null && !query.isBlank()) {
            if (query.trim().length() > 160) throw invalid("搜索词最多 160 字符");
            from += " AND LOWER(s.name) LIKE LOWER(?)";
            args.add("%" + query.trim() + "%");
        }
        return page("""
                SELECT s.id,s.name,s.currency,s.price_cents,
                       CASE WHEN x.currency=s.currency THEN x.base_cents END AS base_cents,
                       CASE WHEN x.currency=s.currency THEN x.retail_cents END AS retail_cents,
                       COALESCE(x.version,0) AS version
                """, from, " ORDER BY s.sort_order,s.id", args, page, size);
    }

    @Transactional
    public void setBase(User admin, long agent, long service, PriceChange change) {
        update(agent, service, change, true);
        audit.record(admin, "AGENT_SUBSCRIPTION_BASE_PRICE", "SERVICE", service, null,
                Map.of("agentUserId", agent, "baseCents", change.cents()), null);
    }

    @Transactional
    public void setRetail(long agent, long service, PriceChange change) {
        update(agent, service, change, false);
    }

    private void update(long agent, long service, PriceChange change, boolean admin) {
        requireActive(agent);
        var product = jdbc.queryForList("""
                SELECT price_cents,currency FROM other_services
                WHERE id=? AND enabled=TRUE AND purchase_enabled=TRUE AND currency IN ('CNY','USD') FOR UPDATE
                """, service).stream().findFirst().orElseThrow(() -> invalid("该订阅商品不可配置代理价格"));
        String currency = (String) product.get("currency");
        long publicPrice = ((Number) product.get("price_cents")).longValue();
        var current = jdbc.queryForList("""
                SELECT base_cents,retail_cents,currency,version FROM agent_subscription_prices
                WHERE agent_user_id=? AND service_id=? FOR UPDATE
                """, agent, service).stream().findFirst().orElse(Map.of());
        long version = current.isEmpty() ? 0 : ((Number) current.get("version")).longValue();
        if (version != change.version()) throw new ResponseStatusException(HttpStatus.CONFLICT, "价格已更新，请刷新后重试");
        Long cents = change.cents();
        if ((admin && cents == null) || (cents != null && (cents < 1 || cents > publicPrice))) {
            throw invalid("价格须大于零且不高于公开售价");
        }
        if (admin) {
            Long retail = currency.equals(current.get("currency")) && current.get("retail_cents") instanceof Number value
                    ? value.longValue() : null;
            if (retail != null && (retail < cents || retail > publicPrice)) retail = null;
            if (current.isEmpty()) {
                jdbc.update("INSERT INTO agent_subscription_prices(agent_user_id,service_id,currency,base_cents) VALUES (?,?,?,?)",
                        agent, service, currency, cents);
            } else {
                jdbc.update("""
                        UPDATE agent_subscription_prices SET currency=?,base_cents=?,retail_cents=?,version=version+1
                        WHERE agent_user_id=? AND service_id=?
                        """, currency, cents, retail, agent, service);
            }
        } else {
            if (!currency.equals(current.get("currency"))) throw invalid("请先由管理员设置该商品底价");
            if (cents != null && cents < ((Number) current.get("base_cents")).longValue()) {
                throw invalid("代理售价不得低于底价");
            }
            jdbc.update("""
                    UPDATE agent_subscription_prices SET retail_cents=?,version=version+1
                    WHERE agent_user_id=? AND service_id=?
                    """, cents, agent, service);
        }
    }

    public PageResponse<Map<String, Object>> resaleLedger(long agent, int page, int size) {
        return page("""
                SELECT l.id,l.note,l.entry_type,l.amount,l.created_at
                """, """
                FROM agent_commission_ledger l
                WHERE l.beneficiary_user_id=? AND l.entry_type IN ('AGENT_RESALE','AGENT_RESALE_REVERSAL')
                """, " ORDER BY l.id DESC", new ArrayList<>(List.of(agent)), page, size);
    }

    private PageResponse<Map<String, Object>> page(String select, String from, String order,
                                                    List<Object> args, int page, int size) {
        if (page < 1 || page > 1_000_000 || !List.of(10,20,50,100).contains(size)) throw invalid("分页参数无效");
        long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1L) * size);
        PageResponse<Map<String, Object>> response = new PageResponse<>();
        response.setPage(page);
        response.setSize(size);
        response.setTotal(total);
        response.setItems(jdbc.queryForList(select + from + order + " LIMIT ? OFFSET ?", args.toArray()));
        return response;
    }

    private void requireActive(long agent) {
        if (!enabled || jdbc.queryForList("SELECT p.user_id " + ACTIVE_AGENT + " AND p.user_id=?", Long.class, agent).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该账号尚未开通代理");
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
