package com.transit.dto;

import com.transit.model.PlusOrder;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlusOrderResponse {
    private PlusOrder order;
    private String message;
}
