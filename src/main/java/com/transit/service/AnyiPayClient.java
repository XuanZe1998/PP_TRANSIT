package com.transit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Server-side client for the AnyiPay API documented at a.tjrl3.cn.
 * Credentials never cross the application boundary and every successful
 * provider response is verified with the platform public key.
 */
@Service
public class AnyiPayClient {

    private static final String SIGN_TYPE = "RSA";
    private static final long DEFAULT_TIMESTAMP_TOLERANCE_SECONDS = 300;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean allowMoneyMutations;
    private final URI baseUri;
    private final String merchantId;
    private final String merchantPrivateKey;
    private final String platformPublicKey;
    private final String notifyUrl;
    private final String returnUrl;
    private final String defaultPaymentType;
    private final Duration requestTimeout;
    private final long timestampToleranceSeconds;

    public AnyiPayClient(WebClient webClient,
                         ObjectMapper objectMapper,
                         @Value("${payment.enabled:false}") boolean enabled,
                         @Value("${payment.allow-money-mutations:false}") boolean allowMoneyMutations,
                         @Value("${payment.base-url:https://a.tjrl3.cn}") String baseUrl,
                         @Value("${payment.merchant-id:}") String merchantId,
                         @Value("${payment.merchant-private-key:}") String merchantPrivateKey,
                         @Value("${payment.platform-public-key:}") String platformPublicKey,
                         @Value("${payment.notify-url:}") String notifyUrl,
                         @Value("${payment.return-url:}") String returnUrl,
                         @Value("${payment.default-payment-type:alipay}") String defaultPaymentType,
                         @Value("${payment.request-timeout-seconds:15}") long requestTimeoutSeconds,
                         @Value("${payment.timestamp-tolerance-seconds:300}") long timestampToleranceSeconds) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.allowMoneyMutations = allowMoneyMutations;
        this.baseUri = validateBaseUri(baseUrl);
        this.merchantId = trim(merchantId);
        this.merchantPrivateKey = trim(merchantPrivateKey);
        this.platformPublicKey = trim(platformPublicKey);
        this.notifyUrl = validateConfiguredUrl(notifyUrl, "notify-url", false);
        this.returnUrl = validateConfiguredUrl(returnUrl, "return-url", true);
        this.defaultPaymentType = validatePaymentType(defaultPaymentType);
        this.requestTimeout = Duration.ofSeconds(Math.max(3, Math.min(60, requestTimeoutSeconds)));
        this.timestampToleranceSeconds = Math.max(30,
                Math.min(900, timestampToleranceSeconds <= 0
                        ? DEFAULT_TIMESTAMP_TOLERANCE_SECONDS : timestampToleranceSeconds));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isMoneyMutationsEnabled() {
        return enabled && allowMoneyMutations;
    }

    public String merchantId() {
        requireConfigured();
        return merchantId;
    }

    public String createPagePaymentUrl(String outTradeNo,
                                       String name,
                                       String money,
                                       String businessParam) {
        return createPagePaymentUrl(outTradeNo, name, money, businessParam, defaultPaymentType);
    }

    public String createPagePaymentUrl(String outTradeNo,
                                       String name,
                                       String money,
                                       String businessParam,
                                       String paymentType) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", validatePaymentType(paymentType));
        params.put("out_trade_no", required(outTradeNo, "out_trade_no", 80));
        params.put("notify_url", required(notifyUrl, "notify_url", 2000));
        params.put("return_url", required(returnUrl, "return_url", 2000));
        params.put("name", required(name, "name", 127));
        params.put("money", validateMoney(money));
        putIfPresent(params, "param", limited(businessParam, "param", 1000));
        return buildPagePaymentUrl(params);
    }

    public String buildPagePaymentUrl(Map<String, String> paymentParams) {
        requireConfigured();
        Map<String, String> signed = signedParameters(paymentParams);
        StringBuilder query = new StringBuilder();
        signed.forEach((key, value) -> {
            if (!query.isEmpty()) query.append('&');
            query.append(urlEncode(key)).append('=').append(urlEncode(value));
        });
        return endpoint("/api/pay/submit") + "?" + query;
    }

    public JsonNode queryPayment(String tradeNo, String outTradeNo) {
        return execute("/api/pay/query", oneOf("trade_no", tradeNo, "out_trade_no", outTradeNo));
    }

    public JsonNode refund(String tradeNo, String outTradeNo, String money, String outRefundNo) {
        requireMoneyMutationsEnabled();
        Map<String, String> params = oneOf("trade_no", tradeNo, "out_trade_no", outTradeNo);
        params.put("money", validateMoney(money));
        putIfPresent(params, "out_refund_no", limited(outRefundNo, "out_refund_no", 80));
        return execute("/api/pay/refund", params);
    }

    public JsonNode queryRefund(String refundNo, String outRefundNo) {
        return execute("/api/pay/refundquery",
                oneOf("refund_no", refundNo, "out_refund_no", outRefundNo));
    }

    public JsonNode closePayment(String tradeNo, String outTradeNo) {
        requireMoneyMutationsEnabled();
        return execute("/api/pay/close", oneOf("trade_no", tradeNo, "out_trade_no", outTradeNo));
    }

    public JsonNode merchantInfo() {
        return execute("/api/merchant/info", Map.of());
    }

    public JsonNode merchantOrders(int offset, int limit, Integer status) {
        if (offset < 0) throw badRequest("offset must be non-negative");
        if (limit < 1 || limit > 50) throw badRequest("limit must be between 1 and 50");
        if (status != null && status != 0 && status != 1) {
            throw badRequest("status must be 0 or 1");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("offset", String.valueOf(offset));
        params.put("limit", String.valueOf(limit));
        if (status != null) params.put("status", String.valueOf(status));
        return execute("/api/merchant/orders", params);
    }

    public JsonNode submitTransfer(String type,
                                   String account,
                                   String name,
                                   String money,
                                   String remark,
                                   String outBizNo,
                                   String bookId) {
        requireMoneyMutationsEnabled();
        String normalizedType = trim(type);
        if (!List.of("alipay", "wxpay", "qqpay", "bank").contains(normalizedType)) {
            throw badRequest("Unsupported transfer type");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", normalizedType);
        params.put("account", required(account, "account", 255));
        putIfPresent(params, "name", limited(name, "name", 120));
        params.put("money", validateMoney(money));
        putIfPresent(params, "remark", limited(remark, "remark", 500));
        putIfPresent(params, "out_biz_no", limited(outBizNo, "out_biz_no", 80));
        putIfPresent(params, "bookid", limited(bookId, "bookid", 120));
        return execute("/api/transfer/submit", params);
    }

    public JsonNode queryTransfer(String bizNo, String outBizNo) {
        return execute("/api/transfer/query", oneOf("biz_no", bizNo, "out_biz_no", outBizNo));
    }

    public JsonNode transferBalance() {
        return execute("/api/transfer/balance", Map.of());
    }

    public Map<String, String> verifyCallback(MultiValueMap<String, String> requestParams) {
        requireConfigured();
        Map<String, String> flattened = new LinkedHashMap<>();
        requestParams.forEach((key, values) -> {
            if (values == null || values.size() != 1) {
                throw badRequest("Callback contains a duplicate or missing parameter");
            }
            flattened.put(key, values.get(0));
        });
        verifySignedPayload(flattened);
        if (!merchantId.equals(flattened.get("pid"))) {
            throw badRequest("Callback merchant does not match configured merchant");
        }
        return Map.copyOf(flattened);
    }

    JsonNode execute(String path, Map<String, String> requestParameters) {
        requireConfigured();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        signedParameters(requestParameters).forEach(form::add);
        String raw;
        try {
            raw = webClient.post()
                    .uri(endpoint(path))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(requestTimeout);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "AnyiPay request failed", exception);
        }
        try {
            JsonNode response = objectMapper.readTree(raw == null ? "" : raw);
            if (response == null || !response.isObject()) {
                throw new IllegalArgumentException("response is not a JSON object");
            }
            if (response.path("code").asInt(-1) != 0) {
                String message = limited(response.path("msg").asText("AnyiPay rejected the request"),
                        "provider message", 300);
                throw new ResponseStatusException(BAD_GATEWAY, message);
            }
            Map<String, Object> values = objectMapper.convertValue(response, new TypeReference<>() { });
            verifySignedPayload(values);
            return response;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "AnyiPay returned an invalid response", exception);
        }
    }

    Map<String, String> signedParameters(Map<String, String> requestParameters) {
        requireConfigured();
        Map<String, String> params = new LinkedHashMap<>();
        if (requestParameters != null) params.putAll(requestParameters);
        params.put("pid", merchantId);
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        params.remove("sign");
        params.remove("sign_type");
        params.put("sign", sign(canonicalize(params), merchantPrivateKey));
        params.put("sign_type", SIGN_TYPE);
        return params;
    }

    void verifySignedPayload(Map<String, ?> values) {
        Object signValue = values == null ? null : values.get("sign");
        Object timestampValue = values == null ? null : values.get("timestamp");
        if (signValue == null || timestampValue == null) {
            throw badRequest("Signed payload is missing sign or timestamp");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(String.valueOf(timestampValue));
        } catch (NumberFormatException exception) {
            throw badRequest("Signed payload timestamp is invalid");
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > timestampToleranceSeconds) {
            throw badRequest("Signed payload timestamp is outside the allowed window");
        }
        if (!verify(canonicalize(values), String.valueOf(signValue), platformPublicKey)) {
            throw badRequest("AnyiPay signature verification failed");
        }
    }

    static String canonicalize(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return "";
        Map<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || "sign".equals(key) || "sign_type".equals(key) || value == null) return;
            if (value instanceof Collection<?> || value.getClass().isArray() || value instanceof Map<?, ?>) return;
            String text = String.valueOf(value);
            if (!text.trim().isEmpty()) sorted.put(key, text);
        });
        List<String> pairs = new ArrayList<>();
        sorted.forEach((key, value) -> pairs.add(key + "=" + value));
        return String.join("&", pairs);
    }

    static String sign(String content, String privateKey) {
        try {
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(decodePem(privateKey)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "AnyiPay merchant private key is invalid", exception);
        }
    }

    static boolean verify(String content, String encodedSignature, String publicKey) {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(decodePem(publicKey)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(encodedSignature));
        } catch (Exception exception) {
            return false;
        }
    }

    private static byte[] decodePem(String value) {
        String normalized = trim(value)
                .replace("\\n", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private void requireConfigured() {
        if (!enabled) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "AnyiPay is disabled");
        if (merchantId.isBlank() || merchantPrivateKey.isBlank() || platformPublicKey.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "AnyiPay credentials are not configured");
        }
        if (notifyUrl.isBlank() || returnUrl.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "AnyiPay notify and return URLs are not configured");
        }
    }

    private void requireMoneyMutationsEnabled() {
        requireConfigured();
        if (!allowMoneyMutations) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "AnyiPay refund, close, and transfer operations are disabled");
        }
    }

    private String endpoint(String path) {
        return baseUri.resolve(path.startsWith("/") ? path.substring(1) : path).toString();
    }

    private Map<String, String> oneOf(String firstKey, String first, String secondKey, String second) {
        String firstValue = trim(first);
        String secondValue = trim(second);
        if (firstValue.isBlank() == secondValue.isBlank()) {
            throw badRequest("Exactly one of " + firstKey + " and " + secondKey + " is required");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put(firstValue.isBlank() ? secondKey : firstKey, firstValue.isBlank() ? secondValue : firstValue);
        return params;
    }

    private static URI validateBaseUri(String raw) {
        try {
            URI uri = URI.create(trim(raw));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("not a safe HTTPS origin");
            }
            String normalized = uri.toString();
            return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("anyipay.base-url must be a valid HTTPS origin", exception);
        }
    }

    private static String validateConfiguredUrl(String raw, String field, boolean optional) {
        String value = trim(raw);
        if (value.isBlank() && optional) return "";
        if (value.isBlank()) return "";
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || !List.of("https", "http").contains(uri.getScheme().toLowerCase())) {
                throw new IllegalArgumentException("not HTTP(S)");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("anyipay." + field + " must be an absolute HTTP(S) URL", exception);
        }
    }

    private static String validatePaymentType(String raw) {
        String value = trim(raw).toLowerCase();
        if (!List.of("alipay", "wxpay", "qqpay").contains(value)) {
            throw new IllegalArgumentException("Unsupported anyipay.default-payment-type");
        }
        return value;
    }

    private static String validateMoney(String raw) {
        String value = trim(raw);
        if (!value.matches("(?:0|[1-9]\\d{0,9})\\.\\d{2}") || "0.00".equals(value)) {
            throw badRequest("money must be a positive amount with exactly two decimals");
        }
        return value;
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = limited(value, field, maxLength);
        if (normalized == null) throw badRequest(field + " is required");
        return normalized;
    }

    private static String limited(String value, String field, int maxLength) {
        String normalized = trim(value);
        if (normalized.isBlank()) return null;
        if (normalized.length() > maxLength) throw badRequest(field + " is too long");
        return normalized;
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) values.put(key, value);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }
}
