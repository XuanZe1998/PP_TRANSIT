package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import static com.transit.service.ChannelModelNames.parseModels;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminChannelService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private GatewayPricingService gatewayPrices;

    private final ChannelMapper channelMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ChannelUrlPolicy channelUrlPolicy;
    private final ChannelSecretService channelSecretService;
    private final ModelPriceTierService priceTierService;
    private final ApplicationEventPublisher events;
    private final ChannelProbeService probes;
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
        boolean newApiChannel = !aiApiBankChannel && ("new-api".equalsIgnoreCase(current.getSourceCode())
                || Integer.valueOf(1).equals(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM new_api_connections WHERE channel_id=?", Integer.class, id)));
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
        if (newApiChannel) {
            current.setSourceCode("new-api");
            current.setSourceName("New API");
        }
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
        if(gatewayPrices!=null)gatewayPrices.protect(target.getId());
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
        jdbcTemplate.update("DELETE FROM aiapibank_image_price_variants WHERE model_offer_id IN (SELECT id FROM aiapibank_model_offers WHERE provider_group_id IN (SELECT id FROM aiapibank_provider_groups WHERE channel_id=?))", id);
        jdbcTemplate.update("DELETE FROM aiapibank_model_offers WHERE provider_group_id IN (SELECT id FROM aiapibank_provider_groups WHERE channel_id=?)", id);
        jdbcTemplate.update("DELETE FROM aiapibank_provider_groups WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM gateway_publication_requests WHERE model_mapping_id IN (SELECT id FROM model_mappings WHERE channel_id=?)", id);
        jdbcTemplate.update("DELETE FROM sub2api_model_state WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM sub2api_connections WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM new_api_model_state WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM new_api_connections WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM upstream_catalog_sync WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM gateway_price_overrides WHERE model_mapping_id IN (SELECT id FROM model_mappings WHERE channel_id=?)", id);
        jdbcTemplate.update("DELETE FROM gateway_price_rules WHERE scope_type='MODEL' AND scope_id IN (SELECT id FROM model_mappings WHERE channel_id=?)", id);
        jdbcTemplate.update("DELETE FROM gateway_price_rules WHERE scope_type='GROUP' AND scope_id=?", id);
        jdbcTemplate.update("DELETE FROM upstream_site_channels WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM upstream_display_mappings WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM provider_account_route_bindings WHERE channel_id=?", id);
        jdbcTemplate.update("DELETE FROM provider_credentials WHERE channel_id=?", id);
        List<Long> mappingIds = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                        .eq(ModelMapping::getChannelId, id))
                .stream().map(ModelMapping::getId).toList();
        priceTierService.deleteForMappings(mappingIds);
        modelMappingMapper.delete(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, id));
        channelMapper.deleteById(id);
    }

    public Map<String, Object> test(Long id) {
        return probes.test(id);
    }

    public Map<String, Object> testModel(Long id, Map<String, Object> request) {
        return probes.testModel(id, request);
    }

    public Map<String, Object> testCredential(Long channelId, Long credentialId) {
        return probes.testCredential(channelId, credentialId);
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

                """);
    }

    private BigDecimal coalesce(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) return value;
        }
        return BigDecimal.ZERO;
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

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

}
