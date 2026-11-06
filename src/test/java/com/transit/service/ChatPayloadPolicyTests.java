package com.transit.service;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatPayloadPolicyTests {
    @Test void enforcesUtf8BytesRatherThanCharacterCount() {
        ChatRequest request = new ChatRequest();
        request.setModel("test-model");
        ChatRequest.Message message = new ChatRequest.Message();
        message.setRole("user"); message.setContent("中文");
        request.setMessages(List.of(message));
        assertDoesNotThrow(() -> ChatPayloadPolicy.validateRequest(request, 6));
        assertEquals(413, assertThrows(ResponseStatusException.class,
                () -> ChatPayloadPolicy.validateRequest(request, 5)).getStatusCode().value());
        request.setMaxTokens(0);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> ChatPayloadPolicy.validateRequest(request, 6)).getStatusCode().value());
    }

    @Test void estimatesMissingUsageAndRejectsInvalidProviderUsage() {
        ChatResponse response = response("answer");
        assertTrue(ChatPayloadPolicy.normalizeUsage(response, 10));
        assertEquals(12, response.getUsage().getTotalTokens());
        assertTrue(response.getUsage().getEstimated());
        response.getUsage().setPromptTokens(-1);
        assertThrows(ResponseStatusException.class, () -> ChatPayloadPolicy.normalizeUsage(response, 10));
    }

    @Test void preservesReportedUsageAndReasoningFallback() {
        ChatResponse response = response("");
        response.getChoices().get(0).getMessage().setReasoningContent("fallback answer");
        ChatPayloadPolicy.normalizeAssistantAnswer(response);
        assertEquals("fallback answer", response.getChoices().get(0).getMessage().getContent());
        ChatResponse.Usage usage = new ChatResponse.Usage();
        usage.setPromptTokens(3); usage.setCompletionTokens(2); usage.setTotalTokens(8);
        response.setUsage(usage);
        assertFalse(ChatPayloadPolicy.normalizeUsage(response, 100));
        assertEquals(8, usage.getTotalTokens());
        assertNull(usage.getEstimated());
        assertThrows(ResponseStatusException.class, () -> ChatPayloadPolicy.normalizeAssistantAnswer(response("")));
    }

    private ChatResponse response(String text) {
        ChatResponse response = new ChatResponse();
        ChatResponse.Message message = new ChatResponse.Message(); message.setContent(text);
        ChatResponse.Choice choice = new ChatResponse.Choice(); choice.setMessage(message);
        response.setChoices(List.of(choice)); return response;
    }
}
