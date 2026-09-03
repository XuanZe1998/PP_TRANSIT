package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class AdminChannelService {

    private final ChannelMapper channelMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ChannelUrlPolicy channelUrlPolicy;
    private final ProviderGatewayFactory providerGatewayFactory;
    private final ChannelSecretService channelSecretService;
    private final ModelPriceTierService priceTierService;
    private final ApplicationEventPublisher events;
    @Autowired(required = false)
    private ProviderCredentialService providerCredentialService;
    @Autowired(required = false)
    private ModelIdentityService modelIdentityService;

    @Transactional(readOnly = true)
    public List<Channel> list() {
        List<Channel> channels = channelMapper.selectList(null);
        if (channels.isEmpty()) return channels;
        List<ModelMapping> pricing = modelMappingMapper.selectList(
                        new LambdaQueryWrapper<ModelMapping>()
                                .in(ModelMapping::getChannelId, channels.stream().map(Channel::getId).toList())
                                .orderByAsc(ModelMapping::getChannelId)
                                .orderByAsc(ModelMapping::getChannelModelName));
        priceTierService.attach(pricing);
        Map<Long, List<ModelMapping>> pricingByChannel = pricing.stream()
                .collect(Collectors.groupingBy(ModelMapping::getChannelId,
                        LinkedHashMap::new, Collectors.toList()));
        channels.forEach(channel -> {
            channel.setModelPricing(pricingByChannel.getOrDefault(channel.getId(), List.of()));
            channelSecretService.redact(channel);
        });
        return channels;
    }

    @Transactional
    public Channel create(Channel channel) {
        List<ModelMapping> requestedPricing = safePricing(channel.getModelPricing());
        normalize(channel);
        rejectDuplicateManagedChannel(channel, null);
        requireCredentialEncryption();
        channel.setApiKey(channelSecretService.encrypt(channel.getApiKey()));
        channelMapper.insert(channel);
        synchronizeModelMappings(channel, parseModels(channel.getModels()), requestedPricing);
        return channelView(channelMapper.selectById(channel.getId()));
    }

    @Transactional
    public Channel update(Long id, Channel request) {
        Channel current = channelMapper.selectById(id);
        if (current == null) {
            throw new IllegalArgumentException("Channel not found");
        }
        boolean aiApiBankChannel = isAiApiBankChannel(id, current);
        String requestedModels = normalizedModels(request.getModels());
        boolean routingConfigurationChanged = !Objects.equals(current.getType(), request.getType())
                || !Objects.equals(current.getBaseUrl(), request.getBaseUrl())
                || !Objects.equals(normalizedModels(current.getModels()), requestedModels);
        current.setName(request.getName());
        current.setType(request.getType());
        current.setSourceCode(aiApiBankChannel ? AiApiBankCatalogService.SOURCE_CODE
                : defaultString(request.getSourceCode(), current.getSourceCode()));
        current.setSourceName(aiApiBankChannel ? AiApiBankCatalogService.SOURCE_NAME
                : defaultString(request.getSourceName(), current.getSourceName()));
        current.setProtocolType(defaultString(request.getProtocolType(), "openai-chat"));
        current.setBaseUrl(request.getBaseUrl());
        normalizeSourceMetadata(current);
        rejectDuplicateManagedChannel(current, id);
        boolean credentialChanged = request.getApiKey() != null && !request.getApiKey().isBlank()
                && !request.getApiKey().contains("***");
        if (credentialChanged) {
            requireCredentialEncryption();
            current.setApiKey(channelSecretService.encrypt(request.getApiKey()));
            routingConfigurationChanged = true;
        }
        current.setModels(requestedModels);
        current.setEnabled(request.isEnabled());
        current.setGroupName(defaultString(request.getGroupName(), "default"));
        current.setWeight(request.getWeight() <= 0 ? 100 : request.getWeight());
        current.setRpmLimit(Math.max(0, request.getRpmLimit()));
        current.setTpmLimit(Math.max(0, request.getTpmLimit()));
        current.setAutoDisable(request.isAutoDisable());
        current.setFailureThreshold(request.getFailureThreshold() <= 0 ? 3 : request.getFailureThreshold());
        current.setCooldownSeconds(request.getCooldownSeconds() <= 0 ? 60 : request.getCooldownSeconds());
        current.setHealthStatus(routingConfigurationChanged
                ? "UNTESTED" : defaultString(current.getHealthStatus(), "UNTESTED"));
        current.setCooldownUntil(request.getCooldownUntil());
        validateChannel(current);
        channelMapper.updateById(current);
        synchronizeModelMappings(current, parseModels(current.getModels()), safePricing(request.getModelPricing()));
        if (credentialChanged && aiApiBankChannel) {
            events.publishEvent(new AiApiBankCredentialConfiguredEvent(id));
        }
        return channelView(channelMapper.selectById(id));
    }

    @Transactional
    public ModelMapping saveModelPricing(Long channelId, ModelMapping request) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        String requestedName = defaultString(request.getChannelModelName(), request.getPublicModelName()).trim();
        List<String> names = parseModels(requestedName);
        if (names.size() != 1 || !names.get(0).equals(requestedName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exactly one valid model name is required");
        }
        String modelName = names.get(0);
        ModelMapping target = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                        .eq(ModelMapping::getChannelId, channelId)
                        .eq(ModelMapping::getChannelModelName, modelName)
                        .last("LIMIT 1"))
                .stream().findFirst().orElseGet(() -> defaultMapping(channelId, modelName));
        applyEditablePricing(target, request);
        target.setPublicModelName(modelName);
        target.setChannelModelName(modelName);
        target.setChannelId(channelId);
        validatePricing(target);
        if (target.getId() == null) {
            modelMappingMapper.insert(target);
        } else {
            modelMappingMapper.updateById(target);
        }
        priceTierService.synchronize(target, request.getPriceTiers());
        registerIdentity(channel, target, ModelIdentityService.RANK_MAPPING);

        List<String> channelModels = new java.util.ArrayList<>(parseModels(channel.getModels()));
        if (!channelModels.contains(modelName)) {
            channelModels.add(modelName);
            channel.setModels(String.join("\n", channelModels));
            channelMapper.updateById(channel);
        }
        return target;
    }

    @Transactional
    public void deleteModelPricing(Long channelId, Long mappingId) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        ModelMapping mapping = modelMappingMapper.selectById(mappingId);
        if (mapping == null || !Objects.equals(mapping.getChannelId(), channelId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel model pricing not found");
        }
        priceTierService.deleteForMappings(List.of(mappingId));
        modelMappingMapper.deleteById(mappingId);
        channel.setModels(parseModels(channel.getModels()).stream()
                .filter(model -> !model.equals(mapping.getChannelModelName()))
                .collect(Collectors.joining("\n")));
        channelMapper.updateById(channel);
    }

    @Transactional
    public void delete(Long id) {
        List<Long> mappingIds = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                        .eq(ModelMapping::getChannelId, id))
                .stream().map(ModelMapping::getId).toList();
        priceTierService.deleteForMappings(mappingIds);
        modelMappingMapper.delete(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, id));
        channelMapper.deleteById(id);
    }

    public Map<String, Object> test(Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        String model = resolveProviderModel(channel, Map.of());
        if (model == null || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Configure at least one provider model before testing this channel");
        }
        Map<String, Object> result = testModel(id, Map.of(
                "providerModelName", model,
                "prompt", "你是什么模型",
                "timeoutSeconds", 20));
        String status = stringValue(result.get("healthStatus"), "DEGRADED");
        long latency = longValue(result.get("latencyMs"), 0);
        jdbcTemplate.update(
                "INSERT INTO channel_health_checks(channel_id, status, latency_ms, message, checked_at) VALUES (?, ?, ?, ?, ?)",
                id, status, latency, status.equals("HEALTHY")
                        ? "Authenticated provider probe succeeded" : "Authenticated provider probe failed", LocalDateTime.now()
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

    public List<Map<String, Object>> healthChecks(Long channelId) {
        return jdbcTemplate.queryForList("""
                SELECT channel_id, status, latency_ms, message, checked_at
                FROM channel_health_checks
                WHERE channel_id = ?
                ORDER BY checked_at DESC
                LIMIT 50
                """, channelId);
    }

    public List<Map<String, Object>> testLogs() {
        return jdbcTemplate.queryForList("""
                SELECT ctl.id, ctl.channel_id, c.name AS channel_name, ctl.model_name, ctl.provider_type,
                       ctl.status, ctl.exit_code, ctl.latency_ms,
                       ctl.prompt_tokens, ctl.completion_tokens, ctl.cached_tokens,
                       ctl.estimated_cost_amount, ctl.response_summary, ctl.error_message, ctl.tested_at
                FROM channel_test_logs ctl
                LEFT JOIN channels c ON c.id = ctl.channel_id
                ORDER BY ctl.tested_at DESC
                LIMIT 500
                """);
    }

    private Map<String, Object> runProbe(Channel channel, String model, String prompt, int timeoutSeconds,
                                         Map<String, Object> options) {
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
                    trim(Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()), 500), 1);
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

    private BigDecimal coalesce(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) return value;
        }
        return BigDecimal.ZERO;
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
        if (channel.getModels() == null || channel.getModels().isBlank()) {
            return null;
        }
        return parseModels(channel.getModels()).stream().findFirst().orElse(null);
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

    private void normalize(Channel channel) {
        normalizeSourceMetadata(channel);
        channel.setProtocolType(defaultString(channel.getProtocolType(), "openai-chat"));
        channel.setGroupName(defaultString(channel.getGroupName(), "default"));
        channel.setWeight(channel.getWeight() <= 0 ? 100 : channel.getWeight());
        channel.setFailureThreshold(channel.getFailureThreshold() <= 0 ? 3 : channel.getFailureThreshold());
        channel.setCooldownSeconds(channel.getCooldownSeconds() <= 0 ? 60 : channel.getCooldownSeconds());
        channel.setHealthStatus("UNTESTED");
        channel.setModels(normalizedModels(channel.getModels()));
        validateChannel(channel);
    }

    private void normalizeSourceMetadata(Channel channel) {
        String type = defaultString(channel.getType(), "").toLowerCase(java.util.Locale.ROOT);
        String baseUrl = defaultString(channel.getBaseUrl(), "").toLowerCase(java.util.Locale.ROOT);
        if (type.equals("haoee") || type.equals("haoee-openai") || baseUrl.contains("haoee.com")) {
            channel.setSourceCode("haoee");
            channel.setSourceName("好易智算");
            return;
        }
        if (type.equals("nvidia") || baseUrl.contains("nvidia.com")) {
            channel.setSourceCode("nvidia");
            channel.setSourceName("NVIDIA");
            return;
        }
        channel.setSourceCode(defaultString(channel.getSourceCode(), "other"));
        channel.setSourceName(defaultString(channel.getSourceName(), "其他兼容服务"));
    }

    private boolean isAiApiBankChannel(Long channelId, Channel channel) {
        if (channel != null && AiApiBankCatalogService.SOURCE_CODE.equalsIgnoreCase(channel.getSourceCode())) {
            return true;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiapibank_provider_groups WHERE channel_id=?", Integer.class, channelId);
        if (count != null && count > 0) return true;
        if (channel == null
                || !defaultString(channel.getName(), "").toLowerCase(Locale.ROOT).startsWith("aiapibank")
                || !defaultString(channel.getBaseUrl(), "").toLowerCase(Locale.ROOT).contains("aiapibank.com")) {
            return false;
        }
        Integer legacyGroupCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiapibank_provider_groups WHERE group_slug=?",
                Integer.class, channel.getGroupName());
        return legacyGroupCount != null && legacyGroupCount > 0;
    }

    private void rejectDuplicateManagedChannel(Channel channel, Long currentId) {
        String source = defaultString(channel.getSourceCode(), "other").toLowerCase(Locale.ROOT);
        if (!List.of("haoee", "nvidia").contains(source)) return;
        String normalizedUrl = defaultString(channel.getBaseUrl(), "").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        List<Channel> existing = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getSourceCode, source));
        boolean duplicate = existing.stream().anyMatch(item -> !Objects.equals(item.getId(), currentId)
                && defaultString(item.getBaseUrl(), "").replaceAll("/+$", "").toLowerCase(Locale.ROOT).equals(normalizedUrl));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该托管供应商渠道已存在，请在现有渠道的凭证池中添加 API Key");
        }
    }

    private Channel channelView(Channel channel) {
        if (channel == null) return null;
        List<ModelMapping> pricing = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channel.getId())
                .orderByAsc(ModelMapping::getChannelModelName));
        priceTierService.attach(pricing);
        channel.setModelPricing(pricing);
        channelSecretService.redact(channel);
        return channel;
    }

    private List<ModelMapping> safePricing(List<ModelMapping> pricing) {
        return pricing == null ? List.of() : pricing;
    }

    private String normalizedModels(String value) {
        return String.join("\n", parseModels(value));
    }

    private List<String> parseModels(String value) {
        if (value == null || value.isBlank()) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String item : value.split("[,，、\\r\\n]+")) {
            String model = item.trim();
            if (model.isBlank()) continue;
            if (!model.matches("[A-Za-z0-9._:/-]{1,160}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model name is invalid: " + model);
            }
            unique.add(model);
        }
        return List.copyOf(unique);
    }

    private void synchronizeModelMappings(Channel channel, List<String> models,
                                          List<ModelMapping> requestedPricing) {
        Long channelId = channel.getId();
        List<ModelMapping> existing = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channelId));
        Map<String, ModelMapping> existingByModel = existing.stream().collect(Collectors.toMap(
                ModelMapping::getChannelModelName, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, ModelMapping> requestedByModel = requestedPricing.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getChannelModelName() != null || item.getPublicModelName() != null)
                .collect(Collectors.toMap(
                        item -> defaultString(item.getChannelModelName(), item.getPublicModelName()).trim(),
                        Function.identity(), (left, right) -> right, LinkedHashMap::new));

        Set<Long> retainedIds = new LinkedHashSet<>();
        for (String model : models) {
            ModelMapping target = existingByModel.get(model);
            ModelMapping requested = requestedByModel.get(model);
            if (target == null) {
                target = defaultMapping(channelId, model);
            }
            if (requested != null) {
                applyPricing(target, requested);
            }
            // Model names and channel ownership are derived from the channel and
            // cannot drift through a second manual administration surface.
            target.setPublicModelName(model);
            target.setChannelModelName(model);
            target.setChannelId(channelId);
            validatePricing(target);
            if (target.getId() == null) {
                modelMappingMapper.insert(target);
            } else {
                modelMappingMapper.updateById(target);
            }
            priceTierService.synchronize(target,
                    requested == null ? List.of() : requested.getPriceTiers());
            registerIdentity(channel, target, ModelIdentityService.RANK_MAPPING);
            retainedIds.add(target.getId());
        }

        for (ModelMapping mapping : existing) {
            if (!retainedIds.contains(mapping.getId())) {
                priceTierService.deleteForMappings(List.of(mapping.getId()));
                modelMappingMapper.deleteById(mapping.getId());
            }
        }
    }

    private void registerIdentity(Channel channel, ModelMapping mapping, int rank) {
        if (modelIdentityService != null) modelIdentityService.register(channel, mapping, mapping.getVendor(), rank);
    }

    private ModelMapping defaultMapping(Long channelId, String model) {
        return ModelMapping.builder()
                .publicModelName(model)
                .channelModelName(model)
                .channelId(channelId)
                .priority(10)
                .enabled(false)
                .priceRatio(BigDecimal.ONE)
                .costPerMillion(BigDecimal.ZERO)
                .inputPricePerMillion(BigDecimal.ZERO)
                .outputPricePerMillion(BigDecimal.ZERO)
                .cachedPricePerMillion(BigDecimal.ZERO)
                .inputCostPerMillion(BigDecimal.ZERO)
                .outputCostPerMillion(BigDecimal.ZERO)
                .cachedCostPerMillion(BigDecimal.ZERO)
                .billingEnabled(false)
                .billingMode("DISABLED")
                .pricingStatus("PENDING")
                .pricingMessage("缺少关键销售价格")
                .officialUnitPrice(BigDecimal.ZERO)
                .costUnitPrice(BigDecimal.ZERO)
                .saleUnitPrice(BigDecimal.ZERO)
                .trafficPercent(100)
                .capabilityTags("manual,pricing-required")
                .build();
    }

    private void applyPricing(ModelMapping target, ModelMapping source) {
        applyEditablePricing(target, source);
        target.setVendor(defaultString(source.getVendor(), "unknown"));
        target.setCapability(defaultString(source.getCapability(), "text"));
        target.setInputModalities(defaultString(source.getInputModalities(), "text"));
        target.setOutputModalities(defaultString(source.getOutputModalities(), "text"));
        target.setProtocols(defaultString(source.getProtocols(), "chat-completions"));
        target.setPricingUnit(defaultString(source.getPricingUnit(), "TOKEN"));
        target.setBillingMode(defaultString(source.getBillingMode(), target.isBillingEnabled() ? "PAID" : "DISABLED"));
        target.setPricingStatus(defaultString(source.getPricingStatus(), "PENDING"));
        target.setPricingMessage(source.getPricingMessage());
        target.setPricingSourceUrl(source.getPricingSourceUrl());
        target.setPricingVerifiedAt(source.getPricingVerifiedAt());
        target.setOfficialUnitPrice(coalesce(source.getOfficialUnitPrice(), BigDecimal.ZERO));
        target.setCostUnitPrice(coalesce(source.getCostUnitPrice(), BigDecimal.ZERO));
        target.setSaleUnitPrice(coalesce(source.getSaleUnitPrice(), BigDecimal.ZERO));
        target.setEndpointPath(source.getEndpointPath());
        target.setTaskQueryPath(source.getTaskQueryPath());
        target.setTaskQueryMethod(defaultString(source.getTaskQueryMethod(), "POST"));
    }

    private void applyEditablePricing(ModelMapping target, ModelMapping source) {
        target.setPriority(source.getPriority());
        target.setEnabled(source.isEnabled());
        target.setPriceRatio(coalesce(source.getPriceRatio(), BigDecimal.ONE));
        target.setCostPerMillion(coalesce(source.getCostPerMillion(), BigDecimal.ZERO));
        target.setInputCostPerMillion(coalesce(source.getInputCostPerMillion(), target.getCostPerMillion()));
        target.setOutputCostPerMillion(coalesce(source.getOutputCostPerMillion(), target.getCostPerMillion()));
        target.setCachedCostPerMillion(coalesce(source.getCachedCostPerMillion(), BigDecimal.ZERO));
        target.setInputPricePerMillion(coalesce(source.getInputPricePerMillion(),
                suggestedSale(target.getInputCostPerMillion(), target.getPriceRatio(), BigDecimal.ONE)));
        target.setOutputPricePerMillion(coalesce(source.getOutputPricePerMillion(),
                suggestedSale(target.getOutputCostPerMillion(), target.getPriceRatio(), BigDecimal.ONE)));
        target.setCachedPricePerMillion(coalesce(source.getCachedPricePerMillion(),
                suggestedSale(target.getCachedCostPerMillion(), target.getPriceRatio(), BigDecimal.ZERO)));
        target.setBillingEnabled(source.isBillingEnabled());
        target.setBillingMode(defaultString(source.getBillingMode(), source.isBillingEnabled() ? "PAID" : "DISABLED"));
        target.setPricingStatus(defaultString(source.getPricingStatus(), "PENDING"));
        target.setPricingMessage(source.getPricingMessage());
        target.setPricingSourceUrl(source.getPricingSourceUrl());
        target.setPricingVerifiedAt(source.getPricingVerifiedAt());
        target.setOfficialUnitPrice(coalesce(source.getOfficialUnitPrice(), BigDecimal.ZERO));
        target.setCostUnitPrice(coalesce(source.getCostUnitPrice(), BigDecimal.ZERO));
        target.setSaleUnitPrice(coalesce(source.getSaleUnitPrice(), BigDecimal.ZERO));
        target.setTrafficPercent(source.getTrafficPercent() <= 0 ? 100 : source.getTrafficPercent());
        target.setCapabilityTags(source.getCapabilityTags());
    }

    private BigDecimal suggestedSale(BigDecimal cost, BigDecimal ratio, BigDecimal fallback) {
        if (cost == null || cost.signum() == 0) return fallback;
        return cost.multiply(coalesce(ratio, BigDecimal.ONE));
    }

    private void validatePricing(ModelMapping mapping) {
        if (mapping.getPriority() < -10_000 || mapping.getPriority() > 10_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "priority is out of range");
        }
        if (mapping.getTrafficPercent() < 1 || mapping.getTrafficPercent() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trafficPercent must be between 1 and 100");
        }
        validateAmount(mapping.getPriceRatio(), "priceRatio");
        validateAmount(mapping.getCostPerMillion(), "costPerMillion");
        validateAmount(mapping.getInputPricePerMillion(), "inputPricePerMillion");
        validateAmount(mapping.getOutputPricePerMillion(), "outputPricePerMillion");
        validateAmount(mapping.getCachedPricePerMillion(), "cachedPricePerMillion");
        validateAmount(mapping.getInputCostPerMillion(), "inputCostPerMillion");
        validateAmount(mapping.getOutputCostPerMillion(), "outputCostPerMillion");
        validateAmount(mapping.getCachedCostPerMillion(), "cachedCostPerMillion");
        validateAmount(mapping.getOfficialUnitPrice(), "officialUnitPrice");
        validateAmount(mapping.getCostUnitPrice(), "costUnitPrice");
        validateAmount(mapping.getSaleUnitPrice(), "saleUnitPrice");
        String unit = defaultString(mapping.getPricingUnit(), "TOKEN").toUpperCase(Locale.ROOT);
        if (!List.of("TOKEN", "SECOND", "IMAGE", "MINUTE", "CHARACTER", "TASK").contains(unit)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported pricingUnit");
        }
        mapping.setPricingUnit(unit);
        String mode = defaultString(mapping.getBillingMode(), mapping.isBillingEnabled() ? "PAID" : "DISABLED").toUpperCase(Locale.ROOT);
        if (!List.of("PAID", "FREE_PREVIEW", "DISABLED").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported billingMode");
        }
        mapping.setBillingMode(mode);
    }

    private void validateAmount(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is out of range");
        }
    }

    private void validateChannel(Channel channel) {
        if (channel.getName() == null || channel.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel name is required");
        }
        if (channel.getType() == null || channel.getType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel type is required");
        }
        if (channel.getBaseUrl() == null || channel.getBaseUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel baseUrl is required");
        }
        if (channel.getApiKey() == null || channel.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel apiKey is required");
        }
        if (channel.getWeight() < 1 || channel.getWeight() > 10_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weight must be between 1 and 10000");
        }
        if (channel.getRpmLimit() < 0 || channel.getRpmLimit() > 10_000_000
                || channel.getTpmLimit() < 0 || channel.getTpmLimit() > 1_000_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel rate limit is out of range");
        }
        if (channel.getFailureThreshold() < 1 || channel.getFailureThreshold() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "failureThreshold must be between 1 and 100");
        }
        if (channel.getCooldownSeconds() < 5 || channel.getCooldownSeconds() > 86_400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cooldownSeconds must be between 5 and 86400");
        }
        if (channel.getModels() != null && channel.getModels().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel model list is too long");
        }
        if (!channel.getSourceCode().matches("[A-Za-z0-9._-]{1,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel sourceCode is invalid");
        }
        if (!channel.getProtocolType().matches("[A-Za-z0-9._-]{1,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel protocolType is invalid");
        }
        channelUrlPolicy.validate(channel.getBaseUrl());
    }

    private void requireCredentialEncryption() {
        if (!channelSecretService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Channel credential encryption is not configured");
        }
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
