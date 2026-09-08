package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** Detects a sub2api installation without relying on its customizable brand name. */
@Service
@RequiredArgsConstructor
public class Sub2ApiCatalogClient {
    private final WebClient webClient;
    private final ChannelUrlPolicy urls;

    public record Snapshot(List<String> models, String detectionMethod, Integer billingSchemaVersion,
                           BigDecimal effectiveRateMultiplier, String siteName) { }

    public Snapshot fetch(String base, String key) {
        urls.validate(base);
        GatewaySyncProgress.phase("MODELS");
        List<String> models = NewApiCatalogClient.parseModels(get(base + "/v1/models", key));
        GatewaySyncProgress.phase("IDENTITY");
        try {
            JsonNode billing = get(base + "/v1/sub2api/billing", key);
            validateBilling(billing);
            return new Snapshot(models, "KEY_BILLING", billing.path("schema_version").asInt(),
                    positiveDecimal(billing, "effective_rate_multiplier"), null);
        } catch (RuntimeException billingUnavailable) {
            // Simple mode and older forks may not expose key billing. Their public settings
            // endpoint remains a safe, credential-free compatibility fingerprint.
            JsonNode settings = get(base + "/api/v1/settings/public", null);
            JsonNode data = settings.path("data");
            if (settings.path("code").asInt(Integer.MIN_VALUE) != 0 || !data.isObject()
                    || !data.path("version").isTextual() || data.path("version").asText().isBlank()) {
                throw new IllegalArgumentException("Upstream is not a recognized sub2api installation");
            }
            String site = data.path("site_name").asText("").trim();
            return new Snapshot(models, "PUBLIC_SETTINGS", null, null, site.isBlank() ? null : site);
        }
    }

    private JsonNode get(String url, String key) {
        var request = webClient.get().uri(url);
        if (key != null && !key.isBlank()) request.header("Authorization", bearer(key));
        return request.exchangeToMono(response -> {
            if (!response.statusCode().is2xxSuccessful()) return response.releaseBody()
                    .then(reactor.core.publisher.Mono.error(
                            new IllegalStateException("HTTP " + response.statusCode().value())));
            return response.bodyToMono(JsonNode.class);
        }).timeout(Duration.ofSeconds(20)).block();
    }

    static void validateBilling(JsonNode payload) {
        if (payload == null || !payload.isObject()
                || !"sub2api.key_billing".equals(payload.path("object").asText())
                || !payload.path("schema_version").canConvertToInt()
                || payload.path("schema_version").asInt() < 1
                || !"token".equals(payload.path("billing_scope").asText())
                || positiveDecimal(payload, "effective_rate_multiplier") == null) {
            throw new IllegalArgumentException("Invalid sub2api billing identity");
        }
    }

    private static BigDecimal positiveDecimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) return null;
        BigDecimal result = value.decimalValue();
        return result.signum() > 0 && result.compareTo(new BigDecimal("1000")) <= 0 ? result : null;
    }

    private static String bearer(String key) {
        String value = key.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value : "Bearer " + value;
    }
}
