package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

/** One management contract; native adapters retain upstream-specific pricing and protocols. */
@Service
@RequiredArgsConstructor
public class NewApiCatalogManagementService {
    private final JdbcTemplate jdbc;
    private final NewApiSyncService newApi;
    private final AiApiBankCatalogService aiApiBank;
    private final ProviderModelCatalogService providers;
    @org.springframework.beans.factory.annotation.Autowired private Sub2ApiSyncService sub2api;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private GatewaySyncJobs jobs;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AiApiBankAccountSessionService bankSessions;
    @Value("${new-api.sync.enabled:true}") private boolean automatic;
    @Value("${aiapibank.enabled:true}") private boolean bankEnabled;
    @org.springframework.beans.factory.annotation.Autowired private GatewaySiteService sites;

    public Map<String,Object> synchronizeGroups(long site) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_sites WHERE id=? AND adapter='aiapibank'",Integer.class,site)!=1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"站点不支持分组目录同步");
        GatewaySyncProgress.phase("GROUPS");
        var result=aiApiBank.discoverGroups(site);
        sites.reconcile();registerAdapters();
        for(Long channel:jdbc.queryForList("SELECT c.id FROM channels c JOIN upstream_site_channels sc ON sc.channel_id=c.id JOIN upstream_catalog_sync u ON u.channel_id=c.id WHERE sc.site_id=? AND u.sync_enabled=TRUE AND c.api_key IS NOT NULL AND c.api_key<>''",Long.class,site))jobs.enqueue(channel);
        boolean authorized=bankSessions!=null&&bankSessions.isAuthorized(site);
        String source=authorized?"账号授权目录":"匿名公开目录";
        return Map.of("status","SUCCESS","message","模型广场"+source+"同步完成：本次发现 "+result.seen()+" 个，新增 "+result.added()+" 个；未出现的已有分组保留，新分组须配置 Key 并核验报价后开放调用");
    }

    private void enqueueBankGroups() {
        if(!bankEnabled)return;
        sites.reconcile();
        for(Long site:jdbc.queryForList("SELECT id FROM upstream_sites WHERE adapter='aiapibank'",Long.class))jobs.enqueueGroups(site);
    }

    public List<Map<String, Object>> directory() {
        return jdbc.queryForList("""
            SELECT c.id channel_id,c.name channel_name,c.source_code,c.source_name,c.enabled channel_enabled,
                   c.health_status,COALESCE(n.upstream_group,g.group_name,c.group_name) upstream_group,
                   CASE WHEN x.channel_id IS NOT NULL THEN 'sub2api'
                        WHEN n.channel_id IS NOT NULL THEN 'new-api'
                        WHEN c.source_code IN ('haoee','aiapibank') THEN c.source_code ELSE 'manual' END adapter,
                   CASE WHEN c.api_key IS NOT NULL AND c.api_key<>'' THEN TRUE ELSE FALSE END credential_configured,
                   COALESCE(x.sync_enabled,n.sync_enabled,s.sync_enabled,FALSE) sync_enabled,
                   COALESCE(x.sync_status,n.sync_status,s.sync_status,g.sync_status,'UNMANAGED') sync_status,
                   COALESCE(x.last_message,n.last_message,s.last_message,'') last_message,
                   COALESCE(x.last_synced_at,n.last_synced_at,s.last_synced_at,g.last_synced_at) last_synced_at,
                   COALESCE(m.model_count,0) model_count,COALESCE(m.enabled_count,0) enabled_count,
                   COALESCE(d.public_name,'平台智能路由') public_name
            FROM channels c
            LEFT JOIN new_api_connections n ON n.channel_id=c.id
            LEFT JOIN sub2api_connections x ON x.channel_id=c.id
            LEFT JOIN upstream_catalog_sync s ON s.channel_id=c.id
            LEFT JOIN aiapibank_provider_groups g ON g.channel_id=c.id
            LEFT JOIN upstream_display_mappings d ON d.channel_id=c.id AND d.enabled=TRUE
            LEFT JOIN (SELECT channel_id,COUNT(*) model_count,
                       SUM(CASE WHEN enabled=TRUE THEN 1 ELSE 0 END) enabled_count
                       FROM model_mappings GROUP BY channel_id) m ON m.channel_id=c.id
            ORDER BY c.source_code,c.id
            """);
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void registerAdapters() {
        jdbc.update("""
            INSERT INTO upstream_catalog_sync(channel_id)
            SELECT id FROM channels c WHERE source_code IN ('haoee','aiapibank')
            AND NOT EXISTS (SELECT 1 FROM upstream_catalog_sync s WHERE s.channel_id=c.id)
            """);
    }

    public Object importAiApiBank(boolean preview) {
        var result = aiApiBank.sync(preview);
        if (!preview) registerAdapters();
        return result;
    }

    public void configure(long id, boolean enabled) {
        String adapter = adapter(id);
        String table = "new-api".equals(adapter) ? "new_api_connections"
                : "sub2api".equals(adapter) ? "sub2api_connections" : "upstream_catalog_sync";
        if (jdbc.update("UPDATE " + table + " SET sync_enabled=? WHERE channel_id=?", enabled, id) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "渠道尚未接入自动同步");
    }

    private String adapter(long id) {
        return directory().stream().filter(row -> ((Number)row.get("channel_id")).longValue() == id)
                .map(row -> String.valueOf(row.get("adapter"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "渠道不存在"));
    }

    public Map<String,Object> synchronize(long id, boolean manual) {
        String adapter = adapter(id);
        if ("new-api".equals(adapter)) return newApi.synchronize(id, manual);
        if ("sub2api".equals(adapter)) return sub2api.synchronize(id, manual);
        if (!Set.of("haoee", "aiapibank").contains(adapter))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该渠道尚未配置目录适配器，请使用 New API 快速接入");
        String lease = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (jdbc.update("""
                UPDATE upstream_catalog_sync SET lease_token=?,lease_until=? WHERE channel_id=?
                AND (lease_until IS NULL OR lease_until<?) AND (sync_enabled=TRUE OR ?=TRUE)
                """, lease, now.plusMinutes(3), id, now, manual) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同步已执行、已暂停或尚未注册");
        try {
            String message;
            if ("aiapibank".equals(adapter)) {
                var result = aiApiBank.syncChannel(id);
                message = "AiAPIBank 分组已同步，导入或更新 " + result.modelsApplied() + " 个模型及原生报价";
            } else {
                int count = providers.synchronizeHaoeeLive(id);
                message = "好易智算目录已同步，共 " + count + " 个模型；报价沿用已核验配置，新报价待配置";
            }
            finish(id, lease, "SUCCESS", message);
            return Map.of("channelId", id, "adapter", adapter, "message", message);
        } catch (RuntimeException error) {
            finish(id, lease, "ERROR", "上游目录同步失败，请检查凭据、接口和网络；保留原有目录");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "上游目录同步失败，请检查凭据、接口和网络", error);
        }
    }

    private void finish(long id, String lease, String status, String message) {
        jdbc.update("""
            UPDATE upstream_catalog_sync SET sync_status=?,last_message=?,last_synced_at=?,lease_token=NULL,lease_until=NULL
            WHERE channel_id=? AND lease_token=?
            """, status, message, LocalDateTime.now(), id, lease);
    }

    @Scheduled(cron = "${aiapibank.sync-cron:0 20 3 * * *}", zone = "${aiapibank.sync-zone:Asia/Tokyo}")
    public void discoverAiApiBankGroups() {
        if (!automatic || !bankEnabled) return;
        try { enqueueBankGroups(); }
        catch (RuntimeException ignored) { /* Native importer persists its run result. */ }
    }

    @Scheduled(cron = "${new-api.sync.cron:0 */15 * * * *}")
    public void scheduled() {
        if (!automatic) return;
        registerAdapters();
        List<Long> ids = jdbc.queryForList("""
            SELECT s.channel_id FROM upstream_catalog_sync s JOIN channels c ON c.id=s.channel_id
            WHERE s.sync_enabled=TRUE AND c.source_code='haoee'
            AND c.api_key IS NOT NULL AND c.api_key<>''
            """, Long.class);
        for (Long id : ids) {
            try { jobs.enqueue(id); }
            catch (RuntimeException ignored) { /* A channel failure does not stop other adapters. */ }
        }
        for (Long id : jdbc.queryForList("""
                SELECT x.channel_id FROM sub2api_connections x JOIN channels c ON c.id=x.channel_id
                WHERE x.sync_enabled=TRUE AND c.source_code='sub2api'
                  AND c.api_key IS NOT NULL AND c.api_key<>''
                """, Long.class)) {
            try { jobs.enqueue(id); }
            catch (RuntimeException ignored) { /* A channel failure does not stop other adapters. */ }
        }
    }
}
