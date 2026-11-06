package com.transit.service;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Pure request limits and provider response normalization, independent of routing and settlement. */
final class ChatPayloadPolicy {
    private ChatPayloadPolicy() {}
    static void validateRequest(ChatRequest request, int maxRequestContentBytes) {
        if (request == null || request.getModel() == null || request.getModel().isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "model is required");
        }
        if (request.getModel().length() > 160 || !request.getModel().matches("[A-Za-z0-9._:/-]+")) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "model is invalid");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty() || request.getMessages().size() > 200) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "messages must contain 1 to 200 items");
        }
        long contentBytes = 0;
        for (ChatRequest.Message message : request.getMessages()) {
            if (message == null || message.getRole() == null
                    || !message.getRole().matches("(?i)system|developer|user|assistant|tool")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every message must have a supported role");
            }
            contentBytes += utf8Length(Objects.toString(message.getContent(), ""));
            contentBytes += utf8Length(Objects.toString(message.getName(), ""));
            if (contentBytes > Math.max(1, maxRequestContentBytes)) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Message content exceeds the configured request-size limit");
            }
        }
        if (request.getMaxTokens() != null && (request.getMaxTokens() < 1 || request.getMaxTokens() > 131072)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "max_tokens is out of range");
        }
    }

    /**
     * A provider-independent conservative estimate. Dividing UTF-8 bytes by
     * three intentionally overestimates ordinary Latin text while tracking CJK
     * text reasonably closely; the small per-message allowance covers roles and
     * chat framing. The response explicitly marks estimated usage when this is
     * used for final billing.
     */
    static int estimateRequestTokens(ChatRequest request) {
        long bytes = 0;
        for (ChatRequest.Message message : request.getMessages()) {
            bytes += utf8Length(Objects.toString(message.getRole(), ""));
            bytes += utf8Length(Objects.toString(message.getContent(), ""));
            bytes += utf8Length(Objects.toString(message.getName(), ""));
        }
        long estimate = ((bytes + 2L) / 3L) + (request.getMessages().size() * 8L) + 8L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, estimate));
    }

    static int estimateResponseTokens(ChatResponse response) {
        long bytes = 0;
        if (response.getChoices() != null) {
            for (ChatResponse.Choice choice : response.getChoices()) {
                if (choice != null && choice.getMessage() != null) {
                    bytes += utf8Length(Objects.toString(choice.getMessage().getContent(), ""));
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (bytes + 2L) / 3L));
    }

    static void normalizeAssistantAnswer(ChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned no assistant answer");
        }
        for (ChatResponse.Choice choice : response.getChoices()) {
            if (choice == null || choice.getMessage() == null) continue;
            ChatResponse.Message message = choice.getMessage();
            String content = Objects.toString(message.getContent(), "").trim();
            String reasoningContent = Objects.toString(message.getReasoningContent(), "").trim();
            String reasoning = Objects.toString(message.getReasoning(), "").trim();
            if (content.isBlank()) {
                String fallback = !reasoningContent.isBlank() ? reasoningContent : reasoning;
                if (!fallback.isBlank()) {
                    message.setContent(fallback);
                    content = fallback;
                }
            }
            if (!content.isBlank()) return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned an empty assistant answer");
    }

    static boolean normalizeUsage(ChatResponse response, int estimatedPromptTokens) {
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned an empty response");
        }
        ChatResponse.Usage usage = response.getUsage();
        boolean estimated = usage == null
                || usage.getPromptTokens() == null
                || usage.getCompletionTokens() == null;
        if (usage == null) {
            usage = new ChatResponse.Usage();
            response.setUsage(usage);
        }
        int prompt = usage.getPromptTokens() == null ? estimatedPromptTokens : usage.getPromptTokens();
        int completion = usage.getCompletionTokens() == null ? estimateResponseTokens(response) : usage.getCompletionTokens();
        if (prompt < 0 || completion < 0 || prompt > 10_000_000 || completion > 10_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned invalid token usage");
        }
        long calculatedTotal = (long) prompt + completion;
        int reportedTotal = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
        if (reportedTotal < 0 || reportedTotal > 20_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned invalid total token usage");
        }
        usage.setPromptTokens(prompt);
        usage.setCompletionTokens(completion);
        usage.setTotalTokens((int) Math.max(calculatedTotal, reportedTotal));
        usage.setEstimated(estimated ? Boolean.TRUE : null);
        return estimated;
    }

    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

}
