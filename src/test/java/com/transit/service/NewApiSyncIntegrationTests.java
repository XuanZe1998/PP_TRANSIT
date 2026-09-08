package com.transit.service;

import com.sun.net.httpserver.HttpServer;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"new-api.sync.enabled=false", "aiapibank.enabled=false"})
class NewApiSyncIntegrationTests {
    @Autowired NewApiOnboardingService onboarding;
    @Autowired NewApiSyncService sync;
    @Autowired ProviderModelCatalogService providerCatalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelMapper channels;
    @Autowired ModelMappingMapper mappings;
    @Autowired AdminChannelService adminChannels;
    final java.util.ArrayList<Long> created = new java.util.ArrayList<>();
    HttpServer server;
    String base;
    AtomicReference<String> modelResponse = new AtomicReference<>();
    AtomicReference<String> priceResponse = new AtomicReference<>();
    AtomicReference<String> pricingAuthorization = new AtomicReference<>();
    AtomicReference<String> modelAuthorization = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", e -> {
            modelAuthorization.set(e.getRequestHeaders().getFirst("Authorization"));
            byte[] body = modelResponse.get().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json"); e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.createContext("/api/pricing", e -> {
            pricingAuthorization.set(e.getRequestHeaders().getFirst("Authorization"));
            byte[] body = priceResponse.get().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json"); e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
        catalog(List.of("a", "b"), "2.5");
    }
    @AfterEach void stop() {
        server.stop(0);
        for (Long id : created) {
            jdbc.update("DELETE FROM new_api_model_state WHERE channel_id=?", id);
            jdbc.update("DELETE FROM new_api_connections WHERE channel_id=?", id);
            adminChannels.delete(id);
        }
    }
    void catalog(List<String> models, String ratio) {
        modelResponse.set("{\"data\":[" + models.stream().map(m -> "{\"id\":\"" + m + "\"}").collect(java.util.stream.Collectors.joining(",")) + "]}");
        priceResponse.set("{\"success\":true,\"usable_group\":{\"paid\":\"test\"},\"group_ratio\":{\"paid\":0.2},\"data\":["
                + models.stream().map(m -> "{\"model_name\":\"" + m + "\",\"quota_type\":0,\"model_ratio\":" + ratio
                + ",\"completion_ratio\":5,\"enable_groups\":[\"paid\"]}").collect(java.util.stream.Collectors.joining(",")) + "]}");
    }
    @Test void nativeHaoeeAutomaticSyncRejectsIncompleteOrEmptyLiveCatalogWithoutFallback() {
        Channel channel = connect();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE channel_id=?", Integer.class, channel.getId());
        for (String response : List.of("{\"data\":[],\"has_more\":true}", "{\"data\":[]}", "{\"success\":false,\"data\":[]}")) {
            modelResponse.set(response);
            assertThatThrownBy(() -> providerCatalog.synchronizeHaoeeLive(channel.getId()))
                    .hasMessageContaining("catalog unavailable");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE channel_id=?", Integer.class, channel.getId())).isEqualTo(before);
        }
    }

    Channel connect() {
        Channel channel = onboarding.connect(new NewApiOnboardingService.Request(base, "inference-secret", "New API sync test", List.of("a"),
                "paid", new BigDecimal("1.5"), BigDecimal.ONE, "pricing-secret", 1L, true, true, true));
        created.add(channel.getId());
        return channel;
    }
    long mappingId(long channel, String model) {
        return jdbc.queryForObject("SELECT id FROM model_mappings WHERE channel_id=? AND channel_model_name=?", Long.class, channel, model);
    }
    void publish(long id) {
        jdbc.update("UPDATE channels SET enabled=TRUE WHERE id=?", id);
        jdbc.update("UPDATE model_mappings SET enabled=TRUE WHERE channel_id=?", id);
    }
    boolean enabled(long channel, String model) { return mappings.selectById(mappingId(channel, model)).isEnabled(); }

    @Test void importsEncryptedCredentialsAndPricesThenUpdatesAndAddsWithoutPublishing() {
        Channel c = connect(); long id = c.getId();
        assertThat(c.isEnabled()).isFalse(); assertThat(enabled(id, "a")).isFalse();
        assertThat(channels.selectById(id).getApiKey()).startsWith("enc:v1:");
        assertThat(jdbc.queryForObject("SELECT pricing_access_token FROM new_api_connections WHERE channel_id=?", String.class, id)).startsWith("enc:v1:");
        assertThat(modelAuthorization.get()).isEqualTo("Bearer inference-secret");
        assertThat(pricingAuthorization.get()).isEqualTo("Bearer pricing-secret");
        assertThat(sync.status().toString()).doesNotContain("pricing-secret", "inference-secret", "enc:v1:");
        var mapping = mappings.selectById(mappingId(id, "a"));
        assertThat(mapping.getInputCostPerMillion()).isEqualByComparingTo("1");
        assertThat(mapping.getInputPricePerMillion()).isEqualByComparingTo("1.5");
        catalog(List.of("a", "b", "c"), "5");
        sync.synchronize(id, true);
        assertThat(mappings.selectById(mappingId(id, "a")).getInputCostPerMillion()).isEqualByComparingTo("2");
        assertThat(enabled(id, "c")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE channel_id=? AND channel_model_name='b'", Integer.class, id)).isZero();
    }

    @Test void requiresTwoCompleteMissingObservationsAndNeverDeletesOtherChannelsOrAutoRestores() {
        long id = connect().getId(), other = connect().getId(); publish(id); publish(other);
        catalog(List.of(), "2.5");
        sync.synchronize(id, true); assertThat(enabled(id, "a")).isTrue();
        sync.synchronize(id, true); assertThat(enabled(id, "a")).isFalse();
        assertThat(enabled(other, "a")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE channel_id=?", Integer.class, id)).isEqualTo(1);
        catalog(List.of("a"), "2.5"); sync.synchronize(id, true);
        assertThat(enabled(id, "a")).isFalse();
        assertThat(jdbc.queryForObject("SELECT missing_count FROM new_api_model_state WHERE channel_id=? AND upstream_model_name='a'", Integer.class, id)).isZero();
    }

    @Test void invalidModelResponseDoesNotCauseRemovalAndFailedPricingPreservesAmounts() {
        long id = connect().getId(); publish(id);
        modelResponse.set("{\"success\":false,\"data\":[]}");
        for (int i=0; i<2; i++) assertThatThrownBy(() -> sync.synchronize(id, true)).hasMessageContaining("同步失败");
        assertThat(enabled(id, "a")).isTrue();
        assertThat(jdbc.queryForObject("SELECT missing_count FROM new_api_model_state WHERE channel_id=? AND upstream_model_name='a'", Integer.class, id)).isZero();
        catalog(List.of("a"), "2.5"); priceResponse.set("{\"success\":false}");
        sync.synchronize(id, true);
        assertThat(enabled(id, "a")).isTrue();
        assertThat(mappings.selectById(mappingId(id, "a")).getInputCostPerMillion()).isEqualByComparingTo("1");
    }

    @Test void groupRemovalCanBeConfirmedWhenItsInferenceKeyNoLongerWorks() {
        long id = connect().getId(); publish(id);
        modelResponse.set("{\"success\":false}");
        priceResponse.set("{\"success\":true,\"data\":[],\"usable_group\":{},\"group_ratio\":{}}");
        sync.synchronize(id, true); assertThat(channels.selectById(id).isEnabled()).isTrue();
        sync.synchronize(id, true); assertThat(channels.selectById(id).isEnabled()).isFalse();
        assertThat(enabled(id, "a")).isFalse();
    }

    @Test void disablingPriceSyncPreservesOperatorPricesAndOverlappingSyncIsRejected() {
        long id = connect().getId();
        sync.configure(id, new NewApiSyncService.Settings(true, true, false, null, null, null, null));
        catalog(List.of("a"), "50"); sync.synchronize(id, true);
        assertThat(mappings.selectById(mappingId(id, "a")).getInputCostPerMillion()).isEqualByComparingTo("1");
        jdbc.update("UPDATE new_api_connections SET lease_until=? WHERE channel_id=?", java.time.LocalDateTime.now().plusMinutes(2), id);
        assertThatThrownBy(() -> sync.synchronize(id, true)).hasMessageContaining("409");
    }
}
