package com.transit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PublicModelPricing {
    private String billingMode;
    private String status;
    private String message;
    private String sourceUrl;
    private LocalDateTime verifiedAt;
    private String unit;
    private String unitLabel;
    private String currency;
    private BigDecimal saleUnitPrice;
    private MoneyAmount saleUnitPriceMoney;
}
