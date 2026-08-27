package com.transit;

import com.transit.service.SecretHashService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityBoundaryIntegrationTests {

    private static final String USER_PASSWORD = "StrongPass123";
    private static final String ADMIN_PASSWORD = "CommercialAdminPass123";

    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private SecretHashService secretHashService;

    private String userToken;
    private String userRefreshToken;
    private Long userId;
    private String username;
    private String adminToken;
    private String adminUsername;

    @BeforeAll
    void createUserAndAdministratorPrincipals() {
        username = "security-user-" + UUID.randomUUID() + "@example.com";
        Map<String, Object> userSession = register(username, USER_PASSWORD);
        userToken = userSession.get("access_token").toString();
        userRefreshToken = userSession.get("refresh_token").toString();
        userId = ((Number) userSession.get("user_id")).longValue();

        adminUsername = "security-admin-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO admins(username, password, display_name, enabled, created_at)
                VALUES (?, ?, 'Security Test Administrator', TRUE, ?)
                """, adminUsername, passwordEncoder.encode(ADMIN_PASSWORD), LocalDateTime.now());
        Map<String, Object> adminSession = client.post()
                .uri("/admin/auth/login")
                .bodyValue(Map.of("username", adminUsername, "password", ADMIN_PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(adminSession).isNotNull();
        adminToken = adminSession.get("access_token").toString();
    }

    @ParameterizedTest(name = "anonymous caller may use {0}")
    @ValueSource(strings = {
            "/public/models",
            "/public/other-services",
            "/platform/user/docs",
            "/actuator/health",
            "/actuator/info"
    })
    void documentedPublicRoutesRemainAnonymous(String path) {
        client.get().uri(path).exchange().expectStatus().isOk()
                .expectBody().consumeWith(ignored -> { });
    }

    @ParameterizedTest(name = "anonymous caller is rejected by {0}")
    @ValueSource(strings = {
            "/user/profile",
            "/user/tokens",
            "/platform/user/wallet",
            "/service-orders",
            "/service-orders/admin/orders",
            "/admin/api/dashboard",
            "/platform/admin/dashboard",
            "/channels",
            "/tokens",
            "/mappings",
            "/actuator/prometheus"
    })
    void protectedRoutesRejectAnonymousCallers(String path) {
        client.get().uri(path).exchange().expectStatus().isUnauthorized()
                .expectBody().consumeWith(ignored -> { });
    }

    @ParameterizedTest(name = "ordinary user may use {0}")
    @ValueSource(strings = {
            "/user/profile",
            "/user/tokens",
            "/user/logs",
            "/user/stats",
            "/user/usage/analytics",
            "/service-orders"
    })
    void ordinaryUsersCanUseUserRoutes(String path) {
        client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .exchange().expectStatus().isOk()
                .expectBody().consumeWith(ignored -> { });
    }

    @Test
    void playgroundRejectsClientSuppliedBillingOverrides() {
        client.post().uri("/user/playground")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .bodyValue(Map.of(
                        "tokenId", 1,
                        "model", "public-model",
                        "prompt", "hello",
                        "inputPricePerMillion", 0,
                        "totalAmount", 0))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().consumeWith(ignored -> { });
    }

    @ParameterizedTest(name = "ordinary user is forbidden from {0}")
    @ValueSource(strings = {
            "/admin/api/dashboard",
            "/admin/api/finance/summary",
            "/admin/api/finance/transactions",
            "/admin/api/finance/redeem-codes",
            "/admin/api/finance/recharge-plans",
            "/platform/admin/dashboard",
            "/channels",
            "/tokens",
            "/mappings",
            "/service-orders/admin/orders",
            "/actuator/prometheus"
    })
    void ordinaryUsersCannotCrossTheAdministratorBoundary(String path) {
        client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .exchange().expectStatus().isForbidden()
                .expectBody().consumeWith(ignored -> { });
    }

    @ParameterizedTest(name = "administrator may use {0}")
    @ValueSource(strings = {
            "/admin/api/dashboard",
            "/admin/api/finance/summary",
            "/admin/api/finance/transactions",
            "/admin/api/finance/redeem-codes",
            "/admin/api/finance/recharge-plans",
            "/platform/admin/dashboard",
            "/channels",
            "/tokens",
            "/mappings",
            "/service-orders/admin/orders",
            "/actuator/prometheus"
    })
    void administratorsCanUseAdministratorRoutes(String path) {
        client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .exchange().expectStatus().isOk()
                .expectBody().consumeWith(ignored -> { });
    }

    @ParameterizedTest(name = "removed legacy route is inaccessible: {0}")
    @ValueSource(strings = {
            "/plus/products",
            "/plus/orders",
            "/plus/admin/products",
            "/service-07/order",
            "/payment-service/api/regions"
    })
    void removedLegacyRoutesAreInaccessible(String path) {
        client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void passwordsAndSessionCredentialsAreHashedAtRestAndNeverReturnedByProfiles() {
        String password = jdbcTemplate.queryForObject(
                "SELECT password FROM users WHERE id = ?", String.class, userId);
        assertThat(password).startsWith("$2").doesNotContain(USER_PASSWORD);

        List<Map<String, Object>> sessions = jdbcTemplate.queryForList(
                "SELECT access_token, refresh_token FROM oauth_tokens WHERE user_id = ?", userId);
        assertThat(sessions).isNotEmpty().allSatisfy(session -> {
            assertThat(session.get("access_token").toString()).startsWith("sha256:");
            assertThat(session.get("refresh_token").toString()).startsWith("sha256:");
        });
        assertThat(sessions).anySatisfy(session -> {
            assertThat(session.get("access_token")).isEqualTo(secretHashService.hash(userToken));
            assertThat(session.get("refresh_token")).isEqualTo(secretHashService.hash(userRefreshToken));
        });

        String storedAdminToken = jdbcTemplate.queryForObject(
                "SELECT access_token FROM admins WHERE username = ?", String.class, adminUsername);
        assertThat(storedAdminToken).isEqualTo(secretHashService.hash(adminToken));

        String profile = client.get().uri("/user/profile")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(profile)
                .doesNotContain("password")
                .doesNotContain(userToken)
                .doesNotContain(userRefreshToken);

        String users = client.get().uri("/admin/api/users")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(users).doesNotContain("password").doesNotContain(USER_PASSWORD);
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiKeySecretIsReturnedOnceWhileOnlyItsDigestIsPersisted() {
        Map<String, Object> created = client.post().uri("/user/tokens")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .bodyValue(Map.of(
                        "name", "one-time-key-" + UUID.randomUUID(),
                        "totalQuota", 5_000,
                        "allowAllModels", true,
                        "enabled", true,
                        "description", "security regression test"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(created).isNotNull();
        String secret = created.get("secret").toString();
        Long tokenId = ((Number) created.get("id")).longValue();
        assertThat(secret).startsWith("sk-at-");
        assertThat(created).containsEntry("oneTimeSecret", true)
                .doesNotContainKeys("key", "accessToken", "refreshToken");

        String stored = jdbcTemplate.queryForObject(
                "SELECT `key` FROM tokens WHERE id = ?", String.class, tokenId);
        assertThat(stored).isEqualTo(secretHashService.hash(secret)).doesNotContain(secret);

        List<Map<String, Object>> listed = client.get().uri("/user/tokens")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .exchange().expectStatus().isOk()
                .expectBody(List.class).returnResult().getResponseBody();
        assertThat(listed).isNotNull();
        Map<String, Object> listedKey = listed.stream()
                .filter(item -> tokenId.equals(((Number) item.get("id")).longValue()))
                .findFirst().orElseThrow();
        assertThat(listedKey).doesNotContainKeys("secret", "key", "accessToken", "refreshToken");
        assertThat(listedKey.get("keyPreview").toString()).doesNotContain(secret);

        client.get().uri("/v1/models")
                .header(HttpHeaders.AUTHORIZATION, bearer(secret))
                .exchange().expectStatus().isOk()
                .expectBody().consumeWith(ignored -> { });

        Map<String, Object> updated = client.put().uri("/user/tokens/{id}", tokenId)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .bodyValue(Map.of("name", "renamed key", "totalQuota", 6_000, "enabled", true))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(updated).isNotNull().doesNotContainKeys("secret", "key");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT `key` FROM tokens WHERE id = ?", String.class, tokenId)).isEqualTo(stored);

        client.delete().uri("/user/tokens/{id}", tokenId)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .exchange().expectStatus().isOk()
                .expectBody().consumeWith(ignored -> { });
        client.get().uri("/v1/models")
                .header(HttpHeaders.AUTHORIZATION, bearer(secret))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().consumeWith(ignored -> { });
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothAdministratorApiKeyAliasesUseTheSameOneTimeSecretContract() {
        for (String endpoint : List.of("/tokens", "/admin/api/tokens")) {
            Map<String, Object> created = client.post().uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                    .bodyValue(Map.of(
                            "userId", userId,
                            "name", "admin-created-" + UUID.randomUUID(),
                            "totalQuota", 1_000,
                            "allowAllModels", true,
                            "enabled", true))
                    .exchange().expectStatus().isOk()
                    .expectBody(Map.class).returnResult().getResponseBody();
            assertThat(created).isNotNull();
            String secret = created.get("secret").toString();
            Long id = ((Number) created.get("id")).longValue();
            assertThat(created).containsEntry("oneTimeSecret", true)
                    .doesNotContainKeys("key", "accessToken", "refreshToken");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT `key` FROM tokens WHERE id = ?", String.class, id))
                    .isEqualTo(secretHashService.hash(secret));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void upstreamChannelSecretsAreMaskedInEveryAdministrativeResponse() {
        String upstreamSecret = "upstream-secret-" + UUID.randomUUID();
        Map<String, Object> created = client.post().uri("/admin/api/channels")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .bodyValue(Map.of(
                        "name", "secret-channel-" + UUID.randomUUID(),
                        "type", "openai",
                        "baseUrl", "https://127.0.0.1:9443/v1",
                        "apiKey", upstreamSecret,
                        "models", "secret-test-model",
                        "enabled", true))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(created).isNotNull().doesNotContainKey("apiKey");
        assertThat(created.get("apiKeyConfigured")).isEqualTo(true);
        Long channelId = ((Number) created.get("id")).longValue();
        String encrypted = jdbcTemplate.queryForObject(
                "SELECT api_key FROM channels WHERE id = ?", String.class, channelId);
        assertThat(encrypted).startsWith("enc:v1:").doesNotContain(upstreamSecret);

        for (String endpoint : List.of("/channels", "/admin/api/channels")) {
            String response = client.get().uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                    .exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(response).doesNotContain(upstreamSecret);
        }

        String publicCatalog = client.get().uri("/public/models")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(publicCatalog).doesNotContain(upstreamSecret).doesNotContain("apiKey");
    }

    @Test
    @SuppressWarnings("unchecked")
    void administratorCanSaveOneChannelModelPricingWithoutChangingSiblingModel() {
        Map<String, Object> created = client.post().uri("/admin/api/channels")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .bodyValue(Map.of(
                        "name", "pricing-channel-" + UUID.randomUUID(),
                        "type", "openai",
                        "baseUrl", "https://127.0.0.1:9443/v1",
                        "apiKey", "pricing-secret-" + UUID.randomUUID(),
                        "models", "pricing-model-a\npricing-model-b",
                        "enabled", true))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(created).isNotNull();
        Long channelId = ((Number) created.get("id")).longValue();

        client.put().uri("/admin/api/channels/{id}/model-pricing", channelId)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .bodyValue(Map.of("channelModelName", "pricing-model-a"))
                .exchange().expectStatus().isForbidden();

        Map<String, Object> updated = client.put().uri("/admin/api/channels/{id}/model-pricing", channelId)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .bodyValue(Map.ofEntries(
                        Map.entry("channelModelName", "pricing-model-a"),
                        Map.entry("priority", 20),
                        Map.entry("enabled", true),
                        Map.entry("billingEnabled", true),
                        Map.entry("trafficPercent", 75),
                        Map.entry("priceRatio", 1),
                        Map.entry("costPerMillion", 0),
                        Map.entry("inputCostPerMillion", 1.25),
                        Map.entry("outputCostPerMillion", 2.5),
                        Map.entry("cachedCostPerMillion", 0),
                        Map.entry("inputPricePerMillion", 2.5),
                        Map.entry("outputPricePerMillion", 5),
                        Map.entry("cachedPricePerMillion", 0)))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(updated).isNotNull().containsEntry("channelModelName", "pricing-model-a");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_mappings WHERE channel_id=?", Integer.class, channelId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT traffic_percent FROM model_mappings WHERE channel_id=? AND channel_model_name='pricing-model-a'",
                Integer.class, channelId)).isEqualTo(75);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT traffic_percent FROM model_mappings WHERE channel_id=? AND channel_model_name='pricing-model-b'",
                Integer.class, channelId)).isEqualTo(100);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> register(String identifier, String password) {
        Map<String, Object> verification = client.post().uri("/auth/verification/email/send")
                .bodyValue(Map.of("recipient", identifier, "purpose", "REGISTER"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(verification).isNotNull();
        Map<String, Object> response = client.post().uri("/auth/register")
                .bodyValue(Map.of(
                        "email", identifier,
                        "emailCode", verification.get("debugCode").toString(),
                        "password", password))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
