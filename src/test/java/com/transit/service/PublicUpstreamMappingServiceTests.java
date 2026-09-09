package com.transit.service;

import com.transit.dto.PublicModel;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicUpstreamMappingServiceTests {

    @Test
    void staleGroupAliasesCollapseToOneSiteFacetBeforeDatabaseRepairRuns() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:public_site_routes;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE upstream_sites(id BIGINT PRIMARY KEY,public_code VARCHAR(80),public_name VARCHAR(120),badge_text VARCHAR(40),badge_color VARCHAR(16))");
        jdbc.execute("CREATE TABLE upstream_site_channels(channel_id BIGINT PRIMARY KEY,site_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE upstream_display_mappings(channel_id BIGINT,public_code VARCHAR(80),public_name VARCHAR(120),badge_text VARCHAR(40),badge_color VARCHAR(16),enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE model_mappings(channel_id BIGINT,public_model_name VARCHAR(160),enabled BOOLEAN)");
        jdbc.update("INSERT INTO upstream_sites(id,public_code,public_name,badge_text,badge_color) VALUES (8,'aab','AAB','AAB','#6d5dfc')");
        jdbc.update("INSERT INTO upstream_site_channels(channel_id,site_id) VALUES (24,8),(33,8)");
        jdbc.update("INSERT INTO upstream_display_mappings(channel_id,public_code,public_name,enabled) VALUES (24,'group-24','AAB',TRUE),(33,'group-33','AAB',TRUE)");
        jdbc.update("INSERT INTO model_mappings(channel_id,public_model_name,enabled) VALUES (24,'model-a',TRUE),(33,'model-b',TRUE)");
        PublicUpstreamMappingService mappings = new PublicUpstreamMappingService(jdbc);
        PublicModel first = new PublicModel(); first.setPublicName("model-a");
        PublicModel second = new PublicModel(); second.setPublicName("model-b");

        mappings.sanitize(List.of(first, second));

        assertThat(first.getUpstreams()).extracting(item -> item.getCode() + ":" + item.getName())
                .containsExactly("aab:AAB");
        assertThat(second.getUpstreams()).extracting(item -> item.getCode() + ":" + item.getName())
                .containsExactly("aab:AAB");
        assertThat(new PublicModelMarketplaceService().facets(List.of(first, second),
                PublicModelMarketplaceService.criteria(null, null, null, null, null,
                        null, null, null, null, null, null)).get("routes"))
                .extracting(option -> option.value() + ":" + option.label() + ":" + option.count())
                .containsExactly("aab:AAB:2");
    }

    @Test
    void consistentLegacyNamesStillUseOneSyntheticSiteCodeWhenSiteDisplayIsBlank() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:blank_public_site_routes;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE upstream_sites(id BIGINT PRIMARY KEY,public_code VARCHAR(80),public_name VARCHAR(120),badge_text VARCHAR(40),badge_color VARCHAR(16))");
        jdbc.execute("CREATE TABLE upstream_site_channels(channel_id BIGINT PRIMARY KEY,site_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE upstream_display_mappings(channel_id BIGINT,public_code VARCHAR(80),public_name VARCHAR(120),badge_text VARCHAR(40),badge_color VARCHAR(16),enabled BOOLEAN)");
        jdbc.update("INSERT INTO upstream_sites(id) VALUES (9)");
        jdbc.update("INSERT INTO upstream_site_channels(channel_id,site_id) VALUES (24,9),(33,9)");
        jdbc.update("INSERT INTO upstream_display_mappings(channel_id,public_code,public_name,enabled) VALUES (24,'group-24','AAB',TRUE),(33,'group-33','AAB',TRUE)");

        assertThat(new PublicUpstreamMappingService(jdbc).forChannels(List.of(24L, 33L)).values())
                .extracting(item -> item.getCode() + ":" + item.getName())
                .containsOnly("site-9:AAB");
    }
}
