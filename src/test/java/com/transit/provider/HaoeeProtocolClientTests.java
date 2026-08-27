package com.transit.provider;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.transit.model.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HaoeeProtocolClientTests {
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
}
