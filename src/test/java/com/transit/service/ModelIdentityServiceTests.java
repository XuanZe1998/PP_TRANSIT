package com.transit.service;

import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ModelIdentityServiceTests {
    private JdbcTemplate jdbc;
    private ModelIdentityService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:model-identities;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE model_catalog_identities(comparison_key VARCHAR(240) PRIMARY KEY,display_name VARCHAR(200),publisher_code VARCHAR(80),publisher_name VARCHAR(120),category VARCHAR(40),capability VARCHAR(40),input_modalities VARCHAR(255),output_modalities VARCHAR(255),protocols VARCHAR(255),metadata_rank INT,metadata_source VARCHAR(80),created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE model_identity_aliases(id BIGINT AUTO_INCREMENT PRIMARY KEY,source_code VARCHAR(80),upstream_model_name VARCHAR(200),comparison_key VARCHAR(240),explicit_override BOOLEAN NOT NULL DEFAULT FALSE,created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(source_code,upstream_model_name))");
        service = new ModelIdentityService(jdbc);
    }

    @Test
    void normalizesPublisherPrefixButKeepsVersionIdentity() {
        String prefixed = service.register("haoee", "deepseek-ai/deepseek-v4-pro-0813", "deepseek",
                "reasoning", "text", "text", "chat-completions", ModelIdentityService.RANK_MAPPING);
        String plain = service.register("aiapibank", "deepseek-v4-pro-0813", "deepseek",
                "reasoning", "text", "text", "chat-completions", ModelIdentityService.RANK_PROVIDER_CATALOG);
        String otherVersion = service.register("aiapibank", "deepseek-v4-pro-0814", "deepseek",
                "reasoning", "text", "text", "chat-completions", ModelIdentityService.RANK_PROVIDER_CATALOG);

        assertThat(prefixed).isEqualTo(plain);
        assertThat(otherVersion).isNotEqualTo(plain);
    }

    @Test
    void higherRankMetadataWinsAcrossChannels() {
        String key = service.register("haoee", "gpt-5.6", "openai", "text", "text", "text",
                "chat-completions", ModelIdentityService.RANK_MAPPING);
        service.register("aiapibank", "gpt-5.6", "openai", "reasoning", "text,image", "text",
                "responses,chat-completions", ModelIdentityService.RANK_PROVIDER_CATALOG);
        service.register("other", "gpt-5.6", "unknown", "text", "text", "text",
                "chat-completions", ModelIdentityService.RANK_INFERRED);

        var row = jdbc.queryForMap("SELECT * FROM model_catalog_identities WHERE comparison_key=?", key);
        assertThat(row.get("CAPABILITY")).isEqualTo("reasoning");
        assertThat(row.get("INPUT_MODALITIES")).isEqualTo("text,image");
        assertThat(row.get("METADATA_RANK")).isEqualTo(ModelIdentityService.RANK_PROVIDER_CATALOG);
    }

    @Test
    void stablePublicVersionWinsUnlessAliasIsExplicitlyOverridden() {
        Channel channel = Channel.builder().sourceCode("haoee").type("openai").build();
        ModelMapping mapping = ModelMapping.builder().publicModelName("claude-opus-4-7")
                .channelModelName("anthropic/claude-opus-4-8").vendor("anthropic").build();

        String key = service.register(channel, mapping, "anthropic", ModelIdentityService.RANK_MAPPING);
        assertThat(key).isEqualTo("anthropic:claude-opus-4-7");

        jdbc.update("UPDATE model_identity_aliases SET comparison_key=?,explicit_override=TRUE", "anthropic:claude-opus-current");
        assertThat(service.register(channel, mapping, "anthropic", ModelIdentityService.RANK_MAPPING))
                .isEqualTo("anthropic:claude-opus-current");
    }
}
