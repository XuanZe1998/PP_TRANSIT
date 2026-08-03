package com.transit.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PublicModel {
    private String publicName;
    private String type;
    private BigDecimal minInputPricePerMillion;
    private BigDecimal maxInputPricePerMillion;
    private BigDecimal minOutputPricePerMillion;
    private BigDecimal maxOutputPricePerMillion;
    private BigDecimal minCachedPricePerMillion;
    private BigDecimal maxCachedPricePerMillion;
    private BigDecimal minCacheReadPricePerMillion;
    private BigDecimal maxCacheReadPricePerMillion;
    private BigDecimal minCacheWritePricePerMillion;
    private BigDecimal maxCacheWritePricePerMillion;
    private long routeCount;
    private long providerCount;
    private String currency;
    private long amountScale;
    private String priceUnit;
    private boolean priceVariesByRoute;
}
