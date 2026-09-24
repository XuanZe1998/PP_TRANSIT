package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.config.SubscriptionServiceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
@RequiredArgsConstructor
public class SubscriptionServiceClient {
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,79}");
    private static final Set<String> RESERVED = Set.of("app_id", "timestamp", "sign", "sign_type", "secret");
    private static final Set<Integer> PAGE_SIZES = Set.of(10, 20, 50, 100);
    private static final int MAX_REQUEST_BYTES = 6 * 1024 * 1024;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final SubscriptionServiceProperties properties;

    public Map<String, Object> configuration() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "configured", properties.credentialsConfigured(),
                "mutationsAllowed", properties.isAllowMutations(),
                "gateway", validatedGateway().toString()
        );
    }

    public JsonNode invoke(SubscriptionServiceOperation operation, Map<String, Object> suppliedParameters) {
        requireAvailable(operation);
        Map<String, Object> parameters = validateParameters(suppliedParameters);
        if (operation.paged()) applyPageContract(parameters);

        Map<String, Object> requestBody = new LinkedHashMap<>(parameters);
        requestBody.put("app_id", properties.getAppId().trim());
        requestBody.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        requestBody.put("sign", signature(requestBody, properties.getSecret().trim(), objectMapper));
        byte[] payload = serialize(requestBody);
        if (payload.length > MAX_REQUEST_BYTES) {
            throw new ResponseStatusException(BAD_REQUEST, "请求内容超过 6 MB 限制");
        }

        URI target = endpoint(operation.path());
        try {
            Response response = webClient.post().uri(target)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchangeToMono(value -> value.bodyToMono(byte[].class)
                            .defaultIfEmpty(new byte[0])
                            .map(bytes -> new Response(value.statusCode().value(), bytes)))
                    .timeout(Duration.ofSeconds(Math.max(3, Math.min(120, properties.getRequestTimeoutSeconds()))))
                    .block();
            if (response == null) throw upstream("上游未返回响应");
            if (response.body.length > Math.max(64 * 1024, properties.getMaxResponseBytes())) {
                throw upstream("上游响应超过安全上限");
            }
            JsonNode json = response.body.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(response.body);
            if (response.status >= 400 || json.path("code").asInt(0) != 1) {
                throw upstream(safeMessage(json.path("msg").asText("上游请求失败")));
            }
            JsonNode data = json.path("data");
            if ((operation == SubscriptionServiceOperation.PURCHASE_COMPLAINT_MESSAGES
                    || operation == SubscriptionServiceOperation.COMPLAINT_MESSAGES)
                    && largestArray(data) > 200) {
                throw upstream("上游未分页留言超过 200 条，请缩小查询范围");
            }
            return data;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "订阅服务上游暂时不可用", exception);
        }
    }

    public Map<String, Object> page(JsonNode data, Map<String, Object> parameters) {
        JsonNode itemsNode = data.isArray() ? data : firstArray(data, "items", "list", "rows", "data");
        if (itemsNode == null) itemsNode = objectMapper.createArrayNode();
        int page = positiveInt(parameters.get("page_no"), 1);
        int size = positiveInt(parameters.get("page_size"), 10);
        long total = firstLong(data, -1, "total", "count", "total_count");
        if (total < 0 || total < itemsNode.size()) {
            throw upstream("上游分页结果未返回可信的完整总数");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("items", objectMapper.convertValue(itemsNode, List.class));
        if (data.isObject()) {
            ObjectNode metadata = ((ObjectNode) data).deepCopy();
            List.of("items", "list", "rows", "data", "total", "count", "total_count", "page", "page_no", "page_size", "size")
                    .forEach(metadata::remove);
            if (!metadata.isEmpty()) result.put("meta", objectMapper.convertValue(metadata, Map.class));
        }
        return result;
    }

    private Map<String, Object> validateParameters(Map<String, Object> supplied) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (supplied == null) return result;
        supplied.forEach((key, value) -> {
            String normalized = key == null ? "" : key.trim();
            if (!PARAMETER_NAME.matcher(normalized).matches() || RESERVED.contains(normalized.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(BAD_REQUEST, "请求参数名无效");
            }
            Object safe = normalize(value, 0);
            if (!empty(safe)) result.put(normalized, safe);
        });
        return result;
    }

    private Object normalize(Object value, int depth) {
        if (depth > 8) throw new ResponseStatusException(BAD_REQUEST, "请求参数嵌套过深");
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (!PARAMETER_NAME.matcher(name).matches()) throw new ResponseStatusException(BAD_REQUEST, "嵌套参数名无效");
                Object normalized = normalize(item, depth + 1);
                if (!empty(normalized)) sorted.put(name, normalized);
            });
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            iterable.forEach(item -> list.add(normalize(item, depth + 1)));
            return list;
        }
        throw new ResponseStatusException(BAD_REQUEST, "请求参数类型无效");
    }

    private void applyPageContract(Map<String, Object> parameters) {
        int page = positiveInt(parameters.get("page_no"), 1);
        int size = positiveInt(parameters.get("page_size"), 10);
        if (page < 1 || !PAGE_SIZES.contains(size)) {
            throw new ResponseStatusException(BAD_REQUEST, "分页参数无效，每页仅允许 10/20/50/100");
        }
        parameters.put("page_no", page);
        parameters.put("page_size", size);
    }

    private int positiveInt(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException invalid) { throw new ResponseStatusException(BAD_REQUEST, "分页参数无效"); }
    }

    static String signature(Map<String, Object> parameters, String secret, ObjectMapper objectMapper) {
        try {
            TreeMap<String, Object> sorted = new TreeMap<>(parameters);
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (RESERVED.contains(entry.getKey().toLowerCase(Locale.ROOT)) && !"app_id".equals(entry.getKey()) && !"timestamp".equals(entry.getKey())) continue;
                Object value = normalizeForSignature(entry.getValue());
                if (empty(value)) continue;
                String encoded = value instanceof Map<?, ?> || value instanceof Iterable<?>
                        ? objectMapper.writeValueAsString(value) : String.valueOf(value);
                parts.add(entry.getKey() + "=" + encoded);
            }
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest((String.join("&", parts) + secret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成订阅服务签名", exception);
        }
    }

    private static Object normalizeForSignature(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> {
                Object normalized = normalizeForSignature(item);
                if (!empty(normalized)) sorted.put(String.valueOf(key), normalized);
            });
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            iterable.forEach(item -> list.add(normalizeForSignature(item)));
            return list;
        }
        return value;
    }

    private byte[] serialize(Object body) {
        try { return objectMapper.writeValueAsBytes(body); }
        catch (Exception exception) { throw new ResponseStatusException(BAD_REQUEST, "请求无法序列化"); }
    }

    private void requireAvailable(SubscriptionServiceOperation operation) {
        if (!properties.isEnabled() || !properties.credentialsConfigured()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "订阅服务尚未配置");
        }
        if (operation.mutation() && !properties.isAllowMutations()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "订阅服务写操作尚未开启");
        }
    }

    private URI endpoint(String documentedPath) {
        URI gateway = validatedGateway();
        String suffix = documentedPath.startsWith("/openApi") ? documentedPath.substring("/openApi".length()) : documentedPath;
        return URI.create(gateway.toString().replaceAll("/+$", "") + suffix);
    }

    private URI validatedGateway() {
        try {
            URI uri = URI.create(properties.getGateway() == null ? "" : properties.getGateway().trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            return URI.create(uri.toString().replaceAll("/+$", ""));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("subscription-service.gateway 必须是 HTTPS 网关地址", invalid);
        }
    }

    private JsonNode firstArray(JsonNode data, String... names) {
        if (!data.isObject()) return null;
        for (String name : names) if (data.path(name).isArray()) return data.path(name);
        return null;
    }

    private long firstLong(JsonNode data, long fallback, String... names) {
        if (!data.isObject()) return fallback;
        for (String name : names) {
            JsonNode value = data.path(name);
            if (value.canConvertToLong()) return value.asLong();
            if (value.isTextual() && value.asText().matches("\\d+")) return Long.parseLong(value.asText());
        }
        return fallback;
    }

    private int largestArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        if (node.isArray()) return node.size();
        if (!node.isObject()) return 0;
        int largest = 0;
        var fields = node.fields();
        while (fields.hasNext()) largest = Math.max(largest, largestArray(fields.next().getValue()));
        return largest;
    }

    private static boolean empty(Object value) {
        return value == null || value instanceof String string && string.isBlank()
                || value instanceof Map<?, ?> map && map.isEmpty()
                || value instanceof Iterable<?> iterable && !iterable.iterator().hasNext();
    }

    private String safeMessage(String value) {
        String safe = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ").trim();
        return safe.isBlank() ? "上游请求失败" : safe.substring(0, Math.min(300, safe.length()));
    }

    private ResponseStatusException upstream(String message) {
        return new ResponseStatusException(BAD_GATEWAY, message);
    }

    private record Response(int status, byte[] body) {}
}
