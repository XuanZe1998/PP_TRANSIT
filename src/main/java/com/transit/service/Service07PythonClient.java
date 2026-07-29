package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class Service07PythonClient {
    private final WebClient webClient;
    private final String internalToken;
    private final Duration timeout;

    public Service07PythonClient(
            @Value("${payment-service.url:http://127.0.0.1:5000}") String baseUrl,
            @Value("${payment-service.internal-token:}") String internalToken,
            @Value("${payment-service.fulfillment-timeout-seconds:240}") long timeoutSeconds) {
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.timeout = Duration.ofSeconds(Math.max(30, Math.min(timeoutSeconds, 600)));
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(this.timeout);
        this.webClient = WebClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .clientConnector(new ReactorClientHttpConnector(client))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    public String generateCheckoutLink(String session, String country) {
        JsonNode response = post("/api/internal/service-07/checkout-link", Map.of(
                "session", session,
                "country", country == null ? "US" : country));
        String url = response.path("url").asText("").trim();
        if (!url.matches("^https://[^\\s]+$") || url.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Python fulfillment service returned an invalid checkout URL");
        }
        return url;
    }

    public String autoFill(String session, String checkoutUrl, VmCardCheckoutCard card) {
        if (internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Python fulfillment integration is not configured");
        }
        Map<String, Object> cardPayload = new LinkedHashMap<>();
        cardPayload.put("number", card.number());
        cardPayload.put("expiry", card.expiry());
        cardPayload.put("cvc", card.cvc());
        cardPayload.put("name", card.billingName());
        cardPayload.put("address", card.billingAddress());
        cardPayload.put("city", card.billingCity());
        cardPayload.put("state", card.billingState());
        cardPayload.put("zip", card.billingZip());
        cardPayload.put("country", card.billingCountry());

        JsonNode response = post("/api/internal/service-07/autofill", Map.of(
                "session", session,
                "checkout_url", checkoutUrl,
                "card", cardPayload));
        String result = response.path("result_label").asText("subscription-complete").trim();
        return result.isBlank() ? "subscription-complete" : result.substring(0, Math.min(result.length(), 120));
    }

    private JsonNode post(String path, Map<String, Object> body) {
        if (internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Python fulfillment integration is not configured");
        }
        JsonNode response;
        try {
            response = webClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(timeout);
        } catch (WebClientResponseException exception) {
            String reason = exception.getResponseBodyAsString();
            if (reason.length() > 300) reason = reason.substring(0, 300);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Python fulfillment service rejected the request: " + reason, exception);
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException statusException) throw statusException;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Python fulfillment service is unavailable", exception);
        }
        if (response == null || !response.path("ok").asBoolean(false)) {
            String error = response == null ? "empty response" : response.path("error").asText("unknown error");
            if (error.length() > 300) error = error.substring(0, 300);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Subscription automation failed: " + error);
        }
        return response;
    }

    private String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        if (!result.matches("^https?://[^\\s]+$")) {
            throw new IllegalArgumentException("payment-service.url must be an HTTP(S) URL");
        }
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
