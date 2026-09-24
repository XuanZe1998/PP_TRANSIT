package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class MoneyServiceTests {
    private final MoneyService service=new MoneyService(new BigDecimal("7.12345678"));

    @Test void returnsConfiguredBillingRate(){
        assertThat(service.usdCnyRate()).isEqualByComparingTo("7.12345678");
    }

    @Test void convertsUsdBillingUnitsWithCeilingRounding(){
        assertThat(service.usdToCnyAmount(123,service.usdCnyRate())).isEqualTo(877L);
        assertThat(service.usdToCnyAmount(0,service.usdCnyRate())).isZero();
    }

    @Test void rejectsInvalidBillingRate(){
        MoneyService invalid=new MoneyService(BigDecimal.ZERO);
        assertThatThrownBy(invalid::usdCnyRate).isInstanceOf(ResponseStatusException.class);
    }
}
