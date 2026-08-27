package com.transit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts a settled response into OpenAI-compatible SSE chat chunks. */
@Component
@RequiredArgsConstructor
public class OpenAiStreamAdapter {

    private static final int CHUNK_CODE_POINTS = 128;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<String>> encode(ChatResponse response) {
        List<ServerSentEvent<String>> events = new ArrayList<>();
        events.add(event(chunk(response, Map.of("role", "assistant"), null, null, null)));
        String content = response.getChoices() == null ? "" : response.getChoices().stream()
                .filter(Objects::nonNull)
                .map(ChatResponse.Choice::getMessage)
                .filter(Objects::nonNull)
                .map(ChatResponse.Message::getContent)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
        for (String part : splitByCodePoint(content, CHUNK_CODE_POINTS)) {
            events.add(event(chunk(response, Map.of("content", part), null, null, null)));
        }
        String finishReason = response.getChoices() == null ? "stop" : response.getChoices().stream()
                .filter(Objects::nonNull)
                .map(ChatResponse.Choice::getFinishReason)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("stop");
        events.add(event(chunk(response, Map.of(), finishReason, response.getUsage(), response.getBilling())));
        events.add(ServerSentEvent.builder("[DONE]").build());
        return Flux.fromIterable(events);
    }

    private String chunk(ChatResponse response, Map<String, Object> delta, String finishReason,
                         ChatResponse.Usage usage, ChatResponse.Billing billing) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", response.getId());
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", response.getCreated());
        chunk.put("model", response.getModel());
        chunk.put("choices", List.of(choice));
        if (usage != null) chunk.put("usage", usage);
        if (billing != null) chunk.put("billing", billing);
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode streaming response", error);
        }
    }

    private ServerSentEvent<String> event(String data) {
        return ServerSentEvent.builder(data).build();
    }

    private List<String> splitByCodePoint(String value, int chunkSize) {
        if (value == null || value.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int remaining = value.codePointCount(start, value.length());
            int take = Math.min(chunkSize, remaining);
            int end = value.offsetByCodePoints(start, take);
            result.add(value.substring(start, end));
            start = end;
        }
        return result;
    }
}
