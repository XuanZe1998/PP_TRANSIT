package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamOAuthSecurityTests {
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void credentialBundleIsAesGcmEncryptedAndRoundTripsWithoutLeakingTokens() {
        ChannelSecretService secrets = new ChannelSecretService(KEY);
        OAuthCredentialBundleService bundles = new OAuthCredentialBundleService(new ObjectMapper(), secrets);
        UpstreamOAuthProvider.OAuthToken token = new UpstreamOAuthProvider.OAuthToken(
                "access-secret", "refresh-secret", Instant.parse("2030-01-01T00:00:00Z"), "openid models", "Bearer", Map.of("tenant", "masked"));

        String stored = bundles.encrypt(token);

        assertThat(stored).startsWith("enc:v1:").doesNotContain("access-secret").doesNotContain("refresh-secret");
        assertThat(bundles.decrypt(stored)).isEqualTo(token);
    }

    @Test
    void pkceChallengeAndStateDigestAreDeterministicButDoNotRevealVerifier() {
        String verifier = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-._~";
        assertThat(UpstreamOAuthStateService.challenge(verifier))
                .isEqualTo("7SjWf5Of9twuEYtSj6OLPS7Cghq5qnlkvYWqFFxRvWo")
                .doesNotContain(verifier);
        assertThat(UpstreamOAuthStateService.sha256("one-time-state")).hasSize(64).doesNotContain("one-time-state");
    }

    @Test
    void purposeBoundCiphertextCannotBeDecryptedAsAnotherSecretType() {
        ChannelSecretService secrets = new ChannelSecretService(KEY);
        String encrypted = secrets.encryptForPurpose("oauth-client-config", "private-client-secret");
        assertThat(encrypted).doesNotContain("private-client-secret");
        assertThat(secrets.decryptForPurpose("oauth-client-config", encrypted)).isEqualTo("private-client-secret");
        assertThatThrownBy(() -> secrets.decryptForPurpose("provider-token", encrypted))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("secret purpose");
    }

    @Test
    void oauthStateCanOnlyBeConsumedOnce() {
        StateFixture fixture = stateFixture();
        UpstreamOAuthStateService.Created created = fixture.service().create(
                "CODEX", 7L, null, 3L, 11L, "primary", "gpt-*",
                "https://gateway.example/upstream/oauth/callback/codex", "POPUP");

        UpstreamOAuthStateService.Consumed consumed = fixture.service().consume("CODEX", created.state());

        assertThat(consumed.flowId()).isEqualTo(created.flowId());
        assertThat(consumed.verifier()).isEqualTo(created.verifier());
        assertThat(consumed.nonce()).isEqualTo(created.nonce());
        assertThatThrownBy(() -> fixture.service().consume("CODEX", created.state()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已过期、已消费或无效");
    }

    @Test
    void expiredOauthStateFailsClosed() {
        StateFixture fixture = stateFixture();
        UpstreamOAuthStateService.Created created = fixture.service().create(
                "CLAUDE", 9L, null, null, 12L, "default", "*",
                "https://gateway.example/upstream/oauth/callback/claude", "MANUAL");
        fixture.jdbc().update("UPDATE upstream_oauth_states SET expires_at=? WHERE flow_id=?",
                LocalDateTime.now().minusSeconds(1), created.flowId());

        assertThatThrownBy(() -> fixture.service().consume("CLAUDE", created.state()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已过期、已消费或无效");
    }

    private StateFixture stateFixture() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:oauth-state-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE upstream_oauth_states(
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, flow_id VARCHAR(64), state_hash VARCHAR(64), platform VARCHAR(32),
                  encrypted_code_verifier TEXT, encrypted_nonce TEXT, admin_user_id BIGINT,
                  reauthorize_credential_id BIGINT, upstream_proxy_id BIGINT, price_template_id BIGINT,
                  account_group VARCHAR(120), model_scope TEXT, redirect_uri VARCHAR(1000), callback_mode VARCHAR(32),
                  oauth_client_config_version BIGINT NOT NULL DEFAULT 0,
                  expires_at DATETIME, consumed_at DATETIME, created_at DATETIME
                )
                """);
        UpstreamOAuthStateService service = new UpstreamOAuthStateService(jdbc, new ChannelSecretService(KEY));
        return new StateFixture(jdbc, service);
    }

    private record StateFixture(JdbcTemplate jdbc, UpstreamOAuthStateService service) {}
}
