package com.transit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ChatResponse {
    private String id;
    private String object;
    private Long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;
    /**
     * Gateway-calculated settlement data. The client may display this object,
     * but all rates and amounts are selected and calculated by the server.
     */
    private Billing billing;

    @Data
    public static class Choice {
        private Integer index;
        private Message message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    public static class Message {
        private String role;
        private String content;
        @JsonProperty("reasoning_content")
        private String reasoningContent;
        private String reasoning;
    }

    @Data
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @JsonProperty("completion_tokens")
        private Integer completionTokens;
        @JsonProperty("total_tokens")
        private Integer totalTokens;
        @JsonProperty("prompt_tokens_details")
        private PromptTokensDetails promptTokensDetails;
        @JsonProperty("cache_read_input_tokens")
        private Integer cacheReadInputTokens;
        @JsonProperty("cache_creation_input_tokens")
        private Integer cacheCreationInputTokens;
        /** True only when the upstream omitted usage and the gateway used its
         * documented conservative tokenizer estimate. */
        private Boolean estimated;

        public Integer cachedTokens() {
            int cached = 0;
            if (promptTokensDetails != null && promptTokensDetails.getCachedTokens() != null) {
                cached += promptTokensDetails.getCachedTokens();
            }
            if (cacheReadInputTokens != null) {
                cached += cacheReadInputTokens;
            }
            if (cacheCreationInputTokens != null) {
                cached += cacheCreationInputTokens;
            }
            return cached;
        }
    }

    @Data
    public static class Billing {
        private String currency;
        @JsonProperty("amount_scale")
        private Long amountScale;
        @JsonProperty("trace_id")
        private String traceId;
        @JsonProperty("billable_input_tokens")
        private Integer billableInputTokens;
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;
        @JsonProperty("input_price_per_million")
        private BigDecimal inputPricePerMillion;
        @JsonProperty("output_price_per_million")
        private BigDecimal outputPricePerMillion;
        @JsonProperty("cached_price_per_million")
        private BigDecimal cachedPricePerMillion;
        @JsonProperty("input_amount")
        private Long inputAmount;
        @JsonProperty("output_amount")
        private Long outputAmount;
        @JsonProperty("cached_amount")
        private Long cachedAmount;
        @JsonProperty("total_amount")
        private Long totalAmount;
        @JsonProperty("billing_enabled")
        private Boolean billingEnabled;
    }

    @Data
    public static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;
    }
}
