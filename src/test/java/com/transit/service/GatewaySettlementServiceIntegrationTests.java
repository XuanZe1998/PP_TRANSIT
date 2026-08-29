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
import java.time.LocalDateTime;

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

    @Test
    void enterpriseMemberUsageDebitsAndReconcilesBothAllocationAndTreasury() {
        EnterpriseAccount account = enterpriseAccount(1_000, 300, 40);
        String id = unique("enterprise-reservation");

        GatewaySettlementService.Reservation reservation = settlementService.reserve(
                account.token(), account.member(), 100, 100, id, "test-model");

        assertThat(walletBalance(account.memberWalletId())).isEqualTo(200);
        assertThat(walletBalance(account.treasuryWalletId())).isEqualTo(900);
        assertThat(userBalance(account.ownerId())).isEqualTo(900);
        assertThat(userBalance(account.member().getId())).isEqualTo(40);
        assertThat(reservation.fundingWalletAccountId()).isEqualTo(account.treasuryWalletId());

        settlementService.settle(reservation, 20, 50, "enterprise usage");

        assertThat(walletBalance(account.memberWalletId())).isEqualTo(250);
        assertThat(walletBalance(account.treasuryWalletId())).isEqualTo(950);
        assertThat(userBalance(account.ownerId())).isEqualTo(950);
        assertThat(userBalance(account.member().getId())).isEqualTo(40);
    }

    @Test
    void enterpriseFailedRequestRefundsBothAllocationAndTreasury() {
        EnterpriseAccount account = enterpriseAccount(1_000, 300, 40);
        GatewaySettlementService.Reservation reservation = settlementService.reserve(
                account.token(), account.member(), 100, 100,
                unique("enterprise-release"), "test-model");

        settlementService.release(reservation, "upstream failed");

        assertThat(walletBalance(account.memberWalletId())).isEqualTo(300);
        assertThat(walletBalance(account.treasuryWalletId())).isEqualTo(1_000);
        assertThat(userBalance(account.ownerId())).isEqualTo(1_000);
        assertThat(userBalance(account.member().getId())).isEqualTo(40);
    }

    @Test
    void insufficientEnterpriseTreasuryRollsBackEmployeeAndTokenReservations() {
        EnterpriseAccount account = enterpriseAccount(50, 300, 40);

        assertThatThrownBy(() -> settlementService.reserve(
                account.token(), account.member(), 100, 100,
                unique("enterprise-insufficient-treasury"), "test-model"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));

        assertThat(tokenUsage(account.token().getId())).isZero();
        assertThat(walletBalance(account.memberWalletId())).isEqualTo(300);
        assertThat(walletBalance(account.treasuryWalletId())).isEqualTo(50);
        assertThat(userBalance(account.ownerId())).isEqualTo(50);
        assertThat(userBalance(account.member().getId())).isEqualTo(40);
        assertThat(reservationCount(account.token().getId())).isZero();
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

    private long walletBalance(Long walletId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id=?", Long.class, walletId);
        return value == null ? 0 : value;
    }

    private EnterpriseAccount enterpriseAccount(long treasury, long memberQuota, long personalBalance) {
        String ownerName = unique("enterprise-owner");
        String memberName = unique("enterprise-member");
        jdbcTemplate.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',?,'ENTERPRISE')",
                ownerName, ownerName + "@example.com", treasury);
        jdbcTemplate.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',?,'PERSONAL')",
                memberName, memberName + "@example.com", personalBalance);
        Long ownerId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username=?", Long.class, ownerName);
        Long memberId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username=?", Long.class, memberName);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'COMPANY','ACTIVE',?,?,?)",
                ownerName + " company", ownerId, now, now);
        Long organizationId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM organizations WHERE created_by=?", Long.class, ownerId);
        jdbcTemplate.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)",
                organizationId, ownerId, now);
        jdbcTemplate.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'MEMBER','ACTIVE',?)",
                organizationId, memberId, now);
        jdbcTemplate.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',?,'ACTIVE',?,?)",
                organizationId, ownerId, treasury, now, now);
        jdbcTemplate.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'MEMBER',?,'ACTIVE',?,?)",
                organizationId, memberId, memberQuota, now, now);
        Long treasuryWalletId = jdbcTemplate.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, organizationId, ownerId);
        Long memberWalletId = jdbcTemplate.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, organizationId, memberId);
        String key = unique("enterprise-key");
        jdbcTemplate.update("INSERT INTO tokens(`key`,key_prefix,user_id,organization_id,name,used_quota,total_quota,enabled) VALUES (?,'sk-at-test',?,?, 'enterprise-test',0,1000,TRUE)",
                key, memberId, organizationId);
        Long tokenId = jdbcTemplate.queryForObject("SELECT id FROM tokens WHERE `key`=?", Long.class, key);
        User member = User.builder().id(memberId).username(memberName).status("ACTIVE")
                .balance(personalBalance).defaultOrganizationId(organizationId).build();
        Token token = Token.builder().id(tokenId).userId(memberId).organizationId(organizationId)
                .enabled(true).usedQuota(0).totalQuota(1000).build();
        return new EnterpriseAccount(ownerId, member, token, treasuryWalletId, memberWalletId);
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

    private record EnterpriseAccount(Long ownerId, User member, Token token,
                                     Long treasuryWalletId, Long memberWalletId) {
    }
}
