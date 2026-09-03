package com.transit.service;

import com.transit.dto.PublicModel;
import com.transit.dto.AiApiBankPriceDimensions;
import com.transit.dto.AiApiBankPriceTierView;
import com.transit.dto.AiApiBankProviderGroupView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicModelMarketplaceServiceTests {
    private final PublicModelMarketplaceService service = new PublicModelMarketplaceService();

    @Test
    void appliesOrWithinFacetAndAndAcrossFacets() {
        List<PublicModel> models = List.of(
                model("platform/gpt", "openai:gpt-5.6", "platform-route", "openai", "language", "reasoning", "TOKEN", "10"),
                model("bank/gpt", "openai:gpt-5.6", "aiapibank", "openai", "language", "reasoning", "TOKEN", "8"),
                model("bank/claude", "anthropic:claude-5", "aiapibank", "anthropic", "language", "text", "TOKEN", "6"));
        var filters = PublicModelMarketplaceService.criteria(null, "platform-route,aiapibank", "openai",
                null, "reasoning", null, null, null, null, null, null);

        assertThat(service.filter(models, filters)).extracting(PublicModel::getPublicName)
                .containsExactly("platform/gpt", "bank/gpt");
        assertThat(service.facets(models, filters).get("publishers"))
                .extracting(option -> option.value() + ":" + option.count())
                .containsExactly("openai:2");
    }

    @Test
    void comparesOnlyTheSameCanonicalModelWithoutMergingCards() {
        PublicModel platform = model("gpt-5.6", "openai:gpt-5.6", "platform-route", "openai", "language", "reasoning", "TOKEN", "10");
        PublicModel bank = model("aiapibank/gpt/gpt-5.6", "openai:gpt-5.6", "aiapibank", "openai", "language", "reasoning", "TOKEN", "8");
        PublicModel version = model("aiapibank/gpt/gpt-5.6-2026", "openai:gpt-5.6-2026", "aiapibank", "openai", "language", "reasoning", "TOKEN", "7");
        PublicModel unpriced = model("pending/gpt", "openai:gpt-5.6", "pending", "openai", "language", "reasoning", "TOKEN", "0");
        unpriced.setBillingConfigured(false);

        var result = service.comparison(List.of(platform, bank, version, unpriced), platform.getPublicName());

        assertThat(result.offers()).extracting(PublicModel::getPublicName)
                .containsExactlyInAnyOrder(platform.getPublicName(), bank.getPublicName());
        assertThat(result.comparableCount()).isEqualTo(2);
    }

    @Test
    void sortsByPositiveSalePriceAndLeavesFreePreviewOutOfPriceRanking() {
        PublicModel expensive = model("expensive", "x:expensive", "platform-route", "x", "language", "text", "TOKEN", "12");
        PublicModel cheap = model("cheap", "x:cheap", "aiapibank", "x", "language", "text", "TOKEN", "3");
        PublicModel freePreview = model("preview", "x:preview", "platform-route", "x", "language", "text", "TOKEN", "0");
        freePreview.setBillingMode("FREE_PREVIEW");

        assertThat(service.sort(List.of(expensive, freePreview, cheap), "price_asc"))
                .extracting(PublicModel::getPublicName).containsExactly("cheap", "expensive", "preview");
        assertThat(service.sort(List.of(cheap, freePreview, expensive), "price_desc"))
                .extracting(PublicModel::getPublicName).containsExactly("expensive", "cheap", "preview");
    }

    @Test
    void publicModelSerializationDoesNotExposeProcurementMultipliers() throws Exception {
        PublicModel model = model("safe", "x:safe", "platform-route", "x", "language", "text", "TOKEN", "3");
        model.setMinInputCostMultiplier(new BigDecimal("0.25"));
        model.setMaxOutputCostMultiplier(new BigDecimal("0.50"));
        model.setProviderGroup(AiApiBankProviderGroupView.builder().name("标准")
                .resolvedRateMultiplier(new BigDecimal("0.40")).build());
        model.setPriceTiers(List.of(AiApiBankPriceTierView.builder().label("标准")
                .official(null).sourcePrice(null)
                .sale(AiApiBankPriceDimensions.builder().input(new BigDecimal("3")).build()).build()));

        String json = new ObjectMapper().writeValueAsString(model);

        assertThat(json).doesNotContain("CostMultiplier").doesNotContain("costMultiplier");
        assertThat(json).doesNotContain("sourcePrice").doesNotContain("official")
                .doesNotContain("resolvedRateMultiplier");
        assertThat(json).contains("minInputPricePerMillion");
    }

    private PublicModel model(String name, String key, String route, String publisher, String category,
                              String capability, String unit, String inputPrice) {
        PublicModel model = new PublicModel();
        model.setPublicName(name); model.setDisplayName(name); model.setComparisonKey(key);
        model.setRouteCode(route); model.setRouteName(route); model.setPlanCode("standard"); model.setPlanName("标准");
        model.setPublisherCode(publisher); model.setPublisherName(publisher); model.setCategory(category);
        model.setCapability(capability); model.setInputModalities("text"); model.setOutputModalities("text");
        model.setProtocols("chat-completions"); model.setPricingUnit(unit); model.setPricingStatus("VERIFIED");
        model.setBillingMode("PAID"); model.setBillingConfigured(true);
        model.setAvailable(true);
        model.setMinInputPricePerMillion(new BigDecimal(inputPrice));
        return model;
    }
}
