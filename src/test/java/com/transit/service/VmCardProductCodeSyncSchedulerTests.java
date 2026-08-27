package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VmCardProductCodeSyncSchedulerTests {

    @Test
    void synchronizesWhenSchedulerAndClientAreReady() {
        VmCardClientService client = mock(VmCardClientService.class);
        when(client.productCodeSyncReady()).thenReturn(true);
        when(client.synchronizeProductCodes()).thenReturn(3);
        VmCardProductCodeSyncScheduler scheduler = new VmCardProductCodeSyncScheduler(client);
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        scheduler.synchronize();

        verify(client).synchronizeProductCodes();
    }

    @Test
    void skipsWhenIntegrationIsNotReady() {
        VmCardClientService client = mock(VmCardClientService.class);
        when(client.productCodeSyncReady()).thenReturn(false);
        VmCardProductCodeSyncScheduler scheduler = new VmCardProductCodeSyncScheduler(client);
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        scheduler.synchronize();

        verify(client, never()).synchronizeProductCodes();
    }

    @Test
    void containsVendorFailuresSoTheSchedulerKeepsRunning() {
        VmCardClientService client = mock(VmCardClientService.class);
        when(client.productCodeSyncReady()).thenReturn(true);
        when(client.synchronizeProductCodes()).thenThrow(new IllegalStateException("vendor unavailable"));
        VmCardProductCodeSyncScheduler scheduler = new VmCardProductCodeSyncScheduler(client);
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        assertThatCode(scheduler::synchronize).doesNotThrowAnyException();
    }
}
