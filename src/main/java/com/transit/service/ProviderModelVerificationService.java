package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.mapper.ChannelMapper;
import com.transit.model.Channel;
import com.transit.model.ProviderModel;
import com.transit.provider.HaoeeProtocolClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProviderModelVerificationService {
    private static final Set<String> PAID_CAPABILITIES = Set.of("image", "video", "music", "speech", "transcription");
    private final ProviderModelCatalogService catalogService;
    private final ChannelMapper channelMapper;
    private final ChannelSecretService channelSecretService;
    private final AdminChannelService adminChannelService;
    private final HaoeeProtocolClient haoeeProtocolClient;
    private final ObjectMapper objectMapper;

    @Value("${model-catalog.manual-verification-only:true}")
    private boolean manualVerificationOnly;

    public List<Long> queue(String source, int limit, boolean allowPaid) {
        List<Long> ids = new ArrayList<>();
        List<ProviderModel> candidates = catalogService.list(source, null).stream()
                .sorted(Comparator.comparingInt((ProviderModel model) -> verificationRank(model.getVerificationStatus()))
                        .thenComparing(ProviderModel::getUpstreamModelName))
                .toList();
        for (ProviderModel model : candidates) {
            if ("AVAILABLE".equals(model.getVerificationStatus()) || "RETIRED".equals(model.getVerificationStatus())) continue;
            if ("VERIFYING".equals(model.getVerificationStatus()) && model.getUpdatedAt() != null
                    && model.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(30))) continue;
            if (!allowPaid && PAID_CAPABILITIES.contains(model.getCapability())) continue;
            if (!verifiable(model)) continue;
            catalogService.beginVerification(model.getId());
            ids.add(model.getId());
            if (ids.size() >= Math.max(1, Math.min(limit, 100))) break;
        }
        return ids;
    }

    public List<Long> queueOne(long id, boolean allowPaid) {
        ProviderModel model = catalogService.require(id);
        if (PAID_CAPABILITIES.contains(model.getCapability()) && !allowPaid) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "This model can incur image, audio or task charges; set allowPaid=true to confirm");
        }
        if (!verifiable(model)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "No safe verification template exists for this protocol");
        }
        catalogService.beginVerification(id);
        return List.of(id);
    }

    @Async
    public void verifyQueuedAsync(List<Long> ids, boolean allowPaid) {
        for (Long id : ids) verify(id, allowPaid);
    }

    /** Progresses the low-cost backlog and retries failures without touching paid media models. */
    @Scheduled(cron = "${model-catalog.verification-retry-cron:0 37 3 * * *}")
    public void retryLowCostModelsDaily() {
        if (manualVerificationOnly) return;
        for (String source : List.of("nvidia", "haoee")) {
            List<Long> ids = queue(source, 10, false);
            for (Long id : ids) verify(id, false);
        }
    }

    public Map<String, Object> verify(long id, boolean allowPaid) {
        ProviderModel model = catalogService.require(id);
        Channel channel = channelMapper.selectOne(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getSourceCode, model.getSourceCode()).orderByAsc(Channel::getId).last("LIMIT 1"));
        if (channel == null) {
            catalogService.completeVerification(id, false, "没有配置该来源渠道");
            return Map.of("id", id, "status", "FAILED");
        }
        if (PAID_CAPABILITIES.contains(model.getCapability()) && !allowPaid) {
            catalogService.completeVerification(id, false, "高费用模型需要管理员确认");
            return Map.of("id", id, "status", "FAILED");
        }
        try {
            Map<String, Object> result;
            if (model.getProtocols().contains("chat-completions")) {
                result = adminChannelService.testModel(channel.getId(), Map.of(
                        "providerModelName", model.getUpstreamModelName(), "prompt", "Reply with exactly OK.",
                        "timeoutSeconds", 180, "maxTokens", 32));
            } else {
                channelSecretService.reveal(channel);
                ObjectNode body = probeBody(model);
                String path = probePath(model);
                haoeeProtocolClient.invoke(channel, model.getUpstreamModelName(), path, HttpMethod.POST, body)
                        .timeout(Duration.ofSeconds(60)).block();
                result = new LinkedHashMap<>(); result.put("status", "SUCCESS"); result.put("model", model.getUpstreamModelName());
            }
            boolean success = "SUCCESS".equalsIgnoreCase(Objects.toString(result.get("status"), ""));
            catalogService.completeVerification(id, success,
                    success ? "低成本连通性验证成功" : Objects.toString(result.get("error"), "上游验证失败"));
            return result;
        } catch (RuntimeException error) {
            catalogService.completeVerification(id, false, rootMessage(error));
            return Map.of("id", id, "status", "FAILED", "error", rootMessage(error));
        }
    }

    private boolean verifiable(ProviderModel model) {
        String protocols = model.getProtocols();
        return protocols.contains("chat-completions") || protocols.contains("responses")
                || protocols.contains("embeddings") || protocols.contains("reranks")
                || protocols.contains("images") || protocols.contains("tasks");
    }

    private String probePath(ProviderModel model) {
        if (model.getEndpointPath() != null && !model.getEndpointPath().isBlank()) return model.getEndpointPath();
        if (model.getProtocols().contains("responses")) return "/v1/responses";
        if (model.getProtocols().contains("embeddings")) return "/compatible-mode/v1/embeddings";
        if (model.getProtocols().contains("reranks")) return "/compatible-api/v1/reranks";
        if (model.getProtocols().contains("images")) return "/v1/images/generations";
        if (model.getProtocols().contains("tasks")) return "/v1/tasks";
        throw new IllegalArgumentException("No probe endpoint configured");
    }

    private ObjectNode probeBody(ProviderModel model) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model.getUpstreamModelName());
        if (model.getProtocols().contains("responses")) {
            body.put("input", "Reply with exactly OK."); body.put("max_output_tokens", 16);
        } else if (model.getProtocols().contains("embeddings")) {
            body.put("input", "catalog health check");
        } else if (model.getProtocols().contains("reranks")) {
            body.put("query", "catalog health check");
            body.putArray("documents").add("catalog health check").add("unrelated text");
        } else if (model.getProtocols().contains("images")) {
            body.put("prompt", "A simple gray square on a white background");
            body.put("n", 1);
        } else if (model.getProtocols().contains("tasks")) {
            body.put("prompt", "A calm ocean at sunrise");
        }
        return body;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = Objects.toString(current.getMessage(), current.getClass().getSimpleName());
        return message.substring(0, Math.min(1000, message.length()));
    }

    private int verificationRank(String status) {
        return "DISCOVERED".equals(status) ? 0 : "VERIFYING".equals(status) ? 1 : 2;
    }
}
