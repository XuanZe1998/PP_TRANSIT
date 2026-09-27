package com.transit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.transit.service.SecretHashService;
import com.transit.service.OAuthService;
import com.transit.service.VerificationDeliveryService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AuthFlowIntegrationTests {

    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecretHashService secretHashService;

    @Autowired
    private OAuthService oauthService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private VerificationDeliveryService verificationDelivery;

    @Test
    void registrationLoginAndLogoutFormACompleteSessionLifecycle() {
        String identifier = uniqueEmail();
        Map<String, Object> registration = register(identifier, "StrongPass123");
        String accessToken = registration.get("access_token").toString();
        assertThat(oauthService.getUserFromToken(accessToken).getUsername()).isEqualTo(identifier);

        client.get()
                .uri("/user/profile")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo(identifier);

        client.post()
                .uri("/auth/logout")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().consumeWith(ignored -> { });

        client.get()
                .uri("/user/profile")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().consumeWith(ignored -> { });

        client.post()
                .uri("/auth/login")
                .bodyValue(Map.of("identifier", identifier, "password", "StrongPass123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").isNotEmpty()
                .jsonPath("$.refresh_token").isNotEmpty();
    }

    @Test
    void firstPasswordLoginBootstrapsTrustForAccountsWithoutIpHistory() {
        String identifier = uniqueEmail();
        jdbcTemplate.update("""
                INSERT INTO users(username, password, email, auth_provider, role, status, balance)
                VALUES (?, ?, ?, 'local', 'USER', 'ACTIVE', 0)
                """, identifier, passwordEncoder.encode("StrongPass123"), identifier);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, identifier);

        client.post()
                .uri("/auth/login")
                .bodyValue(Map.of("identifier", identifier, "password", "StrongPass123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").isNotEmpty()
                .jsonPath("$.verificationRequired").doesNotExist();

        Integer trustedAddresses = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM login_ip_history
                WHERE user_id = ? AND verified = TRUE AND revoked_at IS NULL
                """, Integer.class, userId);
        assertThat(trustedAddresses).isEqualTo(1);
    }

    @Test
    void spoofedForwardedChainCannotBypassNewLoginIpChallenge() {
        String identifier = uniqueEmail();
        String firstIp = "198.51.100.10";
        String secondIp = "198.51.100.11";
        jdbcTemplate.update("""
                INSERT INTO users(username, password, email, auth_provider, role, status, balance)
                VALUES (?, ?, ?, 'local', 'USER', 'ACTIVE', 0)
                """, identifier, passwordEncoder.encode("StrongPass123"), identifier);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, identifier);

        // The trusted edge overwrites X-Real-IP. The first password login bootstraps its address.
        client.post().uri("/auth/login")
                .header("X-Real-IP", firstIp)
                .bodyValue(Map.of("identifier", identifier, "password", "StrongPass123"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.access_token").isNotEmpty();

        // A forged XFF still naming the trusted address must not suppress the new-IP challenge.
        Map<String, Object> challenge = client.post().uri("/auth/login")
                .header("X-Real-IP", secondIp)
                .header("X-Forwarded-For", firstIp)
                .bodyValue(Map.of("identifier", identifier, "password", "StrongPass123"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(challenge).isNotNull()
                .containsEntry("verificationRequired", true)
                .doesNotContainKeys("access_token", "refresh_token");
        String challengeId = challenge.get("challengeId").toString();
        String storedDigest = jdbcTemplate.queryForObject(
                "SELECT ip_digest FROM login_ip_challenges WHERE challenge_id = ? AND user_id = ?",
                String.class, challengeId, userId);
        assertThat(storedDigest).isEqualTo(secretHashService.hash("login-ip|" + secondIp));

        // The IP check precedes code validation; the challenge stays bound to the new address.
        client.post().uri("/auth/login/ip-verify")
                .header("X-Real-IP", firstIp)
                .bodyValue(Map.of("challengeId", challengeId, "code", "000000"))
                .exchange().expectStatus().isForbidden();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM login_ip_challenges WHERE challenge_id = ?", String.class, challengeId))
                .isEqualTo("PENDING");

        // A matching IP reaches code validation rather than failing with an internal error.
        client.post().uri("/auth/login/ip-verify")
                .header("X-Real-IP", secondIp)
                .bodyValue(Map.of("challengeId", challengeId, "code", "not-a-code"))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void newLoginIpValidCodeTrustsAddressAndCannotBeReplayed() {
        String identifier = uniqueEmail();
        String firstIp = "198.51.100.20";
        String secondIp = "198.51.100.21";
        jdbcTemplate.update("""
                INSERT INTO users(username, password, email, auth_provider, role, status, balance)
                VALUES (?, ?, ?, 'local', 'USER', 'ACTIVE', 0)
                """, identifier, passwordEncoder.encode("StrongPass123"), identifier);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, identifier);

        client.post().uri("/auth/login")
                .header("X-Real-IP", firstIp)
                .bodyValue(Map.of("identifier", identifier, "password", "StrongPass123"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.access_token").isNotEmpty();
        Map<String, Object> challenge = client.post().uri("/auth/login")
                .header("X-Real-IP", secondIp)
                .bodyValue(Map.of("identifier", identifier, "password", "StrongPass123"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(challenge).isNotNull().containsEntry("verificationRequired", true)
                .doesNotContainKeys("access_token", "refresh_token");
        String challengeId = challenge.get("challengeId").toString();
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(verificationDelivery).sendEmail(eq(identifier), codeCaptor.capture());
        String code = codeCaptor.getValue();

        // Even the real code cannot be redeemed from another address.
        client.post().uri("/auth/login/ip-verify")
                .header("X-Real-IP", firstIp)
                .bodyValue(Map.of("challengeId", challengeId, "code", code))
                .exchange().expectStatus().isForbidden();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM user_verification_codes WHERE recipient = ? AND purpose = 'NEW_LOGIN_IP'",
                String.class, identifier)).isEqualTo("PENDING");

        Map<String, Object> session = client.post().uri("/auth/login/ip-verify")
                .header("X-Real-IP", secondIp)
                .bodyValue(Map.of("challengeId", challengeId, "code", code))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(session).isNotNull().containsKeys("access_token", "refresh_token");
        client.get().uri("/user/profile")
                .header("Authorization", "Bearer " + session.get("access_token"))
                .exchange().expectStatus().isOk();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM login_ip_challenges WHERE challenge_id = ?", String.class, challengeId))
                .isEqualTo("CONSUMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM login_ip_history WHERE user_id = ? AND ip_digest = ? AND verified = TRUE AND revoked_at IS NULL",
                Integer.class, userId, secretHashService.hash("login-ip|" + secondIp))).isEqualTo(1);

        client.post().uri("/auth/login/ip-verify")
                .header("X-Real-IP", secondIp)
                .bodyValue(Map.of("challengeId", challengeId, "code", code))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void accessTokenUsesItsOwnExpiryInsteadOfRefreshTokenExpiry() {
        Map<String, Object> registration = register(uniqueEmail(), "StrongPass123");
        String accessToken = registration.get("access_token").toString();
        int updated = jdbcTemplate.update(
                "UPDATE oauth_tokens SET access_expires_at = ? WHERE access_token = ?",
                LocalDateTime.now().minusSeconds(1),
                secretHashService.hash(accessToken)
        );
        assertThat(updated).isEqualTo(1);

        client.get()
                .uri("/user/profile")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().consumeWith(ignored -> { });
    }

    @Test
    void registrationValidationReturnsClientErrors() {
        client.post()
                .uri("/auth/register")
                .bodyValue(Map.of("identifier", "not-an-email", "password", "StrongPass123"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().consumeWith(ignored -> { });

        client.post()
                .uri("/auth/register")
                .bodyValue(Map.of("identifier", uniqueEmail(), "password", "weak"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().consumeWith(ignored -> { });
    }

    @Test
    void gatewayEndpointsRequireApiKeyAuthentication() {
        client.get()
                .uri("/v1/models")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").exists();
    }

    @Test
    void passwordResetRevokesExistingSessionsAndRequiresTheNewPassword() {
        String identifier = uniqueEmail();
        Map<String, Object> registration = register(identifier, "StrongPass123");
        String oldAccessToken = registration.get("access_token").toString();

        Map<String, Object> resetRequest = client.post()
                .uri("/auth/password-reset/request")
                .bodyValue(Map.of("email", identifier))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(resetRequest).isNotNull();
        assertThat(resetRequest.get("accepted")).isEqualTo(true);

        client.post()
                .uri("/auth/password-reset/confirm")
                .bodyValue(Map.of("email", identifier, "code", resetRequest.get("debugCode"),
                        "password", "NewStrongPass456", "confirmPassword", "NewStrongPass456"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reset").isEqualTo(true);

        client.post()
                .uri("/auth/password-reset/confirm")
                .bodyValue(Map.of("email", identifier, "code", resetRequest.get("debugCode"),
                        "password", "AnotherPass789", "confirmPassword", "AnotherPass789"))
                .exchange().expectStatus().isBadRequest();

        client.get().uri("/user/profile")
                .header("Authorization", "Bearer " + oldAccessToken)
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/auth/login")
                .bodyValue(Map.of("identifier", identifier, "password", "StrongPass123"))
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/auth/login")
                .bodyValue(Map.of("identifier", identifier, "password", "NewStrongPass456"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.access_token").isNotEmpty();
    }

    @Test
    void genericVerificationEndpointCannotSendPasswordResetCodes() {
        client.post().uri("/auth/verification/email/send")
                .bodyValue(Map.of("recipient", uniqueEmail(), "purpose", "PASSWORD_RESET"))
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/auth/verification/phone/send")
                .bodyValue(Map.of("recipient", "+8613800138000", "purpose", "PASSWORD_RESET"))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void unknownPasswordResetEmailGetsTheSameGenericAcknowledgement() {
        client.post().uri("/auth/password-reset/request")
                .bodyValue(Map.of("email", uniqueEmail()))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accepted").isEqualTo(true)
                .jsonPath("$.message").isEqualTo("如果该邮箱已注册，验证码将发送到对应邮箱")
                .jsonPath("$.debugCode").doesNotExist();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> register(String identifier, String password) {
        Map<String, Object> verification = client.post()
                .uri("/auth/verification/email/send")
                .bodyValue(Map.of("recipient", identifier, "purpose", "REGISTER"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(verification).isNotNull();
        String emailCode = verification.get("debugCode").toString();
        Map<String, Object> response = client.post()
                .uri("/auth/register")
                .bodyValue(Map.of("email", identifier, "emailCode", emailCode, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@example.com";
    }
}
