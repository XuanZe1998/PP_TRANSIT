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

@TableName("vmcard_product_codes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VmCardProductCode {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String environment;
    private String bin;

    @TableField("product_code")
    private String productCode;

    private String type;
    private String network;
    private String media;

    @TableField("issuing_area")
    private String issuingArea;

    @TableField("remaining_open_card_num")
    private Integer remainingOpenCardNum;

    private Boolean available;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
