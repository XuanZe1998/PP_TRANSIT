package com.transit.service;

import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class Sub2ApiOnboardingService {
    public static final String SOURCE_CODE = "sub2api";
    private static final String KEY_GROUP = "api-key-bound";

    private final Sub2ApiCatalogClient catalog;
    private final AdminChannelService channels;
    private final Sub2ApiSyncService sync;
    private final GatewaySiteService sites;
    private final GatewaySyncJobs jobs;
    private final TransactionTemplate transactions;

    public NewApiOnboardingService.Preview preview(NewApiOnboardingService.Request request) {
        NewApiOnboardingService.secret(request.apiKey(), true);
        String base = NewApiOnboardingService.normalizeBaseUrl(request.baseUrl());
        var snapshot = catalog.fetch(base, request.apiKey());
        String host = URI.create(base).getHost();
        String suggested = snapshot.siteName() == null ? "sub2api · " + host : snapshot.siteName();
        var group = new NewApiPricing.Group(KEY_GROUP, groupDescription(snapshot),
                snapshot.effectiveRateMultiplier() == null ? java.math.BigDecimal.ONE : snapshot.effectiveRateMultiplier());
        var prices = snapshot.models().stream().map(model -> pendingPrice(model, snapshot)).toList();
        return new NewApiOnboardingService.Preview(base, trim(suggested, 100), snapshot.models(),
                List.of(group), prices,
                "sub2api 的 Key 自描述接口不暴露每模型采购价；导入后需在本站核验采购价和售价后再发布。", KEY_GROUP);
    }

    public Map<String, Object> connect(NewApiOnboardingService.Request request) {
        NewApiOnboardingService.secret(request.apiKey(), true);
        List<String> selected = selection(request.models());
        String base = NewApiOnboardingService.normalizeBaseUrl(request.baseUrl());
        var snapshot = catalog.fetch(base, request.apiKey());
        if (!snapshot.models().containsAll(selected)) throw bad("模型权限已变化，请重新读取模型目录");
        String requestedName = request.name() == null ? "" : request.name().trim();
        if (requestedName.length() > 100) throw bad("渠道名称不能超过 100 个字符");

        long channelId = transactions.execute(status -> {
            List<ModelMapping> mappings = selected.stream().map(Sub2ApiOnboardingService::draft).toList();
            String fallbackName = snapshot.siteName() == null ? "sub2api · " + URI.create(base).getHost() : snapshot.siteName();
            Channel created = channels.create(Channel.builder()
                    .name(requestedName.isBlank() ? trim(fallbackName, 100) : requestedName)
                    .sourceCode(SOURCE_CODE).sourceName("sub2api")
                    .type("openai-compatible").protocolType("multi-protocol")
                    .baseUrl(base).apiKey(request.apiKey().trim()).groupName(KEY_GROUP)
                    .models(String.join("\n", selected)).modelPricing(mappings)
                    .enabled(false).healthStatus("UNTESTED").build());
            sync.register(created.getId(), base, snapshot, request, selected);
            if (request.siteId() != null) sites.attach(created.getId(), request.siteId());
            else sites.reconcile();
            if (request.publicName() != null && !request.publicName().isBlank())
                sites.configurePublicDisplayForChannel(created.getId(), request.publicName());
            return created.getId();
        });
        return Map.of("channelId", channelId, "jobId", jobs.enqueue(channelId));
    }

    private static List<String> selection(List<String> models) {
        if (models == null || models.isEmpty() || models.size() > 500
                || models.stream().anyMatch(model -> model == null || !NewApiCatalogClient.validModel(model))) {
            throw bad("请选择 1–500 个有效模型");
        }
        List<String> selected = List.copyOf(new LinkedHashSet<>(models));
        if (String.join("\n", selected).length() > 2000) throw bad("所选模型名称总长度超过渠道容量");
        return selected;
    }

    public static ModelMapping draft(String model) {
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        String protocols = lower.contains("image") || lower.contains("seedream") ? "images"
                : lower.contains("embed") ? "embeddings"
                : lower.contains("claude") ? "chat-completions,responses,messages,count-tokens"
                : lower.contains("gemini") ? "chat-completions,responses,gemini-generate-content,gemini-stream-generate-content,gemini-count-tokens"
                : "chat-completions,responses";
        String capability = lower.contains("image") || lower.contains("seedream") ? "image"
                : lower.contains("embed") ? "embedding" : "text";
        return ModelMapping.builder().publicModelName(model).channelModelName(model).enabled(false)
                .billingEnabled(false).billingMode("DISABLED").pricingStatus("PENDING")
                .inputPricePerMillion(java.math.BigDecimal.ZERO).outputPricePerMillion(java.math.BigDecimal.ZERO)
                .vendor(ModelIdentityService.publisherCode(null, model)).capability(capability).protocols(protocols)
                .capabilityTags("sub2api,pricing-required")
                .pricingMessage("待核验 sub2api 分组的模型采购价").build();
    }

    private static NewApiPricing.Price pendingPrice(String model, Sub2ApiCatalogClient.Snapshot snapshot) {
        String rate = snapshot.effectiveRateMultiplier() == null ? "当前版本未暴露分组倍率"
                : "Key 有效计费倍率 " + snapshot.effectiveRateMultiplier().stripTrailingZeros().toPlainString();
        return new NewApiPricing.Price(model, "PENDING", rate + "；需手工核验模型单价", "UNKNOWN",
                null, null, null, null, null, null, null, null, null, null);
    }

    private static String groupDescription(Sub2ApiCatalogClient.Snapshot snapshot) {
        return snapshot.effectiveRateMultiplier() == null ? "当前推理 Key 绑定的上游分组"
                : "当前推理 Key 绑定的上游分组，有效倍率 " + snapshot.effectiveRateMultiplier();
    }

    private static String trim(String value, int max) { return value.substring(0, Math.min(max, value.length())); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
