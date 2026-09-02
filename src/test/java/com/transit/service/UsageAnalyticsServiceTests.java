package com.transit.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageAnalyticsServiceTests {
    private JdbcTemplate jdbc;
    private UsageAnalyticsService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:usage_analytics_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE organizations(id BIGINT PRIMARY KEY,organization_type VARCHAR(24))");
        jdbc.execute("""
                CREATE TABLE logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,created_at DATETIME,user_id BIGINT,organization_id BIGINT,
                model VARCHAR(160),token_id BIGINT,status VARCHAR(24),prompt_tokens BIGINT,completion_tokens BIGINT,
                cache_read_tokens BIGINT,cache_write_tokens BIGINT,cache_miss_tokens BIGINT,total_tokens BIGINT,
                total_amount BIGINT,sale_amount BIGINT,cost BIGINT,cost_amount BIGINT,settlement_amount BIGINT)
                """);
        jdbc.update("INSERT INTO organizations VALUES (1,'PERSONAL'),(2,'COMPANY')");
        insert(1, 1, "model-a", 100, 20, 1000, 400);
        insert(2, 2, "model-b", 200, 50, 3000, 1800);
        service = new UsageAnalyticsService(jdbc);
    }

    @Test
    void returnsDailyByModelAndProfitWithoutMixingCurrencies() {
        Map<String, Object> result = service.analytics(null, null, "2026-08-25", "2026-08-25", null, null, null, null, null);
        assertEquals(2, ((List<?>) result.get("dailyByModel")).size());
        Map<?, ?> totals = (Map<?, ?>) result.get("totals");
        assertEquals(4000L, ((Number) value(totals, "total_amount")).longValue());
        assertEquals(1800L, ((Number) value(totals, "profit_amount")).longValue());
        assertEquals("USD", result.get("currency"));
    }

    @Test
    void companyAndOrganizationFiltersExcludePersonalUsage() {
        Map<String, Object> result = service.analytics(null, null, "2026-08-25", "2026-08-25", null, null, null, "COMPANY", 2L);
        Map<?, ?> totals = (Map<?, ?>) result.get("totals");
        assertEquals(250L, ((Number) value(totals, "total_tokens")).longValue());
        assertEquals(1200L, ((Number) value(totals, "profit_amount")).longValue());
    }

    private void insert(long organizationId, long userId, String model, long prompt, long output, long sale, long cost) {
        jdbc.update("""
                INSERT INTO logs(created_at,user_id,organization_id,model,token_id,status,prompt_tokens,completion_tokens,
                cache_read_tokens,cache_write_tokens,cache_miss_tokens,total_tokens,total_amount,cost_amount,settlement_amount)
                VALUES ('2026-08-25 12:00:00',?,?,?,?, 'SUCCESS',?,?,0,0,?,?,?, ?,0)
                """, userId, organizationId, model, userId, prompt, output, prompt, prompt + output, sale, cost);
    }

    private Object value(Map<?, ?> row, String key) {
        return row.containsKey(key) ? row.get(key) : row.get(key.toUpperCase());
    }
}
