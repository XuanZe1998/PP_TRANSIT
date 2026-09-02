package com.transit.service;

import com.transit.dto.AiApiBankPriceDimensions;
import com.transit.dto.AiApiBankPriceTierView;
import com.transit.dto.AiApiBankProviderGroupView;
import com.transit.dto.AiApiBankUnitPriceVariant;
import com.transit.dto.PublicModel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AiApiBankPublicCatalogService {
    private final JdbcTemplate jdbc;

    public void enrich(List<PublicModel> models) {
        if (models == null || models.isEmpty()) return;
        List<String> names = models.stream().map(PublicModel::getPublicName).filter(Objects::nonNull)
                .filter(name -> name.startsWith(AiApiBankCatalogService.SOURCE_CODE + "/")).distinct().toList();
        if (names.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
        List<Map<String, Object>> offers = jdbc.queryForList("""
                SELECT o.public_model_name,o.upstream_model_name,o.model_mapping_id,g.external_group_id,g.group_name,
                g.group_slug,g.description,g.platform,g.subscription_type,g.base_rate_multiplier,g.group_rate_multiplier,
                g.user_rate_multiplier,g.resolved_rate_multiplier,g.peak_rate_enabled,g.peak_start,g.peak_end,
                g.peak_rate_multiplier,g.billing_timezone,g.exclusive_group,g.image_rate_independent,
                g.image_rate_multiplier,g.long_context_pricing_enabled
                FROM aiapibank_model_offers o JOIN aiapibank_provider_groups g ON g.id=o.provider_group_id
                WHERE o.enabled=TRUE AND o.public_model_name IN (""" + placeholders + ")", names.toArray());
        Map<String, PublicModel> byName = new HashMap<>();
        models.forEach(model -> byName.put(model.getPublicName(), model));
        for (Map<String, Object> row : offers) {
            String publicName = text(row, "public_model_name");
            PublicModel model = byName.get(publicName);
            if (model == null) continue;
            String upstream = text(row, "upstream_model_name");
            model.setUpstreamModelName(upstream);
            model.setDisplayName(upstream + " · " + text(row, "group_name"));
            model.setProviderGroup(AiApiBankProviderGroupView.builder().channel(AiApiBankCatalogService.SOURCE_NAME)
                    .externalId(longValue(row, "external_group_id")).name(text(row, "group_name"))
                    .slug(text(row, "group_slug")).description(text(row, "description"))
                    .platform(text(row, "platform")).subscriptionType(text(row, "subscription_type"))
                    .baseRateMultiplier(decimal(row, "base_rate_multiplier"))
                    .groupRateMultiplier(decimal(row, "group_rate_multiplier"))
                    .userRateMultiplier(decimal(row, "user_rate_multiplier"))
                    .resolvedRateMultiplier(decimal(row, "resolved_rate_multiplier"))
                    .peakRateEnabled(bool(row, "peak_rate_enabled")).peakStart(text(row, "peak_start"))
                    .peakEnd(text(row, "peak_end")).peakRateMultiplier(decimal(row, "peak_rate_multiplier"))
                    .timezone(text(row, "billing_timezone")).exclusive(bool(row, "exclusive_group"))
                    .imageRateIndependent(bool(row, "image_rate_independent"))
                    .imageRateMultiplier(decimal(row, "image_rate_multiplier"))
                    .longContextPricingEnabled(bool(row, "long_context_pricing_enabled")).build());
            long mappingId = longValue(row, "model_mapping_id");
            model.setPriceTiers(tiers(mappingId));
            model.setUnitPriceVariants(variants(mappingId));
        }
    }

    private List<AiApiBankPriceTierView> tiers(long mappingId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT tier_name,max_context_tokens,official_input_price,official_output_price,
                official_cache_read_price,official_cache_write_price,cost_input_price,cost_output_price,
                official_cache_write_1h_price,official_image_input_price,official_image_output_price,official_per_request_price,
                cost_cache_read_price,cost_cache_write_price,cost_cache_write_1h_price,cost_image_input_price,
                cost_image_output_price,cost_per_request_price,sale_input_price,sale_output_price,
                sale_cache_read_price,sale_cache_write_price,sale_cache_write_1h_price,sale_image_input_price,
                sale_image_output_price,sale_per_request_price FROM model_price_tiers
                WHERE model_mapping_id=? ORDER BY sort_order,id
                """, mappingId);
        List<AiApiBankPriceTierView> result = new ArrayList<>();
        Integer minimum = 0;
        for (Map<String,Object> row : rows) {
            Integer maximum = value(row, "max_context_tokens") instanceof Number n ? n.intValue() : null;
            result.add(AiApiBankPriceTierView.builder().label(text(row, "tier_name"))
                    .minTokens(minimum).maxTokens(maximum)
                    .official(dimensions(row, "official_")).sourcePrice(dimensions(row, "cost_"))
                    .sale(dimensions(row, "sale_")).build());
            minimum = maximum == null ? null : maximum + 1;
        }
        return result;
    }

    private AiApiBankPriceDimensions dimensions(Map<String,Object> row, String prefix) {
        return AiApiBankPriceDimensions.builder().input(decimal(row, prefix + "input_price"))
                .output(decimal(row, prefix + "output_price"))
                .cacheRead(decimal(row, prefix + "cache_read_price"))
                .cacheWrite(decimal(row, prefix + "cache_write_price"))
                .cacheWrite1h(decimal(row, prefix + "cache_write_1h_price"))
                .imageInput(decimal(row, prefix + "image_input_price"))
                .imageOutput(decimal(row, prefix + "image_output_price"))
                .perRequest(decimal(row, prefix + "per_request_price"))
                .unit("USD / 1M Token").build();
    }

    private List<AiApiBankUnitPriceVariant> variants(long mappingId) {
        return jdbc.queryForList("""
                SELECT v.resolution_tier,v.max_edge_pixels,v.source_unit_price,v.sale_unit_price,v.unit
                FROM aiapibank_image_price_variants v JOIN aiapibank_model_offers o ON o.id=v.model_offer_id
                WHERE o.model_mapping_id=? ORDER BY v.max_edge_pixels
                """, mappingId).stream().map(row -> AiApiBankUnitPriceVariant.builder()
                .resolution(text(row, "resolution_tier")).maxEdgePixels((int) longValue(row, "max_edge_pixels"))
                .sourcePrice(decimal(row, "source_unit_price")).sale(decimal(row, "sale_unit_price"))
                .unit(text(row, "unit")).build()).toList();
    }

    private Object value(Map<String,Object> row,String key){if(row.containsKey(key))return row.get(key);for(var entry:row.entrySet())if(entry.getKey().equalsIgnoreCase(key))return entry.getValue();return null;}
    private String text(Map<String,Object> row,String key){Object value=value(row,key);return value==null?"":value.toString().trim();}
    private long longValue(Map<String,Object> row,String key){Object value=value(row,key);return value instanceof Number n?n.longValue():Long.parseLong(value.toString());}
    private BigDecimal decimal(Map<String,Object> row,String key){Object value=value(row,key);return value instanceof BigDecimal b?b:value instanceof Number n?new BigDecimal(n.toString()):value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
    private boolean bool(Map<String,Object> row,String key){Object value=value(row,key);return value instanceof Boolean b?b:value instanceof Number n?n.intValue()!=0:Boolean.parseBoolean(String.valueOf(value));}
}
