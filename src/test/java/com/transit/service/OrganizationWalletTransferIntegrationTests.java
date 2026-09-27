package com.transit.service;

import com.transit.mapper.UserMapper;
import com.transit.model.User;
import com.transit.model.Token;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrganizationWalletTransferIntegrationTests {
    @Autowired OrganizationService organizations;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired GatewaySettlementService gateway;

    @Test
    void creatingAnOrganizationTransfersFromOwnTreasuryNotCurrentMemberWallet() {
        User member = user(10_000L);
        User companyOwner = user(0L);
        Long personalId = organization(member, "PERSONAL", "OWNER", "TREASURY", 10_000L);
        Long companyId = organization(companyOwner, "COMPANY", "OWNER", "TREASURY", 0L);
        Map<String, Object> invitation = (Map<String, Object>) organizations.invite(companyOwner, companyId,
                Map.of("email", member.getEmail(), "role", "MEMBER"), UUID.randomUUID().toString());
        organizations.accept(member, invitation.get("invitationToken").toString());
        member = users.selectById(member.getId());

        assertThat(totalWalletBalance(member.getId())).isEqualTo(10_000L);
        Long newId = ((Number) organizations.create(member, "new treasury").get("id")).longValue();

        assertThat(walletBalance(personalId, member.getId())).isZero();
        assertThat(walletBalance(companyId, member.getId())).isZero();
        assertThat(walletBalance(newId, member.getId())).isEqualTo(10_000L);
        assertThat(totalWalletBalance(member.getId())).isEqualTo(10_000L);
        assertThat(users.selectById(member.getId()).getBalance()).isEqualTo(10_000L);
    }

    @Test
    void staleCallerSnapshotTransfersOnlyCurrentDatabaseBalance() {
        User owner = user(10_000L);
        Long personalId = organization(owner, "PERSONAL", "OWNER", "TREASURY", 10_000L);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", personalId, owner.getId());
        owner = users.selectById(owner.getId());
        jdbc.update("UPDATE users SET balance=5000 WHERE id=?", owner.getId());
        jdbc.update("UPDATE wallet_accounts SET balance=5000 WHERE organization_id=? AND user_id=?",
                personalId, owner.getId());

        Long newId = ((Number) organizations.create(owner, "fresh balance").get("id")).longValue();

        assertThat(walletBalance(personalId, owner.getId())).isZero();
        assertThat(walletBalance(newId, owner.getId())).isEqualTo(5_000L);
        assertThat(totalWalletBalance(owner.getId())).isEqualTo(5_000L);
        assertThat(users.selectById(owner.getId()).getBalance()).isEqualTo(5_000L);
    }

    @Test
    void insufficientSourceWalletRollsBackOrganizationCreation() {
        User owner = user(10_000L);
        Long personalId = organization(owner, "PERSONAL", "OWNER", "TREASURY", 5_000L);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", personalId, owner.getId());
        owner = users.selectById(owner.getId());
        String name = "insufficient-" + UUID.randomUUID();
        User caller = owner;

        assertThatThrownBy(() -> organizations.create(caller, name))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Balance changed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE name=?",
                Integer.class, name)).isZero();
        assertThat(walletBalance(personalId, owner.getId())).isEqualTo(5_000L);
        assertThat(totalWalletBalance(owner.getId())).isEqualTo(5_000L);
    }

    @Test
    void mismatchedSourceWalletDoesNotCopyAnOutdatedUserBalance() {
        User owner = user(5_000L);
        Long personalId = organization(owner, "PERSONAL", "OWNER", "TREASURY", 10_000L);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", personalId, owner.getId());
        String name = "mismatched-" + UUID.randomUUID();

        assertThatThrownBy(() -> organizations.create(owner, name))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Balance changed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE name=?",
                Integer.class, name)).isZero();
        assertThat(walletBalance(personalId, owner.getId())).isEqualTo(10_000L);
        assertThat(users.selectById(owner.getId()).getBalance()).isEqualTo(5_000L);
    }

    @Test
    void openingAnotherCompanyKeepsExistingEmployeeAllocationsFunded() {
        User owner = user(10_000L);
        User employee = user(0L);
        Long existingId = organization(owner, "COMPANY", "OWNER", "TREASURY", 10_000L);
        addMember(existingId, employee, "MEMBER", "MEMBER", 4_000L);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", existingId, owner.getId());
        owner = users.selectById(owner.getId());

        Long nextId = ((Number) organizations.create(owner, "next company").get("id")).longValue();

        assertThat(walletBalance(existingId, owner.getId())).isEqualTo(4_000L);
        assertThat(walletBalance(existingId, employee.getId())).isEqualTo(4_000L);
        assertThat(walletBalance(nextId, owner.getId())).isEqualTo(6_000L);
        assertThat(users.selectById(owner.getId()).getBalance()).isEqualTo(6_000L);
        assertThat(walletBalance(existingId, owner.getId()) + walletBalance(nextId, owner.getId()))
                .isEqualTo(10_000L);
    }

    @Test
    void oldCompanyReservationReleaseDoesNotOverwriteNewDefaultTreasuryMirror() {
        User owner = user(10_000L);
        User employee = user(0L);
        Long existingId = organization(owner, "COMPANY", "OWNER", "TREASURY", 10_000L);
        addMember(existingId, employee, "MEMBER", "MEMBER", 4_000L);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id IN (?,?)",
                existingId, owner.getId(), employee.getId());
        owner = users.selectById(owner.getId());
        employee = users.selectById(employee.getId());
        Long nextId = ((Number) organizations.create(owner, "next with reservation").get("id")).longValue();
        String tokenKey = "sk-test-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tokens(`key`,key_prefix,user_id,organization_id,name,used_quota,total_quota,enabled)
                VALUES (?,'sk-test',?,?,'old-company',0,1000,TRUE)
                """, tokenKey, employee.getId(), existingId);
        Long tokenId = jdbc.queryForObject("SELECT id FROM tokens WHERE `key`=?", Long.class, tokenKey);
        Token token = Token.builder().id(tokenId).userId(employee.getId()).organizationId(existingId).build();

        GatewaySettlementService.Reservation reservation = gateway.reserve(token, employee, 10, 1_000,
                "old-company-" + UUID.randomUUID(), "test-model");
        gateway.release(reservation, "controlled regression");

        assertThat(walletBalance(existingId, owner.getId())).isEqualTo(4_000L);
        assertThat(walletBalance(existingId, employee.getId())).isEqualTo(4_000L);
        assertThat(walletBalance(nextId, owner.getId())).isEqualTo(6_000L);
        assertThat(users.selectById(owner.getId()).getBalance()).isEqualTo(6_000L);
    }

    @Test
    void ownerCannotDemoteSelfAndOrphanFundedTreasury() {
        User owner = user(10_000L);
        Long companyId = organization(owner, "COMPANY", "OWNER", "TREASURY", 10_000L);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", companyId, owner.getId());

        assertThatThrownBy(() -> organizations.updateMember(owner, companyId, owner.getId(),
                Map.of("role", "MEMBER")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT member_role FROM organization_members WHERE organization_id=? AND user_id=?",
                String.class, companyId, owner.getId())).isEqualTo("OWNER");
        assertThat(walletBalance(companyId, owner.getId())).isEqualTo(10_000L);
        assertThat(users.selectById(owner.getId()).getBalance()).isEqualTo(10_000L);
    }

    @Test
    void ownerCannotSuspendSelfAndOrphanFundedTreasury() {
        User owner = user(10_000L);
        Long companyId = organization(owner, "COMPANY", "OWNER", "TREASURY", 10_000L);

        assertThatThrownBy(() -> organizations.updateMember(owner, companyId, owner.getId(),
                Map.of("status", "SUSPENDED")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT status FROM organization_members WHERE organization_id=? AND user_id=?",
                String.class, companyId, owner.getId())).isEqualTo("ACTIVE");
        assertThat(walletBalance(companyId, owner.getId())).isEqualTo(10_000L);
    }

    @Test
    void legacyUserWithoutTreasuryTransfersOnce() {
        User owner = user(7_000L);

        Long newId = ((Number) organizations.create(owner, "legacy treasury").get("id")).longValue();

        assertThat(walletBalance(newId, owner.getId())).isEqualTo(7_000L);
        assertThat(totalWalletBalance(owner.getId())).isEqualTo(7_000L);
        assertThat(users.selectById(owner.getId()).getBalance()).isEqualTo(7_000L);
    }

    private User user(long balance) {
        String identity = "org-transfer-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        User user = User.builder().username(identity).password("test")
                .email(identity + "@example.com").emailVerifiedAt(now)
                .role("USER").status("ACTIVE").balance(balance).invoiceEnabled(false)
                .createdAt(now).build();
        users.insert(user);
        return user;
    }

    private Long organization(User owner, String type, String role, String accountType, long balance) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String name = "org-transfer-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at)
                VALUES (?,?,'ACTIVE',?,?,?)
                """, name, type, owner.getId(), now, now);
        Long id = jdbc.queryForObject("SELECT id FROM organizations WHERE name=?", Long.class, name);
        addMember(id, owner, role, accountType, balance);
        return id;
    }

    private void addMember(Long organizationId, User user, String role, String accountType, long balance) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at)
                VALUES (?,?,?,'ACTIVE',?)
                """, organizationId, user.getId(), role, now);
        jdbc.update("""
                INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',?,?)
                """, organizationId, user.getId(), accountType, balance, now, now);
    }

    private Long walletBalance(Long organizationId, Long userId) {
        return jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?",
                Long.class, organizationId, userId);
    }

    private Long totalWalletBalance(Long userId) {
        return jdbc.queryForObject("SELECT SUM(balance) FROM wallet_accounts WHERE user_id=?",
                Long.class, userId);
    }
}
