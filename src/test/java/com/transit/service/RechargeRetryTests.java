package com.transit.service;

import com.transit.dto.RechargeOrderRequest;
import com.transit.mapper.UserMapper;
import com.transit.model.User;
import com.transit.model.WalletRechargeOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "payment.local-test-mode=true")
@Transactional
class RechargeRetryTests {
    @Autowired RechargeOrderService recharges;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentIntentService payments;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockBean MaPayClient gateway;

    @Test
    void retryPreservesTheQuoteAndCreditButCreatesOnlyOneReplacement() {
        User user = user();
        WalletRechargeOrder old = expired(user, "USD");
        var next = recharges.retry(user, old.getId());
        var repeated = recharges.retry(user, old.getId());
        assertThat(next.getId()).isNotEqualTo(old.getId()).isEqualTo(repeated.getId());
        assertThat(next.getOrderNo()).isNotEqualTo(old.getOrderNo());
        assertThat(next.getTotalCreditUnits()).isEqualTo(old.getTotalCreditUnits());
        assertThat(next.getPaymentMethod()).isEqualTo(old.getPaymentMethod());
        assertThat(next.getPaymentIntent().getSettlementAmountCents()).isEqualTo(old.getPaymentIntent().getSettlementAmountCents());
        assertThat(next.getPaymentIntent().getSourceCurrency()).isEqualTo("USD");
        assertThat(next.getPaymentIntent().getExchangeRate()).isEqualByComparingTo(old.getPaymentIntent().getExchangeRate());
        assertThat(next.getPaymentIntent().getProviderTradeNo()).isNull();
        assertThat(next.getPaymentIntent().getExpiresAt()).isAfter(old.getPaymentIntent().getExpiresAt());
        assertThat(recharges.get(user, old.getId()).getRetryOrderId()).isEqualTo(next.getId());
        assertThat(users.selectById(user.getId()).getBalance()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void paidAtTheGatewayIsSettledBeforeRetryInsteadOfCreatingAnotherOrder() {
        User user = user();
        var old = expired(user);
        var intent = old.getPaymentIntent();
        jdbc.update("UPDATE payment_intents SET provider_trade_no='RETRY-PAID' WHERE id=?", intent.getId());
        when(gateway.query(old.getOrderNo())).thenReturn(new MaPayClient.OrderResult(true, "RETRY-PAID", old.getOrderNo(), "wxpay", "1.00", "payment-intent:" + intent.getId()));
        var result = recharges.retry(user, old.getId());
        assertThat(result.getId()).isEqualTo(old.getId());
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getRetryOrderId()).isNull();
        assertThat(users.selectById(user.getId()).getBalance()).isEqualTo(10000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recharge_orders WHERE user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void unavailableGatewayAndForeignOrdersCannotCreateReplacementPayments() {
        User user = user();
        var old = expired(user);
        assertThatThrownBy(() -> recharges.retry(user(), old.getId())).hasMessageContaining("not found");
        jdbc.update("UPDATE payment_intents SET provider_trade_no='RETRY-UNKNOWN' WHERE id=?", old.getPaymentIntent().getId());
        when(gateway.query(old.getOrderNo())).thenThrow(new IllegalStateException("gateway unavailable"));
        assertThatThrownBy(() -> recharges.retry(user, old.getId())).hasMessageContaining("gateway unavailable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recharge_orders WHERE user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void lateOldPaymentAndNewPaymentEachCreditOnceWithoutOverwritingHistory() {
        User user = user();
        var old = expired(user);
        var next = recharges.retry(user, old.getId());
        for (var order : java.util.List.of(old, next)) {
            var evidence = java.util.Map.of("trade_status", "TRADE_SUCCESS", "out_trade_no", order.getOrderNo(),
                    "trade_no", "LATE-RETRY-" + order.getId(), "type", "wxpay", "money", "1.00",
                    "param", "payment-intent:" + order.getPaymentIntent().getId());
            payments.receiveNotification(evidence);
            payments.receiveNotification(evidence);
        }
        assertThat(recharges.get(user, old.getId()).getStatus()).isEqualTo("PAID");
        assertThat(recharges.get(user, next.getId()).getStatus()).isEqualTo("PAID");
        assertThat(users.selectById(user.getId()).getBalance()).isEqualTo(20000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE user_id=? AND reference_type='RECHARGE_BASE'", Integer.class, user.getId())).isEqualTo(2);
    }

    private User user() {
        String identity = "retry-" + UUID.randomUUID();
        var user = User.builder().username(identity).password("test").email(identity + "@example.com")
                .emailVerifiedAt(LocalDateTime.now(ZoneOffset.UTC)).role("USER").status("ACTIVE").balance(0)
                .invoiceEnabled(false).createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        users.insert(user);
        return user;
    }

    private WalletRechargeOrder expired(User user) {
        return expired(user, "CNY");
    }

    private WalletRechargeOrder expired(User user, String currency) {
        var request = new RechargeOrderRequest(); request.setCustomAmount(new BigDecimal("1.00")); request.setPaymentMethod("wxpay");
        request.setCurrency(currency);
        var order = recharges.create(user, request);
        jdbc.update("UPDATE wallet_recharge_orders SET status='EXPIRED' WHERE id=?", order.getId());
        jdbc.update("UPDATE payment_intents SET status='EXPIRED',expires_at=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), order.getPaymentIntent().getId());
        sqlSession.clearCache();
        return recharges.get(user, order.getId());
    }
}
