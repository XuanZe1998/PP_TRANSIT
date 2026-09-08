package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NewApiSyncService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private GatewayPricingService gatewayPrices;
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private GatewaySyncJobs jobs;
    private final ChannelMapper channels;
    private final ModelMappingMapper mappings;
    private final ChannelSecretService secrets;
    private final ModelPriceTierService tiers;
    private final ModelIdentityService identities;
    private final NewApiCatalogClient client;
    private final NewApiPricing pricing;
    private final TransactionTemplate transactions;
    @Value("${new-api.sync.enabled:true}") private boolean schedulerEnabled;

    public void register(Channel channel, NewApiOnboardingService.Request request, List<String> allModels) {
        jdbc.update("""
                INSERT INTO new_api_connections(channel_id,base_url,upstream_group,sale_markup,unit_usd,
                pricing_access_token,pricing_user_id,sync_enabled,add_new_models,update_prices,sync_status,last_message)
                VALUES (?,?,?,?,?,?,?,?,?,?,'IMPORTED','已导入，尚未验证实际调用')
                """, channel.getId(), channel.getBaseUrl(), request.upstreamGroup().trim(),
                NewApiOnboardingService.markup(request), NewApiOnboardingService.unitUsd(request),
                secrets.encrypt(request.pricingAccessToken()), request.pricingUserId(),
                !Boolean.FALSE.equals(request.autoSync()), !Boolean.FALSE.equals(request.addNewModels()),
                !Boolean.FALSE.equals(request.updatePrices()));
        if(gatewayPrices!=null)gatewayPrices.initializeGroup(channel.getId(),NewApiOnboardingService.markup(request));
        Map<String, ModelMapping> selected = mappingMap(channel.getId());
        // Remember intentionally excluded models, so the next sync does not silently import them.
        for (String model : allModels) jdbc.update("""
                INSERT INTO new_api_model_state(channel_id,upstream_model_name,model_mapping_id) VALUES (?,?,?)
                """, channel.getId(), model, selected.containsKey(model) ? selected.get(model).getId() : null);
    }

    public List<Map<String, Object>> status() {
        return jdbc.queryForList("""
                SELECT n.channel_id,n.upstream_group,n.sale_markup,n.unit_usd,n.sync_enabled,n.add_new_models,
                       n.update_prices,n.sync_status,n.last_message,n.last_synced_at,n.missing_group_count,
                       c.name channel_name,c.enabled channel_enabled,
                       CASE WHEN n.pricing_access_token IS NULL OR n.pricing_access_token='' THEN FALSE ELSE TRUE END pricing_auth_configured
                  FROM new_api_connections n JOIN channels c ON c.id=n.channel_id ORDER BY n.channel_id DESC
                """);
    }

    public record Settings(boolean syncEnabled, boolean addNewModels, boolean updatePrices,
                           BigDecimal saleMarkup, BigDecimal unitUsd,
                           @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
                           String pricingAccessToken, Long pricingUserId) {
        @Override public String toString() { return "NewApiSyncSettings[redacted]"; }
    }

    public void configure(long id, Settings settings) {
        NewApiOnboardingService.secret(settings.pricingAccessToken(), false);
        if (settings.pricingUserId() != null && settings.pricingUserId() <= 0) throw bad("账号 ID 无效");
        transactions.executeWithoutResult(tx -> {
            Map<String, Object> current = connection(id, true);
            var markup = NewApiOnboardingService.positive(settings.saleMarkup(), decimal(current, "sale_markup"), "销售倍率");
            var unit = NewApiOnboardingService.positive(settings.unitUsd(), decimal(current, "unit_usd"), "报价单位折合 USD");
            boolean authChanged = settings.pricingAccessToken() != null && !settings.pricingAccessToken().isBlank();
            jdbc.update("""
                    UPDATE new_api_connections SET sync_enabled=?,add_new_models=?,update_prices=?,sale_markup=?,unit_usd=?,
                    pricing_access_token=?,pricing_user_id=?,lease_token=NULL,lease_until=NULL WHERE channel_id=?
                    """, settings.syncEnabled(), settings.addNewModels(), settings.updatePrices(), markup, unit,
                    authChanged ? secrets.encrypt(settings.pricingAccessToken()) : current.get("pricing_access_token"),
                    authChanged ? settings.pricingUserId() : current.get("pricing_user_id"), id);
            if (authChanged) {
                // A changed pricing identity is not comparable with previous deletion observations.
                jdbc.update("UPDATE new_api_connections SET missing_group_count=0 WHERE channel_id=?", id);
                jdbc.update("UPDATE new_api_model_state SET missing_count=0 WHERE channel_id=?", id);
            }
        });
    }

    @Scheduled(cron = "${new-api.sync.cron:0 */15 * * * *}")
    public void scheduled() {
        if (!schedulerEnabled) return;
        List<Long> ids = jdbc.queryForList("""
                SELECT n.channel_id FROM new_api_connections n JOIN channels c ON c.id=n.channel_id
                 WHERE n.sync_enabled=TRUE
                """, Long.class);
        for (Long id : ids) {
            try { jobs.enqueue(id); }
            catch (RuntimeException ignored) { /* Per-channel errors are persisted by synchronize. */ }
        }
    }

    public Map<String, Object> synchronize(long id, boolean manual) {
        String lease = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int claimed = jdbc.update("""
                UPDATE new_api_connections SET lease_token=?,lease_until=? WHERE channel_id=?
                 AND (lease_until IS NULL OR lease_until<?) AND (sync_enabled=TRUE OR ?=TRUE)
                """, lease, now.plusMinutes(3), id, now, manual);
        if (claimed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "同步已在执行、已暂停或渠道不存在");
        try {
            Map<String, Object> config = connection(id, false);
            Channel channel = channels.selectById(id);
            if (channel == null || !"new-api".equals(channel.getSourceCode())
                    || !NewApiOnboardingService.normalizeBaseUrl(channel.getBaseUrl()).equals(config.get("base_url")))
                throw bad("渠道地址或来源已修改，请重新接入，防止向新地址发送价格凭据");
            String encryptedKey = channel.getApiKey();
            var snapshot = client.fetch(text(config, "base_url"), secrets.decrypt(encryptedKey),
                    secrets.decrypt(text(config, "pricing_access_token")), numberOrNull(config.get("pricing_user_id")));
            GatewaySyncProgress.phase("APPLY");
            return transactions.execute(tx -> {
                Map<String, Object> locked = connection(id, true);
                if (!lease.equals(locked.get("lease_token"))) throw bad("同步设置已变化，请重试");
                // Serialize with normal channel edits and reject stale credentials/URL snapshots.
                jdbc.queryForList("SELECT id FROM channels WHERE id=? FOR UPDATE", id);
                Channel latest = channels.selectById(id);
                if (latest == null || !Objects.equals(latest.getApiKey(), encryptedKey)
                        || !Objects.equals(latest.getBaseUrl(), channel.getBaseUrl())
                        || !Objects.equals(latest.getSourceCode(), channel.getSourceCode())) throw bad("渠道配置已变化，请重试");
                return apply(latest, locked, snapshot);
            });
        } catch (RuntimeException e) {
            jdbc.update("""
                    UPDATE new_api_connections SET sync_status='ERROR',last_message='同步失败，保留原有目录与价格；请检查权限、地址和网络'
                     WHERE channel_id=? AND lease_token=?
                    """, id, lease);
            // No raw upstream exception, request or credentials are returned/logged.
            throw bad("同步失败，原有目录与价格已保留，请检查配置或网络");
        } finally {
            jdbc.update("UPDATE new_api_connections SET lease_token=NULL,lease_until=NULL WHERE channel_id=? AND lease_token=?", id, lease);
        }
    }

    private Map<String, Object> apply(Channel channel, Map<String, Object> config, NewApiCatalogClient.Snapshot snapshot) {
        long id = channel.getId();
        String group = text(config, "upstream_group");
        boolean groupAbsent = snapshot.pricingComplete() && !pricing.groupExists(snapshot.pricing(), group);
        if (!snapshot.modelsComplete() && !groupAbsent) throw bad("模型目录不可用");
        int groupMissing = snapshot.pricingComplete() ? (groupAbsent ? integer(config, "missing_group_count") + 1 : 0)
                : integer(config, "missing_group_count");
        int added = 0, updated = 0, disabled = 0, pending = 0, capacitySkipped = 0;
        Map<String, ModelMapping> current = mappingMap(id);
        Map<String, Map<String, Object>> states = new LinkedHashMap<>();
        jdbc.queryForList("SELECT * FROM new_api_model_state WHERE channel_id=?", id)
                .forEach(row -> states.put(text(row, "upstream_model_name"), row));
        var priceRows = pricing.rows(snapshot.pricing());
        Set<String> live = snapshot.modelsComplete() ? new LinkedHashSet<>(snapshot.models()) : new LinkedHashSet<>();
        // Price membership adds group-specific evidence to the token-visible model catalog.
        if (snapshot.pricingComplete()) live.removeIf(model -> !pricing.inGroup(priceRows.get(model), group));
        if (groupAbsent) live.clear();
        var ratio = pricing.groups(snapshot.pricing()).stream().filter(g -> g.name().equals(group))
                .map(NewApiPricing.Group::ratio).findFirst().orElse(null);

        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            String model = entry.getKey();
            ModelMapping mapping = current.get(model);
            Long trackedId = numberOrNull(entry.getValue().get("model_mapping_id"));
            if (mapping == null || !Objects.equals(mapping.getId(), trackedId)) continue;
            if (!live.contains(model)) {
                // Valid pricing can confirm a group removal even when that group's key now returns 403.
                int missing = integer(entry.getValue(), "missing_count") + 1;
                jdbc.update("UPDATE new_api_model_state SET missing_count=?,retired=? WHERE channel_id=? AND upstream_model_name=?",
                        missing, missing >= 2, id, model);
                if (missing >= 2) {
                    mapping.setEnabled(false);
                    mapping.setPricingMessage("上游模型或分组连续两次完整同步缺失，已停用");
                    mappings.updateById(mapping);
                    disabled++;
                }
                continue;
            }
            jdbc.update("UPDATE new_api_model_state SET missing_count=0,retired=FALSE WHERE channel_id=? AND upstream_model_name=?", id, model);
            if (snapshot.pricingComplete() && bool(config, "update_prices")) {
                var price = pricing.calculate(model, priceRows.get(model), group, ratio,
                        decimal(config, "sale_markup"), decimal(config, "unit_usd"));
                pricing.apply(mapping, price, text(config, "base_url"), group);
                mappings.updateById(mapping);
                if (price.supported()) { tiers.synchronize(mapping, mapping.getPriceTiers()); if(gatewayPrices!=null)gatewayPrices.repriceAfterSync(mapping.getId()); updated++; }
                else pending++;
            }
            // Returning models stay disabled if an operator or prior removal disabled them.
        }
        LinkedHashSet<String> names = new LinkedHashSet<>(current.keySet());
        if (bool(config, "add_new_models") && snapshot.modelsComplete() && !groupAbsent) {
            for (String model : live) {
                if (states.containsKey(model) || current.containsKey(model)) continue;
                LinkedHashSet<String> candidate = new LinkedHashSet<>(names); candidate.add(model);
                if (String.join("\n", candidate).length() > 2000 || candidate.size() > 500) { capacitySkipped++; continue; }
                ModelMapping mapping = draft(model); mapping.setChannelId(id);
                if (snapshot.pricingComplete()) pricing.apply(mapping,
                        pricing.calculate(model, priceRows.get(model), group, ratio, decimal(config, "sale_markup"), decimal(config, "unit_usd")),
                        text(config, "base_url"), group);
                mappings.insert(mapping);
                tiers.synchronize(mapping, mapping.getPriceTiers());
                if(gatewayPrices!=null)gatewayPrices.repriceAfterSync(mapping.getId());
                identities.register(channel, mapping, mapping.getVendor(), ModelIdentityService.RANK_INFERRED);
                jdbc.update("INSERT INTO new_api_model_state(channel_id,upstream_model_name,model_mapping_id) VALUES (?,?,?)", id, model, mapping.getId());
                names.add(model); added++;
            }
        }
        if (added > 0) jdbc.update("UPDATE channels SET models=? WHERE id=?", String.join("\n", names), id);
        if (groupAbsent && groupMissing >= 2) jdbc.update("UPDATE channels SET enabled=FALSE WHERE id=?", id);
        String status = groupAbsent && groupMissing >= 2 ? "GROUP_REMOVED" : groupAbsent ? "MISSING_CONFIRMATION"
                : !snapshot.pricingComplete() || pending > 0 || capacitySkipped > 0 ? "PARTIAL" : "SUCCESS";
        String message = "新增 " + added + "，更新价格 " + updated + "，停用 " + disabled + "，待配置 " + pending
                + "，容量不足 " + capacitySkipped + (snapshot.pricingComplete() ? "" : "；价格读取失败，保留原价")
                + (groupAbsent ? "；上游分组不可见（第 " + groupMissing + " 次）" : "");
        jdbc.update("""
                UPDATE new_api_connections SET missing_group_count=?,sync_status=?,last_message=?,last_synced_at=? WHERE channel_id=?
                """, groupMissing, status, message, LocalDateTime.now(), id);
        refreshChannelProtocol(id);
        return Map.of("channelId", id, "status", status, "message", message, "added", added, "updated", updated, "disabled", disabled);
    }

    private void refreshChannelProtocol(long channelId) {
        List<ModelMapping> current = mappings.selectList(
                new LambdaQueryWrapper<ModelMapping>().eq(ModelMapping::getChannelId, channelId));
        if (current.isEmpty()) return;
        boolean anyImage = current.stream().anyMatch(mapping -> "image".equalsIgnoreCase(mapping.getCapability()));
        boolean anyOther = current.stream().anyMatch(mapping -> !"image".equalsIgnoreCase(mapping.getCapability()));
        String protocol = anyImage && !anyOther ? "openai-image" : anyImage ? "multi-protocol" : "openai-chat";
        jdbc.update("UPDATE channels SET protocol_type=? WHERE id=? AND protocol_type<>?", protocol, channelId, protocol);
    }

    static ModelMapping draft(String model) {
        ModelMapping mapping = ModelMapping.builder().publicModelName(model).channelModelName(model).enabled(false)
                .billingEnabled(false).billingMode("DISABLED").pricingStatus("PENDING")
                .inputPricePerMillion(BigDecimal.ZERO).outputPricePerMillion(BigDecimal.ZERO)
                .capabilityTags("new-api,pricing-required").pricingMessage("待同步采购价格").build();
        NewApiPricing.applyProtocolMetadata(mapping);
        return mapping;
    }
    private Map<String, ModelMapping> mappingMap(long id) {
        Map<String, ModelMapping> result = new LinkedHashMap<>();
        mappings.selectList(new LambdaQueryWrapper<ModelMapping>().eq(ModelMapping::getChannelId, id))
                .forEach(mapping -> result.put(mapping.getChannelModelName(), mapping));
        return result;
    }
    private Map<String, Object> connection(long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM new_api_connections WHERE channel_id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw bad("该渠道没有 New API 同步配置");
        return rows.get(0);
    }
    static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    static int integer(Map<String, Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    static BigDecimal decimal(Map<String, Object> row, String key) { return new BigDecimal(row.get(key).toString()); }
    static boolean bool(Map<String, Object> row, String key) { Object v = row.get(key); return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue() != 0); }
    static Long numberOrNull(Object v) { return v instanceof Number n ? n.longValue() : null; }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
