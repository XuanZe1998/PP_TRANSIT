package com.transit.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class NewApiCatalogClientTests {
    @Test void haoeeCatalogUsesCanonicalSlashPathForBothBaseForms() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1/models/", e -> {
                byte[] body="{\"data\":[{\"id\":\"chat-model\"}]}".getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type","application/json");
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });
            server.start();
            var client=new NewApiCatalogClient(WebClient.create(),new ChannelUrlPolicy(true));
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThat(client.haoeeModels(base,"secret")).containsExactly("chat-model");
            assertThat(client.haoeeModels(base+"/v1/","secret")).containsExactly("chat-model");
        } finally {server.stop(0);}
    }
    @Test void redirectsCannotForwardCredentialsAndErrorBodiesAreNotExposed() throws Exception {
        AtomicReference<String> leaked = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1/models", e -> {
                e.getResponseHeaders().add("Location", "/collector"); e.sendResponseHeaders(302, -1); e.close();
            });
            server.createContext("/collector", e -> { leaked.set(e.getRequestHeaders().getFirst("Authorization")); e.sendResponseHeaders(200, -1); e.close(); });
            server.createContext("/api/pricing", e -> {
                byte[] b = "pricing-secret echo".getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(403, b.length); e.getResponseBody().write(b); e.close();
            });
            server.start();
            var result = new NewApiCatalogClient(WebClient.create(), new ChannelUrlPolicy(true))
                    .fetch("http://127.0.0.1:" + server.getAddress().getPort(), "inference-secret", "pricing-secret", 1L);
            assertThat(leaked.get()).isNull();
            assertThat(result.modelsComplete()).isFalse(); assertThat(result.pricingComplete()).isFalse();
            assertThat(result.toString()).doesNotContain("inference-secret", "pricing-secret echo");
        } finally { server.stop(0); }
    }
    @Test void rejectsPrivateDestinationsBeforeSendingEitherCredential() {
        var client = new NewApiCatalogClient(WebClient.create(), new ChannelUrlPolicy(false));
        assertThatThrownBy(() -> client.fetch("http://127.0.0.1:1", "secret", "secret", 1L)).hasMessageContaining("not allowed");
    }
}
