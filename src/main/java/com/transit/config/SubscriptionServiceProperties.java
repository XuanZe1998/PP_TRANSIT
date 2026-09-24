package com.transit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "subscription-service")
public class SubscriptionServiceProperties {
    private boolean enabled;
    private boolean allowMutations;
    private String gateway = "https://www.16688.com.cn/openApi";
    private String appId;
    private String secret;
    private long requestTimeoutSeconds = 30;
    private int maxResponseBytes = 2 * 1024 * 1024;

    public boolean credentialsConfigured() {
        return text(appId) && text(secret);
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
