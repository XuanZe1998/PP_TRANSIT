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

        public int cacheReadTokens() {
            int openAiCached = promptTokensDetails == null || promptTokensDetails.getCachedTokens() == null
                    ? 0 : Math.max(0, promptTokensDetails.getCachedTokens());
            int explicitRead = cacheReadInputTokens == null ? 0 : Math.max(0, cacheReadInputTokens);
            return Math.max(openAiCached, explicitRead);
        }

        public int cacheWriteTokens() {
            return cacheCreationInputTokens == null ? 0 : Math.max(0, cacheCreationInputTokens);
        }

        public Integer cachedTokens() {
            return Math.addExact(cacheReadTokens(), cacheWriteTokens());
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
        @JsonProperty("cache_read_tokens")
        private Integer cacheReadTokens;
        @JsonProperty("cache_write_tokens")
        private Integer cacheWriteTokens;
        @JsonProperty("price_tier")
        private String priceTier;
        @JsonProperty("sale_group_name")
        private String saleGroupName;
        @JsonProperty("price_unit")
        private String priceUnit;
        @JsonProperty("price_suffix")
        private String priceSuffix;
        @JsonProperty("input_price_per_million")
        private BigDecimal inputPricePerMillion;
        @JsonProperty("output_price_per_million")
        private BigDecimal outputPricePerMillion;
        @JsonProperty("cached_price_per_million")
        private BigDecimal cachedPricePerMillion;
        @JsonProperty("cache_read_price_per_million")
        private BigDecimal cacheReadPricePerMillion;
        @JsonProperty("cache_write_price_per_million")
        private BigDecimal cacheWritePricePerMillion;
        @JsonProperty("input_amount")
        private Long inputAmount;
        @JsonProperty("output_amount")
        private Long outputAmount;
        @JsonProperty("cached_amount")
        private Long cachedAmount;
        @JsonProperty("cache_read_amount")
        private Long cacheReadAmount;
        @JsonProperty("cache_write_amount")
        private Long cacheWriteAmount;
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
