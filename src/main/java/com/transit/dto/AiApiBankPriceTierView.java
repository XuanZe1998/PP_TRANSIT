package com.transit.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiApiBankPriceTierView {
    private String label;
    private Integer minTokens;
    private Integer maxTokens;
    private AiApiBankPriceDimensions official;
    private AiApiBankPriceDimensions sourcePrice;
    private AiApiBankPriceDimensions sale;
}

