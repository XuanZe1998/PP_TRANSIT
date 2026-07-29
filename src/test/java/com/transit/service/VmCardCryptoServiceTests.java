package com.transit.service;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class VmCardCryptoServiceTests {

    @Test
    void roundTripsLongPayloadUsingPkcs1Chunking() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String publicPem = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        String privatePem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String payload = "{\"description\":\"" + "长参数-test-".repeat(90) + "\"}";

        VmCardCryptoService service = new VmCardCryptoService();
        String ciphertext = service.encrypt(payload, publicPem);
        String decrypted = service.decrypt(ciphertext, privatePem);

        assertThat(ciphertext).matches("[0-9a-f]+");
        assertThat(decrypted).isEqualTo(payload);
    }

    @Test
    void exposesEveryDocumentedCardOperation() {
        assertThat(VmCardOperation.values()).hasSize(12);
        assertThat(VmCardOperation.metadata())
                .extracting(row -> row.get("path"))
                .containsExactly(
                        "/getAccountBalance", "/getProductCode", "/createCard", "/cardDetail",
                        "/updateCardLimit", "/freezeCard", "/rechargeCard", "/refundCard",
                        "/cardTransaction", "/deleteCard", "/getCardList", "/getCardFlow"
                );
    }

    private String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----";
    }
}
