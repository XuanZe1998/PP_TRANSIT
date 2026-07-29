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

    @JsonIgnore
    @TableField("service_type")
    private String serviceType;

    @JsonIgnore
    @TableField("linked_product_id")
    private Long linkedProductId;

    @TableField("action_label")
    private String actionLabel;

    @TableField("price_cents")
    private Long priceCents;

    @TableField("service_fee_cents")
    private Long serviceFeeCents;

    @TableField(exist = false)
    private Long amountCents;

    @TableField("currency")
    private String currency;

    @TableField("purchase_enabled")
    private Boolean purchaseEnabled;

    @TableField(exist = false)
    private Boolean orderEnabled;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
