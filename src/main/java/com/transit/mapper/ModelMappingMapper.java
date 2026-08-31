package com.transit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.transit.dto.PublicModel;
import com.transit.model.ModelMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ModelMappingMapper extends BaseMapper<ModelMapping> {

    String UPSTREAM_AVAILABLE = """
            mm.enabled = TRUE
            AND c.enabled = TRUE
            AND (
              (c.api_key IS NOT NULL AND c.api_key <> '')
              OR (c.managed = TRUE AND EXISTS (
                SELECT 1 FROM provider_credentials pc
                WHERE pc.channel_id=c.id AND pc.enabled=TRUE AND pc.health_status IN ('HEALTHY','DEGRADED')
                  AND pc.auth_type='OAUTH' AND pc.entitlement_status='ACTIVE' AND pc.cost_reliable=TRUE
              ))
            )
            AND (
                UPPER(COALESCE(c.health_status, 'UNTESTED')) IN ('HEALTHY', 'DEGRADED')
                OR (
                    UPPER(COALESCE(c.health_status, 'UNTESTED')) = 'COOLDOWN'
                    AND (c.cooldown_until IS NULL OR c.cooldown_until < CURRENT_TIMESTAMP)
                )
            )
            """;

    String ROUTABLE = """
            (
              UPPER(COALESCE(mm.billing_mode, 'PAID')) = 'FREE_PREVIEW'
              OR (
                UPPER(COALESCE(mm.billing_mode, 'PAID')) = 'PAID'
                AND mm.billing_enabled = TRUE
                AND (
                  (UPPER(COALESCE(mm.pricing_unit, 'TOKEN')) <> 'TOKEN' AND COALESCE(mm.sale_unit_price, 0) > 0)
                  OR (UPPER(COALESCE(mm.pricing_unit, 'TOKEN')) = 'TOKEN'
                      AND COALESCE(mm.input_price_per_million, 0) > 0
                      AND (LOWER(COALESCE(mm.capability, 'text')) IN ('embedding', 'rerank')
                           OR COALESCE(mm.output_price_per_million, 0) > 0))
                )
              )
            )
            AND
            """ + UPSTREAM_AVAILABLE;

    String PUBLIC_COLUMNS = """
            mm.public_model_name AS publicName,
            COALESCE(MAX(mds.display_priority), 0) AS displayPriority,
            CASE WHEN COUNT(DISTINCT LOWER(c.type)) > 1 THEN 'multi' ELSE MAX(LOWER(c.type)) END AS type,
            CASE WHEN COUNT(DISTINCT LOWER(c.source_code)) > 1 THEN 'multi' ELSE MAX(LOWER(c.source_code)) END AS source,
            CASE WHEN COUNT(DISTINCT c.source_name) > 1 THEN '多个来源' ELSE MAX(c.source_name) END AS sourceName,
            GROUP_CONCAT(DISTINCT LOWER(COALESCE(c.source_code, c.type)) ORDER BY LOWER(COALESCE(c.source_code, c.type)) SEPARATOR ',') AS sources,
            CASE WHEN COUNT(DISTINCT LOWER(mm.vendor)) > 1 THEN 'multi' ELSE MAX(LOWER(mm.vendor)) END AS vendor,
            CASE WHEN COUNT(DISTINCT LOWER(mm.capability)) > 1 THEN 'multi' ELSE MAX(LOWER(mm.capability)) END AS capability,
            MAX(mm.input_modalities) AS inputModalities,
            MAX(mm.output_modalities) AS outputModalities,
            MAX(mm.protocols) AS protocols,
            CASE WHEN COUNT(DISTINCT UPPER(mm.pricing_unit)) > 1 THEN 'MIXED' ELSE MAX(UPPER(mm.pricing_unit)) END AS pricingUnit,
            CASE WHEN SUM(CASE WHEN UPPER(COALESCE(mm.billing_mode, 'PAID'))='FREE_PREVIEW' THEN 1 ELSE 0 END) > 0
                 THEN 'FREE_PREVIEW' ELSE MAX(UPPER(COALESCE(mm.billing_mode, 'PAID'))) END AS billingMode,
            CASE WHEN SUM(CASE WHEN UPPER(COALESCE(mm.pricing_status, 'PENDING'))='FREE_PREVIEW' THEN 1 ELSE 0 END) > 0
                 THEN 'FREE_PREVIEW' ELSE MAX(UPPER(COALESCE(mm.pricing_status, 'PENDING'))) END AS pricingStatus,
            MAX(mm.pricing_message) AS pricingMessage,
            MAX(mm.pricing_source_url) AS pricingSourceUrl,
            MAX(mm.pricing_verified_at) AS pricingVerifiedAt,
            MAX(COALESCE(mm.sale_unit_price, 0)) AS saleUnitPrice,
            TRUE AS available,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_input_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, mm.input_price_per_million, 0) ELSE 0 END) AS minInputPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_input_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, mm.input_price_per_million, 0) ELSE 0 END) AS maxInputPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_output_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, mm.output_price_per_million, 0) ELSE 0 END) AS minOutputPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_output_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, mm.output_price_per_million, 0) ELSE 0 END) AS maxOutputPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_cache_read_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, mm.cached_price_per_million, 0) ELSE 0 END) AS minCachedPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_cache_read_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, mm.cached_price_per_million, 0) ELSE 0 END) AS maxCachedPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_cache_read_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, mm.cached_price_per_million, 0) ELSE 0 END) AS minCacheReadPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_cache_read_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, mm.cached_price_per_million, 0) ELSE 0 END) AS maxCacheReadPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_cache_write_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, 0) ELSE 0 END) AS minCacheWritePricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE AND COALESCE(mm.input_price_per_million, 0) > 0 THEN COALESCE(pt.sale_cache_write_price * CASE WHEN UPPER(COALESCE(pt.sale_price_unit, 'M')) = 'KB' THEN 1000 ELSE 1 END, 0) ELSE 0 END) AS maxCacheWritePricePerMillion,
            CASE WHEN SUM(CASE WHEN UPPER(COALESCE(mm.billing_mode, 'PAID')) = 'FREE_PREVIEW'
                              OR (UPPER(COALESCE(mm.billing_mode, 'PAID')) = 'PAID' AND mm.billing_enabled = TRUE AND
                                  ((UPPER(COALESCE(mm.pricing_unit, 'TOKEN')) <> 'TOKEN' AND COALESCE(mm.sale_unit_price, 0) > 0)
                                   OR (UPPER(COALESCE(mm.pricing_unit, 'TOKEN')) = 'TOKEN'
                                       AND COALESCE(mm.input_price_per_million, 0) > 0
                                       AND (LOWER(COALESCE(mm.capability, 'text')) IN ('embedding', 'rerank')
                                            OR COALESCE(mm.output_price_per_million, 0) > 0))))
                         THEN 1 ELSE 0 END) > 0 THEN TRUE ELSE FALSE END AS billingConfigured,
            NULL AS minInputCostMultiplier,
            NULL AS maxInputCostMultiplier,
            NULL AS minOutputCostMultiplier,
            NULL AS maxOutputCostMultiplier,
            NULL AS minCacheReadCostMultiplier,
            NULL AS maxCacheReadCostMultiplier,
            NULL AS minCacheWriteCostMultiplier,
            NULL AS maxCacheWriteCostMultiplier,
            COUNT(DISTINCT c.id) AS routeCount,
            COUNT(DISTINCT LOWER(c.type)) AS providerCount
            """;

    @Select("""
            SELECT mm.*
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            WHERE mm.public_model_name = #{publicModelName} AND
            """ + ROUTABLE + " ORDER BY mm.priority DESC")
    List<ModelMapping> findByPublicModelNameWithChannel(@Param("publicModelName") String publicModelName);

    @Select("SELECT " + PUBLIC_COLUMNS + """
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            LEFT JOIN model_price_tiers pt ON pt.model_mapping_id = mm.id
            LEFT JOIN model_market_display_settings mds ON mds.public_model_name = mm.public_model_name
            WHERE
            """ + UPSTREAM_AVAILABLE + " GROUP BY mm.public_model_name ORDER BY mm.public_model_name")
    List<PublicModel> findPublicModels();

    @Select("""
            SELECT COUNT(1) FROM (
              SELECT mm.public_model_name
              FROM model_mappings mm
              JOIN channels c ON mm.channel_id = c.id
              WHERE
            """ + UPSTREAM_AVAILABLE + """
                AND (#{type} IS NULL
                     OR LOWER(c.type) = LOWER(#{type})
                     OR LOWER(c.source_code) = LOWER(#{type})
                     OR (LOWER(#{type}) = 'other'
                         AND COALESCE(LOWER(c.source_code), LOWER(c.type), 'other') NOT IN ('haoee', 'nvidia')))
                AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
              GROUP BY mm.public_model_name
            ) t
            """)
    Long countPublicModels(@Param("query") String query, @Param("type") String type);

    @Select("SELECT " + PUBLIC_COLUMNS + """
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            LEFT JOIN model_price_tiers pt ON pt.model_mapping_id = mm.id
            LEFT JOIN model_market_display_settings mds ON mds.public_model_name = mm.public_model_name
            WHERE
            """ + UPSTREAM_AVAILABLE + """
              AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
            GROUP BY mm.public_model_name
            ORDER BY mm.public_model_name ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PublicModel> findPublicModelsPaged(@Param("query") String query,
                                            @Param("limit") Integer limit,
                                            @Param("offset") Integer offset);

    @Select("SELECT " + PUBLIC_COLUMNS + """
            , COALESCE(MAX(lg.logs_count), 0) AS hot
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            LEFT JOIN model_price_tiers pt ON pt.model_mapping_id = mm.id
            LEFT JOIN model_market_display_settings mds ON mds.public_model_name = mm.public_model_name
            LEFT JOIN (
              SELECT model, COUNT(*) AS logs_count
              FROM logs WHERE status LIKE 'SUCCESS%' GROUP BY model
            ) lg ON lg.model = mm.public_model_name
            WHERE
            """ + UPSTREAM_AVAILABLE + """
              AND (#{type} IS NULL
                   OR LOWER(c.type) = LOWER(#{type})
                   OR LOWER(c.source_code) = LOWER(#{type})
                   OR (LOWER(#{type}) = 'other'
                       AND COALESCE(LOWER(c.source_code), LOWER(c.type), 'other') NOT IN ('haoee', 'nvidia')))
              AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
            GROUP BY mm.public_model_name
            ORDER BY hot DESC, mm.public_model_name ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PublicModel> findPublicModelsPagedHot(@Param("query") String query,
                                               @Param("type") String type,
                                               @Param("limit") Integer limit,
                                               @Param("offset") Integer offset);

    @Select("SELECT " + PUBLIC_COLUMNS + """
            , MAX(lg.last_used) AS lastUsed
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            LEFT JOIN model_price_tiers pt ON pt.model_mapping_id = mm.id
            LEFT JOIN model_market_display_settings mds ON mds.public_model_name = mm.public_model_name
            LEFT JOIN (
              SELECT model, MAX(created_at) AS last_used
              FROM logs WHERE status LIKE 'SUCCESS%' GROUP BY model
            ) lg ON lg.model = mm.public_model_name
            WHERE
            """ + UPSTREAM_AVAILABLE + """
              AND (#{type} IS NULL
                   OR LOWER(c.type) = LOWER(#{type})
                   OR LOWER(c.source_code) = LOWER(#{type})
                   OR (LOWER(#{type}) = 'other'
                       AND COALESCE(LOWER(c.source_code), LOWER(c.type), 'other') NOT IN ('haoee', 'nvidia')))
              AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
            GROUP BY mm.public_model_name
            ORDER BY (lastUsed IS NULL), lastUsed DESC, mm.public_model_name ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PublicModel> findPublicModelsPagedRecent(@Param("query") String query,
                                                  @Param("type") String type,
                                                  @Param("limit") Integer limit,
                                                  @Param("offset") Integer offset);

    @Select("SELECT " + PUBLIC_COLUMNS + """
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            LEFT JOIN model_price_tiers pt ON pt.model_mapping_id = mm.id
            LEFT JOIN model_market_display_settings mds ON mds.public_model_name = mm.public_model_name
            WHERE
            """ + UPSTREAM_AVAILABLE + """
              AND (#{type} IS NULL
                   OR LOWER(c.type) = LOWER(#{type})
                   OR LOWER(c.source_code) = LOWER(#{type})
                   OR (LOWER(#{type}) = 'other'
                       AND COALESCE(LOWER(c.source_code), LOWER(c.type), 'other') NOT IN ('haoee', 'nvidia')))
              AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
            GROUP BY mm.public_model_name
            ORDER BY mm.public_model_name ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PublicModel> findPublicModelsPagedByName(@Param("query") String query,
                                                  @Param("type") String type,
                                                  @Param("limit") Integer limit,
                                                  @Param("offset") Integer offset);

    @Select("SELECT " + PUBLIC_COLUMNS + """
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            LEFT JOIN model_price_tiers pt ON pt.model_mapping_id = mm.id
            LEFT JOIN model_market_display_settings mds ON mds.public_model_name = mm.public_model_name
            WHERE
            """ + UPSTREAM_AVAILABLE + """
              AND (#{type} IS NULL
                   OR LOWER(c.type) = LOWER(#{type})
                   OR LOWER(c.source_code) = LOWER(#{type})
                   OR (LOWER(#{type}) = 'other'
                       AND COALESCE(LOWER(c.source_code), LOWER(c.type), 'other') NOT IN ('haoee', 'nvidia')))
              AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
            GROUP BY mm.public_model_name
            ORDER BY COALESCE(MAX(mds.display_priority), 0) DESC,
                     LOWER(mm.public_model_name) ASC, mm.public_model_name ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PublicModel> findPublicModelsPagedByPriority(@Param("query") String query,
                                                      @Param("type") String type,
                                                      @Param("limit") Integer limit,
                                                      @Param("offset") Integer offset);

    @Select("SELECT " + PUBLIC_COLUMNS + """
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            LEFT JOIN model_price_tiers pt ON pt.model_mapping_id = mm.id
            LEFT JOIN model_market_display_settings mds ON mds.public_model_name = mm.public_model_name
            WHERE
            """ + UPSTREAM_AVAILABLE + """
              AND (#{type} IS NULL
                   OR LOWER(c.type) = LOWER(#{type})
                   OR LOWER(c.source_code) = LOWER(#{type})
                   OR (LOWER(#{type}) = 'other'
                       AND COALESCE(LOWER(c.source_code), LOWER(c.type), 'other') NOT IN ('haoee', 'nvidia')))
              AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
            GROUP BY mm.public_model_name
            ORDER BY mm.public_model_name DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PublicModel> findPublicModelsPagedByNameDesc(@Param("query") String query,
                                                      @Param("type") String type,
                                                      @Param("limit") Integer limit,
                                                      @Param("offset") Integer offset);
}
