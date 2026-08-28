package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelUrlPolicyTests {

    @Test
    void productionPolicyRejectsPlainHttpAndLoopback() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy(false);
        assertThrows(ResponseStatusException.class, () -> policy.validate("http://example.com"));
        assertThrows(ResponseStatusException.class, () -> policy.validate("https://127.0.0.1"));
    }

    @Test
    void explicitPrivateDevelopmentModeAllowsLocalHttpMock() {
        ChannelUrlPolicy policy = new ChannelUrlPolicy(true);
        assertDoesNotThrow(() -> policy.validate("http://127.0.0.1:8099"));
    }
}
