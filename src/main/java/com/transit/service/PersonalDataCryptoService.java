package com.transit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PersonalDataCryptoService {
    private static final String PREFIX = "pii:v1:";
    private static final byte[] AAD = "api-transit/personal-data/v1".getBytes(StandardCharsets.UTF_8);
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public PersonalDataCryptoService(@Value("${security.data-encryption-key:}") String encodedKey) {
        SecretKeySpec parsed = null;
        if (encodedKey != null && !encodedKey.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(encodedKey.trim());
            if (raw.length != 32) throw new IllegalStateException("security.data-encryption-key must contain 32 bytes");
            parsed = new SecretKeySpec(raw, "AES");
        }
        key = parsed;
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank() || key == null) return null;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce)); cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (Exception e) { throw new IllegalStateException("无法加密个人数据", e); }
    }

    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX) || key == null) return null;
        try {
            byte[] envelope = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] nonce = java.util.Arrays.copyOfRange(envelope, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce)); cipher.updateAAD(AAD);
            return new String(cipher.doFinal(java.util.Arrays.copyOfRange(envelope, 12, envelope.length)), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }
}
