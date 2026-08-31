package com.transit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OAuthCredentialBundleService {
    private final ObjectMapper json;
    private final ChannelSecretService secrets;

    public String encrypt(UpstreamOAuthProvider.OAuthToken token) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("access_token", token.accessToken()); value.put("refresh_token", token.refreshToken());
            value.put("expires_at", token.expiresAt().toString()); value.put("scope", token.scope());
            value.put("token_type", token.tokenType()); value.put("metadata", token.metadata());
            return secrets.encrypt(json.writeValueAsString(value));
        } catch (Exception exception) { throw new IllegalStateException("Unable to encrypt OAuth credential bundle", exception); }
    }

    public UpstreamOAuthProvider.OAuthToken decrypt(String encrypted) {
        try {
            Map<String, Object> value = json.readValue(secrets.decrypt(encrypted), new TypeReference<>() {});
            return new UpstreamOAuthProvider.OAuthToken(text(value.get("access_token")), text(value.get("refresh_token")),
                    Instant.parse(text(value.get("expires_at"))), text(value.get("scope")), text(value.get("token_type")),
                    value.get("metadata") instanceof Map<?, ?> map ? stringMap(map) : Map.of());
        } catch (Exception exception) { throw new IllegalStateException("Unable to decrypt OAuth credential bundle", exception); }
    }

    private Map<String, Object> stringMap(Map<?, ?> input) {
        Map<String, Object> output = new LinkedHashMap<>(); input.forEach((key, value) -> output.put(String.valueOf(key), value)); return output;
    }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
}
