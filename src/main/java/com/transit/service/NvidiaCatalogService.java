package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ProviderModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Discovers the complete NVIDIA catalog and verifies low-cost routes independently. */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class NvidiaCatalogService implements ApplicationRunner {
    static final String CHANNEL_NAME = "NVIDIA Catalog";
    static final String BASE_URL = "https://integrate.api.nvidia.com/v1";
    private static final String PROBE_IMAGE_URL =
            "https://assets.ngc.nvidia.com/products/api-catalog/phi-3-5-vision/example1b.jpg";

    private final ChannelMapper channelMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final ChannelSecretService channelSecretService;
    private final AdminChannelService adminChannelService;
    private final ProviderModelCatalogService providerModelCatalogService;

    @Value("${nvidia.key:}") private String configuredKey;
    @Value("${nvida.key:}") private String legacyConfiguredKey;
    @Value("${nvidia.verify-on-startup:false}") private boolean verifyOnStartup;
    @Value("${nvida.verify-on-startup:false}") private boolean legacyVerifyOnStartup;
    @Value("${nvidia.startup-verification-limit:10}") private int startupVerificationLimit;
    @Value("${model-catalog.manual-verification-only:true}") private boolean manualVerificationOnly;

    @Override
    public void run(ApplicationArguments args) {
        if (effectiveKey().isBlank()) {
            log.info("nvidia.key is empty; NVIDIA catalog remains inactive");
            return;
        }
        try {
            syncCatalog();
        } catch (RuntimeException error) {
            // The provider catalog is a cache. A temporary upstream outage must not prevent
            // the gateway from starting or invalidate the last successfully synchronized rows.
            log.warn("NVIDIA startup catalog synchronization failed; retained previous catalog: {}",
                    error.getMessage());
        }
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void verifyAfterStartup() {
        if (manualVerificationOnly || !(verifyOnStartup || legacyVerifyOnStartup) || effectiveKey().isBlank()) return;
        Channel channel = findManagedChannel();
        if (channel == null) {
            log.info("Skipped NVIDIA startup verification because no managed channel is available");
            return;
        }
        Long channelId = channel.getId();
        Map<String, Object> result = verifyBatch(channelId, Math.max(1, Math.min(startupVerificationLimit, 50)));
        log.info("NVIDIA startup catalog verification completed: total={}, success={}, failed={}",
                result.get("total"), result.get("success"), result.get("failed"));
    }

    public Long syncCatalog() {
        String sharedKey = requireConfiguredKey();
        if (!channelSecretService.isConfigured()) {
            throw new IllegalStateException("nvidia.key requires security.data-encryption-key");
        }
        Channel channel = findManagedChannel();
        boolean keyChanged = channel == null || !sameKey(channel.getApiKey(), sharedKey);
        if (channel == null) {
            channel = Channel.builder().name(CHANNEL_NAME).type("nvidia")
                    .sourceCode("nvidia").sourceName("NVIDIA").protocolType("openai-chat")
                    .baseUrl(BASE_URL).apiKey(channelSecretService.encrypt(sharedKey)).models(null)
                    .enabled(true).groupName("nvidia").weight(100).healthStatus("UNTESTED")
                    .createdAt(LocalDateTime.now()).build();
            channelMapper.insert(channel);
        } else {
            channel.setName(CHANNEL_NAME); channel.setType("nvidia"); channel.setSourceCode("nvidia");
            channel.setSourceName("NVIDIA"); channel.setProtocolType("openai-chat"); channel.setBaseUrl(BASE_URL);
            channel.setModels(null); channel.setEnabled(true); channel.setGroupName("nvidia");
            channel.setWeight(channel.getWeight() <= 0 ? 100 : channel.getWeight());
            if (keyChanged) {
                channel.setApiKey(channelSecretService.encrypt(sharedKey));
                channel.setHealthStatus("UNTESTED"); channel.setCooldownUntil(null);
            }
            channelMapper.updateById(channel);
        }
        if (keyChanged) {
            modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                    .eq(ModelMapping::getChannelId, channel.getId())).forEach(mapping -> {
                mapping.setEnabled(false); modelMappingMapper.updateById(mapping);
            });
            providerModelCatalogService.invalidateSourceAvailability("nvidia",
                    "NVIDIA Key 已更换，等待重新验证");
        }
        providerModelCatalogService.ensureNvidiaBootstrapSnapshot(channel.getId());
        int total = providerModelCatalogService.synchronizeNvidia(channel.getId(), sharedKey).size();
        log.info("NVIDIA full catalog synchronized on one shared-key channel: {} models", total);
        return channel.getId();
    }

    /** Compatibility endpoint now verifies a bounded batch instead of blocking on the entire provider catalog. */
    public Map<String, Object> verifyAll(Long channelId) {
        return verifyBatch(channelId, 20);
    }

    public Map<String, Object> verifyBatch(Long channelId, int limit) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || !"nvidia".equalsIgnoreCase(channel.getSourceCode())
                || !CHANNEL_NAME.equals(channel.getName())) {
            throw new IllegalArgumentException("Channel is not the managed NVIDIA catalog channel");
        }
        List<ProviderModel> candidates = providerModelCatalogService.listBySource("nvidia").stream()
                .filter(model -> model.getProtocols().contains("chat-completions"))
                .filter(model -> !"AVAILABLE".equals(model.getVerificationStatus()))
                .filter(model -> !"RETIRED".equals(model.getVerificationStatus()))
                .sorted(Comparator.comparingInt((ProviderModel model) -> verificationRank(model.getVerificationStatus()))
                        .thenComparing(ProviderModel::getUpstreamModelName))
                .limit(Math.max(1, Math.min(limit, 100))).toList();
        int success = 0;
        int failed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ProviderModel model = candidates.get(index);
            providerModelCatalogService.beginVerification(model.getId());
            log.info("Testing NVIDIA catalog model {}/{}: {}", index + 1, candidates.size(), model.getUpstreamModelName());
            Map<String, Object> result;
            try {
                result = adminChannelService.testModel(channelId, probeOptions(model));
            } catch (RuntimeException exception) {
                result = new LinkedHashMap<>();
                result.put("model", model.getUpstreamModelName()); result.put("status", "FAILED");
                result.put("error", exception.getMessage());
            }
            results.add(result);
            boolean ok = "SUCCESS".equalsIgnoreCase(Objects.toString(result.get("status"), ""));
            providerModelCatalogService.completeVerification(model.getId(), ok,
                    ok ? "低成本连通性验证成功" : Objects.toString(result.get("error"), "上游验证失败"));
            if (ok) success++; else failed++;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("channelId", channelId); summary.put("total", candidates.size());
        summary.put("success", success); summary.put("failed", failed); summary.put("results", results);
        return summary;
    }

    private Channel findManagedChannel() {
        return channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getName, CHANNEL_NAME).orderByAsc(Channel::getId)).stream().findFirst().orElse(null);
    }

    private Map<String, Object> probeOptions(ProviderModel model) {
        String id = model.getUpstreamModelName();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("providerModelName", id); options.put("prompt", "Reply with exactly OK.");
        options.put("timeoutSeconds", 180); options.put("temperature", id.contains("mistral") ? 0.6 : 1.0);
        options.put("topP", id.contains("mistral") ? 0.7 : 0.95); options.put("maxTokens", 32);
        if (id.contains("deepseek")) options.put("chatTemplateKwargs", Map.of("thinking", true, "reasoning_effort", "high"));
        if ("vision".equals(model.getCapability())) options.put("imageUrl", PROBE_IMAGE_URL);
        return options;
    }

    private boolean sameKey(String storedKey, String sharedKey) {
        if (storedKey == null || storedKey.isBlank()) return false;
        try { return Objects.equals(channelSecretService.decrypt(storedKey), sharedKey); }
        catch (IllegalStateException staleCiphertext) {
            log.warn("NVIDIA managed credential cannot be decrypted; re-encrypting it with the configured master key");
            return false;
        }
    }

    private String requireConfiguredKey() {
        String key = effectiveKey();
        if (key.isBlank()) throw new IllegalStateException("nvidia.key is empty");
        return key;
    }

    private String effectiveKey() {
        if (configuredKey != null && !configuredKey.isBlank()) return configuredKey.trim();
        return legacyConfiguredKey == null ? "" : legacyConfiguredKey.trim();
    }

    private int verificationRank(String status) {
        return "DISCOVERED".equals(status) ? 0 : "VERIFYING".equals(status) ? 1 : 2;
    }
}
