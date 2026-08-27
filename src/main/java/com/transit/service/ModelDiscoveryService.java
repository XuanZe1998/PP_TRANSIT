package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ProviderModel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/** Authenticated upstream model discovery and conservative local synchronization. */
@Service
@RequiredArgsConstructor
public class ModelDiscoveryService {

    private static final int MAX_DISCOVERED_MODELS = 500;
    private static final String MODEL_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}";

    private final ChannelMapper channelMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final ChannelSecretService channelSecretService;
    private final ChannelUrlPolicy channelUrlPolicy;
    private final WebClient webClient;
    private final JdbcTemplate jdbcTemplate;
    private final ProviderModelCatalogService providerModelCatalogService;

    public List<Map<String, Object>> providerCatalog() {
        return List.of(
                provider("openai", "OpenAI", "https://api.openai.com", "OPENAI", true),
                provider("openai-compatible", "OpenAI Compatible", "", "OPENAI", true),
                provider("haoee", "好易智算 MaaS", "https://maas.haoee.com", "OPENAI", true),
                provider("anthropic", "Anthropic Claude", "https://api.anthropic.com", "ANTHROPIC", true),
                provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com", "GEMINI", true),
                provider("deepseek", "DeepSeek", "https://api.deepseek.com", "OPENAI", true),
                provider("xai", "xAI", "https://api.x.ai", "OPENAI", true),
                provider("openrouter", "OpenRouter", "https://openrouter.ai/api", "OPENAI", true),
                provider("qwen", "Qwen Compatible", "https://dashscope.aliyuncs.com/compatible-mode", "OPENAI", true),
                provider("kimi", "Moonshot Kimi", "https://api.moonshot.cn", "OPENAI", true),
                provider("glm", "Zhipu GLM Compatible", "https://open.bigmodel.cn/api/paas", "OPENAI", true),
                provider("mistral", "Mistral", "https://api.mistral.ai", "OPENAI", true),
                provider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "OPENAI", true)
        );
    }

    public Map<String, Object> discover(Long channelId) {
        Channel channel = requireChannel(channelId);
        channelSecretService.reveal(channel);
        if (channel.getApiKey() == null || channel.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel API key is required");
        }
        channelUrlPolicy.validate(channel.getBaseUrl());
        long started = System.currentTimeMillis();
        List<String> models = fetchModels(channel);
        List<ModelMapping> existing = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channelId));
        Set<String> mapped = existing.stream().map(ModelMapping::getChannelModelName)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        List<String> missing = models.stream().filter(model -> !mapped.contains(model)).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channelId", channelId);
        result.put("channelName", channel.getName());
        result.put("provider", normalizedType(channel));
        result.put("models", models);
        result.put("missingModels", missing);
        result.put("existingCount", models.size() - missing.size());
        result.put("missingCount", missing.size());
        result.put("latencyMs", System.currentTimeMillis() - started);
        return result;
    }

    @Transactional
    public Map<String, Object> synchronize(Long channelId, boolean activateNew) {
        Map<String, Object> preview = discover(channelId);
        @SuppressWarnings("unchecked")
        List<String> models = (List<String>) preview.get("models");
        int created = 0;
        int existing = 0;
        for (String model : models) {
            boolean mapped = !modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                    .eq(ModelMapping::getChannelId, channelId)
                    .eq(ModelMapping::getChannelModelName, model)
                    .last("LIMIT 1")).isEmpty();
            if (mapped) {
                existing++;
                continue;
            }
            ModelMapping mapping = ModelMapping.builder()
                    .publicModelName(model)
                    .channelModelName(model)
                    .channelId(channelId)
                    .priority(10)
                    .enabled(activateNew)
                    .priceRatio(BigDecimal.ONE)
                    .costPerMillion(BigDecimal.ZERO)
                    .inputPricePerMillion(BigDecimal.ZERO)
                    .outputPricePerMillion(BigDecimal.ZERO)
                    .cachedPricePerMillion(BigDecimal.ZERO)
                    .inputCostPerMillion(BigDecimal.ZERO)
                    .outputCostPerMillion(BigDecimal.ZERO)
                    .cachedCostPerMillion(BigDecimal.ZERO)
                    .billingEnabled(false)
                    .trafficPercent(100)
                    .capabilityTags("discovered,pricing-required")
                    .build();
            modelMappingMapper.insert(mapping);
            created++;
        }

        String compactModels = compactModelList(models, 2000);
        jdbcTemplate.update("""
                UPDATE channels
                   SET models = ?, health_status = 'HEALTHY', cooldown_until = NULL,
                       consecutive_failures = 0, last_error = NULL, last_tested_at = ?
                 WHERE id = ?
                """, compactModels, LocalDateTime.now(), channelId);

        Map<String, Object> result = new LinkedHashMap<>(preview);
        result.put("created", created);
        result.put("existing", existing);
        result.put("activated", activateNew ? created : 0);
        result.put("pricingRequired", created);
        result.put("message", activateNew
                ? "Models synchronized and activated with billing disabled; configure pricing before commercial use"
                : "Models synchronized as disabled mappings; configure pricing and enable them explicitly");
        return result;
    }

    private List<String> fetchModels(Channel channel) {
        String type = normalizedType(channel);
        if (isHaoee(channel, type)) {
            return haoeeCatalogModels(channel.getId());
        }
        String url = modelsUrl(channel, type);
        WebClient.RequestHeadersSpec<?> request = webClient.get().uri(url);
        if ("gemini".equals(type) || "google".equals(type)) {
            request = request.header("x-goog-api-key", channel.getApiKey());
        } else if ("anthropic".equals(type) || "deepseek-anthropic".equals(type)) {
            request = request.header("x-api-key", channel.getApiKey())
                    .header("anthropic-version", "2023-06-01");
        } else {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.getApiKey());
        }
        JsonNode payload = request.retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .block();
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned an empty model catalog");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectModels(payload, result, "gemini".equals(type) || "google".equals(type));
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Provider response did not contain a supported model catalog");
        }
        return result.stream().sorted(Comparator.naturalOrder()).limit(MAX_DISCOVERED_MODELS).toList();
    }

    private List<String> haoeeCatalogModels(Long channelId) {
        List<ProviderModel> catalog = providerModelCatalogService.listBySource("haoee");
        if (catalog.isEmpty()) {
            providerModelCatalogService.synchronizeHaoee(channelId);
            catalog = providerModelCatalogService.listBySource("haoee");
        }
        return catalog.stream()
                .filter(model -> !Set.of("FAILED", "UNSUPPORTED", "RETIRED")
                        .contains(String.valueOf(model.getVerificationStatus()).toUpperCase(Locale.ROOT)))
                .map(ProviderModel::getUpstreamModelName)
                .filter(value -> value != null && value.matches(MODEL_PATTERN))
                .distinct()
                .sorted()
                .limit(MAX_DISCOVERED_MODELS)
                .toList();
    }

    private boolean isHaoee(Channel channel, String type) {
        return "haoee".equals(type) || "haoee-openai".equals(type)
                || "haoee".equalsIgnoreCase(channel.getSourceCode())
                || (channel.getBaseUrl() != null && channel.getBaseUrl().toLowerCase(Locale.ROOT).contains("maas.haoee.com"));
    }

    private void collectModels(JsonNode payload, Set<String> result, boolean stripGeminiPrefix) {
        if (payload == null || payload.isNull()) return;
        if (payload.isArray()) {
            collectModelArray(payload, result, stripGeminiPrefix);
            return;
        }
        if (!payload.isObject()) return;
        for (String field : List.of("data", "models", "items", "results", "records", "list")) {
            JsonNode candidate = payload.get(field);
            if (candidate == null || candidate.isNull()) continue;
            if (candidate.isArray()) {
                collectModelArray(candidate, result, stripGeminiPrefix || "models".equals(field));
            } else if (candidate.isObject()) {
                collectModels(candidate, result, stripGeminiPrefix);
            }
        }
        JsonNode resultNode = payload.get("result");
        if (resultNode != null && resultNode.isObject()) {
            collectModels(resultNode, result, stripGeminiPrefix);
        }
    }

    private void collectModelArray(JsonNode array, Set<String> result, boolean stripGeminiPrefix) {
        StreamSupport.stream(array.spliterator(), false).forEach(item -> {
            String value = text(item, "id");
            if (value == null) value = text(item, "name");
            if (value == null) value = text(item, "model");
            if (value == null) value = text(item, "modelId");
            if (value == null) value = text(item, "model_name");
            if (value == null && item.isTextual()) value = item.asText();
            if (value == null) return;
            if (stripGeminiPrefix && value.startsWith("models/")) value = value.substring("models/".length());
            value = value.trim();
            if (value.matches(MODEL_PATTERN)) result.add(value);
        });
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private String modelsUrl(Channel channel, String type) {
        String base = channel.getBaseUrl().replaceAll("/+$", "");
        if (base.endsWith("/models")) return base;
        if ("gemini".equals(type) || "google".equals(type)) {
            return base.endsWith("/v1beta") ? base + "/models" : base + "/v1beta/models";
        }
        if ("anthropic".equals(type) || "deepseek-anthropic".equals(type)) {
            return base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
        }
        if ("deepseek".equals(type) || base.contains("api.deepseek.com")) return base + "/models";
        return base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
    }

    private Channel requireChannel(Long channelId) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        return channel;
    }

    private String normalizedType(Channel channel) {
        return channel.getType() == null ? "openai-compatible" : channel.getType().trim().toLowerCase(Locale.ROOT);
    }

    private String compactModelList(List<String> models, int maxLength) {
        List<String> selected = new ArrayList<>();
        int length = 0;
        for (String model : models) {
            int next = model.length() + (selected.isEmpty() ? 0 : 1);
            if (length + next > maxLength) break;
            selected.add(model);
            length += next;
        }
        return String.join(",", selected);
    }

    private Map<String, Object> provider(String type, String name, String defaultBaseUrl,
                                         String protocol, boolean discovery) {
        return Map.of(
                "type", type,
                "name", name,
                "defaultBaseUrl", defaultBaseUrl,
                "protocol", protocol,
                "modelDiscovery", discovery
        );
    }
}
