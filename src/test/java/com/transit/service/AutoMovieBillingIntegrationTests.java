package com.transit.service;

import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AutoMovieBillingIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired AutoMovieService autoMovie;
    @Autowired CreativeBillingService billing;

    @Test
    void scriptReservationAndCancellationKeepPersonalTreasurySynchronized() {
        String name = "movie-billing-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',20000,'PERSONAL')", name, name + "@example.com");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)", name, userId, now, now);
        long orgId = jdbc.queryForObject("SELECT MAX(id) FROM organizations WHERE created_by=?", Long.class, userId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", orgId, userId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',20000,'ACTIVE',?,?)", orgId, userId, now, now);
        long walletId = jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, orgId, userId);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", orgId, userId);
        jdbc.update("INSERT INTO creative_projects(user_id,title,source_text,target_duration,ratio,resolution,stage,status,version,created_at,updated_at) VALUES (?,'test','test source',30,'16:9','720p','SOURCE','DRAFT',1,?,?)", userId, now, now);
        long projectId = jdbc.queryForObject("SELECT MAX(id) FROM creative_projects WHERE user_id=?", Long.class, userId);
        User user = User.builder().id(userId).defaultOrganizationId(orgId).status("ACTIVE").build();

        autoMovie.enqueueScript(user, projectId, Map.of("version", 1));
        long reserved = jdbc.queryForObject("SELECT reserved_amount FROM creative_billing_reservations WHERE project_id=?", Long.class, projectId);
        assertThat(reserved).isPositive();
        assertThat(userBalance(userId)).isEqualTo(20000 - reserved);
        assertThat(walletBalance(walletId)).isEqualTo(20000 - reserved);
        assertThat(heldBalance(walletId)).isEqualTo(reserved);

        autoMovie.cancel(user, projectId, Map.of("version", 1));
        assertThat(userBalance(userId)).isEqualTo(20000);
        assertThat(walletBalance(walletId)).isEqualTo(20000);
        assertThat(heldBalance(walletId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM creative_billing_reservations WHERE project_id=?", String.class, projectId)).isEqualTo("RELEASED");
    }

    @Test
    void companyMemberSettlementAndReleaseConserveAllocationAndTreasury() {
        String prefix = "movie-company-" + UUID.randomUUID();
        long ownerId = newUser(prefix + "-owner", 1000, "COMPANY");
        long memberId = newUser(prefix + "-member", 0, "COMPANY");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'COMPANY','ACTIVE',?,?,?)", prefix, ownerId, now, now);
        long orgId = jdbc.queryForObject("SELECT MAX(id) FROM organizations WHERE created_by=?", Long.class, ownerId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", orgId, ownerId, now);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'MEMBER','ACTIVE',?)", orgId, memberId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',1000,'ACTIVE',?,?)", orgId, ownerId, now, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'MEMBER',300,'ACTIVE',?,?)", orgId, memberId, now, now);
        long treasuryId = jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, orgId, ownerId);
        long memberWalletId = jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, orgId, memberId);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id IN (?,?)", orgId, ownerId, memberId);
        User member = User.builder().id(memberId).defaultOrganizationId(orgId).status("ACTIVE").build();
        long projectId = 900000 + memberId;

        billing.reserve(member, projectId, "SCRIPT", 100);
        assertThat(walletBalance(memberWalletId)).isEqualTo(200);
        assertThat(walletBalance(treasuryId)).isEqualTo(900);
        assertThat(heldBalance(memberWalletId)).isEqualTo(100);
        assertThat(heldBalance(treasuryId)).isEqualTo(100);
        assertThat(userBalance(ownerId)).isEqualTo(900);
        assertThat(userBalance(memberId)).isZero();

        billing.settle(projectId, "SCRIPT", 40);
        billing.settle(projectId, "SCRIPT", 40);
        assertThat(walletBalance(memberWalletId)).isEqualTo(260);
        assertThat(walletBalance(treasuryId)).isEqualTo(960);
        assertThat(heldBalance(memberWalletId)).isZero();
        assertThat(heldBalance(treasuryId)).isZero();
        assertThat(userBalance(ownerId)).isEqualTo(960);

        billing.reserve(member, projectId, "VIDEO", 50);
        jdbc.update("UPDATE organization_members SET status='SUSPENDED' WHERE organization_id=? AND user_id=?", orgId, memberId);
        billing.releaseOpenReservations(projectId);
        billing.releaseOpenReservations(projectId);
        assertThat(walletBalance(memberWalletId)).isEqualTo(260);
        assertThat(walletBalance(treasuryId)).isEqualTo(960);
        assertThat(heldBalance(memberWalletId)).isZero();
        assertThat(heldBalance(treasuryId)).isZero();
        assertThat(userBalance(ownerId)).isEqualTo(960);
    }

    @Test
    void historicalTreasuryMismatchRequiresReconciliationBeforeNewDebit() {
        PersonalFixture f = personalFixture(1000);
        jdbc.update("UPDATE users SET balance=900 WHERE id=?", f.userId());
        assertThatThrownBy(() -> billing.reserve(f.user(), f.projectId(), "SCRIPT", 100))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("reconciliation");
        assertThat(userBalance(f.userId())).isEqualTo(900);
        assertThat(walletBalance(f.walletId())).isEqualTo(1000);
        assertThat(heldBalance(f.walletId())).isZero();
        assertThat(reservationCount(f.projectId())).isZero();
    }

    @Test
    void suspendedAccountCannotReserveUsingStaleActiveUserObject() {
        PersonalFixture f = personalFixture(1000);
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?", f.userId());
        assertThatThrownBy(() -> billing.reserve(f.user(), f.projectId(), "SCRIPT", 100))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(userBalance(f.userId())).isEqualTo(1000);
        assertThat(walletBalance(f.walletId())).isEqualTo(1000);
        assertThat(heldBalance(f.walletId())).isZero();
        assertThat(reservationCount(f.projectId())).isZero();
    }

    @Test
    void refundStillSynchronizesSuspendedOriginalTreasuryAndOwner() {
        PersonalFixture f = personalFixture(1000);
        billing.reserve(f.user(), f.projectId(), "SCRIPT", 100);
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?", f.userId());
        jdbc.update("UPDATE wallet_accounts SET status='SUSPENDED' WHERE id=?", f.walletId());
        jdbc.update("UPDATE organization_members SET status='SUSPENDED' WHERE organization_id=?", f.orgId());
        billing.releaseOpenReservations(f.projectId());
        billing.releaseOpenReservations(f.projectId());
        assertThat(userBalance(f.userId())).isEqualTo(1000);
        assertThat(walletBalance(f.walletId())).isEqualTo(1000);
        assertThat(heldBalance(f.walletId())).isZero();
    }

    @Test
    void fullAndPartialSettlementReleaseHoldsAndAreIdempotent() {
        PersonalFixture f = personalFixture(1000);
        billing.reserve(f.user(), f.projectId(), "SCRIPT", 100);
        billing.settle(f.projectId(), "SCRIPT", 100);
        billing.settle(f.projectId(), "SCRIPT", 100);
        assertThat(userBalance(f.userId())).isEqualTo(900);
        assertThat(walletBalance(f.walletId())).isEqualTo(900);
        assertThat(heldBalance(f.walletId())).isZero();
        billing.reserve(f.user(), f.projectId(), "VISUALS", 100);
        billing.settle(f.projectId(), "VISUALS", 40);
        billing.releaseOpenReservations(f.projectId());
        assertThat(userBalance(f.userId())).isEqualTo(860);
        assertThat(walletBalance(f.walletId())).isEqualTo(860);
        assertThat(heldBalance(f.walletId())).isZero();
    }

    @Test
    void deletingFailedProjectReleasesRemainingHold() {
        PersonalFixture f = personalFixture(1000);
        billing.reserve(f.user(), f.projectId(), "SCRIPT", 100);
        jdbc.update("UPDATE creative_projects SET status='FAILED' WHERE id=?", f.projectId());
        autoMovie.delete(f.user(), f.projectId());
        assertThat(userBalance(f.userId())).isEqualTo(1000);
        assertThat(walletBalance(f.walletId())).isEqualTo(1000);
        assertThat(heldBalance(f.walletId())).isZero();
        assertThat(reservationCount(f.projectId())).isZero();
    }

    @Test
    void legacyReservationRefundDoesNotInventWalletHolds() {
        PersonalFixture f = personalFixture(1000);
        jdbc.update("UPDATE users SET balance=900 WHERE id=?", f.userId());
        jdbc.update("INSERT INTO creative_billing_reservations(project_id,user_id,stage,estimated_amount,reserved_amount,status,created_at) VALUES (?,?,'SCRIPT',100,100,'RESERVED',?)",
                f.projectId(), f.userId(), LocalDateTime.now());
        billing.releaseOpenReservations(f.projectId());
        billing.releaseOpenReservations(f.projectId());
        assertThat(userBalance(f.userId())).isEqualTo(1000);
        assertThat(walletBalance(f.walletId())).isEqualTo(1000);
        assertThat(heldBalance(f.walletId())).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void treasuryShortfallRollsBackPreviouslyDebitedMemberAllocation() {
        PersonalFixture owner = personalFixture(20);
        long memberId = newUser("movie-rollback-" + UUID.randomUUID(), 0, "COMPANY");
        try {
            jdbc.update("UPDATE organizations SET organization_type='COMPANY' WHERE id=?", owner.orgId());
            jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'MEMBER','ACTIVE',?)",
                    owner.orgId(), memberId, LocalDateTime.now());
            jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'MEMBER',300,'ACTIVE',?,?)",
                    owner.orgId(), memberId, LocalDateTime.now(), LocalDateTime.now());
            jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", owner.orgId(), memberId);
            long memberWallet = jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE user_id=?", Long.class, memberId);
            User member = User.builder().id(memberId).status("ACTIVE").build();
            assertThatThrownBy(() -> billing.reserve(member, owner.projectId(), "SCRIPT", 100))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(walletBalance(memberWallet)).isEqualTo(300);
            assertThat(heldBalance(memberWallet)).isZero();
            assertThat(walletBalance(owner.walletId())).isEqualTo(20);
            assertThat(heldBalance(owner.walletId())).isZero();
            assertThat(userBalance(owner.userId())).isEqualTo(20);
            assertThat(reservationCount(owner.projectId())).isZero();
        } finally {
            jdbc.update("DELETE FROM creative_billing_reservations WHERE project_id=?", owner.projectId());
            jdbc.update("DELETE FROM creative_projects WHERE id=?", owner.projectId());
            jdbc.update("DELETE FROM wallet_accounts WHERE organization_id=?", owner.orgId());
            jdbc.update("DELETE FROM organization_members WHERE organization_id=?", owner.orgId());
            jdbc.update("DELETE FROM organizations WHERE id=?", owner.orgId());
            jdbc.update("DELETE FROM users WHERE id IN (?,?)", owner.userId(), memberId);
        }
    }

    @Test
    void ownerWithoutDefaultOrganizationStillUsesOwnTreasury() {
        PersonalFixture f = personalFixture(1000);
        jdbc.update("UPDATE organizations SET organization_type='COMPANY' WHERE id=?", f.orgId());
        jdbc.update("UPDATE users SET default_organization_id=NULL WHERE id=?", f.userId());
        billing.reserve(f.user(), f.projectId(), "SCRIPT", 100);
        assertThat(walletBalance(f.walletId())).isEqualTo(900);
        assertThat(userBalance(f.userId())).isEqualTo(900);
        assertThat(heldBalance(f.walletId())).isEqualTo(100);
        billing.releaseOpenReservations(f.projectId());
        assertThat(walletBalance(f.walletId())).isEqualTo(1000);
        assertThat(userBalance(f.userId())).isEqualTo(1000);
    }

    @Test
    void organizationSwitchDoesNotRedirectRefundOrOverwriteCurrentMirror() {
        PersonalFixture f = personalFixture(1000);
        billing.reserve(f.user(), f.projectId(), "SCRIPT", 100);
        String name = "movie-switched-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)", name, f.userId(), now, now);
        long newOrg = jdbc.queryForObject("SELECT id FROM organizations WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", newOrg, f.userId(), now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',400,'ACTIVE',?,?)", newOrg, f.userId(), now, now);
        long newWallet = jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=?", Long.class, newOrg);
        jdbc.update("UPDATE users SET default_organization_id=?,balance=400 WHERE id=?", newOrg, f.userId());
        billing.releaseOpenReservations(f.projectId());
        assertThat(walletBalance(f.walletId())).isEqualTo(1000);
        assertThat(heldBalance(f.walletId())).isZero();
        assertThat(walletBalance(newWallet)).isEqualTo(400);
        assertThat(userBalance(f.userId())).isEqualTo(400);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReleasesRefundReservationOnlyOnce() throws Exception {
        PersonalFixture f = personalFixture(1000);
        var executor = Executors.newFixedThreadPool(2);
        try {
            billing.reserve(f.user(), f.projectId(), "SCRIPT", 100);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { ready.countDown(); start.await(); billing.releaseOpenReservations(f.projectId()); return null; });
            var second = executor.submit(() -> { ready.countDown(); start.await(); billing.releaseOpenReservations(f.projectId()); return null; });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(walletBalance(f.walletId())).isEqualTo(1000);
            assertThat(userBalance(f.userId())).isEqualTo(1000);
            assertThat(heldBalance(f.walletId())).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM creative_billing_reservations WHERE project_id=?", String.class, f.projectId())).isEqualTo("RELEASED");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM creative_billing_reservations WHERE project_id=?", f.projectId());
            jdbc.update("DELETE FROM creative_projects WHERE id=?", f.projectId());
            jdbc.update("DELETE FROM wallet_accounts WHERE organization_id=?", f.orgId());
            jdbc.update("DELETE FROM organization_members WHERE organization_id=?", f.orgId());
            jdbc.update("DELETE FROM organizations WHERE id=?", f.orgId());
            jdbc.update("DELETE FROM users WHERE id=?", f.userId());
        }
    }

    private PersonalFixture personalFixture(long balance) {
        String name = "movie-fixture-" + UUID.randomUUID();
        long userId = newUser(name, balance, "PERSONAL");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)", name, userId, now, now);
        long orgId = jdbc.queryForObject("SELECT id FROM organizations WHERE created_by=?", Long.class, userId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", orgId, userId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',?,'ACTIVE',?,?)", orgId, userId, balance, now, now);
        long walletId = jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE user_id=?", Long.class, userId);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", orgId, userId);
        jdbc.update("INSERT INTO creative_projects(user_id,title,source_text,target_duration,ratio,resolution,stage,status,version,created_at,updated_at) VALUES (?,'test','test',30,'16:9','720p','SOURCE','DRAFT',1,?,?)", userId, now, now);
        long projectId = jdbc.queryForObject("SELECT id FROM creative_projects WHERE user_id=?", Long.class, userId);
        return new PersonalFixture(userId, orgId, walletId, projectId);
    }

    private long reservationCount(long projectId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM creative_billing_reservations WHERE project_id=?", Long.class, projectId);
    }
    private record PersonalFixture(long userId, long orgId, long walletId, long projectId) {
        User user() { return User.builder().id(userId).defaultOrganizationId(orgId).status("ACTIVE").build(); }
    }

    private long newUser(String name, long balance, String accountType) {
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',?,?)", name, name + "@example.com", balance, accountType);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
    }
    private long userBalance(long id) { return jdbc.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, id); }
    private long walletBalance(long id) { return jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE id=?", Long.class, id); }
    private long heldBalance(long id) { return jdbc.queryForObject("SELECT held_balance FROM wallet_accounts WHERE id=?", Long.class, id); }
}
