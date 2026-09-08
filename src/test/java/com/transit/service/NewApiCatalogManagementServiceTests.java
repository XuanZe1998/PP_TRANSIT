package com.transit.service;

import com.transit.mapper.ChannelMapper;
import com.transit.model.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"new-api.sync.enabled=false", "aiapibank.enabled=false"})
@Transactional
class NewApiCatalogManagementServiceTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelMapper channels;
    NewApiSyncService standard;
    AiApiBankCatalogService bank;
    ProviderModelCatalogService providers;
    NewApiCatalogManagementService service;

    @BeforeEach void setup() {
        standard = mock(NewApiSyncService.class);
        bank = mock(AiApiBankCatalogService.class);
        providers = mock(ProviderModelCatalogService.class);
        service = new NewApiCatalogManagementService(jdbc, standard, bank, providers);
    }
    long channel(String source) {
        var channel = Channel.builder().name("catalog-" + source).sourceCode(source).sourceName(source)
                .type("openai-compatible").baseUrl("https://example.com").apiKey("never-return-this-secret")
                .createdAt(LocalDateTime.now()).build();
        channels.insert(channel);
        return channel.getId();
    }
    @Test void listsAllSourcesWithoutCredentialsAndRegistersAdaptersIdempotently() {
        long haoee = channel("haoee"), aiapi = channel("aiapibank"), manual = channel("other");
        service.registerAdapters(); service.registerAdapters();
        assertThat(service.directory()).extracting(row -> row.get("channel_id"))
                .contains(haoee, aiapi, manual);
        assertThat(service.directory().toString()).doesNotContain("never-return-this-secret");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_catalog_sync WHERE channel_id IN (?,?)", Integer.class, haoee, aiapi)).isEqualTo(2);
    }
    @Test void dispatchesNativeAdaptersAndPreservesSourceMetadata() {
        long haoee = channel("haoee"), aiapi = channel("aiapibank");
        service.registerAdapters();
        when(providers.synchronizeHaoeeLive(haoee)).thenReturn(6);
        when(bank.syncChannel(aiapi)).thenReturn(new AiApiBankCatalogService.ChannelSyncResult(aiapi, "paid", 3, 3, 0, LocalDateTime.now()));
        assertThat(service.synchronize(haoee, true).get("message").toString()).contains("6");
        service.synchronize(aiapi, true);
        verify(providers).synchronizeHaoeeLive(haoee); verify(bank).syncChannel(aiapi);
        assertThat(channels.selectById(haoee).getSourceCode()).isEqualTo("haoee");
        assertThat(jdbc.queryForObject("SELECT sync_status FROM upstream_catalog_sync WHERE channel_id=?", String.class, aiapi)).isEqualTo("SUCCESS");
    }
    @Test void standardChannelUsesExistingSyncAndPauseSettings() {
        long id = channel("new-api");
        jdbc.update("INSERT INTO new_api_connections(channel_id,base_url,upstream_group,sale_markup) VALUES(?,?,?,1.2)", id, "https://example.com", "paid");
        when(standard.synchronize(id, true)).thenReturn(Map.of("message", "done"));
        service.configure(id, false);
        assertThat(jdbc.queryForObject("SELECT sync_enabled FROM new_api_connections WHERE channel_id=?", Boolean.class, id)).isFalse();
        assertThat(service.synchronize(id, true)).containsEntry("message", "done");
        verify(standard).synchronize(id, true); verifyNoInteractions(bank, providers);
    }
    @Test void pauseAndLeasePreventDuplicateNativeCallsAndFailureReleasesLease() {
        long id = channel("haoee"); service.registerAdapters(); service.configure(id, false);
        assertThatThrownBy(() -> service.synchronize(id, false)).hasMessageContaining("409");
        verifyNoInteractions(providers);
        service.configure(id, true);
        jdbc.update("UPDATE upstream_catalog_sync SET lease_until=? WHERE channel_id=?", LocalDateTime.now().plusMinutes(2), id);
        assertThatThrownBy(() -> service.synchronize(id, true)).hasMessageContaining("409");
        jdbc.update("UPDATE upstream_catalog_sync SET lease_until=NULL WHERE channel_id=?", id);
        when(providers.synchronizeHaoeeLive(id)).thenThrow(new IllegalStateException("upstream-secret"));
        assertThatThrownBy(() -> service.synchronize(id, true)).hasMessageContaining("502").hasMessageNotContaining("upstream-secret");
        assertThat(jdbc.queryForObject("SELECT sync_status FROM upstream_catalog_sync WHERE channel_id=?", String.class, id)).isEqualTo("ERROR");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM upstream_catalog_sync WHERE channel_id=?", String.class, id)).isNull();
    }
    @Test void unsupportedChannelCannotEnableOrRunAutomaticSync() {
        long id = channel("other");
        assertThatThrownBy(() -> service.synchronize(id, true)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.configure(id, true)).hasMessageContaining("409");
        verifyNoInteractions(standard, bank, providers);
    }
}
