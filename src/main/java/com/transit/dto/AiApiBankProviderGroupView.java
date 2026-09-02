package com.transit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class AiApiBankProviderGroupView {
    private String channel;
    private Long externalId;
    private String name;
    private String slug;
    private String description;
    private String platform;
    private String subscriptionType;
    private BigDecimal baseRateMultiplier;
    private BigDecimal groupRateMultiplier;
    private BigDecimal userRateMultiplier;
    private BigDecimal resolvedRateMultiplier;
    private boolean peakRateEnabled;
    private String peakStart;
    private String peakEnd;
    private BigDecimal peakRateMultiplier;
    private String timezone;
    private boolean exclusive;
    private boolean imageRateIndependent;
    private BigDecimal imageRateMultiplier;
    private boolean longContextPricingEnabled;
}

