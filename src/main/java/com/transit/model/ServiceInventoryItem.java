package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_inventory_items")
public class ServiceInventoryItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("service_id") private Long serviceId;
    @JsonIgnore @TableField("content_encrypted") private String contentEncrypted;
    @JsonIgnore @TableField("content_fingerprint") private String contentFingerprint;
    private String status;
    @TableField("reserved_order_id") private Long reservedOrderId;
    @TableField("reserved_until") private LocalDateTime reservedUntil;
    @TableField("delivered_at") private LocalDateTime deliveredAt;
    @TableField("created_at") private LocalDateTime createdAt;
}
