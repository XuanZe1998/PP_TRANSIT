package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AiApiBankAccountSessionServiceTests {
    private JdbcTemplate jdbc;
    private ChannelSecretService secrets;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:aiapibank_session;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE upstream_sites(id BIGINT PRIMARY KEY,adapter VARCHAR(40) NOT NULL)");
        jdbc.execute("INSERT INTO upstream_sites(id,adapter) VALUES(7,'aiapibank')");
        jdbc.execute("""
                CREATE TABLE gateway_account_credentials(
                  site_id BIGINT PRIMARY KEY,access_token TEXT NOT NULL,
                  encrypted_refresh_token TEXT NULL,encrypted_pending_token TEXT NULL,
                  account_email_preview VARCHAR(190),auth_status VARCHAR(32) NOT NULL,
                  access_expires_at TIMESTAMP NULL,last_authenticated_at TIMESTAMP NULL,
                  last_error VARCHAR(1000),updated_at TIMESTAMP NOT NULL)
                """);
        secrets = new ChannelSecretService(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    void storesOnlyEncryptedRefreshTokenAndRefreshesAfterRestart() {
        AtomicInteger refreshes = new AtomicInteger();
        WebClient client = client(request -> {
            String path = request.url().getPath();
            if (path.endsWith("/auth/login")) {
                return "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":120}";
            }
            if (path.endsWith("/auth/refresh")) {
                refreshes.incrementAndGet();
                return "{\"code\":0,\"data\":{\"access_token\":\"access-2\",\"refresh_token\":\"refresh-2\",\"expires_in\":900}}";
            }
            throw new AssertionError(path);
        });
        AiApiBankAccountSessionService service = service(client);

        var result = service.authorize(7, "owner@example.com", "private-password", null);

        assertThat(result.authenticated()).isTrue();
        String encrypted = jdbc.queryForObject(
                "SELECT encrypted_refresh_token FROM gateway_account_credentials WHERE site_id=7", String.class);
        assertThat(encrypted).startsWith("enc:v2:aiapibank-refresh:")
                .doesNotContain("refresh-1", "private-password");
        assertThat(jdbc.queryForObject(
                "SELECT account_email_preview FROM gateway_account_credentials WHERE site_id=7", String.class))
                .isEqualTo("o***@example.com");
        assertThat(jdbc.queryForObject(
                "SELECT access_token FROM gateway_account_credentials WHERE site_id=7", String.class)).isEmpty();

        AiApiBankAccountSessionService restarted = service(client);
        assertThat(restarted.accessToken(7L)).isEqualTo("access-2");
        assertThat(refreshes).hasValue(1);
        String rotated = jdbc.queryForObject(
                "SELECT encrypted_refresh_token FROM gateway_account_credentials WHERE site_id=7", String.class);
        assertThat(secrets.decryptForPurpose("aiapibank-refresh", rotated)).isEqualTo("refresh-2");
    }

    @Test
    void keepsTwoFactorChallengeEncryptedUntilTotpCompletesAuthorization() {
        WebClient client = client(request -> {
            String path = request.url().getPath();
            if (path.endsWith("/auth/login")) {
                return "{\"requires_2fa\":true,\"temp_token\":\"pending-secret\"}";
            }
            if (path.endsWith("/auth/login/2fa")) {
                return "{\"access_token\":\"access-2fa\",\"refresh_token\":\"refresh-2fa\",\"expires_in\":600}";
            }
            throw new AssertionError(path);
        });
        AiApiBankAccountSessionService service = service(client);

        var pending = service.authorize(7, "owner@example.com", "private-password", null);
        assertThat(pending.requiresTwoFactor()).isTrue();
        String encryptedPending = jdbc.queryForObject(
                "SELECT encrypted_pending_token FROM gateway_account_credentials WHERE site_id=7", String.class);
        assertThat(encryptedPending).startsWith("enc:v2:aiapibank-login-challenge:")
                .doesNotContain("pending-secret", "private-password");

        var ready = service.authorize(7, null, null, "123456");
        assertThat(ready.authenticated()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT encrypted_pending_token FROM gateway_account_credentials WHERE site_id=7", String.class)).isEmpty();
        assertThat(service.accessToken(7L)).isEqualTo("access-2fa");
    }

    private AiApiBankAccountSessionService service(WebClient client) {
        var service = new AiApiBankAccountSessionService(client, json, jdbc, secrets);
        ReflectionTestUtils.setField(service, "baseUrl", "https://aiapibank.test");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        return service;
    }

    private WebClient client(java.util.function.Function<org.springframework.web.reactive.function.client.ClientRequest, String> response) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body(response.apply(request)).build())).build();
    }
}
