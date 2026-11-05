package com.transit.dto;

import lombok.Data;

@Data
public class ServiceOrderQuoteRequest {
    private Long serviceId;
    private Integer quantity;
    private String couponCode;
    private String agentCode;
}
