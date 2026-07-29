package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the NVIDIA NIM catalog configured by {@code nvida.key}. All catalog
 * entries deliberately share one channel, and therefore one encrypted API key.
 * New or key-rotated mappings stay disabled until their individual production
 * probe succeeds.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class NvidiaCatalogService implements ApplicationRunner {

    static final String CHANNEL_NAME = "NVIDIA Catalog";
    static final String BASE_URL = "https://integrate.api.nvidia.com/v1";

    private static final List<NvidiaModel> CHAT_MODELS = List.of(
            model("deepseek-ai/deepseek-v4-flash", "chat,reasoning,nvidia"),
            model("deepseek-ai/deepseek-v4-pro", "chat,reasoning,nvidia"),
            model("google/diffusiongemma-26b-a4b-it", "chat,image-generation,nvidia"),
            model("google/gemma-4-31b-it", "chat,reasoning,nvidia"),
            model("meta/llama-3.1-70b-instruct", "chat,nvidia"),
            model("meta/llama-3.1-8b-instruct", "chat,nvidia"),
            model("meta/llama-3.2-11b-vision-instruct", "chat,vision,nvidia"),
            model("meta/llama-3.2-90b-vision-instruct", "chat,vision,nvidia"),
            model("meta/llama-3.3-70b-instruct", "chat,nvidia"),
            model("meta/llama-4-maverick-17b-128e-instruct", "chat,vision,nvidia"),
            model("minimaxai/minimax-m2.7", "chat,nvidia"),
            model("minimaxai/minimax-m3", "chat,vision,nvidia"),
            model("mistralai/mistral-medium-3.5-128b", "chat,reasoning,nvidia"),
            model("mistralai/mistral-small-4-119b-2603", "chat,reasoning,nvidia"),
            model("nvidia/llama-3.1-nemotron-nano-vl-8b-v1", "chat,vision,nvidia"),
            model("nvidia/llama-3.3-nemotron-super-49b-v1", "chat,reasoning,nvidia"),
            model("nvidia/llama-3.3-nemotron-super-49b-v1.5", "chat,reasoning,nvidia"),
            model("nvidia/nemotron-3-nano-30b-a3b", "chat,reasoning,nvidia"),
            model("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", "chat,vision,reasoning,nvidia"),
            model("nvidia/nemotron-3-super-120b-a12b", "chat,reasoning,nvidia"),
            model("nvidia/nemotron-3-ultra-550b-a55b", "chat,reasoning,nvidia"),
            model("nvidia/nemotron-mini-4b-instruct", "chat,nvidia"),
            model("nvidia/nemotron-nano-12b-v2-vl", "chat,vision,nvidia"),
            model("openai/gpt-oss-120b", "chat,reasoning,nvidia"),
            model("openai/gpt-oss-20b", "chat,reasoning,nvidia"),
            model("qwen/qwen3.5-122b-a10b", "chat,reasoning,nvidia"),
            model("qwen/qwen3.5-397b-a17b", "chat,reasoning,nvidia"),
            model("qwen/qwen3-next-80b-a3b-instruct", "chat,nvidia"),
            model("stepfun-ai/step-3.5-flash", "chat,nvidia"),
            model("stepfun-ai/step-3.7-flash", "chat,vision,nvidia"),
            model("z-ai/glm-5.2", "chat,reasoning,chinese,nvidia")
    );

    private final ChannelMapper channelMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final ChannelSecretService channelSecretService;
    private final AdminChannelService adminChannelService;

    @Value("${nvida.key:}")
    private String configuredKey;

    @Value("${nvida.verify-on-startup:false}")
    private boolean verifyOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.info("nvida.key is empty; NVIDIA catalog remains inactive");
            return;
        }
        Long channelId = syncCatalog();
        if (verifyOnStartup) {
            Map<String, Object> result = verifyAll(channelId);
            log.info("NVIDIA catalog verification completed: total={}, success={}, failed={}",
                    result.get("total"), result.get("success"), result.get("failed"));
        }
    }

    public Long syncCatalog() {
        String sharedKey = requireConfiguredKey();
        if (!channelSecretService.isConfigured()) {
            throw new IllegalStateException("nvida.key requires security.data-encryption-key");
        }

        Channel channel = findManagedChannel();
        boolean keyChanged = channel == null || !sameKey(channel.getApiKey(), sharedKey);
        if (channel == null) {
            channel = Channel.builder()
                    .name(CHANNEL_NAME)
                    .type("nvidia")
                    .baseUrl(BASE_URL)
                    .apiKey(channelSecretService.encrypt(sharedKey))
                    .models(modelCsv())
                    .enabled(true)
                    .groupName("nvidia")
                    .weight(100)
                    .healthStatus("UNTESTED")
                    .createdAt(LocalDateTime.now())
                    .build();
            channelMapper.insert(channel);
        } else {
            channel.setName(CHANNEL_NAME);
            channel.setType("nvidia");
            channel.setBaseUrl(BASE_URL);
            channel.setModels(modelCsv());
            channel.setEnabled(true);
            channel.setGroupName("nvidia");
            channel.setWeight(channel.getWeight() <= 0 ? 100 : channel.getWeight());
            if (keyChanged) {
                channel.setApiKey(channelSecretService.encrypt(sharedKey));
                channel.setHealthStatus("UNTESTED");
                channel.setCooldownUntil(null);
            }
            channelMapper.updateById(channel);
        }

        if (keyChanged) {
            for (ModelMapping mapping : mappingsFor(channel.getId())) {
                mapping.setEnabled(false);
                modelMappingMapper.updateById(mapping);
            }
        }
        for (NvidiaModel model : CHAT_MODELS) {
            ensureMapping(channel.getId(), model);
        }
        log.info("NVIDIA catalog synchronized on one shared-key channel with {} chat models", CHAT_MODELS.size());
        return channel.getId();
    }

    /**
     * Runs every model through the same gateway path used by customers. Each
     * result is persisted independently by AdminChannelService; only successful
     * mappings become public/routable.
     */
    public Map<String, Object> verifyAll(Long channelId) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || !"nvidia".equalsIgnoreCase(channel.getType())
                || !CHANNEL_NAME.equals(channel.getName())) {
            throw new IllegalArgumentException("Channel is not the managed NVIDIA catalog channel");
        }
        int success = 0;
        int failed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < CHAT_MODELS.size(); index++) {
            NvidiaModel model = CHAT_MODELS.get(index);
            log.info("Testing NVIDIA model {}/{}: {}", index + 1, CHAT_MODELS.size(), model.id());
            Map<String, Object> result;
            try {
                result = adminChannelService.testModel(channelId, Map.of(
                        "providerModelName", model.id(),
                        "prompt", "Reply with exactly OK.",
                        "timeoutSeconds", 120));
            } catch (RuntimeException exception) {
                result = new LinkedHashMap<>();
                result.put("model", model.id());
                result.put("status", "FAILED");
                result.put("error", exception.getMessage());
            }
            results.add(result);
            if ("SUCCESS".equalsIgnoreCase(Objects.toString(result.get("status"), ""))) {
                success++;
            } else {
                failed++;
            }
            log.info("NVIDIA model result {}/{}: model={}, status={}, latencyMs={}",
                    index + 1, CHAT_MODELS.size(), model.id(), result.get("status"), result.get("latencyMs"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("channelId", channelId);
        summary.put("total", CHAT_MODELS.size());
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("results", results);
        return summary;
    }

    static List<String> chatModelIds() {
        return CHAT_MODELS.stream().map(NvidiaModel::id).toList();
    }

    private Channel findManagedChannel() {
        return channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                        .eq(Channel::getName, CHANNEL_NAME)
                        .orderByAsc(Channel::getId))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private List<ModelMapping> mappingsFor(Long channelId) {
        return modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channelId));
    }

    private void ensureMapping(Long channelId, NvidiaModel model) {
        ModelMapping existing = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                        .eq(ModelMapping::getChannelId, channelId)
                        .eq(ModelMapping::getChannelModelName, model.id())
                        .orderByAsc(ModelMapping::getId))
                .stream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.setPublicModelName(model.id());
            existing.setCapabilityTags(model.capabilityTags());
            modelMappingMapper.updateById(existing);
            return;
        }
        ModelMapping mapping = ModelMapping.builder()
                .publicModelName(model.id())
                .channelModelName(model.id())
                .channelId(channelId)
                .priority(100)
                .enabled(false)
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
                .capabilityTags(model.capabilityTags())
                .build();
        modelMappingMapper.insert(mapping);
    }

    private boolean sameKey(String storedKey, String sharedKey) {
        if (storedKey == null || storedKey.isBlank()) {
            return false;
        }
        return Objects.equals(channelSecretService.decrypt(storedKey), sharedKey);
    }

    private String requireConfiguredKey() {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException("nvida.key is empty");
        }
        return configuredKey.trim();
    }

    private static String modelCsv() {
        return String.join(",", chatModelIds());
    }

    private static NvidiaModel model(String id, String capabilityTags) {
        return new NvidiaModel(id, capabilityTags);
    }

    private record NvidiaModel(String id, String capabilityTags) {
    }
}
