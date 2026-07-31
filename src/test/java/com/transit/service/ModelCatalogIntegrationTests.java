package com.transit.service;

import com.transit.dto.PublicModel;
import com.transit.mapper.ModelMappingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ModelCatalogIntegrationTests {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ModelMappingMapper modelMappingMapper;

    @Test
    void publishesOnlyModelsThatTheGatewayCanActuallyRoute() {
        long healthy = channel("catalog-healthy", "openai", "HEALTHY", null);
        long degraded = channel("catalog-degraded", "deepseek", "DEGRADED", null);
        long untested = channel("catalog-untested", "openai", "UNTESTED", null);
        long cooling = channel("catalog-cooling", "openai", "COOLDOWN", LocalDateTime.now().plusMinutes(5));
        long recovered = channel("catalog-recovered", "openai", "COOLDOWN", LocalDateTime.now().minusMinutes(1));

        mapping("catalog-shared-model", healthy, true);
        mapping("catalog-shared-model", degraded, true);
        mapping("catalog-hidden-untested", untested, true);
        mapping("catalog-hidden-cooling", cooling, true);
        mapping("catalog-recovered-model", recovered, true);
        mapping("catalog-disabled-mapping", healthy, false);

        List<PublicModel> models = modelMappingMapper.findPublicModels();

        assertThat(models).extracting(PublicModel::getPublicName)
                .contains("catalog-shared-model", "catalog-recovered-model")
                .doesNotContain("catalog-hidden-untested", "catalog-hidden-cooling", "catalog-disabled-mapping");
        PublicModel shared = models.stream()
                .filter(model -> "catalog-shared-model".equals(model.getPublicName()))
                .findFirst()
                .orElseThrow();
        assertThat(shared.getRouteCount()).isEqualTo(2);
        assertThat(shared.getProviderCount()).isEqualTo(2);
        assertThat(shared.getType()).isEqualTo("multi");
    }

    private long channel(String name, String type, String health, LocalDateTime cooldownUntil) {
        jdbcTemplate.update("""
                INSERT INTO channels(name, type, base_url, api_key, enabled, health_status, cooldown_until)
                VALUES (?, ?, 'https://provider.example.com', 'encrypted-provider-key', TRUE, ?, ?)
                """, name, type, health, cooldownUntil);
        return jdbcTemplate.queryForObject("SELECT id FROM channels WHERE name = ?", Long.class, name);
    }

    private void mapping(String publicName, long channelId, boolean enabled) {
        jdbcTemplate.update("""
                INSERT INTO model_mappings(
                    public_model_name, channel_model_name, channel_id, priority, enabled,
                    input_price_per_million, output_price_per_million, cached_price_per_million
                ) VALUES (?, ?, ?, 10, ?, 1.25, 4.5, 0.25)
                """, publicName, publicName + "-provider", channelId, enabled);
    }
}
