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

@TableName("creative_provider_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreativeProviderConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("provider_key")
    private String providerKey;

    @TableField("display_name")
    private String displayName;

    @TableField("base_url")
    private String baseUrl;

    @TableField("api_key")
    private String apiKey;

    @TableField("model_ids_json")
    private String modelIdsJson;

    @TableField("default_model")
    private String defaultModel;

    private boolean enabled;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
