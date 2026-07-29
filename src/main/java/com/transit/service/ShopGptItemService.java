package com.transit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.model.User;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ShopGptItemService {

    private static final Duration SESSION_TTL = Duration.ofMinutes(20);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_CAPTCHA_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final Map<Long, ShopGptSession> sessions = new ConcurrentHashMap<>();

    @Value("${features.shopgpt.enabled:false}")
    private boolean enabled;

    @Value("${shopgpt.base-url:}")
    private String configuredBaseUrl;

    @Value("${shopgpt.item-id:0}")
    private long itemId;

    @Value("${shopgpt.max-quantity:10}")
    private int maxQuantity;

    @Value("${shopgpt.request-timeout-seconds:20}")
    private long requestTimeoutSeconds;

    private volatile WebClient webClient;
    private String baseUrl;
    private Duration requestTimeout;

    @PostConstruct
    void initializeClient() {
        if (!enabled) {
            // A disabled integration deliberately has no HTTP client configured,
            // making accidental supplier traffic impossible.
            return;
        }
        baseUrl = validateBaseUrl(configuredBaseUrl);
        if (itemId <= 0) {
            throw new IllegalStateException("ShopGPT is enabled but shopgpt.item-id is missing or invalid");
        }
        if (maxQuantity < 1 || maxQuantity > 1000) {
            throw new IllegalStateException("shopgpt.max-quantity must be between 1 and 1000");
        }
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 60) {
            throw new IllegalStateException("shopgpt.request-timeout-seconds must be between 1 and 60");
        }
        requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "API-Transit-Station/1.0")
                .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_CAPTCHA_BYTES))
                .build();
    }

    public Map<String, Object> prepare(User user) {
        ShopGptSession session = ensureSession(user);
        synchronized (session) {
            List<Map<String, Object>> payMethods = getPayMethods(session);
            registerPayMethods(session, payMethods);
            if (session.payId == null) {
                throw upstreamFailure("ShopGPT returned no usable payment methods", null);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("email", session.email);
            payload.put("payMethods", payMethods);
            payload.put("sync", syncSession(session, session.quantity, session.captcha, session.payId));
            return payload;
        }
    }

    public CaptchaImage captcha(User user) {
        ShopGptSession session = ensureSession(user);
        synchronized (session) {
            CaptchaImage image = getBytes("/user/captcha/image?action=trade", session);
            session.captcha = "";
            session.lastAccessAt = Instant.now();
            return image;
        }
    }

    public Map<String, Object> sync(User user, int quantity, String captcha, Integer payId) {
        ShopGptSession session = ensureSession(user);
        synchronized (session) {
            return syncSession(session, quantity, captcha, payId);
        }
    }

    public Map<String, Object> trade(User user, int quantity, String captcha, Integer payId) {
        ShopGptSession session = ensureSession(user);
        synchronized (session) {
            session.quantity = validateQuantity(quantity);
            session.captcha = captcha == null ? "" : captcha.trim();
            if (session.captcha.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Captcha is required");
            }
            session.payId = validatePayId(payId, session);

            MultiValueMap<String, String> form = baseForm(session);
            form.add("captcha", session.captcha);
            form.add("pay_id", String.valueOf(session.payId));
            Map<String, Object> response = postJson("/user/api/order/trade", form, session);
            session.captcha = "";
            session.lastAccessAt = Instant.now();
            return response;
        }
    }

    private Map<String, Object> syncSession(ShopGptSession session, int quantity, String captcha, Integer payId) {
        session.quantity = validateQuantity(quantity);
        session.captcha = captcha == null ? "" : captcha.trim();
        if (payId != null) {
            session.payId = validatePayId(payId, session);
        }
        session.lastAccessAt = Instant.now();

        MultiValueMap<String, String> form = baseForm(session);
        Map<String, Object> valuation = postJson("/user/api/index/valuation", form, session);
        Map<String, Object> stock = postJson("/user/api/index/stock", form, session);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", session.email);
        payload.put("quantity", session.quantity);
        payload.put("payId", session.payId);
        payload.put("valuation", valuation);
        payload.put("stock", stock);
        return payload;
    }

    private ShopGptSession ensureSession(User user) {
        requireEnabled();
        if (user == null || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is required");
        }
        String email = requireEmail(user);
        removeExpiredSessions();
        ShopGptSession session = sessions.computeIfAbsent(user.getId(), id -> new ShopGptSession(email));
        synchronized (session) {
            session.email = email;
            if (session.cookies.isEmpty() || session.lastAccessAt.plus(SESSION_TTL).isBefore(Instant.now())) {
                session.resetForNewSupplierSession();
                getText("/item/" + itemId, session);
            }
            session.lastAccessAt = Instant.now();
        }
        return session;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ShopGPT integration is disabled; supplier traffic is blocked by features.shopgpt.enabled=false");
        }
        if (webClient == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ShopGPT integration configuration is incomplete");
        }
    }

    private String validateBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ShopGPT is enabled but shopgpt.base-url is missing");
        }
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("shopgpt.base-url must be an absolute HTTPS origin without credentials, query, or fragment");
            }
            String normalized = value.trim();
            return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("shopgpt.base-url must be a valid HTTPS URL", exception);
        }
    }

    private String requireEmail(User user) {
        if (user.getEmail() != null && EMAIL_PATTERN.matcher(user.getEmail().trim()).matches()) {
            return user.getEmail().trim().toLowerCase();
        }
        if (user.getUsername() != null && EMAIL_PATTERN.matcher(user.getUsername().trim()).matches()) {
            return user.getUsername().trim().toLowerCase();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "当前账号没有有效邮箱，请先完成邮箱绑定和验证");
    }

    private MultiValueMap<String, String> baseForm(ShopGptSession session) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("item_id", String.valueOf(itemId));
        form.add("contact", session.email);
        // Never reuse an email address (or an application password) as the
        // supplier order lookup password. Each supplier session gets 256 bits.
        form.add("password", session.orderPassword);
        form.add("num", String.valueOf(session.quantity));
        return form;
    }

    private List<Map<String, Object>> getPayMethods(ShopGptSession session) {
        Map<String, Object> response = postJson("/user/api/index/pay?itemId=" + itemId,
                new LinkedMultiValueMap<>(), session);
        Object data = response.get("data");
        if (data instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((key, value) -> row.put(String.valueOf(key), value));
                    rows.add(row);
                }
            }
            return rows;
        }
        return List.of();
    }

    private void registerPayMethods(ShopGptSession session, List<Map<String, Object>> methods) {
        session.allowedPayIds.clear();
        for (Map<String, Object> method : methods) {
            Integer id = integerValue(method.get("id"));
            if (id != null && id > 0) {
                session.allowedPayIds.add(id);
            }
        }
        if (session.payId == null || !session.allowedPayIds.contains(session.payId)) {
            session.payId = session.allowedPayIds.stream().findFirst().orElse(null);
        }
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int validateQuantity(int quantity) {
        if (quantity < 1 || quantity > maxQuantity) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "quantity must be between 1 and " + maxQuantity);
        }
        return quantity;
    }

    private int validatePayId(Integer payId, ShopGptSession session) {
        if (payId == null || payId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid payId is required");
        }
        if (!session.allowedPayIds.isEmpty() && !session.allowedPayIds.contains(payId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected payId is not offered by the supplier");
        }
        return payId;
    }

    private Map<String, Object> postJson(String path, MultiValueMap<String, String> form, ShopGptSession session) {
        String body = block(webClient.post()
                .uri(path)
                .headers(headers -> applyHeaders(headers, session))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchangeToMono(response -> readText(response, session)), "ShopGPT request failed");
        try {
            return objectMapper.readValue(body == null ? "{}" : body,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception exception) {
            throw upstreamFailure("ShopGPT returned an invalid JSON response", exception);
        }
    }

    private String getText(String path, ShopGptSession session) {
        return block(webClient.get()
                .uri(path)
                .headers(headers -> applyHeaders(headers, session))
                .exchangeToMono(response -> readText(response, session)), "ShopGPT session initialization failed");
    }

    private CaptchaImage getBytes(String path, ShopGptSession session) {
        return block(webClient.get()
                .uri(path)
                .headers(headers -> applyHeaders(headers, session))
                .exchangeToMono(response -> {
                    updateCookies(session, response);
                    if (!response.statusCode().is2xxSuccessful()) {
                        int status = response.statusCode().value();
                        return response.releaseBody().then(Mono.error(
                                upstreamFailure("ShopGPT captcha request returned HTTP " + status, null)));
                    }
                    MediaType contentType = response.headers().contentType().orElse(null);
                    if (contentType == null || !"image".equalsIgnoreCase(contentType.getType())) {
                        return response.releaseBody().then(Mono.error(
                                upstreamFailure("ShopGPT captcha response was not an image", null)));
                    }
                    return response.bodyToMono(byte[].class).flatMap(bytes -> {
                        if (bytes.length == 0 || bytes.length > MAX_CAPTCHA_BYTES) {
                            return Mono.error(upstreamFailure("ShopGPT captcha response size is invalid", null));
                        }
                        return Mono.just(new CaptchaImage(contentType.toString(),
                                Base64.getEncoder().encodeToString(bytes)));
                    });
                }), "ShopGPT captcha request failed");
    }

    private Mono<String> readText(ClientResponse response, ShopGptSession session) {
        updateCookies(session, response);
        if (!response.statusCode().is2xxSuccessful()) {
            int status = response.statusCode().value();
            return response.releaseBody().then(Mono.error(
                    upstreamFailure("ShopGPT upstream returned HTTP " + status, null)));
        }
        return response.bodyToMono(String.class);
    }

    private <T> T block(Mono<T> operation, String message) {
        try {
            T result = operation.block(requestTimeout);
            if (result == null) {
                throw upstreamFailure(message + ": empty response", null);
            }
            return result;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw upstreamFailure(message, exception);
        }
    }

    private ResponseStatusException upstreamFailure(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, cause);
    }

    private void applyHeaders(HttpHeaders headers, ShopGptSession session) {
        headers.set(HttpHeaders.REFERER, baseUrl + "/item/" + itemId);
        if (!session.cookies.isEmpty()) {
            List<String> cookiePairs = session.cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList();
            headers.set(HttpHeaders.COOKIE, String.join("; ", cookiePairs));
        }
    }

    private void updateCookies(ShopGptSession session, ClientResponse response) {
        for (Map.Entry<String, List<ResponseCookie>> entry : response.cookies().entrySet()) {
            if (!entry.getValue().isEmpty() && session.cookies.size() < 32) {
                String value = entry.getValue().get(entry.getValue().size() - 1).getValue();
                if (entry.getKey().length() <= 128 && value.length() <= 4096) {
                    session.cookies.put(entry.getKey(), value);
                }
            }
        }
    }

    private void removeExpiredSessions() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccessAt.isBefore(cutoff));
    }

    static String newOrderPassword() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record CaptchaImage(String contentType, String base64) {
    }

    private static class ShopGptSession {
        private final Map<String, String> cookies = new ConcurrentHashMap<>();
        private final Set<Integer> allowedPayIds = new LinkedHashSet<>();
        private String email;
        private String orderPassword;
        private int quantity = 1;
        private String captcha = "";
        private Integer payId;
        private Instant lastAccessAt = Instant.EPOCH;

        private ShopGptSession(String email) {
            this.email = email;
            this.orderPassword = newOrderPassword();
        }

        private void resetForNewSupplierSession() {
            cookies.clear();
            allowedPayIds.clear();
            orderPassword = newOrderPassword();
            payId = null;
            captcha = "";
        }
    }
}
