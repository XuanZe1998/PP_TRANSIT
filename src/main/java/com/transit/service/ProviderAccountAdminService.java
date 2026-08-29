package com.transit.service;

import com.transit.model.ProviderCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProviderAccountAdminService {
    private final JdbcTemplate jdbc;
    private final ProviderCredentialService credentials;
    private final AdminChannelService channelService;
    @Value("${features.linknux.provider-accounts.enabled:false}") private boolean enabled;
    @Value("${features.linknux.provider-oauth.enabled:false}") private boolean oauthEnabled;

    public Map<String, Object> status() { return Map.of("enabled", enabled, "oauthEnabled", oauthEnabled,
            "platforms", List.of("OPENAI", "CODEX", "CLAUDE", "GEMINI", "ANTIGRAVITY", "GROK", "COMPATIBLE")); }

    public List<Map<String, Object>> list(Long channelId) {
        String base = "SELECT id,channel_id,name,platform,auth_type,secret_preview,priority,weight,rpm_limit,tpm_limit,concurrency_limit,enabled,health_status,cooldown_until,oauth_expires_at,account_group,upstream_proxy_id,cost_mode,period_cost_amount,cost_reliable,model_scope,temporary_unschedulable_until,last_error_class,last_error,average_latency_ms,last_used_at,created_at,updated_at FROM provider_credentials";
        return channelId == null ? jdbc.queryForList(base + " ORDER BY id DESC") : jdbc.queryForList(base + " WHERE channel_id=? ORDER BY id DESC", channelId);
    }

    @Transactional
    public ProviderCredential create(Map<String, Object> body) {
        requireEnabled();
        Long channelId = number(body.get("channelId"));
        if (channelId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelId 不能为空");
        return credentials.create(channelId, input(body));
    }

    @Transactional
    public List<ProviderCredential> bulkImport(Map<String, Object> body) {
        requireEnabled();
        Long channelId = number(body.get("channelId"));
        Object raw = body.get("accounts");
        if (channelId == null || !(raw instanceof List<?> accounts) || accounts.isEmpty() || accounts.size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量导入需包含 1–200 个账号");
        }
        return accounts.stream().map(value -> {
            if (!(value instanceof Map<?, ?> map)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号格式无效");
            @SuppressWarnings("unchecked") Map<String, Object> account = (Map<String, Object>) map;
            return credentials.create(channelId, input(account));
        }).toList();
    }

    public void setPaused(long id, boolean paused) {
        requireEnabled();
        int updated = jdbc.update("UPDATE provider_credentials SET enabled=?,temporary_unschedulable_until=?,updated_at=? WHERE id=?",
                !paused, paused ? LocalDateTime.of(9999, 12, 31, 23, 59) : null, LocalDateTime.now(), id);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上游账号不存在");
    }

    public void bindRoute(long id, Map<String, Object> body) {
        requireEnabled();
        Long channelId = number(body.get("channelId"));
        if (channelId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelId 不能为空");
        jdbc.update("INSERT INTO provider_account_route_bindings(credential_id,channel_id,model_pattern,protocol,priority,weight,enabled,created_at) VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE priority=VALUES(priority),weight=VALUES(weight),enabled=VALUES(enabled)",
                id, channelId, text(body.getOrDefault("modelPattern", "*")), text(body.getOrDefault("protocol", "*")),
                intNumber(body.get("priority"), 0), Math.max(1, intNumber(body.get("weight"), 100)), !Boolean.FALSE.equals(body.get("enabled")), LocalDateTime.now());
    }

    public void snapshotQuota(long id, Map<String, Object> body) {
        requireEnabled();
        jdbc.update("INSERT INTO provider_account_quota_snapshots(credential_id,quota_type,used_amount,limit_amount,remaining_amount,source,captured_at) VALUES (?,?,?,?,?,?,?)",
                id, text(body.getOrDefault("quotaType", "REQUEST")), longNumber(body.get("usedAmount"), 0),
                longNumber(body.get("limitAmount"), 0), longNumber(body.get("remainingAmount"), 0),
                text(body.getOrDefault("source", "MANUAL")), LocalDateTime.now());
    }

    public Map<String, Object> test(long id) {
        requireEnabled();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT channel_id FROM provider_credentials WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上游账号不存在");
        long channelId = ((Number) rows.get(0).get("channel_id")).longValue();
        return channelService.testCredential(channelId, id);
    }

    public Map<String, Object> oauthAuthorization(String platform) {
        if (!oauthEnabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "上游 OAuth 号池默认关闭；请先完成授权账号合规确认并启用功能开关");
        String normalized = text(platform).toUpperCase(Locale.ROOT);
        if (!List.of("OPENAI", "CODEX", "CLAUDE", "GEMINI", "ANTIGRAVITY", "GROK").contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂不支持该平台的 OAuth");
        return Map.of("platform", normalized, "status", "CONFIG_REQUIRED", "message", "需要为该平台配置标准 OAuth 客户端与回调地址后才能授权");
    }

    private ProviderCredential input(Map<String, Object> body) {
        return ProviderCredential.builder().name(text(body.get("name"))).secret(text(body.get("secret")))
                .platform(text(body.getOrDefault("platform", "COMPATIBLE"))).authType(text(body.getOrDefault("authType", "API_KEY")))
                .accountGroup(text(body.getOrDefault("accountGroup", "default"))).upstreamProxyId(number(body.get("upstreamProxyId")))
                .costMode(text(body.getOrDefault("costMode", "MODEL_MAPPING"))).periodCostAmount(longNumber(body.get("periodCostAmount"), 0))
                .costReliable(Boolean.TRUE.equals(body.get("costReliable"))).modelScope(text(body.get("modelScope")))
                .priority(intNumber(body.get("priority"), 0)).weight(intNumber(body.get("weight"), 100))
                .rpmLimit(intNumber(body.get("rpmLimit"), 0)).tpmLimit(intNumber(body.get("tpmLimit"), 0))
                .concurrencyLimit(intNumber(body.get("concurrencyLimit"), 0)).enabled(!Boolean.FALSE.equals(body.get("enabled"))).build();
    }
    private void requireEnabled() { if (!enabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "新账号池功能尚未启用"); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private int intNumber(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private long longNumber(Object value, long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
}
