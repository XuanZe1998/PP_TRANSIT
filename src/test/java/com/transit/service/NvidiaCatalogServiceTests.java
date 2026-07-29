package com.transit.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NvidiaCatalogServiceTests {

    @Test
    void catalogContainsEveryChatModelFromDocsExactlyOnce() {
        List<String> models = NvidiaCatalogService.chatModelIds();

        assertThat(models).hasSize(31);
        assertThat(new HashSet<>(models)).hasSize(models.size());
        assertThat(models).contains(
                "deepseek-ai/deepseek-v4-flash",
                "google/gemma-4-31b-it",
                "nvidia/nemotron-3-ultra-550b-a55b",
                "openai/gpt-oss-20b",
                "z-ai/glm-5.2");
    }

    @Test
    void oneChannelModelsFitCurrentDatabaseColumn() {
        String modelCsv = String.join(",", NvidiaCatalogService.chatModelIds());

        assertThat(modelCsv.length()).isLessThanOrEqualTo(2_000);
    }
}
