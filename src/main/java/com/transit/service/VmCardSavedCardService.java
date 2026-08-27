package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.mapper.VmCardSavedCardMapper;
import com.transit.model.VmCardSavedCard;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VmCardSavedCardService {
    private static final int MAX_PAYLOAD_BYTES = 131_072;

    private final VmCardSavedCardMapper cardMapper;
    private final ObjectMapper objectMapper;
    private final ChannelSecretService secretService;

    public void requireSecureStorage() {
        if (!secretService.isConfigured()) {
            throw new IllegalStateException(
                    "Sensitive card storage encryption is not configured; set security.data-encryption-key");
        }
    }

    @Transactional
    public Map<String, Object> saveCreatedCard(String environment,
                                                String cardId,
                                                Map<String, Object> request,
                                                JsonNode createResponse,
                                                JsonNode cardDetailResponse) {
        requireSecureStorage();
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException("VMCard create response did not include card_id");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("request", objectMapper.valueToTree(request == null ? Map.of() : request));
        payload.set("createResponse", createResponse == null ? objectMapper.nullNode() : createResponse.deepCopy());
        if (cardDetailResponse != null) {
            payload.set("cardDetailResponse", cardDetailResponse.deepCopy());
        }
        payload.put("savedAt", LocalDateTime.now(ZoneOffset.UTC).toString());

        String plaintext = payload.toString();
        if (plaintext.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("VMCard card payload is too large to save locally");
        }

        String normalizedEnvironment = "production".equalsIgnoreCase(environment) ? "production" : "sandbox";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        VmCardSavedCard existing = cardMapper.selectOne(new LambdaQueryWrapper<VmCardSavedCard>()
                .eq(VmCardSavedCard::getEnvironment, normalizedEnvironment)
                .eq(VmCardSavedCard::getCardId, cardId)
                .last("LIMIT 1"));

        VmCardSavedCard card = existing == null ? VmCardSavedCard.builder()
                .cardId(cardId)
                .environment(normalizedEnvironment)
                .createdAt(now)
                .build() : existing;
        card.setLabel(text(request, "label", 160));
        card.setProductCode(text(request, "product_code", 120));
        card.setEmail(text(request, "email", 254));
        LocalDateTime providerCreatedAt = extractCardCreatedAt(cardDetailResponse);
        if (providerCreatedAt != null) {
            card.setCardCreatedAt(providerCreatedAt);
        } else if (card.getCardCreatedAt() == null) {
            card.setCardCreatedAt(now);
        }
        card.setEncryptedPayload(secretService.encrypt(plaintext));
        card.setUpdatedAt(now);

        try {
            if (card.getId() == null) {
                cardMapper.insert(card);
            } else {
                cardMapper.updateById(card);
            }
        } catch (DuplicateKeyException duplicate) {
            VmCardSavedCard concurrent = cardMapper.selectOne(new LambdaQueryWrapper<VmCardSavedCard>()
                    .eq(VmCardSavedCard::getEnvironment, normalizedEnvironment)
                    .eq(VmCardSavedCard::getCardId, cardId)
                    .last("LIMIT 1"));
            if (concurrent == null) throw duplicate;
            card.setId(concurrent.getId());
            card.setCreatedAt(concurrent.getCreatedAt());
            cardMapper.updateById(card);
        }
        return summary(card, cardDetailResponse != null);
    }

    @Transactional
    public void updateDisabledOrFrozenAt(String environment, String cardId, boolean disabledOrFrozen) {
        if (cardId == null || cardId.isBlank()) return;
        cardMapper.update(null, new LambdaUpdateWrapper<VmCardSavedCard>()
                .eq(VmCardSavedCard::getEnvironment, normalizeEnvironment(environment))
                .eq(VmCardSavedCard::getCardId, cardId.trim())
                .set(VmCardSavedCard::getDisabledOrFrozenAt,
                        disabledOrFrozen ? LocalDateTime.now(ZoneOffset.UTC) : null)
                .set(VmCardSavedCard::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
    }

    public List<Map<String, Object>> recentCards() {
        return cardMapper.selectList(new LambdaQueryWrapper<VmCardSavedCard>()
                        .orderByDesc(VmCardSavedCard::getCreatedAt)
                        .last("LIMIT 100"))
                .stream()
                .map(this::toView)
                .toList();
    }

    private Map<String, Object> toView(VmCardSavedCard card) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", card.getId());
        view.put("cardId", card.getCardId());
        view.put("environment", card.getEnvironment());
        view.put("label", card.getLabel());
        view.put("productCode", card.getProductCode());
        view.put("email", card.getEmail());
        view.put("cardCreatedAt", card.getCardCreatedAt());
        view.put("disabledOrFrozenAt", card.getDisabledOrFrozenAt());
        view.put("createdAt", card.getCreatedAt());
        view.put("updatedAt", card.getUpdatedAt());
        try {
            view.put("payload", objectMapper.readTree(secretService.decrypt(card.getEncryptedPayload())));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored VMCard card payload is invalid", exception);
        }
        return view;
    }

    private Map<String, Object> summary(VmCardSavedCard card, boolean detailSaved) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("id", card.getId());
        result.put("cardId", card.getCardId());
        result.put("detailSaved", detailSaved);
        result.put("email", card.getEmail());
        result.put("cardCreatedAt", card.getCardCreatedAt());
        result.put("disabledOrFrozenAt", card.getDisabledOrFrozenAt());
        return result;
    }

    private LocalDateTime extractCardCreatedAt(JsonNode cardDetailResponse) {
        if (cardDetailResponse == null) return null;
        JsonNode value = cardDetailResponse.path("data").path("create_time");
        if (value.isNumber()) {
            long epoch = value.asLong();
            if (epoch <= 0) return null;
            if (epoch > 10_000_000_000L) epoch /= 1000;
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC);
        }
        String raw = value.asText("").trim();
        if (raw.isBlank()) return null;
        if (raw.matches("\\d{10,13}")) {
            long epoch = Long.parseLong(raw);
            if (raw.length() == 13) epoch /= 1000;
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC);
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(raw), ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Try the provider's non-zone date formats below.
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))) {
            try {
                return LocalDateTime.parse(raw, formatter);
            } catch (DateTimeParseException ignored) {
                // Continue with the next supported provider format.
            }
        }
        try {
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String text(Map<String, Object> request, String key, int maxLength) {
        if (request == null || request.get(key) == null) return null;
        String value = String.valueOf(request.get(key)).trim();
        if (value.isBlank()) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String normalizeEnvironment(String environment) {
        return "production".equalsIgnoreCase(environment) ? "production" : "sandbox";
    }
}
