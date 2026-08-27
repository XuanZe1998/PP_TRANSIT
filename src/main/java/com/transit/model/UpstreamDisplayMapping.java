package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("upstream_display_mappings")
public class UpstreamDisplayMapping {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("channel_id") private Long channelId;
    @TableField("public_code") private String publicCode;
    @TableField("public_name") private String publicName;
    @TableField("badge_text") private String badgeText;
    @TableField("badge_color") private String badgeColor;
    @TableField("sort_order") private Integer sortOrder;
    private Boolean enabled;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
