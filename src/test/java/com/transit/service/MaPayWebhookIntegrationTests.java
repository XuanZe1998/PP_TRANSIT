package com.transit.service;

import com.transit.dto.RechargeOrderRequest;
import com.transit.mapper.PaymentIntentMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.User;
import com.transit.model.WalletRechargeOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "payment.local-test-mode=true",
        "mapay.enabled=true",
        "mapay.merchant-id=1001",
        "mapay.merchant-key=test-only-secret",
        "mapay.notify-url=https://example.invalid/webhooks/mapay"
})
@AutoConfigureWebTestClient
class MaPayWebhookIntegrationTests {
    private static final String TEST_KEY = "test-only-secret";

    @Autowired WebTestClient client;
    @Autowired RechargeOrderService rechargeOrderService;
    @Autowired PaymentIntentMapper paymentIntentMapper;
    @Autowired UserMapper userMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void signedHttpCallbackRejectsInvalidEvidenceAndCreditsWalletOnlyOnce() {
        User user = verifiedUser();
        Long organizationId = createPersonalWallet(user);
        RechargeOrderRequest request = new RechargeOrderRequest();
        request.setCustomAmount(new BigDecimal("1.00"));
        request.setPaymentMethod("alipay");
        WalletRechargeOrder order = rechargeOrderService.create(user, request);
        PaymentIntent intent = order.getPaymentIntent();
        String tradeNo = "MAPAY-WEBHOOK-" + UUID.randomUUID();
        Map<String, String> valid = signedCallback(intent, tradeNo);

        Map<String, String> tampered = new LinkedHashMap<>(valid);
        tampered.put("money", "2.00");
        post(form(tampered), "fail");

        Map<String, String> wrongMerchant = new LinkedHashMap<>(valid);
        wrongMerchant.put("pid", "attacker");
        wrongMerchant.put("sign", MaPayClient.sign(wrongMerchant, TEST_KEY));
        post(form(wrongMerchant), "fail");

        MultiValueMap<String, String> duplicated = form(valid);
        duplicated.add("money", "2.00");
        post(duplicated, "fail");

        Map<String, String> signedWrongAmount = new LinkedHashMap<>(valid);
        signedWrongAmount.put("money", "2.00");
        signedWrongAmount.put("sign", MaPayClient.sign(signedWrongAmount, TEST_KEY));
        post(form(signedWrongAmount), "fail");
        assertPendingWithoutCredit(user, organizationId, order, intent);

        post(form(valid), "success");
        assertThat(rechargeOrderService.get(user, order.getId()).getStatus()).isEqualTo("PAID");
        assertThat(paymentIntentMapper.selectById(intent.getId()).getStatus()).isEqualTo("PAID");
        assertThat(paymentIntentMapper.selectById(intent.getId()).getProviderTradeNo()).isEqualTo(tradeNo);
        assertCreditedOnce(user, organizationId, order);

        post(form(valid), "success");
        assertCreditedOnce(user, organizationId, order);
    }

    private void post(MultiValueMap<String, String> fields, String expected) {
        client.post().uri("/webhooks/mapay")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(fields))
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo(expected);
    }

    private Map<String, String> signedCallback(PaymentIntent intent, String tradeNo) {
        Map<String, String> callback = new LinkedHashMap<>();
        callback.put("pid", "1001");
        callback.put("trade_status", "TRADE_SUCCESS");
        callback.put("out_trade_no", intent.getOrderNo());
        callback.put("trade_no", tradeNo);
        callback.put("type", intent.getPaymentMethod());
        callback.put("money", BigDecimal.valueOf(intent.getSettlementAmountCents(), 2)
                .setScale(2).toPlainString());
        callback.put("param", "payment-intent:" + intent.getId());
        callback.put("sign_type", "MD5");
        callback.put("sign", MaPayClient.sign(callback, TEST_KEY));
        return callback;
    }

    private MultiValueMap<String, String> form(Map<String, String> fields) {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        fields.forEach(result::add);
        return result;
    }

    private void assertPendingWithoutCredit(User user, Long organizationId,
                                            WalletRechargeOrder order, PaymentIntent intent) {
        assertThat(rechargeOrderService.get(user, order.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(paymentIntentMapper.selectById(intent.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(userMapper.selectById(user.getId()).getBalance()).isZero();
        assertThat(walletBalance(organizationId, user.getId())).isZero();
        assertThat(creditEntries(user.getId(), order.getId())).isZero();
    }

    private void assertCreditedOnce(User user, Long organizationId, WalletRechargeOrder order) {
        assertThat(userMapper.selectById(user.getId()).getBalance()).isEqualTo(order.getTotalCreditUnits());
        assertThat(walletBalance(organizationId, user.getId())).isEqualTo(order.getTotalCreditUnits());
        assertThat(creditEntries(user.getId(), order.getId())).isEqualTo(1);
    }

    private Long walletBalance(Long organizationId, Long userId) {
        return jdbc.queryForObject("SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?",
                Long.class, organizationId, userId);
    }

    private Integer creditEntries(Long userId, Long orderId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_transactions
                 WHERE user_id=? AND reference_id=? AND reference_type IN ('RECHARGE_BASE','RECHARGE_GIFT')
                """, Integer.class, userId, orderId);
    }

    private User verifiedUser() {
        String identity = "mapay-http-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        User user = User.builder().username(identity).password("test")
                .email(identity + "@example.com").emailVerifiedAt(now)
                .role("USER").status("ACTIVE").balance(0).invoiceEnabled(false)
                .createdAt(now).build();
        userMapper.insert(user);
        return user;
    }

    private Long createPersonalWallet(User user) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at)
                VALUES (?,'PERSONAL','ACTIVE',?,?,?)
                """, user.getUsername() + " personal", user.getId(), now, now);
        Long organizationId = jdbc.queryForObject(
                "SELECT MAX(id) FROM organizations WHERE created_by=?", Long.class, user.getId());
        jdbc.update("""
                INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at)
                VALUES (?,?,'OWNER','ACTIVE',?)
                """, organizationId, user.getId(), now);
        jdbc.update("""
                INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at)
                VALUES (?,?,'TREASURY',0,'ACTIVE',?,?)
                """, organizationId, user.getId(), now, now);
        jdbc.update("UPDATE users SET default_organization_id=? WHERE id=?", organizationId, user.getId());
        return organizationId;
    }
}
