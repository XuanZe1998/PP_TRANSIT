package com.transit.service;

import com.transit.model.Channel;
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
public class OpenAiCompatibleOnboarding {
    static final String ADAPTER = "openai-compatible";

    private final NewApiCatalogClient catalog;
    private final AdminChannelService channels;
    private final GatewaySiteService sites;
    private final TransactionTemplate transactions;

    public NewApiOnboardingService.Preview preview(NewApiOnboardingService.Request request) {
        NewApiOnboardingService.secret(request.apiKey(), true);
        String base = NewApiOnboardingService.normalizeBaseUrl(request.baseUrl());
        List<String> models = catalog.models(base, request.apiKey().trim());
        String host = URI.create(base).getHost();
        return new NewApiOnboardingService.Preview(base, host.substring(0, Math.min(100, host.length())),
                models, List.of(), List.of(), null, null);
    }

    public Map<String, Object> connect(NewApiOnboardingService.Request request) {
        List<String> models = request.models();
        if (models == null || models.isEmpty() || models.size() > 500
                || models.stream().anyMatch(id -> id == null || !NewApiCatalogClient.validModel(id))) {
            throw bad("请选择 1–500 个有效模型");
        }
        List<String> selected = List.copyOf(new LinkedHashSet<>(models));
        String modelNames = String.join("\n", selected);
        if (modelNames.length() > 2000) throw bad("所选模型名称总长度超过渠道容量，请减少选择或分批接入");
        var snapshot = preview(request);
        if (!snapshot.models().containsAll(selected)) throw bad("模型权限已变化，请重新读取模型目录");
        String name = request.name() == null || request.name().isBlank() ? snapshot.suggestedName() : request.name().trim();
        if (name.length() > 100) throw bad("渠道名称不能超过 100 个字符");
        var mappings = selected.stream().map(id -> {
            var mapping = NewApiSyncService.draft(id);
            mapping.setCapabilityTags("openai-compatible,pricing-required");
            mapping.setPricingMessage("待配置");
            return mapping;
        }).toList();

        return transactions.execute(status -> {
            Channel channel = channels.create(Channel.builder().name(name).type(ADAPTER)
                    .sourceCode(ADAPTER).sourceName("OpenAI compatible").protocolType("openai-chat")
                    .baseUrl(snapshot.baseUrl()).apiKey(request.apiKey().trim()).models(modelNames)
                    .modelPricing(mappings).enabled(false).build());
            if (request.siteId() != null) sites.attach(channel.getId(), request.siteId());
            sites.reconcile();
            if (request.publicName() != null && !request.publicName().isBlank()) {
                sites.configurePublicDisplayForChannel(channel.getId(), request.publicName());
            }
            return Map.of("channelId", channel.getId());
        });
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
