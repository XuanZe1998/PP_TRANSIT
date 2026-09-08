package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.DujiaoNextProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DujiaoNextClientTests {
    @Test
    void signsTheExactMethodPathTimestampAndBodyDigest() {
        String signature = DujiaoNextClient.signature("test-secret", "POST",
                "/api/v1/upstream/orders", "1700000000", "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(signature).isEqualTo("95f716beeb3b255affb36b9f9e27390d8c20ec705bdb35f1213174167a3d8072");
    }

    @Test
    void verifiesSignedCallbacksAndRejectsExpiredOnes() {
        DujiaoNextProperties properties = new DujiaoNextProperties();
        properties.setEnabled(true);
        properties.setApiKey("key-123");
        properties.setApiSecret("secret-456");
        properties.setTimestampToleranceSeconds(60);
        DujiaoNextClient client = new DujiaoNextClient(WebClient.create(), new ObjectMapper(), properties);
        byte[] body = "{\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = DujiaoNextClient.signature("secret-456", "POST",
                "/api/v1/upstream/callback", timestamp, body);

        client.verifyCallback("key-123", timestamp, signature, body);

        assertThatThrownBy(() -> client.verifyCallback("key-123", "1",
                DujiaoNextClient.signature("secret-456", "POST", "/api/v1/upstream/callback", "1", body), body))
                .isInstanceOf(DujiaoNextApiException.class)
                .hasMessageContaining("Expired");
    }
}
