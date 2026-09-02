package com.transit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class AiApiBankPriceDimensions {
    private BigDecimal input;
    private BigDecimal output;
    private BigDecimal cacheRead;
    private BigDecimal cacheWrite;
    /** Optional one-hour cache-write price when the upstream exposes it. */
    private BigDecimal cacheWrite1h;
    /** Optional image input/output dimensions exposed by the upstream token matrix. */
    private BigDecimal imageInput;
    private BigDecimal imageOutput;
    private BigDecimal perRequest;
    private String unit;
}
