package com.transit.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import com.transit.dto.MoneyAmount;

@TableName("other_services")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtherService {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    @TableField("image_url")
    private String imageUrl;

    @TableField("sort_order")
    private Integer sortOrder;

    private Boolean enabled;

    @TableField("action_label")
    private String actionLabel;

    @TableField("price_cents")
    private Long priceCents;

    @TableField("service_fee_cents")
    private Long serviceFeeCents;

    @TableField(exist = false)
    private Long amountCents;

    @TableField(exist = false)
    private MoneyAmount priceMoney;

    @TableField(exist = false)
    private MoneyAmount serviceFeeMoney;

    @TableField(exist = false)
    private MoneyAmount amountMoney;

    @TableField("currency")
    private String currency;

    @TableField("purchase_enabled")
    private Boolean purchaseEnabled;

    @TableField("product_type")
    private String productType;

    @TableField("fulfillment_mode")
    private String fulfillmentMode;

    @TableField("purchase_prompt")
    private String purchasePrompt;

    @TableField("max_purchase_quantity")
    private Integer maxPurchaseQuantity;

    @TableField("manual_stock")
    private Integer manualStock;

    @JsonIgnore
    @TableField("manual_reserved")
    private Integer manualReserved;

    @TableField("wholesale_tiers_json")
    private String wholesaleTiersJson;

    @TableField("input_schema_json")
    private String inputSchemaJson;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @TableField("redemption_url")
    private String redemptionUrl;

    @TableField(exist = false)
    private Boolean redemptionConfigured;

    @TableField(exist = false)
    private String redemptionPath;

    @TableField(exist = false)
    private Integer availableStock;

    @TableField(exist = false)
    private Boolean orderEnabled;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
