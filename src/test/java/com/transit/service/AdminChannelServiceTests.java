package com.transit.service;

import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.provider.ProviderGatewayFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminChannelServiceTests {

    @Mock private ChannelMapper channelMapper;
    @Mock private ModelMappingMapper modelMappingMapper;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ChannelUrlPolicy channelUrlPolicy;
    @Mock private ProviderGatewayFactory providerGatewayFactory;
    @Mock private ChannelSecretService channelSecretService;

    private AdminChannelService service;

    @BeforeEach
    void setUp() {
        service = new AdminChannelService(channelMapper, modelMappingMapper, jdbcTemplate,
                channelUrlPolicy, providerGatewayFactory, channelSecretService);
        org.mockito.Mockito.lenient().when(channelSecretService.isConfigured()).thenReturn(true);
        org.mockito.Mockito.lenient().when(channelSecretService.encrypt("provider-key")).thenReturn("encrypted-provider-key");
    }

    @Test
    void creatingAChannelAutomaticallyCreatesPricedMappingsForEveryDelimitedModel() {
        Channel request = channel("model-a、model-b\nmodel-a");
        request.setModelPricing(List.of(
                pricing("model-a", "2.5", "1", "2.5", "5"),
                pricing("model-b", "3", "2", "6", "9")
        ));
        doAnswer(invocation -> {
            Channel saved = invocation.getArgument(0);
            saved.setId(42L);
            return 1;
        }).when(channelMapper).insert(any(Channel.class));
        when(channelMapper.selectById(42L)).thenAnswer(invocation -> request);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(), List.of());

        Channel created = service.create(request);

        assertThat(created.getModels()).isEqualTo("model-a\nmodel-b");
        ArgumentCaptor<ModelMapping> mappings = ArgumentCaptor.forClass(ModelMapping.class);
        verify(modelMappingMapper, org.mockito.Mockito.times(2)).insert(mappings.capture());
        assertThat(mappings.getAllValues()).extracting(ModelMapping::getChannelModelName)
                .containsExactly("model-a", "model-b");
        assertThat(mappings.getAllValues().get(0).getInputPricePerMillion())
                .isEqualByComparingTo("2.5");
        assertThat(mappings.getAllValues().get(0).getInputCostPerMillion())
                .isEqualByComparingTo("1");
        assertThat(mappings.getAllValues().get(0).getPriceRatio())
                .isEqualByComparingTo("2.5");
    }

    @Test
    void updatingAChannelRemovesMappingsForModelsNoLongerOffered() {
        Channel current = channel("model-a\nold-model");
        current.setId(7L);
        current.setApiKey("encrypted-provider-key");
        Channel request = channel("model-a,model-b");
        request.setApiKey("");
        ModelMapping modelA = pricing("model-a", "2", "1", "2", "4");
        modelA.setId(11L);
        modelA.setChannelId(7L);
        ModelMapping oldModel = pricing("old-model", "1", "0", "1", "1");
        oldModel.setId(12L);
        oldModel.setChannelId(7L);
        when(channelMapper.selectById(7L)).thenReturn(current, current);
        when(modelMappingMapper.selectList(any()))
                .thenReturn(List.of(modelA, oldModel), List.of(modelA));

        service.update(7L, request);

        verify(modelMappingMapper).deleteById(12L);
        verify(modelMappingMapper).updateById(modelA);
        verify(modelMappingMapper).insert(org.mockito.ArgumentMatchers.argThat((ModelMapping mapping) ->
                "model-b".equals(mapping.getChannelModelName()) && mapping.getChannelId().equals(7L)));
    }

    @Test
    void changingOnlyTheModelDelimiterKeepsAHealthyChannelHealthy() {
        Channel current = channel("model-a\nmodel-b");
        current.setId(8L);
        current.setApiKey("encrypted-provider-key");
        current.setHealthStatus("HEALTHY");
        Channel request = channel("model-a、model-b");
        request.setApiKey("");
        when(channelMapper.selectById(8L)).thenReturn(current, current);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(), List.of());

        Channel updated = service.update(8L, request);

        assertThat(updated.getModels()).isEqualTo("model-a\nmodel-b");
        assertThat(updated.getHealthStatus()).isEqualTo("HEALTHY");
    }

    @Test
    void listingChannelsLoadsAllPricingRowsInOneBatch() {
        Channel first = channel("model-a");
        first.setId(1L);
        Channel second = channel("model-b");
        second.setId(2L);
        ModelMapping firstPricing = pricing("model-a", "2", "1", "2", "3");
        firstPricing.setChannelId(1L);
        ModelMapping secondPricing = pricing("model-b", "3", "2", "6", "9");
        secondPricing.setChannelId(2L);
        when(channelMapper.selectList(null)).thenReturn(List.of(first, second));
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(firstPricing, secondPricing));

        List<Channel> result = service.list();

        assertThat(result.get(0).getModelPricing()).containsExactly(firstPricing);
        assertThat(result.get(1).getModelPricing()).containsExactly(secondPricing);
        verify(modelMappingMapper, org.mockito.Mockito.times(1)).selectList(any());
    }

    @Test
    void deletingAChannelAlsoDeletesItsAutomaticallyOwnedMappings() {
        service.delete(19L);

        verify(modelMappingMapper).delete(any());
        verify(channelMapper).deleteById(19L);
    }

    private Channel channel(String models) {
        return Channel.builder()
                .name("supplier-key-1")
                .type("openai-compatible")
                .baseUrl("https://provider.example.com/v1")
                .apiKey("provider-key")
                .models(models)
                .enabled(true)
                .groupName("supplier")
                .weight(100)
                .autoDisable(true)
                .failureThreshold(3)
                .cooldownSeconds(60)
                .build();
    }

    private ModelMapping pricing(String model, String ratio, String inputCost,
                                 String inputSale, String outputSale) {
        return ModelMapping.builder()
                .publicModelName(model)
                .channelModelName(model)
                .priority(10)
                .enabled(true)
                .priceRatio(new BigDecimal(ratio))
                .costPerMillion(BigDecimal.ZERO)
                .inputPricePerMillion(new BigDecimal(inputSale))
                .outputPricePerMillion(new BigDecimal(outputSale))
                .cachedPricePerMillion(BigDecimal.ZERO)
                .inputCostPerMillion(new BigDecimal(inputCost))
                .outputCostPerMillion(new BigDecimal(inputCost))
                .cachedCostPerMillion(BigDecimal.ZERO)
                .billingEnabled(true)
                .trafficPercent(100)
                .build();
    }
}
