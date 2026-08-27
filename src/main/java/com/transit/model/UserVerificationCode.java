package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_verification_codes")
public class UserVerificationCode {
    @TableId(type = IdType.AUTO) private Long id;
    private String recipient;
    private String channel;
    private String purpose;
    @TableField("code_hash") private String codeHash;
    private String status;
    private Integer attempts;
    @TableField("expires_at") private LocalDateTime expiresAt;
    @TableField("consumed_at") private LocalDateTime consumedAt;
    @TableField("created_at") private LocalDateTime createdAt;
}
