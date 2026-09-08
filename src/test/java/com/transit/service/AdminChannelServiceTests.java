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
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminChannelServiceTests {

    @Mock private ChannelMapper channelMapper;
    @Mock private ModelMappingMapper modelMappingMapper;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ChannelUrlPolicy channelUrlPolicy;
    @Mock private ProviderGatewayFactory providerGatewayFactory;
    @Mock private ChannelSecretService channelSecretService;
    @Mock private ModelPriceTierService priceTierService;
    @Mock private ApplicationEventPublisher events;
    @Mock private OpenAiImageHealthProbe imageHealthProbe;

    private AdminChannelService service;

    @BeforeEach
    void setUp() {
        service = new AdminChannelService(channelMapper, modelMappingMapper, jdbcTemplate,
                channelUrlPolicy, providerGatewayFactory, channelSecretService, priceTierService, events, imageHealthProbe);
        org.mockito.Mockito.lenient().when(channelSecretService.isConfigured()).thenReturn(true);
        org.mockito.Mockito.lenient().when(channelSecretService.encrypt("provider-key")).thenReturn("encrypted-provider-key");
    }

    @Test
    void groupTestSelectsChatModelInsteadOfFirstImageModel() {
        Channel channel = channel("image-model\nchat-model");
        channel.setId(42L);
        when(channelMapper.selectById(42L)).thenReturn(channel);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(
                ModelMapping.builder().channelModelName("image-model").protocols("images").build(),
                ModelMapping.builder().channelModelName("chat-model").protocols("chat-completions").build()));
        AdminChannelService spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(java.util.Map.of("healthStatus", "HEALTHY", "latencyMs", 1))
                .when(spy).testModel(org.mockito.ArgumentMatchers.eq(42L), any());
        spy.test(42L);
        verify(spy).testModel(42L, java.util.Map.of("providerModelName", "chat-model", "prompt", "你是什么模型", "timeoutSeconds", 20));
    }

    @Test
    void newApiGroupTestPrefersVerifiedModelAndFallsBackAfterTransientFailure() {
        Channel channel = channel("dynamic-model\nstable-model\nbackup-model");
        channel.setId(43L);
        channel.setSourceCode("new-api");
        ModelMapping dynamic = ModelMapping.builder().id(1L).channelModelName("dynamic-model")
                .protocols("chat-completions").pricingStatus("PENDING").build();
        ModelMapping stable = ModelMapping.builder().id(2L).channelModelName("stable-model")
                .protocols("chat-completions").pricingStatus("VERIFIED").build();
        ModelMapping backup = ModelMapping.builder().id(3L).channelModelName("backup-model")
                .protocols("chat-completions").pricingStatus("VERIFIED").build();
        when(channelMapper.selectById(43L)).thenReturn(channel);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(dynamic, stable, backup));
        AdminChannelService spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(java.util.Map.of("status", "UPSTREAM_ERROR", "healthStatus", "DEGRADED", "latencyMs", 1))
                .when(spy).testModel(43L, java.util.Map.of("providerModelName", "stable-model", "prompt", "你是什么模型", "timeoutSeconds", 60));
        org.mockito.Mockito.doReturn(java.util.Map.of("status", "SUCCESS", "healthStatus", "HEALTHY", "latencyMs", 2))
                .when(spy).testModel(43L, java.util.Map.of("providerModelName", "backup-model", "prompt", "你是什么模型", "timeoutSeconds", 60));

        assertThat(spy.test(43L).get("healthStatus")).isEqualTo("HEALTHY");

        verify(spy).testModel(43L, java.util.Map.of("providerModelName", "stable-model", "prompt", "你是什么模型", "timeoutSeconds", 60));
        verify(spy).testModel(43L, java.util.Map.of("providerModelName", "backup-model", "prompt", "你是什么模型", "timeoutSeconds", 60));
        verify(spy, never()).testModel(43L, java.util.Map.of("providerModelName", "dynamic-model", "prompt", "你是什么模型", "timeoutSeconds", 60));
    }

    @Test
    void imageProtocolUsesDedicatedImageProbe() {
        Channel channel = channel("gpt-image-2");
        channel.setId(44L);
        channel.setProtocolType("openai-image");
        when(channelMapper.selectById(44L)).thenReturn(channel);
        when(imageHealthProbe.probe(channel, "gpt-image-2", 30)).thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of(
                "status", "SUCCESS", "latencyMs", 3L, "model", "gpt-image-2", "usage", java.util.Map.of(),
                "sampleText", "Image generation response accepted", "error", "", "exitCode", 0)));

        assertThat(service.testModel(44L, java.util.Map.of("providerModelName", "gpt-image-2", "timeoutSeconds", 30))
                .get("healthStatus")).isEqualTo("HEALTHY");

        verify(imageHealthProbe).probe(channel, "gpt-image-2", 30);
        verify(providerGatewayFactory, never()).resolve(any());
    }

    @Test
    void imageGroupTestPrefersLowestResolutionModel() {
        Channel channel = channel("gpt-image-2\ngpt-image-2-1K\ngpt-image-2-2K\ngpt-image-2-4K");
        channel.setId(45L);
        channel.setSourceCode("new-api");
        channel.setProtocolType("openai-image");
        when(channelMapper.selectById(45L)).thenReturn(channel);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(
                imageMapping(1L, "gpt-image-2"), imageMapping(2L, "gpt-image-2-1K"),
                imageMapping(3L, "gpt-image-2-2K"), imageMapping(4L, "gpt-image-2-4K")));
        AdminChannelService spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(java.util.Map.of("status", "SUCCESS", "healthStatus", "HEALTHY", "latencyMs", 2))
                .when(spy).testModel(45L, java.util.Map.of("providerModelName", "gpt-image-2-1K",
                        "prompt", "你是什么模型", "timeoutSeconds", 120));

        assertThat(spy.test(45L).get("healthStatus")).isEqualTo("HEALTHY");
        verify(spy).testModel(45L, java.util.Map.of("providerModelName", "gpt-image-2-1K",
                "prompt", "你是什么模型", "timeoutSeconds", 120));
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
    void creatingAHaoeeChannelAssignsTheHaoeeCatalogSourceAutomatically() {
        Channel request = channel("model-a");
        request.setType("haoee");
        request.setBaseUrl("https://maas.haoee.com/v1");
        doAnswer(invocation -> {
            Channel saved = invocation.getArgument(0);
            saved.setId(43L);
            return 1;
        }).when(channelMapper).insert(any(Channel.class));
        when(channelMapper.selectById(43L)).thenAnswer(invocation -> request);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(), List.of());

        Channel created = service.create(request);

        assertThat(created.getSourceCode()).isEqualTo("haoee");
        assertThat(created.getSourceName()).isEqualTo("好易智算");
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
    void editingBoundNewApiChannelRepairsSourceWithoutChangingPublication() {
        Channel current = channel("");
        current.setId(35L);
        current.setSourceCode("other");
        current.setSourceName("其他兼容服务");
        current.setHealthStatus("HEALTHY");
        Channel request = channel("");
        request.setApiKey("");
        request.setEnabled(true);
        when(channelMapper.selectById(35L)).thenReturn(current);
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class), org.mockito.ArgumentMatchers.eq(35L)))
                .thenAnswer(invocation -> invocation.<String>getArgument(0).contains("new_api_connections") ? 1 : 0);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of());
        service.update(35L, request);
        assertThat(current.getSourceCode()).isEqualTo("new-api");
        assertThat(current.getSourceName()).isEqualTo("New API");
        assertThat(current.isEnabled()).isTrue();
        assertThat(current.getHealthStatus()).isEqualTo("HEALTHY");
    }

    @Test
    void savingAiApiBankCredentialSchedulesOnlyThatChannelCatalogSync() {
        Channel current = channel("");
        current.setId(9L);
        current.setSourceCode(AiApiBankCatalogService.SOURCE_CODE);
        current.setGroupName("gpt-low");
        current.setApiKey(null);
        Channel request = channel("");
        request.setGroupName("gpt-low");
        request.setApiKey("provider-key");
        when(channelMapper.selectById(9L)).thenReturn(current, current);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(), List.of());

        service.update(9L, request);

        verify(events).publishEvent(new AiApiBankCredentialConfiguredEvent(9L));
        assertThat(current.getSourceCode()).isEqualTo(AiApiBankCatalogService.SOURCE_CODE);
        assertThat(current.getSourceName()).isEqualTo(AiApiBankCatalogService.SOURCE_NAME);
    }

    @Test
    void savingCredentialRepairsLegacyAiApiBankChannelMetadata() {
        Channel current = channel("");
        current.setId(10L);
        current.setSourceCode("other");
        current.setSourceName("其他兼容服务");
        current.setGroupName("gpt-pro");
        Channel request = channel("");
        request.setGroupName("gpt-pro");
        request.setApiKey("provider-key");
        when(channelMapper.selectById(10L)).thenReturn(current, current);
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiapibank_provider_groups WHERE channel_id=?", Integer.class, 10L))
                .thenReturn(1);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(), List.of());

        service.update(10L, request);

        verify(events).publishEvent(new AiApiBankCredentialConfiguredEvent(10L));
        assertThat(current.getSourceCode()).isEqualTo(AiApiBankCatalogService.SOURCE_CODE);
        assertThat(current.getSourceName()).isEqualTo(AiApiBankCatalogService.SOURCE_NAME);
    }

    @Test
    void savingCredentialRecognizesLegacyAiApiBankChannelAfterGroupWasReboundToDuplicate() {
        Channel current = channel("");
        current.setId(11L);
        current.setName("AiAPIBank · GPT低价分组");
        current.setBaseUrl("https://aiapibank.com");
        current.setSourceCode("other");
        current.setGroupName("gpt-low");
        Channel request = channel("");
        request.setName(current.getName());
        request.setBaseUrl(current.getBaseUrl());
        request.setGroupName(current.getGroupName());
        request.setApiKey("provider-key");
        when(channelMapper.selectById(11L)).thenReturn(current, current);
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiapibank_provider_groups WHERE channel_id=?", Integer.class, 11L))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiapibank_provider_groups WHERE group_slug=?", Integer.class, "gpt-low"))
                .thenReturn(1);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(), List.of());

        service.update(11L, request);

        verify(events).publishEvent(new AiApiBankCredentialConfiguredEvent(11L));
        assertThat(current.getSourceCode()).isEqualTo(AiApiBankCatalogService.SOURCE_CODE);
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

    @Test
    void savingOneModelPricingDoesNotTouchOtherMappings() {
        Channel channel = channel("model-a\nmodel-b");
        channel.setId(25L);
        ModelMapping modelA = pricing("model-a", "2", "1", "2", "4");
        modelA.setId(31L);
        modelA.setChannelId(25L);
        modelA.setVendor("openai");
        modelA.setCapability("reasoning");
        ModelMapping modelB = pricing("model-b", "3", "2", "6", "9");
        modelB.setId(32L);
        modelB.setChannelId(25L);
        ModelMapping request = pricing("model-a", "4", "2", "8", "12");
        when(channelMapper.selectById(25L)).thenReturn(channel);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(modelA));

        ModelMapping updated = service.saveModelPricing(25L, request);

        assertThat(updated.getId()).isEqualTo(31L);
        assertThat(updated.getInputPricePerMillion()).isEqualByComparingTo("8");
        assertThat(updated.getVendor()).isEqualTo("openai");
        assertThat(updated.getCapability()).isEqualTo("reasoning");
        verify(modelMappingMapper).updateById(modelA);
        verify(modelMappingMapper, never()).updateById(modelB);
        verify(modelMappingMapper, never()).deleteById(32L);
        verify(priceTierService).synchronize(modelA, request.getPriceTiers());
    }

    @Test
    void savingNewSlashModelAppendsItWithoutReplacingExistingModels() {
        Channel channel = channel("model-a");
        channel.setId(26L);
        ModelMapping request = pricing("vendor/model-new", "2", "1", "2", "4");
        when(channelMapper.selectById(26L)).thenReturn(channel);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ModelMapping inserted = invocation.getArgument(0);
            inserted.setId(41L);
            return 1;
        }).when(modelMappingMapper).insert(any(ModelMapping.class));

        ModelMapping created = service.saveModelPricing(26L, request);

        assertThat(created.getId()).isEqualTo(41L);
        assertThat(channel.getModels()).isEqualTo("model-a\nvendor/model-new");
        verify(channelMapper).updateById(channel);
        verify(priceTierService).synchronize(created, request.getPriceTiers());
    }

    @Test
    void deletingOneChannelModelRemovesItsTiersAndListEntry() {
        Channel channel = channel("model-a\nmodel-b");
        channel.setId(27L);
        ModelMapping modelA = pricing("model-a", "2", "1", "2", "4");
        modelA.setId(51L);
        modelA.setChannelId(27L);
        when(channelMapper.selectById(27L)).thenReturn(channel);
        when(modelMappingMapper.selectById(51L)).thenReturn(modelA);

        service.deleteModelPricing(27L, 51L);

        verify(priceTierService).deleteForMappings(List.of(51L));
        verify(modelMappingMapper).deleteById(51L);
        assertThat(channel.getModels()).isEqualTo("model-b");
        verify(channelMapper).updateById(channel);
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

    private ModelMapping imageMapping(long id, String model) {
        return ModelMapping.builder()
                .id(id)
                .channelModelName(model)
                .capability("image")
                .protocols("images")
                .pricingStatus("VERIFIED")
                .enabled(true)
                .build();
    }
}
