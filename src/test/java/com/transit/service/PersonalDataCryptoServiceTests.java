package com.transit.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalDataCryptoServiceTests {
    @Test
    void encryptsIpAddressesWithRandomAuthenticatedEnvelopes() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        PersonalDataCryptoService crypto = new PersonalDataCryptoService(key);
        String first = crypto.encrypt("203.0.113.8"), second = crypto.encrypt("203.0.113.8");
        assertThat(first).startsWith("pii:v1:").isNotEqualTo(second).doesNotContain("203.0.113.8");
        assertThat(crypto.decrypt(first)).isEqualTo("203.0.113.8");
        assertThat(crypto.decrypt(first + "tampered")).isNull();
    }

    @Test
    void doesNotPersistReversibleDataWithoutAConfiguredKey() {
        PersonalDataCryptoService crypto = new PersonalDataCryptoService("");
        assertThat(crypto.encrypt("203.0.113.8")).isNull();
    }
}
