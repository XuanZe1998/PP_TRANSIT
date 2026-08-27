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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("provider_models")
public class ProviderModel {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("source_code") private String sourceCode;
    @TableField("source_name") private String sourceName;
    @TableField("upstream_model_name") private String upstreamModelName;
    @TableField("public_model_name") private String publicModelName;
    private String vendor;
    private String capability;
    @TableField("input_modalities") private String inputModalities;
    @TableField("output_modalities") private String outputModalities;
    private String protocols;
    @TableField("pricing_unit") private String pricingUnit;
    @TableField("endpoint_path") private String endpointPath;
    @TableField("task_query_path") private String taskQueryPath;
    @TableField("task_query_method") private String taskQueryMethod;
    @TableField("verification_status") private String verificationStatus;
    @TableField("verification_message") private String verificationMessage;
    @TableField("verified_at") private LocalDateTime verifiedAt;
    @TableField("last_seen_at") private LocalDateTime lastSeenAt;
    @TableField("missing_sync_count") private int missingSyncCount;
    @TableField("raw_metadata") private String rawMetadata;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
