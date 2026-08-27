package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.VmCardWebhookEventMapper;
import com.transit.model.VmCardWebhookEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VmCardWebhookService {
    private final VmCardWebhookEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final ChannelSecretService secretService;
    private final VmCardClientService clientService;

    public void receive(String suppliedSecret, JsonNode payload) {
        requireWebhookSecret(suppliedSecret);
        if (payload == null || !payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook payload must be a JSON object");
        }
        if (!secretService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sensitive payload encryption is not configured");
        }
        if (payload.toString().getBytes(StandardCharsets.UTF_8).length > 32_768) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "VMCard webhook payload is too large");
        }
        String eventType = "Card3ds".equalsIgnoreCase(payload.path("business_type").asText())
                ? "CARD_3DS" : "TRANSACTION";
        String externalId = externalId(eventType, payload);
        Long existing = eventMapper.selectCount(new LambdaQueryWrapper<VmCardWebhookEvent>()
                .eq(VmCardWebhookEvent::getEventType, eventType)
                .eq(VmCardWebhookEvent::getExternalId, externalId));
        if (existing != null && existing > 0) return;

        VmCardWebhookEvent event = VmCardWebhookEvent.builder()
                .eventType(eventType)
                .externalId(externalId)
                .encryptedPayload(secretService.encrypt(payload.toString()))
                .receivedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // VMCard retries webhooks when it does not receive the acknowledgement.
        }
    }

    public List<Map<String, Object>> recentEvents() {
        return eventMapper.selectList(new LambdaQueryWrapper<VmCardWebhookEvent>()
                        .orderByDesc(VmCardWebhookEvent::getReceivedAt)
                        .last("LIMIT 100"))
                .stream()
                .map(this::toView)
                .toList();
    }

    private Map<String, Object> toView(VmCardWebhookEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", event.getId());
        view.put("eventType", event.getEventType());
        view.put("externalId", event.getExternalId());
        view.put("receivedAt", event.getReceivedAt());
        try {
            view.put("payload", objectMapper.readTree(secretService.decrypt(event.getEncryptedPayload())));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored VMCard webhook payload is invalid", exception);
        }
        return view;
    }

    private String externalId(String eventType, JsonNode payload) {
        if ("TRANSACTION".equals(eventType)) {
            String authId = payload.path("auth_id").asText();
            if (!authId.isBlank()) return authId;
        }
        return sha256(payload.toString());
    }

    private void requireWebhookSecret(String suppliedSecret) {
        String configured = clientService.webhookSecret();
        if (configured == null || configured.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "VMCard webhook is not configured");
        }
        byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedSecret == null ? new byte[0] : suppliedSecret.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook endpoint not found");
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
