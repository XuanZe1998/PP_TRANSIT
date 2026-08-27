package com.transit.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NvidiaCatalogServiceTests {

    @Test
    void nvidiaCatalogIsDiscoveredDynamicallyInsteadOfUsingTheOldSixModelAllowlist() {
        assertThat(List.of(NvidiaCatalogService.class.getDeclaredMethods()))
                .extracting(Method::getName)
                .doesNotContain("chatModelIds");
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("catalog/nvidia-models.yaml")) {
            assertThat(input).isNotNull();
            Map<String, Object> snapshot = new Yaml().load(input);
            assertThat((List<?>) snapshot.get("models")).hasSize(102).doesNotHaveDuplicates();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void versionedHaoeeManifestContainsTheFullReviewedCatalog() {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("catalog/haoee-models.yaml")) {
            assertThat(input).isNotNull();
            Map<String, Object> manifest = new Yaml().load(input);
            List<Map<String, Object>> models = (List<Map<String, Object>>) manifest.get("models");

            assertThat(models).hasSizeGreaterThanOrEqualTo(70);
            assertThat(models).extracting(row -> row.get("name"))
                    .contains("claude-opus-4-8", "gpt-5.5", "suno-v5.5", "text-embedding-v4");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
