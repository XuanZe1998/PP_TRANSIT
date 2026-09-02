package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
@Transactional
class AiApiBankSyncIntegrationTests {
    @MockitoSpyBean private AiApiBankCatalogService catalog;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void missingCredentialsCreateDisabledGroupChannelsWithoutPublishingModels() {
        ObjectNode root = json.createObjectNode().put("code", 0);
        ObjectNode group = root.putObject("data").putArray("groups").addObject()
                .put("id", 32).put("name", "GPT低价分组").put("description", "低价")
                .put("platform", "openai").put("subscription_type", "standard")
                .put("rate_multiplier", 0.07).put("peak_rate_enabled", false);
        ObjectNode model = group.putArray("models").addObject().put("name", "gpt-5.6").put("platform", "openai");
        model.putObject("pricing").put("billing_mode", "token").put("input_price", 0.000005).put("output_price", 0.00003);
        model.putObject("official_pricing").put("input_price", 0.000005).put("output_price", 0.00003);
        doReturn(root).when(catalog).fetchCatalog();

        AiApiBankCatalogService.SyncResult result = catalog.sync(false);

        assertThat(result.groupsSeen()).isEqualTo(3);
        assertThat(result.credentialsMissing()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aiapibank_provider_groups", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aiapibank_provider_groups WHERE credential_status='CREDENTIAL_MISSING'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE source_code='aiapibank' AND enabled=FALSE", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE public_model_name LIKE 'aiapibank/%'", Integer.class)).isZero();
    }
}
