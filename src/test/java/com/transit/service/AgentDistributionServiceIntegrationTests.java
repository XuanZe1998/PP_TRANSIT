package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "features.linknux.agent.enabled=true")
class AgentDistributionServiceIntegrationTests {
    @Autowired AgentDistributionService agents;
    @Autowired JdbcTemplate jdbc;

    @Test
    void settlesGrossProfitWithImmediateRebateFrozenCommissionAndIdempotency() {
        long agentId = user("agent");
        long customerId = user("customer");
        String invite = "LX" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("INSERT INTO agent_profiles(user_id,tier_code,invite_code,status,requested_rebate_bps,approved_at,created_at,updated_at) VALUES (?,'BASIC',?,'ACTIVE',200,?,?,?)",
                agentId, invite, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        jdbc.update("INSERT INTO agent_customer_bindings(customer_user_id,agent_user_id,invite_code,customer_rebate_bps,binding_source,bound_at) VALUES (?,?,?,?,?,?)",
                customerId, agentId, invite, 200, "TEST", LocalDateTime.now());
        String event = "req_" + UUID.randomUUID();

        agents.settleApiUsage(event, customerId, 10_000, 5_000);
        agents.settleApiUsage(event, customerId, 10_000, 5_000);

        assertThat(jdbc.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, customerId)).isEqualTo(200L);
        assertThat(jdbc.queryForObject("SELECT customer_rebate_amount FROM agent_commission_events WHERE business_event_id=?", Long.class, event)).isEqualTo(200L);
        assertThat(jdbc.queryForObject("SELECT agent_commission_amount FROM agent_commission_events WHERE business_event_id=?", Long.class, event)).isEqualTo(50L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_commission_ledger WHERE business_event_id=?", Long.class, event)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_commission_ledger WHERE business_event_id=? AND entry_type='AGENT_COMMISSION'", String.class, event)).isEqualTo("FROZEN");
    }

    @Test
    void rebateAndCommissionTransferKeepPersonalTreasurySynchronized() {
        long agentId = user("agent-wallet");
        long customerId = user("customer-wallet");
        long agentWalletId = personalWallet(agentId);
        long customerWalletId = personalWallet(customerId);
        String invite = "LX" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("INSERT INTO agent_profiles(user_id,tier_code,invite_code,status,requested_rebate_bps,approved_at,created_at,updated_at) VALUES (?,'BASIC',?,'ACTIVE',200,?,?,?)",
                agentId, invite, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        jdbc.update("INSERT INTO agent_customer_bindings(customer_user_id,agent_user_id,invite_code,customer_rebate_bps,binding_source,bound_at) VALUES (?,?,?,?,?,?)",
                customerId, agentId, invite, 200, "TEST", LocalDateTime.now());
        String event = "req_" + UUID.randomUUID();

        agents.settleApiUsage(event, customerId, 10_000, 5_000);
        assertThat(balance(customerId)).isEqualTo(200L);
        assertThat(walletBalance(customerWalletId)).isEqualTo(200L);

        jdbc.update("UPDATE agent_commission_ledger SET status='AVAILABLE' WHERE business_event_id=? AND entry_type='AGENT_COMMISSION'", event);
        String transfer = "transfer_" + UUID.randomUUID();
        agents.transferToBalance(agentId, 50, transfer);
        agents.transferToBalance(agentId, 50, transfer);
        assertThat(balance(agentId)).isEqualTo(50L);
        assertThat(walletBalance(agentWalletId)).isEqualTo(50L);
    }

    private long personalWallet(long userId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)",
                "agent-test-" + UUID.randomUUID(), userId, now, now);
        long organizationId = jdbc.queryForObject("SELECT MAX(id) FROM organizations WHERE created_by=?", Long.class, userId);
        jdbc.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)", organizationId, userId, now);
        jdbc.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',0,'ACTIVE',?,?)", organizationId, userId, now, now);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", organizationId, userId);
        return jdbc.queryForObject("SELECT id FROM wallet_accounts WHERE organization_id=? AND user_id=?", Long.class, organizationId, userId);
    }

    private long balance(long userId) {
        return jdbc.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, userId);
    }

    private long walletBalance(long walletId) {
        return jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE id=?", Long.class, walletId);
    }

    private long user(String prefix) {
        String name = prefix + "-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users(username,password,email,auth_provider,role,status,balance) VALUES (?,'',?,'local','USER','ACTIVE',0)", name, name);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
    }
}
