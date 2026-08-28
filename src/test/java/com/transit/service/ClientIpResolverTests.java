package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTests {
    private final ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");

    @Test
    void ignoresForwardedHeadersFromUntrustedPeers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void acceptsTheFirstLiteralAddressFromATrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 127.0.0.1");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void neverResolvesAHostnameSuppliedInAForwardingHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "localhost");
        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }
}
