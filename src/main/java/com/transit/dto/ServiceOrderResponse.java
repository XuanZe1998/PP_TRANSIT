package com.transit.dto;

import com.transit.model.ServiceOrder;
import com.transit.model.PaymentIntent;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServiceOrderResponse {
    private ServiceOrder order;
    private String message;
    private String payType;
    private String paymentUrl;
    private String providerTradeNo;
    private PaymentIntent paymentIntent;
}
