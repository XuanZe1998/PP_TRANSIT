package com.transit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ChatRequest {
    private String model;
    private List<Message> messages;
    private boolean stream;
    private Double temperature;
    @JsonProperty("top_p")
    private Double topP;
    private Integer n;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    @JsonProperty("presence_penalty")
    private Double presencePenalty;
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;
    @JsonProperty("logit_bias")
    private Map<String, Double> logitBias;
    @JsonProperty("chat_template_kwargs")
    private Map<String, Object> chatTemplateKwargs;
    private Integer seed;
    private String user;
    @JsonProperty("previous_response_id")
    private String previousResponseId;
    @JsonProperty("session_id")
    private String sessionId;

    @Data
    public static class Message {
        private String role;
        private Object content; // Content can be String or List (for multi-modal)
        private String name;
    }
}
