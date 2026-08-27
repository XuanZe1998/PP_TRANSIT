package com.transit.service;

import com.transit.model.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelHealthServiceTests {

    @Mock private JdbcTemplate jdbcTemplate;
    private SimpleMeterRegistry registry;
    private ChannelHealthService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ChannelHealthService(jdbcTemplate, registry);
    }

    @Test
    void opensCircuitAtConfiguredFailureThreshold() {
        Channel channel = Channel.builder().id(7L).name("provider").build();
        when(jdbcTemplate.queryForMap(anyString(), eq(7L))).thenReturn(Map.of(
                "consecutive_failures", 3,
                "failure_threshold", 3,
                "cooldown_seconds", 90,
                "auto_disable", true));

        service.recordFailure(channel, new IllegalStateException("unavailable"));

        verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(Object[].class));
        assertThat(registry.get("gateway.channel.circuit.opens").counter().count()).isEqualTo(1);
    }

    @Test
    void successPublishesLatencyAndSuccessMetrics() {
        Channel channel = Channel.builder().id(8L).name("provider").build();

        service.recordSuccess(channel, 42);

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
        assertThat(registry.get("gateway.channel.requests").tag("result", "success").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("gateway.channel.latency").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(42);
    }
}
