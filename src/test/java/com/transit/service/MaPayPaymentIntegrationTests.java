package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.RechargeOrderRequest;
import com.transit.dto.ServiceOrderCreateRequest;
import com.transit.mapper.PaymentIntentMapper;
import com.transit.mapper.ServiceInventoryItemMapper;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.OtherService;
import com.transit.model.PaymentIntent;
import com.transit.model.ServiceInventoryItem;
import com.transit.model.ServiceOrder;
import com.transit.model.User;
import com.transit.model.WalletRechargeOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "payment.local-test-mode=true")
@Transactional
class MaPayPaymentIntegrationTests {
    @Autowired RechargeOrderService rechargeOrderService;
    @Autowired PaymentIntentService paymentIntentService;
    @Autowired PaymentIntentMapper paymentIntentMapper;
    @Autowired OtherServiceCatalogService catalogService;
    @Autowired ServiceCommerceService commerceService;
    @Autowired ServiceOrderService orderService;
    @Autowired ServiceOrderMapper orderMapper;
    @Autowired ServiceInventoryItemMapper inventoryMapper;
    @Autowired UserMapper userMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MaPayLegacyPaymentMigration legacyMigration;

    @Test
    void expiredRechargeCallbackCreditsUserAndOrganizationWalletExactlyOnce() {
        User user = verifiedUser();
        Long organizationId = createPersonalWallet(user);
        Long planId = jdbc.queryForObject(
                "SELECT id FROM recharge_plans WHERE enabled=TRUE ORDER BY bonus_percent DESC,id LIMIT 1", Long.class);
        RechargeOrderRequest request = new RechargeOrderRequest();
        request.setPlanId(planId);
        request.setPaymentMethod("alipay");
        WalletRechargeOrder created = rechargeOrderService.create(user, request);
        PaymentIntent intent = created.getPaymentIntent();

        jdbc.update("UPDATE payment_intents SET status='EXPIRED' WHERE id=?", intent.getId());
        jdbc.update("UPDATE wallet_recharge_orders SET status='EXPIRED' WHERE id=?", created.getId());
        Map<String, String> callback = callback(intent, "MAPAY-RCG-1");
        paymentIntentService.receiveNotification(callback);
        paymentIntentService.receiveNotification(callback);

        WalletRechargeOrder paid = rechargeOrderService.get(user, created.getId());
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paymentIntentMapper.selectById(intent.getId()).getStatus()).isEqualTo("PAID");
        assertThat(userMapper.selectById(user.getId()).getBalance()).isEqualTo(created.getTotalCreditUnits());
        assertThat(jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE organization_id=? AND user_id=?",
                Long.class, organizationId, user.getId())).isEqualTo(created.getTotalCreditUnits());
        int expectedEntries = created.getBonusCreditUnits() > 0 ? 2 : 1;
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_transactions
                 WHERE user_id=? AND reference_id=? AND reference_type IN ('RECHARGE_BASE','RECHARGE_GIFT')
                """, Integer.class, user.getId(), created.getId())).isEqualTo(expectedEntries);
    }

    @Test
    void servicePaymentFulfillsOnceAndReleasedInventoryMovesToReviewUntilRestocked() {
        User user = verifiedUser();
        OtherService deliveredService = automaticService("MaPay delivery " + UUID.randomUUID());
        commerceService.importInventory(deliveredService.getId(), "MAPAY-CARD-ONE");
        ServiceOrder delivered = orderService.createOrder(user, orderRequest(deliveredService, user)).getOrder();
        PaymentIntent deliveredIntent = paymentIntentService.getByBusiness(
                PaymentBusinessSettlementService.SERVICE_ORDER, delivered.getId());
        Map<String, String> deliveredCallback = callback(deliveredIntent, "MAPAY-SVC-1");
        paymentIntentService.receiveNotification(deliveredCallback);
        paymentIntentService.receiveNotification(deliveredCallback);

        ServiceOrder fulfilled = orderMapper.selectById(delivered.getId());
        assertThat(fulfilled.getStatus()).isEqualTo("FULFILLED");
        assertThat(fulfilled.getFulfillmentStatus()).isEqualTo("COMPLETED");
        assertThat(inventoryMapper.selectCount(new LambdaQueryWrapper<ServiceInventoryItem>()
                .eq(ServiceInventoryItem::getReservedOrderId, delivered.getId())
                .eq(ServiceInventoryItem::getStatus, ServiceCommerceService.DELIVERED))).isEqualTo(1);

        OtherService lateService = automaticService("MaPay late delivery " + UUID.randomUUID());
        commerceService.importInventory(lateService.getId(), "RELEASED-BEFORE-PAYMENT");
        ServiceOrder late = orderService.createOrder(user, orderRequest(lateService, user)).getOrder();
        ServiceInventoryItem released = inventoryMapper.selectOne(new LambdaQueryWrapper<ServiceInventoryItem>()
                .eq(ServiceInventoryItem::getServiceId, lateService.getId()).last("LIMIT 1"));
        commerceService.deleteAvailableInventory(lateService.getId(), released.getId());
        PaymentIntent lateIntent = paymentIntentService.getByBusiness(
                PaymentBusinessSettlementService.SERVICE_ORDER, late.getId());
        paymentIntentService.receiveNotification(callback(lateIntent, "MAPAY-SVC-LATE"));

        ServiceOrder review = orderMapper.selectById(late.getId());
        assertThat(paymentIntentMapper.selectById(lateIntent.getId()).getStatus()).isEqualTo("PAID");
        assertThat(review.getStatus()).isEqualTo("PAID");
        assertThat(review.getFulfillmentStatus()).isEqualTo("REVIEW_REQUIRED");

        commerceService.importInventory(lateService.getId(), "ADMIN-RESTOCKED-CARD");
        ServiceOrder retried = orderService.retryAutomaticFulfillment(late.getId());
        assertThat(retried.getStatus()).isEqualTo("FULFILLED");
        assertThat(retried.getFulfillmentStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void fulfillmentExceptionRollsBackDeliverySideEffectsButKeepsPaymentFact() {
        User user = verifiedUser();
        OtherService service = automaticService("MaPay fulfillment failure " + UUID.randomUUID());
        commerceService.importInventory(service.getId(), "ENCRYPTED-CARD");
        ServiceInventoryItem item = inventoryMapper.selectOne(new LambdaQueryWrapper<ServiceInventoryItem>()
                .eq(ServiceInventoryItem::getServiceId, service.getId()).last("LIMIT 1"));
        jdbc.update("UPDATE service_inventory_items SET content_encrypted='enc:v1:invalid-ciphertext' WHERE id=?", item.getId());
        ServiceOrder order = orderService.createOrder(user, orderRequest(service, user)).getOrder();
        PaymentIntent intent = paymentIntentService.getByBusiness(
                PaymentBusinessSettlementService.SERVICE_ORDER, order.getId());

        paymentIntentService.receiveNotification(callback(intent, "MAPAY-SVC-FAIL"));

        ServiceOrder stored = orderMapper.selectById(order.getId());
        assertThat(paymentIntentMapper.selectById(intent.getId()).getStatus()).isEqualTo("PAID");
        assertThat(stored.getStatus()).isEqualTo("PAID");
        assertThat(stored.getFulfillmentStatus()).isEqualTo("REVIEW_REQUIRED");
        ServiceInventoryItem rolledBack = inventoryMapper.selectById(item.getId());
        assertThat(rolledBack.getStatus()).isEqualTo(ServiceCommerceService.AVAILABLE);
        assertThat(rolledBack.getReservedOrderId()).isNull();
    }

    @Test
    void mismatchedCallbackFactsAreRejectedWithoutSettlement() {
        User user = verifiedUser();
        RechargeOrderRequest request = new RechargeOrderRequest();
        request.setCustomAmount(new BigDecimal("12.34"));
        request.setPaymentMethod("wxpay");
        PaymentIntent intent = rechargeOrderService.create(user, request).getPaymentIntent();

        Map<String, String> wrongAmount = callback(intent, "MAPAY-BAD-1");
        wrongAmount.put("money", "12.35");
        assertRejected(wrongAmount, "amount");

        Map<String, String> wrongType = callback(intent, "MAPAY-BAD-2");
        wrongType.put("type", "alipay");
        assertRejected(wrongType, "method");

        Map<String, String> wrongNamespace = callback(intent, "MAPAY-BAD-3");
        wrongNamespace.put("param", "payment-intent:999999");
        assertRejected(wrongNamespace, "namespace");

        Map<String, String> wrongStatus = callback(intent, "MAPAY-BAD-4");
        wrongStatus.put("trade_status", "TRADE_CLOSED");
        assertRejected(wrongStatus, "status");
        assertThat(paymentIntentMapper.selectById(intent.getId()).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void legacyPendingIntentIsCancelledButHistoricalPaidIntentIsPreserved() {
        User user = verifiedUser();
        RechargeOrderRequest request = new RechargeOrderRequest();
        request.setCustomAmount(new BigDecimal("1.00"));
        request.setPaymentMethod("alipay");
        WalletRechargeOrder order = rechargeOrderService.create(user, request);
        PaymentIntent intent = order.getPaymentIntent();
        jdbc.update("UPDATE payment_intents SET payment_provider='LEGACY',status='PENDING' WHERE id=?", intent.getId());
        jdbc.update("DELETE FROM system_settings WHERE setting_key='mapay.legacy.payment.migration.v1'");

        legacyMigration.migrate();

        assertThat(paymentIntentMapper.selectById(intent.getId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(rechargeOrderService.get(user, order.getId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_settings WHERE setting_key='mapay.legacy.payment.migration.v1'",
                Integer.class)).isEqualTo(1);
    }

    private void assertRejected(Map<String, String> callback, String message) {
        assertThatThrownBy(() -> paymentIntentService.receiveNotification(callback))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(message);
    }

    private Map<String, String> callback(PaymentIntent intent, String tradeNo) {
        Map<String, String> callback = new LinkedHashMap<>();
        callback.put("trade_status", "TRADE_SUCCESS");
        callback.put("out_trade_no", intent.getOrderNo());
        callback.put("trade_no", tradeNo);
        callback.put("type", intent.getPaymentMethod());
        callback.put("money", BigDecimal.valueOf(intent.getSettlementAmountCents(), 2).setScale(2).toPlainString());
        callback.put("param", "payment-intent:" + intent.getId());
        return callback;
    }

    private User verifiedUser() {
        String identity = "mapay-" + UUID.randomUUID();
        User user = User.builder()
                .username(identity).password("test").email(identity + "@example.com")
                .emailVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .role("USER").status("ACTIVE").balance(0).invoiceEnabled(false)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
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

    private OtherService automaticService(String name) {
        return catalogService.create(OtherService.builder()
                .name(name).description("integration test").enabled(true).purchaseEnabled(true)
                .priceCents(100L).serviceFeeCents(1L).currency("CNY")
                .fulfillmentMode(ServiceCommerceService.AUTOMATIC).maxPurchaseQuantity(1)
                .wholesaleTiersJson("[]").inputSchemaJson("[]").build());
    }

    private ServiceOrderCreateRequest orderRequest(OtherService service, User user) {
        ServiceOrderCreateRequest request = new ServiceOrderCreateRequest();
        request.setServiceId(service.getId());
        request.setQuantity(1);
        request.setContactEmail(user.getEmail());
        request.setBillingName("MaPay Buyer");
        request.setBillingAddressLine1("1 Main Street");
        request.setBillingDistrict("District");
        request.setBillingCity("City");
        request.setBillingProvince("Province");
        request.setBillingPostalCode("100000");
        request.setBillingCountry("China");
        request.setPaymentMethod("alipay");
        return request;
    }
}
