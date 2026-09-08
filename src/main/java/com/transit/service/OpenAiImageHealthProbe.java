package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.model.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClientRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Performs a real, bounded OpenAI Images request without retaining generated media. */
@Component
@RequiredArgsConstructor
public class OpenAiImageHealthProbe {
    private final WebClient webClient;
    private final ChannelUrlPolicy channelUrlPolicy;

    public Map<String, Object> probe(Channel channel, String model, int timeoutSeconds) {
        long started = System.currentTimeMillis();
        try {
            channelUrlPolicy.validate(channel.getBaseUrl());
            JsonNode response = webClient.post()
                    .uri(endpoint(channel.getBaseUrl()))
                    .httpRequest(request -> {
                        HttpClientRequest nativeRequest = request.getNativeRequest();
                        nativeRequest.responseTimeout(Duration.ofSeconds(timeoutSeconds));
                    })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.getApiKey().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", model,
                            "prompt", "A plain blue square centered on a white background.",
                            "size", "1024x1024",
                            "n", 1))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();
            JsonNode item = response == null ? null : response.path("data").path(0);
            if (item == null || item.isMissingNode()
                    || (item.path("url").asText("").isBlank() && item.path("b64_json").asText("").isBlank())) {
                return failed("FAILED", started, model, "Provider returned no generated image", 1);
            }
            return success(started, model);
        } catch (RuntimeException error) {
            TimeoutException timeout = cause(error, TimeoutException.class);
            if (timeout != null || isReadTimeout(error)) {
                return failed("TIMEOUT", started, model, "Provider image probe timed out", 124);
            }
            WebClientResponseException response = cause(error, WebClientResponseException.class);
            if (response != null) {
                int code = response.getStatusCode().value();
                String status = code == 401 || code == 403 ? "AUTH_FAILED"
                        : code == 429 ? "RATE_LIMITED" : "UPSTREAM_ERROR";
                return failed(status, started, model, "Provider image endpoint returned HTTP " + code, code);
            }
            Throwable root = rootCause(error);
            return failed("FAILED", started, model,
                    trim(Objects.toString(root.getMessage(), root.getClass().getSimpleName()), 500), 1);
        }
    }

    static String endpoint(String baseUrl) {
        String endpoint = baseUrl.trim().replaceAll("/+$", "");
        return endpoint.endsWith("/v1") ? endpoint + "/images/generations"
                : endpoint + "/v1/images/generations";
    }

    private Map<String, Object> success(long started, String model) {
        Map<String, Object> result = base("SUCCESS", started, model, 0);
        result.put("sampleText", "Image generation response accepted");
        result.put("error", "");
        return result;
    }

    private Map<String, Object> failed(String status, long started, String model, String error, int exitCode) {
        Map<String, Object> result = base(status, started, model, exitCode);
        result.put("sampleText", "");
        result.put("error", error == null ? "" : error);
        return result;
    }

    private Map<String, Object> base(String status, long started, String model, int exitCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("latencyMs", System.currentTimeMillis() - started);
        result.put("model", model);
        result.put("usage", Map.of("promptTokens", 0, "completionTokens", 0, "cachedTokens", 0,
                "cacheReadTokens", 0, "cacheWriteTokens", 0));
        result.put("exitCode", exitCode);
        return result;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static <T extends Throwable> T cause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }

    private static boolean isReadTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if ("ReadTimeoutException".equals(current.getClass().getSimpleName())) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String trim(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(max, value.length()));
    }
}
