package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.ModelPriceTierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiApiBankCatalogServiceTests {
    private final ObjectMapper json = new ObjectMapper();
    private AiApiBankCatalogService service;

    @BeforeEach
    void setUp() {
        service = new AiApiBankCatalogService(WebClient.create(), json, mock(JdbcTemplate.class),
                mock(TransactionTemplate.class), mock(ChannelMapper.class), mock(ModelMappingMapper.class),
                mock(ModelPriceTierMapper.class), mock(ChannelSecretService.class));
        ReflectionTestUtils.setField(service, "saleMarkup", new BigDecimal("1.10"));
    }

    @Test
    void mapsAllThirteenKnownGroupsToStableSlugs() {
        Map<Long,String> expected = Map.ofEntries(
                Map.entry(37L,"grok-low"), Map.entry(32L,"gpt-low"), Map.entry(18L,"gpt-plus-stable"),
                Map.entry(5L,"gpt-plus-pro"), Map.entry(4L,"gpt-pro"), Map.entry(2L,"claude-special"),
                Map.entry(65L,"claude-cursor"), Map.entry(6L,"claude-max20"), Map.entry(64L,"deepseek"),
                Map.entry(63L,"glm"), Map.entry(62L,"kimi"), Map.entry(-1001L,"gpt-image2-1k"),
                Map.entry(-1002L,"image2-all-res"));
        expected.forEach((id, slug) -> assertThat(AiApiBankCatalogService.groupSlug(id)).isEqualTo(slug));
        assertThat(AiApiBankCatalogService.groupSlug(999)).isEqualTo("group-999");
    }

    @Test
    void parsesCatalogIntervalsAndBuildsPathModelId() {
        ObjectNode root = json.createObjectNode().put("code", 0);
        ArrayNode groups = root.putObject("data").putArray("groups");
        ObjectNode group = groups.addObject().put("id", 32).put("name", "GPT低价分组")
                .put("description", "低价").put("platform", "openai").put("subscription_type", "standard")
                .put("rate_multiplier", 0.07).put("long_context_pricing_enabled", true);
        ObjectNode model = group.putArray("models").addObject().put("name", "gpt-5.6").put("platform", "openai");
        model.putObject("pricing").put("billing_mode", "token").put("input_price", 0.000005)
                .putArray("intervals").addObject().put("min_tokens", 0).put("max_tokens", 272000)
                .put("tier_label", "≤272K").put("input_price", 0.000005);
        model.putObject("official_pricing").put("input_price", 0.000005);

        List<AiApiBankCatalogService.GroupSnapshot> parsed = service.parseCatalog(root);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).slug()).isEqualTo("gpt-low");
        assertThat(parsed.get(0).models().get(0).path("pricing").path("intervals").get(0)
                .path("max_tokens").asInt()).isEqualTo(272000);
        assertThat(AiApiBankCatalogService.publicModelId(parsed.get(0).slug(), "gpt-5.6"))
                .isEqualTo("aiapibank/gpt-low/gpt-5.6");
    }

    @Test
    void appliesTenPercentMarkupExactlyAndKeepsZeroAtZero() {
        assertThat(service.salePrice(new BigDecimal("0.05"))).isEqualByComparingTo("0.055");
        assertThat(service.salePrice(new BigDecimal("0.10"))).isEqualByComparingTo("0.11");
        assertThat(service.salePrice(BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsEmptyCatalogInsteadOfReplacingLastKnownGoodState() {
        ObjectNode empty = json.createObjectNode().put("code", 0);
        empty.putObject("data").putArray("groups");
        assertThatThrownBy(() -> service.parseCatalog(empty)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝覆盖现有目录");
    }
}

