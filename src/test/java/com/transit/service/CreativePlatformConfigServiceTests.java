package com.transit.service;

import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CreativePlatformConfigServiceTests {
    @Autowired CreativePlatformConfigService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void encryptsPlatformKeysAndMaintainsOneDefaultPerCapability() {
        User admin = new User(); admin.setId(99L);
        Map<String, Object> first = service.createConnection(admin, Map.of(
                "capability", "TEXT", "provider", "openai-chat", "displayName", "主文本",
                "baseUrl", "https://api.example.com", "apiKey", "secret-first",
                "models", List.of("model-a"), "defaultModel", "model-a", "enabled", true, "isDefault", true));
        service.createConnection(admin, Map.of(
                "capability", "TEXT", "provider", "openai-chat", "displayName", "备用文本",
                "baseUrl", "https://api2.example.com", "apiKey", "secret-second",
                "models", List.of("model-b"), "defaultModel", "model-b", "enabled", true, "isDefault", true));

        String stored = jdbc.queryForObject("SELECT api_key FROM creative_platform_connections WHERE id=?", String.class, first.get("id"));
        assertThat(stored).startsWith("enc:v1:").doesNotContain("secret-first");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creative_platform_connections WHERE capability='TEXT' AND is_default=TRUE", Integer.class)).isEqualTo(1);
        assertThat(service.platformAccess("TEXT", true).apiKey()).isEqualTo("secret-second");
        assertThat(service.connections()).allSatisfy(item -> {
            assertThat(item).doesNotContainKey("apiKey");
            assertThat(item.get("apiKeyPreview")).isEqualTo("****");
        });
    }

    @Test
    void encryptsStorageCredentialsAndKeepsThemOutOfViews() {
        User admin = new User(); admin.setId(99L);
        Map<String, Object> current = service.storageView();
        Map<String, Object> updated = service.updateStorage(admin, Map.of(
                "version", current.get("version"), "endpoint", "https://s3.example.com",
                "region", "auto", "bucket", "creative", "publicBaseUrl", "https://cdn.example.com",
                "accessKey", "access-secret", "secretKey", "storage-secret", "enabled", true));

        assertThat(updated.get("accessKeyPreview")).isEqualTo("****");
        assertThat(updated).doesNotContainKeys("accessKey", "secretKey");
        Map<String, Object> stored = jdbc.queryForMap("SELECT access_key,secret_key FROM creative_storage_configs WHERE id=1");
        assertThat(stored.get("access_key").toString()).startsWith("enc:v1:").doesNotContain("access-secret");
        assertThat(stored.get("secret_key").toString()).startsWith("enc:v1:").doesNotContain("storage-secret");
    }
}
