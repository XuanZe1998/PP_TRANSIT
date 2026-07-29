package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;

@TableName("plus_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlusOrder {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Long userId;

    @TableField("product_id")
    private Long productId;

    @TableField("product_name")
    private String productName;

    @TableField("unit_price_cents")
    private Long unitPriceCents;

    @TableField("service_fee_cents")
    private Long serviceFeeCents;

    @TableField("amount_cents")
    private Long amountCents;

    private String currency;

    @TableField("payment_amount_cents")
    private Long paymentAmountCents;

    @TableField("payment_currency")
    private String paymentCurrency;

    @TableField("exchange_rate")
    private BigDecimal exchangeRate;

    private String status;

    @TableField("contact_email")
    private String contactEmail;

    @TableField("contact_note")
    private String contactNote;

    @TableField("fulfillment_note")
    private String fulfillmentNote;

    @TableField("payment_reference")
    private String paymentReference;

    @TableField("payment_provider")
    private String paymentProvider;

    @TableField("provider_trade_no")
    private String providerTradeNo;

    @TableField("payment_type")
    private String paymentType;

    @TableField("payment_url")
    private String paymentUrl;

    @TableField("fulfillment_reference")
    private String fulfillmentReference;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("paid_at")
    private LocalDateTime paidAt;

    @TableField("fulfilled_at")
    private LocalDateTime fulfilledAt;

    @TableField("downloaded_at")
    private LocalDateTime downloadedAt;
}
