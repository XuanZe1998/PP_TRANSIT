package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiApiBankPricingService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
    private final JdbcTemplate jdbc;

    public ModelPriceTier applyActivePeak(ModelMapping mapping, ModelPriceTier tier) {
        BigDecimal multiplier = activePeakMultiplier(mapping == null ? null : mapping.getId());
        if (tier == null || multiplier.compareTo(BigDecimal.ONE) == 0) return tier;
        ModelPriceTier copy = ModelPriceTier.builder()
                .id(tier.getId()).modelMappingId(tier.getModelMappingId()).tierName(tier.getTierName())
                .maxContextTokens(tier.getMaxContextTokens()).sortOrder(tier.getSortOrder())
                .officialGroupName(tier.getOfficialGroupName()).officialInputPrice(tier.getOfficialInputPrice())
                .officialOutputPrice(tier.getOfficialOutputPrice()).officialCacheReadPrice(tier.getOfficialCacheReadPrice())
                .officialCacheWritePrice(tier.getOfficialCacheWritePrice())
                .officialCacheWrite1hPrice(tier.getOfficialCacheWrite1hPrice())
                .officialImageInputPrice(tier.getOfficialImageInputPrice()).officialImageOutputPrice(tier.getOfficialImageOutputPrice())
                .officialPerRequestPrice(tier.getOfficialPerRequestPrice()).officialPriceUnit(tier.getOfficialPriceUnit())
                .officialPriceSuffix(tier.getOfficialPriceSuffix()).costGroupName(tier.getCostGroupName() + " · 峰时")
                .costInputPrice(scale(tier.getCostInputPrice(), multiplier)).costOutputPrice(scale(tier.getCostOutputPrice(), multiplier))
                .costCacheReadPrice(scale(tier.getCostCacheReadPrice(), multiplier)).costCacheWritePrice(scale(tier.getCostCacheWritePrice(), multiplier))
                .costCacheWrite1hPrice(scale(tier.getCostCacheWrite1hPrice(), multiplier))
                // Peak pricing applies to token dimensions only. Image and
                // per-request units remain governed by their own variants.
                .costImageInputPrice(tier.getCostImageInputPrice()).costImageOutputPrice(tier.getCostImageOutputPrice())
                .costPerRequestPrice(tier.getCostPerRequestPrice())
                .costPriceUnit(tier.getCostPriceUnit()).costPriceSuffix(tier.getCostPriceSuffix())
                .saleGroupName(tier.getSaleGroupName() + " · 峰时")
                .saleInputPrice(scale(tier.getSaleInputPrice(), multiplier)).saleOutputPrice(scale(tier.getSaleOutputPrice(), multiplier))
                .saleCacheReadPrice(scale(tier.getSaleCacheReadPrice(), multiplier)).saleCacheWritePrice(scale(tier.getSaleCacheWritePrice(), multiplier))
                .saleCacheWrite1hPrice(scale(tier.getSaleCacheWrite1hPrice(), multiplier))
                .saleImageInputPrice(tier.getSaleImageInputPrice()).saleImageOutputPrice(tier.getSaleImageOutputPrice())
                .salePerRequestPrice(tier.getSalePerRequestPrice())
                .salePriceUnit(tier.getSalePriceUnit()).salePriceSuffix(tier.getSalePriceSuffix())
                .createdAt(tier.getCreatedAt()).updatedAt(tier.getUpdatedAt()).build();
        return copy;
    }

    public BigDecimal activePeakMultiplier(Long mappingId) {
        if (mappingId == null) return BigDecimal.ONE;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT g.peak_rate_enabled,g.peak_start,g.peak_end,g.peak_rate_multiplier,g.billing_timezone
                FROM aiapibank_model_offers o JOIN aiapibank_provider_groups g ON g.id=o.provider_group_id
                WHERE o.model_mapping_id=? AND o.enabled=TRUE
                """, mappingId);
        if (rows.isEmpty()) return BigDecimal.ONE;
        Map<String, Object> row = rows.get(0);
        if (!bool(row, "peak_rate_enabled")) return BigDecimal.ONE;
        String startText = text(row, "peak_start");
        String endText = text(row, "peak_end");
        if (startText.isBlank() || endText.isBlank()) return BigDecimal.ONE;
        try {
            ZoneId zone = ZoneId.of(defaultText(text(row, "billing_timezone"), "Asia/Shanghai"));
            LocalTime now = ZonedDateTime.now(zone).toLocalTime();
            LocalTime start = LocalTime.parse(startText, TIME);
            LocalTime end = LocalTime.parse(endText, TIME);
            boolean active = start.equals(end) || (start.isBefore(end)
                    ? !now.isBefore(start) && now.isBefore(end)
                    : !now.isBefore(start) || now.isBefore(end));
            return active ? positive(decimal(row, "peak_rate_multiplier")) : BigDecimal.ONE;
        } catch (DateTimeParseException | java.time.zone.ZoneRulesException invalid) {
            return BigDecimal.ONE;
        }
    }

    public UnitQuote imageQuote(ModelMapping mapping, JsonNode request) {
        if (mapping == null || mapping.getId() == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT v.resolution_tier,v.max_edge_pixels,v.source_unit_price,v.sale_unit_price
                FROM aiapibank_image_price_variants v
                JOIN aiapibank_model_offers o ON o.id=v.model_offer_id
                WHERE o.model_mapping_id=? AND o.enabled=TRUE ORDER BY v.max_edge_pixels
                """, mapping.getId());
        if (rows.isEmpty()) return null;
        int requested = requestedMaxEdge(request == null ? "" : request.path("size").asText(""));
        for (Map<String, Object> row : rows) {
            int limit = ((Number) value(row, "max_edge_pixels")).intValue();
            if (requested <= limit) return new UnitQuote(text(row, "resolution_tier"), limit,
                    decimal(row, "source_unit_price"), decimal(row, "sale_unit_price"));
        }
        int maximum = ((Number) value(rows.get(rows.size() - 1), "max_edge_pixels")).intValue();
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "该 AiAPIBank 图片分组最高仅支持 " + resolution(maximum) + " 分辨率");
    }

    int requestedMaxEdge(String size) {
        String normalized = size == null ? "" : size.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "auto".equals(normalized) || "1k".equals(normalized)) return 1024;
        if (normalized.contains("4k")) return 4096;
        if (normalized.contains("2k")) return 2048;
        int largest = 0;
        for (String part : normalized.split("[^0-9]+")) {
            if (!part.isBlank()) {
                try { largest = Math.max(largest, Integer.parseInt(part)); }
                catch (NumberFormatException ignored) { /* validated below */ }
            }
        }
        if (largest <= 0 || largest > 16384) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的图片分辨率 size");
        return largest;
    }

    private String resolution(int pixels) { return pixels <= 1024 ? "1K" : pixels <= 2048 ? "2K" : "4K"; }
    private BigDecimal scale(BigDecimal value, BigDecimal multiplier) { return value == null ? BigDecimal.ZERO : value.multiply(multiplier); }
    private BigDecimal positive(BigDecimal value) { return value == null || value.signum() <= 0 ? BigDecimal.ONE : value; }
    private boolean bool(Map<String,Object> row, String key) { Object value=value(row,key); return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue()!=0 : Boolean.parseBoolean(String.valueOf(value)); }
    private BigDecimal decimal(Map<String,Object> row, String key) { Object value=value(row,key); return value instanceof BigDecimal b ? b : value instanceof Number n ? new BigDecimal(n.toString()) : value==null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private Object value(Map<String,Object> row,String key){if(row.containsKey(key))return row.get(key);for(var entry:row.entrySet())if(entry.getKey().equalsIgnoreCase(key))return entry.getValue();return null;}
    private String text(Map<String,Object> row,String key){Object value=value(row,key);return value==null?"":value.toString().trim();}
    private String defaultText(String value,String fallback){return value==null||value.isBlank()?fallback:value;}

    public record UnitQuote(String resolution, int maxEdgePixels, BigDecimal sourcePrice, BigDecimal salePrice) {}
}
