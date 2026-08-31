package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderPriceTemplateServiceTests {
    private ProviderPriceTemplateService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:price-template-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE provider_price_templates(id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(160),platform VARCHAR(32),
                model_pattern VARCHAR(160),priority INT,pricing_unit VARCHAR(32),official_price_json TEXT,cost_price_json TEXT,
                sale_price_json TEXT,source_url VARCHAR(1000),source_note VARCHAR(500),enabled BOOLEAN,created_at DATETIME,updated_at DATETIME)
                """);
        service = new ProviderPriceTemplateService(jdbc, new ObjectMapper());
        create("fallback", "*", 999, 1);
        create("glob-low", "gpt-*", 1, 2);
        create("glob-high", "gpt-*", 20, 3);
        create("exact", "gpt-5", 0, 4);
    }

    @Test
    void matchingUsesExactThenGlobThenWildcardAndPriorityWithinSameLevel() {
        assertThat(service.match("CODEX", "gpt-5", null).templateId()).isEqualTo(4);
        assertThat(service.match("CODEX", "gpt-6", null).templateId()).isEqualTo(3);
        assertThat(service.match("CODEX", "o3", null).templateId()).isEqualTo(1);
        assertThat(service.match("CLAUDE", "claude-x", null)).isNull();
    }

    private void create(String name, String pattern, int priority, int expectedId) {
        Map<String, Object> row = service.create(Map.of("name", name, "platform", "CODEX", "modelPattern", pattern,
                "priority", priority, "pricingUnit", "TOKEN", "officialPrice", Map.of("inputPerMillion", 1),
                "costPrice", Map.of("inputPerMillion", 1), "salePrice", Map.of("inputPerMillion", 2)));
        assertThat(((Number) row.get("id")).intValue()).isEqualTo(expectedId);
    }
}
