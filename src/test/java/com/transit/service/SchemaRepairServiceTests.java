package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaRepairServiceTests {

    @Test
    void consolidatesSameSiteSameNameGroupMappingsIntoOneSiteIdentity() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:site_public_identity;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE upstream_sites(id BIGINT PRIMARY KEY,public_code VARCHAR(80),public_name VARCHAR(120),badge_text VARCHAR(40),badge_color VARCHAR(16))");
        jdbc.execute("CREATE TABLE upstream_site_channels(channel_id BIGINT PRIMARY KEY,site_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE upstream_display_mappings(channel_id BIGINT,public_code VARCHAR(80),public_name VARCHAR(120),badge_text VARCHAR(40),badge_color VARCHAR(16),enabled BOOLEAN)");
        jdbc.update("INSERT INTO upstream_sites(id) VALUES (9)");
        jdbc.update("INSERT INTO upstream_site_channels(channel_id,site_id) VALUES (35,9),(42,9),(43,9)");
        jdbc.update("INSERT INTO upstream_display_mappings(channel_id,public_code,public_name,enabled) VALUES (35,'new-api-35','ahh',TRUE),(42,'new-api-42','ahh',TRUE),(43,'new-api-43','ahh',TRUE)");
        SchemaRepairService service = new SchemaRepairService(jdbc);

        assertThat(service.consolidateDuplicateSitePublicMappings()).isEqualTo(3);
        assertThat(service.consolidateDuplicateSitePublicMappings()).isZero();
        assertThat(jdbc.queryForObject("SELECT public_code FROM upstream_sites WHERE id=9", String.class)).isEqualTo("site-9");
        assertThat(jdbc.queryForObject("SELECT public_name FROM upstream_sites WHERE id=9", String.class)).isEqualTo("ahh");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_display_mappings", Integer.class)).isZero();
        assertThat(new PublicUpstreamMappingService(jdbc).forChannels(List.of(35L, 42L, 43L)).values())
                .extracting(upstream -> upstream.getCode() + ":" + upstream.getName())
                .containsOnly("site-9:ahh");
    }

    @Test
    void repairsExistingNewApiImageChannelsAndIsIdempotent() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:new_api_image_protocol;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE channels (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT, source_code VARCHAR(80),
                    protocol_type VARCHAR(80), health_status VARCHAR(40)
                )
                """);
        jdbc.execute("""
                CREATE TABLE model_mappings (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT, channel_id BIGINT, channel_model_name VARCHAR(160),
                    capability VARCHAR(40), input_modalities VARCHAR(255), output_modalities VARCHAR(255),
                    protocols VARCHAR(255), endpoint_path VARCHAR(500)
                )
                """);
        jdbc.update("INSERT INTO channels(source_code,protocol_type,health_status) VALUES ('new-api','openai-chat','DEGRADED')");
        Long imageChannel = jdbc.queryForObject("SELECT id FROM channels", Long.class);
        jdbc.update("INSERT INTO model_mappings(channel_id,channel_model_name,capability,input_modalities,output_modalities,protocols) VALUES (?,'gpt-image-2','text','text','text','chat-completions')", imageChannel);
        jdbc.update("INSERT INTO channels(source_code,protocol_type,health_status) VALUES ('new-api','openai-chat','HEALTHY')");
        Long textChannel = jdbc.queryForObject("SELECT MAX(id) FROM channels", Long.class);
        jdbc.update("INSERT INTO model_mappings(channel_id,channel_model_name,capability,input_modalities,output_modalities,protocols) VALUES (?,'gpt-5.6-terra','text','text','text','chat-completions')", textChannel);
        SchemaRepairService service = new SchemaRepairService(jdbc);

        assertThat(service.repairNewApiImageProtocols()).isEqualTo(2);
        assertThat(service.repairNewApiImageProtocols()).isZero();
        assertThat(jdbc.queryForObject("SELECT protocol_type FROM channels WHERE id=?", String.class, imageChannel))
                .isEqualTo("openai-image");
        assertThat(jdbc.queryForObject("SELECT health_status FROM channels WHERE id=?", String.class, imageChannel))
                .isEqualTo("UNTESTED");
        assertThat(jdbc.queryForObject("SELECT protocols FROM model_mappings WHERE channel_id=?", String.class, imageChannel))
                .isEqualTo("images");
        assertThat(jdbc.queryForObject("SELECT endpoint_path FROM model_mappings WHERE channel_id=?", String.class, imageChannel))
                .isEqualTo("/v1/images/generations");
        assertThat(jdbc.queryForObject("SELECT protocol_type FROM channels WHERE id=?", String.class, textChannel))
                .isEqualTo("openai-chat");
    }

    @Test
    void backfillsLegacyChannelModelsWithoutOverwritingExistingPricing() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:channel_mapping_backfill;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE channels (
                    source_code VARCHAR(50),
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
        jdbc.update("INSERT INTO channels(source_code,models) VALUES ('haoee','managed-new-model')");
        SchemaRepairService service = new SchemaRepairService(jdbc);

        assertThat(service.backfillChannelModelMappings()).isEqualTo(1);
        assertThat(service.backfillChannelModelMappings()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE channel_model_name='managed-new-model'",Integer.class)).isZero();
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
