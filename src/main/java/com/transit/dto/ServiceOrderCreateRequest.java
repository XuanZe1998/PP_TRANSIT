package com.transit.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ServiceOrderCreateRequest {
    private Long serviceId;
    private Integer quantity;
    private String couponCode;
    private Map<String, String> customFields;
    private String contactEmail;
    private String contactNote;
    private String billingName;
    private String billingAddressLine1;
    private String billingDistrict;
    private String billingCity;
    private String billingProvince;
    private String billingPostalCode;
    private String billingCountry;
    private String paymentMethod;
}
