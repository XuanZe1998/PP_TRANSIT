package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamErrorClassifierTests {
    @Test
    void onlyExplicitlySafeFailuresAllowAccountSwitching() {
        assertThat(UpstreamErrorClassifier.classify(WebClientResponseException.create(429, "limited", null, null, null)))
                .isEqualTo(UpstreamErrorClassifier.ErrorClass.RATE_LIMIT);
        assertThat(UpstreamErrorClassifier.safeToSwitch(new ConnectException("refused"))).isTrue();
        assertThat(UpstreamErrorClassifier.safeToSwitch(new java.net.SocketTimeoutException("unknown acceptance"))).isFalse();
    }
}
