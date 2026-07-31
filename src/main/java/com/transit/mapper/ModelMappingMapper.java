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

    String ROUTABLE = """
            mm.enabled = TRUE
            AND c.enabled = TRUE
            AND c.api_key IS NOT NULL AND c.api_key <> ''
            AND (
                UPPER(COALESCE(c.health_status, 'UNTESTED')) IN ('HEALTHY', 'DEGRADED')
                OR (
                    UPPER(COALESCE(c.health_status, 'UNTESTED')) = 'COOLDOWN'
                    AND (c.cooldown_until IS NULL OR c.cooldown_until < CURRENT_TIMESTAMP)
                )
            )
            """;

    String PUBLIC_COLUMNS = """
            mm.public_model_name AS publicName,
            CASE WHEN COUNT(DISTINCT LOWER(c.type)) > 1 THEN 'multi' ELSE MAX(LOWER(c.type)) END AS type,
            MIN(CASE WHEN mm.billing_enabled = TRUE THEN COALESCE(mm.input_price_per_million, mm.price_ratio, 0) ELSE 0 END) AS minInputPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE THEN COALESCE(mm.input_price_per_million, mm.price_ratio, 0) ELSE 0 END) AS maxInputPricePerMillion,
            MIN(CASE WHEN mm.billing_enabled = TRUE THEN COALESCE(mm.output_price_per_million, mm.price_ratio, 0) ELSE 0 END) AS minOutputPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE THEN COALESCE(mm.output_price_per_million, mm.price_ratio, 0) ELSE 0 END) AS maxOutputPricePerMillion,
            MIN(CASE WHEN mm.billing_enabled = TRUE THEN COALESCE(mm.cached_price_per_million, 0) ELSE 0 END) AS minCachedPricePerMillion,
            MAX(CASE WHEN mm.billing_enabled = TRUE THEN COALESCE(mm.cached_price_per_million, 0) ELSE 0 END) AS maxCachedPricePerMillion,
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
            WHERE
            """ + ROUTABLE + " GROUP BY mm.public_model_name ORDER BY mm.public_model_name")
    List<PublicModel> findPublicModels();

    @Select("""
            SELECT COUNT(1) FROM (
              SELECT mm.public_model_name
              FROM model_mappings mm
              JOIN channels c ON mm.channel_id = c.id
              WHERE
            """ + ROUTABLE + """
                AND (#{type} IS NULL OR LOWER(c.type) = LOWER(#{type}))
                AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
              GROUP BY mm.public_model_name
            ) t
            """)
    Long countPublicModels(@Param("query") String query, @Param("type") String type);

    @Select("SELECT " + PUBLIC_COLUMNS + """
            FROM model_mappings mm
            JOIN channels c ON mm.channel_id = c.id
            WHERE
            """ + ROUTABLE + """
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
            LEFT JOIN (
              SELECT model, COUNT(*) AS logs_count
              FROM logs WHERE status LIKE 'SUCCESS%' GROUP BY model
            ) lg ON lg.model = mm.public_model_name
            WHERE
            """ + ROUTABLE + """
              AND (#{type} IS NULL OR LOWER(c.type) = LOWER(#{type}))
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
            LEFT JOIN (
              SELECT model, MAX(created_at) AS last_used
              FROM logs WHERE status LIKE 'SUCCESS%' GROUP BY model
            ) lg ON lg.model = mm.public_model_name
            WHERE
            """ + ROUTABLE + """
              AND (#{type} IS NULL OR LOWER(c.type) = LOWER(#{type}))
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
            WHERE
            """ + ROUTABLE + """
              AND (#{type} IS NULL OR LOWER(c.type) = LOWER(#{type}))
              AND (#{query} IS NULL OR LOWER(mm.public_model_name) LIKE CONCAT('%', LOWER(#{query}), '%'))
            GROUP BY mm.public_model_name
            ORDER BY mm.public_model_name ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PublicModel> findPublicModelsPagedByName(@Param("query") String query,
                                                  @Param("type") String type,
                                                  @Param("limit") Integer limit,
                                                  @Param("offset") Integer offset);
}
