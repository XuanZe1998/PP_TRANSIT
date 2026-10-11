package com.transit.service;

import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OrganizationWalletTransferSecurityTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organizations;

    @Test
    void memberCannotMoveEmployerAllocationIntoNewPersonalTreasury() {
        String name = "org-member-security-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',100,'PERSONAL')",
                name, name + "@example.com");
        Long memberId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',0,'ENTERPRISE')",
                name + "-owner", name + "-owner@example.com");
        Long employerOwnerId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name + "-owner");
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'COMPANY','ACTIVE',?,?,?)",
                name + " employer", employerOwnerId, now, now);
        Long employerOrg = jdbc.queryForObject("SELECT id FROM organizations WHERE created_by=?", Long.class, employerOwnerId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'MEMBER','ACTIVE',?)", employerOrg, memberId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'MEMBER',100,'ACTIVE',?,?)",
                employerOrg, memberId, now, now);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", employerOrg, memberId);

        User member = User.builder().id(memberId).balance(100).defaultOrganizationId(employerOrg).build();
        assertThatThrownBy(() -> organizations.create(member, name + " private"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        Long employerBalance = jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, employerOrg, memberId);
        assertThat(employerBalance).isEqualTo(100L);
        Integer created = jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE created_by=?", Integer.class, memberId);
        assertThat(created).isZero();
    }

    @Test
    void staleConcurrentOwnerSnapshotsCannotDuplicateWalletBalance() {
        String name = "org-transfer-security-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',100,'PERSONAL')",
                name, name + "@example.com");
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)",
                name + " personal", userId, now, now);
        Long originalOrg = jdbc.queryForObject("SELECT id FROM organizations WHERE created_by=?", Long.class, userId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", originalOrg, userId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',100,'ACTIVE',?,?)",
                originalOrg, userId, now, now);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", originalOrg, userId);

        // Two requests can read the same user snapshot before either transfer commits.
        User staleSnapshot = User.builder().id(userId).balance(100).defaultOrganizationId(originalOrg).build();
        organizations.create(staleSnapshot, name + " first");
        assertThatThrownBy(() -> organizations.create(staleSnapshot, name + " second"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");

        Long aggregate = jdbc.queryForObject("SELECT COALESCE(SUM(balance),0) FROM wallet_accounts WHERE user_id=? AND status='ACTIVE'", Long.class, userId);
        assertThat(aggregate).isEqualTo(100L);
        var entries = jdbc.queryForList("""
                SELECT transaction_id, direction, amount, balance_after FROM wallet_ledger_entries
                WHERE user_id=? AND entry_type='ORG_CREATE_TRANSFER' ORDER BY id
                """, userId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("transaction_id")).isEqualTo(entries.get(1).get("transaction_id"));
        assertThat(entries.stream().map(row -> row.get("direction"))).containsExactly("DEBIT", "CREDIT");
        assertThat(entries.stream().map(row -> ((Number) row.get("amount")).longValue())).containsExactly(100L, 100L);
    }

    @ParameterizedTest
    @CsvSource({"90,0", "100,5"})
    void inconsistentOrHeldPersonalWalletNeverCreatesMoney(long walletBalance, long heldBalance) {
        String name = "org-inconsistent-security-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',100,'PERSONAL')",
                name, name + "@example.com");
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)",
                name + " personal", userId, now, now);
        Long originalOrg = jdbc.queryForObject("SELECT id FROM organizations WHERE created_by=?", Long.class, userId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", originalOrg, userId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,held_balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',?,?,'ACTIVE',?,?)",
                originalOrg, userId, walletBalance, heldBalance, now, now);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", originalOrg, userId);

        User staleSnapshot = User.builder().id(userId).balance(100).defaultOrganizationId(originalOrg).build();
        assertThatThrownBy(() -> organizations.create(staleSnapshot, name + " company"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE created_by=?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, originalOrg, userId))
                .isEqualTo(walletBalance);
        assertThat(jdbc.queryForObject("SELECT default_organization_id FROM users WHERE id=?", Long.class, userId)).isEqualTo(originalOrg);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger_entries WHERE user_id=? AND entry_type='ORG_CREATE_TRANSFER'", Integer.class, userId)).isZero();
    }

    @Test
    void companyOwnerCannotTransferTreasuryToAnotherOrganization() {
        String name = "org-company-owner-security-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',100,'ENTERPRISE')",
                name, name + "@example.com");
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'COMPANY','ACTIVE',?,?,?)",
                name + " company", userId, now, now);
        Long originalOrg = jdbc.queryForObject("SELECT id FROM organizations WHERE created_by=?", Long.class, userId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", originalOrg, userId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',100,'ACTIVE',?,?)",
                originalOrg, userId, now, now);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", originalOrg, userId);

        User owner = User.builder().id(userId).balance(100).defaultOrganizationId(originalOrg).build();
        assertThatThrownBy(() -> organizations.create(owner, name + " next"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE created_by=?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, originalOrg, userId)).isEqualTo(100L);
    }

}
