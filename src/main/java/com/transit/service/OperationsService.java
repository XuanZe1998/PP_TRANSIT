package com.transit.service;

import com.transit.dto.OperationsOverview;
import com.transit.dto.ProviderCatalogItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OperationsService {
    private final JdbcTemplate jdbcTemplate;

    public OperationsOverview overview() {
        Map<String, Object> requestStats = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total_requests,
                       COALESCE(SUM(CASE WHEN status LIKE 'SUCCESS%' THEN 1 ELSE 0 END), 0) AS success_requests,
                       COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_requests,
                       COALESCE(SUM(total_tokens), 0) AS total_consumed_tokens
                FROM logs
                """);
        List<String> activeProviders = jdbcTemplate.queryForList("""
                SELECT DISTINCT LOWER(type)
                FROM channels
                WHERE enabled = TRUE AND health_status = 'HEALTHY'
                  AND ((api_key IS NOT NULL AND api_key <> '') OR (managed=TRUE AND EXISTS (SELECT 1 FROM provider_credentials pc WHERE pc.channel_id=channels.id AND pc.enabled=TRUE AND pc.entitlement_status='ACTIVE' AND pc.cost_reliable=TRUE)))
                  AND (cooldown_until IS NULL OR cooldown_until < CURRENT_TIMESTAMP)
                ORDER BY LOWER(type)
                """, String.class);
        return OperationsOverview.builder()
                .totalChannels(count("SELECT COUNT(*) FROM channels"))
                .enabledChannels(count("""
                        SELECT COUNT(*) FROM channels
                        WHERE enabled = TRUE AND health_status = 'HEALTHY'
                          AND ((api_key IS NOT NULL AND api_key <> '') OR (managed=TRUE AND EXISTS (SELECT 1 FROM provider_credentials pc WHERE pc.channel_id=channels.id AND pc.enabled=TRUE AND pc.entitlement_status='ACTIVE' AND pc.cost_reliable=TRUE)))
                          AND (cooldown_until IS NULL OR cooldown_until < CURRENT_TIMESTAMP)
                        """))
                .totalMappings(count("SELECT COUNT(*) FROM model_mappings WHERE enabled = TRUE"))
                .totalTokens(count("SELECT COUNT(*) FROM tokens WHERE enabled = TRUE"))
                .totalUsers(count("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'"))
                .totalRequests(number(requestStats.get("total_requests")))
                .successRequests(number(requestStats.get("success_requests")))
                .failedRequests(number(requestStats.get("failed_requests")))
                .totalConsumedTokens(number(requestStats.get("total_consumed_tokens")))
                .activeProviders(activeProviders)
                .build();
    }

    /** Public catalog generated only from configured, currently routable
     * channels. It intentionally makes no claims about providers that are not
     * actually available in this installation. */
    public List<ProviderCatalogItem> providerCatalog() {
        List<Map<String, Object>> providers = jdbcTemplate.queryForList("""
                SELECT LOWER(c.type) AS provider_type,
                       COUNT(DISTINCT c.id) AS channel_count,
                       COUNT(DISTINCT mm.public_model_name) AS model_count
                FROM channels c
                JOIN model_mappings mm ON mm.channel_id = c.id AND mm.enabled = TRUE
                WHERE c.enabled = TRUE AND c.health_status = 'HEALTHY'
                  AND ((c.api_key IS NOT NULL AND c.api_key <> '') OR (c.managed=TRUE AND EXISTS (SELECT 1 FROM provider_credentials pc WHERE pc.channel_id=c.id AND pc.enabled=TRUE AND pc.entitlement_status='ACTIVE' AND pc.cost_reliable=TRUE)))
                  AND (c.cooldown_until IS NULL OR c.cooldown_until < CURRENT_TIMESTAMP)
                GROUP BY LOWER(c.type)
                ORDER BY LOWER(c.type)
                """);
        List<ProviderCatalogItem> result = new ArrayList<>();
        for (Map<String, Object> row : providers) {
            String type = String.valueOf(row.get("provider_type")).toLowerCase(Locale.ROOT);
            long channelCount = number(row.get("channel_count"));
            long modelCount = number(row.get("model_count"));
            List<String> models = jdbcTemplate.queryForList("""
                    SELECT DISTINCT mm.public_model_name
                    FROM model_mappings mm
                    JOIN channels c ON c.id = mm.channel_id
                    WHERE mm.enabled = TRUE AND c.enabled = TRUE AND c.health_status = 'HEALTHY'
                      AND LOWER(c.type) = ? AND ((c.api_key IS NOT NULL AND c.api_key <> '') OR (c.managed=TRUE AND EXISTS (SELECT 1 FROM provider_credentials pc WHERE pc.channel_id=c.id AND pc.enabled=TRUE AND pc.entitlement_status='ACTIVE' AND pc.cost_reliable=TRUE)))
                      AND (c.cooldown_until IS NULL OR c.cooldown_until < CURRENT_TIMESTAMP)
                    ORDER BY mm.public_model_name LIMIT 8
                    """, String.class, type);
            result.add(ProviderCatalogItem.builder()
                    .provider(displayName(type))
                    .providerType(type)
                    .headline("当前已配置且健康的 " + displayName(type) + " 上游")
                    .endpointStyle(endpointStyle(type))
                    .recommendedBaseUrl("/v1")
                    .modelFamilies(models)
                    .highlights(List.of(
                            channelCount + " 个健康渠道",
                            modelCount + " 个可路由模型",
                            "公开价格范围见模型目录"))
                    .build());
        }
        return result;
    }

    private String displayName(String type) {
        return switch (type) {
            case "openai", "openai-compatible" -> "OpenAI Compatible";
            case "anthropic" -> "Anthropic";
            case "gemini", "google" -> "Google Gemini";
            case "deepseek" -> "DeepSeek";
            case "xai" -> "xAI";
            default -> type;
        };
    }

    private String endpointStyle(String type) {
        return switch (type) {
            case "anthropic" -> "Anthropic Messages";
            case "gemini", "google" -> "Gemini generateContent";
            default -> "OpenAI Chat Completions";
        };
    }

    private long count(String sql) {
        Number value = jdbcTemplate.queryForObject(sql, Number.class);
        return value == null ? 0 : value.longValue();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }
}
