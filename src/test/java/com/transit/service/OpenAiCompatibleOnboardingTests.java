package com.transit.service;

import com.transit.model.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiCompatibleOnboardingTests {
    private final NewApiCatalogClient catalog = mock(NewApiCatalogClient.class);
    private final AdminChannelService channels = mock(AdminChannelService.class);
    private final GatewaySiteService sites = mock(GatewaySiteService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final OpenAiCompatibleOnboarding onboarding = new OpenAiCompatibleOnboarding(catalog, channels, sites, transactions);
    private final NewApiOnboardingService.Request request = new NewApiOnboardingService.Request(
            "https://example.org/v1", "test-key", "", List.of("chat-model"));

    @Test
    void connectRechecksTheVisibleDirectoryBeforeWriting() {
        when(catalog.models("https://example.org", "test-key"))
                .thenReturn(List.of("chat-model")).thenReturn(List.of());
        assertEquals(List.of("chat-model"), onboarding.preview(request).models());
        assertThrows(ResponseStatusException.class, () -> onboarding.connect(request));
        verifyNoInteractions(channels, sites, transactions);
    }

    @Test
    void importsOnlySelectedModelsAsUnpublishedDrafts() {
        when(catalog.models("https://example.org", "test-key")).thenReturn(List.of("chat-model", "other"));
        when(transactions.execute(any())).thenAnswer(call ->
                call.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
        when(channels.create(any())).thenReturn(Channel.builder().id(7L).build());
        assertEquals(7L, onboarding.connect(request).get("channelId"));
        var captured = ArgumentCaptor.forClass(Channel.class);
        verify(channels).create(captured.capture());
        var channel = captured.getValue();
        assertEquals("chat-model", channel.getModels());
        assertFalse(channel.isEnabled());
        assertEquals("PENDING", channel.getModelPricing().get(0).getPricingStatus());
        verify(sites).reconcile();
    }
}
