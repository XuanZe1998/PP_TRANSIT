package com.transit.service;

import com.transit.mapper.ProviderCredentialMapper;
import com.transit.model.Channel;
import com.transit.model.ProviderAuthContext;
import com.transit.model.ProviderCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProviderOAuthTokenService {
    private final ProviderCredentialMapper mapper;
    private final OAuthCredentialBundleService bundles;
    private final UpstreamOAuthProviderRegistry registry;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    @Autowired(required = false)
    private UpstreamOAuthClientConfigService clientConfigs;

    public ProviderAuthContext context(ProviderCredential account, Channel channel) {
        requirePlatformEnabled(account.getPlatform());
        ProviderCredential current = account;
        if (account.getOauthExpiresAt() == null || !account.getOauthExpiresAt().isAfter(LocalDateTime.now().plusMinutes(5))) current = refresh(account, false);
        UpstreamOAuthProvider.OAuthToken token = bundles.decrypt(current.getCredentialBundle());
        if (!"ACTIVE".equalsIgnoreCase(current.getEntitlementStatus())) throw unavailable("OAuth entitlement 不可用，需要重新授权");
        return new ProviderAuthContext(current.getId(), current.getPlatform(), "OAUTH", null, token.accessToken(),
                channel.getBaseUrl(), current.getUpstreamProxyId(), current.getEntitlementStatus(), token.metadata());
    }

    public ProviderCredential forceRefresh(long credentialId) {
        ProviderCredential current = mapper.selectById(credentialId);
        if (current == null || !"OAUTH".equalsIgnoreCase(current.getAuthType())) throw unavailable("OAuth 账号不存在");
        requirePlatformEnabled(current.getPlatform());
        return refresh(current, true);
    }

    private void requirePlatformEnabled(String platform) {
        if (clientConfigs == null) return;
        UpstreamOAuthClientConfigService.RuntimeConfig config = clientConfigs.resolve(platform);
        if (!config.enabled() || !config.configured()) throw unavailable(platform + " OAuth Client 配置已禁用或不完整");
    }

    private ProviderCredential refresh(ProviderCredential stale, boolean force) {
        String key = "gateway:oauth-refresh:" + stale.getId(), owner = UUID.randomUUID().toString();
        Boolean locked;
        try { locked = redis.opsForValue().setIfAbsent(key, owner, Duration.ofSeconds(30)); }
        catch (RuntimeException exception) { throw unavailable("Redis 刷新锁不可用；为保护 refresh token 已拒绝刷新"); }
        if (!Boolean.TRUE.equals(locked)) {
            ProviderCredential winner = mapper.selectById(stale.getId());
            if (winner != null && winner.getTokenVersion() > stale.getTokenVersion()) return winner;
            throw unavailable("OAuth Token 正由其他实例刷新，请稍后重试");
        }
        try {
            ProviderCredential current = mapper.selectById(stale.getId());
            if (!force && current.getOauthExpiresAt() != null && current.getOauthExpiresAt().isAfter(LocalDateTime.now().plusMinutes(5))) return current;
            UpstreamOAuthProvider.OAuthToken old = bundles.decrypt(current.getCredentialBundle());
            if (old.refreshToken() == null || old.refreshToken().isBlank()) return pause(current, "缺少 refresh token，需要重新授权");
            try {
                UpstreamOAuthProvider.OAuthToken refreshed = registry.require(current.getPlatform()).refresh(old.refreshToken(), current.getUpstreamProxyId());
                int updated = mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ProviderCredential>()
                        .eq("id", current.getId()).eq("token_version", current.getTokenVersion())
                        .set("encrypted_credential_bundle", bundles.encrypt(refreshed))
                        .set("oauth_expires_at", LocalDateTime.ofInstant(refreshed.expiresAt(), ZoneId.systemDefault()))
                        .set("authorization_scope", refreshed.scope()).set("last_refreshed_at", LocalDateTime.now())
                        .set("refresh_failure_count", 0).set("token_version", current.getTokenVersion() + 1)
                        .set("last_error", null));
                if (updated != 1) {
                    ProviderCredential winner = mapper.selectById(current.getId());
                    if (winner != null && winner.getTokenVersion() > current.getTokenVersion()) return winner;
                    throw unavailable("OAuth Token CAS 刷新冲突");
                }
                event(current.getId(), "TOKEN_REFRESHED", null, false, "OAuth access token refreshed using CAS");
                return mapper.selectById(current.getId());
            } catch (ResponseStatusException exception) { throw exception; }
            catch (RuntimeException exception) {
                int failures = current.getRefreshFailureCount() + 1;
                mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getId, current.getId()).set(ProviderCredential::getRefreshFailureCount, failures)
                        .set(ProviderCredential::getLastErrorClass, "AUTH").set(ProviderCredential::getLastError, "OAuth refresh failed (details redacted)")
                        .set(ProviderCredential::isEnabled, failures < 3).set(ProviderCredential::getHealthStatus, failures >= 3 ? "DISABLED" : "DEGRADED"));
                event(current.getId(), "TOKEN_REFRESH_FAILED", "AUTH", failures < 3, "OAuth refresh failed; provider detail redacted");
                throw unavailable(failures >= 3 ? "连续刷新失败，账号已暂停并需要重新授权" : "OAuth Token 刷新失败");
            }
        } finally { unlock(key, owner); }
    }

    private ProviderCredential pause(ProviderCredential account, String reason) {
        account.setEnabled(false); account.setHealthStatus("DISABLED"); account.setLastError(reason); mapper.updateById(account); throw unavailable(reason);
    }
    private void unlock(String key, String owner) {
        try { redis.execute(new DefaultRedisScript<>("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class), java.util.List.of(key), owner); }
        catch (RuntimeException ignored) { }
    }
    private ResponseStatusException unavailable(String message) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message); }
    private void event(long id, String type, String errorClass, boolean retryable, String detail) {
        jdbc.update("INSERT INTO provider_account_events(credential_id,event_type,error_class,retryable,detail_masked,created_at) VALUES (?,?,?,?,?,?)",
                id, type, errorClass, retryable, detail, LocalDateTime.now());
    }
}
