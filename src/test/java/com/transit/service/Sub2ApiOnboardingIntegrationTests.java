package com.transit.service;

import com.sun.net.httpserver.HttpServer;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"new-api.sync.enabled=false", "aiapibank.enabled=false"})
@Transactional
class Sub2ApiOnboardingIntegrationTests {
    @Autowired Sub2ApiOnboardingService onboarding;
    @Autowired Sub2ApiSyncService sync;
    @Autowired ChannelMapper channels;
    @Autowired ModelMappingMapper mappings;
    @Autowired JdbcTemplate jdbc;
    HttpServer server;
    String base;
    AtomicReference<String> modelsResponse = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelsResponse.set("{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"claude-sonnet-4-6\"}]}");
        server.createContext("/v1/models", exchange -> json(exchange, modelsResponse.get()));
        server.createContext("/v1/sub2api/billing", exchange -> json(exchange,
                "{\"object\":\"sub2api.key_billing\",\"schema_version\":1,\"billing_scope\":\"token\",\"effective_rate_multiplier\":1.2}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test
    void oneClickConnectCreatesASeparateSiteGroupAndPersistentSyncJob() {
        var preview = onboarding.preview(new NewApiOnboardingService.Request(base + "/v1", "sub-key", "", List.of()));
        assertThat(preview.models()).containsExactly("gpt-5", "claude-sonnet-4-6");
        assertThat(preview.selectedGroup()).isEqualTo("api-key-bound");

        var result = onboarding.connect(new NewApiOnboardingService.Request(base, "sub-key", "Upstream A",
                List.of("claude-sonnet-4-6"), preview.selectedGroup(), null, null, null, null,
                true, true, false));
        long channelId = ((Number) result.get("channelId")).longValue();
        var channel = channels.selectById(channelId);
        assertThat(channel.getSourceCode()).isEqualTo("sub2api");
        assertThat(jdbc.queryForObject("SELECT api_key FROM channels WHERE id=?", String.class, channelId)).startsWith("enc:v1:");
        assertThat(channel.isEnabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_site_channels WHERE channel_id=?", Integer.class, channelId)).isOne();
        assertThat(jdbc.queryForObject("SELECT sync_status FROM sub2api_connections WHERE channel_id=?", String.class, channelId)).isEqualTo("IMPORTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_sync_jobs WHERE id=?", Integer.class, result.get("jobId"))).isOne();
        var mapping = mappings.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.transit.model.ModelMapping>()
                .eq(com.transit.model.ModelMapping::getChannelId, channelId)).get(0);
        assertThat(mapping.isEnabled()).isFalse();
        assertThat(mapping.isBillingEnabled()).isFalse();
        assertThat(mapping.getProtocols()).contains("messages", "responses");
    }

    @Test
    void synchronizationAddsDisabledModelsPreservesManualPricesAndRequiresTwoMissingCatalogs() {
        var result = onboarding.connect(new NewApiOnboardingService.Request(base, "sub-key", "Upstream sync",
                List.of("gpt-5"), "api-key-bound", null, null, null, null, true, true, false));
        long channelId = ((Number) result.get("channelId")).longValue();
        jdbc.update("UPDATE model_mappings SET enabled=TRUE,billing_enabled=TRUE,billing_mode='TOKEN',input_price_per_million=7 WHERE channel_id=?", channelId);

        modelsResponse.set("{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"new-model\"}]}");
        sync.synchronize(channelId, true);
        assertThat(jdbc.queryForObject("SELECT input_price_per_million FROM model_mappings WHERE channel_id=? AND channel_model_name='gpt-5'", java.math.BigDecimal.class, channelId))
                .isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT enabled FROM model_mappings WHERE channel_id=? AND channel_model_name='new-model'", Boolean.class, channelId)).isFalse();

        modelsResponse.set("{\"object\":\"list\",\"data\":[{\"id\":\"new-model\"}]}");
        sync.synchronize(channelId, true);
        assertThat(jdbc.queryForObject("SELECT enabled FROM model_mappings WHERE channel_id=? AND channel_model_name='gpt-5'", Boolean.class, channelId)).isTrue();
        sync.synchronize(channelId, true);
        assertThat(jdbc.queryForObject("SELECT enabled FROM model_mappings WHERE channel_id=? AND channel_model_name='gpt-5'", Boolean.class, channelId)).isFalse();
    }

    private static void json(com.sun.net.httpserver.HttpExchange exchange, String value) throws java.io.IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
