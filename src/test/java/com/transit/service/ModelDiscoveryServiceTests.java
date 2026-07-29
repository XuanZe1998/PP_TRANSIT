package com.transit.service;

import com.sun.net.httpserver.HttpServer;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelDiscoveryServiceTests {

    @Mock private ChannelMapper channelMapper;
    @Mock private ModelMappingMapper mappingMapper;
    @Mock private ChannelSecretService secretService;
    @Mock private ChannelUrlPolicy urlPolicy;
    @Mock private JdbcTemplate jdbcTemplate;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void discoversAndNormalizesAnOpenAiCompatibleCatalog() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"bad model\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        Channel channel = Channel.builder()
                .id(11L)
                .name("test")
                .type("openai-compatible")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("upstream-secret")
                .build();
        when(channelMapper.selectById(11L)).thenReturn(channel);
        when(secretService.reveal(channel)).thenReturn(channel);
        when(mappingMapper.selectList(any())).thenReturn(List.of());
        ModelDiscoveryService service = new ModelDiscoveryService(channelMapper, mappingMapper,
                secretService, urlPolicy, WebClient.create(), jdbcTemplate);

        Map<String, Object> result = service.discover(11L);

        assertThat(result.get("models")).isEqualTo(List.of("a-model", "z-model"));
        assertThat(result.get("missingCount")).isEqualTo(2);
        assertThat(authorization.get()).isEqualTo("Bearer upstream-secret");
    }
}
