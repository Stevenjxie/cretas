package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-D5 (#572) + 🔴 G1 (2026-07-03) tests: SalesDeliveryBatchAllocationServiceImpl
 * warehouse discovery / guard semantics.
 *
 * <ul>
 *   <li><b>EXPLICIT</b> sourceWarehouseCode → strict single-warehouse (recommend filters, allocate
 *       409-guards). Unchanged from T4-D5.</li>
 *   <li><b>BLANK</b> sourceWarehouseCode (G1 fix) → NO single-warehouse constraint: recommend
 *       searches ALL shippable (non-RD) warehouses; allocate accepts any non-RD warehouse batch.
 *       Fixes Steve #1 bug where blank hard-defaulted to WH-LOG and returned empty even though FG
 *       existed in WH-WKS.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SalesDeliveryBatchAllocationServiceWarehouseTest {

    @Mock SalesDeliveryItemBatchAllocationRepository allocationRepository;
    @Mock SalesDeliveryItemRepository deliveryItemRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock WarehouseResolver warehouseResolver;

    @InjectMocks SalesDeliveryBatchAllocationServiceImpl service;

    private static final String FID = "F001";
    private static final String ITEM_ID = "42";
    private static final String WH_LOG_ID = "wh-log-uuid-001";
    private static final String WH_WKS_ID = "wh-wks-uuid-001";
    private static final String WH_RD_ID = "wh-rd-uuid-001";
    private static final String PRODUCT_ID = "p-1";

    private SalesDeliveryItem deliveryItem(String sourceCode, BigDecimal qty) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(42L);
        item.setSourceWarehouseCode(sourceCode);
        item.setDeliveredQuantity(qty);
        item.setUnit("kg");
        return item;
    }

    private FinishedGoodsBatch batch(String id, String warehouseId, BigDecimal produced) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setFactoryId(FID);
        b.setBatchNumber("BATCH-" + id);
        b.setWarehouseId(warehouseId);
        b.setProducedQuantity(produced);
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setProductTypeId(PRODUCT_ID);
        b.setUnit("kg");
        return b;
    }

    // ─────────────── allocateBatches ───────────────

    @Test
    void allocateBatches_rejectsBatchFromDifferentWarehouse() {
        SalesDeliveryItem item = deliveryItem("WH-LOG", new BigDecimal("10"));
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveId(FID, "WH-LOG")).thenReturn(WH_LOG_ID);

        FinishedGoodsBatch wksBatch = batch("b1", WH_WKS_ID, new BigDecimal("20"));
        when(finishedGoodsBatchRepository.findById("b1")).thenReturn(Optional.of(wksBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("10"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("WH-LOG"),
                "error message should mention the expected warehouse code");
    }

    @Test
    void allocateBatches_acceptsBatchFromMatchingWarehouse() {
        SalesDeliveryItem item = deliveryItem("WH-WKS", new BigDecimal("10"));
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveId(FID, "WH-WKS")).thenReturn(WH_WKS_ID);

        FinishedGoodsBatch wksBatch = batch("b1", WH_WKS_ID, new BigDecimal("20"));
        when(finishedGoodsBatchRepository.findById("b1")).thenReturn(Optional.of(wksBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("10"));

        assertDoesNotThrow(() -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        verify(allocationRepository).saveAll(anyList());
    }

    // 🔴 G1: blank source → accept a batch from ANY non-RD warehouse (previously 409'd WH-WKS batch).
    @Test
    void allocateBatches_blankSource_acceptsBatchFromAnyNonRdWarehouse() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("10"));
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);

        // FG sits in WH-WKS (production landing zone) — under the old WH-LOG default this 409'd.
        FinishedGoodsBatch wksBatch = batch("b1", WH_WKS_ID, new BigDecimal("20"));
        when(finishedGoodsBatchRepository.findById("b1")).thenReturn(Optional.of(wksBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("10"));

        assertDoesNotThrow(() -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        verify(allocationRepository).saveAll(anyList());
        // blank source resolves NO explicit warehouse — the phantom WH-LOG default is gone.
        verify(warehouseResolver, never()).resolveId(eq(FID), any());
    }

    // 🔴 G1: blank source still rejects a batch from the R&D/trial warehouse (non-saleable).
    @Test
    void allocateBatches_blankSource_rejectsRdWarehouseBatch() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("10"));
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);

        FinishedGoodsBatch rdBatch = batch("b1", WH_RD_ID, new BigDecimal("20"));
        when(finishedGoodsBatchRepository.findById("b1")).thenReturn(Optional.of(rdBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("10"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("研发") || ex.getMessage().contains("中试"),
                "error should explain the batch is in the R&D/trial warehouse");
    }

    // ─────────────── recommendFifo ───────────────

    @Test
    void recommendFifo_usesSourceWarehouseCodeWhenProvided() {
        when(warehouseResolver.resolveId(FID, "WH-WKS")).thenReturn(WH_WKS_ID);
        when(finishedGoodsBatchRepository.findAvailableBatchesFifoByWarehouse(FID, PRODUCT_ID, WH_WKS_ID))
                .thenReturn(List.of());

        service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("10"), "WH-WKS");

        verify(warehouseResolver).resolveId(FID, "WH-WKS");
        verify(finishedGoodsBatchRepository).findAvailableBatchesFifoByWarehouse(FID, PRODUCT_ID, WH_WKS_ID);
        // explicit path must NOT fall into the cross-warehouse discovery.
        verify(finishedGoodsBatchRepository, never())
                .findAvailableBatchesFefoAllWarehousesExcluding(any(), any(), any());
    }

    // 🔴 G1: blank source → cross-warehouse (non-RD) discovery, NOT a single-warehouse lookup.
    // This is the core bug repro: FG in WH-WKS must be surfaced under a blank source.
    @Test
    void recommendFifo_blankSource_searchesAllWarehousesExcludingRd() {
        FinishedGoodsBatch wksBatch = batch("b1", WH_WKS_ID, new BigDecimal("20"));
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(FID, PRODUCT_ID, "WH-RD"))
                .thenReturn(List.of(wksBatch));

        var result = service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("10"), null);

        assertEquals(1, result.size(), "FG in WH-WKS must be discovered under a blank source (bug repro)");
        assertEquals("BATCH-b1", result.get(0).get("batchNumber"));
        verify(finishedGoodsBatchRepository).findAvailableBatchesFefoAllWarehousesExcluding(FID, PRODUCT_ID, "WH-RD");
        verify(warehouseResolver, never()).resolveId(eq(FID), any());
        verify(warehouseResolver, never()).resolveLogisticsId(any());
        verify(finishedGoodsBatchRepository, never())
                .findAvailableBatchesFifoByWarehouse(any(), any(), any());
    }

    @Test
    void recommendFifo_blankStringSource_searchesAllWarehousesExcludingRd() {
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(FID, PRODUCT_ID, "WH-RD"))
                .thenReturn(List.of());

        service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("10"), "  ");

        verify(finishedGoodsBatchRepository).findAvailableBatchesFefoAllWarehousesExcluding(FID, PRODUCT_ID, "WH-RD");
        verify(warehouseResolver, never()).resolveId(eq(FID), any());
    }

    // ─────────────── warehousesWithAvailableStock (honest empty-state) ───────────────

    @Test
    void warehousesWithAvailableStock_delegatesExcludingRd() {
        when(finishedGoodsBatchRepository.findWarehouseCodesWithAvailableStock(FID, PRODUCT_ID, "WH-RD"))
                .thenReturn(List.of("WH-WKS", "WH-LOG"));

        var codes = service.warehousesWithAvailableStock(FID, PRODUCT_ID);

        assertEquals(List.of("WH-WKS", "WH-LOG"), codes);
        verify(finishedGoodsBatchRepository).findWarehouseCodesWithAvailableStock(FID, PRODUCT_ID, "WH-RD");
    }
}
