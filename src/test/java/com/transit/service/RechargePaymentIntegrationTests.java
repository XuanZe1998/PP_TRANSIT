package com.transit.service;

import com.transit.dto.RechargeOrderRequest;
import com.transit.mapper.UserMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.User;
import com.transit.model.WalletRechargeOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties="payment.local-test-mode=true")
@Transactional
class RechargePaymentIntegrationTests {
    @Autowired RechargeOrderService rechargeOrderService;
    @Autowired PaymentIntentService paymentIntentService;
    @Autowired UserMapper userMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test void fixedPlanCreditsBaseAndGiftOnceAndFullRefundReversesBoth(){
        User user=User.builder().username("recharge-"+UUID.randomUUID()).password("test").email("buyer@example.com")
                .emailVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .role("USER").status("ACTIVE").balance(0).createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        userMapper.insert(user);
        Long planId=jdbcTemplate.queryForObject("SELECT id FROM recharge_plans WHERE bonus_percent>0 ORDER BY id LIMIT 1",Long.class);
        RechargeOrderRequest request=new RechargeOrderRequest(); request.setPlanId(planId); request.setPaymentMethod("alipay");
        request.setContactEmail(user.getEmail()); request.setBillingName("Lisa White"); request.setBillingAddressLine1("305 Main St");
        request.setBillingDistrict("Carlton"); request.setBillingCity("Carlton"); request.setBillingProvince("Oregon");
        request.setBillingPostalCode("97111"); request.setBillingCountry("United States");
        WalletRechargeOrder order=rechargeOrderService.create(user,request);
        PaymentIntent paid=paymentIntentService.start(user,order.getPaymentIntent().getId());
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(userMapper.selectById(user.getId()).getBalance()).isEqualTo(order.getTotalCreditUnits());
        paymentIntentService.start(user,paid.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE user_id=? AND type IN ('RECHARGE','GIFT')",Integer.class,user.getId())).isEqualTo(2);
        PaymentIntent refunded=paymentIntentService.refund(paid.getId(),"integration test refund");
        assertThat(refunded.getStatus()).isEqualTo("REFUNDED");
        assertThat(userMapper.selectById(user.getId()).getBalance()).isZero();
        assertThat(rechargeOrderService.get(user,order.getId()).getStatus()).isEqualTo("REFUNDED");
    }
}
