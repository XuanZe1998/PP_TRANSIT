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
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties="payment.local-test-mode=true")
@Transactional
class RechargePaymentIntegrationTests {
    @Autowired RechargeOrderService rechargeOrderService;
    @Autowired PaymentIntentService paymentIntentService;
    @Autowired AdminUserService adminUserService;
    @Autowired UserMapper userMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test void fixedPlanCreditsBaseAndGiftOnceAndFullRefundReversesBoth(){
        User user=User.builder().username("recharge-"+UUID.randomUUID()).password("test").email("buyer@example.com")
                .emailVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .role("USER").status("ACTIVE").balance(0).invoiceEnabled(true).createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        userMapper.insert(user);
        Long organizationId=createPersonalOrganization(user);
        Long planId=jdbcTemplate.queryForObject("SELECT id FROM recharge_plans WHERE bonus_percent>0 ORDER BY id LIMIT 1",Long.class);
        RechargeOrderRequest request=new RechargeOrderRequest(); request.setPlanId(planId); request.setPaymentMethod("alipay");
        request.setContactEmail(user.getEmail()); request.setBillingName("Lisa White"); request.setBillingAddressLine1("305 Main St");
        request.setBillingDistrict("Carlton"); request.setBillingCity("Carlton"); request.setBillingProvince("Oregon");
        request.setBillingPostalCode("97111"); request.setBillingCountry("United States");
        WalletRechargeOrder order=rechargeOrderService.create(user,request);
        PaymentIntent paid=paymentIntentService.start(user,order.getPaymentIntent().getId());
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(userMapper.selectById(user.getId()).getBalance()).isEqualTo(order.getTotalCreditUnits());
        assertThat(walletBalance(organizationId,user.getId())).isEqualTo(order.getTotalCreditUnits());
        paymentIntentService.start(user,paid.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE user_id=? AND type IN ('RECHARGE','GIFT')",Integer.class,user.getId())).isEqualTo(2);
        PaymentIntent refunded=paymentIntentService.refund(paid.getId(),"integration test refund");
        assertThat(refunded.getStatus()).isEqualTo("REFUNDED");
        assertThat(userMapper.selectById(user.getId()).getBalance()).isZero();
        assertThat(walletBalance(organizationId,user.getId())).isZero();
        assertThat(rechargeOrderService.get(user,order.getId()).getStatus()).isEqualTo("REFUNDED");
    }

    @Test void customRechargeRequiresPositiveAmountAndDoesNotGrantBonus(){
        User user=verifiedUser(false);
        RechargeOrderRequest request=new RechargeOrderRequest();
        request.setCustomAmount(new BigDecimal("12.34")); request.setPaymentMethod("wxpay");
        WalletRechargeOrder order=rechargeOrderService.create(user,request);
        assertThat(order.getPlanId()).isZero();
        assertThat(order.getPlanName()).isEqualTo("自定义充值");
        assertThat(order.getPaymentAmountUnits()).isEqualTo(123400L);
        assertThat(order.getBaseCreditUnits()).isEqualTo(123400L);
        assertThat(order.getBonusCreditUnits()).isZero();
        assertThat(order.getTotalCreditUnits()).isEqualTo(123400L);

        RechargeOrderRequest invalid=new RechargeOrderRequest();
        invalid.setCustomAmount(BigDecimal.ZERO); invalid.setPaymentMethod("alipay");
        assertThatThrownBy(() -> rechargeOrderService.create(user,invalid))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("greater than 0");
    }

    @Test void invoiceRequestRequiresAdministratorAccess(){
        User user=verifiedUser(false);
        Long planId=jdbcTemplate.queryForObject("SELECT id FROM recharge_plans ORDER BY id LIMIT 1",Long.class);
        RechargeOrderRequest request=new RechargeOrderRequest(); request.setPlanId(planId); request.setPaymentMethod("alipay");
        request.setNeedInvoice(true); request.setContactEmail(user.getEmail()); request.setBillingName("Invoice Buyer");
        request.setBillingAddressLine1("1 Main St"); request.setBillingDistrict("District"); request.setBillingCity("City");
        request.setBillingProvince("Province"); request.setBillingPostalCode("100000"); request.setBillingCountry("China");
        assertThatThrownBy(() -> rechargeOrderService.create(user,request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Invoice access has not been enabled");

        User enabled=adminUserService.updateUser(user.getId(),java.util.Map.of("invoiceEnabled",true));
        assertThat(enabled.isInvoiceEnabled()).isTrue();
        assertThat(rechargeOrderService.create(enabled,request).getInvoiceRequested()).isTrue();
    }

    private User verifiedUser(boolean invoiceEnabled){
        User user=User.builder().username("recharge-"+UUID.randomUUID()).password("test").email("buyer@example.com")
                .emailVerifiedAt(LocalDateTime.now(ZoneOffset.UTC)).role("USER").status("ACTIVE").balance(0)
                .invoiceEnabled(invoiceEnabled).createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        userMapper.insert(user);
        createPersonalOrganization(user);
        return user;
    }

    private Long createPersonalOrganization(User user){
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,'PERSONAL','ACTIVE',?,?,?)",
                user.getUsername()+" personal",user.getId(),now,now);
        Long organizationId=jdbcTemplate.queryForObject("SELECT MAX(id) FROM organizations WHERE created_by=?",Long.class,user.getId());
        jdbcTemplate.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',?)",
                organizationId,user.getId(),now);
        jdbcTemplate.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?,'TREASURY',0,'ACTIVE',?,?)",
                organizationId,user.getId(),now,now);
        jdbcTemplate.update("UPDATE users SET default_organization_id=? WHERE id=?",organizationId,user.getId());
        return organizationId;
    }

    private long walletBalance(Long organizationId,Long userId){
        Long value=jdbcTemplate.queryForObject("SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?",Long.class,organizationId,userId);
        return value==null?0:value;
    }
}
