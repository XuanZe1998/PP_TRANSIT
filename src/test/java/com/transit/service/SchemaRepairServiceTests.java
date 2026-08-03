package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaRepairServiceTests {

    @Test
    void backfillsLegacyChannelModelsWithoutOverwritingExistingPricing() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:channel_mapping_backfill;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE channels (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    models VARCHAR(2000)
                )
                """);
        jdbc.execute("""
                CREATE TABLE model_mappings (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    public_model_name VARCHAR(160) NOT NULL,
                    channel_model_name VARCHAR(160) NOT NULL,
                    channel_id BIGINT,
                    priority INT NOT NULL,
                    enabled BOOLEAN NOT NULL,
                    price_ratio DECIMAL(10,4) NOT NULL,
                    cost_per_million DECIMAL(12,6) NOT NULL,
                    input_price_per_million DECIMAL(18,6) NOT NULL,
                    output_price_per_million DECIMAL(18,6) NOT NULL,
                    cached_price_per_million DECIMAL(18,6) NOT NULL,
                    input_cost_per_million DECIMAL(18,6) NOT NULL,
                    output_cost_per_million DECIMAL(18,6) NOT NULL,
                    cached_cost_per_million DECIMAL(18,6) NOT NULL,
                    billing_enabled BOOLEAN NOT NULL,
                    traffic_percent INT NOT NULL,
                    created_at TIMESTAMP
                )
                """);
        jdbc.update("INSERT INTO channels(models) VALUES (?)", "model-a、model-b\nmodel-a");
        Long channelId = jdbc.queryForObject("SELECT id FROM channels", Long.class);
        jdbc.update("""
                INSERT INTO model_mappings(
                    public_model_name, channel_model_name, channel_id, priority, enabled,
                    price_ratio, cost_per_million,
                    input_price_per_million, output_price_per_million, cached_price_per_million,
                    input_cost_per_million, output_cost_per_million, cached_cost_per_million,
                    billing_enabled, traffic_percent, created_at
                ) VALUES (?, ?, ?, 20, TRUE, 3, 0, 7, 9, 0, 2, 3, 0, TRUE, 100, CURRENT_TIMESTAMP)
                """, "model-b", "model-b", channelId);
        SchemaRepairService service = new SchemaRepairService(jdbc);

        assertThat(service.backfillChannelModelMappings()).isEqualTo(1);
        assertThat(service.backfillChannelModelMappings()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_mappings WHERE channel_id = ?", Integer.class, channelId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                        SELECT input_price_per_million FROM model_mappings
                        WHERE channel_id = ? AND channel_model_name = 'model-b'
                        """, BigDecimal.class, channelId))
                .isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("""
                        SELECT input_price_per_million FROM model_mappings
                        WHERE channel_id = ? AND channel_model_name = 'model-a'
                        """, BigDecimal.class, channelId))
                .isEqualByComparingTo("1");
    }

    @Test
    void backfillsOneUnlimitedTierFromLegacyFlatPricingAndIsIdempotent() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:price_tier_backfill;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE model_mappings (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    input_price_per_million DECIMAL(18,6),
                    output_price_per_million DECIMAL(18,6),
                    cached_price_per_million DECIMAL(18,6),
                    cost_per_million DECIMAL(18,6),
                    input_cost_per_million DECIMAL(18,6),
                    output_cost_per_million DECIMAL(18,6),
                    cached_cost_per_million DECIMAL(18,6),
                    price_ratio DECIMAL(10,4)
                )
                """);
        jdbc.execute("""
                CREATE TABLE model_price_tiers (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    model_mapping_id BIGINT NOT NULL,
                    tier_name VARCHAR(120), max_context_tokens INT, sort_order INT,
                    official_group_name VARCHAR(120), official_input_price DECIMAL(18,6),
                    official_output_price DECIMAL(18,6), official_cache_read_price DECIMAL(18,6),
                    official_cache_write_price DECIMAL(18,6), cost_group_name VARCHAR(120),
                    cost_input_price DECIMAL(18,6), cost_output_price DECIMAL(18,6),
                    cost_cache_read_price DECIMAL(18,6), cost_cache_write_price DECIMAL(18,6),
                    sale_group_name VARCHAR(120), sale_input_price DECIMAL(18,6),
                    sale_output_price DECIMAL(18,6), sale_cache_read_price DECIMAL(18,6),
                    sale_cache_write_price DECIMAL(18,6), created_at TIMESTAMP, updated_at TIMESTAMP
                )
                """);
        jdbc.update("""
                INSERT INTO model_mappings(
                    input_price_per_million, output_price_per_million, cached_price_per_million,
                    cost_per_million, input_cost_per_million, output_cost_per_million,
                    cached_cost_per_million, price_ratio
                ) VALUES (7, 9, 1, 0, 2, 3, 0.5, 3)
                """);
        SchemaRepairService service = new SchemaRepairService(jdbc);

        assertThat(service.backfillModelPriceTiers()).isEqualTo(1);
        assertThat(service.backfillModelPriceTiers()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_price_tiers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT max_context_tokens FROM model_price_tiers", Integer.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT sale_input_price FROM model_price_tiers", BigDecimal.class))
                .isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT cost_output_price FROM model_price_tiers", BigDecimal.class))
                .isEqualByComparingTo("3");
    }
}
