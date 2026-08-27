package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_refund_jobs")
public class PaymentRefundJob {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("payment_intent_id") private Long paymentIntentId;
    @TableField("service_order_id") private Long serviceOrderId;
    private String reason;
    private String status;
    private Integer attempts;
    @TableField("next_attempt_at") private LocalDateTime nextAttemptAt;
    @TableField("last_error") private String lastError;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
