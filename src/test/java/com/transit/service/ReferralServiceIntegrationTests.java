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

@SpringBootTest(properties={"payment.local-test-mode=true","referral.scan-initial-ms=3600000"})
@Transactional
class ReferralServiceIntegrationTests {
    @Autowired ReferralService referrals;
    @Autowired AgentDistributionService agents;
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
    private ServiceOrder order(User buyer,OtherService product) {
        var request=new ServiceOrderCreateRequest();
        request.setServiceId(product.getId());request.setQuantity(1);request.setContactEmail(buyer.getEmail());
        request.setBillingName("Buyer");request.setBillingAddressLine1("Main street");request.setBillingDistrict("District");
        request.setBillingCity("City");request.setBillingProvince("Province");request.setBillingPostalCode("100000");
        request.setBillingCountry("China");request.setPaymentMethod("alipay");
        return orders.createOrder(buyer,request).getOrder();
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
    void normalCheckoutSnapshotsCommissionExcludingFeesAndPreservesInviteAndRules() {
        User inviter=user(),buyer=user(),other=user(); enable(inviter);
        String code=invite(inviter);
        assertThat(referrals.bindInvite(buyer.getId(),code,"REGISTER")).isTrue();
        assertThat(referrals.bindInvite(buyer.getId(),invite(other),"OAUTH_REGISTER")).isTrue();
        assertThat(referrals.bindInvite(inviter.getId(),code,"REGISTER")).isFalse();
        assertThat(referrals.bindInvite(other.getId(),"invalid","REGISTER")).isFalse();
        OtherService product=product(inviter);
        ServiceOrder first=order(buyer,product),second=order(buyer,product);
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
    void calculationCapsProfitAndRoundsDown() {
        var fixed=new ReferralService.Rule(true,"FIXED",500,0,1000);
        assertThat(ReferralService.calculate(1600,1000,1,fixed)).isEqualTo(300);
        assertThat(ReferralService.calculate(6000,2000,2,fixed)).isEqualTo(1000);
        assertThat(ReferralService.calculate(999,1000,1,fixed)).isZero();
        assertThat(ReferralService.calculate(1234,100,1,new ReferralService.Rule(true,"PERCENT",0,555,100))).isEqualTo(68);
    }

    @Test
    void withdrawalsHoldFundsRequireClaimAndReverseAfterPaidRefundWithoutDuplication() {
        User inviter=user(),buyer=user(),anotherAdmin=user();enable(inviter);
        referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);
        ServiceOrder first=null;
        for(int i=0;i<4;i++) { ServiceOrder order=order(buyer,product);mature(order);if(first==null)first=order; }
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
        OtherService product=product(inviter);ServiceOrder first=order(buyer,product);
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
        for(int i=0;i<3;i++) mature(order(buyer,product));
        assertThat(balance(inviter)).isEqualTo(1750);
        assertThatThrownBy(()->withdraw(inviter)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void feeOnlyRefundDoesNotReverseCommissionAndMerchandiseRefundsAreCumulative() {
        User inviter=user(),buyer=user();enable(inviter);referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        ServiceOrder order=order(buyer,product(inviter));mature(order);
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
        OtherService product=product(inviter);ServiceOrder first=order(buyer,product);mature(first);
        for(int i=0;i<3;i++) mature(order(buyer,product));
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
        ServiceOrder order=order(buyer,product);
        assertThat(order.getReferralCommissionCents()).isNull();
    }

    @Test
    void pendingWithdrawalIsReleasedAfterRefundAndCancellationIsIdempotent() {
        User inviter=user(),buyer=user();enable(inviter);referrals.bindInvite(buyer.getId(),invite(inviter),"REGISTER");
        OtherService product=product(inviter);
        ServiceOrder first=order(buyer,product);mature(first);
        for(int i=0;i<3;i++) mature(order(buyer,product));
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
            for(int i=0;i<4;i++) mature(order(b,product));
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
            last=order(buyer,product);
            jdbc.update("UPDATE service_orders SET status='FULFILLED',fulfilled_at=? WHERE id=?",now(),last.getId());
        }
        mature(last);
        assertThat(balance(inviter)).isEqualTo(500);
        jdbc.update("UPDATE other_services SET currency='USD' WHERE id=?",product.getId());
        ServiceOrder usd=order(buyer,product);
        long expected=BigDecimal.valueOf(usd.getAmountCents()-usd.getServiceFeeCents()).multiply(usd.getExchangeRate())
                .setScale(0,java.math.RoundingMode.DOWN).longValueExact();
        assertThat(jdbc.queryForObject("SELECT sale_cents FROM referral_orders WHERE order_id=?",Long.class,usd.getId())).isEqualTo(expected);
    }
}
