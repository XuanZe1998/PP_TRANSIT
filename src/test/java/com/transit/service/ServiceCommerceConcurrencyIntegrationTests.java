package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.ServiceOrderCreateRequest;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.mapper.ServiceInventoryItemMapper;
import com.transit.mapper.PaymentRefundJobMapper;
import com.transit.model.OtherService;
import com.transit.model.ServiceOrder;
import com.transit.model.ServiceInventoryItem;
import com.transit.model.ServiceCoupon;
import com.transit.model.PaymentRefundJob;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "payment.local-test-mode=true")
class ServiceCommerceConcurrencyIntegrationTests {
    @Autowired OtherServiceCatalogService catalogService;
    @Autowired ServiceCommerceService commerceService;
    @Autowired ServiceOrderService orderService;
    @Autowired ServiceInventoryItemMapper inventoryMapper;
    @Autowired ServiceOrderMapper orderMapper;
    @Autowired ServiceCouponAdminService couponAdminService;
    @Autowired PaymentRefundJobMapper refundJobMapper;

    @Test
    void unpaidConcurrentOrdersNeverReserveInventory() throws Exception {
        OtherService service = automaticService("Last stock " + System.nanoTime());
        commerceService.importInventory(service.getId(), "ONLY-ONE");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> createAfter(start, service, 71_001L));
            Future<Boolean> second = executor.submit(() -> createAfter(start, service, 71_002L));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactly(true, true);
            assertThat(inventoryMapper.selectCount(new LambdaQueryWrapper<ServiceInventoryItem>()
                    .eq(ServiceInventoryItem::getServiceId, service.getId())
                    .eq(ServiceInventoryItem::getStatus, ServiceCommerceService.RESERVED))).isZero();
            assertThat(inventoryMapper.selectCount(new LambdaQueryWrapper<ServiceInventoryItem>()
                    .eq(ServiceInventoryItem::getServiceId, service.getId())
                    .eq(ServiceInventoryItem::getStatus, ServiceCommerceService.AVAILABLE))).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLocalPaymentAttemptsDeliverOnlyOnce() throws Exception {
        OtherService service = automaticService("Idempotent payment " + System.nanoTime());
        commerceService.importInventory(service.getId(), "DELIVER-ONCE");
        User user = user(72_001L);
        ServiceOrder order = orderService.createOrder(user, request(service, user.getEmail())).getOrder();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> payAfter(start, user, order.getId()));
            Future<?> second = executor.submit(() -> payAfter(start, user, order.getId()));
            start.countDown(); first.get(); second.get();
        } finally {
            executor.shutdownNow();
        }
        ServiceOrder stored = orderMapper.selectById(order.getId());
        assertThat(stored.getStatus()).isEqualTo("FULFILLED");
        assertThat(inventoryMapper.selectCount(new LambdaQueryWrapper<ServiceInventoryItem>()
                .eq(ServiceInventoryItem::getServiceId, service.getId())
                .eq(ServiceInventoryItem::getStatus, ServiceCommerceService.DELIVERED))).isEqualTo(1);
    }

    @Test
    void paidOversellDeliversOneOrderAndQueuesOneIdempotentRefund() {
        OtherService service = automaticService("Paid oversell " + System.nanoTime());
        commerceService.importInventory(service.getId(), "ONLY-PAID-WINNER");
        User firstUser = user(72_101L), secondUser = user(72_102L);
        ServiceOrder first = orderService.createOrder(firstUser, request(service, firstUser.getEmail())).getOrder();
        ServiceOrder second = orderService.createOrder(secondUser, request(service, secondUser.getEmail())).getOrder();

        orderService.startPayment(firstUser, first.getId(), "127.0.0.1");
        orderService.startPayment(secondUser, second.getId(), "127.0.0.1");

        assertThat(orderMapper.selectById(first.getId()).getStatus()).isEqualTo("FULFILLED");
        assertThat(orderMapper.selectById(second.getId()).getStatus()).isEqualTo("REFUND_PENDING");
        assertThat(inventoryMapper.selectCount(new LambdaQueryWrapper<ServiceInventoryItem>()
                .eq(ServiceInventoryItem::getServiceId, service.getId())
                .eq(ServiceInventoryItem::getStatus, ServiceCommerceService.DELIVERED))).isEqualTo(1);
        assertThat(refundJobMapper.selectCount(new LambdaQueryWrapper<PaymentRefundJob>()
                .eq(PaymentRefundJob::getServiceOrderId, second.getId()))).isEqualTo(1);
    }

    @Test
    void concurrentBuyersCannotUseTheLastCouponOpportunityTwice() throws Exception {
        OtherService service = catalogService.create(OtherService.builder().name("Last coupon " + System.nanoTime())
                .description("test").enabled(true).purchaseEnabled(true).priceCents(100L).serviceFeeCents(1L)
                .currency("CNY").fulfillmentMode(ServiceCommerceService.MANUAL).maxPurchaseQuantity(1)
                .wholesaleTiersJson("[]").inputSchemaJson("[]").build());
        ServiceCoupon coupon = new ServiceCoupon(); coupon.setCode("ONE" + System.nanoTime()); coupon.setDiscountCents(10L);
        coupon.setRemainingUses(1); coupon.setEnabled(true); coupon.setServiceIds(List.of(service.getId())); coupon = couponAdminService.save(null, coupon);
        String couponCode = coupon.getCode();
        Long couponId = coupon.getId();
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> createWithCouponAfter(start, service, couponCode, 73_001L));
            Future<Boolean> second = executor.submit(() -> createWithCouponAfter(start, service, couponCode, 73_002L));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(couponAdminService.list().stream().filter(item -> item.getId().equals(couponId)).findFirst().orElseThrow().getRemainingUses()).isZero();
    }

    private boolean createAfter(CountDownLatch start, OtherService service, long userId) {
        try { start.await(); User user = user(userId); orderService.createOrder(user, request(service, user.getEmail())); return true; }
        catch (Exception expectedConflict) { return false; }
    }

    private boolean createWithCouponAfter(CountDownLatch start, OtherService service, String code, long userId) {
        try { start.await(); User user = user(userId); ServiceOrderCreateRequest request = request(service, user.getEmail()); request.setCouponCode(code); orderService.createOrder(user, request); return true; }
        catch (Exception expectedConflict) { return false; }
    }

    private void payAfter(CountDownLatch start, User user, Long orderId) {
        try { start.await(); orderService.startPayment(user, orderId, "127.0.0.1"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
    }

    private OtherService automaticService(String name) {
        return catalogService.create(OtherService.builder().name(name).description("test").enabled(true).purchaseEnabled(true)
                .priceCents(100L).serviceFeeCents(1L).currency("CNY").fulfillmentMode(ServiceCommerceService.AUTOMATIC)
                .maxPurchaseQuantity(1).wholesaleTiersJson("[]").inputSchemaJson("[]").build());
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("buyer" + id + "@example.com");
        user.setEmailVerifiedAt(java.time.LocalDateTime.now());
        return user;
    }

    private ServiceOrderCreateRequest request(OtherService service, String email) {
        ServiceOrderCreateRequest request = new ServiceOrderCreateRequest(); request.setServiceId(service.getId()); request.setQuantity(1);
        request.setContactEmail(email); request.setBillingName("Buyer"); request.setBillingAddressLine1("305 Main St"); request.setBillingDistrict("Carlton");
        request.setBillingCity("Carlton"); request.setBillingProvince("Oregon"); request.setBillingPostalCode("97111"); request.setBillingCountry("United States"); request.setPaymentMethod("alipay"); return request;
    }
}
