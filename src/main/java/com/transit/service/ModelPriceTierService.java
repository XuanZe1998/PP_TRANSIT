package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.ModelPriceTierMapper;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModelPriceTierService {
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");
    private static final int MAX_CONTEXT_TOKENS = 100_000_000;
    private static final Set<String> PRICE_UNITS = Set.of("M", "KB");

    private final ModelPriceTierMapper tierMapper;
    private final ModelMappingMapper mappingMapper;

    public void attach(List<ModelMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) return;
        List<Long> ids = mappings.stream().map(ModelMapping::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        Map<Long, List<ModelPriceTier>> byMapping = tierMapper.selectList(
                        new LambdaQueryWrapper<ModelPriceTier>()
                                .in(ModelPriceTier::getModelMappingId, ids)
                                .orderByAsc(ModelPriceTier::getModelMappingId)
                                .orderByAsc(ModelPriceTier::getSortOrder)
                                .orderByAsc(ModelPriceTier::getId))
                .stream()
                .collect(Collectors.groupingBy(ModelPriceTier::getModelMappingId,
                        LinkedHashMap::new, Collectors.toList()));
        mappings.forEach(mapping -> mapping.setPriceTiers(
                byMapping.getOrDefault(mapping.getId(), List.of())));
    }

    @Transactional
    public void synchronize(ModelMapping mapping, List<ModelPriceTier> requested) {
        if (mapping.getId() == null) {
            throw new IllegalStateException("Model mapping must be persisted before its price tiers");
        }
        List<ModelPriceTier> source = requested == null ? List.of() : requested;
        if (source.isEmpty()) {
            List<ModelPriceTier> existing = tiersFor(mapping.getId());
            if (!existing.isEmpty()) {
                mapping.setPriceTiers(existing);
                return;
            }
            source = List.of(defaultTier(mapping));
        }
        List<ModelPriceTier> normalized = normalize(mapping.getId(), source);
        tierMapper.delete(new LambdaQueryWrapper<ModelPriceTier>()
                .eq(ModelPriceTier::getModelMappingId, mapping.getId()));
        normalized.forEach(tierMapper::insert);
        mapping.setPriceTiers(normalized);
        mirrorPrimaryTier(mapping, normalized.get(0));
        mappingMapper.updateById(mapping);
    }

    public void ensureDefault(ModelMapping mapping) {
        synchronize(mapping, List.of());
    }

    @Transactional
    public void deleteForMappings(List<Long> mappingIds) {
        if (mappingIds == null || mappingIds.isEmpty()) return;
        tierMapper.delete(new LambdaQueryWrapper<ModelPriceTier>()
                .in(ModelPriceTier::getModelMappingId, mappingIds));
    }

    public ModelPriceTier select(ModelMapping mapping, int contextTokens) {
        List<ModelPriceTier> tiers = mapping.getPriceTiers();
        if (tiers == null || tiers.isEmpty()) {
            tiers = tiersFor(mapping.getId());
            mapping.setPriceTiers(tiers);
        }
        int context = Math.max(0, contextTokens);
        List<ModelPriceTier> availableTiers = tiers;
        return availableTiers.stream()
                .sorted(Comparator.comparingInt(ModelPriceTier::getSortOrder))
                .filter(tier -> tier.getMaxContextTokens() == null || context <= tier.getMaxContextTokens())
                .findFirst()
                .orElseGet(() -> availableTiers.isEmpty()
                        ? defaultTier(mapping)
                        : availableTiers.get(availableTiers.size() - 1));
    }

    private List<ModelPriceTier> tiersFor(Long mappingId) {
        if (mappingId == null) return List.of();
        return tierMapper.selectList(new LambdaQueryWrapper<ModelPriceTier>()
                .eq(ModelPriceTier::getModelMappingId, mappingId)
                .orderByAsc(ModelPriceTier::getSortOrder)
                .orderByAsc(ModelPriceTier::getId));
    }

    private List<ModelPriceTier> normalize(Long mappingId, List<ModelPriceTier> source) {
        List<ModelPriceTier> result = new ArrayList<>();
        Integer previousMax = null;
        for (int index = 0; index < source.size(); index++) {
            ModelPriceTier item = source.get(index);
            if (item == null) {
                throw badRequest("价格挡位不能为空");
            }
            boolean last = index == source.size() - 1;
            Integer max = item.getMaxContextTokens();
            if (!last && (max == null || max < 1 || max > MAX_CONTEXT_TOKENS)) {
                throw badRequest("除最后一档外，每个挡位必须填写有效的上下文上限");
            }
            if (last && max != null) {
                throw badRequest("最后一个价格挡位必须为不限上下文上限");
            }
            if (previousMax != null && max != null && max <= previousMax) {
                throw badRequest("价格挡位的上下文上限必须严格递增");
            }
            ModelPriceTier tier = ModelPriceTier.builder()
                    .modelMappingId(mappingId)
                    .tierName(text(item.getTierName(), last ? "长上下文挡位" : "上下文挡位 " + (index + 1)))
                    .maxContextTokens(max)
                    .sortOrder(index)
                    .officialGroupName(text(item.getOfficialGroupName(), "官网价格"))
                    .officialInputPrice(amount(item.getOfficialInputPrice(), "官网输入价格"))
                    .officialOutputPrice(amount(item.getOfficialOutputPrice(), "官网输出价格"))
                    .officialCacheReadPrice(amount(item.getOfficialCacheReadPrice(), "官网缓存读取价格"))
                    .officialCacheWritePrice(amount(item.getOfficialCacheWritePrice(), "官网缓存写入价格"))
                    .officialCacheWrite1hPrice(amount(item.getOfficialCacheWrite1hPrice(), "官网1小时缓存写入价格"))
                    .officialImageInputPrice(amount(item.getOfficialImageInputPrice(), "官网图像输入价格"))
                    .officialImageOutputPrice(amount(item.getOfficialImageOutputPrice(), "官网图像输出价格"))
                    .officialPerRequestPrice(amount(item.getOfficialPerRequestPrice(), "官网按请求价格"))
                    .officialPriceUnit(unit(item.getOfficialPriceUnit(), "M"))
                    .officialPriceSuffix(suffix(item.getOfficialPriceSuffix(), item.getOfficialPriceUnit()))
                    .costGroupName(text(item.getCostGroupName(), "采购成本"))
                    .costInputPrice(amount(item.getCostInputPrice(), "输入成本"))
                    .costOutputPrice(amount(item.getCostOutputPrice(), "输出成本"))
                    .costCacheReadPrice(amount(item.getCostCacheReadPrice(), "缓存读取成本"))
                    .costCacheWritePrice(amount(item.getCostCacheWritePrice(), "缓存写入成本"))
                    .costCacheWrite1hPrice(amount(item.getCostCacheWrite1hPrice(), "1小时缓存写入成本"))
                    .costImageInputPrice(amount(item.getCostImageInputPrice(), "图像输入成本"))
                    .costImageOutputPrice(amount(item.getCostImageOutputPrice(), "图像输出成本"))
                    .costPerRequestPrice(amount(item.getCostPerRequestPrice(), "按请求成本"))
                    .costPriceUnit(unit(item.getCostPriceUnit(), "M"))
                    .costPriceSuffix(suffix(item.getCostPriceSuffix(), item.getCostPriceUnit()))
                    .saleGroupName(text(item.getSaleGroupName(), "本站售价"))
                    .saleInputPrice(amount(item.getSaleInputPrice(), "输入售价"))
                    .saleOutputPrice(amount(item.getSaleOutputPrice(), "输出售价"))
                    .saleCacheReadPrice(amount(item.getSaleCacheReadPrice(), "缓存读取售价"))
                    .saleCacheWritePrice(amount(item.getSaleCacheWritePrice(), "缓存写入售价"))
                    .saleCacheWrite1hPrice(amount(item.getSaleCacheWrite1hPrice(), "1小时缓存写入售价"))
                    .saleImageInputPrice(amount(item.getSaleImageInputPrice(), "图像输入售价"))
                    .saleImageOutputPrice(amount(item.getSaleImageOutputPrice(), "图像输出售价"))
                    .salePerRequestPrice(amount(item.getSalePerRequestPrice(), "按请求售价"))
                    .salePriceUnit(unit(item.getSalePriceUnit(), "M"))
                    .salePriceSuffix(suffix(item.getSalePriceSuffix(), item.getSalePriceUnit()))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            validateName(tier.getTierName(), "挡位名称");
            validateName(tier.getOfficialGroupName(), "官网价格组名称");
            validateName(tier.getOfficialPriceSuffix(), "官网价格后缀");
            validateName(tier.getCostGroupName(), "成本价格组名称");
            validateName(tier.getCostPriceSuffix(), "成本价格后缀");
            validateName(tier.getSaleGroupName(), "售价价格组名称");
            validateName(tier.getSalePriceSuffix(), "售价价格后缀");
            result.add(tier);
            previousMax = max;
        }
        return result;
    }

    private ModelPriceTier defaultTier(ModelMapping mapping) {
        return ModelPriceTier.builder()
                .modelMappingId(mapping.getId())
                .tierName("默认挡位")
                .maxContextTokens(null)
                .sortOrder(0)
                .officialGroupName("官网价格（待补充）")
                .officialInputPrice(BigDecimal.ZERO)
                .officialOutputPrice(BigDecimal.ZERO)
                .officialCacheReadPrice(BigDecimal.ZERO)
                .officialCacheWritePrice(BigDecimal.ZERO)
                .officialPriceUnit("M")
                .officialPriceSuffix(defaultSuffix("M"))
                .costGroupName("采购成本")
                .costInputPrice(value(mapping.getInputCostPerMillion(), mapping.getCostPerMillion()))
                .costOutputPrice(value(mapping.getOutputCostPerMillion(), mapping.getCostPerMillion()))
                .costCacheReadPrice(value(mapping.getCachedCostPerMillion(), BigDecimal.ZERO))
                .costCacheWritePrice(BigDecimal.ZERO)
                .costPriceUnit("M")
                .costPriceSuffix(defaultSuffix("M"))
                .saleGroupName("本站售价")
                .saleInputPrice(value(mapping.getInputPricePerMillion(), mapping.getPriceRatio()))
                .saleOutputPrice(value(mapping.getOutputPricePerMillion(), mapping.getPriceRatio()))
                .saleCacheReadPrice(value(mapping.getCachedPricePerMillion(), BigDecimal.ZERO))
                .saleCacheWritePrice(BigDecimal.ZERO)
                .salePriceUnit("M")
                .salePriceSuffix(defaultSuffix("M"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void mirrorPrimaryTier(ModelMapping mapping, ModelPriceTier tier) {
        // Keep legacy flat columns in their historical per-million unit. The
        // tier itself remains the source of truth and retains its configured
        // M/KB unit for billing and display.
        mapping.setInputPricePerMillion(toPerMillion(tier.getSaleInputPrice(), tier.getSalePriceUnit()));
        mapping.setOutputPricePerMillion(toPerMillion(tier.getSaleOutputPrice(), tier.getSalePriceUnit()));
        mapping.setCachedPricePerMillion(toPerMillion(tier.getSaleCacheReadPrice(), tier.getSalePriceUnit()));
        mapping.setInputCostPerMillion(toPerMillion(tier.getCostInputPrice(), tier.getCostPriceUnit()));
        mapping.setOutputCostPerMillion(toPerMillion(tier.getCostOutputPrice(), tier.getCostPriceUnit()));
        mapping.setCachedCostPerMillion(toPerMillion(tier.getCostCacheReadPrice(), tier.getCostPriceUnit()));
        mapping.setPriceRatio(tier.getInputCostMultiplier() == null
                ? BigDecimal.ZERO : tier.getInputCostMultiplier());
    }

    private BigDecimal toPerMillion(BigDecimal amount, String unit) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return "KB".equalsIgnoreCase(unit) ? value.multiply(BigDecimal.valueOf(1_000L)) : value;
    }

    private BigDecimal amount(BigDecimal value, String field) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0 || normalized.compareTo(MAX_PRICE) > 0) {
            throw badRequest(field + "超出允许范围");
        }
        return normalized;
    }

    private BigDecimal value(BigDecimal primary, BigDecimal fallback) {
        return primary != null ? primary : fallback != null ? fallback : BigDecimal.ZERO;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String unit(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) normalized = fallback;
        if (!PRICE_UNITS.contains(normalized)) {
            throw badRequest("价格单位只能是 M 或 KB");
        }
        return normalized;
    }

    private String suffix(String value, String unit) {
        return text(value, defaultSuffix(unit(unit, "M")));
    }

    private String defaultSuffix(String unit) {
        return "USD / 1" + ("KB".equalsIgnoreCase(unit) ? "KB" : "M") + " Token";
    }

    private void validateName(String value, String field) {
        if (value.length() > 120) throw badRequest(field + "不能超过 120 个字符");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
