package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

/** Durable publication intent. Only explicitly requested models are enabled. */
@Service @RequiredArgsConstructor
public class GatewayPublicationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AdminChannelService channels;
    @Autowired @Lazy private GatewaySyncJobs jobs;

    public Map<String,Object> request(long model) {
        return tx.execute(status -> {
            var rows = lockModel(model);
            if (rows.isEmpty()) return result(model, false, false, "模型不存在");
            var row = rows.get(0);
            String reason = reason(row);
            String state = reason.isEmpty() ? "PUBLISHED" : "WAITING";
            if (jdbc.update("UPDATE gateway_publication_requests SET status=?,reason=?,next_attempt_at=?,updated_at=? WHERE model_mapping_id=?",
                    state, reason, LocalDateTime.now(), LocalDateTime.now(), model) == 0)
                jdbc.update("INSERT INTO gateway_publication_requests(model_mapping_id,status,reason) VALUES(?,?,?)", model, state, reason);
            if (reason.isEmpty()) jdbc.update("UPDATE model_mappings SET enabled=TRUE WHERE id=?", model);
            return result(model, reason.isEmpty(), !reason.isEmpty(), reason);
        });
    }

    private Map<String,Object> result(long id, boolean success, boolean queued, String reason) {
        return Map.of("id", id, "success", success, "queued", queued, "reason", reason);
    }

    private List<Map<String,Object>> lockModel(long model) {
        for (Long channel : jdbc.queryForList("SELECT channel_id FROM model_mappings WHERE id=?", Long.class, model))
            jdbc.queryForList("SELECT id FROM channels WHERE id=? FOR UPDATE", channel);
        return jdbc.queryForList("""
            SELECT m.*, c.enabled channel_enabled, c.health_status,
                CASE WHEN c.api_key IS NOT NULL AND c.api_key<>'' THEN TRUE ELSE FALSE END has_key,
                (SELECT COUNT(*) FROM new_api_model_state n WHERE n.channel_id=m.channel_id AND n.upstream_model_name=m.channel_model_name AND (n.missing_count>0 OR n.retired=TRUE))
                  + (SELECT COUNT(*) FROM sub2api_model_state s WHERE s.channel_id=m.channel_id AND s.upstream_model_name=m.channel_model_name AND (s.missing_count>0 OR s.retired=TRUE)) upstream_missing,
                (SELECT COUNT(*) FROM model_price_tiers t WHERE t.model_mapping_id=m.id AND t.service_tier='priority'
                    AND (COALESCE(t.sale_input_price,0)<=0 OR COALESCE(t.sale_output_price,0)<=0)) invalid_service_prices
            FROM model_mappings m JOIN channels c ON c.id=m.channel_id WHERE m.id=? FOR UPDATE
            """, model);
    }

    static String reason(Map<String,Object> row) {
        if (!truth(row.get("has_key"))) return "缺少分组 Key；补齐后自动重试";
        if (!truth(row.get("channel_enabled"))) return "分组未启用；启用后自动重试";
        if (truth(row.get("upstream_missing"))) return "上游目录尚未确认此模型可用，等待后续完整同步";
        if (!"VERIFIED".equals(row.get("pricing_status")))
            return "等待报价核验：" + Objects.toString(row.get("pricing_message"), "尚未读取上游报价");
        if (!truth(row.get("billing_enabled")) || !"PAID".equals(row.get("billing_mode"))) return "计费尚未启用";
        if (truth(row.get("invalid_service_prices"))) return "服务档位售价不完整；请核验手工售价或加价规则";
        boolean token = "TOKEN".equals(row.get("pricing_unit"));
        if (token ? !positive(row.get("input_price_per_million")) ||
                (!Set.of("embedding", "rerank").contains(Objects.toString(row.get("capability"), "text")) && !positive(row.get("output_price_per_million")))
                : !positive(row.get("sale_unit_price"))) return "销售价格不完整；请检查生效的加价规则";
        if (!Set.of("HEALTHY", "DEGRADED").contains(Objects.toString(row.get("health_status"), "")))
            return "等待分组可用性验证";
        return "";
    }

    /** Called while the durable sync job still owns the unified channel lock. */
    public void afterSync(long channel) {
        jdbc.update("""
            UPDATE gateway_publication_requests SET status='WAITING',reason='报价发生变化，等待自动恢复',updated_at=?
            WHERE status='PUBLISHED' AND model_mapping_id IN
                (SELECT id FROM model_mappings WHERE channel_id=? AND enabled=FALSE AND (pricing_status IS NULL OR pricing_status<>'VERIFIED'))
            """, LocalDateTime.now(), channel);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM gateway_publication_requests p JOIN model_mappings m ON m.id=p.model_mapping_id WHERE m.channel_id=? AND p.status='WAITING'", Integer.class, channel) == 0) return;
        var group = jdbc.queryForMap("SELECT enabled,health_status,CASE WHEN api_key IS NOT NULL AND api_key<>'' THEN TRUE ELSE FALSE END has_key FROM channels WHERE id=?", channel);
        if (truth(group.get("enabled")) && truth(group.get("has_key")) &&
                !Set.of("HEALTHY", "DEGRADED").contains(Objects.toString(group.get("health_status"), ""))) {
            try { channels.test(channel); }
            catch (RuntimeException ignored) { /* The persisted health state gates publication below. */ }
        }
        completeReady(channel);
    }

    public void completeReady(long channel) {
        tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM channels WHERE id=? FOR UPDATE", channel);
            for (Long id : jdbc.queryForList("SELECT m.id FROM model_mappings m JOIN gateway_publication_requests p ON p.model_mapping_id=m.id WHERE m.channel_id=? AND p.status='WAITING' ORDER BY m.id", Long.class, channel)) {
                var rows = lockModel(id);
                if (rows.isEmpty()) continue;
                var intent = jdbc.queryForList("SELECT status FROM gateway_publication_requests WHERE model_mapping_id=? FOR UPDATE", id);
                if (intent.isEmpty() || !"WAITING".equals(intent.get(0).get("status"))) continue;
                String reason = reason(rows.get(0));
                if (reason.isEmpty()) jdbc.update("UPDATE model_mappings SET enabled=TRUE WHERE id=?", id);
                jdbc.update("UPDATE gateway_publication_requests SET status=?,reason=?,next_attempt_at=?,updated_at=? WHERE model_mapping_id=?",
                        reason.isEmpty() ? "PUBLISHED" : "WAITING", reason, LocalDateTime.now().plusMinutes(15), LocalDateTime.now(), id);
            }
        });
    }

    public void cancel(long model) {
        tx.executeWithoutResult(status -> {
            lockModel(model);
            jdbc.update("UPDATE gateway_publication_requests SET status='CANCELLED',reason='已取消自动发布',updated_at=? WHERE model_mapping_id=? AND status IN ('WAITING','PUBLISHED')", LocalDateTime.now(), model);
        });
    }

    public void remove(long model) {
        jdbc.update("DELETE FROM gateway_publication_requests WHERE model_mapping_id=?", model);
    }

    @Scheduled(fixedDelayString="${gateway.publication.retry-delay-ms:30000}", initialDelay=15000)
    public void retry() {
        for (Long channel : jdbc.queryForList("""
            SELECT DISTINCT m.channel_id FROM gateway_publication_requests p JOIN model_mappings m ON m.id=p.model_mapping_id
            WHERE p.status='WAITING' AND p.next_attempt_at<=? ORDER BY m.channel_id LIMIT 20
            """, Long.class, LocalDateTime.now())) {
            completeReady(channel);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM gateway_publication_requests p JOIN model_mappings m ON m.id=p.model_mapping_id WHERE m.channel_id=? AND p.status='WAITING'", Integer.class, channel) == 0) continue;
            jdbc.update("UPDATE gateway_publication_requests SET next_attempt_at=?,updated_at=? WHERE status='WAITING' AND model_mapping_id IN (SELECT id FROM model_mappings WHERE channel_id=?)",
                    LocalDateTime.now().plusMinutes(15), LocalDateTime.now(), channel);
            var prerequisites = jdbc.queryForMap("""
                SELECT c.enabled,CASE WHEN c.api_key IS NOT NULL AND c.api_key<>'' THEN TRUE ELSE FALSE END has_key,
                  CASE WHEN n.channel_id IS NOT NULL OR x.channel_id IS NOT NULL OR u.channel_id IS NOT NULL THEN TRUE ELSE FALSE END has_adapter
                FROM channels c LEFT JOIN new_api_connections n ON n.channel_id=c.id
                LEFT JOIN sub2api_connections x ON x.channel_id=c.id
                LEFT JOIN upstream_catalog_sync u ON u.channel_id=c.id WHERE c.id=?
                """, channel);
            if (!truth(prerequisites.get("enabled")) || !truth(prerequisites.get("has_key")) || !truth(prerequisites.get("has_adapter"))) {
                String reason = !truth(prerequisites.get("enabled")) ? "分组未启用；启用后自动重试"
                        : !truth(prerequisites.get("has_key")) ? "缺少分组 Key；补齐后自动重试"
                        : "该分组没有自动报价适配器；配置适配器或人工核验价格";
                jdbc.update("UPDATE gateway_publication_requests SET reason=?,updated_at=? WHERE status='WAITING' AND model_mapping_id IN (SELECT id FROM model_mappings WHERE channel_id=?)", reason, LocalDateTime.now(), channel);
                continue;
            }
            try { jobs.enqueue(channel); }
            catch (RuntimeException error) {
                jdbc.update("UPDATE gateway_publication_requests SET reason=?,updated_at=? WHERE status='WAITING' AND model_mapping_id IN (SELECT id FROM model_mappings WHERE channel_id=?)",
                        GatewaySyncJobs.classify(error).message() + "；15分钟后自动重试", LocalDateTime.now(), channel);
            }
        }
    }

    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0; }
    private static boolean positive(Object value) { return value != null && new java.math.BigDecimal(value.toString()).signum()>0; }
}
