package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.VmCardProductCodeMapper;
import com.transit.model.VmCardProductCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VmCardProductCodeServiceIntegrationTests {
    @Autowired
    private VmCardProductCodeService productCodeService;

    @Autowired
    private VmCardProductCodeMapper productCodeMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        productCodeMapper.delete(new LambdaQueryWrapper<VmCardProductCode>()
                .eq(VmCardProductCode::getEnvironment, "production"));
    }

    @Test
    void syncsAllFieldsAndRequiresAvailabilityAndPositiveRemainingQuota() throws Exception {
        int synchronizedCount = productCodeService.sync("production", objectMapper.readTree("""
                [
                  {
                    "bin": "539500",
                    "product_code": "S5395YL",
                    "type": "save",
                    "network": "mastercard",
                    "media": "virtual",
                    "issuing_area": "US",
                    "remaining_open_card_num": 2
                  },
                  {
                    "bin": "441100",
                    "product_code": "NO_QUOTA",
                    "type": "share",
                    "network": "visa",
                    "media": "virtual",
                    "issuing_area": "HK",
                    "remaining_open_card_num": 0
                  }
                ]
                """));

        assertThat(synchronizedCount).isEqualTo(2);
        VmCardProductCode selected = productCodeService.findUsable("production", "").orElseThrow();
        assertThat(selected)
                .extracting(VmCardProductCode::getBin, VmCardProductCode::getProductCode,
                        VmCardProductCode::getType, VmCardProductCode::getNetwork,
                        VmCardProductCode::getMedia, VmCardProductCode::getIssuingArea,
                        VmCardProductCode::getRemainingOpenCardNum, VmCardProductCode::getAvailable)
                .containsExactly("539500", "S5395YL", "save", "mastercard",
                        "virtual", "US", 2, true);
        assertThat(productCodeService.findUsable("production", "NO_QUOTA")).isEmpty();

        Map<String, Object> disabled = productCodeService.setAvailability(
                selected.getId(), "production", false);
        assertThat(disabled).containsEntry("available", false).containsEntry("usable", false);
        assertThat(productCodeService.findUsable("production", "S5395YL")).isEmpty();

        productCodeService.sync("production", objectMapper.readTree("""
                [{"product_code":"S5395YL","remaining_open_card_num":5}]
                """));
        List<Map<String, Object>> records = productCodeService.list("production");
        Map<String, Object> refreshed = records.stream()
                .filter(item -> "S5395YL".equals(item.get("productCode")))
                .findFirst()
                .orElseThrow();
        assertThat(refreshed)
                .containsEntry("remainingOpenCardNum", 5)
                .containsEntry("available", false)
                .containsEntry("usable", false);
    }

    @Test
    void decrementsQuotaOnlyForAnEnabledProductWithRemainingQuota() throws Exception {
        productCodeService.sync("production", objectMapper.readTree("""
                [{"product_code":"AUTO_CODE","remaining_open_card_num":1}]
                """));

        productCodeService.decrementRemaining("production", "AUTO_CODE");

        assertThat(productCodeService.findUsable("production", "AUTO_CODE")).isEmpty();
        assertThat(productCodeService.list("production").get(0))
                .containsEntry("remainingOpenCardNum", 0)
                .containsEntry("usable", false);
    }
}
