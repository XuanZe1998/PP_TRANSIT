package com.transit.service;

import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.ModelMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AdminModelService {

    private final ModelMappingMapper modelMappingMapper;
    private final ChannelMapper channelMapper;
    private final ChannelSecretService channelSecretService;
    private final ModelPriceTierService priceTierService;
    @Autowired(required = false) private ProviderCredentialService providerCredentials;

    public List<ModelMapping> list() {
        List<ModelMapping> mappings = modelMappingMapper.selectList(null);
        priceTierService.attach(mappings);
        return mappings.stream()
                .map(this::decorateAvailability)
                .toList();
    }

    @Transactional
    public ModelMapping create(ModelMapping mapping) {
        normalize(mapping);
        modelMappingMapper.insert(mapping);
        priceTierService.synchronize(mapping, mapping.getPriceTiers());
        return decorateAvailability(mapping);
    }

    @Transactional
    public ModelMapping update(Long id, ModelMapping request) {
        ModelMapping mapping = modelMappingMapper.selectById(id);
        if (mapping == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model mapping not found");
        }
        mapping.setPublicModelName(request.getPublicModelName());
        mapping.setChannelModelName(request.getChannelModelName());
        mapping.setChannelId(request.getChannelId());
        mapping.setPriority(request.getPriority());
        mapping.setEnabled(request.isEnabled());
        mapping.setPriceRatio(request.getPriceRatio());
        mapping.setCostPerMillion(request.getCostPerMillion());
        mapping.setInputPricePerMillion(request.getInputPricePerMillion());
        mapping.setOutputPricePerMillion(request.getOutputPricePerMillion());
        mapping.setCachedPricePerMillion(request.getCachedPricePerMillion());
        mapping.setInputCostPerMillion(request.getInputCostPerMillion());
        mapping.setOutputCostPerMillion(request.getOutputCostPerMillion());
        mapping.setCachedCostPerMillion(request.getCachedCostPerMillion());
        mapping.setBillingEnabled(request.isBillingEnabled());
        mapping.setTrafficPercent(request.getTrafficPercent());
        mapping.setCapabilityTags(request.getCapabilityTags());
        mapping.setVendor(request.getVendor());
        mapping.setCapability(request.getCapability());
        mapping.setInputModalities(request.getInputModalities());
        mapping.setOutputModalities(request.getOutputModalities());
        mapping.setProtocols(request.getProtocols());
        mapping.setPricingUnit(request.getPricingUnit());
        mapping.setBillingMode(request.getBillingMode());
        mapping.setPricingStatus(request.getPricingStatus());
        mapping.setPricingMessage(request.getPricingMessage());
        mapping.setPricingSourceUrl(request.getPricingSourceUrl());
        mapping.setPricingVerifiedAt(request.getPricingVerifiedAt());
        mapping.setOfficialUnitPrice(request.getOfficialUnitPrice());
        mapping.setCostUnitPrice(request.getCostUnitPrice());
        mapping.setSaleUnitPrice(request.getSaleUnitPrice());
        mapping.setEndpointPath(request.getEndpointPath());
        mapping.setTaskQueryPath(request.getTaskQueryPath());
        mapping.setTaskQueryMethod(request.getTaskQueryMethod());
        normalize(mapping);
        modelMappingMapper.updateById(mapping);
        priceTierService.synchronize(mapping, request.getPriceTiers());
        return decorateAvailability(mapping);
    }

    public ModelMapping updateRouting(Long id, String publicModelName, String channelModelName,
                                      Long channelId, int priority, boolean enabled) {
        ModelMapping mapping = modelMappingMapper.selectById(id);
        if (mapping == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model mapping not found");
        }
        mapping.setPublicModelName(publicModelName);
        mapping.setChannelModelName(channelModelName);
        mapping.setChannelId(channelId);
        mapping.setPriority(priority);
        mapping.setEnabled(enabled);
        normalize(mapping);
        modelMappingMapper.updateById(mapping);
        return decorateAvailability(mapping);
    }

    @Transactional
    public void delete(Long id) {
        priceTierService.deleteForMappings(List.of(id));
        modelMappingMapper.deleteById(id);
    }

    private void normalize(ModelMapping mapping) {
        if (mapping.getPublicModelName() == null || mapping.getChannelModelName() == null
                || !mapping.getPublicModelName().matches("[A-Za-z0-9._:/-]{1,160}")
                || !mapping.getChannelModelName().matches("[A-Za-z0-9._:/-]{1,160}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model names are invalid");
        }
        if (mapping.getChannelId() == null || channelMapper.selectById(mapping.getChannelId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid channel is required");
        }
        if (mapping.getPriority() < -10000 || mapping.getPriority() > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "priority is out of range");
        }
        if (mapping.getPriceRatio() == null) mapping.setPriceRatio(BigDecimal.ONE);
        if (mapping.getCostPerMillion() == null) mapping.setCostPerMillion(BigDecimal.ZERO);
        if (mapping.getInputPricePerMillion() == null) mapping.setInputPricePerMillion(mapping.getPriceRatio());
        if (mapping.getOutputPricePerMillion() == null) mapping.setOutputPricePerMillion(mapping.getPriceRatio());
        if (mapping.getCachedPricePerMillion() == null) mapping.setCachedPricePerMillion(BigDecimal.ZERO);
        if (mapping.getInputCostPerMillion() == null) mapping.setInputCostPerMillion(mapping.getCostPerMillion());
        if (mapping.getOutputCostPerMillion() == null) mapping.setOutputCostPerMillion(mapping.getCostPerMillion());
        if (mapping.getCachedCostPerMillion() == null) mapping.setCachedCostPerMillion(BigDecimal.ZERO);
        if (mapping.getTrafficPercent() <= 0) mapping.setTrafficPercent(100);
        if (mapping.getTrafficPercent() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trafficPercent must be between 1 and 100");
        }
        validatePrice(mapping.getPriceRatio(), "priceRatio");
        validatePrice(mapping.getCostPerMillion(), "costPerMillion");
        validatePrice(mapping.getInputPricePerMillion(), "inputPricePerMillion");
        validatePrice(mapping.getOutputPricePerMillion(), "outputPricePerMillion");
        validatePrice(mapping.getCachedPricePerMillion(), "cachedPricePerMillion");
        validatePrice(mapping.getInputCostPerMillion(), "inputCostPerMillion");
        validatePrice(mapping.getOutputCostPerMillion(), "outputCostPerMillion");
        validatePrice(mapping.getCachedCostPerMillion(), "cachedCostPerMillion");
        if (mapping.getCapabilityTags() != null && mapping.getCapabilityTags().length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capabilityTags is too long");
        }
        mapping.setVendor(normalizeEnumLike(mapping.getVendor(), "unknown", 80));
        mapping.setCapability(normalizeEnumLike(mapping.getCapability(), "text", 40));
        mapping.setInputModalities(normalizeCsv(mapping.getInputModalities(), "text", 255));
        mapping.setOutputModalities(normalizeCsv(mapping.getOutputModalities(), "text", 255));
        mapping.setProtocols(normalizeCsv(mapping.getProtocols(), "chat-completions", 255));
        mapping.setPricingUnit(normalizeEnumLike(mapping.getPricingUnit(), "TOKEN", 40).toUpperCase(Locale.ROOT));
        mapping.setBillingMode(normalizeEnumLike(mapping.getBillingMode(), mapping.isBillingEnabled() ? "PAID" : "DISABLED", 24).toUpperCase(Locale.ROOT));
        mapping.setPricingStatus(normalizeEnumLike(mapping.getPricingStatus(), "PENDING", 24).toUpperCase(Locale.ROOT));
        if (!List.of("TOKEN", "SECOND", "IMAGE", "MINUTE", "CHARACTER", "TASK").contains(mapping.getPricingUnit())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported pricing unit");
        }
        if (!List.of("PAID", "FREE_PREVIEW", "DISABLED").contains(mapping.getBillingMode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported billing mode");
        }
        if (!List.of("VERIFIED", "ESTIMATED", "FREE_PREVIEW", "PENDING").contains(mapping.getPricingStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported pricing status");
        }
        if (mapping.getOfficialUnitPrice() == null) mapping.setOfficialUnitPrice(BigDecimal.ZERO);
        if (mapping.getCostUnitPrice() == null) mapping.setCostUnitPrice(BigDecimal.ZERO);
        if (mapping.getSaleUnitPrice() == null) mapping.setSaleUnitPrice(BigDecimal.ZERO);
        validatePrice(mapping.getOfficialUnitPrice(), "officialUnitPrice");
        validatePrice(mapping.getCostUnitPrice(), "costUnitPrice");
        validatePrice(mapping.getSaleUnitPrice(), "saleUnitPrice");
        if (mapping.getEndpointPath() != null && mapping.getEndpointPath().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpointPath is too long");
        }
        if (mapping.getTaskQueryPath() != null && mapping.getTaskQueryPath().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskQueryPath is too long");
        }
        String queryMethod = mapping.getTaskQueryMethod() == null ? "POST" : mapping.getTaskQueryMethod().toUpperCase(Locale.ROOT);
        if (!List.of("GET", "POST").contains(queryMethod)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskQueryMethod must be GET or POST");
        }
        mapping.setTaskQueryMethod(queryMethod);
    }

    private String normalizeEnumLike(String value, String fallback, int max) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.length() > max || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model catalog metadata is invalid");
        }
        return normalized;
    }

    private String normalizeCsv(String value, String fallback, int max) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.length() > max || !normalized.matches("[A-Za-z0-9._,-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model catalog metadata is invalid");
        }
        return normalized;
    }

    private void validatePrice(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is out of range");
        }
    }

    private com.transit.model.Channel redactedChannel(Long channelId) {
        if (channelId == null) {
            return null;
        }
        com.transit.model.Channel channel = channelMapper.selectById(channelId);
        channelSecretService.redact(channel);
        return channel;
    }

    private ModelMapping decorateAvailability(ModelMapping mapping) {
        com.transit.model.Channel channel = redactedChannel(mapping.getChannelId());
        mapping.setChannel(channel);
        if (!mapping.isEnabled()) {
            unavailable(mapping, "MAPPING_DISABLED", "映射未发布");
            return mapping;
        }
        if (channel == null) {
            unavailable(mapping, "CHANNEL_MISSING", "绑定渠道不存在");
            return mapping;
        }
        if (!channel.isEnabled()) {
            unavailable(mapping, "CHANNEL_DISABLED", "渠道已停用");
            return mapping;
        }
        if (!channel.isApiKeyConfigured() && !(channel.isManaged() && providerCredentials != null && providerCredentials.hasAvailable(channel))) {
            unavailable(mapping, "CREDENTIAL_MISSING", channel.isManaged() ? "托管渠道没有可调度 OAuth 账号" : "渠道未配置 API Key");
            return mapping;
        }

        String health = Objects.toString(channel.getHealthStatus(), "UNTESTED")
                .trim().toUpperCase(Locale.ROOT);
        if ("COOLDOWN".equals(health)
                && channel.getCooldownUntil() != null
                && channel.getCooldownUntil().isAfter(LocalDateTime.now())) {
            unavailable(mapping, "CHANNEL_COOLDOWN", "渠道熔断冷却中");
            return mapping;
        }
        if (!"HEALTHY".equals(health)
                && !"DEGRADED".equals(health)
                && !"COOLDOWN".equals(health)) {
            unavailable(mapping, "CHANNEL_" + health, "渠道状态为 " + health + "，请先完成测试");
            return mapping;
        }

        mapping.setCallable(true);
        mapping.setAvailabilityStatus("CALLABLE");
        mapping.setAvailabilityMessage("DEGRADED".equals(health) ? "可调用（渠道降级）" : "可供前端用户调用");
        return mapping;
    }

    private void unavailable(ModelMapping mapping, String status, String message) {
        mapping.setCallable(false);
        mapping.setAvailabilityStatus(status);
        mapping.setAvailabilityMessage(message);
    }
}
