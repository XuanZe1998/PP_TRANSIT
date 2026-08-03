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

    @TableField("created_at")
    private LocalDateTime createdAt;
}
