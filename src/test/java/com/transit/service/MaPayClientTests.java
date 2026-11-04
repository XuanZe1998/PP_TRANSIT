package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.MaPayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaPayClientTests {

    @Test
    void sendsAndSignsTheConfiguredChannelForEachPaymentMethod() throws Exception {
        List<Map<String, String>> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/xpay/epay/mapi.php", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            requests.add(fields);
            byte[] response = "{\"code\":1,\"trade_no\":\"M-1\",\"qrcode\":\"https://pay.example/qr\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            MaPayProperties properties = properties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setChannelIds(Map.of("alipay", 123L, "wxpay", 456L));
            MaPayClient client = new MaPayClient(WebClient.create(), new ObjectMapper(), properties);
            for (String method : List.of("alipay", "wxpay")) {
                assertThat(client.start("CHANNEL-" + method, "Product", "0.01", "test", method,
                        "127.0.0.1", "pc").action().type()).isEqualTo("QRCODE");
            }
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0)).containsEntry("type", "alipay").containsEntry("channel_id", "123");
            assertThat(requests.get(1)).containsEntry("type", "wxpay").containsEntry("channel_id", "456");
            for (Map<String, String> fields : requests) {
                assertThat(fields.get("sign")).isEqualTo(MaPayClient.sign(fields, "secret"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void md5SignatureUsesAsciiOrderSkipsOnlyEmptyAndSupportsUtf8() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("z", "last");
        fields.put("empty", "");
        fields.put("sign", "ignored");
        fields.put("sign_type", "MD5");
        fields.put("name", "测试商品");
        fields.put("a", "first");

        assertThat(MaPayClient.sign(fields, "secret"))
                .isEqualTo("8aa7e26156c2ab009de37d365f2377af");
    }

    @Test
    void callbackRequiresSignatureMerchantAndRejectsDuplicateFields() {
        MaPayClient client = client("{\"code\":1}");
        Map<String, String> callback = new LinkedHashMap<>();
        callback.put("pid", "1001");
        callback.put("trade_no", "M-1");
        callback.put("out_trade_no", "LOCAL-1");
        callback.put("type", "alipay");
        callback.put("name", "测试商品");
        callback.put("money", "1.00");
        callback.put("trade_status", "TRADE_SUCCESS");
        callback.put("param", "payment-intent:1");
        callback.put("sign", MaPayClient.sign(callback, "secret"));
        callback.put("sign_type", "MD5");

        assertThat(client.verifyCallback(multi(callback)))
                .containsEntry("out_trade_no", "LOCAL-1");

        callback.put("money", "2.00");
        assertThatThrownBy(() -> client.verifyCallback(multi(callback)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("signature");

        MultiValueMap<String, String> duplicate = multi(callback);
        duplicate.add("pid", "attacker");
        assertThatThrownBy(() -> client.verifyCallback(duplicate))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void mapsExactlyOneProviderAction() {
        assertThat(startWith("payurl", "https://pay.example/redirect").action().type()).isEqualTo("REDIRECT");
        assertThat(startWith("qrcode", "weixin://wxpay/example").action().type()).isEqualTo("QRCODE");
        assertThat(startWith("urlscheme", "alipays://platformapi/startapp").action().type()).isEqualTo("URL_SCHEME");
    }

    @Test
    void reportsGatewayBalanceRejectionAsUnavailablePaymentChannel() {
        MaPayClient rejected = client("{\"code\":-1,\"msg\":\"余额不足\"}");
        assertThatThrownBy(() -> rejected.start("LOCAL-1", "Product", "1.00", "payment-intent:1",
                "alipay", "127.0.0.1", "pc"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getReason()).isEqualTo("支付通道暂不可用，请稍后重试或联系平台客服。");
                });
    }

    @Test
    void acceptsMultipleProviderActionsByPriorityAndRejectsUnsupportedPaymentMethod() {
        MaPayClient multiple = client("{\"code\":1,\"trade_no\":\"M-1\",\"money\":\"1.00\","
                + "\"payurl\":\"https://pay.example\",\"qrcode\":\"weixin://pay\"}");
        assertThat(multiple.start("LOCAL-1", "Product", "1.00", "payment-intent:1",
                "alipay", "127.0.0.1", "pc").action())
                .isEqualTo(new MaPayClient.PaymentAction("REDIRECT", "https://pay.example"));

        assertThatThrownBy(() -> client("{\"code\":1}").start("LOCAL-1", "Product", "1.00",
                "payment-intent:1", "bank", "127.0.0.1", "pc"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void fallsBackToSignedSubmitPageWhenProviderOmitsPaymentAction() {
        MaPayClient missing = client("{\"code\":1,\"trade_no\":\"M-1\",\"money\":\"1.00\"}");
        MaPayClient.PaymentAction action = missing.start("LOCAL-1", "Product", "1.00", "payment-intent:1",
                "alipay", "127.0.0.1", "pc").action();

        assertThat(action.type()).isEqualTo("REDIRECT");
        assertThat(action.url())
                .startsWith("https://mzf.mapay.test/xpay/epay/submit.php?")
                .contains("pid=1001")
                .contains("type=alipay")
                .contains("out_trade_no=LOCAL-1")
                .contains("notify_url=https%3A%2F%2Fmerchant.example%2Fwebhooks%2Fmapay")
                .contains("sign_type=MD5")
                .contains("sign=")
                .doesNotContain("secret");
    }

    private MaPayClient.PaymentStart startWith(String field, String value) {
        String json = "{\"code\":1,\"trade_no\":\"M-1\",\"money\":\"1.00\",\""
                + field + "\":\"" + value.replace("/", "\\/") + "\"}";
        return client(json).start("LOCAL-1", "Product", "1.00", "payment-intent:1",
                "alipay", "127.0.0.1", "pc");
    }

    private MaPayClient client(String responseBody) {
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(responseBody).build())).build();
        return new MaPayClient(webClient, new ObjectMapper(), properties());
    }

    private MaPayProperties properties() {
        MaPayProperties properties = new MaPayProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://mzf.mapay.test");
        properties.setMerchantId("1001");
        properties.setMerchantKey("secret");
        properties.setNotifyUrl("https://merchant.example/webhooks/mapay");
        properties.setReturnUrl("https://merchant.example/payment/result");
        properties.setAllowedMethods(List.of("alipay", "wxpay"));
        return properties;
    }

    private MultiValueMap<String, String> multi(Map<String, String> values) {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        values.forEach(result::add);
        return result;
    }
}
