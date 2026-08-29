package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("provider_credentials")
public class ProviderCredential {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("channel_id")
    private Long channelId;
    private String name;
    @Builder.Default private String platform = "COMPATIBLE";
    @TableField("auth_type") @Builder.Default private String authType = "API_KEY";
    @TableField("encrypted_secret")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String secret;
    @TableField("secret_preview")
    private String secretPreview;
    @TableField("encrypted_credential_bundle")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String credentialBundle;
    @TableField("oauth_expires_at") private LocalDateTime oauthExpiresAt;
    @TableField("account_group") @Builder.Default private String accountGroup = "default";
    @TableField("upstream_proxy_id") private Long upstreamProxyId;
    @TableField("cost_mode") @Builder.Default private String costMode = "MODEL_MAPPING";
    @TableField("period_cost_amount") private long periodCostAmount;
    @TableField("cost_reliable") @Builder.Default private boolean costReliable = false;
    @TableField("model_scope") private String modelScope;
    private int priority;
    @Builder.Default private int weight = 100;
    @TableField("rpm_limit") private int rpmLimit;
    @TableField("tpm_limit") private int tpmLimit;
    @TableField("concurrency_limit") private int concurrencyLimit;
    @Builder.Default private boolean enabled = true;
    @TableField("health_status") @Builder.Default private String healthStatus = "UNTESTED";
    @TableField("cooldown_until") private LocalDateTime cooldownUntil;
    @TableField("temporary_unschedulable_until") private LocalDateTime temporaryUnschedulableUntil;
    @TableField("consecutive_failures") private int consecutiveFailures;
    @TableField("total_successes") private long totalSuccesses;
    @TableField("total_failures") private long totalFailures;
    @TableField("average_latency_ms") private long averageLatencyMs;
    @TableField("last_error") private String lastError;
    @TableField("last_error_class") private String lastErrorClass;
    @TableField("last_used_at") private LocalDateTime lastUsedAt;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
