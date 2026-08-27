package com.transit.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.model.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleGatewayTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void aggregatesNvidiaSseIntoNonStreamingResponse() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] body = ("data: {\"id\":\"chat-1\",\"created\":1,\"model\":\"meta/test\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"thinking \"}}]}\n\n"
                    + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"O\"}}]}\n\n"
                    + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"K\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleGateway gateway = new OpenAiCompatibleGateway(WebClient.create(), objectMapper);
        Channel channel = Channel.builder()
                .type("nvidia")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("secret")
                .build();
        ChatRequest request = new ChatRequest();
        ChatRequest.Message message = new ChatRequest.Message();
        message.setRole("user");
        message.setContent("hello");
        request.setMessages(List.of(message));
        request.setMaxTokens(16);

        ChatResponse response = gateway.chatCompletions(channel, request, "public", "meta/test").block();

        assertThat(captured.get().get("stream").asBoolean()).isTrue();
        assertThat(captured.get().get("temperature").asDouble()).isEqualTo(1.0);
        assertThat(captured.get().has("n")).isFalse();
        assertThat(captured.get().get("messages").get(0).has("name")).isFalse();
        assertThat(response).isNotNull();
        assertThat(response.getChoices().get(0).getMessage().getContent()).isEqualTo("OK");
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(5);
    }
}
