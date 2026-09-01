package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.transit.model.User;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class WalletBalanceSynchronizationIntegrationTests {
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AdminUserService adminUserService;
    @Autowired RedeemCodeService redeemCodeService;
    @Autowired WalletBalanceReconciliationService reconciliationService;
    @Autowired OrganizationService organizationService;
    @Autowired GatewaySettlementService gatewaySettlementService;
    @Autowired PlatformOperationsService platformOperationsService;

    @Test
    void walletTransactionsSupportSelectableServerSidePagination() {
        PersonalAccount account = personalAccount(100, 100);
        for (int index = 0; index < 25; index++) {
            jdbcTemplate.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark,created_at) VALUES (?,'ADJUSTMENT',1,?,'test',?,?)",
                    account.userId(), 101 + index, "entry-" + index, LocalDateTime.now().plusSeconds(index));
        }
        User user = User.builder().id(account.userId()).balance(100).status("ACTIVE").build();

        Map<String, Object> secondPage = platformOperationsService.userWallet(user, 2, 10);
        assertThat(((Number) secondPage.get("transactionTotal")).longValue()).isEqualTo(25);
        assertThat((java.util.List<?>) secondPage.get("transactions")).hasSize(10);

        Map<String, Object> lastPage = platformOperationsService.userWallet(user, 3, 10);
        assertThat((java.util.List<?>) lastPage.get("transactions")).hasSize(5);
        assertThat(((Number) lastPage.get("transactionTotal")).longValue()).isEqualTo(25);

        assertThat((Number) secondPage.get("transactionPage")).isNotNull().extracting(Number::longValue).isEqualTo(2L);
        assertThat((Number) secondPage.get("transactionPageSize")).isNotNull().extracting(Number::longValue).isEqualTo(10L);
        assertThat((Number) lastPage.get("transactionPageSize")).isNotNull().extracting(Number::longValue).isEqualTo(10L);
    }

    @Test
    void walletPaginationRejectsUnsupportedPageSizeAndKeepsTotalStable() {
        PersonalAccount account = personalAccount(100, 100);
        for (int index = 0; index < 7; index++) {
            jdbcTemplate.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark,created_at) VALUES (?,'ADJUSTMENT',1,?,'test',?,?)",
                    account.userId(), 101 + index, "entry-" + index, LocalDateTime.now().plusSeconds(index));
        }
        User user = User.builder().id(account.userId()).balance(100).status("ACTIVE").build();

        Map<String, Object> invalidSize = platformOperationsService.userWallet(user, 1, 7);
        assertThat((Number) invalidSize.get("transactionPageSize")).isNotNull().extracting(Number::longValue).isEqualTo(10L);
        assertThat(((java.util.List<?>) invalidSize.get("transactions")).size()).isEqualTo(7);
        assertThat(((Number) invalidSize.get("transactionTotal")).longValue()).isEqualTo(7);

        Map<String, Object> fallbackToTen = platformOperationsService.userWallet(user, 1, 10);
        assertThat((Number) fallbackToTen.get("transactionPage")).isNotNull().extracting(Number::longValue).isEqualTo(1L);
        assertThat((Number) fallbackToTen.get("transactionPageSize")).isNotNull().extracting(Number::longValue).isEqualTo(10L);
        assertThat(((java.util.List<?>) fallbackToTen.get("transactions")).size()).isEqualTo(7);
    }

    @Test
    void adminAdjustmentAndRedeemCodeKeepPersonalWalletSynchronized() {
        PersonalAccount account = personalAccount(100, 100);

        adminUserService.adjustBalance(account.userId(), 50, "integration credit", account.userId());
        assertThat(userBalance(account.userId())).isEqualTo(150);
        assertThat(walletBalance(account.walletId())).isEqualTo(150);

        String code = "wallet-sync-" + UUID.randomUUID();
        redeemCodeService.issue(code, 25, 1);
        redeemCodeService.redeem(account.userId(), code);
        assertThat(userBalance(account.userId())).isEqualTo(175);
        assertThat(walletBalance(account.walletId())).isEqualTo(175);

        Map<String, Object> adminRow = adminUserService.users().stream()
                .filter(row -> account.userId().equals(((Number) row.get("id")).longValue()))
                .findFirst().orElseThrow();
        assertThat(adminRow.get("organization_name")).isEqualTo(account.organizationName());
        assertThat(adminRow.get("organization_type")).isEqualTo("PERSONAL");
        assertThat(((Number) adminRow.get("wallet_balance")).longValue()).isEqualTo(175);
    }

    @Test
    void oneTimeReconciliationRestoresPersonalWalletsAndCompanyTreasuryWithoutDoubling() {
        jdbcTemplate.update("DELETE FROM system_settings WHERE setting_key=?",
                WalletBalanceReconciliationService.MARKER);
        PersonalAccount personal = personalAccount(1_000, 0);
        CompanyAccount company = legacyCompany(700, 300);

        assertThat(reconciliationService.reconcileOnce()).isTrue();
        assertThat(walletBalance(personal.walletId())).isEqualTo(1_000);
        assertThat(walletBalance(company.treasuryWalletId())).isEqualTo(1_000);
        assertThat(userBalance(company.ownerId())).isEqualTo(1_000);

        assertThat(reconciliationService.reconcileOnce()).isFalse();
        assertThat(walletBalance(company.treasuryWalletId())).isEqualTo(1_000);
    }

    @Test
    void employeeAllocationChangesOnlyTheLimitAndDoesNotPrepayEnterpriseFunds() {
        CompanyAccount company = legacyCompany(1_000, 200);
        User owner = User.builder().id(company.ownerId()).username("owner").status("ACTIVE")
                .balance(1_000).defaultOrganizationId(company.organizationId()).build();

        organizationService.allocate(owner, company.organizationId(),
                Map.of("userId", company.memberId(), "amount", 100), UUID.randomUUID().toString(), false);

        assertThat(walletBalance(company.treasuryWalletId())).isEqualTo(1_000);
        assertThat(walletBalance(company.memberWalletId())).isEqualTo(300);
        assertThat(userBalance(company.ownerId())).isEqualTo(1_000);
        assertThat(userBalance(company.memberId())).isEqualTo(200);
    }

    @Test
    void reconciliationMigratesAnActiveEmployeeReservationToTheEnterpriseTreasury() {
        jdbcTemplate.update("DELETE FROM system_settings WHERE setting_key=?",
                WalletBalanceReconciliationService.MARKER);
        CompanyAccount company = legacyCompany(700, 200);
        jdbcTemplate.update("UPDATE wallet_accounts SET held_balance=100 WHERE id=?", company.memberWalletId());
        String key = "legacy-reserved-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO tokens(`key`,key_prefix,user_id,organization_id,name,used_quota,total_quota,enabled)
                VALUES (?,'sk-at-test',?,?,'legacy-reserved',100,1000,TRUE)
                """, key, company.memberId(), company.organizationId());
        Long tokenId = jdbcTemplate.queryForObject("SELECT id FROM tokens WHERE `key`=?", Long.class, key);
        String reservationId = "legacy-reservation-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO gateway_reservations
                (reservation_id,token_id,user_id,wallet_account_id,model,reserved_tokens,reserved_amount,status,expires_at,created_at)
                VALUES (?,?,?,?,?,100,100,'RESERVED',?,?)
                """, reservationId, tokenId, company.memberId(), company.memberWalletId(), "test-model",
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now());

        assertThat(reconciliationService.reconcileOnce()).isTrue();
        assertThat(walletBalance(company.treasuryWalletId())).isEqualTo(900);
        assertThat(walletHeldBalance(company.treasuryWalletId())).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT funding_wallet_account_id FROM gateway_reservations WHERE reservation_id=?
                """, Long.class, reservationId)).isEqualTo(company.treasuryWalletId());

        gatewaySettlementService.release(gatewaySettlementService.restore(reservationId), "migration test release");
        assertThat(walletBalance(company.memberWalletId())).isEqualTo(300);
        assertThat(walletBalance(company.treasuryWalletId())).isEqualTo(1_000);
        assertThat(walletHeldBalance(company.treasuryWalletId())).isZero();
    }

    private PersonalAccount personalAccount(long userBalance, long walletBalance) {
        String username = "personal-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',?,'PERSONAL')",
                username, username + "@example.com", userBalance);
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        LocalDateTime now = LocalDateTime.now();
        String organizationName = username + " personal";
        jdbcTemplate.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)",
                organizationName, userId, now, now);
        Long organizationId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM organizations WHERE created_by=?", Long.class, userId);
        jdbcTemplate.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)",
                organizationId, userId, now);
        jdbcTemplate.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',?,'ACTIVE',?,?)",
                organizationId, userId, walletBalance, now, now);
        jdbcTemplate.update("UPDATE users SET default_organization_id=? WHERE id=?", organizationId, userId);
        Long walletId = jdbcTemplate.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, organizationId, userId);
        return new PersonalAccount(userId, walletId, organizationName);
    }

    private CompanyAccount legacyCompany(long ownerLegacyBalance, long employeeAllocation) {
        String owner = "company-owner-" + UUID.randomUUID();
        String member = "company-member-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',?,'ENTERPRISE')",
                owner, owner + "@example.com", ownerLegacyBalance);
        jdbcTemplate.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',?,'PERSONAL')",
                member, member + "@example.com", employeeAllocation);
        Long ownerId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username=?", Long.class, owner);
        Long memberId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username=?", Long.class, member);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'COMPANY','ACTIVE',?,?,?)",
                owner + " company", ownerId, now, now);
        Long organizationId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM organizations WHERE created_by=?", Long.class, ownerId);
        jdbcTemplate.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", organizationId, ownerId, now);
        jdbcTemplate.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'MEMBER','ACTIVE',?)", organizationId, memberId, now);
        jdbcTemplate.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',?,'ACTIVE',?,?)", organizationId, ownerId, ownerLegacyBalance, now, now);
        jdbcTemplate.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'MEMBER',?,'ACTIVE',?,?)", organizationId, memberId, employeeAllocation, now, now);
        Long treasuryWalletId = jdbcTemplate.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, organizationId, ownerId);
        Long memberWalletId = jdbcTemplate.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, organizationId, memberId);
        jdbcTemplate.update("UPDATE users SET default_organization_id=? WHERE id IN (?,?)", organizationId, ownerId, memberId);
        return new CompanyAccount(ownerId, memberId, organizationId, treasuryWalletId, memberWalletId);
    }

    private long userBalance(Long userId) {
        Long value = jdbcTemplate.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, userId);
        return value == null ? 0 : value;
    }

    private long walletBalance(Long walletId) {
        Long value = jdbcTemplate.queryForObject("SELECT balance FROM wallet_accounts WHERE id=?", Long.class, walletId);
        return value == null ? 0 : value;
    }

    private long walletHeldBalance(Long walletId) {
        Long value = jdbcTemplate.queryForObject("SELECT held_balance FROM wallet_accounts WHERE id=?", Long.class, walletId);
        return value == null ? 0 : value;
    }

    private record PersonalAccount(Long userId, Long walletId, String organizationName) {}
    private record CompanyAccount(Long ownerId, Long memberId, Long organizationId,
                                  Long treasuryWalletId, Long memberWalletId) {}
}
