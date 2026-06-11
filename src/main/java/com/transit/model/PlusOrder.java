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

    private String status;

    @TableField("contact_email")
    private String contactEmail;

    @TableField("contact_note")
    private String contactNote;

    @TableField("fulfillment_note")
    private String fulfillmentNote;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("downloaded_at")
    private LocalDateTime downloadedAt;
}
