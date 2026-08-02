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
}
