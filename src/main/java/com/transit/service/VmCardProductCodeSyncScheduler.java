package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VmCardProductCodeSyncScheduler {

    private final VmCardClientService clientService;

    @Value("${vmcard.product-code-sync.enabled:true}")
    private boolean enabled;

    @Scheduled(
            fixedRateString = "${vmcard.product-code-sync.interval-ms:60000}",
            initialDelayString = "${vmcard.product-code-sync.initial-delay-ms:5000}"
    )
    public void synchronize() {
        if (!enabled || !clientService.productCodeSyncReady()) {
            return;
        }
        try {
            int synchronizedProducts = clientService.synchronizeProductCodes();
            log.info("VMCard product-code catalog synchronized: {} products", synchronizedProducts);
        } catch (RuntimeException exception) {
            log.warn("VMCard product-code catalog synchronization failed: {}",
                    safeMessage(exception));
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
