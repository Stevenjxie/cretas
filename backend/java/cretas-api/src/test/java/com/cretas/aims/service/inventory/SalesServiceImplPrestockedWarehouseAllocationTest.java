package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.enums.CustomerStockFulfillmentMode;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesServiceImplPrestockedWarehouseAllocationTest {

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesDeliveryBatchAllocationService allocationService;

    private SalesServiceImpl service;
    private SalesDeliveryRecord delivery;

    @BeforeEach
    void setUp() {
        service = new SalesServiceImpl(
                salesOrderRepository, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "batchAllocationService", allocationService);

        SalesOrder order = new SalesOrder();
        order.setId("SO-1");
        order.setFactoryId("F006");
        order.setCustomerStockFulfillmentMode(CustomerStockFulfillmentMode.PRESTOCKED);
        when(salesOrderRepository.findById("SO-1")).thenReturn(Optional.of(order));

        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(101L);
        item.setProductTypeId("SKU-1");
        item.setProductName("客户卤牛肉");
        item.setDeliveredQuantity(BigDecimal.ONE);
        item.setUnit("box");

        delivery = new SalesDeliveryRecord();
        delivery.setSalesOrderId("SO-1");
        delivery.setItems(List.of(item));
    }

    @Test
    void warehouseConfirmationAutoAllocatesTheExactPrestockedReservation() {
        when(allocationService.isFullyAllocated("F006", "101")).thenReturn(false);
        when(allocationService.recommendFifo(
                "F006", "101", "SKU-1", BigDecimal.ONE, "box", null))
                .thenReturn(List.of(recommendation("FG-1", BigDecimal.ONE)));

        ReflectionTestUtils.invokeMethod(service, "autoAllocatePrestockedReservations", "F006", delivery);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchAllocationDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(allocationService).allocateBatches(org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("101"), captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(allocation -> {
            assertThat(allocation.getFinishedGoodsBatchId()).isEqualTo("FG-1");
            assertThat(allocation.getAllocatedQty()).isEqualByComparingTo(BigDecimal.ONE);
        });
    }

    @Test
    void incompletePrestockedRecommendationFailsBeforeAnyAllocationOrInventoryWrite() {
        when(allocationService.isFullyAllocated("F006", "101")).thenReturn(false);
        when(allocationService.recommendFifo(
                "F006", "101", "SKU-1", BigDecimal.ONE, "box", null))
                .thenReturn(List.of(recommendation("FG-1", new BigDecimal("0.5"))));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "autoAllocatePrestockedReservations", "F006", delivery))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("PRESTOCKED_RESERVATION_INSUFFICIENT"));

        verify(allocationService, never()).allocateBatches(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
    }

    private Map<String, Object> recommendation(String batchId, BigDecimal quantity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", batchId);
        row.put("recommendedQuantity", quantity);
        return row;
    }
}
