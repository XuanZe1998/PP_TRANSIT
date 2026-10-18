package com.transit.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityCorsTests {
    private final CorsConfigurationSource source = new SecurityConfig()
            .corsConfigurationSource("https://linknux.com");

    @ParameterizedTest
    @ValueSource(strings = {"/service-orders/41/payment", "/payment-intents/41/start"})
    void checkoutPreflightAllowsRequiredDisclosureHeader(String path) throws Exception {
        MockHttpServletRequest request = preflight(path, "https://linknux.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(request), request, response)).isTrue();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("https://linknux.com");
        String allowed = response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
        assertThat(allowed).isNotNull();
        assertThat(Arrays.stream(allowed.split(",")).map(String::trim).toList())
                .contains("authorization", "content-type", "idempotency-key", "x-checkout-disclosure");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    @Test
    void checkoutPreflightStillRejectsUntrustedOrigins() throws Exception {
        MockHttpServletRequest request = preflight("/service-orders/41/payment", "https://untrusted.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(request), request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    private MockHttpServletRequest preflight(String path, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.setScheme("https");
        request.setServerName("api.linknux.com");
        request.setServerPort(443);
        request.addHeader(HttpHeaders.ORIGIN, origin);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                "authorization,content-type,idempotency-key,x-checkout-disclosure");
        return request;
    }
}
