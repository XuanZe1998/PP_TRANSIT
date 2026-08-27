package com.transit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PublicContextPricing {
    private boolean enabled;
    private String basis;
    private Integer thresholdTokens;
    private BigDecimal multiplier;
    private BigDecimal baseInputPrice;
    private BigDecimal baseOutputPrice;
    private BigDecimal longInputPrice;
    private BigDecimal longOutputPrice;
    private String priceUnit;
    private String message;
}
