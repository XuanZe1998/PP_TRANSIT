package com.transit.service;

import com.sun.net.httpserver.HttpServer;
import com.transit.model.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenAiImageHealthProbeTests {
    private HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void callsImagesEndpointAndAcceptsUrlWithoutRetainingIt() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"data\":[{\"url\":\"https://cdn.example.test/private-result.png\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        OpenAiImageHealthProbe probe = new OpenAiImageHealthProbe(WebClient.builder().build(), mock(ChannelUrlPolicy.class));

        Map<String, Object> result = probe.probe(channel(base), "gpt-image-2", 5);

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(result.get("sampleText")).isEqualTo("Image generation response accepted");
        assertThat(result.toString()).doesNotContain("private-result.png", "provider-secret");
        assertThat(path.get()).isEqualTo("/v1/images/generations");
        assertThat(authorization.get()).isEqualTo("Bearer provider-secret");
        assertThat(requestBody.get()).contains("\"model\":\"gpt-image-2\"");
    }

    @Test void classifiesProviderHttpFailureWithoutReturningResponseBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            byte[] body = "sensitive upstream detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        OpenAiImageHealthProbe probe = new OpenAiImageHealthProbe(WebClient.builder().build(), mock(ChannelUrlPolicy.class));

        Map<String, Object> result = probe.probe(channel(base), "gpt-image-2", 5);

        assertThat(result.get("status")).isEqualTo("UPSTREAM_ERROR");
        assertThat(result.get("exitCode")).isEqualTo(400);
        assertThat(result.get("error").toString()).contains("image endpoint returned HTTP 400")
                .doesNotContain("sensitive upstream detail");
    }

    @Test void preservesExistingV1Prefix() {
        assertThat(OpenAiImageHealthProbe.endpoint("https://example.com/v1"))
                .isEqualTo("https://example.com/v1/images/generations");
        assertThat(OpenAiImageHealthProbe.endpoint("https://example.com/proxy"))
                .isEqualTo("https://example.com/proxy/v1/images/generations");
    }

    private Channel channel(String base) {
        return Channel.builder().baseUrl(base).apiKey("provider-secret").protocolType("openai-image").build();
    }
}
