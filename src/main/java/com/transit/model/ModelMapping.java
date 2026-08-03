package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@TableName("model_mappings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelMapping {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("public_model_name")
    private String publicModelName; // What the user sends (e.g., "gpt-4")

    @TableField("channel_model_name")
    private String channelModelName; // What the provider expects (e.g., "gpt-4-0613")

    @TableField("channel_id")
    private Long channelId;

    @Builder.Default
    private int priority = 10; // To handle multiple channels supporting the same model

    @Builder.Default
    private boolean enabled = true;

    @TableField("price_ratio")
    @Builder.Default
    private BigDecimal priceRatio = BigDecimal.ONE;

    @TableField("cost_per_million")
    @Builder.Default
    private BigDecimal costPerMillion = BigDecimal.ZERO;

    @TableField("input_price_per_million")
    @Builder.Default
    private BigDecimal inputPricePerMillion = BigDecimal.ONE;

    @TableField("output_price_per_million")
    @Builder.Default
    private BigDecimal outputPricePerMillion = BigDecimal.ONE;

    @TableField("cached_price_per_million")
    @Builder.Default
    private BigDecimal cachedPricePerMillion = BigDecimal.ZERO;

    @TableField("input_cost_per_million")
    @Builder.Default
    private BigDecimal inputCostPerMillion = BigDecimal.ZERO;

    @TableField("output_cost_per_million")
    @Builder.Default
    private BigDecimal outputCostPerMillion = BigDecimal.ZERO;

    @TableField("cached_cost_per_million")
    @Builder.Default
    private BigDecimal cachedCostPerMillion = BigDecimal.ZERO;

    @TableField("billing_enabled")
    @Builder.Default
    private boolean billingEnabled = true;

    @TableField("traffic_percent")
    @Builder.Default
    private int trafficPercent = 100;

    @TableField("capability_tags")
    private String capabilityTags;

    /** Context-sensitive official, acquisition-cost and sale-price groups. */
    @TableField(exist = false)
    @Builder.Default
    private List<ModelPriceTier> priceTiers = List.of();

    // Use a transient field for the Channel object if needed, 
    // or handle join manually in mapper.
    @TableField(exist = false)
    private Channel channel;

    /** Whether this mapping can currently receive end-user traffic. */
    @TableField(exist = false)
    private boolean callable;

    /** Stable machine-readable status used by the administration console. */
    @TableField(exist = false)
    private String availabilityStatus;

    /** Human-readable explanation when a mapping is not callable. */
    @TableField(exist = false)
    private String availabilityMessage;
}
