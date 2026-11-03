package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.transit.mapper.PaymentIntentMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalPaymentStartTests {
    private final PaymentIntentMapper mapper = mock(PaymentIntentMapper.class);
    private final MaPayClient gateway = mock(MaPayClient.class);
    private final PaymentBusinessSettlementService settlement = mock(PaymentBusinessSettlementService.class);
    private final LegalDocumentService legal = mock(LegalDocumentService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PaymentIntentService payments = new PaymentIntentService(mapper, null, gateway, settlement,
            new TransactionTemplate(transactions), legal);

    private PaymentIntent pending() {
        ReflectionTestUtils.setField(payments, "localTestMode", true);
        PaymentIntent intent = new PaymentIntent();
        intent.setId(9L); intent.setUserId(7L); intent.setOrderNo("LOCAL-TEST-9");
        intent.setStatus("PENDING"); intent.setPaymentProvider("MAPAY"); intent.setPaymentMethod("alipay");
        intent.setSourceAmount(100L); intent.setSourceCurrency("CNY"); intent.setSourceScale(100L);
        intent.setSettlementAmountCents(100L); intent.setSettlementCurrency("CNY");
        intent.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15));
        intent.setPaymentActionType("QRCODE"); intent.setPaymentUrl("https://pay.example.test/old");
        when(mapper.selectById(9L)).thenReturn(intent);
        when(mapper.selectOne(any())).thenReturn(intent, (PaymentIntent) null);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.update(any(PaymentIntent.class), org.mockito.ArgumentMatchers.<Wrapper<PaymentIntent>>any())).thenReturn(1);
        return intent;
    }

    private User user(long id) { User user = new User(); user.setId(id); return user; }

    @Test void localPaymentIgnoresAnOldQrCodeAndSettlesOnlyOnce() {
        PaymentIntent intent = pending();
        var result = payments.start(user(7L), 9L, "127.0.0.1", "pc");
        assertThat(result.intent().getStatus()).isEqualTo("PAID");
        assertThat(result.intent().getProviderTradeNo()).isEqualTo("LOCAL-LOCAL-TEST-9");
        assertThat(result.intent().getInternalState()).isEqualTo("LOCAL_TEST");
        assertThat(result.action()).isNull();
        assertThat(payments.start(user(7L), 9L, "127.0.0.1", "pc").action()).isNull();
        verify(settlement, times(1)).settle(intent);
        verifyNoInteractions(gateway);
    }

    @Test void testPaymentsStillRequireOrderOwnership() {
        pending();
        assertThatThrownBy(() -> payments.start(user(8L), 9L, "127.0.0.1", "pc"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(gateway, settlement);
    }

    @Test void normalPaymentStillUsesExistingGatewayAction() {
        pending();
        ReflectionTestUtils.setField(payments, "localTestMode", false);
        var result = payments.start(user(7L), 9L, "127.0.0.1", "pc");
        assertThat(result.intent().getStatus()).isEqualTo("PENDING");
        assertThat(result.action().url()).isEqualTo("https://pay.example.test/old");
        verifyNoInteractions(gateway, settlement);
    }
}
