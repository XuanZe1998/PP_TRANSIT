package com.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    @JsonIgnore private BigDecimal baseRateMultiplier;
    @JsonIgnore private BigDecimal groupRateMultiplier;
    @JsonIgnore private BigDecimal userRateMultiplier;
    @JsonIgnore private BigDecimal resolvedRateMultiplier;
    private boolean peakRateEnabled;
    private String peakStart;
    private String peakEnd;
    @JsonIgnore private BigDecimal peakRateMultiplier;
    private String timezone;
    private boolean exclusive;
    private boolean imageRateIndependent;
    @JsonIgnore private BigDecimal imageRateMultiplier;
    private boolean longContextPricingEnabled;
}
