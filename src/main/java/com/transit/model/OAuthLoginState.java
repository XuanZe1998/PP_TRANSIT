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

@TableName("oauth_login_states")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthLoginState {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("state_hash")
    private String stateHash;

    private String provider;

    @TableField("target_user_id")
    private Long targetUserId;

    @TableField("flow_type")
    private String flowType;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("consumed_at")
    private LocalDateTime consumedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
