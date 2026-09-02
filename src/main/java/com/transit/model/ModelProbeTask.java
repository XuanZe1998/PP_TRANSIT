package com.transit.model;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single model-probe (identity / quality / security) task record.
 *
 * The heavy probe run happens in the Node.js sidecar; this row tracks the
 * lifecycle (submitted -> running -> done / failed) and stores the JSON
 * report so the frontend can poll for the result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("model_probe_tasks")
public class ModelProbeTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Idempotency key supplied by the caller. */
    @TableField("idempotency_key")
    private String idempotencyKey;

    /** Owning user; null for admin-initiated probes. */
    @TableField("user_id")
    private Long userId;

    /** Target OpenAI-compatible endpoint base URL. */
    @TableField("base_url")
    private String baseUrl;

    /** API key used against the endpoint (stored encrypted-transient; see service). */
    @TableField("api_key")
    private String apiKey;

    /** Model id claimed by the endpoint. */
    @TableField("model_id")
    private String modelId;

    /** Model the vendor claims to be running (used for identity verification). */
    @TableField("claimed_model")
    private String claimedModel;

    /** Whether optional probes (e.g. context length) are included. */
    @TableField("include_optional")
    private boolean includeOptional;

    /** Lifecycle status: SUBMITTED / RUNNING / SUCCESS / FAILED. */
    @TableField("status")
    private String status;

    /** 0-100 conservative score from the report (null until SUCCESS). */
    @TableField("score")
    private Integer score;

    /** 0-100 optimistic score from the report. */
    @TableField("score_max")
    private Integer scoreMax;

    /** Full JSON report produced by the engine (null until SUCCESS). */
    @TableField("report_json")
    private String reportJson;

    /** Error message when FAILED. */
    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;
}