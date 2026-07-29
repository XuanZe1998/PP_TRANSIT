package com.transit.service;

import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.ModelMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminModelService {

    private final ModelMappingMapper modelMappingMapper;
    private final ChannelMapper channelMapper;
    private final ChannelSecretService channelSecretService;

    public List<ModelMapping> list() {
        return modelMappingMapper.selectList(null).stream()
                .peek(mapping -> mapping.setChannel(redactedChannel(mapping.getChannelId())))
                .toList();
    }

    public ModelMapping create(ModelMapping mapping) {
        normalize(mapping);
        modelMappingMapper.insert(mapping);
        mapping.setChannel(redactedChannel(mapping.getChannelId()));
        return mapping;
    }

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
        normalize(mapping);
        modelMappingMapper.updateById(mapping);
        mapping.setChannel(redactedChannel(mapping.getChannelId()));
        return mapping;
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
        mapping.setChannel(redactedChannel(channelId));
        return mapping;
    }

    public void delete(Long id) {
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
    }

    private void validatePrice(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is out of range");
        }
    }

    private com.transit.model.Channel redactedChannel(Long channelId) {
        com.transit.model.Channel channel = channelMapper.selectById(channelId);
        channelSecretService.redact(channel);
        return channel;
    }
}
