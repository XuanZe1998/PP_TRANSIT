package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiStreamAdapterTests {

    private final OpenAiStreamAdapter adapter = new OpenAiStreamAdapter(new ObjectMapper());

    @Test
    void emitsRoleContentUsageAndDoneEvents() {
        ChatResponse.Message message = new ChatResponse.Message();
        message.setRole("assistant");
        message.setContent("hello 👋");
        ChatResponse.Choice choice = new ChatResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason("stop");
        ChatResponse.Usage usage = new ChatResponse.Usage();
        usage.setPromptTokens(2);
        usage.setCompletionTokens(2);
        usage.setTotalTokens(4);
        ChatResponse response = new ChatResponse();
        response.setId("chatcmpl-test");
        response.setObject("chat.completion");
        response.setCreated(1L);
        response.setModel("public-model");
        response.setChoices(List.of(choice));
        response.setUsage(usage);

        StepVerifier.create(adapter.encode(response).map(event -> event.data()))
                .recordWith(java.util.ArrayList::new)
                .expectNextCount(4)
                .consumeRecordedWith(events -> {
                    List<String> values = List.copyOf(events);
                    assertThat(values.get(0)).contains("chat.completion.chunk", "assistant");
                    assertThat(values.get(1)).contains("hello 👋");
                    assertThat(values.get(2)).contains("finish_reason", "prompt_tokens");
                    assertThat(values.get(3)).isEqualTo("[DONE]");
                })
                .verifyComplete();
    }
}
