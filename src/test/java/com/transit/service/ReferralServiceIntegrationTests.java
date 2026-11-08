package com.transit.service;

import com.transit.dto.ServiceOrderCreateRequest;
import com.transit.mapper.UserMapper;
import com.transit.model.OtherService;
import com.transit.model.ServiceOrder;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"payment.local-test-mode=true","payment.local-checkout-bypass=true","server.address=127.0.0.1",
        "referral.scan-initial-ms=3600000","features.linknux.agent.enabled=true"})
@Transactional
class ReferralServiceIntegrationTests {
    @Autowired ReferralService referrals;
    @Autowired ServiceCouponAdminService coupons;
    @Autowired AgentDistributionService agents;
    @Autowired AgentSubscriptionPricingService agentPricing;
    @Autowired ServiceOrderService orders;
    @Autowired OtherServiceCatalogService catalog;
    @Autowired ServiceCommerceService commerce;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private User user() {
        String id="ref-"+UUID.randomUUID();
        User user=User.builder().username(id).email(id+"@example.com").password("test")
                .role("USER").status("ACTIVE").emailVerifiedAt(now()).createdAt(now()).balance(0).invoiceEnabled(false).build();
        users.insert(user);
        return user;
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private void enable(User admin) {
        long version=((Number)referrals.settings().get("version")).longValue();
        referrals.saveSettings(admin,new ReferralService.Settings(true,7,2000,"两个工作日内审核",version));
    }
    private String invite(User inviter) {
        referrals.enroll(inviter.getId());
        return jdbc.queryForObject("SELECT invite_code FROM agent_profiles WHERE user_id=?",String.class,inviter.getId());
    }
    private OtherService product(User admin) {
        OtherService service=catalog.create(OtherService.builder().name("Referral Plus "+UUID.randomUUID())
                .enabled(true).purchaseEnabled(true).priceCents(3000L).serviceFeeCents(100L).currency("CNY")
                .fulfillmentMode(ServiceCommerceService.AUTOMATIC).maxPurchaseQuantity(10)
                .wholesaleTiersJson("[]").inputSchemaJson("[]").build());
        commerce.importInventory(service.getId(),"CARD-"+UUID.randomUUID()+" CARD-"+UUID.randomUUID()+" CARD-"+UUID.randomUUID()+" CARD-"+UUID.randomUUID());
        var preview=referrals.preview(admin,service.getId(),new ReferralService.RulePreview(0,
                new ReferralService.Rule(true,"FIXED",500,0,1000),3000,1));
        referrals.applyRule(admin,service.getId(),(String)preview.get("token"));
        return service;
    }
    private ServiceOrder historicalOrder(User buyer, OtherService product) {
        ServiceOrder order = createOrder(buyer, product, "alipay", null);
        // Seed snapshots produced by the previous invitation program to exercise legacy payouts.
        jdbc.update("""
                UPDATE referral_orders SET mode='FIXED', commission_cents=fixed_cents,
                  status=CASE WHEN fixed_cents>0 AND EXISTS (
                      SELECT 1 FROM referral_members m WHERE m.user_id=referral_orders.inviter_id AND m.status='ACTIVE'
                  ) THEN 'AWAITING_PAYMENT' ELSE 'INELIGIBLE' END
                WHERE order_id=?
                """, order.getId());
        order.setReferralCommissionCents(referrals.orderCommission(order.getId()));
        return order;
    }

    private ServiceOrder createOrder(User buyer, OtherService product, String method, String coupon) {
        return createOrder(buyer, product, method, coupon, null, null);
    }

    private ServiceOrder createOrder(User buyer, OtherService product, String method, String coupon,
                                     String agentCode, Long expectedAmount) {
        var request=new ServiceOrderCreateRequest();
        request.setServiceId(product.getId());request.setQuantity(1);request.setContactEmail(buyer.getEmail());
        request.setBillingName("Buyer");request.setBillingAddressLine1("Main street");request.setBillingDistrict("District");
        request.setBillingCity("City");request.setBillingProvince("Province");request.setBillingPostalCode("100000");
        request.setBillingCountry("China");request.setPaymentMethod(method);request.setCouponCode(coupon);
        request.setAgentCode(agentCode);request.setExpectedAmountCents(expectedAmount);
        return orders.createOrder(buyer,request).getOrder();
    }

    @Test
    void agentLinkUsesRetailSnapshotAndCreditsOnlyTheResaleAgentWithRefundSource() {
        User agent=user(), buyer=user(), otherAgent=user();
        enable(agent);
        String code=invite(agent);
        var profile=agents.apply(agent.getId(),0);
        agents.reviewAgent(agent.getId(),((Number)profile.get("id")).longValue(),"ACTIVE","BASIC");
        String otherCode=invite(otherAgent);
        var otherProfile=agents.apply(otherAgent.getId(),0);
        agents.reviewAgent(agent.getId(),((Number)otherProfile.get("id")).longValue(),"ACTIVE","BASIC");
        assertThat(referrals.bindInvite(buyer.getId(),otherCode,"REGISTER")).isTrue();
        OtherService product=product(agent);
        agentPricing.setBase(agent,agent.getId(),product.getId(),new AgentSubscriptionPricingService.PriceChange(2500L,0));
        agentPricing.setRetail(agent.getId(),product.getId(),new AgentSubscriptionPricingService.PriceChange(2800L,1));

        assertThat(orders.quote(buyer,product.getId(),1,null).getListUnitPriceCents()).isEqualTo(3000L);
        assertThat(orders.quote(agent,product.getId(),1,null,code).getListUnitPriceCents()).isEqualTo(2500L);
        assertThat(orders.quote(buyer,product.getId(),1,null,code).getListUnitPriceCents()).isEqualTo(2800L);
        var publicPage=catalog.listPage(true,1,10,product.getName());
        agentPricing.applyCatalogPrices(publicPage.getItems(),null,code);
        assertThat(publicPage.getItems().get(0).getPriceCents()).isEqualTo(2800L);
        assertThat(agentPricing.prices(agent.getId(),false,1,10,product.getName()).getTotal()).isEqualTo(1L);

        jdbc.update("UPDATE users SET balance=1000000 WHERE id=?",buyer.getId());
        ServiceOrder order=createOrder(buyer,product,"balance",null,code,2900L);
        agentPricing.setBase(agent,agent.getId(),product.getId(),new AgentSubscriptionPricingService.PriceChange(2600L,2));
        assertThat(orders.startPayment(buyer,order.getId(),"127.0.0.1").getOrder().getStatus()).isEqualTo("FULFILLED");
        mature(order);
        referrals.settleDue();
        assertThat(agents.summary(agent.getId()).get("availableCommission")).isEqualTo(30000L);
        assertThat(agents.summary(otherAgent.getId()).get("availableCommission")).isEqualTo(50000L);
        assertThat(agentPricing.resaleLedger(agent.getId(),1,10).getItems().get(0))
                .containsEntry("entry_type","AGENT_RESALE").containsEntry("note","订阅差价 · "+order.getOrderNo());
        assertThat(agentPricing.resaleLedger(otherAgent.getId(),1,10).getTotal()).isZero();

        referrals.refund(agent,order.getId(),new ReferralService.Refund(1400,1400L,"REF-"+UUID.randomUUID(),now()));
        assertThat(agents.summary(agent.getId()).get("availableCommission")).isEqualTo(15000L);
        referrals.refund(agent,order.getId(),new ReferralService.Refund(1500,1400L,"REF-"+UUID.randomUUID(),now()));
        assertThat(agents.summary(agent.getId()).get("availableCommission")).isEqualTo(0L);
        assertThat(agentPricing.resaleLedger(agent.getId(),1,10).getTotal()).isEqualTo(3L);
    }
    private void mature(ServiceOrder order) {
        jdbc.update("UPDATE service_orders SET status='FULFILLED',fulfillment_status='COMPLETED',fulfilled_at=? WHERE id=?",now().minusDays(8),order.getId());
        // Simulate time passing for an already-frozen order without restarting its freeze window.
        jdbc.update("UPDATE referral_orders SET available_at=? WHERE order_id=? AND available_at IS NOT NULL",now().minusDays(1),order.getId());
        referrals.settleDue();
    }
    private long balance(User user) { return ((Number)referrals.summary(user.getId()).get("availableCents")).longValue(); }
    private long withdraw(User user) {
        referrals.saveAccount(user.getId(),new ReferralService.Account("ALIPAY","payout-"+user.getId()+"@example.com","Test recipient"));
        return ((Number)referrals.withdraw(user.getId(),new ReferralService.Withdrawal(2000,UUID.randomUUID().toString(),
                (String)referrals.account(user.getId()).get("accountToken"))).get("id")).longValue();
    }

    @Test
    void ordinaryInviterEarnsCommissionAndOneCouponWhileLaterAgentOrdersUseOnlyAgentLedger() {
        User inviter=user(), buyer=user();
        long version=((Number)referrals.settings().get("version")).longValue();
        referrals.saveSettings(inviter,new ReferralService.Settings(true,7,2000,"审核后打款",version,500L));
        assertThat(referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER")).isTrue();
        OtherService product=product(inviter);
        ServiceOrder first=createOrder(buyer,product,"alipay",null);
        assertThat(first.getReferralCommissionCents()).isEqualTo(500L);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_profiles WHERE user_id=?",String.class,inviter.getId())).isEqualTo("INVITER");
        assertThat(orders.startPayment(buyer,first.getId(),"127.0.0.1").getOrder().getStatus()).isEqualTo("FULFILLED");
        referrals.awardFirstPurchaseCoupons();
        referrals.awardFirstPurchaseCoupons();
        var page=coupons.personalPage(inviter.getId(),null,1,10);
        assertThat(page.getTotal()).isEqualTo(1);
        String code=(String)page.getItems().get(0).get("code");
        assertThatThrownBy(() -> orders.quote(buyer,product.getId(),1,code)).isInstanceOf(ResponseStatusException.class);
        assertThat(orders.quote(inviter,product.getId(),1,code).getCouponDiscountCents()).isEqualTo(500L);
        assertThat(referrals.summary(inviter.getId())).containsEntry("earnedCents",500L).containsEntry("pendingCents",500L);
        assertThat(referrals.list("commissions",inviter.getId(),1,10,null,null,null,null).getTotal()).isEqualTo(1);
        mature(first);
        assertThat(balance(inviter)).isEqualTo(500L);

        var agent=agents.apply(inviter.getId(),200);
        agents.reviewAgent(inviter.getId(),((Number)agent.get("id")).longValue(),"ACTIVE","BASIC");
        jdbc.update("UPDATE users SET balance=1000000 WHERE id=?",buyer.getId());
        ServiceOrder next=createOrder(buyer,product,"balance",null);
        assertThat(next.getReferralCommissionCents()).isEqualTo(500L);
        assertThat(orders.startPayment(buyer,next.getId(),"127.0.0.1").getOrder().getStatus()).isEqualTo("FULFILLED");
        orders.startPayment(buyer,next.getId(),"127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT balance FROM users WHERE id=?",Long.class,buyer.getId())).isEqualTo(690000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents WHERE business_type='SERVICE_ORDER' AND business_id=?",Long.class,next.getId())).isZero();
        mature(next);
        assertThat(balance(inviter)).isEqualTo(500L);
        assertThat(agents.summary(inviter.getId()).get("availableCommission")).isEqualTo(50000L);
        assertThat(agents.requestWithdrawal(inviter.getId(),50000L,"MANUAL",null)).containsEntry("status","PENDING");
        assertThat(agents.summary(inviter.getId()).get("availableCommission")).isEqualTo(0L);
        assertThat(coupons.personalPage(inviter.getId(),null,1,10).getTotal()).isEqualTo(1);

        jdbc.update("UPDATE users SET balance=1000000 WHERE id=?",inviter.getId());
        ServiceOrder discounted=createOrder(inviter,product,"balance",code);
        orders.startPayment(inviter,discounted.getId(),"127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT balance FROM users WHERE id=?",Long.class,inviter.getId())).isEqualTo(740000L);
        assertThatThrownBy(() -> orders.quote(inviter,product.getId(),1,code)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void normalCheckoutSnapshotsCommissionExcludingFeesAndPreservesInviteAndRules() {
        User inviter=user(),buyer=user(),other=user(); enable(inviter);
        String code=invite(inviter);
        assertThat(referrals.bindInvite(buyer.getId(),code,"REGISTER")).isTrue();
        assertThat(referrals.bindInvite(buyer.getId(),invite(other),"OAUTH_REGISTER")).isTrue();
        assertThat(referrals.bindInvite(inviter.getId(),code,"REGISTER")).isFalse();
        assertThat(referrals.bindInvite(other.getId(),"invalid","REGISTER")).isFalse();
        OtherService product=product(inviter);
        ServiceOrder first=historicalOrder(buyer,product),second=historicalOrder(buyer,product);
        assertThat(second.getPaymentAmountCents()).isEqualTo(3101);
        assertThat(second.getReferralCommissionCents()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT sale_cents FROM referral_orders WHERE order_id=?",Long.class,second.getId())).isEqualTo(3000);
        jdbc.update("UPDATE referral_rules SET fixed_cents=100,version=2 WHERE service_id=?",product.getId());
        mature(first); referrals.settleDue();
        assertThat(balance(inviter)).isEqualTo(500);
        assertThat(balance(other)).isZero();
        assertThat(referrals.list("invitations",inviter.getId(),1,10,null,null,null,null).getItems().get(0).get("customer_email"))
                .isNotEqualTo(buyer.getEmail());
        assertThatThrownBy(()->referrals.list("commissions",buyer.getId(),1,200,null,null,null,null)).isInstanceOf(ResponseStatusException.class);
        assertThat(referrals.list("commissions",buyer.getId(),1,10,null,null,null,null).getTotal()).isZero();
    }

    @Test
    void ordinaryReferralDoesNotDependOnAgentSwitchOrApprovalAndUsesCampaignFreeze() {
        User inviter=user(),buyer=user(); enable(inviter);
        assertThat(referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER")).isTrue();
        OtherService product=product(inviter);
        jdbc.update("UPDATE other_services SET price_cents=1000 WHERE id=?",product.getId());
        jdbc.update("UPDATE referral_rules SET cost_cents=1000 WHERE service_id=?",product.getId());
        jdbc.update("UPDATE referral_settings SET freeze_days=2 WHERE id=1");
        jdbc.update("UPDATE agent_profiles SET status='PENDING' WHERE user_id=?",inviter.getId());
        org.springframework.test.util.ReflectionTestUtils.setField(referrals,"agentsEnabled",false);
        try {
            assertThat(referrals.publicOffer(product.getId())).containsEntry("fixed_cents",500L);
            assertThat(referrals.list("products",inviter.getId(),1,10,product.getName(),null,null,null).getItems().get(0))
                    .containsEntry("fixed_cents",500L).doesNotContainKey("cost_cents");
            var order=createOrder(buyer,product,"alipay",null);
            assertThat(order.getReferralCommissionCents()).isEqualTo(500L);
            assertThat(jdbc.queryForMap("SELECT mode,freeze_days FROM referral_orders WHERE order_id=?",order.getId()))
                    .containsEntry("mode","FIXED").containsEntry("freeze_days",2);
            jdbc.update("UPDATE referral_settings SET enabled=FALSE WHERE id=1");
            assertThat(createOrder(buyer,product,"alipay",null).getReferralCommissionCents()).isNull();
            assertThat(referrals.publicOffer(product.getId())).isEmpty();
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(referrals,"agentsEnabled",true); }
    }

    @Test
    void ordinaryCommissionFollowsPaymentDeliveryFreezeAndReversesRefundExactlyOnce() {
        User inviter=user(),buyer=user();enable(inviter);
        referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);
        var order=createOrder(buyer,product,"alipay",null);
        assertThat(referrals.list("commissions",inviter.getId(),1,10,null,null,null,null).getTotal()).isEqualTo(1);
        assertThat(referrals.summary(inviter.getId())).containsEntry("earnedCents",0L);
        orders.startPayment(buyer,order.getId(),"127.0.0.1");
        referrals.settleDue();
        assertThat(jdbc.queryForObject("SELECT status FROM referral_orders WHERE order_id=?",String.class,order.getId())).isEqualTo("FROZEN");
        assertThat(referrals.summary(inviter.getId())).containsEntry("pendingCents",500L).containsEntry("availableCents",0L);
        mature(order);referrals.settleDue();
        assertThat(balance(inviter)).isEqualTo(500L);
        assertThat(agents.summary(inviter.getId()).get("availableCommission")).isEqualTo(0L);
        referrals.refund(inviter,order.getId(),new ReferralService.Refund(order.getPaymentAmountCents(),3000L,"ORDINARY-FULL-"+order.getId(),now()));
        referrals.refund(inviter,order.getId(),new ReferralService.Refund(order.getPaymentAmountCents(),3000L,"ORDINARY-FULL-"+order.getId(),now()));
        assertThat(balance(inviter)).isZero();
    }

    @Test
    void calculationCapsProfitAndRoundsDown() {
        var fixed=new ReferralService.Rule(true,"FIXED",500,0,1000);
        assertThat(ReferralService.calculate(1600,1000,1,fixed)).isEqualTo(300);
        assertThat(ReferralService.calculate(6000,2000,2,fixed)).isEqualTo(1000);
        assertThat(ReferralService.calculate(999,1000,1,fixed)).isZero();
        assertThat(ReferralService.calculate(1234,100,1,new ReferralService.Rule(true,"PERCENT",0,555,100))).isEqualTo(68);
        assertThat(ReferralService.calculate(13000,13000,1,fixed)).isZero();
        assertThat(ReferralService.calculate(14000,13000,1,fixed)).isEqualTo(500);
        assertThat(ReferralService.calculate(13999,13000,1,fixed)).isEqualTo(499);
    }

    @Test
    void previewExplainsZeroProfitLossQuantityAndDisabledRules() {
        User admin=user(); OtherService product=product(admin);
        var rule=new ReferralService.Rule(true,"FIXED",500,0,13000);
        var zero=referrals.preview(admin,product.getId(),new ReferralService.RulePreview(1,rule,13000,1));
        assertThat(zero).containsEntry("commissionCents",500L).containsEntry("grossCents",0L)
                .containsEntry("merchandiseCents",13000L).containsEntry("totalCostCents",13000L)
                .containsEntry("configuredCommissionCents",500L).containsEntry("profitCapCents",0L);
        var loss=referrals.preview(admin,product.getId(),new ReferralService.RulePreview(1,rule,12000,1));
        assertThat(loss).containsEntry("grossCents",-1000L).containsEntry("commissionCents",500L);
        var multiple=referrals.preview(admin,product.getId(),new ReferralService.RulePreview(1,rule,27999,2));
        assertThat(multiple).containsEntry("totalCostCents",26000L).containsEntry("configuredCommissionCents",1000L)
                .containsEntry("profitCapCents",999L).containsEntry("commissionCents",1000L);
        var disabled=referrals.preview(admin,product.getId(),new ReferralService.RulePreview(1,
                new ReferralService.Rule(false,"FIXED",500,0,13000),14000,1));
        assertThat(disabled).containsEntry("commissionCents",0L).containsEntry("enabled",false);
    }

    @Test
    void customCopyUsesPreviewVersionAndOnlyNewOrdersUseChangedRules() {
        User inviter=user(),buyer=user(); enable(inviter);
        referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);
        ServiceOrder oldOrder=historicalOrder(buyer,product);
        var change=referrals.preview(inviter,product.getId(),new ReferralService.RulePreview(1,
                new ReferralService.Rule(true,"FIXED",700,0,1000,"  专属邀请奖励\n以订单为准  "),3000,1));
        assertThat(referrals.publicOffer(product.getId())).containsEntry("display_text","");
        referrals.applyRule(inviter,product.getId(),(String)change.get("token"));
        assertThat(referrals.publicOffer(product.getId())).containsEntry("display_text","专属邀请奖励\n以订单为准")
                .doesNotContainKeys("cost_cents");
        assertThat(jdbc.queryForObject("SELECT commission_cents FROM referral_orders WHERE order_id=?",Long.class,oldOrder.getId())).isEqualTo(500);
        assertThat(historicalOrder(buyer,product).getReferralCommissionCents()).isEqualTo(700);
        assertThatThrownBy(()->referrals.preview(inviter,product.getId(),new ReferralService.RulePreview(1,
                new ReferralService.Rule(true,"FIXED",700,0,1000,"stale"),3000,1))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->referrals.preview(inviter,product.getId(),new ReferralService.RulePreview(2,
                new ReferralService.Rule(true,"FIXED",700,0,1000,"x".repeat(501)),3000,1))).isInstanceOf(ResponseStatusException.class);
        var clear=referrals.preview(inviter,product.getId(),new ReferralService.RulePreview(2,
                new ReferralService.Rule(true,"FIXED",700,0,1000,"  "),3000,1));
        referrals.applyRule(inviter,product.getId(),(String)clear.get("token"));
        assertThat(referrals.publicOffer(product.getId())).containsEntry("display_text","");
        jdbc.update("UPDATE referral_rules SET enabled=FALSE WHERE service_id=?",product.getId());
        assertThat(referrals.publicOffer(product.getId())).isEmpty();
    }

    @Test
    void withdrawalsHoldFundsRequireClaimAndReverseAfterPaidRefundWithoutDuplication() {
        User inviter=user(),buyer=user(),anotherAdmin=user();enable(inviter);
        referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);
        ServiceOrder first=null;
        for(int i=0;i<4;i++) { ServiceOrder order=historicalOrder(buyer,product);mature(order);if(first==null)first=order; }
        assertThat(balance(inviter)).isEqualTo(2000);
        long id=withdraw(inviter);
        assertThat(balance(inviter)).isZero();
        assertThatThrownBy(()->referrals.paid(inviter,id,new ReferralService.Payment(2000,"PAY-1",now()))).isInstanceOf(ResponseStatusException.class);
        referrals.review(inviter,new ReferralService.Review(List.of(id),"APPROVED",null));
        referrals.claim(inviter,id);
        assertThatThrownBy(()->referrals.reveal(anotherAdmin,id)).isInstanceOf(ResponseStatusException.class);
        assertThat(referrals.reveal(inviter,id).account()).contains("payout-");
        referrals.saveAccount(inviter.getId(),new ReferralService.Account("WECHAT","new-account","New recipient"));
        assertThat(referrals.reveal(inviter,id).channel()).isEqualTo("ALIPAY");
        referrals.paid(inviter,id,new ReferralService.Payment(2000,"PAY-"+id,now()));
        var refund=new ReferralService.Refund(first.getPaymentAmountCents(),3000L,"REFUND-"+UUID.randomUUID(),now());
        referrals.refund(inviter,first.getId(),refund);referrals.refund(inviter,first.getId(),refund);
        assertThat(referrals.summary(inviter.getId()).get("debtCents")).isEqualTo(500L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM referral_refunds WHERE order_id=?",Long.class,first.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT encrypted_details FROM referral_withdrawals WHERE id=?",String.class,id)).doesNotContain("payout-");
    }

    @Test
    void refundBeforeMaturityReducesCreditAndRefundDuringReviewReleasesHold() {
        User inviter=user(),buyer=user();enable(inviter);
        referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);ServiceOrder first=historicalOrder(buyer,product);
        jdbc.update("UPDATE service_orders SET status='FULFILLED',fulfilled_at=? WHERE id=?",now(),first.getId());
        referrals.settleDue();
        assertThat(balance(inviter)).isZero();
        referrals.refund(inviter,first.getId(),new ReferralService.Refund(1550,1500L,"HALF-"+UUID.randomUUID(),now()));
        Object due=jdbc.queryForObject("SELECT available_at FROM referral_orders WHERE order_id=?",Object.class,first.getId());
        jdbc.update("UPDATE service_orders SET fulfilled_at=? WHERE id=?",now().minusDays(8),first.getId());
        referrals.settleDue();
        assertThat(balance(inviter)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_at FROM referral_orders WHERE order_id=?",Object.class,first.getId())).isEqualTo(due);
        mature(first);
        assertThat(balance(inviter)).isEqualTo(250);
        for(int i=0;i<3;i++) mature(historicalOrder(buyer,product));
        assertThat(balance(inviter)).isEqualTo(1750);
        assertThatThrownBy(()->withdraw(inviter)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void feeOnlyRefundDoesNotReverseCommissionAndMerchandiseRefundsAreCumulative() {
        User inviter=user(),buyer=user();enable(inviter);referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        ServiceOrder order=historicalOrder(buyer,product(inviter));mature(order);
        referrals.refund(inviter,order.getId(),new ReferralService.Refund(100,0L,"FEE-"+UUID.randomUUID(),now()));
        assertThat(balance(inviter)).isEqualTo(500);
        var partial=new ReferralService.Refund(1000,1000L,"PART-"+UUID.randomUUID(),now());
        referrals.refund(inviter,order.getId(),partial);referrals.refund(inviter,order.getId(),partial);
        assertThat(balance(inviter)).isEqualTo(334);
        referrals.refund(inviter,order.getId(),new ReferralService.Refund(2000,2000L,"REST-"+UUID.randomUUID(),now()));
        assertThat(balance(inviter)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM referral_orders WHERE order_id=?",String.class,order.getId())).isEqualTo("REVERSED");
    }

    @Test
    void payingWithdrawalRemainsHeldDuringRefundUntilConfirmedNotTransferred() {
        User inviter=user(),buyer=user();enable(inviter);referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);ServiceOrder first=historicalOrder(buyer,product);mature(first);
        for(int i=0;i<3;i++) mature(historicalOrder(buyer,product));
        long id=withdraw(inviter);
        referrals.review(inviter,new ReferralService.Review(List.of(id),"APPROVED",null));referrals.claim(inviter,id);
        referrals.refund(inviter,first.getId(),new ReferralService.Refund(3100,3000L,"IN-FLIGHT-"+UUID.randomUUID(),now()));
        assertThat(jdbc.queryForObject("SELECT status FROM referral_withdrawals WHERE id=?",String.class,id)).isEqualTo("PAYING");
        assertThat(referrals.summary(inviter.getId()).get("debtCents")).isEqualTo(500L);
        referrals.paymentFailed(inviter,id,"经核实未转账");
        assertThat(balance(inviter)).isEqualTo(1500);
        assertThat(referrals.summary(inviter.getId()).get("debtCents")).isEqualTo(0L);
    }

    @Test
    void staleRulePreviewFailsAndDisabledMembersCannotCreateNewCommission() {
        User inviter=user(),buyer=user();enable(inviter);referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);
        var preview=referrals.preview(inviter,product.getId(),new ReferralService.RulePreview(1,new ReferralService.Rule(true,"FIXED",700,0,1000),3000,1));
        jdbc.update("UPDATE referral_rules SET version=2 WHERE service_id=?",product.getId());
        assertThatThrownBy(()->referrals.applyRule(inviter,product.getId(),(String)preview.get("token"))).isInstanceOf(ResponseStatusException.class);
        referrals.memberStatus(inviter,inviter.getId(),"SUSPENDED");
        ServiceOrder order=historicalOrder(buyer,product);
        assertThat(order.getReferralCommissionCents()).isNull();
    }

    @Test
    void pendingWithdrawalIsReleasedAfterRefundAndCancellationIsIdempotent() {
        User inviter=user(),buyer=user();enable(inviter);referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);
        ServiceOrder first=historicalOrder(buyer,product);mature(first);
        for(int i=0;i<3;i++) mature(historicalOrder(buyer,product));
        long id=withdraw(inviter);
        referrals.cancel(inviter.getId(),id);referrals.cancel(inviter.getId(),id);
        assertThat(balance(inviter)).isEqualTo(2000);
        long next=withdraw(inviter);
        referrals.review(inviter,new ReferralService.Review(List.of(next),"APPROVED",null));
        referrals.refund(inviter,first.getId(),new ReferralService.Refund(first.getPaymentAmountCents(),3000L,"REF-"+UUID.randomUUID(),now()));
        assertThat(balance(inviter)).isEqualTo(1500);
        assertThat(jdbc.queryForObject("SELECT status FROM referral_withdrawals WHERE id=?",String.class,next)).isEqualTo("CANCELLED");
    }

    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentWithdrawalsCannotSpendTheSameBalance() throws Exception {
        User inviter=transactions.execute(tx -> {
            User a=user(),b=user();enable(a);referrals.bindInvite(b.getId(),invite(a),"REGISTER");
            OtherService product=product(a);
            for(int i=0;i<4;i++) mature(historicalOrder(b,product));
            referrals.saveAccount(a.getId(),new ReferralService.Account("WECHAT","concurrency-"+a.getId(),"Recipient"));
            return a;
        });
        ExecutorService executor=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        Callable<Boolean> action=()->{
            ready.countDown();go.await(5,TimeUnit.SECONDS);
            try { referrals.withdraw(inviter.getId(),new ReferralService.Withdrawal(2000,UUID.randomUUID().toString(),
                    (String)referrals.account(inviter.getId()).get("accountToken")));return true; }
            catch(ResponseStatusException insufficient) { return false; }
        };
        try {
            Future<Boolean> one=executor.submit(action),two=executor.submit(action);
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(balance(inviter)).isZero();
        } finally {
            executor.shutdownNow();
            jdbc.update("UPDATE referral_settings SET enabled=FALSE WHERE id=1");
        }
    }

    @Test
    void cursorScanDoesNotStopBehindUnreadyOrdersAndForeignCurrencyUsesSnapshotRate() {
        User inviter=user(),buyer=user();enable(inviter);referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);
        commerce.importInventory(product.getId(),java.util.stream.IntStream.range(0,102)
                .mapToObj(i->"SCAN-"+i+"-"+UUID.randomUUID()).collect(java.util.stream.Collectors.joining(" ")));
        ServiceOrder last=null;
        for(int i=0;i<102;i++) {
            last=historicalOrder(buyer,product);
            jdbc.update("UPDATE service_orders SET status='FULFILLED',fulfilled_at=? WHERE id=?",now(),last.getId());
        }
        mature(last);
        assertThat(balance(inviter)).isEqualTo(500);
        jdbc.update("UPDATE other_services SET currency='USD' WHERE id=?",product.getId());
        ServiceOrder usd=historicalOrder(buyer,product);
        long expected=BigDecimal.valueOf(usd.getAmountCents()-usd.getServiceFeeCents()).multiply(usd.getExchangeRate())
                .setScale(0,java.math.RoundingMode.DOWN).longValueExact();
        assertThat(jdbc.queryForObject("SELECT sale_cents FROM referral_orders WHERE order_id=?",Long.class,usd.getId())).isEqualTo(expected);
    }
}
