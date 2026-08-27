package com.transit.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ServiceOrderQuoteResponse {
    private Long serviceId;
    private String serviceName;
    private String fulfillmentMode;
    private Integer quantity;
    private Long listUnitPriceCents;
    private Long effectiveUnitPriceCents;
    private Long merchandiseSubtotalCents;
    private Long wholesaleDiscountCents;
    private Long couponDiscountCents;
    private Long serviceFeeCents;
    private Long amountCents;
    private String currency;
    private Integer availableStock;
    private Boolean available;
    private String purchasePrompt;
    private List<InputField> inputFields;
    private Long couponId;
    private String couponCode;
    private MoneyAmount listUnitPriceMoney;
    private MoneyAmount effectiveUnitPriceMoney;
    private MoneyAmount merchandiseSubtotalMoney;
    private MoneyAmount wholesaleDiscountMoney;
    private MoneyAmount couponDiscountMoney;
    private MoneyAmount serviceFeeMoney;
    private MoneyAmount amountMoney;

    public record InputField(String key, String label, boolean required, int maxLength) {}
}
