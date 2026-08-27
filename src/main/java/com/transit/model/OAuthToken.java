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

@TableName("oauth_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthToken {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("access_token")
    @JsonIgnore
    private String accessToken;

    @TableField("refresh_token")
    @JsonIgnore
    private String refreshToken;

    @TableField("token_type")
    private String tokenType;

    @TableField("user_id")
    private Long userId;

    @TableField("client_id")
    private String clientId;

    @TableField("scope")
    private String scope;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("access_expires_at")
    private LocalDateTime accessExpiresAt;

    @TableField("revoked")
    private Boolean revoked;

    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("device_name")
    private String deviceName;

    @TableField("ip_digest")
    @JsonIgnore
    private String ipDigest;

    @TableField("last_active_at")
    private LocalDateTime lastActiveAt;
}
