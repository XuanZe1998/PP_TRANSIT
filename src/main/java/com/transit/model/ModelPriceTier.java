package com.transit.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@TableName("model_price_tiers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPriceTier {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("model_mapping_id")
    private Long modelMappingId;

    @TableField("tier_name")
    private String tierName;

    /** Inclusive upper context-token boundary; null means unlimited. */
    @TableField("max_context_tokens")
    private Integer maxContextTokens;

    @TableField("sort_order")
    private int sortOrder;

    @TableField("official_group_name")
    private String officialGroupName;
    @TableField("official_input_price")
    private BigDecimal officialInputPrice;
    @TableField("official_output_price")
    private BigDecimal officialOutputPrice;
    @TableField("official_cache_read_price")
    private BigDecimal officialCacheReadPrice;
    @TableField("official_cache_write_price")
    private BigDecimal officialCacheWritePrice;
    @TableField("official_price_unit")
    private String officialPriceUnit;
    @TableField("official_price_suffix")
    private String officialPriceSuffix;

    @TableField("cost_group_name")
    private String costGroupName;
    @TableField("cost_input_price")
    private BigDecimal costInputPrice;
    @TableField("cost_output_price")
    private BigDecimal costOutputPrice;
    @TableField("cost_cache_read_price")
    private BigDecimal costCacheReadPrice;
    @TableField("cost_cache_write_price")
    private BigDecimal costCacheWritePrice;
    @TableField("cost_price_unit")
    private String costPriceUnit;
    @TableField("cost_price_suffix")
    private String costPriceSuffix;

    @TableField("sale_group_name")
    private String saleGroupName;
    @TableField("sale_input_price")
    private BigDecimal saleInputPrice;
    @TableField("sale_output_price")
    private BigDecimal saleOutputPrice;
    @TableField("sale_cache_read_price")
    private BigDecimal saleCacheReadPrice;
    @TableField("sale_cache_write_price")
    private BigDecimal saleCacheWritePrice;
    @TableField("sale_price_unit")
    private String salePriceUnit;
    @TableField("sale_price_suffix")
    private String salePriceSuffix;

    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public BigDecimal getInputCostMultiplier() {
        return multiplier(costInputPrice, costPriceUnit, officialInputPrice, officialPriceUnit);
    }

    public BigDecimal getOutputCostMultiplier() {
        return multiplier(costOutputPrice, costPriceUnit, officialOutputPrice, officialPriceUnit);
    }

    public BigDecimal getCacheReadCostMultiplier() {
        return multiplier(costCacheReadPrice, costPriceUnit, officialCacheReadPrice, officialPriceUnit);
    }

    public BigDecimal getCacheWriteCostMultiplier() {
        return multiplier(costCacheWritePrice, costPriceUnit, officialCacheWritePrice, officialPriceUnit);
    }

    private BigDecimal multiplier(BigDecimal cost, String costUnit, BigDecimal official, String officialUnit) {
        if (cost == null || official == null || official.signum() <= 0) return null;
        BigDecimal normalizedCost = "KB".equalsIgnoreCase(costUnit) ? cost.multiply(BigDecimal.valueOf(1_000)) : cost;
        BigDecimal normalizedOfficial = "KB".equalsIgnoreCase(officialUnit) ? official.multiply(BigDecimal.valueOf(1_000)) : official;
        return normalizedCost.divide(normalizedOfficial, 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
