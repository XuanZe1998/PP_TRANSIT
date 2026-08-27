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
import java.math.BigDecimal;

@TableName("creative_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreativeTask {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("provider_key")
    private String providerKey;

    @TableField("provider_config_id")
    private Long providerConfigId;

    @TableField("project_id")
    private Long projectId;

    @TableField("shot_id")
    private Long shotId;

    private String stage;

    @TableField("model_key")
    private String modelKey;

    private String mode;

    @TableField("project_name")
    private String projectName;

    private String prompt;

    @TableField("first_frame_url")
    private String firstFrameUrl;

    @TableField("input_last_frame_url")
    private String inputLastFrameUrl;

    @TableField("reference_urls_json")
    private String referenceUrlsJson;

    @TableField("options_json")
    private String optionsJson;

    @TableField("provider_task_id")
    private String providerTaskId;

    private String status;

    @TableField("video_url")
    private String videoUrl;

    @TableField("thumbnail_url")
    private String thumbnailUrl;

    @TableField("output_last_frame_url")
    private String outputLastFrameUrl;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("billing_unit")
    private String billingUnit;

    @TableField("billable_quantity")
    private BigDecimal billableQuantity;

    @TableField("unit_sale_price")
    private BigDecimal unitSalePrice;

    @TableField("unit_cost_price")
    private BigDecimal unitCostPrice;
}
