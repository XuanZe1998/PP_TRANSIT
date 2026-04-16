package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
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
    private String accessToken;

    @TableField("refresh_token")
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

    @TableField("revoked")
    private Boolean revoked;

    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
