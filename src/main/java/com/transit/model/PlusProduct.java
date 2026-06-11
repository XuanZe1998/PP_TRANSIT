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

@TableName("plus_products")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlusProduct {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    @TableField("image_url")
    private String imageUrl;

    @TableField("price_cents")
    private Long priceCents;

    @TableField("service_fee_cents")
    private Long serviceFeeCents;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
