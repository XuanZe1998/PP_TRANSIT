package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.MaPayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
@Slf4j
@RequiredArgsConstructor
public class MaPayClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MaPayProperties properties;

    public boolean isEnabled() {
        return properties.isEnabled() && properties.credentialsConfigured();
    }

    public String merchantId() {
        requireEnabled();
        return properties.getMerchantId().trim();
    }

    public PaymentStart start(String outTradeNo, String name, String money, String param,
                              String paymentMethod, String clientIp, String device) {
        requireEnabled();
        String method = paymentMethod(paymentMethod);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pid", merchantId());
        fields.put("type", method);
        fields.put("out_trade_no", required(outTradeNo, "out_trade_no", 80));
        fields.put("notify_url", required(properties.getNotifyUrl(), "notify_url", 2000));
        put(fields, "return_url", properties.getReturnUrl());
        fields.put("name", required(name, "name", 127));
        fields.put("money", required(money, "money", 40));
        put(fields, "sitename", properties.getSiteName());
        put(fields, "param", param);
        put(fields, "clientip", clientIp);
        put(fields, "device", device);
        fields.put("sign", sign(fields, properties.getMerchantKey()));
        fields.put("sign_type", "MD5");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        fields.forEach(form::add);
        JsonNode json = postForm("/xpay/epay/mapi.php", form);
        if (json.path("code").asInt(0) != 1) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    safeMessage(json.path("msg").asText(), "MaPay rejected the payment request"));
        }
        String tradeNo = required(json.path("trade_no").asText(null), "trade_no", 120);
        List<PaymentAction> actions = new ArrayList<>();
        action(actions, "REDIRECT", json.path("payurl").asText(null));
        action(actions, "QRCODE", json.path("qrcode").asText(null));
        action(actions, "URL_SCHEME", json.path("urlscheme").asText(null));
        if (actions.isEmpty()) {
            log.warn("MaPay create-payment response contained no direct action; using signed submit-page fallback; response fields={}",
                    responseFieldNames(json));
            action(actions, "REDIRECT", submitUrl(fields));
        }
        if (actions.size() > 1) {
            log.info("MaPay create-payment response contained actions {}; selected {}",
                    actions.stream().map(PaymentAction::type).toList(), actions.get(0).type());
        }
        return new PaymentStart(tradeNo, json.path("money").asText(money), actions.get(0));
    }

    public OrderResult query(String outTradeNo) {
        requireEnabled();
        URI target = UriComponentsBuilder.fromUri(baseUri())
                .path("/xpay/epay/api.php")
                .queryParam("act", "order")
                .queryParam("pid", merchantId())
                .queryParam("key", properties.getMerchantKey().trim())
                .queryParam("out_trade_no", required(outTradeNo, "out_trade_no", 80))
                .build().encode().toUri();
        JsonNode json = getJson(target);
        if (json.path("code").asInt(0) != 1) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    safeMessage(json.path("msg").asText(), "MaPay order query failed"));
        }
        return new OrderResult(
                json.path("status").asInt(0) == 1,
                json.path("trade_no").asText(null),
                json.path("out_trade_no").asText(null),
                json.path("type").asText(null),
                json.path("money").asText(null),
                json.path("param").asText(null));
    }

    public Map<String, String> verifyCallback(MultiValueMap<String, String> parameters) {
        requireEnabled();
        if (parameters == null || parameters.isEmpty()) throw badRequest("MaPay callback is empty");
        Map<String, String> values = new LinkedHashMap<>();
        parameters.forEach((key, entries) -> {
            if (key == null || entries == null || entries.size() != 1) {
                throw badRequest("MaPay callback contains duplicate parameters");
            }
            values.put(key, entries.get(0));
        });
        String supplied = required(values.get("sign"), "sign", 64).toLowerCase(Locale.ROOT);
        if (!"MD5".equalsIgnoreCase(values.getOrDefault("sign_type", "MD5"))) {
            throw badRequest("Unsupported MaPay callback signature type");
        }
        String expected = sign(values, properties.getMerchantKey());
        if (!constantTimeEquals(expected, supplied)) throw badRequest("Invalid MaPay callback signature");
        if (!constantTimeEquals(merchantId(), required(values.get("pid"), "pid", 80))) {
            throw badRequest("MaPay callback merchant does not match");
        }
        return Map.copyOf(values);
    }

    static String sign(Map<String, String> input, String key) {
        if (key == null || key.isBlank()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "MaPay key is missing");
        TreeMap<String, String> sorted = new TreeMap<>();
        if (input != null) input.forEach((name, value) -> {
            if (name != null && value != null && !value.isEmpty()
                    && !"sign".equals(name) && !"sign_type".equals(name)) sorted.put(name, value);
        });
        String material = sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right).orElse("") + key;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign MaPay request", exception);
        }
    }

    private JsonNode postForm(String path, MultiValueMap<String, String> form) {
        try {
            Response response = webClient.post().uri(baseUri().resolve(path))
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(value -> value.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                            .map(body -> new Response(value.statusCode().value(), body)))
                    .timeout(timeout()).block();
            return parse(response);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "MaPay payment request failed", exception);
        }
    }

    private JsonNode getJson(URI target) {
        try {
            Response response = webClient.get().uri(target)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .exchangeToMono(value -> value.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                            .map(body -> new Response(value.statusCode().value(), body)))
                    .timeout(timeout()).block();
            return parse(response);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "MaPay order query failed", exception);
        }
    }

    private JsonNode parse(Response response) throws Exception {
        if (response == null) throw new ResponseStatusException(BAD_GATEWAY, "MaPay returned no response");
        if (response.body().length > MAX_RESPONSE_BYTES) throw new ResponseStatusException(BAD_GATEWAY, "MaPay response is too large");
        if (response.status() < 200 || response.status() >= 300) {
            throw new ResponseStatusException(BAD_GATEWAY, "MaPay returned HTTP " + response.status());
        }
        return objectMapper.readTree(response.body());
    }

    private URI baseUri() {
        try {
            URI uri = URI.create(required(properties.getBaseUrl(), "baseUrl", 1000));
            boolean local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || (!("https".equalsIgnoreCase(uri.getScheme())) && !local)
                    || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid MaPay base URL");
            }
            String normalized = uri.toString().replaceAll("/+$", "");
            return URI.create(normalized + "/");
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "MaPay base URL is invalid");
        }
    }

    private String submitUrl(Map<String, String> fields) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(
                baseUri().resolve("/xpay/epay/submit.php"));
        fields.forEach((name, value) -> builder.queryParam(name,
                URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return builder.build(true).toUriString();
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(3, Math.min(60, properties.getRequestTimeoutSeconds())));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "MaPay is disabled");
        if (!properties.credentialsConfigured()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "MaPay credentials are incomplete");
        baseUri();
    }

    private String paymentMethod(String raw) {
        String value = required(raw, "paymentMethod", 20).toLowerCase(Locale.ROOT);
        if (!properties.methodAllowed(value)) throw badRequest("Unsupported MaPay payment method");
        return value;
    }

    private String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw badRequest(field + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw badRequest(field + " is too long");
        return normalized;
    }

    private void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value.trim());
    }

    private void action(List<PaymentAction> target, String type, String url) {
        if (url == null || url.isBlank()) return;
        String normalized = url.trim();
        if (normalized.length() > 2000) throw new ResponseStatusException(BAD_GATEWAY, "MaPay payment action is too long");
        target.add(new PaymentAction(type, normalized));
    }

    private List<String> responseFieldNames(JsonNode json) {
        if (json == null || !json.isObject()) return List.of();
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String safeMessage(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.substring(0, Math.min(300, normalized.length()));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }

    private record Response(int status, byte[] body) {}
    public record PaymentAction(String type, String url) {}
    public record PaymentStart(String tradeNo, String money, PaymentAction action) {}
    public record OrderResult(boolean paid, String tradeNo, String outTradeNo, String type, String money, String param) {}
}
