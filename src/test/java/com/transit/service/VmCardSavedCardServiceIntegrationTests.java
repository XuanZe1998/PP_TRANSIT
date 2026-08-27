package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.VmCardSavedCardMapper;
import com.transit.model.VmCardSavedCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VmCardSavedCardServiceIntegrationTests {
    private static final String CARD_ID = "saved-card-integration-test";

    @Autowired
    private VmCardSavedCardService savedCardService;

    @Autowired
    private VmCardSavedCardMapper cardMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        cardMapper.delete(new LambdaQueryWrapper<VmCardSavedCard>()
                .eq(VmCardSavedCard::getEnvironment, "sandbox")
                .eq(VmCardSavedCard::getCardId, CARD_ID));
    }

    @Test
    void keepsOneRowPerCardAndTracksFreezeAndUnfreezeDates() throws Exception {
        var createResponse = objectMapper.readTree("""
                {"code":0,"data":{"card_id":"saved-card-integration-test"}}
                """);
        var detailResponse = objectMapper.readTree("""
                {
                  "code":0,
                  "data":{
                    "card_id":"saved-card-integration-test",
                    "create_time":"2026-07-24 15:00:00"
                  }
                }
                """);
        Map<String, Object> request = Map.of(
                "product_code", "S5395YL",
                "email", "integration@example.com");

        savedCardService.saveCreatedCard("sandbox", CARD_ID, request, createResponse, detailResponse);
        savedCardService.saveCreatedCard("sandbox", CARD_ID, request, createResponse, detailResponse);

        assertThat(cardMapper.selectCount(query())).isEqualTo(1);
        VmCardSavedCard stored = cardMapper.selectOne(query());
        assertThat(stored.getEmail()).isEqualTo("integration@example.com");
        assertThat(stored.getCardCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 24, 15, 0));
        assertThat(stored.getDisabledOrFrozenAt()).isNull();

        savedCardService.updateDisabledOrFrozenAt("sandbox", CARD_ID, true);
        assertThat(cardMapper.selectOne(query()).getDisabledOrFrozenAt()).isNotNull();

        savedCardService.updateDisabledOrFrozenAt("sandbox", CARD_ID, false);
        assertThat(cardMapper.selectOne(query()).getDisabledOrFrozenAt()).isNull();
    }

    private LambdaQueryWrapper<VmCardSavedCard> query() {
        return new LambdaQueryWrapper<VmCardSavedCard>()
                .eq(VmCardSavedCard::getEnvironment, "sandbox")
                .eq(VmCardSavedCard::getCardId, CARD_ID);
    }
}
