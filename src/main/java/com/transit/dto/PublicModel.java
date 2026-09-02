package com.transit.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PublicModel {
    private String publicName;
    private String displayName;
    private String upstreamModelName;
    private int displayPriority;
    private String type;
    private String source;
    private String sourceName;
    private String sources;
    private String verificationStatus;
    private String verificationMessage;
    private java.time.LocalDateTime verifiedAt;
    private java.time.LocalDateTime lastSeenAt;
    private String vendor;
    private String capability;
    private String inputModalities;
    private String outputModalities;
    private String protocols;
    private String pricingUnit;
    private boolean available;
    private boolean billingConfigured;
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
    private BigDecimal minInputCostMultiplier;
    private BigDecimal maxInputCostMultiplier;
    private BigDecimal minOutputCostMultiplier;
    private BigDecimal maxOutputCostMultiplier;
    private BigDecimal minCacheReadCostMultiplier;
    private BigDecimal maxCacheReadCostMultiplier;
    private BigDecimal minCacheWriteCostMultiplier;
    private BigDecimal maxCacheWriteCostMultiplier;
    private long routeCount;
    private long providerCount;
    private String currency;
    private long amountScale;
    private String priceUnit;
    private boolean priceVariesByRoute;
    private MoneyAmount minInputPriceMoney;
    private MoneyAmount maxInputPriceMoney;
    private MoneyAmount minOutputPriceMoney;
    private MoneyAmount maxOutputPriceMoney;
    private MoneyAmount minCachedPriceMoney;
    private MoneyAmount maxCachedPriceMoney;
    private MoneyAmount minCacheReadPriceMoney;
    private MoneyAmount maxCacheReadPriceMoney;
    private MoneyAmount minCacheWritePriceMoney;
    private MoneyAmount maxCacheWritePriceMoney;
    private String billingMode;
    private String pricingStatus;
    private String pricingMessage;
    private String pricingSourceUrl;
    private java.time.LocalDateTime pricingVerifiedAt;
    private BigDecimal saleUnitPrice;
    private PublicModelPricing pricing;
    private java.util.List<PublicUpstream> upstreams;
    private PublicContextPricing contextPricing;
    private AiApiBankProviderGroupView providerGroup;
    private java.util.List<AiApiBankPriceTierView> priceTiers;
    private java.util.List<AiApiBankUnitPriceVariant> unitPriceVariants;

    public void applyMoney(String currency,long scale){
        this.currency=currency;this.amountScale=scale;
        minInputPriceMoney=money(minInputPricePerMillion,currency,scale);maxInputPriceMoney=money(maxInputPricePerMillion,currency,scale);
        minOutputPriceMoney=money(minOutputPricePerMillion,currency,scale);maxOutputPriceMoney=money(maxOutputPricePerMillion,currency,scale);
        minCachedPriceMoney=money(minCachedPricePerMillion,currency,scale);maxCachedPriceMoney=money(maxCachedPricePerMillion,currency,scale);
        minCacheReadPriceMoney=money(minCacheReadPricePerMillion,currency,scale);maxCacheReadPriceMoney=money(maxCacheReadPricePerMillion,currency,scale);
        minCacheWritePriceMoney=money(minCacheWritePricePerMillion,currency,scale);maxCacheWritePriceMoney=money(maxCacheWritePricePerMillion,currency,scale);
    }
    private MoneyAmount money(BigDecimal value,String currency,long scale){return value==null?null:new MoneyAmount(value.multiply(BigDecimal.valueOf(scale)).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact(),currency,scale);}
}
