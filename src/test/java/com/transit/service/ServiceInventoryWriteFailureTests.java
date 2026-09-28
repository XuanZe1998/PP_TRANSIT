package com.transit.service;

import com.transit.mapper.OtherServiceMapper;
import com.transit.mapper.ServiceInventoryItemMapper;
import com.transit.model.OtherService;
import com.transit.model.ServiceInventoryItem;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ServiceInventoryWriteFailureTests {
    @Test
    void upstreamPaidOrdersStillDelegateToProcurementWithoutReadingLocalInventory() {
        var services = mock(OtherServiceMapper.class);
        var orders = mock(com.transit.mapper.ServiceOrderMapper.class);
        var inventory = mock(ServiceInventoryItemMapper.class);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var secrets = mock(ChannelSecretService.class);
        var procurement = mock(DujiaoNextProcurementService.class);
        var upstream = OtherService.builder().id(1L).supplierType("DUJIAO_NEXT").fulfillmentMode("AUTOMATIC_DELIVERY").build();
        var order = com.transit.model.ServiceOrder.builder().id(50L).serviceId(1L).supplierType("DUJIAO_NEXT")
                .fulfillmentMode("AUTOMATIC_DELIVERY").quantity(1).status("PAID").build();
        when(services.selectById(1L)).thenReturn(upstream);
        when(jdbc.update(anyString(), any(java.time.LocalDateTime.class), eq(50L))).thenReturn(1);
        when(procurement.enqueue(order, upstream)).thenReturn(order);
        var commerce = new ServiceCommerceService(null, services, orders, inventory, null, jdbc, null, secrets, procurement);
        assertThat(commerce.settlePaid(order)).isSameAs(order);
        verify(procurement).enqueue(order, upstream);
        verifyNoInteractions(inventory, secrets);
    }

    @Test
    void aNonDuplicateFailureRollsBackPreviouslyInsertedItems() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:inventory-rollback-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS items(content VARCHAR(50))");
        jdbc.update("DELETE FROM items");
        OtherServiceMapper services = mock(OtherServiceMapper.class);
        ServiceInventoryItemMapper inventory = mock(ServiceInventoryItemMapper.class);
        ChannelSecretService secrets = mock(ChannelSecretService.class);
        when(services.selectById(1L)).thenReturn(OtherService.builder().id(1L).fulfillmentMode("AUTOMATIC_DELIVERY").build());
        when(secrets.isConfigured()).thenReturn(true); when(secrets.encrypt(anyString())).thenAnswer(call -> call.getArgument(0));
        when(inventory.insert(any(ServiceInventoryItem.class))).thenAnswer(call -> {
            ServiceInventoryItem item = call.getArgument(0);
            if ("FAIL".equals(item.getContentEncrypted())) throw new DataAccessResourceFailureException("write failed");
            return jdbc.update("INSERT INTO items(content) VALUES (?)", item.getContentEncrypted());
        });
        ServiceCommerceService service = new ServiceCommerceService(null, services, null, inventory, null, jdbc, null, secrets, null);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        assertThatThrownBy(() -> transactions.execute(tx -> service.importInventoryWithReport(1L, "ONE FAIL")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items", Integer.class)).isZero();
        assertThat(ServiceCommerceService.class.getMethods()).filteredOn(method -> method.getName().equals("importInventoryWithReport"))
                .allSatisfy(method -> assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull());
    }

    @Test
    void onlyDuplicateKeyFailuresAreIgnored() {
        OtherServiceMapper services = mock(OtherServiceMapper.class);
        ServiceInventoryItemMapper inventory = mock(ServiceInventoryItemMapper.class);
        ChannelSecretService secrets = mock(ChannelSecretService.class);
        when(services.selectById(1L)).thenReturn(OtherService.builder().id(1L).fulfillmentMode("AUTOMATIC_DELIVERY").supplierType("LOCAL_INVENTORY").build());
        when(secrets.isConfigured()).thenReturn(true); when(secrets.encrypt(anyString())).thenReturn("encrypted");
        ServiceCommerceService service = new ServiceCommerceService(null, services, null, inventory, null, null, null, secrets, null);
        when(inventory.insert(any(ServiceInventoryItem.class))).thenThrow(new DuplicateKeyException("duplicate")).thenReturn(1);
        assertThat(service.importInventoryWithReport(1L, "ONE TWO")).containsEntry("imported", 1).containsEntry("duplicateInStock", 1);
        reset(inventory); when(inventory.insert(any(ServiceInventoryItem.class))).thenThrow(new DataAccessResourceFailureException("write failed"));
        assertThatThrownBy(() -> service.importInventoryWithReport(1L, "ONE TWO")).isInstanceOf(DataAccessResourceFailureException.class);
        verify(inventory, times(1)).insert(any(ServiceInventoryItem.class));
    }
}
