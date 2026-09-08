package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.DujiaoNextProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DujiaoNextClient {
    static final String API_PREFIX = "/api/v1/upstream";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final DujiaoNextProperties properties;

    public Map<String, Object> configuration() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("configured", properties.credentialsConfigured());
        result.put("purchasesAllowed", properties.isAllowPurchases());
        result.put("baseUrl", validatedBaseUri().toString());
        result.put("callbackConfigured", text(properties.getCallbackUrl()));
        return result;
    }

    public JsonNode ping() {
        requireReadable();
        return invoke(HttpMethod.POST, API_PREFIX + "/ping", null, null);
    }

    public JsonNode products(int page, int pageSize) {
        requireReadable();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        return invoke(HttpMethod.GET, API_PREFIX + "/products",
                "page=" + safePage + "&page_size=" + safeSize, null);
    }

    public JsonNode product(long productId) {
        requireReadable();
        if (productId < 1) throw new IllegalArgumentException("Dujiao product id must be positive");
        return invoke(HttpMethod.GET, API_PREFIX + "/products/" + productId, null, null);
    }

    public JsonNode createOrder(long skuId, int quantity, String downstreamOrderNo,
                                Map<String, String> manualFormData, String traceId) {
        if (!properties.purchasesEnabled()) {
            throw new DujiaoNextApiException("purchases_disabled",
                    "Dujiao-Next purchases are disabled", 503, false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku_id", skuId);
        body.put("quantity", quantity);
        if (manualFormData != null && !manualFormData.isEmpty()) body.put("manual_form_data", manualFormData);
        body.put("downstream_order_no", downstreamOrderNo);
        if (text(traceId)) body.put("trace_id", traceId);
        if (text(properties.getCallbackUrl())) body.put("callback_url", validatedCallbackUri().toString());
        return invoke(HttpMethod.POST, API_PREFIX + "/orders", null, body);
    }

    public JsonNode order(long upstreamOrderId) {
        requireReadable();
        if (upstreamOrderId < 1) throw new IllegalArgumentException("Dujiao order id must be positive");
        return invoke(HttpMethod.GET, API_PREFIX + "/orders/" + upstreamOrderId, null, null);
    }

    public JsonNode cancel(long upstreamOrderId) {
        if (!properties.purchasesEnabled()) {
            throw new DujiaoNextApiException("purchases_disabled",
                    "Dujiao-Next purchases are disabled", 503, false);
        }
        return invoke(HttpMethod.POST, API_PREFIX + "/orders/" + upstreamOrderId + "/cancel", null, null);
    }

    public void verifyCallback(String apiKey, String timestamp, String signature, byte[] body) {
        requireReadable();
        if (!constantTimeEquals(properties.getApiKey().trim(), apiKey)) {
            throw new DujiaoNextApiException("invalid_api_key", "Invalid Dujiao-Next callback API key", 401, false);
        }
        long parsed;
        try {
            parsed = Long.parseLong(timestamp);
        } catch (RuntimeException invalid) {
            throw new DujiaoNextApiException("invalid_timestamp", "Invalid Dujiao-Next callback timestamp", 401, false);
        }
        long tolerance = Math.max(1, Math.min(300, properties.getTimestampToleranceSeconds()));
        if (Math.abs(Instant.now().getEpochSecond() - parsed) > tolerance) {
            throw new DujiaoNextApiException("timestamp_expired", "Expired Dujiao-Next callback timestamp", 401, false);
        }
        String expected = signature(properties.getApiSecret().trim(), HttpMethod.POST.name(),
                API_PREFIX + "/callback", timestamp, body == null ? new byte[0] : body);
        if (!constantTimeEquals(expected, signature)) {
            throw new DujiaoNextApiException("invalid_signature", "Invalid Dujiao-Next callback signature", 401, false);
        }
    }

    static String signature(String secret, String method, String path, String timestamp, byte[] body) {
        try {
            byte[] safeBody = body == null ? new byte[0] : body;
            String bodyMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(safeBody));
            String material = method.toUpperCase() + "\n" + path + "\n" + timestamp + "\n" + bodyMd5;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign Dujiao-Next request", exception);
        }
    }

    private JsonNode invoke(HttpMethod method, String path, String query, Object body) {
        byte[] payload = serialize(body);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = signature(properties.getApiSecret().trim(), method.name(), path, timestamp, payload);
        URI target = URI.create(validatedBaseUri().toString() + path + (query == null ? "" : "?" + query));
        try {
            WebClient.RequestBodySpec request = webClient.method(method).uri(target)
                    .header("Dujiao-Next-Api-Key", properties.getApiKey().trim())
                    .header("Dujiao-Next-Timestamp", timestamp)
                    .header("Dujiao-Next-Signature", signature)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            WebClient.RequestHeadersSpec<?> prepared = payload.length == 0
                    ? request
                    : request.contentType(MediaType.APPLICATION_JSON).bodyValue(payload);
            Response response = prepared.exchangeToMono(value -> value.bodyToMono(byte[].class)
                            .defaultIfEmpty(new byte[0])
                            .map(bytes -> new Response(value.statusCode().value(), bytes)))
                    .timeout(Duration.ofSeconds(Math.max(3, properties.getRequestTimeoutSeconds())))
                    .block();
            if (response == null) throw new DujiaoNextApiException("Dujiao-Next returned no response", true, null);
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new DujiaoNextApiException("response_too_large", "Dujiao-Next response is too large", 502, false);
            }
            JsonNode json = response.body().length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            String code = json.path("error_code").asText(response.status() >= 400 ? "http_" + response.status() : "api_error");
            String message = json.path("error_message").asText("Dujiao-Next request failed");
            if (response.status() >= 400 || !json.path("ok").asBoolean(false)) {
                boolean retryable = response.status() == 408 || response.status() == 429 || response.status() >= 500;
                throw new DujiaoNextApiException(code, message, response.status(), retryable);
            }
            return json;
        } catch (DujiaoNextApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DujiaoNextApiException("Dujiao-Next request failed", true, exception);
        }
    }

    private byte[] serialize(Object body) {
        if (body == null) return new byte[0];
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize Dujiao-Next request", exception);
        }
    }

    private void requireReadable() {
        if (!properties.isEnabled()) {
            throw new DujiaoNextApiException("integration_disabled", "Dujiao-Next integration is disabled", 503, false);
        }
        if (!properties.credentialsConfigured()) {
            throw new DujiaoNextApiException("credentials_missing", "Dujiao-Next credentials are incomplete", 503, false);
        }
        validatedBaseUri();
    }

    private URI validatedBaseUri() {
        try {
            URI uri = URI.create(properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            String normalized = uri.toString().replaceAll("/+$", "");
            return URI.create(normalized);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("dujiao-next.base-url must be an HTTPS origin", invalid);
        }
    }

    private URI validatedCallbackUri() {
        try {
            URI uri = URI.create(properties.getCallbackUrl().trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("dujiao-next.callback-url must be an absolute HTTPS URL without query or fragment", invalid);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.trim().getBytes(StandardCharsets.UTF_8));
    }

    private boolean text(String value) { return value != null && !value.isBlank(); }
    private record Response(int status, byte[] body) {}
}
