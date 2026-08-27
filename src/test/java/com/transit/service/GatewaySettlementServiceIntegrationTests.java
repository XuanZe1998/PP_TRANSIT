package com.transit.service;

import com.transit.model.Token;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class GatewaySettlementServiceIntegrationTests {

    @Autowired
    private GatewaySettlementService settlementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insufficientBalanceRollsBackTheQuotaReservationBeforeAnyUpstreamCall() {
        Account account = account(5, 1_000);

        assertThatThrownBy(() -> settlementService.reserve(
                account.token(), account.user(), 100, 10,
                unique("reservation"), "test-model"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));

        assertThat(tokenUsage(account.token().getId())).isZero();
        assertThat(userBalance(account.user().getId())).isEqualTo(5);
        assertThat(reservationCount(account.token().getId())).isZero();
    }

    @Test
    void releasingAFailedRequestRefundsQuotaAndBalanceExactlyOnce() {
        Account account = account(1_000, 1_000);
        String id = unique("reservation");

        GatewaySettlementService.Reservation reservation = settlementService.reserve(
                account.token(), account.user(), 100, 200, id, "test-model");
        assertThat(tokenUsage(account.token().getId())).isEqualTo(100);
        assertThat(userBalance(account.user().getId())).isEqualTo(800);

        settlementService.release(reservation, "upstream unavailable");
        settlementService.release(reservation, "duplicate release must be harmless");

        assertThat(tokenUsage(account.token().getId())).isZero();
        assertThat(userBalance(account.user().getId())).isEqualTo(1_000);
        assertThat(reservationStatus(id)).isEqualTo("RELEASED");
    }

    @Test
    void settlementReconcilesConservativeReservationAndIsIdempotent() {
        Account account = account(1_000, 1_000);
        String id = unique("reservation");
        GatewaySettlementService.Reservation reservation = settlementService.reserve(
                account.token(), account.user(), 100, 200, id, "test-model");

        settlementService.settle(reservation, 30, 50, "auditable test usage");
        settlementService.settle(reservation, 30, 50, "duplicate settlement must be harmless");

        assertThat(tokenUsage(account.token().getId())).isEqualTo(30);
        assertThat(userBalance(account.user().getId())).isEqualTo(950);
        assertThat(reservationStatus(id)).isEqualTo("SETTLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_transactions WHERE user_id = ? AND type = 'CONSUME' AND amount = -50",
                Long.class, account.user().getId())).isEqualTo(1L);
    }

    private Account account(long balance, long totalQuota) {
        String username = unique("settlement-user") + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO users(username, password, email, auth_provider, role, status, balance)
                VALUES (?, '', ?, 'local', 'USER', 'ACTIVE', ?)
                """, username, username, balance);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        String key = unique("sha256-test-key");
        jdbcTemplate.update("""
                INSERT INTO tokens(`key`, key_prefix, user_id, name, used_quota, total_quota, enabled)
                VALUES (?, 'sk-at-test', ?, 'settlement-test', 0, ?, TRUE)
                """, key, userId, totalQuota);
        Long tokenId = jdbcTemplate.queryForObject(
                "SELECT id FROM tokens WHERE `key` = ?", Long.class, key);
        User user = User.builder().id(userId).username(username).status("ACTIVE").balance(balance).build();
        Token token = Token.builder().id(tokenId).userId(userId).enabled(true)
                .usedQuota(0).totalQuota(totalQuota).build();
        return new Account(user, token);
    }

    private long tokenUsage(Long tokenId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT used_quota FROM tokens WHERE id = ?", Long.class, tokenId);
        return value == null ? 0 : value;
    }

    private long userBalance(Long userId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT balance FROM users WHERE id = ?", Long.class, userId);
        return value == null ? 0 : value;
    }

    private long reservationCount(Long tokenId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gateway_reservations WHERE token_id = ?", Long.class, tokenId);
        return value == null ? 0 : value;
    }

    private String reservationStatus(String reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM gateway_reservations WHERE reservation_id = ?",
                String.class, reservationId);
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record Account(User user, Token token) {
    }
}
