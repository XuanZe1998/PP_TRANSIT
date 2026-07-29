package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.VmCardSavedCardMapper;
import com.transit.model.VmCardSavedCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VmCardSavedCardServiceTests {
    @Mock private VmCardSavedCardMapper cardMapper;

    private ObjectMapper objectMapper;
    private ChannelSecretService secretService;
    private VmCardSavedCardService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        secretService = new ChannelSecretService(key);
        service = new VmCardSavedCardService(cardMapper, objectMapper, secretService);
    }

    @Test
    void encryptsCreatedCardDataAtRestAndDecryptsItForAdminView() throws Exception {
        when(cardMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            VmCardSavedCard card = invocation.getArgument(0);
            card.setId(17L);
            return 1;
        }).when(cardMapper).insert(any(VmCardSavedCard.class));

        JsonNode createResponse = objectMapper.readTree("""
                {"code":0,"data":{"card_id":"sandbox-card-1"}}
                """);
        JsonNode detailResponse = objectMapper.readTree("""
                {
                  "code":0,
                  "data":{
                    "card_id":"sandbox-card-1",
                    "card_number":"4111111111111111",
                    "cvv":"123",
                    "create_time":"2026-07-24 12:34:56"
                  }
                }
                """);

        Map<String, Object> summary = service.saveCreatedCard(
                "sandbox",
                "sandbox-card-1",
                Map.of(
                        "product_code", "TEST-USD",
                        "label", "local-test",
                        "email", "cardholder@example.com"),
                createResponse,
                detailResponse);

        ArgumentCaptor<VmCardSavedCard> captor = ArgumentCaptor.forClass(VmCardSavedCard.class);
        org.mockito.Mockito.verify(cardMapper).insert(captor.capture());
        VmCardSavedCard stored = captor.getValue();
        assertThat(stored.getEncryptedPayload()).startsWith("enc:v1:");
        assertThat(stored.getEncryptedPayload()).doesNotContain("4111111111111111", "123");
        assertThat(stored.getEmail()).isEqualTo("cardholder@example.com");
        assertThat(stored.getCardCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 24, 12, 34, 56));
        assertThat(stored.getDisabledOrFrozenAt()).isNull();
        assertThat(summary).containsEntry("saved", true).containsEntry("detailSaved", true);

        when(cardMapper.selectList(any())).thenReturn(List.of(stored));
        List<Map<String, Object>> views = service.recentCards();
        JsonNode payload = (JsonNode) views.get(0).get("payload");
        assertThat(payload.path("createResponse").path("data").path("card_id").asText())
                .isEqualTo("sandbox-card-1");
        assertThat(payload.path("cardDetailResponse").path("data").path("card_number").asText())
                .isEqualTo("4111111111111111");
        assertThat(views.get(0))
                .containsEntry("email", "cardholder@example.com")
                .containsEntry("cardCreatedAt", LocalDateTime.of(2026, 7, 24, 12, 34, 56));
    }
}
