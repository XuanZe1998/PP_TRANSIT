package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class IdempotencyServiceIntegrationTests {
    @Autowired IdempotencyService service;

    @Test
    void replaysTheSameRequestAndRejectsTheSameKeyWithDifferentParameters() {
        String actor = UUID.randomUUID().toString();
        String key = "idem-" + UUID.randomUUID();
        Map<String, Object> request = Map.of("userId", 7, "amount", 100);
        IdempotencyService.Claim first = service.claim("TEST", actor, "wallet.allocate", key, request, true);
        service.complete(first, 200, Map.of("transactionId", "tx-1"), "WALLET_TRANSFER", "tx-1");

        IdempotencyService.Claim replay = service.claim("TEST", actor, "wallet.allocate", key, request, true);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.response().path("transactionId").asText()).isEqualTo("tx-1");

        assertThatThrownBy(() -> service.claim("TEST", actor, "wallet.allocate", key,
                Map.of("userId", 7, "amount", 101), true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
