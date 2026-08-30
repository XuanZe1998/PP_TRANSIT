package com.transit.service;

import com.transit.dto.PublicModel;
import com.transit.dto.PageResponse;
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
    @Autowired private ProviderModelCatalogService providerModelCatalogService;

    @Test
    void publishesOnlyModelsThatTheGatewayCanActuallyRoute() {
        long healthy = channel("catalog-healthy", "openai", "HEALTHY", null);
        long degraded = channel("catalog-degraded", "deepseek", "DEGRADED", null);
        long untested = channel("catalog-untested", "openai", "UNTESTED", null);
        long cooling = channel("catalog-cooling", "openai", "COOLDOWN", LocalDateTime.now().plusMinutes(5));
        long recovered = channel("catalog-recovered", "openai", "COOLDOWN", LocalDateTime.now().minusMinutes(1));

        mapping("catalog-shared-model", healthy, true);
        mapping("catalog-shared-model", degraded, true);
        unpricedMapping("catalog-shared-model", healthy, false);
        unpricedMapping("catalog-unpriced-only", healthy, false);
        mapping("catalog-hidden-untested", untested, true);
        mapping("catalog-hidden-cooling", cooling, true);
        mapping("catalog-recovered-model", recovered, true);
        mapping("catalog-disabled-mapping", healthy, false);

        List<PublicModel> models = modelMappingMapper.findPublicModels();

        assertThat(models).extracting(PublicModel::getPublicName)
                .contains("catalog-shared-model", "catalog-recovered-model", "catalog-unpriced-only")
                .doesNotContain("catalog-hidden-untested", "catalog-hidden-cooling", "catalog-disabled-mapping");
        PublicModel shared = models.stream()
                .filter(model -> "catalog-shared-model".equals(model.getPublicName()))
                .findFirst()
                .orElseThrow();
        assertThat(shared.getRouteCount()).isEqualTo(2);
        assertThat(shared.getProviderCount()).isEqualTo(2);
        assertThat(shared.getType()).isEqualTo("multi");
        assertThat(shared.getMinInputPricePerMillion()).isEqualByComparingTo("1.25");
        assertThat(shared.getMaxInputPricePerMillion()).isEqualByComparingTo("1.25");
        assertThat(shared.isBillingConfigured()).isTrue();
        PublicModel pending = models.stream()
                .filter(model -> "catalog-unpriced-only".equals(model.getPublicName()))
                .findFirst().orElseThrow();
        assertThat(pending.isAvailable()).isTrue();
        assertThat(pending.isBillingConfigured()).isFalse();
        assertThat(pending.getMaxInputPricePerMillion()).isEqualByComparingTo("0");
    }

    @Test
    void fullCatalogCanFilterUnverifiedModelsWithoutMakingThemRoutable() {
        jdbcTemplate.update("""
                INSERT INTO provider_models(
                    source_code, source_name, upstream_model_name, public_model_name, vendor,
                    capability, protocols, verification_status, verification_message,
                    last_seen_at, created_at, updated_at)
                VALUES ('haoee', '好易智算', 'catalog-discovered-image', 'catalog-discovered-image',
                        'test-vendor', 'image', 'images', 'DISCOVERED', '等待人工付费验证', NOW(), NOW(), NOW())
                """);

        PageResponse<PublicModel> all = providerModelCatalogService.publicCatalog(
                1, 100, "catalog-discovered", "haoee", "image", "test-vendor", "all");
        PageResponse<PublicModel> available = providerModelCatalogService.publicCatalog(
                1, 100, "catalog-discovered", "haoee", "image", "test-vendor", "available");

        assertThat(all.getTotal()).isEqualTo(1);
        assertThat(all.getItems().get(0).getVerificationStatus()).isEqualTo("DISCOVERED");
        assertThat(all.getItems().get(0).isAvailable()).isFalse();
        assertThat(available.getTotal()).isZero();
        assertThat(modelMappingMapper.findPublicModels()).extracting(PublicModel::getPublicName)
                .doesNotContain("catalog-discovered-image");

        Long modelId = jdbcTemplate.queryForObject(
                "SELECT id FROM provider_models WHERE upstream_model_name='catalog-discovered-image'", Long.class);
        providerModelCatalogService.beginVerification(modelId);
        providerModelCatalogService.completeVerification(modelId, false, "probe rejected");
        assertThat(providerModelCatalogService.verificationHistory(modelId, "haoee", 20))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.get("STATUS")).isEqualTo("FAILED");
                    assertThat(record.get("MESSAGE")).isEqualTo("probe rejected");
                });
    }

    @Test
    void catalogRefreshRespectsAChannelModelSelection() {
        jdbcTemplate.update("""
                INSERT INTO channels(name,type,source_code,source_name,protocol_type,base_url,api_key,models,
                                     enabled,health_status)
                VALUES ('selected-haoee','haoee','haoee','好易智算','multi','https://maas.haoee.com',
                        'encrypted-provider-key','gpt-5.4',TRUE,'UNTESTED')
                """);
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM channels WHERE name='selected-haoee'", Long.class);

        providerModelCatalogService.synchronizeHaoee(channelId);

        List<String> routed = jdbcTemplate.queryForList(
                "SELECT channel_model_name FROM model_mappings WHERE channel_id=? ORDER BY channel_model_name",
                String.class, channelId);
        assertThat(routed).containsExactly("gpt-5.4");
        assertThat(jdbcTemplate.queryForObject("SELECT models FROM channels WHERE id=?", String.class, channelId))
                .isEqualTo("gpt-5.4");
    }

    @Test
    void managedHaoeeCatalogAddsNewReviewedModelsToTheVerificationPipeline() {
        jdbcTemplate.update("""
                INSERT INTO channels(name,type,source_code,source_name,protocol_type,base_url,api_key,models,
                                     enabled,group_name,health_status)
                VALUES ('managed-haoee','haoee','haoee','好易智算','multi','https://maas.haoee.com',
                        'plaintext-test-key','gpt-5.4',TRUE,'haoee','HEALTHY')
                """);
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM channels WHERE name='managed-haoee'", Long.class);

        providerModelCatalogService.synchronizeHaoee(channelId);

        assertThat(jdbcTemplate.queryForObject("SELECT models FROM channels WHERE id=?", String.class, channelId))
                .contains("gpt-5.6-sol", "gpt-5.6-terra");
        assertThat(jdbcTemplate.queryForList("""
                SELECT channel_model_name FROM model_mappings
                WHERE channel_id=? AND channel_model_name LIKE 'gpt-5.6-%'
                ORDER BY channel_model_name
                """, String.class, channelId))
                .containsExactly("gpt-5.6-sol", "gpt-5.6-terra");
        assertThat(jdbcTemplate.queryForList("""
                SELECT enabled FROM model_mappings
                WHERE channel_id=? AND channel_model_name LIKE 'gpt-5.6-%'
                ORDER BY channel_model_name
                """, Boolean.class, channelId))
                .containsExactly(true, true);

        assertThat(jdbcTemplate.queryForList("""
                SELECT verification_status FROM provider_models
                WHERE source_code='haoee' AND upstream_model_name LIKE 'gpt-5.6-%'
                ORDER BY upstream_model_name
                """, String.class)).containsExactly("AVAILABLE", "AVAILABLE");
        assertThat(jdbcTemplate.queryForList("""
                SELECT pricing_status FROM model_mappings
                WHERE channel_id=? AND channel_model_name LIKE 'gpt-5.6-%'
                ORDER BY channel_model_name
                """, String.class, channelId)).containsExactly("VERIFIED", "VERIFIED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT input_price_per_million FROM model_mappings
                WHERE channel_id=? AND channel_model_name='gpt-5.6-sol'
                """, java.math.BigDecimal.class, channelId)).allMatch(price -> price.signum() > 0);
    }

    @Test
    void mapsTheRetiredClaude47PublicIdToTheLiveClaude48UpstreamId() {
        jdbcTemplate.update("""
                INSERT INTO channels(name,type,source_code,source_name,protocol_type,base_url,api_key,models,
                                     enabled,health_status)
                VALUES ('alias-haoee','haoee','haoee','好易智算','multi','https://maas.haoee.com',
                        'plaintext-test-key','claude-opus-4-7',TRUE,'HEALTHY')
                """);
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM channels WHERE name='alias-haoee'", Long.class);

        providerModelCatalogService.synchronizeHaoee(channelId);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT channel_model_name FROM model_mappings
                WHERE channel_id=? AND public_model_name='claude-opus-4-7'
                """, String.class, channelId)).isEqualTo("claude-opus-4-8");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT verification_status FROM provider_models
                WHERE source_code='haoee' AND upstream_model_name='claude-opus-4-7'
                """, String.class)).isEqualTo("RETIRED");
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

    private void unpricedMapping(String publicName, long channelId, boolean billingEnabled) {
        jdbcTemplate.update("""
                INSERT INTO model_mappings(
                    public_model_name, channel_model_name, channel_id, priority, enabled, billing_enabled,
                    input_price_per_million, output_price_per_million, cached_price_per_million
                ) VALUES (?, ?, ?, 9, TRUE, ?, 0, 0, 0)
                """, publicName, publicName + "-unpriced-provider", channelId, billingEnabled);
    }
}
