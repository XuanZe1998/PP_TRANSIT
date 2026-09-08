package com.transit.service;

import com.transit.dto.ServiceOrderCreateRequest;
import com.transit.dto.ServiceOrderResponse;
import com.transit.model.OtherService;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "payment.local-test-mode=false",
        "anyipay.allow-money-mutations=false"
})
@Transactional
class AutomaticCheckoutWithoutRefundCapabilityIntegrationTests {

    @Autowired private OtherServiceCatalogService catalogService;
    @Autowired private ServiceCommerceService commerceService;
    @Autowired private ServiceOrderService orderService;
    @Autowired private PaymentIntentService paymentIntentService;

    @Test
    void automaticProductCanCheckoutWhileRefundCapabilityIsDisabled() {
        assertThat(paymentIntentService.refundsEnabled()).isFalse();
        OtherService service = catalogService.create(OtherService.builder()
                .name("Automatic checkout without refunds")
                .description("test")
                .enabled(true)
                .purchaseEnabled(true)
                .priceCents(1_000L)
                .serviceFeeCents(100L)
                .currency("CNY")
                .fulfillmentMode(ServiceCommerceService.AUTOMATIC)
                .maxPurchaseQuantity(1)
                .wholesaleTiersJson("[]")
                .inputSchemaJson("[]")
                .build());
        commerceService.importInventory(service.getId(), "CHECKOUT-TEST-INVENTORY");

        User user = new User();
        user.setId(99_101L);
        user.setEmail("checkout-without-refunds@example.com");
        user.setEmailVerifiedAt(LocalDateTime.now());

        ServiceOrderCreateRequest request = new ServiceOrderCreateRequest();
        request.setServiceId(service.getId());
        request.setQuantity(1);
        request.setContactEmail(user.getEmail());
        request.setBillingName("Buyer");
        request.setBillingAddressLine1("305 Main St");
        request.setBillingDistrict("Carlton");
        request.setBillingCity("Carlton");
        request.setBillingProvince("Oregon");
        request.setBillingPostalCode("97111");
        request.setBillingCountry("United States");
        request.setPaymentMethod("alipay");

        ServiceOrderResponse response = orderService.createOrder(user, request);

        assertThat(response.getOrder().getFulfillmentMode()).isEqualTo(ServiceCommerceService.AUTOMATIC);
        assertThat(response.getOrder().getStatus()).isEqualTo("PENDING");
        assertThat(response.getPaymentIntent()).isNotNull();
    }
}
