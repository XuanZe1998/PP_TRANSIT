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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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
        requireCredentialEncryption();
        channel.setApiKey(channelSecretService.encrypt(channel.getApiKey()));
        channelMapper.insert(channel);
        synchronizeModelMappings(channel.getId(), parseModels(channel.getModels()), requestedPricing);
        return channelView(channelMapper.selectById(channel.getId()));
    }

    @Transactional
    public Channel update(Long id, Channel request) {
        Channel current = channelMapper.selectById(id);
        if (current == null) {
            throw new IllegalArgumentException("Channel not found");
        }
        String requestedModels = normalizedModels(request.getModels());
        boolean routingConfigurationChanged = !Objects.equals(current.getType(), request.getType())
                || !Objects.equals(current.getBaseUrl(), request.getBaseUrl())
                || !Objects.equals(normalizedModels(current.getModels()), requestedModels);
        current.setName(request.getName());
        current.setType(request.getType());
        current.setBaseUrl(request.getBaseUrl());
        if (request.getApiKey() != null && !request.getApiKey().isBlank() && !request.getApiKey().contains("***")) {
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
        synchronizeModelMappings(id, parseModels(current.getModels()), safePricing(request.getModelPricing()));
        return channelView(channelMapper.selectById(id));
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

        int timeoutSeconds = Math.max(3, Math.min(120, intValue(request.get("timeoutSeconds"), 20)));
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
        Map<String, Object> result = runProbe(channel, model, prompt, timeoutSeconds);
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

    private Map<String, Object> runProbe(Channel channel, String model, String prompt, int timeoutSeconds) {
        long started = System.currentTimeMillis();
        try {
            channelUrlPolicy.validate(channel.getBaseUrl());
            ChatRequest probeRequest = new ChatRequest();
            probeRequest.setModel(model);
            // Several NVIDIA NIMs reject or stall on very small generation
            // budgets. 64 is still a cheap probe while matching the provider's
            // accepted Chat Completions envelope.
            probeRequest.setMaxTokens("nvidia".equalsIgnoreCase(channel.getType()) ? 64 : 16);
            ChatRequest.Message message = new ChatRequest.Message();
            message.setRole("user");
            message.setContent(prompt);
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
            return price(billableInputTokens, positiveCoalesce(mapping.getInputCostPerMillion(), mapping.getCostPerMillion(), BigDecimal.ZERO))
                    + price(completionTokens, positiveCoalesce(mapping.getOutputCostPerMillion(), mapping.getCostPerMillion(), BigDecimal.ZERO))
                    + price(cacheReadTokens, positiveCoalesce(mapping.getCachedCostPerMillion(), BigDecimal.ZERO));
        }
        return price(billableInputTokens, tier.getCostInputPrice())
                + price(completionTokens, tier.getCostOutputPrice())
                + price(cacheReadTokens, tier.getCostCacheReadPrice())
                + price(cacheWriteTokens, tier.getCostCacheWritePrice());
    }

    private long price(int tokens, BigDecimal amountPerMillion) {
        if (tokens <= 0 || amountPerMillion == null || BigDecimal.ZERO.compareTo(amountPerMillion) == 0) {
            return 0;
        }
        return amountPerMillion
                .multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.CEILING)
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
                SET enabled = ?
                WHERE channel_id = ? AND channel_model_name = ?
                """, verified, channel.getId(), model);
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
        channel.setGroupName(defaultString(channel.getGroupName(), "default"));
        channel.setWeight(channel.getWeight() <= 0 ? 100 : channel.getWeight());
        channel.setFailureThreshold(channel.getFailureThreshold() <= 0 ? 3 : channel.getFailureThreshold());
        channel.setCooldownSeconds(channel.getCooldownSeconds() <= 0 ? 60 : channel.getCooldownSeconds());
        channel.setHealthStatus("UNTESTED");
        channel.setModels(normalizedModels(channel.getModels()));
        validateChannel(channel);
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

    private void synchronizeModelMappings(Long channelId, List<String> models,
                                          List<ModelMapping> requestedPricing) {
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
            retainedIds.add(target.getId());
        }

        for (ModelMapping mapping : existing) {
            if (!retainedIds.contains(mapping.getId())) {
                priceTierService.deleteForMappings(List.of(mapping.getId()));
                modelMappingMapper.deleteById(mapping.getId());
            }
        }
    }

    private ModelMapping defaultMapping(Long channelId, String model) {
        return ModelMapping.builder()
                .publicModelName(model)
                .channelModelName(model)
                .channelId(channelId)
                .priority(10)
                .enabled(true)
                .priceRatio(BigDecimal.ONE)
                .costPerMillion(BigDecimal.ZERO)
                .inputPricePerMillion(BigDecimal.ONE)
                .outputPricePerMillion(BigDecimal.ONE)
                .cachedPricePerMillion(BigDecimal.ZERO)
                .inputCostPerMillion(BigDecimal.ZERO)
                .outputCostPerMillion(BigDecimal.ZERO)
                .cachedCostPerMillion(BigDecimal.ZERO)
                .billingEnabled(true)
                .trafficPercent(100)
                .build();
    }

    private void applyPricing(ModelMapping target, ModelMapping source) {
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
