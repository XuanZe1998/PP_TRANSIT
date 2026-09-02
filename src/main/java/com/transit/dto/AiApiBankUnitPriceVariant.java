package com.transit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class AiApiBankUnitPriceVariant {
    private String resolution;
    private Integer maxEdgePixels;
    private BigDecimal sourcePrice;
    private BigDecimal sale;
    private String unit;
}

