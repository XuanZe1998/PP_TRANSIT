package com.transit.service;

import com.transit.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Run only against a disposable, isolated MySQL 8 instance; never a shared or production schema. */
@SpringBootTest(properties = "security.account-deletion.enabled=true")
@ContextConfiguration(initializers = OrganizationWalletMySqlIntegrationTests.DisposableDatabaseGuard.class)
@EnabledIfSystemProperty(named = "linknux.mysql.it", matches = "true")
class OrganizationWalletMySqlIntegrationTests {
    /** Abort before Flyway or startup schema repair can touch a wrongly configured datasource. */
    static final class DisposableDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            String url = context.getEnvironment().getProperty("spring.datasource.url", "");
            String user = context.getEnvironment().getProperty("spring.datasource.username", "");
            if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/linknux_security_test\\?.*")
                    || !"linknux_test".equals(user)) {
                throw new IllegalStateException("Refusing MySQL integration test outside the local disposable schema");
            }
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organizations;
    @Autowired AccountDeletionService deletion;
    @Autowired BCryptPasswordEncoder passwords;
    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void requireDisposableSchema() {
        String catalog = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(catalog).isEqualTo("linknux_security_test");
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).contains("8.0.");
    }

    @AfterEach
    void cleanSyntheticData() {
        if (!"linknux_security_test".equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) return;
        jdbc.execute("DROP TRIGGER IF EXISTS linknux_security_force_ledger_failure");
        for (long userId : users) {
            jdbc.update("DELETE FROM wallet_ledger_entries WHERE user_id=?", userId);
            jdbc.update("DELETE FROM wallet_accounts WHERE user_id=?", userId);
            jdbc.update("DELETE FROM organization_members WHERE user_id=?", userId);
            jdbc.update("UPDATE users SET default_organization_id=NULL WHERE id=?", userId);
            jdbc.update("DELETE FROM organizations WHERE created_by=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
    }

    @Test
    void twoIndependentTransactionsCannotDuplicateOnePersonalBalance() throws Exception {
        User snapshot = personalWallet(100);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> attempt(start, snapshot));
            var second = pool.submit(() -> attempt(start, snapshot));
            start.countDown();
            String a = first.get(20, TimeUnit.SECONDS);
            String b = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a, b)).containsExactly("OK", "OK");
        } finally {
            pool.shutdownNow();
        }
        long userId = snapshot.getId();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(balance),0) FROM wallet_accounts WHERE user_id=? AND status='ACTIVE'", Long.class, userId)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE created_by=?", Integer.class, userId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_accounts WHERE user_id=? AND balance=100", Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void walletInsertFailureRollsBackDebitAndOrganization() {
        User snapshot = personalWallet(100);
        long userId = snapshot.getId();
        long originalOrg = snapshot.getDefaultOrganizationId();
        jdbc.execute("""
                CREATE TRIGGER linknux_security_force_ledger_failure
                BEFORE INSERT ON wallet_accounts FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic rollback test'
                """);
        assertThatThrownBy(() -> organizations.create(snapshot, "rollback-" + UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, originalOrg, userId)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT default_organization_id FROM users WHERE id=?", Long.class, userId)).isEqualTo(originalOrg);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE created_by=?", Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void unusedZeroBalanceAccountIsRemovedOnMySql() {
        User account = personalWallet(0);
        jdbc.update("UPDATE users SET password=? WHERE id=?", passwords.encode("synthetic-password"), account.getId());
        deletion.deleteUnusedPersonalAccount(account.getId(), "synthetic-password");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Integer.class, account.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE id=?", Integer.class, account.getDefaultOrganizationId())).isZero();
    }
    @Test
    void companyMemberAllocationCannotBecomePersonalTreasury() {
        User snapshot = personalWallet(0);
        long userId = snapshot.getId();
        long org = snapshot.getDefaultOrganizationId();
        jdbc.update("UPDATE organizations SET organization_type='COMPANY' WHERE id=?", org);
        jdbc.update("UPDATE organization_members SET member_role='MEMBER' WHERE organization_id=? AND user_id=?", org, userId);
        jdbc.update("UPDATE wallet_accounts SET account_type='MEMBER',balance=100 WHERE organization_id=? AND user_id=?", org, userId);

        organizations.create(snapshot, "allowed-empty-" + UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, org, userId)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE created_by=?", Integer.class, userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(balance),0) FROM wallet_accounts WHERE user_id=? AND account_type='TREASURY'", Long.class, userId)).isZero();
    }

    private String attempt(CountDownLatch start, User user) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) return "TIMEOUT";
            organizations.create(user, "concurrent-" + UUID.randomUUID());
            return "OK";
        } catch (ResponseStatusException error) {
            return error.getStatusCode().value() == 409 ? "CONFLICT" : "HTTP_" + error.getStatusCode().value();
        } catch (Exception error) {
            return error.getClass().getSimpleName();
        }
    }

    private User personalWallet(long balance) {
        String name = "synthetic-security-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance,account_type) VALUES (?,'',?,'local','USER','ACTIVE',?,'PERSONAL')",
                name, name + "@example.invalid", balance);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        users.add(userId);
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)",
                name, userId, now, now);
        long orgId = jdbc.queryForObject("SELECT id FROM organizations WHERE created_by=?", Long.class, userId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", orgId, userId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',?,'ACTIVE',?,?)",
                orgId, userId, balance, now, now);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", orgId, userId);
        return User.builder().id(userId).balance(balance).defaultOrganizationId(orgId).build();
    }
}
