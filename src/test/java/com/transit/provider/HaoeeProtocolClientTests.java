package com.transit.provider;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.transit.dto.ChatRequest;
import com.transit.model.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HaoeeProtocolClientTests {
    @Test
    void sendsTheMappedUpstreamModelForThePublicClaude47Alias() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"id\":\"chatcmpl-test\",\"model\":\"claude-opus-4-8\",\"choices\":[]}").build());
        }).build();
        HaoeeGateway gateway = new HaoeeGateway(client);
        Channel channel = Channel.builder().baseUrl("https://maas.haoee.com/v1")
                .apiKey("haoee-secret").build();
        ChatRequest request = new ChatRequest();
        request.setModel("claude-opus-4-7");
        request.setStream(true);

        StepVerifier.create(gateway.chatCompletions(channel, request,
                        "claude-opus-4-7", "claude-opus-4-8"))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(captured.get().url().toString())
                .isEqualTo("https://maas.haoee.com/v1/chat/completions");
        assertThat(captured.get().headers().getFirst("Authorization")).isEqualTo("Bearer haoee-secret");
        assertThat(captured.get().headers().getFirst("ModelName")).isEqualTo("claude-opus-4-8");
        assertThat(request.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(request.isStream()).isFalse();
    }

    @Test
    void sendsHaoeeAuthorizationAndModelNameHeadersToTheConfiguredProtocolPath() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"data\":[1,2,3]}").build());
        }).build();
        HaoeeProtocolClient gateway = new HaoeeProtocolClient(client);
        Channel channel = Channel.builder().baseUrl("https://maas.haoee.com/")
                .apiKey("haoee-secret").build();

        StepVerifier.create(gateway.invoke(channel, "BAAI/bge-m3", "/compatible-mode/v1/embeddings",
                        HttpMethod.POST, JsonNodeFactory.instance.objectNode().put("input", "hello")))
                .expectNextMatches(response -> response.path("data").size() == 3)
                .verifyComplete();

        assertThat(captured.get().url().toString())
                .isEqualTo("https://maas.haoee.com/compatible-mode/v1/embeddings");
        assertThat(captured.get().headers().getFirst("Authorization")).isEqualTo("Bearer haoee-secret");
        assertThat(captured.get().headers().getFirst("ModelName")).isEqualTo("BAAI/bge-m3");
    }

    @Test
    void doesNotDuplicateAnExistingBearerPrefix() {
        assertThat(HaoeeGateway.authorization("Bearer haoee-secret"))
                .isEqualTo("Bearer haoee-secret");
    }

    @Test
    void preservesResponsesSseEventNamesDataAndHeaders() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body("""
                            event: response.created
                            data: {"type":"response.created","response":{"id":"resp_1"}}

                            event: response.completed
                            data: {"type":"response.completed","response":{"usage":{"input_tokens":7,"output_tokens":3}}}

                            """).build());
        }).build();
        HaoeeProtocolClient gateway = new HaoeeProtocolClient(client);
        Channel channel = Channel.builder().baseUrl("https://maas.haoee.com/v1")
                .apiKey("haoee-secret").build();

        StepVerifier.create(gateway.streamEvents(channel, "gpt-5.4-pro", "/v1/responses",
                        JsonNodeFactory.instance.objectNode().put("stream", true)))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("response.created");
                    assertThat(event.data()).contains("resp_1");
                })
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("response.completed");
                    assertThat(event.data()).contains("\"input_tokens\":7");
                })
                .verifyComplete();

        assertThat(captured.get().url().toString()).isEqualTo("https://maas.haoee.com/v1/responses");
        assertThat(captured.get().headers().getFirst("Authorization")).isEqualTo("Bearer haoee-secret");
        assertThat(captured.get().headers().getFirst("ModelName")).isEqualTo("gpt-5.4-pro");
        assertThat(captured.get().headers().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
    }
}
