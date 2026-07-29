package com.transit.service;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class VmCardCryptoService {

    public String encrypt(String plaintext, String publicKeyPem) {
        try {
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(pemBytes(publicKeyPem, "PUBLIC KEY")));
            int inputBlockSize = rsaBlockSize(key) - 11;
            byte[] encrypted = transform(plaintext.getBytes(StandardCharsets.UTF_8), inputBlockSize, key, Cipher.ENCRYPT_MODE);
            return HexFormat.of().formatHex(Base64.getEncoder().encode(encrypted));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to encrypt VMCard request; verify VMCARD_PUBLIC_KEY", exception);
        }
    }

    public String decrypt(String encodedCiphertext, String privateKeyPem) {
        try {
            RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pemBytes(privateKeyPem, "PRIVATE KEY")));
            byte[] ciphertext = Base64.getDecoder().decode(HexFormat.of().parseHex(encodedCiphertext));
            byte[] decrypted = transform(ciphertext, rsaBlockSize(key), key, Cipher.DECRYPT_MODE);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt VMCard response; verify VMCARD_PRIVATE_KEY", exception);
        }
    }

    private byte[] transform(byte[] input, int blockSize, java.security.Key key, int mode)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(mode, key);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int offset = 0; offset < input.length; offset += blockSize) {
            int length = Math.min(blockSize, input.length - offset);
            byte[] block = cipher.doFinal(input, offset, length);
            output.write(block, 0, block.length);
        }
        return output.toByteArray();
    }

    private int rsaBlockSize(RSAKey key) {
        return (key.getModulus().bitLength() + 7) / 8;
    }

    private byte[] pemBytes(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Missing " + type);
        }
        String normalized = pem.replace("\\n", "\n")
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }
}
