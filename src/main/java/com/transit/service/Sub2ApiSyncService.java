package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Key-scoped model synchronization for independently deployed sub2api sites. */
@Service
@RequiredArgsConstructor
public class Sub2ApiSyncService {
    private final JdbcTemplate jdbc;
    private final ChannelMapper channels;
    private final ModelMappingMapper mappings;
    private final ChannelSecretService secrets;
    private final Sub2ApiCatalogClient catalog;
    private final ModelIdentityService identities;
    private final TransactionTemplate transactions;

    public void register(long channelId, String base, Sub2ApiCatalogClient.Snapshot snapshot,
                         NewApiOnboardingService.Request request, List<String> selected) {
        jdbc.update("""
                INSERT INTO sub2api_connections(channel_id,base_url,detection_method,billing_schema_version,
                effective_rate_multiplier,sync_enabled,add_new_models,sync_status,last_message)
                VALUES (?,?,?,?,?,?,?,'IMPORTED','已导入 Key 可见模型，采购价待核验')
                """, channelId, base, snapshot.detectionMethod(), snapshot.billingSchemaVersion(),
                snapshot.effectiveRateMultiplier(), !Boolean.FALSE.equals(request.autoSync()),
                !Boolean.FALSE.equals(request.addNewModels()));
        Map<String, ModelMapping> current = mappingMap(channelId);
        for (String model : snapshot.models()) {
            jdbc.update("INSERT INTO sub2api_model_state(channel_id,upstream_model_name,model_mapping_id) VALUES (?,?,?)",
                    channelId, model, selected.contains(model) ? current.get(model).getId() : null);
        }
    }

    public Map<String, Object> synchronize(long id, boolean manual) {
        String lease = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int claimed = jdbc.update("""
                UPDATE sub2api_connections SET lease_token=?,lease_until=? WHERE channel_id=?
                AND (lease_until IS NULL OR lease_until<?) AND (sync_enabled=TRUE OR ?=TRUE)
                """, lease, now.plusMinutes(3), id, now, manual);
        if (claimed != 1) throw conflict("同步已执行、已暂停或分组不存在");
        try {
            Map<String, Object> config = connection(id, false);
            Channel channel = channels.selectById(id);
            if (channel == null || !Sub2ApiOnboardingService.SOURCE_CODE.equals(channel.getSourceCode())
                    || !NewApiOnboardingService.normalizeBaseUrl(channel.getBaseUrl()).equals(config.get("base_url"))) {
                throw conflict("分组站点或适配器已变化，请重新接入");
            }
            String encryptedKey = channel.getApiKey();
            Sub2ApiCatalogClient.Snapshot snapshot = catalog.fetch((String) config.get("base_url"), secrets.decrypt(encryptedKey));
            GatewaySyncProgress.phase("APPLY");
            return transactions.execute(status -> applyLocked(id, lease, encryptedKey, snapshot));
        } catch (RuntimeException error) {
            jdbc.update("""
                    UPDATE sub2api_connections SET sync_status='ERROR',last_message='同步失败，已保留原有模型与价格'
                    WHERE channel_id=? AND lease_token=?
                    """, id, lease);
            throw error;
        } finally {
            jdbc.update("UPDATE sub2api_connections SET lease_token=NULL,lease_until=NULL WHERE channel_id=? AND lease_token=?", id, lease);
        }
    }

    private Map<String, Object> applyLocked(long id, String lease, String encryptedKey,
                                             Sub2ApiCatalogClient.Snapshot snapshot) {
        Map<String, Object> config = connection(id, true);
        if (!lease.equals(config.get("lease_token"))) throw conflict("同步设置已变化");
        jdbc.queryForList("SELECT id FROM channels WHERE id=? FOR UPDATE", id);
        Channel channel = channels.selectById(id);
        if (channel == null || !Objects.equals(encryptedKey, channel.getApiKey())) throw conflict("分组凭据已变化");

        Map<String, ModelMapping> current = mappingMap(id);
        Map<String, Map<String, Object>> states = new LinkedHashMap<>();
        jdbc.queryForList("SELECT * FROM sub2api_model_state WHERE channel_id=?", id)
                .forEach(row -> states.put(text(row, "upstream_model_name"), row));
        LinkedHashSet<String> live = new LinkedHashSet<>(snapshot.models());
        int added = 0, disabled = 0, capacitySkipped = 0;

        for (var entry : states.entrySet()) {
            ModelMapping mapping = current.get(entry.getKey());
            if (mapping == null || !Objects.equals(mapping.getId(), number(entry.getValue().get("model_mapping_id")))) continue;
            if (live.contains(entry.getKey())) {
                jdbc.update("UPDATE sub2api_model_state SET missing_count=0,retired=FALSE WHERE channel_id=? AND upstream_model_name=?",
                        id, entry.getKey());
                continue;
            }
            int missing = integer(entry.getValue(), "missing_count") + 1;
            jdbc.update("UPDATE sub2api_model_state SET missing_count=?,retired=? WHERE channel_id=? AND upstream_model_name=?",
                    missing, missing >= 2, id, entry.getKey());
            if (missing >= 2) {
                mapping.setEnabled(false);
                mapping.setPricingMessage("该 Key 连续两次完整目录未返回此模型，已停用");
                mappings.updateById(mapping);
                disabled++;
            }
        }

        LinkedHashSet<String> names = new LinkedHashSet<>(current.keySet());
        if (truth(config.get("add_new_models"))) {
            for (String model : live) {
                if (states.containsKey(model) || current.containsKey(model)) continue;
                LinkedHashSet<String> candidate = new LinkedHashSet<>(names);
                candidate.add(model);
                if (candidate.size() > 500 || String.join("\n", candidate).length() > 2000) { capacitySkipped++; continue; }
                ModelMapping mapping = Sub2ApiOnboardingService.draft(model);
                mapping.setChannelId(id);
                mappings.insert(mapping);
                identities.register(channel, mapping, mapping.getVendor(), ModelIdentityService.RANK_INFERRED);
                jdbc.update("INSERT INTO sub2api_model_state(channel_id,upstream_model_name,model_mapping_id) VALUES (?,?,?)",
                        id, model, mapping.getId());
                names.add(model);
                added++;
            }
        }
        if (added > 0) jdbc.update("UPDATE channels SET models=? WHERE id=?", String.join("\n", names), id);
        String state = capacitySkipped > 0 ? "PARTIAL" : "SUCCESS";
        String message = "sub2api Key 目录同步完成：可见 " + live.size() + "，新增 " + added
                + "，停用 " + disabled + "，容量不足 " + capacitySkipped + "；手工价格未覆盖";
        jdbc.update("""
                UPDATE sub2api_connections SET detection_method=?,billing_schema_version=?,effective_rate_multiplier=?,
                sync_status=?,last_message=?,last_synced_at=? WHERE channel_id=?
                """, snapshot.detectionMethod(), snapshot.billingSchemaVersion(), snapshot.effectiveRateMultiplier(),
                state, message, LocalDateTime.now(), id);
        return Map.of("channelId", id, "status", state, "message", message, "added", added, "disabled", disabled);
    }

    private Map<String, ModelMapping> mappingMap(long channelId) {
        Map<String, ModelMapping> result = new LinkedHashMap<>();
        mappings.selectList(new LambdaQueryWrapper<ModelMapping>().eq(ModelMapping::getChannelId, channelId))
                .forEach(mapping -> result.put(mapping.getChannelModelName(), mapping));
        return result;
    }

    private Map<String, Object> connection(long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM sub2api_connections WHERE channel_id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw conflict("该分组没有 sub2api 同步配置");
        return rows.get(0);
    }

    private static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    private static int integer(Map<String, Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    private static Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0; }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
