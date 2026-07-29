package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@TableName("channels")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Channel {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String type; // e.g., "openai", "anthropic", "gemini", "deepseek"

    @TableField("base_url")
    private String baseUrl;

    @TableField("api_key")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    @TableField(exist = false)
    private boolean apiKeyConfigured;

    @TableField(exist = false)
    private String apiKeyPreview;

    private String models; // Comma separated list of models supported by this channel

    @Builder.Default
    private boolean enabled = true;

    @TableField("group_name")
    @Builder.Default
    private String groupName = "default";

    @Builder.Default
    private int weight = 100;

    @TableField("rpm_limit")
    @Builder.Default
    private int rpmLimit = 0;

    @TableField("tpm_limit")
    @Builder.Default
    private int tpmLimit = 0;

    @TableField("health_status")
    @Builder.Default
    private String healthStatus = "UNTESTED";

    @TableField("cooldown_until")
    private LocalDateTime cooldownUntil;

    /** Automatically place an unhealthy channel into a temporary cooldown. */
    @TableField("auto_disable")
    @Builder.Default
    private boolean autoDisable = true;

    @TableField("failure_threshold")
    @Builder.Default
    private int failureThreshold = 3;

    @TableField("cooldown_seconds")
    @Builder.Default
    private int cooldownSeconds = 60;

    @TableField("consecutive_failures")
    @Builder.Default
    private int consecutiveFailures = 0;

    @TableField("total_successes")
    @Builder.Default
    private long totalSuccesses = 0;

    @TableField("total_failures")
    @Builder.Default
    private long totalFailures = 0;

    @TableField("average_latency_ms")
    @Builder.Default
    private long averageLatencyMs = 0;

    @TableField("last_error")
    private String lastError;

    @TableField("last_tested_at")
    private LocalDateTime lastTestedAt;

    @TableField("last_success_at")
    private LocalDateTime lastSuccessAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
