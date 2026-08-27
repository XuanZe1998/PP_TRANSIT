package com.transit.service;

import com.transit.model.Channel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Envelope format for provider credentials stored in the application database.
 * The 256-bit master key must come from an external secret file/manager and is
 * deliberately never generated into application configuration.
 */
@Component
public class ChannelSecretService {
    private static final String PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "api-transit/channel-key/v1".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public ChannelSecretService(@Value("${security.data-encryption-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            this.key = null;
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException("security.data-encryption-key must be a Base64 encoded 32-byte key");
            }
            this.key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid security.data-encryption-key configuration", invalid);
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (isEncrypted(plaintext)) {
            return plaintext;
        }
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt channel credential", exception);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank() || !isEncrypted(stored)) {
            // Plaintext is accepted only to support a one-time migration of an
            // existing installation. All new writes go through encrypt().
            return stored;
        }
        requireKey();
        try {
            byte[] envelope = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (envelope.length <= NONCE_BYTES + 16) {
                throw new GeneralSecurityException("Encrypted value is truncated");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[envelope.length - NONCE_BYTES];
            System.arraycopy(envelope, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(envelope, NONCE_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt channel credential; verify the configured master key", exception);
        }
    }

    public Channel reveal(Channel channel) {
        if (channel != null) {
            channel.setApiKey(decrypt(channel.getApiKey()));
        }
        return channel;
    }

    public void redact(Channel channel) {
        if (channel == null) return;
        String stored = channel.getApiKey();
        channel.setApiKeyConfigured(stored != null && !stored.isBlank());
        if (stored != null && !stored.isBlank()) {
            // Listing channels must remain available even when a deployment
            // accidentally starts without the master key. Never decrypt a
            // credential merely to render an administrative preview.
            channel.setApiKeyPreview(isEncrypted(stored) ? "****" : mask(stored));
        }
        channel.setApiKey(null);
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Channel credential encryption is not configured; set security.data-encryption-key");
        }
    }
}
