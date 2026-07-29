package com.transit.service;

import com.transit.model.Channel;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelSecretServiceTests {

    @Test
    void redactsEncryptedCredentialWithoutRequiringMasterKey() {
        ChannelSecretService writer = new ChannelSecretService(randomKey());
        Channel channel = Channel.builder().apiKey(writer.encrypt("provider-secret-value")).build();

        new ChannelSecretService("").redact(channel);

        assertThat(channel.isApiKeyConfigured()).isTrue();
        assertThat(channel.getApiKeyPreview()).isEqualTo("****");
        assertThat(channel.getApiKey()).isNull();
    }

    @Test
    void safelyMasksLegacyPlaintextAndHandlesEmptyCredential() {
        ChannelSecretService service = new ChannelSecretService("");
        Channel plaintext = Channel.builder().apiKey("abcd-provider-xyz9").build();
        Channel empty = Channel.builder().apiKey("").build();

        service.redact(plaintext);
        service.redact(empty);

        assertThat(plaintext.isApiKeyConfigured()).isTrue();
        assertThat(plaintext.getApiKeyPreview()).isEqualTo("abcd****xyz9");
        assertThat(plaintext.getApiKey()).isNull();
        assertThat(empty.isApiKeyConfigured()).isFalse();
        assertThat(empty.getApiKeyPreview()).isNull();
        assertThat(empty.getApiKey()).isNull();
    }

    private String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
