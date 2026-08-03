package com.transit.service;

import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.ModelPriceTierMapper;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelPriceTierServiceTests {

    @Mock private ModelPriceTierMapper tierMapper;
    @Mock private ModelMappingMapper mappingMapper;

    private ModelPriceTierService service;

    @BeforeEach
    void setUp() {
        service = new ModelPriceTierService(tierMapper, mappingMapper);
    }

    @Test
    void selectsTheFirstTierWhoseContextUpperBoundContainsTheRequest() {
        ModelMapping mapping = ModelMapping.builder()
                .id(7L)
                .priceTiers(List.of(
                        tier("短上下文", 128_000, 1),
                        tier("长上下文", null, 4)))
                .build();

        assertThat(service.select(mapping, 128_000).getTierName()).isEqualTo("短上下文");
        assertThat(service.select(mapping, 128_001).getTierName()).isEqualTo("长上下文");
        assertThat(service.select(mapping, 2_000_000).getTierName()).isEqualTo("长上下文");
    }

    @Test
    void synchronizingTiersMirrorsThePrimaryTierIntoLegacyMappingColumns() {
        ModelMapping mapping = ModelMapping.builder()
                .id(9L)
                .inputPricePerMillion(new BigDecimal("1"))
                .outputPricePerMillion(new BigDecimal("1"))
                .build();
        ModelPriceTier requested = tier("默认", null, 0);
        requested.setCostInputPrice(new BigDecimal("2"));
        requested.setCostOutputPrice(new BigDecimal("3"));
        requested.setSaleInputPrice(new BigDecimal("5"));
        requested.setSaleOutputPrice(new BigDecimal("7"));
        when(tierMapper.insert(any(ModelPriceTier.class))).thenAnswer(invocation -> 1);

        service.synchronize(mapping, List.of(requested));

        assertThat(mapping.getInputPricePerMillion()).isEqualByComparingTo("5");
        assertThat(mapping.getOutputPricePerMillion()).isEqualByComparingTo("7");
        assertThat(mapping.getInputCostPerMillion()).isEqualByComparingTo("2");
        assertThat(mapping.getOutputCostPerMillion()).isEqualByComparingTo("3");
        ArgumentCaptor<ModelMapping> updated = ArgumentCaptor.forClass(ModelMapping.class);
        org.mockito.Mockito.verify(mappingMapper).updateById(updated.capture());
        assertThat(updated.getValue().getInputPricePerMillion()).isEqualByComparingTo("5");
    }

    @Test
    void rejectsAFiniteLastTierBecauseItWouldLeaveTheContextRangeUnpriced() {
        ModelMapping mapping = ModelMapping.builder().id(11L).build();
        ModelPriceTier finite = tier("唯一挡位", 128_000, 0);

        assertThatThrownBy(() -> service.synchronize(mapping, List.of(finite)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("上下文");
    }

    private ModelPriceTier tier(String name, Integer maxContext, int order) {
        return ModelPriceTier.builder()
                .tierName(name)
                .maxContextTokens(maxContext)
                .sortOrder(order)
                .officialGroupName("官网")
                .costGroupName("成本")
                .saleGroupName("售价")
                .officialInputPrice(BigDecimal.ZERO)
                .officialOutputPrice(BigDecimal.ZERO)
                .officialCacheReadPrice(BigDecimal.ZERO)
                .officialCacheWritePrice(BigDecimal.ZERO)
                .costInputPrice(BigDecimal.ZERO)
                .costOutputPrice(BigDecimal.ZERO)
                .costCacheReadPrice(BigDecimal.ZERO)
                .costCacheWritePrice(BigDecimal.ZERO)
                .saleInputPrice(BigDecimal.ZERO)
                .saleOutputPrice(BigDecimal.ZERO)
                .saleCacheReadPrice(BigDecimal.ZERO)
                .saleCacheWritePrice(BigDecimal.ZERO)
                .build();
    }
}
