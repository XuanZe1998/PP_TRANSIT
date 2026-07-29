package com.transit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.model.VmCardProductCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VmCardClientService {
    private static final String SANDBOX_URL = "https://sandbox-api.vmcardio.com";
    private static final String PRODUCTION_URL = "https://vmapi.vmcardio.com";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final VmCardCryptoService cryptoService;
    private final VmCardSavedCardService savedCardService;
    private final VmCardProductCodeService productCodeService;

    @Value("${vmcard.enabled:false}")
    private boolean enabled;

    @Value("${vmcard.environment:sandbox}")
    private String environment;

    @Value("${vmcard.app-id:}")
    private String appId;

    @Value("${vmcard.app-secret:}")
    private String appSecret;

    @Value("${vmcard.public-key:}")
    private String publicKey;

    @Value("${vmcard.private-key:}")
    private String privateKey;

    @Value("${vmcard.allow-mutations:true}")
    private boolean allowMutations;

    @Value("${vmcard.allow-production-mutations:false}")
    private boolean allowProductionMutations;

    @Value("${vmcard.request-timeout-seconds:30}")
    private long requestTimeoutSeconds;

    @Value("${vmcard.webhook-secret:}")
    private String webhookSecret;

    private volatile CachedToken cachedToken;
    private final Object productCodeSyncMonitor = new Object();

    public Map<String, Object> configuration() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("configured", credentialsConfigured());
        result.put("encryptionConfigured", encryptionConfigured());
        result.put("environment", normalizedEnvironment());
        result.put("baseUrl", baseUrl());
        result.put("mutationsAllowed", mutationsAllowed());
        result.put("productionMutationsAllowed", allowProductionMutations);
        result.put("webhookConfigured", webhookSecret != null && !webhookSecret.isBlank());
        result.put("operations", VmCardOperation.metadata());
        return result;
    }

    public Map<String, Object> checkToken() {
        TokenResult token = accessToken();
        return Map.of(
                "ok", true,
                "environment", normalizedEnvironment(),
                "expiresAt", token.expiresAt().toString()
        );
    }

    public Map<String, Object> execute(String operationId, Map<String, Object> body) {
        VmCardOperation operation = VmCardOperation.fromId(operationId);
        requireConfigured();
        if (operation.mutating() && !mutationsAllowed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "VMCard mutation calls are locked by the environment safety switches");
        }

        Map<String, Object> safeBody = body == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(body);
        validateRequest(operation, safeBody);

        TokenResult token = accessToken();
        String selectedProductCode = null;
        if (operation == VmCardOperation.CREATE_CARD) {
            selectedProductCode = selectProductCode(safeBody, token.token());
            safeBody.put("product_code", selectedProductCode);
        }

        ApiResponse apiResponse;
        int synchronizedProducts = 0;
        if (operation == VmCardOperation.PRODUCT_CODES) {
            ProductCodeSync sync = fetchAndSyncProductCodes(token.token());
            apiResponse = sync.apiResponse();
            synchronizedProducts = sync.synchronizedProducts();
        } else {
            apiResponse = invoke(operation.path(), safeBody, token.token());
            requireVendorSuccess(operation, apiResponse.body());
        }
        JsonNode response = apiResponse.body();

        Map<String, Object> localCard = null;
        if (operation == VmCardOperation.CREATE_CARD) {
            localCard = saveCreatedCard(safeBody, response, token.token());
            if (extractCardId(response) != null) {
                productCodeService.decrementRemaining(normalizedEnvironment(), selectedProductCode);
            }
        }
        updateLocalCardState(operation, safeBody);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation.id());
        result.put("environment", normalizedEnvironment());
        result.put("decrypted", apiResponse.decrypted());
        result.put("response", response);
        if (operation == VmCardOperation.PRODUCT_CODES) {
            result.put("synchronizedProducts", synchronizedProducts);
        }
        if (selectedProductCode != null) {
            result.put("selectedProductCode", selectedProductCode);
        }
        if (localCard != null) {
            result.put("localCard", localCard);
        }
        result.put("executedAt", Instant.now().toString());
        return result;
    }

    /**
     * Fetches the current VMCard product catalog and applies it to the local
     * product-code table. Scheduled and manual refreshes share the same monitor
     * so a slow vendor request cannot run two destructive catalog syncs at once.
     */
    public int synchronizeProductCodes() {
        TokenResult token = accessToken();
        return fetchAndSyncProductCodes(token.token()).synchronizedProducts();
    }

    boolean productCodeSyncReady() {
        return enabled && credentialsConfigured() && encryptionConfigured();
    }

    public String webhookSecret() {
        return webhookSecret;
    }

    public String currentEnvironment() {
        return normalizedEnvironment();
    }

    /**
     * Creates exactly one card and returns its provider id. The caller must
     * persist the id before requesting card details so a retry can reuse the
     * already-created card instead of issuing another one.
     */
    public String createCheckoutCard(Map<String, Object> body) {
        requireConfigured();
        if (!mutationsAllowed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "VMCard mutation calls are locked by the environment safety switches");
        }
        savedCardService.requireSecureStorage();

        Map<String, Object> request = body == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(body);
        TokenResult token = accessToken();
        String productCode = selectProductCode(request, token.token());
        request.put("product_code", productCode);

        ApiResponse created = invoke(VmCardOperation.CREATE_CARD.path(), request, token.token());
        requireVendorSuccess(VmCardOperation.CREATE_CARD, created.body());
        String cardId = extractCardId(created.body());
        if (cardId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "VMCard createCard succeeded without returning card_id");
        }

        // Save the create response before the detail request. This makes the
        // provider card id recoverable if the follow-up call fails.
        savedCardService.saveCreatedCard(
                normalizedEnvironment(), cardId, request, created.body(), null);
        productCodeService.decrementRemaining(normalizedEnvironment(), productCode);
        return cardId;
    }

    /**
     * Fetches the card detail requested by the workflow, stores the full vendor
     * response encrypted, and exposes only an in-memory normalized record.
     */
    public VmCardCheckoutCard getCheckoutCardDetail(String cardId, Map<String, Object> createRequest) {
        requireConfigured();
        String normalizedCardId = cardId == null ? "" : cardId.trim();
        if (normalizedCardId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "card_id is required");
        }
        savedCardService.requireSecureStorage();

        TokenResult token = accessToken();
        ApiResponse detail = invoke(
                VmCardOperation.CARD_DETAIL.path(),
                Map.of("card_id", normalizedCardId),
                token.token());
        requireVendorSuccess(VmCardOperation.CARD_DETAIL, detail.body());
        Map<String, Object> safeRequest = createRequest == null ? Map.of() : createRequest;
        savedCardService.saveCreatedCard(
                normalizedEnvironment(), normalizedCardId, safeRequest, null, detail.body());
        return normalizeCheckoutCard(normalizedCardId, detail.body(), safeRequest);
    }

    private VmCardCheckoutCard normalizeCheckoutCard(String cardId,
                                                     JsonNode response,
                                                     Map<String, Object> request) {
        JsonNode data = response == null ? null : response.path("data");
        if (data != null && data.has("card") && data.path("card").isObject()) {
            data = data.path("card");
        }
        String number = requiredCardField(data, "card_number", "card_no", "number");
        String cvc = requiredCardField(data, "cvv", "cvc", "security_code");
        String expiry = firstCardField(data,
                "card_expiry", "expiry", "expire_date", "expired_date",
                "expiration_date", "valid_thru");
        if (expiry == null) {
            String month = firstCardField(data,
                    "expire_month", "expiry_month", "expired_month", "expiration_month");
            String year = firstCardField(data,
                    "expire_year", "expiry_year", "expired_year", "expiration_year");
            if (month != null && year != null) {
                expiry = String.format("%02d/%s", Integer.parseInt(month), year);
            }
        }
        if (expiry == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "VMCard cardDetail did not include the card expiry");
        }
        if (expiry.matches("^\\d{4}[-/]\\d{1,2}$")) {
            String[] parts = expiry.split("[-/]");
            expiry = String.format("%02d/%s", Integer.parseInt(parts[1]), parts[0]);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> address = request.get("card_address") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.of();
        String firstName = textValue(request.get("first_name"));
        String lastName = textValue(request.get("last_name"));
        String billingName = (firstName + " " + lastName).trim();
        return new VmCardCheckoutCard(
                cardId,
                number,
                expiry,
                cvc,
                billingName,
                textValue(address.get("address_line_one")),
                textValue(address.get("city")),
                textValue(address.get("state")),
                textValue(address.get("post_code")),
                textValue(address.get("country"))
        );
    }

    private String requiredCardField(JsonNode data, String... names) {
        String value = firstCardField(data, names);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "VMCard cardDetail is missing required payment fields");
        }
        return value;
    }

    private String firstCardField(JsonNode data, String... names) {
        if (data == null || data.isMissingNode() || data.isNull()) return null;
        for (String name : names) {
            String value = data.path(name).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return null;
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void validateRequest(VmCardOperation operation, Map<String, Object> request) {
        if (!operation.requiresCardId()) return;
        String cardId = request.get("card_id") == null ? "" : request.get("card_id").toString().trim();
        if (cardId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "card_id is required for VMCard " + operation.id()
                            + "; use the card_id returned by createCard or getCardList, not the card number");
        }
        request.put("card_id", cardId);
    }

    private void updateLocalCardState(VmCardOperation operation, Map<String, Object> request) {
        String cardId = request.get("card_id") == null ? "" : request.get("card_id").toString().trim();
        if (cardId.isBlank()) return;
        if (operation == VmCardOperation.DELETE_CARD) {
            savedCardService.updateDisabledOrFrozenAt(normalizedEnvironment(), cardId, true);
            return;
        }
        if (operation != VmCardOperation.FREEZE_CARD) return;
        String status = request.get("status") == null ? "" : request.get("status").toString().trim();
        if ("ACTIVE".equalsIgnoreCase(status)) {
            savedCardService.updateDisabledOrFrozenAt(normalizedEnvironment(), cardId, false);
        } else if ("CANCELLED".equalsIgnoreCase(status)
                || "FROZEN".equalsIgnoreCase(status)
                || "FREEZE".equalsIgnoreCase(status)) {
            savedCardService.updateDisabledOrFrozenAt(normalizedEnvironment(), cardId, true);
        }
    }

    private String selectProductCode(Map<String, Object> request, String token) {
        String requested = request.get("product_code") == null
                ? ""
                : request.get("product_code").toString().trim();
        String selected = findProductCode(requested);
        if (selected != null) return selected;

        ApiResponse refreshResponse = invoke(VmCardOperation.PRODUCT_CODES.path(), Map.of(), token);
        requireVendorSuccess(VmCardOperation.PRODUCT_CODES, refreshResponse.body());
        syncProductCodes(refreshResponse.body());

        selected = findProductCode(requested);
        if (selected != null) return selected;

        if (!requested.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Requested VMCard product code is disabled, unavailable, or has no remaining card quota");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No enabled VMCard product code has remaining card quota");
    }

    private String findProductCode(String requested) {
        VmCardProductCode product = productCodeService.findUsable(normalizedEnvironment(), requested).orElse(null);
        return product == null ? null : product.getProductCode();
    }

    private int syncProductCodes(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        JsonNode list = data != null && data.isArray() ? data : data == null ? null : data.path("list");
        return productCodeService.sync(normalizedEnvironment(), list);
    }

    private ProductCodeSync fetchAndSyncProductCodes(String token) {
        synchronized (productCodeSyncMonitor) {
            ApiResponse apiResponse = invoke(VmCardOperation.PRODUCT_CODES.path(), Map.of(), token);
            requireVendorSuccess(VmCardOperation.PRODUCT_CODES, apiResponse.body());
            int synchronizedProducts = syncProductCodes(apiResponse.body());
            return new ProductCodeSync(apiResponse, synchronizedProducts);
        }
    }

    private ApiResponse invoke(String path, Map<String, Object> body, String token) {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        WebClient.RequestBodySpec request = webClient.post()
                .uri(baseUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("X-Client-Request-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON);

        JsonNode response;
        try {
            if (safeBody.isEmpty()) {
                response = request.retrieve().bodyToMono(JsonNode.class).block(timeout());
            } else {
                requireEncryptionConfigured();
                String json = objectMapper.writeValueAsString(safeBody);
                if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VMCard request body is too large");
                }
                String content = cryptoService.encrypt(json, publicKey);
                response = request.bodyValue(Map.of("content", content))
                        .retrieve().bodyToMono(JsonNode.class).block(timeout());
            }
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to serialize VMCard request", exception);
        } catch (WebClientResponseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "VMCard returned HTTP " + exception.getStatusCode().value(), exception);
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException statusException) throw statusException;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "VMCard request failed", exception);
        }
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "VMCard returned an empty response");
        }

        boolean decrypted = decryptDataIfNeeded(response);
        return new ApiResponse(response, decrypted);
    }

    private Map<String, Object> saveCreatedCard(Map<String, Object> request,
                                                JsonNode createResponse,
                                                String token) {
        String cardId = extractCardId(createResponse);
        if (cardId == null) return null;

        JsonNode detailResponse = null;
        String warning = null;
        try {
            detailResponse = invoke(VmCardOperation.CARD_DETAIL.path(), Map.of("card_id", cardId), token).body();
            requireVendorSuccess(VmCardOperation.CARD_DETAIL, detailResponse);
        } catch (RuntimeException exception) {
            // The card has already been created. Do not fail the whole request or encourage a duplicate retry.
            warning = "Card created, but the follow-up card detail request failed";
        }

        try {
            Map<String, Object> saved = new LinkedHashMap<>(savedCardService.saveCreatedCard(
                    normalizedEnvironment(), cardId, request, createResponse, detailResponse));
            if (warning != null) saved.put("warning", warning);
            return saved;
        } catch (RuntimeException exception) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("saved", false);
            failed.put("cardId", cardId);
            failed.put("detailSaved", detailResponse != null);
            failed.put("warning", "Card created, but encrypted local storage failed; do not submit createCard again");
            return failed;
        }
    }

    private String extractCardId(JsonNode response) {
        if (response == null) return null;
        String cardId = response.path("data").path("card_id").asText("");
        if (cardId.isBlank()) cardId = response.path("card_id").asText("");
        return cardId.isBlank() ? null : cardId;
    }

    void requireVendorSuccess(VmCardOperation operation, JsonNode response) {
        JsonNode codeNode = response == null ? null : response.get("code");
        if (codeNode == null || codeNode.isNull()) {
            // updateCardLimit is documented to return an empty JSON object on success.
            return;
        }
        int code = codeNode.asInt(Integer.MIN_VALUE);
        if (code == 0 || code == 200) return;

        String message = response.path("msg").asText("").trim();
        if (message.isBlank()) message = response.path("message").asText("").trim();
        if (message.length() > 300) message = message.substring(0, 300);
        String detail = "VMCard " + operation.id() + " failed (code " + codeNode.asText() + ")";
        if (!message.isBlank()) detail += ": " + message;
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    private TokenResult accessToken() {
        requireConfigured();
        CachedToken current = cachedToken;
        Instant now = Instant.now();
        if (current != null && current.expiresAt().isAfter(now.plusSeconds(60))) {
            return new TokenResult(current.token(), current.expiresAt());
        }
        synchronized (this) {
            current = cachedToken;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
                return new TokenResult(current.token(), current.expiresAt());
            }
            JsonNode response;
            try {
                response = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host(baseUrl().substring("https://".length()))
                                .path("/getAccessToken")
                                .queryParam("app_id", appId)
                                .queryParam("app_secret", appSecret)
                                .build())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(timeout());
            } catch (WebClientResponseException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "VMCard token endpoint returned HTTP " + exception.getStatusCode().value(), exception);
            } catch (RuntimeException exception) {
                if (exception instanceof ResponseStatusException statusException) throw statusException;
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to obtain VMCard access token", exception);
            }
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "VMCard token endpoint returned an empty response");
            }
            if (response.path("code").asInt(-1) != 0 || response.path("data").path("token").asText().isBlank()) {
                String vendorCode = response.path("code").asText("unknown");
                String vendorMessage = response.path("msg").asText("");
                if (vendorMessage.isBlank()) {
                    vendorMessage = response.path("message").asText("");
                }
                String detail = vendorMessage.isBlank()
                        ? "VMCard token request failed (code " + vendorCode + ")"
                        : "VMCard token request failed (code " + vendorCode + "): " + vendorMessage;
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, detail);
            }
            String token = response.path("data").path("token").asText();
            long epochSeconds = response.path("data").path("expired_time").asLong(0);
            Instant expiresAt = epochSeconds > Instant.now().getEpochSecond()
                    ? Instant.ofEpochSecond(epochSeconds)
                    : Instant.now().plusSeconds(300);
            cachedToken = new CachedToken(token, expiresAt);
            return new TokenResult(token, expiresAt);
        }
    }

    private boolean decryptDataIfNeeded(JsonNode response) {
        JsonNode data = response.get("data");
        if (data == null || !data.isTextual()) return false;
        String ciphertext = data.asText();
        if (ciphertext.length() < 128 || (ciphertext.length() & 1) != 0 || !ciphertext.matches("[0-9a-fA-F]+")) {
            return false;
        }
        requireEncryptionConfigured();
        String plaintext = cryptoService.decrypt(ciphertext, privateKey);
        try {
            JsonNode decoded = objectMapper.readTree(plaintext);
            if (response instanceof ObjectNode objectNode) {
                objectNode.set("data", decoded);
            }
            return true;
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "VMCard response decrypted to invalid JSON", exception);
        }
    }

    private void requireConfigured() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "VMCard integration is disabled");
        }
        if (!credentialsConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "VMCard API credentials are incomplete");
        }
    }

    private void requireEncryptionConfigured() {
        if (!encryptionConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "VMCard RSA keys are incomplete");
        }
    }

    private boolean credentialsConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    private boolean encryptionConfigured() {
        return publicKey != null && !publicKey.isBlank() && privateKey != null && !privateKey.isBlank();
    }

    private boolean mutationsAllowed() {
        return allowMutations && ("sandbox".equals(normalizedEnvironment()) || allowProductionMutations);
    }

    private String normalizedEnvironment() {
        return "production".equalsIgnoreCase(environment) ? "production" : "sandbox";
    }

    private String baseUrl() {
        return "production".equals(normalizedEnvironment()) ? PRODUCTION_URL : SANDBOX_URL;
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(5, Math.min(120, requestTimeoutSeconds)));
    }

    private record CachedToken(String token, Instant expiresAt) {
    }

    private record TokenResult(String token, Instant expiresAt) {
    }

    private record ApiResponse(JsonNode body, boolean decrypted) {
    }

    private record ProductCodeSync(ApiResponse apiResponse, int synchronizedProducts) {
    }

}
