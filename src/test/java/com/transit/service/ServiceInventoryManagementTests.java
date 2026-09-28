package com.transit.service;

import com.transit.dto.PageResponse;
import com.transit.mapper.OtherServiceMapper;
import com.transit.mapper.ServiceInventoryItemMapper;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.model.OtherService;
import com.transit.model.ServiceInventoryItem;
import com.transit.model.ServiceOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "payment.local-test-mode=true")
@Transactional
class ServiceInventoryManagementTests {
    @Autowired ServiceCommerceService commerce;
    @Autowired OtherServiceMapper services;
    @Autowired ServiceInventoryItemMapper inventory;
    @Autowired ServiceOrderMapper orders;
    @Autowired ServiceOrderService orderService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void paginatesWithoutTruncationAndUsesIdenticalScopedFiltersAndOrderSummaries() throws Exception {
        OtherService service = localService();
        commerce.importInventory(service.getId(), String.join("\n", IntStream.range(0, 205).mapToObj(i -> "SECRET-" + String.format("%04d", i)).toList()));
        OtherService other = localService(); commerce.importInventory(other.getId(), "OTHER-0204");
        PageResponse<ServiceInventoryItem> first = commerce.listInventoryPage(service.getId(), null, 1, 10);
        PageResponse<ServiceInventoryItem> last = commerce.listInventoryPage(service.getId(), null, 21, 10);
        assertThat(first.getTotal()).isEqualTo(205); assertThat(first.getItems()).hasSize(10); assertThat(last.getItems()).hasSize(5);
        assertThat(first.getItems()).isSortedAccordingTo((a,b) -> Long.compare(b.getId(), a.getId()));
        Long id = first.getItems().get(0).getId();
        ServiceOrder order = order(service, "SALE-LOOKUP", "FULFILLED");
        jdbc.update("UPDATE service_inventory_items SET status='DELIVERED',reserved_order_id=?,delivered_at=? WHERE id=?", order.getId(), LocalDateTime.now(), id);
        PageResponse<ServiceInventoryItem> sold = commerce.listInventoryPage(service.getId(), "delivered", 1, 10, "SALE-LOOKUP");
        assertThat(sold.getTotal()).isEqualTo(1); assertThat(sold.getItems()).hasSize(1);
        ServiceInventoryItem item = sold.getItems().get(0);
        assertThat(item.getOrderNo()).isEqualTo(order.getOrderNo()); assertThat(item.getBuyerUserId()).isEqualTo(777L);
        assertThat(item.getDeliveredAt()).isNotNull(); assertThat(item.getOrderStatus()).isEqualTo("FULFILLED");
        assertThat(commerce.listInventoryPage(service.getId(), "AVAILABLE", 1, 10, "SALE-LOOKUP").getTotal()).isZero();
        assertThat(commerce.listInventoryPage(other.getId(), null, 1, 10, "SALE-LOOKUP").getTotal()).isZero();
        assertThat(commerce.listInventoryPage(service.getId(), null, 1, 10, id.toString()).getTotal()).isEqualTo(1);
        assertThat(commerce.listInventoryPage(service.getId(), null, 1, 10, "****0204").getTotal()).isEqualTo(1);
        assertThat(commerce.listInventoryPage(service.getId(), null, 1, 10, "%").getTotal()).isZero();
        assertThat(json.writeValueAsString(first)).doesNotContain("SECRET-", "contentEncrypted", "contentFingerprint");
        assertThat(item.getContentEncrypted()).isNull();
        assertThat(commerce.inventoryStats(service.getId())).containsEntry("AVAILABLE", 204L).containsEntry("DELIVERED", 1L);
        jdbc.update("DELETE FROM service_orders WHERE id=?", order.getId());
        ServiceInventoryItem orphan = commerce.listInventoryPage(service.getId(), "DELIVERED", 1, 10).getItems().get(0);
        assertThat(orphan.getReservedOrderId()).isEqualTo(order.getId()); assertThat(orphan.getOrderNo()).isNull();
    }

    @Test
    void reportsDuplicatesAndRejectsAnInvalidBatchBeforeAnyWrites() {
        OtherService service = localService(); commerce.importInventory(service.getId(), "EXISTING");
        assertThat(commerce.importInventoryWithReport(service.getId(), "EXISTING，NEW NEW"))
                .containsEntry("received", 3).containsEntry("unique", 2).containsEntry("imported", 1)
                .containsEntry("duplicateInInput", 1).containsEntry("duplicateInStock", 1);
        assertThatThrownBy(() -> commerce.importInventory(service.getId(), "SHOULD-NOT-EXIST " + "x".repeat(10001))).isInstanceOf(ResponseStatusException.class);
        assertThat(commerce.inventoryStats(service.getId()).get("AVAILABLE")).isEqualTo(2);
        assertThatThrownBy(() -> commerce.importInventory(service.getId(), "same ".repeat(10001))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.importInventory(service.getId(), " , ")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void batchDeleteRejectsMissingCrossServiceReservedAndDeliveredItemsAtomically() {
        OtherService service = localService(), other = localService();
        commerce.importInventory(service.getId(), "ONE TWO THREE"); commerce.importInventory(other.getId(), "OTHER");
        List<ServiceInventoryItem> rows = commerce.listInventoryPage(service.getId(), null, 1, 10).getItems();
        Long available = rows.get(0).getId(), reserved = rows.get(1).getId(), delivered = rows.get(2).getId();
        Long foreign = commerce.listInventoryPage(other.getId(), null, 1, 10).getItems().get(0).getId();
        jdbc.update("UPDATE service_inventory_items SET status='RESERVED' WHERE id=?", reserved);
        jdbc.update("UPDATE service_inventory_items SET status='DELIVERED' WHERE id=?", delivered);
        for (Long invalid : List.of(reserved, delivered, foreign, Long.MAX_VALUE)) {
            assertThatThrownBy(() -> commerce.deleteAvailableInventoryBatch(service.getId(), List.of(available, invalid))).isInstanceOf(ResponseStatusException.class);
            assertThat(inventory.selectById(available)).isNotNull();
        }
        assertThatThrownBy(() -> commerce.deleteAvailableInventory(service.getId(), reserved)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.replaceAvailableInventory(service.getId(), delivered, "new")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.deleteAvailableInventoryBatch(service.getId(), List.of())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.deleteAvailableInventoryBatch(service.getId(), java.util.Collections.nCopies(101, available))).isInstanceOf(ResponseStatusException.class);
        assertThat(commerce.deleteAvailableInventoryBatch(service.getId(), List.of(available))).isEqualTo(1);
        assertThat(inventory.selectById(reserved)).isNotNull(); assertThat(inventory.selectById(delivered)).isNotNull();
    }

    @Test
    void revealsAllThreeStatesButNeverAnItemFromAnotherServiceAndRejectsUpstreamManagement() {
        OtherService service = localService(), other = localService();
        commerce.importInventory(service.getId(), "FULL-SECRET-CONTENT");
        Long id = commerce.listInventoryPage(service.getId(), null, 1, 10).getItems().get(0).getId();
        for (String status : List.of("AVAILABLE", "RESERVED", "DELIVERED")) {
            jdbc.update("UPDATE service_inventory_items SET status=? WHERE id=?", status, id);
            assertThat(commerce.revealInventory(service.getId(), id)).isEqualTo("FULL-SECRET-CONTENT");
        }
        assertThatThrownBy(() -> commerce.revealInventory(other.getId(), id)).isInstanceOf(ResponseStatusException.class);
        service.setSupplierType("DUJIAO_NEXT"); services.updateById(service);
        assertThatThrownBy(() -> commerce.importInventory(service.getId(), "upstream")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.deleteAvailableInventoryBatch(service.getId(), List.of(id))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.deleteAvailableInventory(service.getId(), id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.replaceAvailableInventory(service.getId(), id, "upstream")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.revealInventory(service.getId(), id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.listInventoryPage(service.getId(), null, 1, 10)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsUnknownServicesStatusesAndInvalidPagesAndUsesScopedOrderPagination() {
        OtherService service = localService(), other = localService();
        assertThatThrownBy(() -> commerce.listInventoryPage(Long.MAX_VALUE, null, 1, 10)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.listInventoryPage(service.getId(), "INVALID", 1, 10)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.listInventoryPage(service.getId(), null, 0, 10)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.listInventoryPage(service.getId(), null, 1, 200)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.listInventoryPage(service.getId(), null, 1, 10, "a".repeat(161))).isInstanceOf(ResponseStatusException.class);
        ServiceOrder found = order(service, "SERVICE-SCOPED", "PENDING"); order(other, "OTHER-SERVICE", "PENDING");
        PageResponse<ServiceOrder> page = orderService.listAllOrdersPage(1, 10, null, null, service.getId());
        assertThat(page.getTotal()).isEqualTo(1); assertThat(page.getItems()).extracting(ServiceOrder::getId).containsExactly(found.getId());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentReservationPreventsBatchDeleteAndReplacementWithoutPartialChanges() throws Exception {
        OtherService service = localService();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            commerce.importInventory(service.getId(), "CONCURRENT-ONE CONCURRENT-TWO");
            List<ServiceInventoryItem> rows = commerce.listInventoryPage(service.getId(), null, 1, 10).getItems();
            Long claimed = rows.get(0).getId(), unclaimed = rows.get(1).getId();
            var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            var reservation = executor.submit(() -> transaction.executeWithoutResult(tx -> {
                jdbc.update("UPDATE service_inventory_items SET status='RESERVED' WHERE id=? AND status='AVAILABLE'", claimed);
                locked.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            var deletion = executor.submit(() -> {
                started.countDown();
                return catchThrowable(() -> commerce.deleteAvailableInventoryBatch(service.getId(), List.of(claimed, unclaimed)));
            });
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown(); reservation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(deletion.get(5, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(ResponseStatusException.class);
            assertThat(inventory.selectById(unclaimed)).isNotNull();
            assertThat(inventory.selectById(claimed).getStatus()).isEqualTo("RESERVED");
            assertThatThrownBy(() -> commerce.replaceAvailableInventory(service.getId(), claimed, "REPLACED")).isInstanceOf(ResponseStatusException.class);
            jdbc.update("UPDATE service_inventory_items SET status='DELIVERED' WHERE id=?", claimed);
            assertThatThrownBy(() -> commerce.deleteAvailableInventory(service.getId(), claimed)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> commerce.replaceAvailableInventory(service.getId(), claimed, "REPLACED")).isInstanceOf(ResponseStatusException.class);
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            jdbc.update("DELETE FROM service_inventory_items WHERE service_id=?", service.getId());
            jdbc.update("DELETE FROM other_services WHERE id=?", service.getId());
        }
    }

    @Test
    void releasedInventoryIsReusableAndDeliveredCardsRemainQueryableByTheirBuyer() {
        OtherService service = localService();
        commerce.importInventory(service.getId(), "BUYER-DELIVERY-SECRET");
        Long id = commerce.listInventoryPage(service.getId(), null, 1, 10).getItems().get(0).getId();
        ServiceOrder pending = order(service, "EXPIRED-RESERVATION", "PENDING");
        pending.setFulfillmentMode(ServiceCommerceService.AUTOMATIC);
        jdbc.update("UPDATE service_inventory_items SET status='RESERVED',reserved_order_id=?,reserved_until=? WHERE id=?",
                pending.getId(), LocalDateTime.now().minusMinutes(1), id);
        commerce.release(pending, true);
        ServiceInventoryItem released = commerce.listInventoryPage(service.getId(), "AVAILABLE", 1, 10).getItems().get(0);
        assertThat(released.getReservedOrderId()).isNull(); assertThat(released.getReservedUntil()).isNull();
        assertThat(orders.selectById(pending.getId()).getStatus()).isEqualTo("EXPIRED");

        ServiceOrder paid = order(service, "BUYER-DELIVERY", "PAID");
        paid.setFulfillmentMode(ServiceCommerceService.AUTOMATIC);
        paid.setSupplierType("LOCAL_INVENTORY"); orders.updateById(paid);
        commerce.settlePaid(paid);
        commerce.settlePaid(orders.selectById(paid.getId()));
        assertThat(commerce.inventoryStats(service.getId())).containsEntry("DELIVERED", 1L).containsEntry("AVAILABLE", 0L);
        var buyer = com.transit.model.User.builder().id(777L).build();
        assertThat(orderService.getUserOrder(buyer, paid.getId()).getDeliveryItems()).containsExactly("BUYER-DELIVERY-SECRET");
        assertThatThrownBy(() -> orderService.getUserOrder(com.transit.model.User.builder().id(778L).build(), paid.getId()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> commerce.deleteAvailableInventory(service.getId(), id)).isInstanceOf(ResponseStatusException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "RESERVED:SINGLE_DELETE", "RESERVED:BATCH_DELETE", "RESERVED:REPLACE",
            "DELIVERED:SINGLE_DELETE", "DELIVERED:BATCH_DELETE", "DELIVERED:REPLACE"})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void mutationWaitsForConcurrentAllocationAndNeverOverwritesAssignedContent(String scenario) throws Exception {
        String[] parts = scenario.split(":");
        OtherService service = localService();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var commit = new java.util.concurrent.CountDownLatch(1);
        try {
            commerce.importInventory(service.getId(), "CONCURRENT-SECRET-ORIGINAL");
            Long id = commerce.listInventoryPage(service.getId(), null, 1, 10).getItems().get(0).getId();
            var transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            var allocation = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.update("UPDATE service_inventory_items SET status=? WHERE id=? AND status='AVAILABLE'", parts[0], id);
                locked.countDown();
                try {
                    if (!commit.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var mutation = executor.submit(() -> catchThrowable(() -> {
                switch (parts[1]) {
                    case "SINGLE_DELETE" -> commerce.deleteAvailableInventory(service.getId(), id);
                    case "BATCH_DELETE" -> commerce.deleteAvailableInventoryBatch(service.getId(), List.of(id));
                    case "REPLACE" -> commerce.replaceAvailableInventory(service.getId(), id, "UNSAFE-REPLACEMENT");
                    default -> throw new IllegalArgumentException("invalid scenario");
                }
            }));
            assertThatThrownBy(() -> mutation.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            commit.countDown(); allocation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(mutation.get(5, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(ResponseStatusException.class);
            assertThat(inventory.selectById(id).getStatus()).isEqualTo(parts[0]);
            assertThat(commerce.revealInventory(service.getId(), id)).isEqualTo("CONCURRENT-SECRET-ORIGINAL");
        } finally {
            commit.countDown(); executor.shutdownNow(); executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            jdbc.update("DELETE FROM service_inventory_items WHERE service_id=?", service.getId());
            jdbc.update("DELETE FROM other_services WHERE id=?", service.getId());
        }
    }

    private OtherService localService() {
        OtherService service = OtherService.builder().name("inventory-test-" + UUID.randomUUID()).productType("CARD_KEY")
                .fulfillmentMode("AUTOMATIC_DELIVERY").supplierType("LOCAL_INVENTORY").enabled(true).purchaseEnabled(true)
                .priceCents(100L).serviceFeeCents(0L).currency("CNY").maxPurchaseQuantity(1).build();
        services.insert(service); return service;
    }
    private ServiceOrder order(OtherService service, String orderNo, String status) {
        ServiceOrder order = ServiceOrder.builder().serviceId(service.getId()).orderNo(orderNo + "-" + UUID.randomUUID()).userId(777L)
                .productName(service.getName()).quantity(1).status(status).amountCents(100L).currency("CNY")
                .unitPriceCents(100L).serviceFeeCents(0L).wholesaleDiscountCents(0L).couponDiscountCents(0L).createdAt(LocalDateTime.now()).build();
        orders.insert(order);
        // Keep the readable lookup prefix while ensuring order uniqueness across the test suite.
        return order;
    }
}
