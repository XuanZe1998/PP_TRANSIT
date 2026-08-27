package com.transit.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * One-way hashing for high-entropy bearer credentials. Random access tokens and
 * API keys do not need reversible encryption; only their digest is persisted.
 */
@Component
public class SecretHashService {

    private static final String PREFIX = "sha256:";

    public String hash(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalArgumentException("Secret must not be blank");
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest(rawSecret));
    }

    public boolean matches(String rawSecret, String storedValue) {
        if (rawSecret == null || rawSecret.isBlank() || storedValue == null || storedValue.isBlank()) {
            return false;
        }
        byte[] expected = storedValue.startsWith(PREFIX)
                ? storedValue.getBytes(StandardCharsets.UTF_8)
                : rawSecret.getBytes(StandardCharsets.UTF_8);
        byte[] actual = storedValue.startsWith(PREFIX)
                ? hash(rawSecret).getBytes(StandardCharsets.UTF_8)
                : storedValue.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public boolean isHashed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private byte[] digest(String rawSecret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(rawSecret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
