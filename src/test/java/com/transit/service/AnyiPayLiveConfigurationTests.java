package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicitly enabled, read-only smoke test for deployed AnyiPay credentials.
 * It calls merchant/info and exercises request signing plus response signature
 * verification without creating, closing, refunding, or transferring money.
 */
@SpringBootTest(properties = "nvida.verify-on-startup=false")
@EnabledIfSystemProperty(named = "anyipay.live-test", matches = "true")
class AnyiPayLiveConfigurationTests {

    @Autowired
    private AnyiPayClient client;

    @Test
    void configuredKeysCanReadSignedMerchantInformation() {
        JsonNode response = client.merchantInfo();

        assertThat(response.path("code").asInt(-1)).isZero();
    }
}
