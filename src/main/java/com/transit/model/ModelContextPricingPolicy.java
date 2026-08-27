package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("model_context_pricing_policies")
public class ModelContextPricingPolicy {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("public_model_name") private String publicModelName;
    private Boolean enabled;
    @TableField("threshold_tokens") private Integer thresholdTokens;
    private BigDecimal multiplier;
    @TableField("verification_note") private String verificationNote;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
