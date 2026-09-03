package com.transit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiApiBankUnitPriceVariant {
    private String resolution;
    private Integer maxEdgePixels;
    private BigDecimal sourcePrice;
    private BigDecimal sale;
    private String unit;
}
