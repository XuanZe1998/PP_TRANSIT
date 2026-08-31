package com.transit.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class UpstreamOAuthProviderRegistry {
    public static final List<String> OAUTH_PLATFORMS = List.of("CODEX", "CLAUDE", "GEMINI", "ANTIGRAVITY", "GROK");
    private final Map<String, UpstreamOAuthProvider> providers;

    public UpstreamOAuthProviderRegistry(UpstreamOAuthClientConfigService configs, UpstreamProxyHttpClientFactory clients) {
        providers = OAUTH_PLATFORMS.stream().collect(Collectors.toUnmodifiableMap(value -> value,
                value -> new ConfiguredUpstreamOAuthProvider(value, configs, clients)));
    }

    public UpstreamOAuthProvider require(String platform) {
        String key = platform == null ? "" : platform.toUpperCase(Locale.ROOT);
        UpstreamOAuthProvider provider = providers.get(key);
        if (provider == null) throw new IllegalArgumentException("Unsupported upstream OAuth platform: " + key);
        return provider;
    }

    public Map<String, Boolean> configurationStatus() {
        return providers.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().configured()));
    }
}
