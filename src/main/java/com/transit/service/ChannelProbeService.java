package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import static com.transit.service.ChannelModelNames.parseModels;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import com.transit.provider.ProviderGateway;
import com.transit.provider.ProviderGatewayFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.TimeoutException;

/** Channel diagnostics, probe selection and health recording; does not edit channel configuration. */
@Service
@RequiredArgsConstructor
public class ChannelProbeService {
    private final ChannelMapper channelMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ChannelUrlPolicy channelUrlPolicy;
    private final ProviderGatewayFactory providerGatewayFactory;
    private final ChannelSecretService channelSecretService;
    private final ModelPriceTierService priceTierService;
    private final OpenAiImageHealthProbe imageHealthProbe;
    @Autowired(required = false)
    private ProviderCredentialService providerCredentialService;

    public Map<String, Object> test(Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        List<String> candidates = resolveProbeModels(channel);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Configure at least one provider model before testing this channel");
        }
        boolean imageChannel = isImageProtocol(channel.getProtocolType());
        int timeoutSeconds = "new-api".equalsIgnoreCase(channel.getSourceCode())
                ? (imageChannel ? 120 : 60) : 20;
        int maximumAttempts = imageChannel ? 1 : Math.min(3, candidates.size());
        Map<String, Object> result = null;
        for (int index = 0; index < maximumAttempts; index++) {
            result = testModel(id, Map.of(
                    "providerModelName", candidates.get(index),
                    "prompt", "你是什么模型",
                    "timeoutSeconds", timeoutSeconds));
            String probeStatus = stringValue(result.get("status"), "FAILED");
            if ("SUCCESS".equalsIgnoreCase(probeStatus) || !retryableProbeStatus(probeStatus)) break;
        }
        if (result == null) throw new IllegalStateException("Provider probe did not run");
        String status = stringValue(result.get("healthStatus"), "DEGRADED");
        long latency = longValue(result.get("latencyMs"), 0);
        String probeStatus = stringValue(result.get("status"), "FAILED");
        jdbcTemplate.update(
                "INSERT INTO channel_health_checks(channel_id, status, latency_ms, message, checked_at) VALUES (?, ?, ?, ?, ?)",
                id, status, latency, status.equals("HEALTHY")
                        ? "Authenticated provider probe succeeded"
                        : "Authenticated provider probe failed: " + probeStatus, LocalDateTime.now()
        );
        return result;
    }

    public Map<String, Object> testModel(Long id, Map<String, Object> request) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        String model = resolveProviderModel(channel, request);
        if (model == null || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerModelName or modelMappingId is required");
        }
        if (channel.getBaseUrl() == null || channel.getBaseUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel baseUrl is required");
        }
        channelSecretService.reveal(channel);
        if (channel.getApiKey() == null || channel.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel apiKey is required");
        }

        int timeoutSeconds = Math.max(3, Math.min(300, intValue(request.get("timeoutSeconds"), 20)));
        String prompt = stringValue(request.get("prompt"), "你是什么模型").trim();
        if (prompt.isBlank()) {
            prompt = "你是什么模型";
        }
        if (prompt.length() > 10_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is too long");
        }
        String pythonCode = stringValue(request.get("pythonCode"), null);
        if (pythonCode != null && !pythonCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Custom executable probes are disabled; use the built-in channel probe");
        }
        Map<String, Object> result = runProbe(channel, model, prompt, timeoutSeconds, request);
        result.put("estimatedCostAmount", estimateCostAmount(channel.getId(), model, result));
        String probeStatus = stringValue(result.get("status"), "FAILED");
        updateNvidiaMappingVerification(channel, model, probeStatus);
        String healthStatus = healthStatus(channel, probeStatus);
        jdbcTemplate.update("UPDATE channels SET health_status = ? WHERE id = ?", healthStatus, channel.getId());
        insertChannelTestLog(channel, model, result, probeStatus);

        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("channelId", id);
        response.put("healthStatus", healthStatus);
        return response;
    }

    public Map<String, Object> testCredential(Long channelId, Long credentialId) {
        if (providerCredentialService == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Credential service is unavailable");
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        ProviderCredentialService.SelectedCredential selected = providerCredentialService.select(channel, credentialId);
        channel.setApiKey(selected.secret());
        channel.setAuthContext(selected.authContext());
        String model = resolveProviderModel(channel, Map.of());
        if (model == null || model.isBlank()) {
            providerCredentialService.releaseUnknown(credentialId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configure at least one provider model before testing this credential");
        }
        Map<String, Object> result = runProbe(channel, model, "你是什么模型", 20, Map.of());
        if ("SUCCESS".equalsIgnoreCase(Objects.toString(result.get("status"), ""))) {
            providerCredentialService.recordSuccess(credentialId, longValue(result.get("latencyMs"), 0));
        } else {
            providerCredentialService.recordFailure(credentialId,
                    new IllegalStateException(Objects.toString(result.get("error"), "Credential probe failed")));
        }
        return result;
    }

    private Map<String, Object> runProbe(Channel channel, String model, String prompt, int timeoutSeconds,
                                         Map<String, Object> options) {
        if (isImageProtocol(channel.getProtocolType()) || NewApiPricing.isImageModel(model)) {
            return imageHealthProbe.probe(channel, model, timeoutSeconds);
        }
        long started = System.currentTimeMillis();
        try {
            channelUrlPolicy.validate(channel.getBaseUrl());
            ChatRequest probeRequest = new ChatRequest();
            probeRequest.setModel(model);
            // Several NVIDIA NIMs reject or stall on very small generation
            // budgets. 64 is still a cheap probe while matching the provider's
            // accepted Chat Completions envelope.
            int defaultMaxTokens = "nvidia".equalsIgnoreCase(channel.getType()) ? 64 : 16;
            probeRequest.setMaxTokens(Math.max(1, Math.min(16_384,
                    intValue(options.get("maxTokens"), defaultMaxTokens))));
            if (options.containsKey("temperature")) {
                probeRequest.setTemperature(doubleValue(options.get("temperature"), 1.0));
            }
            if (options.containsKey("topP")) {
                probeRequest.setTopP(doubleValue(options.get("topP"), 1.0));
            }
            if (options.containsKey("seed")) {
                probeRequest.setSeed(intValue(options.get("seed"), 42));
            }
            if (options.get("chatTemplateKwargs") instanceof Map<?, ?> templateOptions) {
                Map<String, Object> values = new LinkedHashMap<>();
                templateOptions.forEach((key, value) -> values.put(Objects.toString(key), value));
                probeRequest.setChatTemplateKwargs(values);
            }
            ChatRequest.Message message = new ChatRequest.Message();
            message.setRole("user");
            String imageUrl = stringValue(options.get("imageUrl"), "").trim();
            if (imageUrl.isBlank()) {
                message.setContent(prompt);
            } else {
                if (!imageUrl.startsWith("https://assets.ngc.nvidia.com/")) {
                    throw new IllegalArgumentException("NVIDIA probe imageUrl must use assets.ngc.nvidia.com");
                }
                message.setContent(List.of(
                        Map.of("type", "text", "text", prompt),
                        Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))));
            }
            probeRequest.setMessages(List.of(message));
            ProviderGateway gateway = providerGatewayFactory.resolve(channel.getType());
            ChatResponse response = gateway.chatCompletions(channel, probeRequest, model, model)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();
            if (response == null) {
                return failedProbe("FAILED", System.currentTimeMillis() - started, model,
                        "Provider returned an empty response", 1);
            }
            ChatResponse.Usage usage = response.getUsage();
            Map<String, Object> usageResult = new LinkedHashMap<>();
            usageResult.put("promptTokens", usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
            usageResult.put("completionTokens", usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
            usageResult.put("cachedTokens", usage == null ? 0 : usage.cachedTokens());
            usageResult.put("cacheReadTokens", usage == null ? 0 : usage.cacheReadTokens());
            usageResult.put("cacheWriteTokens", usage == null ? 0 : usage.cacheWriteTokens());
            String sample = "";
            if (response.getChoices() != null && !response.getChoices().isEmpty()
                    && response.getChoices().get(0).getMessage() != null) {
                sample = Objects.toString(response.getChoices().get(0).getMessage().getContent(), "");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("latencyMs", System.currentTimeMillis() - started);
            result.put("model", model);
            result.put("usage", usageResult);
            result.put("sampleText", trim(sample, 2000));
            result.put("error", "");
            result.put("exitCode", 0);
            return result;
        } catch (RuntimeException error) {
            Throwable cause = rootCause(error);
            if (cause instanceof TimeoutException) {
                return failedProbe("TIMEOUT", System.currentTimeMillis() - started, model,
                        "Provider probe timed out", 124);
            }
            if (cause instanceof WebClientResponseException responseError) {
                int status = responseError.getStatusCode().value();
                String probeStatus = status == 401 || status == 403 ? "AUTH_FAILED"
                        : status == 429 ? "RATE_LIMITED" : "UPSTREAM_ERROR";
                return failedProbe(probeStatus, System.currentTimeMillis() - started, model,
                        "Provider returned HTTP " + status, status);
            }
            return failedProbe("FAILED", System.currentTimeMillis() - started, model,
                    "Provider probe failed; check channel configuration and upstream availability", 1);
        }
    }

    private void insertChannelTestLog(Channel channel, String model, Map<String, Object> result, String status) {
        Map<?, ?> usage = result.get("usage") instanceof Map<?, ?> map ? map : Map.of();
        jdbcTemplate.update("""
                INSERT INTO channel_test_logs(channel_id, model_name, provider_type, exit_code, status, latency_ms,
                    prompt_tokens, completion_tokens, cached_tokens, estimated_cost_amount, response_summary, error_message, tested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                channel.getId(),
                model,
                channel.getType(),
                intValue(result.get("exitCode"), 0),
                status,
                longValue(result.get("latencyMs"), 0),
                intValue(usage.get("promptTokens"), 0),
                intValue(usage.get("completionTokens"), 0),
                intValue(usage.get("cachedTokens"), 0),
                longValue(result.get("estimatedCostAmount"), 0),
                trim(stringValue(result.get("sampleText"), ""), 2000),
                trim(stringValue(result.get("error"), null), 2000),
                LocalDateTime.now()
        );
    }

    private long estimateCostAmount(Long channelId, String model, Map<String, Object> result) {
        Map<?, ?> usage = result.get("usage") instanceof Map<?, ?> map ? map : Map.of();
        int promptTokens = intValue(usage.get("promptTokens"), 0);
        int completionTokens = intValue(usage.get("completionTokens"), 0);
        int cacheReadTokens = Math.min(promptTokens, intValue(usage.get("cacheReadTokens"), 0));
        int cacheWriteTokens = Math.min(Math.max(0, promptTokens - cacheReadTokens),
                intValue(usage.get("cacheWriteTokens"), 0));
        if (cacheReadTokens == 0 && cacheWriteTokens == 0) {
            cacheReadTokens = Math.min(promptTokens, intValue(usage.get("cachedTokens"), 0));
        }
        int billableInputTokens = Math.max(0, promptTokens - cacheReadTokens - cacheWriteTokens);
        ModelMapping mapping = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channelId)
                .and(q -> q.eq(ModelMapping::getChannelModelName, model).or().eq(ModelMapping::getPublicModelName, model))
                .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
        if (mapping == null) {
            mapping = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                    .eq(ModelMapping::getChannelModelName, model)
                    .or()
                    .eq(ModelMapping::getPublicModelName, model)
                    .last("LIMIT 1"))
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        if (mapping == null) {
            return 0;
        }
        priceTierService.attach(List.of(mapping));
        ModelPriceTier tier = priceTierService.select(mapping, promptTokens);
        if (tier == null) {
            return price(billableInputTokens, positiveCoalesce(mapping.getInputCostPerMillion(), mapping.getCostPerMillion(), BigDecimal.ZERO), "M")
                    + price(completionTokens, positiveCoalesce(mapping.getOutputCostPerMillion(), mapping.getCostPerMillion(), BigDecimal.ZERO), "M")
                    + price(cacheReadTokens, positiveCoalesce(mapping.getCachedCostPerMillion(), BigDecimal.ZERO), "M");
        }
        return price(billableInputTokens, tier.getCostInputPrice(), tier.getCostPriceUnit())
                + price(completionTokens, tier.getCostOutputPrice(), tier.getCostPriceUnit())
                + price(cacheReadTokens, tier.getCostCacheReadPrice(), tier.getCostPriceUnit())
                + price(cacheWriteTokens, tier.getCostCacheWritePrice(), tier.getCostPriceUnit());
    }

    private long price(int tokens, BigDecimal amount, String unit) {
        if (tokens <= 0 || amount == null || BigDecimal.ZERO.compareTo(amount) == 0) {
            return 0;
        }
        long divisor = "KB".equalsIgnoreCase(unit) ? 1_000L : 1_000_000L;
        return amount
                .multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(divisor), 0, RoundingMode.CEILING)
                .longValue();
    }

    private BigDecimal positiveCoalesce(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) return value;
        }
        return BigDecimal.ZERO;
    }

    private String resolveProviderModel(Channel channel, Map<String, Object> request) {
        Long mappingId = longObject(request.get("modelMappingId"));
        if (mappingId != null) {
            ModelMapping mapping = modelMappingMapper.selectById(mappingId);
            if (mapping != null && mapping.getChannelId() != null && mapping.getChannelId().equals(channel.getId())) {
                return mapping.getChannelModelName();
            }
            throw new IllegalArgumentException("Model mapping not found for this channel");
        }
        String explicit = stringValue(request.get("providerModelName"), null);
        if (explicit == null || explicit.isBlank()) {
            explicit = stringValue(request.get("model"), null);
        }
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        List<ModelMapping> candidates = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channel.getId()).orderByDesc(ModelMapping::isEnabled).orderByAsc(ModelMapping::getId));
        Set<String> configured = new java.util.HashSet<>(parseModels(Objects.toString(channel.getModels(), "")));
        String chatModel = candidates.stream()
                .filter(m -> configured.isEmpty() || configured.contains(m.getChannelModelName()))
                .filter(m -> m.getProtocols() != null && m.getProtocols().contains("chat-completions"))
                .map(ModelMapping::getChannelModelName).filter(Objects::nonNull).findFirst().orElse(null);
        if (chatModel != null) return chatModel;
        if (channel.getModels() == null || channel.getModels().isBlank()) {
            return null;
        }
        return parseModels(channel.getModels()).stream().findFirst().orElse(null);
    }

    private List<String> resolveProbeModels(Channel channel) {
        List<ModelMapping> mappings = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channel.getId())
                .orderByDesc(ModelMapping::isEnabled)
                .orderByAsc(ModelMapping::getId));
        Set<String> configured = new LinkedHashSet<>(parseModels(Objects.toString(channel.getModels(), "")));
        boolean imageProtocol = isImageProtocol(channel.getProtocolType());
        List<ModelMapping> compatible = mappings.stream()
                .filter(mapping -> configured.isEmpty() || configured.contains(mapping.getChannelModelName()))
                .filter(mapping -> imageProtocol == isImageMapping(mapping))
                .sorted((left, right) -> {
                    int enabled = Boolean.compare(right.isEnabled(), left.isEnabled());
                    if (enabled != 0) return enabled;
                    int pricing = Boolean.compare(isVerifiedPricing(right), isVerifiedPricing(left));
                    if (pricing != 0) return pricing;
                    if (imageProtocol) {
                        int resolution = Integer.compare(imageProbePriority(left.getChannelModelName()),
                                imageProbePriority(right.getChannelModelName()));
                        if (resolution != 0) return resolution;
                    }
                    long leftId = left.getId() == null ? Long.MAX_VALUE : left.getId();
                    long rightId = right.getId() == null ? Long.MAX_VALUE : right.getId();
                    return Long.compare(leftId, rightId);
                })
                .toList();
        LinkedHashSet<String> result = compatible.stream().map(ModelMapping::getChannelModelName)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (result.isEmpty()) configured.stream()
                .filter(model -> imageProtocol == NewApiPricing.isImageModel(model)).forEach(result::add);
        if (result.isEmpty()) result.addAll(configured);
        return List.copyOf(result);
    }

    private boolean isImageMapping(ModelMapping mapping) {
        return "image".equalsIgnoreCase(mapping.getCapability())
                || (mapping.getProtocols() != null && mapping.getProtocols().toLowerCase(Locale.ROOT).contains("images"))
                || NewApiPricing.isImageModel(mapping.getChannelModelName());
    }

    private boolean isVerifiedPricing(ModelMapping mapping) {
        return "VERIFIED".equalsIgnoreCase(mapping.getPricingStatus())
                || "FREE_PREVIEW".equalsIgnoreCase(mapping.getPricingStatus());
    }

    private int imageProbePriority(String model) {
        String normalized = Objects.toString(model, "").toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(?:-|_)1k(?:$|[^0-9].*)") || normalized.contains("1024")) return 0;
        if (normalized.matches(".*(?:-|_)2k(?:$|[^0-9].*)")) return 2;
        if (normalized.matches(".*(?:-|_)4k(?:$|[^0-9].*)")) return 3;
        return 1;
    }

    private boolean isImageProtocol(String protocol) {
        return "openai-image".equalsIgnoreCase(protocol);
    }

    private boolean retryableProbeStatus(String status) {
        return Set.of("TIMEOUT", "RATE_LIMITED", "UPSTREAM_ERROR").contains(status.toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> failedProbe(String status, long latencyMs, String model, String error, int exitCode) {
        return new LinkedHashMap<>(Map.of(
                "status", status,
                "latencyMs", latencyMs,
                "model", model,
                "usage", Map.of(
                        "promptTokens", 0,
                        "completionTokens", 0,
                        "cachedTokens", 0,
                        "cacheReadTokens", 0,
                        "cacheWriteTokens", 0),
                "sampleText", "",
                "error", error == null ? "" : error,
                "exitCode", exitCode
        ));
    }

    private void updateNvidiaMappingVerification(Channel channel, String model, String probeStatus) {
        if (!"nvidia".equalsIgnoreCase(channel.getType())) {
            return;
        }
        boolean verified = "SUCCESS".equalsIgnoreCase(probeStatus);
        jdbcTemplate.update("""
                UPDATE model_mappings
                SET enabled = ?, billing_enabled = ?, billing_mode = ?, pricing_status = ?,
                    pricing_message = ?, pricing_source_url = ?, pricing_verified_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE pricing_verified_at END
                WHERE channel_id = ? AND channel_model_name = ?
                """, verified, verified, verified ? "FREE_PREVIEW" : "DISABLED",
                verified ? "FREE_PREVIEW" : "PENDING",
                verified ? "免费开发预览 · 非生产服务，不承诺生产 SLA" : "等待 NVIDIA 连通性验证",
                "https://docs.api.nvidia.com/nim/docs/run-anywhere", verified, channel.getId(), model);
    }

    private String healthStatus(Channel channel, String probeStatus) {
        if ("SUCCESS".equalsIgnoreCase(probeStatus)) {
            return "HEALTHY";
        }
        if ("AUTH_FAILED".equalsIgnoreCase(probeStatus)) {
            return "DISABLED";
        }
        if ("nvidia".equalsIgnoreCase(channel.getType())) {
            Long verifiedMappings = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM model_mappings
                    WHERE channel_id = ? AND enabled = TRUE
                    """, Long.class, channel.getId());
            if (verifiedMappings != null && verifiedMappings > 0) {
                return "HEALTHY";
            }
        }
        return "DEGRADED";
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Integer.parseInt(value.toString());
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Double.parseDouble(value.toString());
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Long.parseLong(value.toString());
    }

    private Long longObject(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return null;
        return Long.parseLong(value.toString());
    }

    private String trim(String value, int maxLength) {
        if (value == null) return null;
        return value.substring(0, Math.min(maxLength, value.length()));
    }
}
