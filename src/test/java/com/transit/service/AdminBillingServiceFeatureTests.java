package com.transit.service;

import com.transit.dto.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AdminBillingServiceFeatureTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminBillingService billing;

    @Test
    void rechargeSummaryExcludesAdjustmentsAndTransactionsAreServerPaged() {
        long before = ((Number) billing.financeSummary().get("rechargeAmount")).longValue();
        String username = "finance-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password,email,role,status,balance) VALUES (?,'x',?,'USER','ACTIVE',1110)",
                username, username + "@example.com");
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        transaction(userId, "RECHARGE", 100, "bank", "feature recharge");
        transaction(userId, "REDEEM", 40, "code", "feature redeem");
        transaction(userId, "ADJUST", 1000, "admin", "feature adjustment");
        transaction(userId, "CONSUME", -30, "api", "feature consume");

        long after = ((Number) billing.financeSummary().get("rechargeAmount")).longValue();
        assertThat(after - before).isEqualTo(140);

        PageResponse<Map<String, Object>> first = billing.transactionsPage(1, 2, username);
        PageResponse<Map<String, Object>> second = billing.transactionsPage(2, 2, username);
        assertThat(first.getTotal()).isEqualTo(4);
        assertThat(first.getItems()).hasSize(2);
        assertThat(second.getItems()).hasSize(2);
        assertThat(billing.transactionsPage(1, 20, "feature adjustment").getItems())
                .extracting(row -> row.get("type")).contains("ADJUST");
    }

    private void transaction(long userId, String type, long amount, String channel, String remark) {
        jdbc.update("""
                INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark)
                VALUES (?,?,?,?,?,?)
                """, userId, type, amount, 0, channel, remark);
    }
}
