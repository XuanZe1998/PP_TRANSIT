package com.transit.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiApiBankPricingServiceTests {
    private JdbcTemplate jdbc;
    private AiApiBankPricingService pricing;
    private ModelMapping mapping;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:aiapibank-pricing;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE aiapibank_provider_groups(id BIGINT PRIMARY KEY,peak_rate_enabled BOOLEAN,peak_start VARCHAR(16),peak_end VARCHAR(16),peak_rate_multiplier DECIMAL(20,10),billing_timezone VARCHAR(80))");
        jdbc.execute("CREATE TABLE aiapibank_model_offers(id BIGINT PRIMARY KEY,provider_group_id BIGINT,model_mapping_id BIGINT,enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE aiapibank_image_price_variants(id BIGINT PRIMARY KEY,model_offer_id BIGINT,resolution_tier VARCHAR(24),max_edge_pixels INT,source_unit_price DECIMAL(20,10),sale_unit_price DECIMAL(20,10))");
        jdbc.update("INSERT INTO aiapibank_provider_groups VALUES (1,TRUE,'00:00','00:00',1.5,'Asia/Tokyo')");
        jdbc.update("INSERT INTO aiapibank_model_offers VALUES (2,1,10,TRUE)");
        jdbc.update("INSERT INTO aiapibank_image_price_variants VALUES (3,2,'1K',1024,0.05,0.055)");
        pricing = new AiApiBankPricingService(jdbc);
        mapping = ModelMapping.builder().id(10L).pricingUnit("IMAGE").build();
    }

    @Test
    void selectsImageVariantAndRejectsResolutionAboveGroupLimit() {
        var oneK = pricing.imageQuote(mapping, JsonNodeFactory.instance.objectNode().put("size", "1024x1024"));
        assertThat(oneK.sourcePrice()).isEqualByComparingTo("0.05");
        assertThat(oneK.salePrice()).isEqualByComparingTo("0.055");
        assertThatThrownBy(() -> pricing.imageQuote(mapping,
                JsonNodeFactory.instance.objectNode().put("size", "2048x2048")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("最高仅支持 1K");
    }

    @Test
    void supportsOneTwoAndFourKVariantPricing() {
        jdbc.update("INSERT INTO aiapibank_image_price_variants VALUES (4,2,'2K',2048,0.10,0.11)");
        jdbc.update("INSERT INTO aiapibank_image_price_variants VALUES (5,2,'4K',4096,0.20,0.22)");
        assertThat(pricing.imageQuote(mapping, JsonNodeFactory.instance.objectNode().put("size", "2K")).salePrice())
                .isEqualByComparingTo("0.11");
        assertThat(pricing.imageQuote(mapping, JsonNodeFactory.instance.objectNode().put("size", "4096x4096")).salePrice())
                .isEqualByComparingTo("0.22");
    }

    @Test
    void peakMultiplierAppliesOnlyToTokenCostAndSaleDimensions() {
        ModelPriceTier base = ModelPriceTier.builder().costInputPrice(new BigDecimal("1"))
                .costOutputPrice(new BigDecimal("2")).costCacheReadPrice(new BigDecimal("0.1"))
                .costCacheWritePrice(new BigDecimal("1.25")).saleInputPrice(new BigDecimal("1.1"))
                .saleOutputPrice(new BigDecimal("2.2")).saleCacheReadPrice(new BigDecimal("0.11"))
                .saleCacheWritePrice(new BigDecimal("1.375")).costGroupName("采购")
                .saleGroupName("售价").build();
        ModelPriceTier peak = pricing.applyActivePeak(mapping, base);
        assertThat(peak.getCostInputPrice()).isEqualByComparingTo("1.5");
        assertThat(peak.getSaleOutputPrice()).isEqualByComparingTo("3.3");
        assertThat(peak.getSaleCacheWritePrice()).isEqualByComparingTo("2.0625");
    }
}

