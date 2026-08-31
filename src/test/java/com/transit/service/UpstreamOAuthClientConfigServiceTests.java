package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.UpstreamOAuthProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class UpstreamOAuthClientConfigServiceTests {
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private JdbcTemplate jdbc;
    private UpstreamOAuthClientConfigService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:oauth-client-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE upstream_oauth_client_configs(
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, platform VARCHAR(32) NOT NULL UNIQUE,
                  encrypted_config_bundle TEXT NOT NULL, client_id_preview VARCHAR(160),
                  has_client_secret BOOLEAN NOT NULL, enabled BOOLEAN NOT NULL, config_version BIGINT NOT NULL,
                  last_test_status VARCHAR(32), last_tested_at DATETIME, last_error_masked VARCHAR(500),
                  created_by BIGINT, updated_by BIGINT, created_at DATETIME, updated_at DATETIME
                )
                """);
        jdbc.execute("""
                CREATE TABLE provider_credentials(
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, platform VARCHAR(32), auth_type VARCHAR(32), enabled BOOLEAN,
                  health_status VARCHAR(32), entitlement_status VARCHAR(32), temporary_unschedulable_until DATETIME,
                  last_error_class VARCHAR(32), last_error VARCHAR(500), updated_at DATETIME
                )
                """);
        jdbc.execute("""
                CREATE TABLE provider_account_events(
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, credential_id BIGINT, event_type VARCHAR(48),
                  error_class VARCHAR(32), retryable BOOLEAN, detail_masked VARCHAR(1000), created_at DATETIME
                )
                """);
        UpstreamOAuthProperties properties = new UpstreamOAuthProperties();
        properties.setProviders(new LinkedHashMap<>());
        service = new UpstreamOAuthClientConfigService(jdbc, new ObjectMapper(), new ChannelSecretService(KEY),
                properties, new MockEnvironment(), new ChannelUrlPolicy(true), mock(UpstreamProxyHttpClientFactory.class));
    }

    @Test
    void savesOneAuthenticatedCiphertextAndNeverReturnsClientCredentials() {
        Map<String, Object> saved = service.save("codex", 7L, request(true));

        String stored = jdbc.queryForObject("SELECT encrypted_config_bundle FROM upstream_oauth_client_configs", String.class);
        assertThat(stored).startsWith("enc:v2:oauth-client-config:").doesNotContain("private-client-id").doesNotContain("private-secret");
        assertThat(saved).doesNotContainKeys("clientId", "clientSecret", "encryptedConfigBundle");
        assertThat(saved.get("clientIdPreview")).isEqualTo("priv****t-id");
        assertThat(saved.get("hasClientSecret")).isEqualTo(true);
        assertThat(saved.get("source")).isEqualTo("DATABASE");

        UpstreamOAuthClientConfigService.RuntimeConfig runtime = service.resolve("CODEX");
        assertThat(runtime.clientId()).isEqualTo("private-client-id");
        assertThat(runtime.clientSecret()).isEqualTo("private-secret");
        assertThat(runtime.enabled()).isTrue();
    }

    @Test
    void blankCredentialFieldsPreserveSecretsAndOptimisticVersionRejectsStaleWriter() {
        service.save("CODEX", 7L, request(true));
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("version", 1); partial.put("enabled", false);
        partial.put("clientId", ""); partial.put("clientSecret", "");

        Map<String, Object> updated = service.save("CODEX", 8L, partial);

        assertThat(updated.get("version")).isEqualTo(2L);
        assertThat(service.resolve("CODEX").clientSecret()).isEqualTo("private-secret");
        assertThat(service.resolve("CODEX").clientId()).isEqualTo("private-client-id");
        assertThat(service.resolve("CODEX").enabled()).isFalse();
        assertThatThrownBy(() -> service.save("CODEX", 9L, partial))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("其他管理员");
    }

    @Test
    void databaseDisableIsFailClosedAndDoesNotFallBack() {
        service.save("CLAUDE", 7L, request(false));
        UpstreamOAuthClientConfigService.RuntimeConfig resolved = service.resolve("CLAUDE");
        assertThat(resolved.source()).isEqualTo("DATABASE");
        assertThat(resolved.configured()).isTrue();
        assertThat(resolved.enabled()).isFalse();
    }

    @Test
    void changingClientDocumentInvalidatesExistingOAuthAccounts() {
        service.save("GEMINI", 7L, request(true));
        jdbc.update("""
                INSERT INTO provider_credentials(platform,auth_type,enabled,health_status,entitlement_status,updated_at)
                VALUES ('GEMINI','OAUTH',TRUE,'HEALTHY','ACTIVE',CURRENT_TIMESTAMP)
                """);
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("version", 1); changed.put("enabled", true);
        changed.put("tokenUri", "http://127.0.0.1:9011/oauth/token");

        service.save("GEMINI", 8L, changed);

        Map<String, Object> account = jdbc.queryForMap("SELECT * FROM provider_credentials WHERE platform='GEMINI'");
        assertThat(account.get("ENABLED")).isEqualTo(false);
        assertThat(account.get("HEALTH_STATUS")).isEqualTo("REAUTH_REQUIRED");
        assertThat(account.get("ENTITLEMENT_STATUS")).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_account_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void providerReadsRotatedDatabaseConfigurationWithoutRestart() {
        service.save("CODEX", 7L, request(true));
        ConfiguredUpstreamOAuthProvider provider = new ConfiguredUpstreamOAuthProvider(
                "CODEX", service, mock(UpstreamProxyHttpClientFactory.class));
        assertThat(provider.authorizationUrl("state", "nonce", "challenge", "http://127.0.0.1:8089/callback"))
                .contains("client_id=private-client-id");

        Map<String, Object> rotated = new LinkedHashMap<>();
        rotated.put("version", 1); rotated.put("enabled", true); rotated.put("clientId", "rotated-client-id");
        service.save("CODEX", 8L, rotated);

        assertThat(provider.authorizationUrl("state", "nonce", "challenge", "http://127.0.0.1:8089/callback"))
                .contains("client_id=rotated-client-id").doesNotContain("private-client-id");
    }

    private Map<String, Object> request(boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("callbackBaseUrl", "http://127.0.0.1:8089");
        body.put("clientId", "private-client-id");
        body.put("clientSecret", "private-secret");
        body.put("authorizationUri", "http://127.0.0.1:9010/oauth/authorize");
        body.put("tokenUri", "http://127.0.0.1:9010/oauth/token");
        body.put("userinfoUri", "http://127.0.0.1:9010/oauth/userinfo");
        body.put("modelsUri", "http://127.0.0.1:9010/v1/models");
        body.put("probeUri", "http://127.0.0.1:9010/v1/probe");
        body.put("upstreamBaseUrl", "http://127.0.0.1:9010");
        body.put("scopes", "openid offline_access");
        return body;
    }
}
