package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CreativeBillingServiceIntegrationTests {
    @Autowired CreativeBillingService billing;
    @Autowired JdbcTemplate jdbc;

    @Test
    void settleRefundsBothBalancesOnlyOnce() {
        Fixture fixture = fixture(1_000, 1_000);
        billing.reserve(fixture.userId(), fixture.projectId(), "SCRIPT", 200);
        assertBalances(fixture, 800, 800);

        billing.settle(fixture.projectId(), "SCRIPT", 50);
        billing.settle(fixture.projectId(), "SCRIPT", 50);
        assertBalances(fixture, 950, 950);
        assertThat(reservationCount(fixture.projectId(), "SETTLED")).isEqualTo(1);
    }

    @Test
    void cancellationReleasesOpenReservationsExactlyOnce() {
        Fixture fixture = fixture(1_000, 1_000);
        billing.reserve(fixture.userId(), fixture.projectId(), "VIDEO", 200);

        billing.releaseOpen(fixture.projectId());
        billing.releaseOpen(fixture.projectId());
        assertBalances(fixture, 1_000, 1_000);
        assertThat(reservationCount(fixture.projectId(), "RELEASED")).isEqualTo(1);
    }

    @Test
    void failedReservationInsertRollsBackBothBalanceRows() {
        Fixture fixture = fixture(1_000, 1_000);
        assertThatThrownBy(() -> billing.reserve(fixture.userId(), fixture.projectId(), null, 200))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertBalances(fixture, 1_000, 1_000);
        assertThat(reservationCount(fixture.projectId(), "RESERVED")).isZero();
    }

    @Test
    void existingMismatchCannotBeSilentlyOverwritten() {
        Fixture fixture = fixture(1_000, 900);
        assertThatThrownBy(() -> billing.reserve(fixture.userId(), fixture.projectId(), "VIDEO", 100))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertBalances(fixture, 1_000, 900);
    }

    private Fixture fixture(long userBalance, long walletBalance) {
        String name = "creative-billing-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO users(username,password,email,auth_provider,role,status,balance)
                VALUES (?,'',?,'local','USER','ACTIVE',?)
                """, name, name + "@example.com", userBalance);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        jdbc.update("""
                INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at)
                VALUES (?,'PERSONAL','ACTIVE',?,?,?)
                """, name, userId, now, now);
        Long organizationId = jdbc.queryForObject("SELECT id FROM organizations WHERE name=?", Long.class, name);
        jdbc.update("""
                INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at)
                VALUES (?,?,'OWNER','ACTIVE',?)
                """, organizationId, userId, now);
        jdbc.update("""
                INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at)
                VALUES (?,?,'TREASURY',?,'ACTIVE',?,?)
                """, organizationId, userId, walletBalance, now, now);
        Long walletId = jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=?", Long.class, organizationId);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", organizationId, userId);
        jdbc.update("INSERT INTO creative_projects(user_id,title,source_text) VALUES (?,'test','synthetic')", userId);
        Long projectId = jdbc.queryForObject("SELECT MAX(id) FROM creative_projects WHERE user_id=?", Long.class, userId);
        return new Fixture(userId, walletId, projectId);
    }

    private void assertBalances(Fixture fixture, long userBalance, long walletBalance) {
        assertThat(jdbc.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, fixture.userId()))
                .isEqualTo(userBalance);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE id=?", Long.class, fixture.walletId()))
                .isEqualTo(walletBalance);
    }

    private long reservationCount(long projectId, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM creative_billing_reservations WHERE project_id=? AND status=?",
                Long.class, projectId, status);
    }

    private record Fixture(long userId, long walletId, long projectId) {}
}
