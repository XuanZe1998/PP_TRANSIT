package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.MaPayProperties;
import com.transit.mapper.PaymentIntentMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaPayStartAmountTests {
    private final PaymentIntentMapper mapper = mock(PaymentIntentMapper.class);
    private final PaymentBusinessSettlementService settlement = mock(PaymentBusinessSettlementService.class);
    private final LegalDocumentService legal = mock(LegalDocumentService.class);

    @Test
    void floatingGatewayAmountCannotReplaceTheAgreedOrderAmountOrExposeAPaymentAction() {
        PaymentIntent intent = pending();
        PaymentIntentService payments = payments("0.02");
        assertThatThrownBy(() -> payments.start(user(), 9L, "127.0.0.1", "pc"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Gateway amount does not match");
        assertThat(intent.getSettlementAmountCents()).isEqualTo(1L);
        assertThat(intent.getPaymentUrl()).isNull();
        assertThat(intent.getProviderTradeNo()).isNull();
        verify(mapper, never()).updateById(any(PaymentIntent.class));
        verifyNoInteractions(settlement);
    }

    @Test
    void exactGatewayAmountPassesTheWholeStartFlowAndRemainsUnpaid() {
        PaymentIntent intent = pending();
        var result = payments("0.01").start(user(), 9L, "127.0.0.1", "pc");
        assertThat(result.action().type()).isEqualTo("QRCODE");
        assertThat(result.intent().getSettlementAmountCents()).isEqualTo(1L);
        assertThat(result.intent().getStatus()).isEqualTo("PENDING");
        verify(mapper).updateById(intent);
        verifyNoInteractions(settlement);
    }

    private PaymentIntent pending() {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(9L); intent.setUserId(7L); intent.setOrderNo("AMOUNT-TEST-9");
        intent.setStatus("PENDING"); intent.setPaymentProvider("MAPAY"); intent.setPaymentMethod("alipay");
        intent.setDescription("Amount verification product");
        intent.setSourceAmount(1L); intent.setSourceCurrency("CNY"); intent.setSourceScale(100L);
        intent.setSettlementAmountCents(1L); intent.setSettlementCurrency("CNY");
        intent.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15));
        when(mapper.selectById(9L)).thenReturn(intent);
        return intent;
    }

    private User user() { User user = new User(); user.setId(7L); return user; }

    private PaymentIntentService payments(String gatewayMoney) {
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"code\":1,\"trade_no\":\"M-1\",\"money\":\"" + gatewayMoney
                                + "\",\"qrcode\":\"https://pay.example/qr\"}").build())).build();
        MaPayProperties properties = new MaPayProperties();
        properties.setEnabled(true); properties.setMerchantId("1001"); properties.setMerchantKey("secret");
        properties.setNotifyUrl("https://merchant.example/webhooks/mapay");
        MaPayClient client = new MaPayClient(webClient, new ObjectMapper(), properties);
        return new PaymentIntentService(mapper, new MoneyService(BigDecimal.ONE), client, settlement, null, legal);
    }
}
