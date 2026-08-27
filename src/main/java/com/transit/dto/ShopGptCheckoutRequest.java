package com.transit.dto;

import lombok.Data;

@Data
public class ShopGptCheckoutRequest {
    private Integer quantity;
    private String contactEmail;
    private String billingName;
    private String billingAddressLine1;
    private String billingDistrict;
    private String billingCity;
    private String billingProvince;
    private String billingPostalCode;
    private String billingCountry;
    private String paymentMethod;
}
