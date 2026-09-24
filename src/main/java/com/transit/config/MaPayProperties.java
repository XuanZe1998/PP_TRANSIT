package com.transit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Data
@Component
@ConfigurationProperties(prefix = "mapay")
public class MaPayProperties {
    private boolean enabled;
    private String baseUrl = "https://mzf.mapay.cc";
    private String merchantId;
    private String merchantKey;
    private String notifyUrl;
    private String returnUrl;
    private String siteName = "Linknux";
    private long requestTimeoutSeconds = 15;
    private List<String> allowedMethods = List.of("alipay", "wxpay");

    public boolean credentialsConfigured() {
        return text(merchantId) && text(merchantKey) && text(notifyUrl);
    }

    public boolean methodAllowed(String method) {
        if (!text(method)) return false;
        String normalized = method.trim().toLowerCase(Locale.ROOT);
        return allowedMethods != null && allowedMethods.stream()
                .filter(this::text)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
