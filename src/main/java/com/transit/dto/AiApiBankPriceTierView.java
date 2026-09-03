package com.transit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiApiBankPriceTierView {
    private String label;
    private Integer minTokens;
    private Integer maxTokens;
    private AiApiBankPriceDimensions official;
    private AiApiBankPriceDimensions sourcePrice;
    private AiApiBankPriceDimensions sale;
}
