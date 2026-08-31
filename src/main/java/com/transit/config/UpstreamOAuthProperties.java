package com.transit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "upstream-oauth")
public class UpstreamOAuthProperties {
    private String callbackBaseUrl;
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public Provider provider(String platform) {
        return providers.get(platform == null ? "" : platform.toLowerCase(java.util.Locale.ROOT));
    }

    @Data
    public static class Provider {
        private String clientId;
        private String clientSecret;
        private String authorizationUri;
        private String tokenUri;
        private String userinfoUri;
        private String modelsUri;
        private String probeUri;
        private String upstreamBaseUrl;
        private String scopes;

        public boolean configured() {
            return text(clientId) && text(authorizationUri) && text(tokenUri) && text(userinfoUri)
                    && text(modelsUri) && text(probeUri) && text(upstreamBaseUrl);
        }

        private boolean text(String value) { return value != null && !value.isBlank(); }
    }
}
