package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.transit.dto.PlusOrderRequest;
import com.transit.mapper.PlusOrderMapper;
import com.transit.mapper.PlusProductMapper;
import com.transit.model.PlusOrder;
import com.transit.model.PlusProduct;
import com.transit.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlusOrderServiceCommercialSafetyTests {

    @Mock private PlusProductMapper productMapper;
    @Mock private PlusOrderMapper orderMapper;
    @Mock private AnyiPayClient anyiPayClient;

    private PlusOrderService service;

    @BeforeEach
    void setUp() {
        service = new PlusOrderService(productMapper, orderMapper);
    }

    @Test
    void publicCatalogFiltersForEnabledProducts() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), PlusProduct.class);
        when(productMapper.selectList(any())).thenReturn(List.of());

        service.listEnabledProducts();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PlusProduct>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(productMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment().toLowerCase()).contains("enabled");
    }

    @Test
    void productPricesCannotBeNegativeAndNewProductsDefaultToEnabledUsd() {
        PlusProduct invalid = PlusProduct.builder().name("Plus").priceCents(-1L).build();
        assertStatus(400, () -> service.createProduct(invalid));
        verify(productMapper, never()).insert(any(PlusProduct.class));

        PlusProduct request = PlusProduct.builder()
                .name(" Plus subscription ")
                .priceCents(2_000L)
                .serviceFeeCents(300L)
                .build();
        PlusProduct saved = service.createProduct(request);

        assertThat(saved.getName()).isEqualTo("Plus subscription");
        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getCurrency()).isEqualTo("USD");
        verify(productMapper).insert(saved);
    }

    @Test
    void disabledProductCannotBeOrdered() {
        PlusProduct disabled = product(true);
        disabled.setEnabled(false);
        when(productMapper.selectById(7L)).thenReturn(disabled);

        assertStatus(404, () -> service.createOrder(user(), orderRequest()));

        verify(orderMapper, never()).insert(any(PlusOrder.class));
    }

    @Test
    void orderPersistsContactAndImmutablePriceSnapshot() {
        when(productMapper.selectById(7L)).thenReturn(product(true));
        ArgumentCaptor<PlusOrder> captor = ArgumentCaptor.forClass(PlusOrder.class);

        PlusOrderRequest request = orderRequest();
        request.setContactEmail(" Buyer@Example.com ");
        request.setContactNote(" deliver to the verified account ");
        service.createOrder(user(), request);

        verify(orderMapper).insert(captor.capture());
        PlusOrder order = captor.getValue();
        assertThat(order.getContactEmail()).isEqualTo("buyer@example.com");
        assertThat(order.getContactNote()).isEqualTo("deliver to the verified account");
        assertThat(order.getUnitPriceCents()).isEqualTo(2_000L);
        assertThat(order.getServiceFeeCents()).isEqualTo(300L);
        assertThat(order.getAmountCents()).isEqualTo(2_300L);
        assertThat(order.getCurrency()).isEqualTo("USD");
        assertThat(order.getPaymentAmountCents()).isEqualTo(15_564L);
        assertThat(order.getPaymentCurrency()).isEqualTo("CNY");
        assertThat(order.getExchangeRate()).isEqualByComparingTo("6.76693506");
        assertThat(order.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void orderStateMachineRequiresPaymentThenFulfillmentEvidence() {
        PlusOrder order = order("PENDING");
        when(orderMapper.selectById(11L)).thenReturn(order);
        when(orderMapper.update(any(PlusOrder.class), any())).thenReturn(1);

        assertStatus(409, () -> service.fulfillOrder(11L, "FULFILLED", "supplier-order-1"));
        assertStatus(400, () -> service.fulfillOrder(11L, "PAID", null));
        verify(orderMapper, never()).update(any(PlusOrder.class), any());

        service.fulfillOrder(11L, "PAID", "payment-provider-txn-1");
        assertThat(order.getStatus()).isEqualTo("PAID");
        assertThat(order.getPaymentReference()).isEqualTo("payment-provider-txn-1");
        assertThat(order.getPaidAt()).isNotNull();

        service.fulfillOrder(11L, "FULFILLED", "supplier-order-1");
        assertThat(order.getStatus()).isEqualTo("FULFILLED");
        assertThat(order.getFulfillmentReference()).isEqualTo("supplier-order-1");
        assertThat(order.getFulfilledAt()).isNotNull();
        verify(orderMapper, org.mockito.Mockito.times(2)).update(org.mockito.ArgumentMatchers.eq(order), any());
    }

    @Test
    void pendingFailedAndCancelledOrdersCannotDownloadPaidReceipt() {
        for (String status : List.of("PENDING", "FAILED", "CANCELLED")) {
            PlusOrder order = order(status);
            when(orderMapper.selectById(11L)).thenReturn(order);
            assertStatus(409, () -> service.buildDownload(user(), 11L));
        }
        verify(orderMapper, never()).updateById(any(PlusOrder.class));
    }

    @Test
    void paidReceiptNeedsEvidenceAndConfiguredMerchantThenUsesStoredFacts() {
        PlusOrder order = order("PAID");
        when(orderMapper.selectById(11L)).thenReturn(order);
        assertStatus(409, () -> service.buildDownload(user(), 11L));

        order.setPaymentReference("pay_123");
        order.setPaidAt(LocalDateTime.of(2026, 7, 12, 1, 2));
        assertStatus(503, () -> service.buildDownload(user(), 11L));

        configureMerchant();
        byte[] pdf = service.buildDownload(user(), 11L);
        String rawPdf = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(rawPdf).startsWith("%PDF-1.4");
        assertThat(rawPdf).contains("PLUS-ORDER-11", "pay_123", "USD 23.00", "Merchant Legal Ltd");
        assertThat(order.getDownloadedAt()).isNotNull();
        verify(orderMapper).updateById(order);
    }

    @Test
    void paidOrdersCannotBePhysicallyDeleted() {
        PlusOrder order = order("PAID");
        order.setPaymentReference("pay_123");
        order.setPaidAt(LocalDateTime.now());
        when(orderMapper.selectById(11L)).thenReturn(order);

        assertStatus(409, () -> service.deleteOrder(11L));

        verify(orderMapper, never()).deleteById(11L);
    }

    @Test
    void verifiedGatewayNotificationChecksAmountAndMarksOrderPaid() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "order-test"), PlusOrder.class);
        service = new PlusOrderService(productMapper, orderMapper, anyiPayClient);
        PlusOrder order = order("PENDING");
        order.setPaymentAmountCents(15_564L);
        order.setPaymentCurrency("CNY");
        order.setExchangeRate(new java.math.BigDecimal("6.76693506"));
        when(anyiPayClient.merchantId()).thenReturn("1001");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.update(any(PlusOrder.class), any())).thenReturn(1);

        service.receivePaymentNotification(Map.of(
                "pid", "1001",
                "out_trade_no", order.getOrderNo(),
                "trade_no", "provider-123",
                "type", "alipay",
                "money", "155.64",
                "param", "plus-order:11",
                "trade_status", "TRADE_SUCCESS"));

        assertThat(order.getStatus()).isEqualTo("PAID");
        assertThat(order.getPaymentProvider()).isEqualTo("ANYIPAY");
        assertThat(order.getPaymentReference()).isEqualTo("provider-123");
        assertThat(order.getPaidAt()).isNotNull();
    }

    @Test
    void gatewayNotificationCannotChangeOrderWhenAmountDiffers() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "order-test-2"), PlusOrder.class);
        service = new PlusOrderService(productMapper, orderMapper, anyiPayClient);
        PlusOrder order = order("PENDING");
        order.setCurrency("CNY");
        when(anyiPayClient.merchantId()).thenReturn("1001");
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertStatus(400, () -> service.receivePaymentNotification(Map.of(
                "pid", "1001",
                "out_trade_no", order.getOrderNo(),
                "trade_no", "provider-123",
                "money", "22.99",
                "param", "plus-order:11",
                "trade_status", "TRADE_SUCCESS")));

        verify(orderMapper, never()).update(any(PlusOrder.class), any());
    }

    @Test
    void gatewayNotificationFromAnotherOrderNamespaceIsRejected() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "order-test-3"), PlusOrder.class);
        service = new PlusOrderService(productMapper, orderMapper, anyiPayClient);
        PlusOrder order = order("PENDING");
        order.setCurrency("CNY");
        when(anyiPayClient.merchantId()).thenReturn("1001");
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertStatus(400, () -> service.receivePaymentNotification(Map.of(
                "pid", "1001",
                "out_trade_no", order.getOrderNo(),
                "trade_no", "provider-123",
                "money", "23.00",
                "param", "dujiao-order:11",
                "trade_status", "TRADE_SUCCESS")));

        verify(orderMapper, never()).update(any(PlusOrder.class), any());
    }

    @Test
    void localTestPaymentMarksPendingOrderPaidWithoutCallingGateway() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "local-payment-test"), PlusOrder.class);
        service = new PlusOrderService(productMapper, orderMapper, anyiPayClient);
        ReflectionTestUtils.setField(service, "localTestPaymentMode", true);
        PlusOrder order = order("PENDING");
        when(orderMapper.selectById(11L)).thenReturn(order);
        when(orderMapper.update(any(PlusOrder.class), any())).thenReturn(1);

        var response = service.startPayment(user(), 11L, "127.0.0.1");

        assertThat(response.getOrder().getStatus()).isEqualTo("PAID");
        assertThat(response.getOrder().getPaymentProvider()).isEqualTo("LOCAL_TEST");
        assertThat(response.getOrder().getPaymentReference()).isEqualTo("LOCAL-PLUS-ORDER-11");
        assertThat(response.getOrder().getPaidAt()).isNotNull();
        assertThat(response.getPaymentUrl()).isNull();
        verify(anyiPayClient, never()).createPagePaymentUrl(any(), any(), any(), any());
    }

    @Test
    void pendingUsdOrderUsesLockedCnyQuoteWhenUserClicksPay() {
        service = new PlusOrderService(productMapper, orderMapper, anyiPayClient);
        PlusOrder order = order("PENDING");
        when(orderMapper.selectById(11L)).thenReturn(order);
        when(anyiPayClient.isEnabled()).thenReturn(true);
        when(anyiPayClient.createPagePaymentUrl(
                "PLUS-ORDER-11", "ChatGPT Plus", "155.64", "plus-order:11"))
                .thenReturn("https://a.tjrl3.cn/api/pay/submit?pid=1001&sign=test");

        var response = service.startPayment(user(), 11L, "127.0.0.1");

        assertThat(response.getOrder().getStatus()).isEqualTo("PENDING");
        assertThat(response.getOrder().getPaymentProvider()).isEqualTo("ANYIPAY");
        assertThat(response.getOrder().getPaymentAmountCents()).isEqualTo(15_564L);
        assertThat(response.getOrder().getPaymentCurrency()).isEqualTo("CNY");
        assertThat(response.getOrder().getExchangeRate()).isEqualByComparingTo("6.76693506");
        assertThat(response.getProviderTradeNo()).isNull();
        assertThat(response.getPayType()).isEqualTo("page");
        assertThat(response.getPaymentUrl()).isEqualTo("https://a.tjrl3.cn/api/pay/submit?pid=1001&sign=test");
        verify(orderMapper).updateById(order);
    }

    private void configureMerchant() {
        ReflectionTestUtils.setField(service, "merchantDisplayName", "Merchant");
        ReflectionTestUtils.setField(service, "merchantLegalName", "Merchant Legal Ltd");
        ReflectionTestUtils.setField(service, "merchantAddressLine1", "1 Commercial Street");
        ReflectionTestUtils.setField(service, "merchantAddressLine2", "Tokyo");
        ReflectionTestUtils.setField(service, "merchantContactEmail", "billing@example.com");
        ReflectionTestUtils.setField(service, "merchantRegistrationId", "REG-1");
        ReflectionTestUtils.setField(service, "receiptTimeZone", "Asia/Tokyo");
    }

    private PlusProduct product(boolean enabled) {
        return PlusProduct.builder()
                .id(7L)
                .name("ChatGPT Plus")
                .priceCents(2_000L)
                .serviceFeeCents(300L)
                .currency("USD")
                .enabled(enabled)
                .build();
    }

    private PlusOrderRequest orderRequest() {
        PlusOrderRequest request = new PlusOrderRequest();
        request.setProductId(7L);
        return request;
    }

    private User user() {
        return User.builder().id(99L).username("buyer").email("buyer@example.com").build();
    }

    private PlusOrder order(String status) {
        return PlusOrder.builder()
                .id(11L)
                .orderNo("PLUS-ORDER-11")
                .userId(99L)
                .productId(7L)
                .productName("ChatGPT Plus")
                .unitPriceCents(2_000L)
                .serviceFeeCents(300L)
                .amountCents(2_300L)
                .currency("USD")
                .status(status)
                .contactEmail("buyer@example.com")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void assertStatus(int expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(expected));
    }
}
