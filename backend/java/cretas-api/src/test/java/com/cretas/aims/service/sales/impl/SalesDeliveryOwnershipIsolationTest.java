package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesDeliveryOwnershipIsolationTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "SKU-1";
    private static final String ORDER = "SO-1";
    private static final String CUSTOMER = "C-1";

    @Mock SalesDeliveryItemBatchAllocationRepository allocationRepository;
    @Mock SalesDeliveryItemRepository deliveryItemRepository;
    @Mock FinishedGoodsBatchRepository batchRepository;
    @Mock WarehouseResolver warehouseResolver;
    @Mock ProductTypeRepository productTypeRepository;

    @InjectMocks SalesDeliveryBatchAllocationServiceImpl service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(warehouseResolver.resolveRdId(FACTORY)).thenReturn("WH-RD-ID");
    }

    @Test
    void standardSaleRejectsCustomerOwnedBatchDuringManualAllocation() {
        SalesDeliveryItem item = deliveryItem(SalesProcessingMode.STANDARD_SALE);
        FinishedGoodsBatch batch = customerBatch("B-1", ORDER, CUSTOMER);
        when(deliveryItemRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(item));
        when(batchRepository.findByIdAndFactoryIdForUpdate("B-1", FACTORY)).thenReturn(Optional.of(batch));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.allocateBatches(FACTORY, "11", List.of(allocation("B-1"))));

        assertEquals("DELIVERY_BATCH_OWNERSHIP_MISMATCH", error.getErrorCode());
    }

    @Test
    void tollProcessingAcceptsOnlySameCustomerAndOrderBatch() {
        SalesDeliveryItem item = deliveryItem(SalesProcessingMode.TOLL_PROCESSING);
        FinishedGoodsBatch batch = customerBatch("B-1", ORDER, CUSTOMER);
        when(deliveryItemRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(item));
        when(batchRepository.findByIdAndFactoryIdForUpdate("B-1", FACTORY)).thenReturn(Optional.of(batch));

        assertDoesNotThrow(() -> service.allocateBatches(
                FACTORY, "11", List.of(allocation("B-1"))));
        verify(allocationRepository).saveAll(anyList());
    }

    @Test
    void recommendationFiltersCustomerOwnedBatchFromAnotherSalesOrder() {
        SalesDeliveryItem item = deliveryItem(SalesProcessingMode.TOLL_PROCESSING);
        FinishedGoodsBatch own = customerBatch("OWN", ORDER, CUSTOMER);
        FinishedGoodsBatch other = customerBatch("OTHER", "SO-OTHER", CUSTOMER);
        when(deliveryItemRepository.findById(11L)).thenReturn(Optional.of(item));
        when(batchRepository.findAvailableBatchesFefoAllWarehousesExcluding(
                FACTORY, PRODUCT, "WH-RD")).thenReturn(List.of(other, own));

        List<java.util.Map<String, Object>> result = service.recommendFifo(
                FACTORY, "11", PRODUCT, BigDecimal.ONE, "kg", null);

        assertEquals(1, result.size());
        assertEquals("OWN", result.get(0).get("batchId"));
    }

    private SalesDeliveryItem deliveryItem(SalesProcessingMode processingMode) {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER);
        order.setFactoryId(FACTORY);
        order.setCustomerId(CUSTOMER);
        order.setProcessingMode(processingMode);

        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setFactoryId(FACTORY);
        delivery.setSalesOrderId(ORDER);
        delivery.setCustomerId(CUSTOMER);
        delivery.setSalesOrder(order);

        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(11L);
        item.setProductTypeId(PRODUCT);
        item.setDeliveredQuantity(BigDecimal.ONE);
        item.setUnit("kg");
        item.setSalesOrderItemId(21L);
        item.setDeliveryRecord(delivery);
        return item;
    }

    private FinishedGoodsBatch customerBatch(String id, String sourceOrderId, String ownerCustomerId) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber(id);
        batch.setProductTypeId(PRODUCT);
        batch.setWarehouseId("WH-LOG-ID");
        batch.setStatus("AVAILABLE");
        batch.setUnit("kg");
        batch.setProducedQuantity(BigDecimal.TEN);
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        batch.setOwnerCustomerId(ownerCustomerId);
        batch.setSourceSalesOrderId(sourceOrderId);
        batch.setSourceSalesOrderItemId("21");
        return batch;
    }

    private BatchAllocationDTO allocation(String batchId) {
        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId(batchId);
        dto.setAllocatedQty(BigDecimal.ONE);
        return dto;
    }
}
