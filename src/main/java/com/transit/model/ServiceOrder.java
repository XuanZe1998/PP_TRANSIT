package com.transit.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import com.transit.dto.MoneyAmount;

@TableName("service_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOrder {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Long userId;

    @TableField("service_id")
    private Long serviceId;

    @TableField("product_name")
    private String productName;

    private Integer quantity;

    @TableField("fulfillment_mode")
    private String fulfillmentMode;

    @TableField("unit_price_cents")
    private Long unitPriceCents;

    @TableField("effective_unit_price_cents")
    private Long effectiveUnitPriceCents;

    @TableField("merchandise_subtotal_cents")
    private Long merchandiseSubtotalCents;

    @TableField("wholesale_discount_cents")
    private Long wholesaleDiscountCents;

    @TableField("coupon_id")
    private Long couponId;

    @TableField("coupon_code")
    private String couponCode;

    @TableField("coupon_discount_cents")
    private Long couponDiscountCents;

    @JsonIgnore
    @TableField("coupon_reservation_active")
    private Boolean couponReservationActive;

    @JsonIgnore
    @TableField("refund_resources_released")
    private Boolean refundResourcesReleased;

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

    @TableField("invoice_number")
    private String invoiceNumber;

    @TableField("receipt_number")
    private String receiptNumber;

    @TableField("billing_name")
    private String billingName;

    @TableField("billing_address_line_1")
    private String billingAddressLine1;

    @TableField("billing_district")
    private String billingDistrict;

    @TableField("billing_city")
    private String billingCity;

    @TableField("billing_province")
    private String billingProvince;

    @TableField("billing_postal_code")
    private String billingPostalCode;

    @TableField("billing_country")
    private String billingCountry;

    @TableField("payment_method")
    private String paymentMethod;

    @JsonIgnore
    @TableField("custom_input_json")
    private String customInputJson;

    @TableField(exist = false)
    private java.util.Map<String, String> customFields;

    @TableField("purchase_prompt")
    private String purchasePrompt;

    @TableField("supplier_quote_json")
    private String supplierQuoteJson;

    @TableField("reservation_expires_at")
    private LocalDateTime reservationExpiresAt;

    @TableField("fulfillment_status")
    private String fulfillmentStatus;

    @JsonIgnore
    @TableField("delivery_content_encrypted")
    private String deliveryContentEncrypted;

    @TableField(exist = false)
    private java.util.List<String> deliveryItems;

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

    @TableField(exist = false) private MoneyAmount unitPriceMoney;
    @TableField(exist = false) private MoneyAmount effectiveUnitPriceMoney;
    @TableField(exist = false) private MoneyAmount merchandiseSubtotalMoney;
    @TableField(exist = false) private MoneyAmount wholesaleDiscountMoney;
    @TableField(exist = false) private MoneyAmount couponDiscountMoney;
    @TableField(exist = false) private MoneyAmount serviceFeeMoney;
    @TableField(exist = false) private MoneyAmount amountMoney;
    @TableField(exist = false) private MoneyAmount settlementMoney;
}
