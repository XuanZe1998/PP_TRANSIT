package com.transit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dujiao-next")
public class DujiaoNextProperties {
    private boolean enabled;
    private boolean allowPurchases;
    private String baseUrl = "https://www.cccrad.uk";
    private String apiKey;
    private String apiSecret;
    private String callbackUrl;
    private long requestTimeoutSeconds = 20;
    private long timestampToleranceSeconds = 60;
    private long workerIntervalMs = 2_000;
    private long pollIntervalSeconds = 30;
    private int maxAttempts = 8;
    private int batchSize = 10;

    public boolean credentialsConfigured() {
        return text(apiKey) && text(apiSecret);
    }

    public boolean purchasesEnabled() {
        return enabled && allowPurchases && credentialsConfigured();
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
