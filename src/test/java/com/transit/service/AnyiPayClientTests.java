package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnyiPayClientTests {

    private String privateKey;
    private String publicKey;
    private AnyiPayClient client;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        client = new AnyiPayClient(WebClient.builder().build(), new ObjectMapper(), true, false,
                "https://a.tjrl3.cn", "1001", privateKey, publicKey,
                "https://merchant.example/webhooks/anyipay", "https://merchant.example/services",
                "alipay", 10, 300);
    }

    @Test
    void canonicalizationMatchesProviderRules() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("z", "last");
        values.put("empty", "  ");
        values.put("sign", "ignored");
        values.put("sign_type", "RSA");
        values.put("array", List.of("ignored"));
        values.put("a", "first");

        assertThat(AnyiPayClient.canonicalize(values)).isEqualTo("a=first&z=last");
    }

    @Test
    void pagePaymentUrlContainsRequiredFieldsAndValidSignature() {
        String url = client.createPagePaymentUrl("PLUS-1", "Test product", "1.00", "plus-order:1");
        URI uri = URI.create(url);
        Map<String, String> parameters = parseQuery(uri.getRawQuery());

        assertThat(uri.getScheme() + "://" + uri.getAuthority() + uri.getPath())
                .isEqualTo("https://a.tjrl3.cn/api/pay/submit");
        assertThat(parameters)
                .containsEntry("pid", "1001")
                .containsEntry("type", "alipay")
                .containsEntry("out_trade_no", "PLUS-1")
                .containsEntry("notify_url", "https://merchant.example/webhooks/anyipay")
                .containsEntry("return_url", "https://merchant.example/services")
                .containsEntry("name", "Test product")
                .containsEntry("money", "1.00")
                .containsEntry("param", "plus-order:1")
                .containsEntry("sign_type", "RSA")
                .containsKey("timestamp")
                .containsKey("sign");
        assertThat(AnyiPayClient.verify(
                AnyiPayClient.canonicalize(parameters), parameters.get("sign"), publicKey)).isTrue();
    }

    @Test
    void pagePaymentUrlHonorsSelectedWechatMethod() {
        String url = client.createPagePaymentUrl(
                "PLUS-2", "Test product", "2.00", "plus-order:2", "wxpay");

        assertThat(parseQuery(URI.create(url).getRawQuery())).containsEntry("type", "wxpay");
    }

    @Test
    void callbackRequiresValidSignatureMerchantAndFreshTimestamp() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("pid", "1001");
        payload.put("out_trade_no", "PLUS-1");
        payload.put("money", "12.34");
        payload.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        payload.put("trade_status", "TRADE_SUCCESS");
        payload.put("sign", AnyiPayClient.sign(AnyiPayClient.canonicalize(payload), privateKey));
        payload.put("sign_type", "RSA");

        assertThat(client.verifyCallback(multi(payload))).containsEntry("out_trade_no", "PLUS-1");

        payload.put("money", "99.99");
        assertThatThrownBy(() -> client.verifyCallback(multi(payload)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void callbackRejectsDuplicateParameters() {
        MultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        values.add("pid", "1001");
        values.add("pid", "attacker");

        assertThatThrownBy(() -> client.verifyCallback(values))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void moneyMutationsRequireSecondExplicitSwitch() {
        assertThatThrownBy(() -> client.refund(null, "PLUS-1", "1.00", "REFUND-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disabled");
        assertThatThrownBy(() -> client.submitTransfer("alipay", "buyer@example.com", "Buyer",
                "1.00", null, "TRANSFER-1", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disabled");
    }

    private MultiValueMap<String, String> multi(Map<String, String> values) {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        values.forEach(result::add);
        return result;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }
}
