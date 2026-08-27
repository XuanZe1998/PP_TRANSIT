package com.transit.service.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.service.CreativePlatformConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class SeedanceCreativeProvider implements CreativeVideoProvider {
    private static final String TASK_PATH = "/api/v3/contents/generations/tasks";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CreativePlatformConfigService platformConfigs;

    @Override
    public String key() {
        return "seedance";
    }

    @Override
    public boolean isConfigured() {
        return platformConfigs.platformAccess("VIDEO", false) != null;
    }

    @Override
    public Map<String, Object> catalog() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key());
        result.put("label", "Seedance 视频创作");
        result.put("configured", isConfigured());
        CreativeProviderAccess access = platformConfigs.platformAccess("VIDEO", false);
        result.put("defaultModel", access == null ? "" : access.defaultModel());
        result.put("models", access == null ? List.of() : access.models().stream()
                .map(item -> model(item, item, "管理员配置的视频模型", item.equals(access.defaultModel()))).toList());
        result.put("capabilities", List.of("text_to_video", "image_to_video", "first_last_frame", "storyboard", "video_extend"));
        return result;
    }

    @Override
    public CreativeProviderSubmission submit(CreativeGenerationRequest request) {
        requireConfigured();
        return submit(request, platformAccess());
    }

    @Override
    public CreativeProviderSubmission submit(CreativeGenerationRequest request, CreativeProviderAccess access) {
        requireAccess(access);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", StringUtils.hasText(request.model()) ? request.model() : access.defaultModel());
        ArrayNode content = body.putArray("content");
        content.addObject().put("type", "text").put("text", request.prompt());
        appendImage(content, request.firstFrameUrl(), "first_frame");
        appendImage(content, request.lastFrameUrl(), "last_frame");
        for (String url : request.referenceImageUrls()) {
            appendImage(content, url, "reference_image");
        }
        body.put("ratio", request.ratio());
        body.put("duration", request.duration());
        body.put("resolution", request.resolution());
        body.put("generate_audio", request.generateAudio());
        body.put("return_last_frame", true);

        JsonNode response = requestPost(access, TASK_PATH, body);
        String taskId = response.path("id").asText();
        if (!StringUtils.hasText(taskId)) {
            throw providerFailure(response, "Seedance did not return a task ID");
        }
        return new CreativeProviderSubmission(taskId);
    }

    @Override
    public CreativeProviderTaskState fetch(String providerTaskId) {
        requireConfigured();
        return fetch(providerTaskId, platformAccess());
    }

    @Override
    public CreativeProviderTaskState fetch(String providerTaskId, CreativeProviderAccess access) {
        requireAccess(access);
        JsonNode response;
        try {
            response = webClient.get()
                    .uri(endpoint(access.baseUrl(), TASK_PATH + "/" + providerTaskId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + access.apiKey().trim())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(timeout());
        } catch (WebClientResponseException exception) {
            throw upstreamFailure("查询任务", exception);
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException statusException) throw statusException;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Seedance task query failed", exception);
        }
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Seedance task query returned no response");
        }
        JsonNode content = response.path("content");
        JsonNode error = response.path("error");
        String errorMessage = error.isTextual() ? error.asText() : error.path("message").asText();
        if (!StringUtils.hasText(errorMessage)) {
            errorMessage = response.path("message").asText();
        }
        return new CreativeProviderTaskState(
                normalizeStatus(response.path("status").asText()),
                content.path("video_url").asText(null),
                content.path("cover_url").asText(content.path("thumbnail_url").asText(null)),
                content.path("last_frame_url").asText(null),
                StringUtils.hasText(errorMessage) ? errorMessage : null
        );
    }

    @Override
    public Map<String, Object> testConnection(CreativeProviderAccess access) {
        requireAccess(access);
        long startedAt = System.nanoTime();
        try {
            webClient.get()
                    .uri(endpoint(access.baseUrl(), TASK_PATH) + "?page_num=1&page_size=1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + access.apiKey().trim())
                    .retrieve()
                    .toBodilessEntity()
                    .block(timeout());
        } catch (WebClientResponseException exception) {
            throw upstreamFailure("测试连接", exception);
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException statusException) throw statusException;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Seedance connection test failed", exception);
        }
        return Map.of(
                "ok", true,
                "provider", key(),
                "latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                "message", "连接成功，API Key 和任务接口可用"
        );
    }

    private Map<String, Object> model(String key, String label, String description, boolean featured) {
        return Map.of("key", key, "label", label, "description", description, "featured", featured);
    }

    private void appendImage(ArrayNode content, String url, String role) {
        if (!StringUtils.hasText(url)) return;
        ObjectNode image = content.addObject();
        image.put("type", "image_url");
        image.putObject("image_url").put("url", url);
        image.put("role", role);
    }

    private JsonNode requestPost(CreativeProviderAccess access, String path, ObjectNode body) {
        try {
            JsonNode response = webClient.post()
                    .uri(endpoint(access.baseUrl(), path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + access.apiKey().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(timeout());
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Seedance returned an empty response");
            }
            return response;
        } catch (WebClientResponseException exception) {
            throw upstreamFailure("提交任务", exception);
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException statusException) throw statusException;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Seedance task submission failed", exception);
        }
    }

    private ResponseStatusException providerFailure(JsonNode response, String fallback) {
        String message = response.path("error").path("message").asText();
        if (!StringUtils.hasText(message)) message = response.path("message").asText();
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                StringUtils.hasText(message) ? "Seedance: " + message : fallback);
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seedance is not configured. Set SEEDANCE_ENABLED=true and provide SEEDANCE_API_KEY on the server.");
        }
    }

    private void requireAccess(CreativeProviderAccess access) {
        if (access == null || !StringUtils.hasText(access.baseUrl()) || !StringUtils.hasText(access.apiKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seedance 连接缺少 Base URL 或 API Key");
        }
    }

    private CreativeProviderAccess platformAccess() {
        return platformConfigs.platformAccess("VIDEO", true);
    }

    private ResponseStatusException upstreamFailure(String action, WebClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String message = switch (status) {
            case 401, 403 -> "Seedance " + action + "失败：API Key 无效或没有模型权限";
            case 404 -> "Seedance " + action + "失败：Base URL 或兼容接口路径不正确";
            case 429 -> "Seedance " + action + "失败：上游请求过于频繁或额度受限";
            default -> "Seedance " + action + "失败，上游返回 HTTP " + status;
        };
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, exception);
    }

    private String endpoint(String configuredBaseUrl, String path) {
        String normalizedBase = configuredBaseUrl.trim().replaceAll("/+$", "");
        if (normalizedBase.endsWith(TASK_PATH)) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - TASK_PATH.length());
        }
        if (normalizedBase.endsWith("/api/v3") && path.startsWith("/api/v3/")) {
            return normalizedBase + path.substring("/api/v3".length());
        }
        return normalizedBase + path;
    }

    private Duration timeout() {
        return Duration.ofSeconds(45);
    }

    private String normalizeStatus(String vendorStatus) {
        if (!StringUtils.hasText(vendorStatus)) return "QUEUED";
        return switch (vendorStatus.toLowerCase()) {
            case "queued", "pending" -> "QUEUED";
            case "running", "processing" -> "RUNNING";
            case "succeeded", "success", "completed" -> "SUCCEEDED";
            case "failed", "error" -> "FAILED";
            case "cancelled", "canceled" -> "CANCELLED";
            default -> vendorStatus.toUpperCase();
        };
    }
}
