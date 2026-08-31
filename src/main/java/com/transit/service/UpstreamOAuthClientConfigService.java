package com.transit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.UpstreamOAuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Database-backed OAuth client configuration. The complete document is encrypted
 * as one authenticated envelope; only non-sensitive operational metadata is kept
 * in searchable columns.
 */
@Service
@RequiredArgsConstructor
public class UpstreamOAuthClientConfigService {
    private static final String SECRET_PURPOSE = "oauth-client-config";
    private static final int MAX_URI = 2_000;
    private static final int MAX_CLIENT = 2_000;
    private static final int MAX_SECRET = 8_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ChannelSecretService secrets;
    private final UpstreamOAuthProperties environment;
    private final Environment springEnvironment;
    private final ChannelUrlPolicy urlPolicy;
    private final UpstreamProxyHttpClientFactory httpClients;

    public List<Map<String, Object>> list() {
        return UpstreamOAuthProviderRegistry.OAUTH_PLATFORMS.stream().map(this::view).toList();
    }

    public boolean encryptionReady() { return secrets.isConfigured(); }

    public Map<String, Object> view(String rawPlatform) {
        String platform = platform(rawPlatform);
        Stored stored = find(platform);
        RuntimeConfig config = resolve(platform);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform);
        result.put("source", config.source());
        result.put("enabled", config.enabled());
        result.put("configured", config.configured());
        result.put("encryptionReady", secrets.isConfigured());
        result.put("clientIdPreview", stored == null ? mask(config.clientId()) : safe(stored.clientIdPreview()));
        result.put("hasClientSecret", stored == null ? text(config.clientSecret()) : stored.hasClientSecret());
        result.put("callbackBaseUrl", safe(config.callbackBaseUrl()));
        result.put("authorizationUri", safe(config.authorizationUri()));
        result.put("tokenUri", safe(config.tokenUri()));
        result.put("userinfoUri", safe(config.userinfoUri()));
        result.put("modelsUri", safe(config.modelsUri()));
        result.put("probeUri", safe(config.probeUri()));
        result.put("upstreamBaseUrl", safe(config.upstreamBaseUrl()));
        result.put("scopes", safe(config.scopes()));
        result.put("version", stored == null ? 0 : stored.version());
        result.put("lastTestStatus", stored == null ? "UNTESTED" : stored.lastTestStatus());
        result.put("lastTestedAt", stored == null ? null : stored.lastTestedAt());
        result.put("lastError", stored == null ? null : stored.lastErrorMasked());
        result.put("updatedAt", stored == null ? null : stored.updatedAt());
        return result;
    }

    @Transactional
    public Map<String, Object> save(String rawPlatform, long adminId, Map<String, Object> body) {
        if (!secrets.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "数据加密主密钥未配置，禁止写入 OAuth Client 配置");
        }
        String platform = platform(rawPlatform);
        Stored stored = find(platform);
        RuntimeConfig previous;
        if (stored == null) previous = resolve(platform);
        else {
            try { previous = decrypt(stored); }
            catch (RuntimeException corrupt) { previous = empty(platform, "DATABASE_CORRUPT"); }
        }
        long expectedVersion = number(body == null ? null : body.get("version"), 0);
        if (stored != null && expectedVersion != stored.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被其他管理员更新，请刷新后重试");
        }
        if (bool(body, "clearClientSecret", false) && body != null && text(Objects.toString(body.get("clientSecret"), ""))) {
            throw bad("不能同时填写和清除 Client Secret");
        }

        RuntimeConfig next = new RuntimeConfig(platform,
                bool(body, "enabled", stored == null ? false : stored.enabled()),
                replacement(body, "callbackBaseUrl", previous.callbackBaseUrl()),
                replacement(body, "clientId", previous.clientId()),
                bool(body, "clearClientSecret", false) ? "" : replacement(body, "clientSecret", previous.clientSecret()),
                replacement(body, "authorizationUri", previous.authorizationUri()),
                replacement(body, "tokenUri", previous.tokenUri()),
                replacement(body, "userinfoUri", previous.userinfoUri()),
                replacement(body, "modelsUri", previous.modelsUri()),
                replacement(body, "probeUri", previous.probeUri()),
                replacement(body, "upstreamBaseUrl", previous.upstreamBaseUrl()),
                replacement(body, "scopes", previous.scopes()), "DATABASE", stored == null ? 1 : stored.version() + 1);
        validate(next);
        String encrypted = secrets.encryptForPurpose(SECRET_PURPOSE, json(next.document()));
        String preview = mask(next.clientId());
        LocalDateTime now = LocalDateTime.now();
        if (stored == null) {
            try {
                jdbc.update("""
                        INSERT INTO upstream_oauth_client_configs
                        (platform,encrypted_config_bundle,client_id_preview,has_client_secret,enabled,config_version,
                         last_test_status,created_by,updated_by,created_at,updated_at)
                        VALUES (?,?,?,?,?,1,'UNTESTED',?,?,?,?)
                        """, platform, encrypted, preview, text(next.clientSecret()), next.enabled(), adminId, adminId, now, now);
            } catch (org.springframework.dao.DuplicateKeyException concurrent) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被其他管理员创建，请刷新后重试");
            }
        } else {
            int changed = jdbc.update("""
                    UPDATE upstream_oauth_client_configs
                    SET encrypted_config_bundle=?,client_id_preview=?,has_client_secret=?,enabled=?,
                        config_version=config_version+1,last_test_status='UNTESTED',last_tested_at=NULL,
                        last_error_masked=NULL,updated_by=?,updated_at=?
                    WHERE platform=? AND config_version=?
                    """, encrypted, preview, text(next.clientSecret()), next.enabled(), adminId, now, platform, expectedVersion);
            if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已发生并发更新，请刷新后重试");
            if (!Objects.equals(previous.document(), next.document())) invalidateExistingAccounts(platform);
        }
        applyPlatformState(platform, next.enabled());
        return view(platform);
    }

    /** Database rows take precedence. A disabled or corrupt row never falls back to environment secrets. */
    public RuntimeConfig resolve(String rawPlatform) {
        String platform = platform(rawPlatform);
        Stored stored = find(platform);
        if (stored != null) {
            try { return decrypt(stored); }
            catch (RuntimeException corrupt) { return empty(platform, "DATABASE_CORRUPT"); }
        }
        UpstreamOAuthProperties.Provider fallback = environment.provider(platform);
        if (fallback == null) return empty(platform, "NONE");
        return new RuntimeConfig(platform, legacyEnabled(platform), environment.getCallbackBaseUrl(),
                fallback.getClientId(), fallback.getClientSecret(), fallback.getAuthorizationUri(), fallback.getTokenUri(),
                fallback.getUserinfoUri(), fallback.getModelsUri(), fallback.getProbeUri(), fallback.getUpstreamBaseUrl(),
                fallback.getScopes(), fallback.configured() ? "ENVIRONMENT" : "NONE", 0);
    }

    public boolean anyDatabaseConfigEnabled() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM upstream_oauth_client_configs WHERE enabled=TRUE", Integer.class);
        return count != null && count > 0;
    }

    public Map<String, Object> test(String rawPlatform) {
        String platform = platform(rawPlatform);
        RuntimeConfig config = resolve(platform);
        if (!config.configured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "OAuth Client 配置不完整");
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("authorization", config.authorizationUri());
        endpoints.put("token", config.tokenUri());
        endpoints.put("userinfo", config.userinfoUri());
        endpoints.put("models", config.modelsUri());
        endpoints.put("probe", config.probeUri());
        endpoints.put("upstream", config.upstreamBaseUrl());
        List<Mono<Map.Entry<String, Map<String, Object>>>> checks = new ArrayList<>();
        endpoints.forEach((name, uri) -> checks.add(httpClients.client(null).method(HttpMethod.HEAD).uri(URI.create(uri))
                .exchangeToMono(response -> response.releaseBody().thenReturn(Map.entry(name,
                        Map.<String, Object>of("reachable", true, "status", response.statusCode().value(), "host", URI.create(uri).getHost()))))
                .timeout(Duration.ofSeconds(8))
                .onErrorResume(error -> Mono.just(Map.entry(name,
                        Map.<String, Object>of("reachable", false, "status", 0, "host", URI.create(uri).getHost()))))));
        Map<String, Map<String, Object>> results = Flux.merge(checks).collectMap(Map.Entry::getKey, Map.Entry::getValue).block();
        boolean healthy = results != null && results.size() == endpoints.size()
                && results.values().stream().allMatch(value -> Boolean.TRUE.equals(value.get("reachable")));
        Stored stored = find(platform);
        if (stored != null) jdbc.update("""
                UPDATE upstream_oauth_client_configs SET last_test_status=?,last_tested_at=?,last_error_masked=?,updated_at=?
                WHERE platform=?
                """, healthy ? "REACHABLE" : "FAILED", LocalDateTime.now(),
                healthy ? null : "One or more configured endpoints were unreachable", LocalDateTime.now(), platform);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("platform", platform); response.put("status", healthy ? "REACHABLE" : "FAILED");
        response.put("checks", results == null ? Map.of() : results);
        response.put("note", "仅验证后端到端点的 DNS/TLS/HTTP 可达性，不交换 Token");
        return response;
    }

    private RuntimeConfig decrypt(Stored stored) {
        if (!secrets.isEncryptedForPurpose(SECRET_PURPOSE, stored.encryptedBundle())) throw new IllegalStateException("OAuth client configuration has the wrong encryption purpose");
        try {
            ConfigDocument value = objectMapper.readValue(secrets.decryptForPurpose(SECRET_PURPOSE, stored.encryptedBundle()), ConfigDocument.class);
            return new RuntimeConfig(stored.platform(), stored.enabled(), value.callbackBaseUrl(), value.clientId(),
                    value.clientSecret(), value.authorizationUri(), value.tokenUri(), value.userinfoUri(), value.modelsUri(),
                    value.probeUri(), value.upstreamBaseUrl(), value.scopes(), "DATABASE", stored.version());
        } catch (JsonProcessingException error) { throw new IllegalStateException("Encrypted OAuth client configuration is invalid", error); }
    }

    private Stored find(String platform) {
        return jdbc.query("""
                SELECT platform,encrypted_config_bundle,client_id_preview,has_client_secret,enabled,config_version,
                       last_test_status,last_tested_at,last_error_masked,updated_at
                FROM upstream_oauth_client_configs WHERE platform=?
                """, (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                rs.getBoolean(5), rs.getLong(6), rs.getString(7),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toLocalDateTime(), rs.getString(9),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toLocalDateTime()), platform).stream().findFirst().orElse(null);
    }

    private void invalidateExistingAccounts(String platform) {
        if (!tableExists("provider_credentials")) return;
        if (tableExists("provider_account_events")) {
            jdbc.update("""
                    INSERT INTO provider_account_events
                    (credential_id,event_type,error_class,retryable,detail_masked,created_at)
                    SELECT id,'CLIENT_CONFIG_CHANGED','AUTH',FALSE,
                           'OAuth Client configuration changed; reauthorization required',?
                    FROM provider_credentials WHERE platform=? AND auth_type='OAUTH'
                    """, LocalDateTime.now(), platform);
        }
        jdbc.update("""
                UPDATE provider_credentials
                SET enabled=FALSE,health_status='REAUTH_REQUIRED',entitlement_status='UNKNOWN',
                    last_error_class='AUTH',last_error='OAuth Client configuration changed; reauthorization required',updated_at=?
                WHERE platform=? AND auth_type='OAUTH'
                """, LocalDateTime.now(), platform);
    }

    private void applyPlatformState(String platform, boolean enabled) {
        if (!tableExists("provider_credentials")) return;
        if (enabled) {
            jdbc.update("""
                    UPDATE provider_credentials
                    SET temporary_unschedulable_until=NULL,last_error_class=NULL,last_error=NULL,updated_at=?
                    WHERE platform=? AND auth_type='OAUTH' AND last_error_class='PLATFORM_DISABLED'
                    """, LocalDateTime.now(), platform);
        } else {
            jdbc.update("""
                    UPDATE provider_credentials
                    SET temporary_unschedulable_until=?,last_error_class='PLATFORM_DISABLED',
                        last_error='OAuth platform disabled by administrator',updated_at=?
                    WHERE platform=? AND auth_type='OAUTH' AND enabled=TRUE
                    """, LocalDateTime.of(9999, 12, 31, 23, 59), LocalDateTime.now(), platform);
        }
    }

    private boolean tableExists(String table) {
        Boolean exists = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (java.sql.ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, null, new String[]{"TABLE"})) {
                while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
            }
            return false;
        });
        return Boolean.TRUE.equals(exists);
    }

    private void validate(RuntimeConfig config) {
        required(config.callbackBaseUrl(), "callbackBaseUrl", MAX_URI);
        required(config.clientId(), "clientId", MAX_CLIENT);
        if (config.clientSecret() != null && config.clientSecret().length() > MAX_SECRET) throw bad("clientSecret 过长");
        required(config.authorizationUri(), "authorizationUri", MAX_URI);
        required(config.tokenUri(), "tokenUri", MAX_URI);
        required(config.userinfoUri(), "userinfoUri", MAX_URI);
        required(config.modelsUri(), "modelsUri", MAX_URI);
        required(config.probeUri(), "probeUri", MAX_URI);
        required(config.upstreamBaseUrl(), "upstreamBaseUrl", MAX_URI);
        if (config.scopes() != null && config.scopes().length() > 2_000) throw bad("scopes 过长");
        for (String uri : List.of(config.authorizationUri(), config.tokenUri(), config.userinfoUri(),
                config.modelsUri(), config.probeUri(), config.upstreamBaseUrl())) validateEndpoint(uri);
        validateCallbackBase(config.callbackBaseUrl());
    }

    private void validateEndpoint(String value) {
        urlPolicy.validate(value);
        URI uri = URI.create(value);
        if (uri.getQuery() != null || uri.getFragment() != null) throw bad("OAuth 端点不允许携带 query 或 fragment");
    }

    private void validateCallbackBase(String value) {
        urlPolicy.validate(value);
        URI uri = URI.create(value);
        if ((uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw bad("callbackBaseUrl 必须是不含路径的公网后端根地址");
        }
    }

    private void required(String value, String field, int max) {
        if (!text(value)) throw bad(field + " 不能为空");
        if (value.length() > max) throw bad(field + " 过长");
    }
    private RuntimeConfig empty(String platform, String source) { return new RuntimeConfig(platform, false, "", "", "", "", "", "", "", "", "", "", source, 0); }
    private String replacement(Map<String, Object> body, String key, String previous) { Object raw = body == null ? null : body.get(key); String value = raw == null ? "" : String.valueOf(raw).trim(); return value.isBlank() ? safe(previous) : value; }
    private boolean bool(Map<String, Object> body, String key, boolean fallback) { Object value = body == null ? null : body.get(key); return value instanceof Boolean result ? result : fallback; }
    private long number(Object value, long fallback) { return value instanceof Number number ? number.longValue() : fallback; }
    private String platform(String value) { String normalized = safe(value).toUpperCase(Locale.ROOT); if (!UpstreamOAuthProviderRegistry.OAUTH_PLATFORMS.contains(normalized)) throw bad("不支持的 OAuth 平台"); return normalized; }
    private boolean legacyEnabled(String platform) {
        return springEnvironment.getProperty("features.linknux.provider-accounts.enabled", Boolean.class, false)
                && springEnvironment.getProperty("features.linknux.provider-oauth.enabled", Boolean.class, false)
                && springEnvironment.getProperty("features.linknux.provider-oauth.platforms."
                + platform.toLowerCase(Locale.ROOT), Boolean.class, false);
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException error) { throw new IllegalStateException(error); } }
    private String mask(String value) { if (!text(value)) return ""; if (value.length() <= 8) return "****"; return value.substring(0, 4) + "****" + value.substring(value.length() - 4); }
    private String safe(String value) { return value == null ? "" : value; }
    private boolean text(String value) { return value != null && !value.isBlank(); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record RuntimeConfig(String platform, boolean enabled, String callbackBaseUrl, String clientId,
                                String clientSecret, String authorizationUri, String tokenUri, String userinfoUri,
                                String modelsUri, String probeUri, String upstreamBaseUrl, String scopes,
                                String source, long version) {
        public boolean configured() { return text(clientId) && text(callbackBaseUrl) && text(authorizationUri)
                    && text(tokenUri) && text(userinfoUri) && text(modelsUri) && text(probeUri) && text(upstreamBaseUrl); }
        private static boolean text(String value) { return value != null && !value.isBlank(); }
        ConfigDocument document() { return new ConfigDocument(callbackBaseUrl, clientId, clientSecret, authorizationUri,
                    tokenUri, userinfoUri, modelsUri, probeUri, upstreamBaseUrl, scopes); }
    }
    public record ConfigDocument(String callbackBaseUrl, String clientId, String clientSecret,
                                 String authorizationUri, String tokenUri, String userinfoUri,
                                 String modelsUri, String probeUri, String upstreamBaseUrl, String scopes) {}
    private record Stored(String platform, String encryptedBundle, String clientIdPreview, boolean hasClientSecret,
                          boolean enabled, long version, String lastTestStatus, LocalDateTime lastTestedAt,
                          String lastErrorMasked, LocalDateTime updatedAt) {}
}
