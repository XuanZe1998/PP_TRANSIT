package com.transit.service;

import com.transit.model.ModelMapping;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PublicPricingPolicyTests {
    @Test
    void addsTenPercentAndAlwaysRoundsUpToSixDecimals() {
        assertThat(PublicPricingPolicy.saleFromCost(new BigDecimal("1.028571")))
                .isEqualByComparingTo("1.131429");
        assertThat(PublicPricingPolicy.saleFromCost(new BigDecimal("0.000001")))
                .isEqualByComparingTo("0.000002");
    }

    @Test
    void textRequiresInputAndOutputWhileEmbeddingOnlyRequiresInput() {
        ModelMapping text = priced("text", "0.1", "0");
        ModelMapping embedding = priced("embedding", "0.1", "0");

        assertThat(PublicPricingPolicy.hasRequiredSale(text)).isFalse();
        assertThat(PublicPricingPolicy.hasRequiredSale(embedding)).isTrue();
        embedding.setBillingEnabled(false);
        assertThat(PublicPricingPolicy.hasRequiredSale(embedding)).isFalse();
    }

    @Test
    void nonTokenPaidModelsRequireUnitPriceButExplicitFreePreviewMayBeZero() {
        ModelMapping video = ModelMapping.builder().enabled(true).billingEnabled(true).billingMode("PAID")
                .pricingUnit("SECOND").saleUnitPrice(BigDecimal.ZERO).build();
        assertThat(PublicPricingPolicy.hasRequiredSale(video)).isFalse();
        video.setSaleUnitPrice(new BigDecimal("0.02"));
        assertThat(PublicPricingPolicy.hasRequiredSale(video)).isTrue();
        video.setSaleUnitPrice(BigDecimal.ZERO);
        video.setBillingMode("FREE_PREVIEW");
        assertThat(PublicPricingPolicy.hasRequiredSale(video)).isTrue();
    }

    private ModelMapping priced(String capability, String input, String output) {
        return ModelMapping.builder().enabled(true).billingEnabled(true).capability(capability)
                .inputPricePerMillion(new BigDecimal(input)).outputPricePerMillion(new BigDecimal(output)).build();
    }
}
