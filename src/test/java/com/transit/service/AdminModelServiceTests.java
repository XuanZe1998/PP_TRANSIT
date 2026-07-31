package com.transit.service;

import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminModelServiceTests {

    @Mock private ModelMappingMapper modelMappingMapper;
    @Mock private ChannelMapper channelMapper;

    private AdminModelService service;

    @BeforeEach
    void setUp() {
        service = new AdminModelService(modelMappingMapper, channelMapper, new ChannelSecretService(""));
    }

    @Test
    void reportsWhetherMappingsAreActuallyCallable() {
        ModelMapping healthy = mapping(1L, 11L, true);
        ModelMapping degraded = mapping(2L, 12L, true);
        ModelMapping untested = mapping(3L, 13L, true);
        ModelMapping unpublished = mapping(4L, 11L, false);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(healthy, degraded, untested, unpublished));
        when(channelMapper.selectById(11L)).thenReturn(channel(11L, "HEALTHY", true, "enc:v1:configured"));
        when(channelMapper.selectById(12L)).thenReturn(channel(12L, "DEGRADED", true, "enc:v1:configured"));
        when(channelMapper.selectById(13L)).thenReturn(channel(13L, "UNTESTED", true, "enc:v1:configured"));

        List<ModelMapping> result = service.list();

        assertThat(result.get(0).isCallable()).isTrue();
        assertThat(result.get(0).getAvailabilityStatus()).isEqualTo("CALLABLE");
        assertThat(result.get(1).isCallable()).isTrue();
        assertThat(result.get(1).getAvailabilityMessage()).contains("降级");
        assertThat(result.get(2).isCallable()).isFalse();
        assertThat(result.get(2).getAvailabilityStatus()).isEqualTo("CHANNEL_UNTESTED");
        assertThat(result.get(3).isCallable()).isFalse();
        assertThat(result.get(3).getAvailabilityStatus()).isEqualTo("MAPPING_DISABLED");
        assertThat(result).allSatisfy(mapping -> assertThat(mapping.getChannel().getApiKey()).isNull());
    }

    @Test
    void reportsMissingCredentialWithoutReturningTheSecret() {
        ModelMapping mapping = mapping(1L, 21L, true);
        when(modelMappingMapper.selectList(any())).thenReturn(List.of(mapping));
        when(channelMapper.selectById(21L)).thenReturn(channel(21L, "HEALTHY", true, ""));

        ModelMapping result = service.list().get(0);

        assertThat(result.isCallable()).isFalse();
        assertThat(result.getAvailabilityStatus()).isEqualTo("CREDENTIAL_MISSING");
        assertThat(result.getChannel().getApiKey()).isNull();
    }

    private ModelMapping mapping(long id, long channelId, boolean enabled) {
        return ModelMapping.builder()
                .id(id)
                .publicModelName("public-model-" + id)
                .channelModelName("provider-model-" + id)
                .channelId(channelId)
                .enabled(enabled)
                .build();
    }

    private Channel channel(long id, String health, boolean enabled, String apiKey) {
        return Channel.builder()
                .id(id)
                .name("channel-" + id)
                .type("openai")
                .enabled(enabled)
                .healthStatus(health)
                .apiKey(apiKey)
                .build();
    }
}
