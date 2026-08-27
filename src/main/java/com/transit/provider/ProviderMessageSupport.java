package com.transit.provider;

import com.transit.dto.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ProviderMessageSupport {

    private ProviderMessageSupport() {
    }

    public static String extractSystemPrompt(List<ChatRequest.Message> messages) {
        if (messages == null) {
            return "";
        }
        return messages.stream()
                .filter(message -> "system".equalsIgnoreCase(message.getRole()))
                .map(message -> toPlainText(message.getContent()))
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    public static List<ChatRequest.Message> nonSystemMessages(List<ChatRequest.Message> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> !"system".equalsIgnoreCase(message.getRole()))
                .toList();
    }

    public static String toPlainText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String value) {
            return value;
        }
        if (content instanceof List<?> list) {
            List<String> fragments = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object type = map.get("type");
                    if (Objects.equals(type, "text")
                            || Objects.equals(type, "input_text")
                            || Objects.equals(type, "output_text")) {
                        Object text = map.get("text");
                        if (text != null) {
                            fragments.add(String.valueOf(text));
                        }
                    }
                } else if (item != null) {
                    fragments.add(String.valueOf(item));
                }
            }
            return String.join("\n", fragments);
        }
        return String.valueOf(content);
    }
}
