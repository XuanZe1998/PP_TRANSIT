package com.transit.dto;

import lombok.Data;

@Data
public class ServiceOrderRequest {
    private Long serviceId;
    private String contactEmail;
    private String contactNote;
}
