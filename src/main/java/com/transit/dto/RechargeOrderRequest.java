package com.transit.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RechargeOrderRequest {
    private Long planId;
    private BigDecimal customAmount;
    private String paymentMethod;
    private Boolean needInvoice;
    private String contactEmail;
    private String billingName;
    private String billingAddressLine1;
    private String billingDistrict;
    private String billingCity;
    private String billingProvince;
    private String billingPostalCode;
    private String billingCountry;
}
