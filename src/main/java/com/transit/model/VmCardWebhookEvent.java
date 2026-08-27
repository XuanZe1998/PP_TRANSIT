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

@TableName("vmcard_webhook_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VmCardWebhookEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("event_type")
    private String eventType;

    @TableField("external_id")
    private String externalId;

    @TableField("encrypted_payload")
    private String encryptedPayload;

    @TableField("received_at")
    private LocalDateTime receivedAt;
}
