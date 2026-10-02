package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:account_deletion_security_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "security.account-deletion.enabled=true"})
@AutoConfigureWebTestClient
class AccountDeletionIntegrationTests {
    private static final String PASSWORD = "StrongPass123";
    @Autowired WebTestClient client;
    @Autowired JdbcTemplate jdbc;

    @Test
    void destructiveDeleteIsDisabledByDefault() {
        assertThatThrownBy(() -> new AccountDeletionService(null, null, false)
                .deleteUnusedPersonalAccount(1, PASSWORD))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("503 SERVICE_UNAVAILABLE");
    }

    @Test
    void deletesOnlyOwnUnusedAccountAndInvalidatesSession() {
        Account account = register();
        client.method(org.springframework.http.HttpMethod.DELETE).uri("/user/profile").bodyValue(Map.of("password", PASSWORD))
                .exchange().expectStatus().isUnauthorized();
        client.method(org.springframework.http.HttpMethod.DELETE).uri("/user/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token)
                .bodyValue(Map.of("password", "incorrect"))
                .exchange().expectStatus().isUnauthorized();
        assertThat(count("users", "id", account.id)).isEqualTo(1);

        client.method(org.springframework.http.HttpMethod.DELETE).uri("/user/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token)
                .bodyValue(Map.of("password", PASSWORD)).exchange().expectStatus().isOk();
        assertThat(count("users", "id", account.id)).isZero();
        assertThat(count("organizations", "id", account.organizationId)).isZero();
        assertThat(count("wallet_accounts", "organization_id", account.organizationId)).isZero();
        assertThat(count("organization_members", "organization_id", account.organizationId)).isZero();
        assertThat(count("oauth_tokens", "user_id", account.id)).isZero();
        assertThat(count("legal_acceptances", "user_id", account.id)).isZero();
        assertThat(count("login_ip_history", "user_id", account.id)).isZero();
        assertThat(count("user_verification_codes", "recipient", account.email)).isZero();
        client.get().uri("/user/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void refusesMoneyOrHistoryAndDoesNotPartiallyDelete() {
        Account account = register();
        jdbc.update("UPDATE wallet_accounts SET balance=1 WHERE organization_id=?", account.organizationId);
        attemptConflict(account);
        assertThat(count("users", "id", account.id)).isEqualTo(1);
        jdbc.update("UPDATE wallet_accounts SET balance=0 WHERE organization_id=?", account.organizationId);
        jdbc.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after) VALUES (?,'TEST',0,0)", account.id);
        attemptConflict(account);
        assertThat(count("oauth_tokens", "user_id", account.id)).isPositive();
    }

    @Test
    void refusesApiKeysAndOtherOrganizationReferences() {
        Account account = register();
        jdbc.update("INSERT INTO tokens(`key`,user_id,name) VALUES (?,?,?)", "sk-test-" + UUID.randomUUID(), account.id, "test");
        attemptConflict(account);
        assertThat(count("users", "id", account.id)).isEqualTo(1);
        jdbc.update("DELETE FROM tokens WHERE user_id=?", account.id);
        jdbc.update("INSERT INTO wallet_ledger_entries(transaction_id,wallet_account_id,organization_id,user_id,entry_type,direction,amount,balance_after) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=?", Long.class, account.organizationId),
                account.organizationId, account.id, "TEST", "CREDIT", 0, 0);
        attemptConflict(account);
        assertThat(count("organizations", "id", account.organizationId)).isEqualTo(1);
    }
    private void attemptConflict(Account account) {
        client.method(org.springframework.http.HttpMethod.DELETE).uri("/user/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token)
                .bodyValue(Map.of("password", PASSWORD)).exchange().expectStatus().isEqualTo(409);
    }

    private long count(String table, String column, Object value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Long.class, value);
    }

    private Account register() {
        String email = "delete-test-" + UUID.randomUUID() + "@example.com";
        Map<?, ?> verification = client.post().uri("/auth/verification/email/send")
                .bodyValue(Map.of("recipient", email, "purpose", "REGISTER"))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(verification).isNotNull();
        Map<?, ?> session = client.post().uri("/auth/register")
                .bodyValue(Map.of("email", email, "emailCode", verification.get("debugCode"), "password", PASSWORD))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(session).isNotNull();
        long id = ((Number) session.get("user_id")).longValue();
        long orgId = jdbc.queryForObject("SELECT default_organization_id FROM users WHERE id=?", Long.class, id);
        return new Account(id, orgId, email, session.get("access_token").toString());
    }

    private record Account(long id, long organizationId, String email, String token) {}
}
