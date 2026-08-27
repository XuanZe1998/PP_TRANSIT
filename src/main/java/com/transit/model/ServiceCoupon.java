package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("service_coupons")
public class ServiceCoupon {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    @TableField("discount_cents") private Long discountCents;
    @TableField("remaining_uses") private Integer remainingUses;
    @TableField("reserved_uses") private Integer reservedUses;
    private Boolean enabled;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
    @TableField(exist = false) private List<Long> serviceIds;
}
