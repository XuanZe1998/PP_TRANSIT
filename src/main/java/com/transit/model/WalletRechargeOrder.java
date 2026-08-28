package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.transit.dto.MoneyAmount;
import lombok.Data;

import java.time.LocalDateTime;
import java.math.BigDecimal;

@Data
@TableName("wallet_recharge_orders")
public class WalletRechargeOrder {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("order_no") private String orderNo;
    @TableField("user_id") private Long userId;
    @TableField("plan_id") private Long planId;
    @TableField("plan_name") private String planName;
    @TableField("payment_amount_units") private Long paymentAmountUnits;
    @TableField("base_credit_units") private Long baseCreditUnits;
    @TableField("bonus_percent") private BigDecimal bonusPercent;
    @TableField("bonus_credit_units") private Long bonusCreditUnits;
    @TableField("total_credit_units") private Long totalCreditUnits;
    private String status;
    @TableField("payment_method") private String paymentMethod;
    @TableField("invoice_number") private String invoiceNumber;
    @TableField("invoice_requested") private Boolean invoiceRequested;
    @TableField("receipt_number") private String receiptNumber;
    @TableField("contact_email") private String contactEmail;
    @TableField("billing_name") private String billingName;
    @TableField("billing_address_line_1") private String billingAddressLine1;
    @TableField("billing_district") private String billingDistrict;
    @TableField("billing_city") private String billingCity;
    @TableField("billing_province") private String billingProvince;
    @TableField("billing_postal_code") private String billingPostalCode;
    @TableField("billing_country") private String billingCountry;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
    @TableField("paid_at") private LocalDateTime paidAt;
    @TableField("refunded_at") private LocalDateTime refundedAt;

    @TableField(exist = false) private MoneyAmount paymentMoney;
    @TableField(exist = false) private MoneyAmount baseCreditMoney;
    @TableField(exist = false) private MoneyAmount bonusCreditMoney;
    @TableField(exist = false) private MoneyAmount totalCreditMoney;
    @TableField(exist = false) private PaymentIntent paymentIntent;
}
