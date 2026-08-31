package com.transit.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

/** Authentication selected for one upstream request; never serialized to an admin response. */
public record ProviderAuthContext(
        Long credentialId,
        String platform,
        String authType,
        @JsonIgnore String apiKey,
        @JsonIgnore String accessToken,
        String baseUrl,
        Long upstreamProxyId,
        String entitlementStatus,
        Map<String, Object> metadata) {
    public boolean oauth() { return "OAUTH".equalsIgnoreCase(authType); }
    @JsonIgnore public String bearerOrApiKey() { return oauth() ? accessToken : apiKey; }
}
