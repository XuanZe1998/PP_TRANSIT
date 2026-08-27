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
import java.math.BigDecimal;

@TableName("logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Log {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("token_key")
    private String tokenKey;

    @TableField("token_id")
    private Long tokenId;

    @TableField("model")
    private String model;

    @TableField("channel_id")
    private Long channelId;

    @TableField("organization_id")
    private Long organizationId;

    @TableField("credential_id")
    private Long credentialId;

    @TableField("source_code")
    private String sourceCode;

    @TableField("prompt_tokens")
    private int promptTokens;

    @TableField("completion_tokens")
    private int completionTokens;

    @TableField("cached_tokens")
    private int cachedTokens;

    @TableField("cache_read_tokens")
    private int cacheReadTokens;

    @TableField("cache_write_tokens")
    private int cacheWriteTokens;

    @TableField("cache_miss_tokens")
    private int cacheMissTokens;

    @TableField("total_tokens")
    private int totalTokens;

    @TableField("cost")
    private long cost; // token-based cost

    @TableField("status")
    private String status; // SUCCESS, FAILED

    @TableField("latency_ms")
    private long latencyMs;

    @TableField("trace_id")
    private String traceId;

    @TableField("error_message")
    private String errorMessage;

    @TableField("sale_amount")
    private long saleAmount;

    @TableField("cost_amount")
    private long costAmount;

    @TableField("input_amount")
    private long inputAmount;

    @TableField("output_amount")
    private long outputAmount;

    @TableField("cached_amount")
    private long cachedAmount;

    @TableField("cache_read_amount")
    private long cacheReadAmount;

    @TableField("cache_write_amount")
    private long cacheWriteAmount;

    @TableField("total_amount")
    private long totalAmount;

    @TableField("input_cost_amount")
    private long inputCostAmount;

    @TableField("output_cost_amount")
    private long outputCostAmount;

    @TableField("cached_cost_amount")
    private long cachedCostAmount;

    @TableField("cache_read_cost_amount")
    private long cacheReadCostAmount;

    @TableField("cache_write_cost_amount")
    private long cacheWriteCostAmount;

    @TableField("gross_profit")
    private long grossProfit;

    @TableField("model_currency")
    private String modelCurrency;

    @TableField("model_amount_scale")
    private long modelAmountScale;

    @TableField("settlement_amount")
    private long settlementAmount;

    @TableField("settlement_currency")
    private String settlementCurrency;

    @TableField("exchange_rate")
    private java.math.BigDecimal exchangeRate;

    @TableField("billing_unit")
    private String billingUnit;

    @TableField("billable_quantity")
    private BigDecimal billableQuantity;

    @TableField("unit_sale_price")
    private BigDecimal unitSalePrice;

    @TableField("unit_cost_price")
    private BigDecimal unitCostPrice;

    @TableField("pricing_tier")
    private String pricingTier;

    @TableField("context_threshold_tokens")
    private Integer contextThresholdTokens;

    @TableField("input_unit_sale_price")
    private BigDecimal inputUnitSalePrice;

    @TableField("output_unit_sale_price")
    private BigDecimal outputUnitSalePrice;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
