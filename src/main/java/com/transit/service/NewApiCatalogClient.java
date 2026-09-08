package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.*;

/** Strict, bounded snapshots: never use a partial/error document as a deletion signal. */
@Service
@RequiredArgsConstructor
public class NewApiCatalogClient {
    private final WebClient webClient;
    private final ChannelUrlPolicy urls;
    public record Snapshot(List<String> models, JsonNode pricing, String modelError, String pricingError) {
        public boolean modelsComplete() { return models != null; }
        public boolean pricingComplete() { return pricing != null; }
    }

    public List<String> models(String base,String key) { urls.validate(base); return parseModels(get(base+"/v1/models",key,null)); }
    static String haoeeModelsEndpoint(String base) {
        String normalized = base.replaceAll("/+$", "");
        return normalized.endsWith("/v1") ? normalized + "/models/" : normalized + "/v1/models/";
    }
    public List<String> haoeeModels(String base,String key) {
        urls.validate(base);
        return parseModels(get(haoeeModelsEndpoint(base), key, null));
    }
    public Snapshot fetch(String base, String key, String pricingToken, Long userId) {
        urls.validate(base);
        List<String> models = null;
        JsonNode pricing = null;
        String modelError = null, pricingError = null;
        GatewaySyncProgress.phase("MODELS");
        try { models = parseModels(get(base + "/v1/models", key, null)); }
        catch (RuntimeException e) { modelError = safeError("模型目录", e); }
        GatewaySyncProgress.phase("PRICING");
        try { pricing = parsePricing(get(base + "/api/pricing", pricingToken, userId)); }
        catch (RuntimeException e) { pricingError = safeError("价格目录", e); }
        return new Snapshot(models, pricing, modelError, pricingError);
    }

    private static String safeError(String stage, RuntimeException error) {
        var failure = GatewaySyncJobs.classify(error);
        return stage + "：" + failure.message() + (failure.httpStatus()==null?"":" HTTP " + failure.httpStatus());
    }

    private JsonNode get(String url, String token, Long userId) {
        var request = webClient.get().uri(url);
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        if (userId != null) request.header("New-Api-User", userId.toString());
        return request.exchangeToMono(response -> {
            if (!response.statusCode().is2xxSuccessful()) return response.releaseBody()
                    .then(reactor.core.publisher.Mono.error(new IllegalStateException("HTTP " + response.statusCode().value())));
            return response.bodyToMono(JsonNode.class);
        }).timeout(Duration.ofSeconds(20)).block();
    }

    static List<String> parseModels(JsonNode payload) {
        complete(payload);
        if (!payload.path("data").isArray()) throw new IllegalArgumentException("Invalid models");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode row : payload.get("data")) {
            String id = row.path("id").asText("");
            if (!validModel(id) || !ids.add(id)) throw new IllegalArgumentException("Invalid model ID");
        }
        return List.copyOf(ids);
    }

    static JsonNode parsePricing(JsonNode payload) {
        complete(payload);
        if (!payload.path("success").isBoolean() || !payload.path("success").asBoolean()
                || !payload.path("data").isArray() || !payload.path("usable_group").isObject()
                || !payload.path("group_ratio").isObject()) throw new IllegalArgumentException("Invalid pricing");
        Set<String> names = new HashSet<>();
        for (JsonNode row : payload.get("data")) {
            String name = row.path("model_name").asText("");
            if (!validModel(name) || !names.add(name) || !row.path("enable_groups").isArray())
                throw new IllegalArgumentException("Invalid price row");
            for (JsonNode group : row.get("enable_groups"))
                if (!group.isTextual() || group.asText().isBlank()) throw new IllegalArgumentException("Invalid group");
        }
        return payload;
    }

    private static void complete(JsonNode p) {
        if (p == null || !p.isObject() || p.hasNonNull("error") || (p.has("success") && !p.path("success").asBoolean())
                || p.path("has_more").asBoolean() || p.path("hasMore").asBoolean()
                || p.path("truncated").asBoolean() || !p.path("next_cursor").asText("").isBlank()
                || !p.path("nextCursor").asText("").isBlank()
                || !p.path("next").asText("").isBlank()
                || p.path("data").size() > 10000
                || p.path("total").asInt(0) > p.path("data").size()
                || p.path("total_count").asInt(0) > p.path("data").size()
                || p.path("pagination").path("total").asInt(0) > p.path("data").size())
            throw new IllegalArgumentException("Incomplete catalog");
    }
    static boolean validModel(String id) { return id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"); }
}
