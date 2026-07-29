package com.transit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "nvida.key=test-shared-nvidia-key",
        "nvida.verify-on-startup=false"
})
class NvidiaCatalogBootstrapIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NvidiaCatalogService nvidiaCatalogService;

    @Autowired
    private ChannelSecretService channelSecretService;

    @Test
    @Order(1)
    void syncUsesOneEncryptedKeyAndKeepsModelsHiddenUntilVerified() {
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM channels WHERE name = ?", Long.class, NvidiaCatalogService.CHANNEL_NAME);
        String storedKey = jdbcTemplate.queryForObject(
                "SELECT api_key FROM channels WHERE id = ?", String.class, channelId);
        Long mappingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_mappings WHERE channel_id = ?", Long.class, channelId);
        Long enabledCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_mappings WHERE channel_id = ? AND enabled = TRUE", Long.class, channelId);

        assertThat(channelId).isNotNull();
        assertThat(channelSecretService.isEncrypted(storedKey)).isTrue();
        assertThat(channelSecretService.decrypt(storedKey)).isEqualTo("test-shared-nvidia-key");
        assertThat(mappingCount).isEqualTo(31);
        assertThat(enabledCount).isZero();
    }

    @Test
    @Order(2)
    void sameKeyPreservesVerifiedModelsAndRotatedKeyInvalidatesThem() {
        Long channelId = nvidiaCatalogService.syncCatalog();
        jdbcTemplate.update("""
                UPDATE model_mappings SET enabled = TRUE
                WHERE channel_id = ? AND channel_model_name = ?
                """, channelId, "z-ai/glm-5.2");

        nvidiaCatalogService.syncCatalog();
        assertThat(enabledMappings(channelId)).isEqualTo(1);

        ReflectionTestUtils.setField(nvidiaCatalogService, "configuredKey", "rotated-test-key");
        nvidiaCatalogService.syncCatalog();
        assertThat(enabledMappings(channelId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT health_status FROM channels WHERE id = ?", String.class, channelId))
                .isEqualTo("UNTESTED");
    }

    private Long enabledMappings(Long channelId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_mappings WHERE channel_id = ? AND enabled = TRUE", Long.class, channelId);
    }
}
