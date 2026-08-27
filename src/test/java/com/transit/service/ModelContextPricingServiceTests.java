package com.transit.service;

import com.transit.mapper.ModelContextPricingPolicyMapper;
import com.transit.model.ModelContextPricingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelContextPricingServiceTests {
    private ModelContextPricingService service;

    @BeforeEach
    void setUp() {
        ModelContextPricingPolicyMapper mapper = mock(ModelContextPricingPolicyMapper.class);
        ModelContextPricingPolicy policy = new ModelContextPricingPolicy();
        policy.setPublicModelName("long-model");
        policy.setEnabled(true);
        policy.setThresholdTokens(128_000);
        policy.setMultiplier(ModelContextPricingService.FIXED_MULTIPLIER);
        when(mapper.selectOne(any())).thenReturn(policy);
        service = new ModelContextPricingService(mapper, mock(JdbcTemplate.class));
    }

    @Test
    void thresholdIsInclusiveOfBaseTierAndOnlyLargerInputUsesDoublePrice() {
        BigDecimal base = new BigDecimal("3.250000");

        assertThat(service.salePrice("long-model", 127_999, base)).isEqualByComparingTo("3.250000");
        assertThat(service.salePrice("long-model", 128_000, base)).isEqualByComparingTo("3.250000");
        assertThat(service.salePrice("long-model", 128_001, base)).isEqualByComparingTo("6.500000");
    }
}
