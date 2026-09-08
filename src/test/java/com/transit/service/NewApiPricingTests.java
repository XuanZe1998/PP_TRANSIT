package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class NewApiPricingTests {
    private final ObjectMapper json = new ObjectMapper();
    private final NewApiPricing pricing = new NewApiPricing();
    private NewApiPricing.Price quote(String row) throws Exception {
        return pricing.calculate("a", json.readTree(row), "paid", new BigDecimal("0.2"), new BigDecimal("1.5"), BigDecimal.ONE);
    }
    @Test void convertsTokenAndCacheRatiosWithoutConfusingMarkupAndGroupRatio() throws Exception {
        var p = quote("""
                {"quota_type":0,"model_ratio":2.5,"completion_ratio":5,"cache_ratio":0.1,"create_cache_ratio":1.25,"enable_groups":["paid"]}
                """);
        assertThat(p.supported()).isTrue();
        assertThat(p.input()).isEqualByComparingTo("1");
        assertThat(p.output()).isEqualByComparingTo("5");
        assertThat(p.cacheRead()).isEqualByComparingTo("0.1");
        assertThat(p.cacheWrite()).isEqualByComparingTo("1.25");
        assertThat(p.saleInput()).isEqualByComparingTo("1.5");
        assertThat(p.saleOutput()).isEqualByComparingTo("7.5");
    }
    @Test void perRequestUsesModelPriceNotTokenRatio() throws Exception {
        var p = quote("""
                {"quota_type":1,"model_price":0.5,"model_ratio":99,"enable_groups":["paid"]}
                """);
        assertThat(p.unit()).isEqualTo("TASK");
        assertThat(p.perRequest()).isEqualByComparingTo("0.1");
        assertThat(p.salePerRequest()).isEqualByComparingTo("0.15");
    }
    @Test void imageModelsReceiveImagesProtocolMetadata() throws Exception {
        var p = pricing.calculate("gpt-image-2", json.readTree("""
                {"quota_type":1,"model_price":0.5,"enable_groups":["paid"]}
                """), "paid", new BigDecimal("0.2"), new BigDecimal("1.5"), BigDecimal.ONE);
        var mapping = NewApiSyncService.draft("gpt-image-2");
        pricing.apply(mapping, p, "https://example.com", "paid");
        assertThat(mapping.getCapability()).isEqualTo("image");
        assertThat(mapping.getOutputModalities()).isEqualTo("image");
        assertThat(mapping.getProtocols()).isEqualTo("images");
        assertThat(mapping.getEndpointPath()).isEqualTo("/v1/images/generations");
    }
    @Test void expressionsInvalidFieldsAndOtherGroupsAreNeverImportedAsZeroCost() throws Exception {
        for (String row : new String[]{
                "{\"quota_type\":0,\"billing_expr\":\"p * 5 + c * 30\",\"enable_groups\":[\"paid\"]}",
                "{\"quota_type\":0,\"model_ratio\":1,\"enable_groups\":[\"paid\"]}",
                "{\"quota_type\":1,\"model_price\":-1,\"enable_groups\":[\"paid\"]}",
                "{\"quota_type\":1,\"model_price\":1,\"enable_groups\":[\"other\"]}"}) {
            var p = quote(row);
            assertThat(p.supported()).isFalse();
            assertThat(p.input()).isNull();
            var mapping = NewApiSyncService.draft("a"); mapping.setInputCostPerMillion(new BigDecimal("9"));
            pricing.apply(mapping, p, "https://example.com", "paid");
            assertThat(mapping.getInputCostPerMillion()).isEqualByComparingTo("9");
            assertThat(mapping.isEnabled()).isFalse();
        }
    }
    @Test void safelyReadsServiceTierPricesWithoutEnablingAmbiguousBilling() throws Exception {
        var p = quote("""
                {"quota_type":0,"billing_mode":"tiered_expr",
                 "billing_expr":"param(\\\"service_tier\\\") != \\\"priority\\\" ? tier(\\\"base\\\", p * 5 + c * 30 + cr * 0.5 + cc * 6.25) : tier(\\\"priority\\\", p * 10 + c * 60 + cr * 1 + cc * 12.5)",
                 "enable_groups":["paid"]}
                """);
        assertThat(p.status()).isEqualTo("TIERED");
        assertThat(p.quoted()).isTrue();
        assertThat(p.supported()).isFalse();
        assertThat(p.input()).isEqualByComparingTo("1");
        assertThat(p.output()).isEqualByComparingTo("6");
        assertThat(p.cacheRead()).isEqualByComparingTo("0.1");
        assertThat(p.cacheWrite()).isEqualByComparingTo("1.25");
        assertThat(p.message()).contains("base 输入 1", "priority 输入 2", "service_tier");

        var mapping = NewApiSyncService.draft("gpt-5.6-sol");
        pricing.apply(mapping, p, "https://example.com", "paid");
        assertThat(mapping.getInputCostPerMillion()).isEqualByComparingTo("1");
        assertThat(mapping.getInputPricePerMillion()).isEqualByComparingTo("1.5");
        assertThat(mapping.isBillingEnabled()).isFalse();
        assertThat(mapping.getPricingStatus()).isEqualTo("PENDING");
    }

    @Test void neverExecutesUnrecognizedTieredExpressions() throws Exception {
        var p = quote("""
                {"quota_type":0,"billing_mode":"tiered_expr",
                 "billing_expr":"param(\\\"service_tier\\\") != \\\"priority\\\" ? tier(\\\"base\\\", evil() + p * 5 + c * 30) : tier(\\\"priority\\\", p * 10 + c * 60)",
                 "enable_groups":["paid"]}
                """);
        assertThat(p.status()).isEqualTo("PENDING");
        assertThat(p.input()).isNull();
        assertThat(p.message()).contains("未执行表达式");
    }
    @Test void acceptsValidEmptySnapshotsButRejectsErrorsAndPartialCatalogs() throws Exception {
        assertThat(NewApiCatalogClient.parseModels(json.readTree("{\"data\":[]}"))).isEmpty();
        for (String value : new String[]{"{}", "{\"data\":[],\"success\":false}", "{\"data\":[],\"has_more\":true}",
                "{\"data\":[],\"total\":10}", "{\"data\":[],\"total_count\":10}", "{\"data\":[],\"error\":\"failed\"}",
                "{\"data\":[{\"id\":\"bad id\"}]}", "{\"data\":[],\"next_cursor\":\"more\"}"}) {
            JsonNode payload = json.readTree(value);
            assertThatThrownBy(() -> NewApiCatalogClient.parseModels(payload)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(NewApiCatalogClient.parsePricing(json.readTree("{\"success\":true,\"data\":[],\"usable_group\":{},\"group_ratio\":{}}"))).isNotNull();
        assertThatThrownBy(() -> NewApiCatalogClient.parsePricing(json.readTree("{\"success\":true,\"data\":[]}"))).isInstanceOf(IllegalArgumentException.class);
    }
}
