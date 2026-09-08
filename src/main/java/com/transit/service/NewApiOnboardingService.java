package com.transit.service;

import com.transit.model.Channel;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.LinkedHashSet;

@Service
@RequiredArgsConstructor
public class NewApiOnboardingService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private GatewaySiteService sites;
    private final NewApiCatalogClient catalog;
    private final NewApiPricing pricing;
    private final AdminChannelService channels;
    private final NewApiSyncService sync;
    private final UpstreamDisplayMappingService displayMappings;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public record Request(String baseUrl,
                          @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String apiKey,
                          String name, List<String> models, String upstreamGroup,
                          java.math.BigDecimal saleMarkup, java.math.BigDecimal unitUsd,
                          @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String pricingAccessToken,
                          Long pricingUserId, Boolean autoSync, Boolean addNewModels, Boolean updatePrices,
                          String publicName, Long siteId) {
        public Request(String baseUrl, String apiKey, String name, List<String> models, String upstreamGroup,
                       java.math.BigDecimal saleMarkup, java.math.BigDecimal unitUsd, String pricingAccessToken,
                       Long pricingUserId, Boolean autoSync, Boolean addNewModels, Boolean updatePrices, String publicName) {
            this(baseUrl,apiKey,name,models,upstreamGroup,saleMarkup,unitUsd,pricingAccessToken,pricingUserId,autoSync,addNewModels,updatePrices,publicName,null);
        }
        public Request(String baseUrl, String apiKey, String name, List<String> models, String upstreamGroup,
                       java.math.BigDecimal saleMarkup, java.math.BigDecimal unitUsd, String pricingAccessToken,
                       Long pricingUserId, Boolean autoSync, Boolean addNewModels, Boolean updatePrices) {
            this(baseUrl, apiKey, name, models, upstreamGroup, saleMarkup, unitUsd, pricingAccessToken,
                    pricingUserId, autoSync, addNewModels, updatePrices, null);
        }
        public Request(String baseUrl, String apiKey, String name, List<String> models) {
            this(baseUrl, apiKey, name, models, null, null, null, null, null, true, true, true);
        }
        @Override public String toString() { return "NewApiOnboardingRequest[redacted]"; }
    }
    public record Preview(String baseUrl, String suggestedName, List<String> models,
                          List<NewApiPricing.Group> groups, List<NewApiPricing.Price> prices,
                          String pricingWarning, String selectedGroup) {}

    public Preview preview(Request request) {
        String base = normalizeBaseUrl(request.baseUrl());
        validate(request);
        var snapshot = catalog.fetch(base, request.apiKey().trim(), request.pricingAccessToken(), request.pricingUserId());
        if (!snapshot.modelsComplete()) throw bad(snapshot.modelError());
        return toPreview(request, base, snapshot);
    }

    private Preview toPreview(Request request, String base, NewApiCatalogClient.Snapshot snapshot) {
        var groups = pricing.groups(snapshot.pricing());
        String group = request.upstreamGroup() == null ? "" : request.upstreamGroup().trim();
        var selected = groups.stream().filter(item -> item.name().equals(group)).findFirst().orElse(null);
        var rows = pricing.rows(snapshot.pricing());
        var visibleModels = snapshot.models().stream().filter(model -> group.isBlank() || !snapshot.pricingComplete()
                || pricing.inGroup(rows.get(model), group)).toList();
        var prices = visibleModels.stream().map(model -> pricing.calculate(model, rows.get(model), group,
                selected == null ? null : selected.ratio(), markup(request), unitUsd(request))).toList();
        String suggestion = "New API · " + URI.create(base).getHost();
        return new Preview(base, suggestion.substring(0, Math.min(100, suggestion.length())), visibleModels,
                groups, prices, snapshot.pricingError(), group);
    }

    public Channel connect(Request request) {
        validate(request);
        if (request.models() == null || request.models().isEmpty() || request.models().size() > 500
                || request.models().stream().anyMatch(model -> model == null || !NewApiCatalogClient.validModel(model))) {
            throw bad("请选择 1–500 个有效模型");
        }
        List<String> selected = List.copyOf(new LinkedHashSet<>(request.models()));
        if (String.join("\n", selected).length() > 2000) throw bad("所选模型名称总长度超过渠道容量，请减少选择或分批接入");
        String name = request.name() == null ? "" : request.name().trim();
        if (name.length() > 100) throw bad("渠道名称不能超过 100 个字符");
        String base = normalizeBaseUrl(request.baseUrl());
        var snapshot = catalog.fetch(base, request.apiKey().trim(), request.pricingAccessToken(), request.pricingUserId());
        if (!snapshot.modelsComplete()) throw bad(snapshot.modelError());
        if (!snapshot.models().containsAll(selected)) throw bad("模型权限已变化，请重新读取模型目录");
        String group = request.upstreamGroup() == null ? "" : request.upstreamGroup().trim();
        if (group.isEmpty()) throw bad("请确认该推理 Key 的上游分组");
        if (snapshot.pricingComplete() && !pricing.groupExists(snapshot.pricing(), group))
            throw bad("所选上游分组已不存在或不可见，请重新读取");
        var preview = toPreview(request, base, snapshot);
        if (!preview.models().containsAll(selected)) throw bad("所选模型不在该分组中，请更新报价后重选");
        var priceMap = preview.prices().stream().collect(java.util.stream.Collectors.toMap(NewApiPricing.Price::model, p -> p));
        return transactions.execute(status -> {
            var mappings = selected.stream().map(model -> {
                var mapping = NewApiSyncService.draft(model);
                pricing.apply(mapping, priceMap.get(model), base, group);
                return mapping;
            }).toList();
            boolean anyImage = mappings.stream().anyMatch(mapping -> "image".equalsIgnoreCase(mapping.getCapability()));
            boolean anyOther = mappings.stream().anyMatch(mapping -> !"image".equalsIgnoreCase(mapping.getCapability()));
            String protocol = anyImage && !anyOther ? "openai-image" : anyImage ? "multi-protocol" : "openai-chat";
            Channel channel = channels.create(Channel.builder()
                    .name(name.isEmpty() ? preview.suggestedName() : name)
                    .type("openai-compatible").protocolType(protocol)
                    .sourceCode("new-api").sourceName("New API")
                    .baseUrl(base).apiKey(request.apiKey().trim())
                    .models(String.join("\n", selected)).modelPricing(mappings)
                    .enabled(false).healthStatus("UNTESTED").build());
            sync.register(channel, request, preview.models());
            if(sites!=null) {
                if(request.siteId()!=null) sites.attach(channel.getId(),request.siteId());
                sites.reconcile();
                if(request.publicName()!=null&&!request.publicName().isBlank())
                    sites.configurePublicDisplayForChannel(channel.getId(),request.publicName());
            } else if (request.publicName() != null && !request.publicName().isBlank()) {
                // Compatibility for isolated callers that do not provide site management.
                var display = new com.transit.model.UpstreamDisplayMapping();
                display.setPublicCode("new-api-" + channel.getId());
                display.setPublicName(request.publicName().trim());
                displayMappings.save(channel.getId(), display);
            }
            return channel;
        });
    }

    static java.math.BigDecimal markup(Request request) { return positive(request.saleMarkup(), new java.math.BigDecimal("1.2"), "销售倍率"); }
    static java.math.BigDecimal unitUsd(Request request) { return positive(request.unitUsd(), java.math.BigDecimal.ONE, "报价单位折合 USD"); }
    static java.math.BigDecimal positive(java.math.BigDecimal value, java.math.BigDecimal fallback, String label) {
        var actual = value == null ? fallback : value;
        if (actual.signum() <= 0 || actual.compareTo(new java.math.BigDecimal("1000")) > 0 || actual.scale() > 8)
            throw bad(label + "必须大于 0、不超过 1000，最多 8 位小数");
        return actual;
    }
    private void validate(Request request) {
        secret(request.apiKey(), true);
        secret(request.pricingAccessToken(), false);
        if (request.pricingUserId() != null && request.pricingUserId() <= 0) throw bad("账号 ID 不正确");
        if (request.upstreamGroup() != null && request.upstreamGroup().length() > 160) throw bad("上游分组名称过长");
        if (request.publicName() != null && request.publicName().trim().length() > 120)
            throw bad("前台公开渠道名不能超过 120 个字符");
        markup(request); unitUsd(request);
    }
    static void secret(String value, boolean required) {
        if (value == null || value.isBlank()) { if (required) throw bad("请填写上游推理 API Key"); return; }
        if (value.length() > 8192 || value.chars().anyMatch(Character::isISOControl)) throw bad("凭据格式不正确");
    }

    static String normalizeBaseUrl(String value) {
        String base = value == null ? "" : value.trim().replaceAll("/+$", "");
        if (!base.contains("://")) base = "https://" + base;
        try {
            URI uri = URI.create(base);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || base.length() > 450
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw bad("请输入站点地址，不要包含账号、查询参数或片段");
            }
            if (base.endsWith("/v1/chat/completions")) base = base.substring(0, base.length() - 20);
            else if (base.endsWith("/v1/models")) base = base.substring(0, base.length() - 10);
            else if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
            return base;
        } catch (IllegalArgumentException e) {
            throw bad("站点地址格式不正确");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
