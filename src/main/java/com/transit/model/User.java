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

    @TableField("auth_provider")
    private String authProvider;

    @TableField("role")
    private String role; // ADMIN, USER

    @TableField("status")
    @Builder.Default
    private String status = "ACTIVE";

    @TableField("group_id")
    private Long groupId;

    @TableField("balance")
    @Builder.Default
    private long balance = 0; // Credits in tokens

    @TableField("created_at")
    private LocalDateTime createdAt;
}
