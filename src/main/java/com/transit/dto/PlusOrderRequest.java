package com.transit.dto;

import lombok.Data;

@Data
public class PlusOrderRequest {
    private Long productId;
    private String contactEmail;
    private String contactNote;
}
