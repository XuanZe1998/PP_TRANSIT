package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.model.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.test.util.ReflectionTestUtils;

class NewApiOnboardingServiceTests {
    private final NewApiCatalogClient discovery = mock(NewApiCatalogClient.class);
    private final NewApiSyncService sync = mock(NewApiSyncService.class);
    private final org.springframework.transaction.support.TransactionTemplate tx = mock(org.springframework.transaction.support.TransactionTemplate.class);
    private final AdminChannelService channels = mock(AdminChannelService.class);
    private final UpstreamDisplayMappingService displayMappings = mock(UpstreamDisplayMappingService.class);
    private final GatewaySiteService sites = mock(GatewaySiteService.class);
    private final NewApiOnboardingService service = new NewApiOnboardingService(discovery, new NewApiPricing(), channels, sync, displayMappings, tx);

    NewApiOnboardingServiceTests() {
        ReflectionTestUtils.setField(service, "sites", sites);
    }

    @Test void normalizesCommonUrlsAndPreservesDeploymentPrefix() {
        for (String suffix : List.of("", "/", "/v1", "/v1/", "/v1/models", "/v1/chat/completions")) {
            assertThat(NewApiOnboardingService.normalizeBaseUrl("example.com/proxy" + suffix))
                    .isEqualTo("https://example.com/proxy");
        }
        for (String url : List.of("https://user:secret@example.com", "https://example.com?key=secret", "https://example.com/#x", "file:///tmp/file")) {
            assertThatThrownBy(() -> NewApiOnboardingService.normalizeBaseUrl(url)).hasMessageContaining("400");
        }
    }

    @Test void previewDoesNotPersistAndImportRechecksPermissions() {
        var request = new NewApiOnboardingService.Request("example.com/v1", " secret ", "", List.of("a"));
        when(discovery.fetch("https://example.com", "secret", null, null)).thenReturn(
            new NewApiCatalogClient.Snapshot(List.of("a"), null, null, "prices unavailable"),
            new NewApiCatalogClient.Snapshot(List.of("b"), null, null, "prices unavailable"));
        assertThat(service.preview(request).models()).containsExactly("a");
        verifyNoInteractions(channels);
        assertThatThrownBy(() -> service.connect(request)).hasMessageContaining("模型权限已变化");
        verifyNoInteractions(channels);
    }

    @Test void importsUsingExistingEncryptedChannelWorkflowWithPublicationDisabled() {
        when(discovery.fetch(anyString(), anyString(), isNull(), isNull())).thenReturn(new NewApiCatalogClient.Snapshot(List.of("a", "b"), null, null, "unavailable"));
        when(tx.execute(any())).thenAnswer(invocation -> ((org.springframework.transaction.support.TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        when(channels.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Channel result = service.connect(new NewApiOnboardingService.Request("example.com", "key", "测试", List.of("b", "b"), "default", null, null, null, null, true, true, true));
        verifyNoInteractions(displayMappings);
        assertThat(result.getModels()).isEqualTo("b");
        assertThat(result.getType()).isEqualTo("openai-compatible");
        assertThat(result.getProtocolType()).isEqualTo("openai-chat");
        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getHealthStatus()).isEqualTo("UNTESTED");
        assertThat(result.getModelPricing()).hasSize(1);
        assertThat(result.getModelPricing().get(0).getPricingStatus()).isEqualTo("PENDING");
    }

    @Test void savesExplicitPublicNameOnTheOwningSite() {
        when(discovery.fetch(anyString(), anyString(), isNull(), isNull())).thenReturn(
                new NewApiCatalogClient.Snapshot(List.of("a"), null, null, "unavailable"));
        when(tx.execute(any())).thenAnswer(invocation ->
                ((org.springframework.transaction.support.TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        when(channels.create(any())).thenAnswer(invocation -> {
            Channel channel = invocation.getArgument(0);
            channel.setId(35L);
            return channel;
        });
        service.connect(new NewApiOnboardingService.Request("example.com", "key", "internal", List.of("a"),
                "default", null, null, null, null, true, true, true, " ahh "));
        verify(sites).reconcile();
        verify(sites).configurePublicDisplayForChannel(35L, " ahh ");
        verifyNoInteractions(displayMappings);
    }

    @Test void importsImageOnlySelectionAsOpenAiImageChannel() throws Exception {
        var pricingCatalog = new ObjectMapper().readTree("""
                {"success":true,"usable_group":{"image":"Images"},"group_ratio":{"image":1},
                 "data":[{"model_name":"gpt-image-2","quota_type":1,"model_price":0.1,"enable_groups":["image"]}]}
                """);
        when(discovery.fetch(anyString(), anyString(), isNull(), isNull())).thenReturn(
                new NewApiCatalogClient.Snapshot(List.of("gpt-image-2"), pricingCatalog, null, null));
        when(tx.execute(any())).thenAnswer(invocation ->
                ((org.springframework.transaction.support.TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        when(channels.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Channel result = service.connect(new NewApiOnboardingService.Request("example.com", "key", "images",
                List.of("gpt-image-2"), "image", null, null, null, null, true, true, true));

        assertThat(result.getProtocolType()).isEqualTo("openai-image");
        assertThat(result.getModelPricing().get(0).getProtocols()).isEqualTo("images");
        assertThat(result.getModelPricing().get(0).getCapability()).isEqualTo("image");
    }

    @Test void rejectsEmptySelectionAndOverCapacityBeforeNetworkOrWrites() {
        for (List<String> models : List.of(List.<String>of(), List.of("a".repeat(2001)))) {
            assertThatThrownBy(() -> service.connect(new NewApiOnboardingService.Request("example.com", "key", "", models)))
                    .hasMessageContaining("400");
        }
        verifyNoInteractions(discovery, channels);
    }

    @Test void doesNotExposeKeyInErrorsOrSerializedRequest() throws Exception {
        var request = new NewApiOnboardingService.Request("example.com", "secret-key", "", List.of("a"));
        when(discovery.fetch(anyString(), anyString(), isNull(), isNull())).thenReturn(
            new NewApiCatalogClient.Snapshot(null, null, "模型目录不可用", "价格目录不可用"));
        assertThatThrownBy(() -> service.preview(request)).hasMessageContaining("模型目录不可用").hasMessageNotContaining("secret-key");
        assertThat(request.toString()).doesNotContain("secret-key");
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.writeValueAsString(request)).doesNotContain("secret-key");
        assertThat(mapper.readValue("{\"baseUrl\":\"example.com\",\"apiKey\":\"secret-key\"}", NewApiOnboardingService.Request.class).apiKey()).isEqualTo("secret-key");
    }
}
