package com.transit.service;

import com.transit.dto.ServiceOrderCreateRequest;
import com.transit.dto.ServiceOrderResponse;
import com.transit.dto.ServiceOrderQuoteResponse;
import com.transit.mapper.ServiceInventoryItemMapper;
import com.transit.model.OtherService;
import com.transit.model.ServiceOrder;
import com.transit.model.ServiceCoupon;
import com.transit.model.ServiceInventoryItem;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "payment.local-test-mode=true")
@Transactional
class ServiceCommerceServiceIntegrationTests {

    @Autowired private OtherServiceCatalogService catalogService;
    @Autowired private ServiceCommerceService commerceService;
    @Autowired private ServiceCouponAdminService couponAdminService;
    @Autowired private ServiceOrderService orderService;
    @Autowired private ServiceInventoryItemMapper inventoryMapper;
    @Autowired private com.transit.mapper.ServiceOrderMapper orderMapper;

    @Test
    void automaticProductSnapshotsTierCouponAndDeliversEncryptedInventoryExactlyOnce() {
        OtherService request = OtherService.builder()
                .name("Automatic integration product")
                .description("test")
                .enabled(true)
                .purchaseEnabled(true)
                .priceCents(1_000L)
                .serviceFeeCents(100L)
                .currency("CNY")
                .fulfillmentMode(ServiceCommerceService.AUTOMATIC)
                .maxPurchaseQuantity(5)
                .wholesaleTiersJson("[{\"minQuantity\":2,\"unitPriceCents\":800}]")
                .inputSchemaJson("[{\"key\":\"account\",\"label\":\"Account\",\"required\":true,\"maxLength\":30}]")
                .build();
        OtherService service = catalogService.create(request);
        assertThat(commerceService.importInventory(service.getId(), "CARD-ONE\nCARD-TWO")).isEqualTo(2);

        ServiceCoupon coupon = new ServiceCoupon();
        coupon.setCode("SAVE300");
        coupon.setDiscountCents(300L);
        coupon.setRemainingUses(1);
        coupon.setEnabled(true);
        coupon.setServiceIds(List.of(service.getId()));
        couponAdminService.save(null, coupon);

        ServiceOrderQuoteResponse quote = commerceService.quote(service.getId(), 2, "save300");
        assertThat(quote.getListUnitPriceCents()).isEqualTo(1_000L);
        assertThat(quote.getEffectiveUnitPriceCents()).isEqualTo(800L);
        assertThat(quote.getWholesaleDiscountCents()).isEqualTo(400L);
        assertThat(quote.getCouponDiscountCents()).isEqualTo(300L);
        assertThat(quote.getServiceFeeCents()).isEqualTo(100L);
        assertThat(quote.getAmountCents()).isEqualTo(1_400L);

        ServiceOrderCreateRequest orderRequest = new ServiceOrderCreateRequest();
        orderRequest.setServiceId(service.getId());
        orderRequest.setQuantity(2);
        orderRequest.setCouponCode("SAVE300");
        orderRequest.setCustomFields(java.util.Map.of("account", "buyer@example.com"));
        orderRequest.setContactEmail("buyer@example.com");
        orderRequest.setBillingName("Buyer");
        orderRequest.setBillingAddressLine1("305 Main St");
        orderRequest.setBillingDistrict("Carlton");
        orderRequest.setBillingCity("Carlton");
        orderRequest.setBillingProvince("Oregon");
        orderRequest.setBillingPostalCode("97111");
        orderRequest.setBillingCountry("United States");
        orderRequest.setPaymentMethod("alipay");
        User user = new User();
        user.setId(99_001L);
        user.setEmail("buyer@example.com");
        user.setEmailVerifiedAt(java.time.LocalDateTime.now());

        ServiceOrderResponse created = orderService.createOrder(user, orderRequest);
        ServiceOrder order = created.getOrder();
        assertThat(order.getAmountCents()).isEqualTo(1_400L);
        assertThat(order.getQuantity()).isEqualTo(2);
        assertThat(order.getFulfillmentStatus()).isEqualTo("PENDING");
        assertThat(commerceService.inventoryStats(service.getId()).get(ServiceCommerceService.AVAILABLE)).isEqualTo(2L);

        ServiceOrder paid = orderService.startPayment(user, order.getId(), "127.0.0.1").getOrder();
        assertThat(paid.getStatus()).isEqualTo("FULFILLED");
        assertThat(paid.getFulfillmentStatus()).isEqualTo("COMPLETED");
        assertThat(paid.getDeliveryItems()).containsExactly("CARD-ONE", "CARD-TWO");

        List<ServiceInventoryItem> delivered = inventoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ServiceInventoryItem>()
                        .eq(ServiceInventoryItem::getServiceId, service.getId()));
        assertThat(delivered).allMatch(item -> ServiceCommerceService.DELIVERED.equals(item.getStatus()));
        assertThat(delivered).allMatch(item -> !item.getContentEncrypted().contains("CARD-"));
        assertThat(orderService.listUserOrders(user).get(0).getDeliveryItems()).isNull();
        assertThat(orderService.getUserOrder(user, order.getId()).getDeliveryItems()).containsExactly("CARD-ONE", "CARD-TWO");
        User stranger = new User(); stranger.setId(99_999L);
        assertThatThrownBy(() -> orderService.getUserOrder(stranger, order.getId()))
                .hasMessageContaining("Order not found");
    }

    @Test
    void manualProductWaitsForAdministratorAndOnlyOwnerDetailRevealsDelivery() {
        OtherService service = catalogService.create(OtherService.builder()
                .name("Manual integration product").description("test").enabled(true).purchaseEnabled(true)
                .priceCents(500L).serviceFeeCents(50L).currency("CNY")
                .fulfillmentMode(ServiceCommerceService.MANUAL).maxPurchaseQuantity(3).manualStock(2)
                .wholesaleTiersJson("[]")
                .inputSchemaJson("[{\"key\":\"uid\",\"label\":\"User ID\",\"required\":true,\"maxLength\":20}]")
                .build());
        User user = new User(); user.setId(99_002L); user.setEmail("manual@example.com");
        user.setEmailVerifiedAt(java.time.LocalDateTime.now());
        ServiceOrderCreateRequest request = baseOrderRequest(service, "manual@example.com");
        request.setQuantity(2);
        request.setCustomFields(java.util.Map.of("uid", "user-42"));

        ServiceOrder order = orderService.createOrder(user, request).getOrder();
        ServiceOrder paid = orderService.startPayment(user, order.getId(), "127.0.0.1").getOrder();
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getFulfillmentStatus()).isEqualTo("PENDING");
        assertThat(orderService.listUserOrders(user).get(0).getDeliveryItems()).isNull();

        orderService.completeManualOrder(order.getId(), "MANUAL-SECRET", "done");
        ServiceOrder detail = orderService.getUserOrder(user, order.getId());
        assertThat(detail.getStatus()).isEqualTo("FULFILLED");
        assertThat(detail.getCustomFields()).containsEntry("uid", "user-42");
        assertThat(detail.getDeliveryItems()).containsExactly("MANUAL-SECRET");
    }

    @Test
    void expiredUnpaidOrderLeavesInventoryUntouched() {
        OtherService service = catalogService.create(OtherService.builder()
                .name("Expiry integration product").description("test").enabled(true).purchaseEnabled(true)
                .priceCents(100L).serviceFeeCents(1L).currency("CNY")
                .fulfillmentMode(ServiceCommerceService.AUTOMATIC).maxPurchaseQuantity(1)
                .wholesaleTiersJson("[]").inputSchemaJson("[]").build());
        commerceService.importInventory(service.getId(), "EXPIRING-CARD");
        User user = new User(); user.setId(99_003L); user.setEmail("expiry@example.com");
        user.setEmailVerifiedAt(java.time.LocalDateTime.now());
        ServiceOrder order = orderService.createOrder(user, baseOrderRequest(service, user.getEmail())).getOrder();
        order.setReservationExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        orderMapper.updateById(order);

        commerceService.expirePendingOrders();

        assertThat(orderMapper.selectById(order.getId()).getStatus()).isEqualTo("EXPIRED");
        assertThat(commerceService.inventoryStats(service.getId()).get(ServiceCommerceService.AVAILABLE)).isEqualTo(1L);
    }

    private ServiceOrderCreateRequest baseOrderRequest(OtherService service, String email) {
        ServiceOrderCreateRequest request = new ServiceOrderCreateRequest(); request.setServiceId(service.getId()); request.setQuantity(1);
        request.setContactEmail(email); request.setBillingName("Buyer"); request.setBillingAddressLine1("305 Main St");
        request.setBillingDistrict("Carlton"); request.setBillingCity("Carlton"); request.setBillingProvince("Oregon");
        request.setBillingPostalCode("97111"); request.setBillingCountry("United States"); request.setPaymentMethod("wxpay");
        return request;
    }
}
