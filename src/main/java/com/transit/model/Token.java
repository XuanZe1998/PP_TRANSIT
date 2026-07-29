package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@TableName("tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Token {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("`key`") // "key" is a reserved word in MySQL
    @JsonIgnore
    private String key; // The API Key for users (e.g., sk-xxxx)

    @TableField("key_prefix")
    private String keyPrefix;

    @TableField("user_id")
    private Long userId;

    private String name;

    @TableField("used_quota")
    @Builder.Default
    private long usedQuota = 0;

    @TableField("total_quota")
    @Builder.Default
    private long totalQuota = 0; // Total quota in tokens or credits

    @Builder.Default
    private boolean enabled = true;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("expired_at")
    private LocalDateTime expiredAt;

    @TableField("allowed_models")
    private String allowedModels;

    @TableField("ip_whitelist")
    private String ipWhitelist;

    private String description;
}
