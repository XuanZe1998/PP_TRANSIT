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

@TableName("vmcard_saved_cards")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VmCardSavedCard {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("card_id")
    private String cardId;

    private String environment;
    private String label;

    @TableField("product_code")
    private String productCode;

    private String email;

    @TableField("card_created_at")
    private LocalDateTime cardCreatedAt;

    @TableField("disabled_or_frozen_at")
    private LocalDateTime disabledOrFrozenAt;

    @TableField("encrypted_payload")
    private String encryptedPayload;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
