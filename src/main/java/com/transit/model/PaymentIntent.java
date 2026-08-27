package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.transit.dto.MoneyAmount;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_intents")
public class PaymentIntent {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("order_no") private String orderNo;
    @TableField("user_id") private Long userId;
    @TableField("business_type") private String businessType;
    @TableField("business_id") private Long businessId;
    private String description;
    @TableField("source_amount") private Long sourceAmount;
    @TableField("source_currency") private String sourceCurrency;
    @TableField("source_scale") private Long sourceScale;
    @TableField("settlement_amount_cents") private Long settlementAmountCents;
    @TableField("settlement_currency") private String settlementCurrency;
    @TableField("exchange_rate") private BigDecimal exchangeRate;
    @TableField("payment_method") private String paymentMethod;
    private String status;
    @TableField("payment_provider") private String paymentProvider;
    @TableField("provider_trade_no") private String providerTradeNo;
    @TableField("payment_type") private String paymentType;
    @TableField("payment_url") private String paymentUrl;
    @TableField("expires_at") private LocalDateTime expiresAt;
    @TableField("paid_at") private LocalDateTime paidAt;
    @TableField("refund_status") private String refundStatus;
    @TableField("refund_no") private String refundNo;
    @TableField("provider_refund_no") private String providerRefundNo;
    @TableField("refund_reason") private String refundReason;
    @TableField("refunded_at") private LocalDateTime refundedAt;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;

    @TableField(exist = false) private MoneyAmount sourceMoney;
    @TableField(exist = false) private MoneyAmount settlementMoney;
    @JsonIgnore @TableField(exist = false) private String internalState;
}
