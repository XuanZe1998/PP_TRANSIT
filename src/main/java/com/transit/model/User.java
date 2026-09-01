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

@TableName("users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("username")
    private String username;

    @TableField("password")
    @JsonIgnore
    private String password;

    @TableField("email")
    private String email;

    @TableField("phone")
    private String phone;

    @TableField("display_name")
    private String displayName;

    @TableField("avatar_path")
    private String avatarPath;

    @TableField("email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @TableField("phone_verified_at")
    private LocalDateTime phoneVerifiedAt;

    @TableField("locale")
    @Builder.Default
    private String locale = "zh-CN";

    @TableField("timezone")
    @Builder.Default
    private String timezone = "Asia/Shanghai";

    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    @TableField("auth_provider")
    private String authProvider;

    @TableField("role")
    private String role; // ADMIN, USER

    @TableField("status")
    @Builder.Default
    private String status = "ACTIVE";

    @TableField("group_id")
    private Long groupId;

    @TableField("default_organization_id")
    private Long defaultOrganizationId;

    @TableField("account_type")
    @Builder.Default
    private String accountType = "PERSONAL";

    @TableField("balance")
    @Builder.Default
    private long balance = 0; // Credits in tokens

    @TableField("invoice_enabled")
    @Builder.Default
    private boolean invoiceEnabled = false;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
