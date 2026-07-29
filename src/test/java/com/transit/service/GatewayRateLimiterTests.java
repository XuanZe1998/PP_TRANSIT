package com.transit.service;

import com.transit.model.Token;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayRateLimiterTests {

    @Test
    void rejectsRequestsAboveTheConfiguredPerMinuteLimit() {
        GatewayRateLimiter limiter = new GatewayRateLimiter(
                2,
                Clock.fixed(Instant.parse("2026-07-09T10:00:00Z"), ZoneOffset.UTC)
        );
        Token token = Token.builder().id(42L).build();

        limiter.checkToken(token);
        limiter.checkToken(token);

        assertThatThrownBy(() -> limiter.checkToken(token))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");
    }
}
