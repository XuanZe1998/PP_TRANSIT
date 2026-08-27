package com.transit.service;

import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NvidiaCatalogBootstrapIntegrationTests {

    @Test
    void upstreamCatalogFailureDoesNotPreventGatewayStartup() {
        ChannelSecretService secrets = mock(ChannelSecretService.class);
        NvidiaCatalogService service = new NvidiaCatalogService(
                mock(ChannelMapper.class),
                mock(ModelMappingMapper.class),
                secrets,
                mock(AdminChannelService.class),
                mock(ProviderModelCatalogService.class));
        ReflectionTestUtils.setField(service, "configuredKey", "test-shared-nvidia-key");
        ReflectionTestUtils.setField(service, "legacyConfiguredKey", "");
        when(secrets.isConfigured()).thenReturn(false);

        assertThatCode(() -> service.run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
    }
}
