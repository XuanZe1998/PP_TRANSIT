package com.transit.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sub2ApiCatalogClientTests {
    @Test
    void identifiesCurrentSub2ApiAndReadsTheKeyScopedCatalog() throws Exception {
        AtomicReference<String> modelsAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1/models", exchange -> {
                modelsAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                json(exchange, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"claude-sonnet-4-6\"}]}");
            });
            server.createContext("/v1/sub2api/billing", exchange -> json(exchange, 200,
                    "{\"object\":\"sub2api.key_billing\",\"schema_version\":1,\"billing_scope\":\"token\",\"effective_rate_multiplier\":0.7}"));
            server.start();
            var result = client().fetch(base(server), "sk-test");
            assertThat(modelsAuth.get()).isEqualTo("Bearer sk-test");
            assertThat(result.models()).containsExactly("claude-sonnet-4-6");
            assertThat(result.detectionMethod()).isEqualTo("KEY_BILLING");
            assertThat(result.effectiveRateMultiplier()).isEqualByComparingTo("0.7");
        } finally { server.stop(0); }
    }

    @Test
    void supportsSimpleModeAndOlderForksThroughCredentialFreePublicSettings() throws Exception {
        AtomicReference<String> settingsAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1/models", exchange -> json(exchange, 200, "{\"data\":[{\"id\":\"gpt-5\"}]}"));
            server.createContext("/v1/sub2api/billing", exchange -> json(exchange, 404, "{}"));
            server.createContext("/api/v1/settings/public", exchange -> {
                settingsAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                json(exchange, 200, "{\"code\":0,\"data\":{\"version\":\"0.1.177\",\"site_name\":\"Forked Hub\"}}");
            });
            server.start();
            var result = client().fetch(base(server), "sk-private");
            assertThat(settingsAuth.get()).isNull();
            assertThat(result.detectionMethod()).isEqualTo("PUBLIC_SETTINGS");
            assertThat(result.siteName()).isEqualTo("Forked Hub");
        } finally { server.stop(0); }
    }

    @Test
    void rejectsGenericOpenAiSitesThatDoNotHaveASub2ApiFingerprint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1/models", exchange -> json(exchange, 200, "{\"data\":[{\"id\":\"gpt-5\"}]}"));
            server.createContext("/v1/sub2api/billing", exchange -> json(exchange, 404, "{}"));
            server.createContext("/api/v1/settings/public", exchange -> json(exchange, 200, "{\"status\":\"ok\"}"));
            server.start();
            assertThatThrownBy(() -> client().fetch(base(server), "sk-test"))
                    .hasMessageContaining("recognized sub2api");
        } finally { server.stop(0); }
    }

    @Test
    void assignsProtocolMetadataNeededBySub2ApiNativeSurfaces() {
        assertThat(Sub2ApiOnboardingService.draft("claude-sonnet-4-6").getProtocols())
                .contains("messages", "responses", "chat-completions");
        assertThat(Sub2ApiOnboardingService.draft("gemini-2.5-pro").getProtocols())
                .contains("gemini-generate-content", "responses");
        assertThat(Sub2ApiOnboardingService.draft("text-embedding-3-large").getProtocols())
                .isEqualTo("embeddings");
    }

    private Sub2ApiCatalogClient client() {
        return new Sub2ApiCatalogClient(WebClient.create(), new ChannelUrlPolicy(true));
    }

    private String base(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static void json(com.sun.net.httpserver.HttpExchange exchange, int status, String value) throws java.io.IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
