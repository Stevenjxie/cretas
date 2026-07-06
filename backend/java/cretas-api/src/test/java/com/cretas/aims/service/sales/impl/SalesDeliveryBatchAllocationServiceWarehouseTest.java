package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
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
    @Mock ProductTypeRepository productTypeRepository;
    @Mock com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository deliveryRecordRepository;

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
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(wksBatch));

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
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(wksBatch));

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
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(wksBatch));

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
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(rdBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("10"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("研发") || ex.getMessage().contains("中试"),
                "error should explain the batch is in the R&D/trial warehouse");
    }

    // ─────────────── 🔴 预留隔离 (reserved lifecycle) ───────────────

    private SalesDeliveryItemBatchAllocation allocRecord(String batchId, BigDecimal qty, String unit) {
        SalesDeliveryItemBatchAllocation a = new SalesDeliveryItemBatchAllocation();
        a.setFactoryId(FID);
        a.setDeliveryItemId(ITEM_ID);
        a.setFinishedGoodsBatchId(batchId);
        a.setBatchNumber("BATCH-" + batchId);
        a.setAllocatedQty(qty);
        a.setUnit(unit);
        return a;
    }

    // 分配 = 在批次上写预留 (reserved += 分配量), 之后其它单读到的可用即已扣本单占用。
    @Test
    void allocateBatches_incrementsReservedByAllocatedQty() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("10"));
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);

        FinishedGoodsBatch b = batch("b1", WH_WKS_ID, new BigDecimal("20")); // reserved 0
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(b));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("10"));

        service.allocateBatches(FID, ITEM_ID, List.of(dto));

        assertEquals(0, b.getReservedQuantity().compareTo(new BigDecimal("10")),
                "分配后批次预留应等于分配量");
        verify(finishedGoodsBatchRepository).save(b);
        verify(allocationRepository).saveAll(anyList());
    }

    // 🔴 核心并发隔离: 批次已被另一发货行预留 (reserved=60), 本行再分配 50 → 可用=40 → 409, 不能超分同一物理库存。
    @Test
    void allocateBatches_cannotOverAllocateReservedByOtherLine() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("50"));
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);

        FinishedGoodsBatch b = batch("b1", WH_WKS_ID, new BigDecimal("100"));
        b.setReservedQuantity(new BigDecimal("60")); // 另一行已占 60 → 可用仅 40
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(b));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("50"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("可用库存不足"));
        // 未写入本行预留 (409 前不改), 另一行的 60 保持不变。
        assertEquals(0, b.getReservedQuantity().compareTo(new BigDecimal("60")));
    }

    // 改分配 (re-allocate): 先释放本行旧预留再按新量预留 → 不叠加。旧 8 → 新 5, 批次预留最终=5 不是 13。
    @Test
    void allocateBatches_reAllocate_releasesOldReserveNoStacking() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("5"));
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);

        FinishedGoodsBatch b = batch("b1", WH_WKS_ID, new BigDecimal("20"));
        b.setReservedQuantity(new BigDecimal("8")); // 本行旧分配占用 8
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(b));
        // 本行现有分配记录 (旧 8) → 释放依据
        when(allocationRepository.findByFactoryIdAndDeliveryItemId(FID, ITEM_ID))
                .thenReturn(List.of(allocRecord("b1", new BigDecimal("8"), "kg")));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("5"));

        service.allocateBatches(FID, ITEM_ID, List.of(dto));

        // 释放旧 8 (→0) 再预留新 5 (→5) —— 不叠加成 13。
        assertEquals(0, b.getReservedQuantity().compareTo(new BigDecimal("5")),
                "改分配应释放旧预留再按新量预留, 不叠加");
    }

    // 跨单位预留: 发货行=盒, 批次原生=kg, 每盒15g → 分配 60盒 预留应换算回 kg = 0.9kg。
    @Test
    void allocateBatches_reserveConvertedToBatchNativeUnit() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("60"));
        item.setUnit("盒");
        item.setProductTypeId(PRODUCT_ID);
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(productTypeWithGramsPerUnit(new BigDecimal("15"), "盒")));

        FinishedGoodsBatch kgBatch = batch("b1", WH_WKS_ID, new BigDecimal("1")); // 1kg, reserved 0
        kgBatch.setUnit("kg");
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(kgBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("60")); // 60盒

        service.allocateBatches(FID, ITEM_ID, List.of(dto));

        // 60盒 × 15g = 900g = 0.9kg 预留 (原生单位)。
        assertEquals(0, new BigDecimal("0.9").compareTo(kgBatch.getReservedQuantity()),
                "预留应换算回批次原生单位 (kg)");
    }

    // 清空分配 = 释放预留 (release on cancel)。批次预留 10 → 清空后归 0, 并删记录。
    @Test
    void clearAllocations_releasesReservedAndDeletes() {
        FinishedGoodsBatch b = batch("b1", WH_WKS_ID, new BigDecimal("20"));
        b.setReservedQuantity(new BigDecimal("10"));
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(b));
        when(allocationRepository.findByFactoryIdAndDeliveryItemId(FID, ITEM_ID))
                .thenReturn(List.of(allocRecord("b1", new BigDecimal("10"), "kg")));

        service.clearAllocations(FID, ITEM_ID);

        assertEquals(0, b.getReservedQuantity().compareTo(BigDecimal.ZERO), "清空分配应释放预留归零");
        verify(allocationRepository).deleteByFactoryIdAndDeliveryItemId(FID, ITEM_ID);
    }

    // 防呆守卫: 已发货的发货单不能再改批次分配 (防重复释放预留 → available 虚高)。
    @Test
    void allocateBatches_rejectedWhenDeliveryAlreadyShipped() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("10"));
        item.setDeliveryRecordId("dlv-1");
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        com.cretas.aims.entity.inventory.SalesDeliveryRecord rec =
                new com.cretas.aims.entity.inventory.SalesDeliveryRecord();
        rec.setStatus(com.cretas.aims.entity.enums.SalesDeliveryStatus.SHIPPED);
        when(deliveryRecordRepository.findById("dlv-1")).thenReturn(Optional.of(rec));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("10"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("不允许"));
        // 已发货 → 提前 409, 绝不触碰批次库存。
        verify(finishedGoodsBatchRepository, never()).save(any());
    }

    // ─────────────── recommendFifo ───────────────

    @Test
    void recommendFifo_usesSourceWarehouseCodeWhenProvided() {
        when(warehouseResolver.resolveId(FID, "WH-WKS")).thenReturn(WH_WKS_ID);
        when(finishedGoodsBatchRepository.findAvailableBatchesFifoByWarehouse(FID, PRODUCT_ID, WH_WKS_ID))
                .thenReturn(List.of());

        service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("10"), null, "WH-WKS");

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

        var result = service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("10"), null, null);

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

        service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("10"), null, "  ");

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

    // ─────────────── 🔴 C1 (2026-07-05): unit-aware FEFO / allocation ───────────────
    // F006 现场确认: 同一产品的成品批次可能记录在不同单位 (一批小结填了 productWeight → kg,
    // 另一批未填 → 盒). 以下测试锁定「换算后再比较/求和」, 不再把跨单位数字裸相加/比较.

    private com.cretas.aims.entity.ProductType productTypeWithGramsPerUnit(BigDecimal gramsPerUnit, String unit) {
        com.cretas.aims.entity.ProductType pt = new com.cretas.aims.entity.ProductType();
        pt.setId(PRODUCT_ID);
        pt.setGramsPerUnit(gramsPerUnit);
        pt.setUnit(unit);
        return pt;
    }

    @Test
    void recommendFifo_unitParam_convertsBatchNativeUnitIntoTargetUnit() {
        // 每盒 15g. 一批 4454.5 盒 (未小结 productWeight), 换算到 kg = 66.8175kg.
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(productTypeWithGramsPerUnit(new BigDecimal("15"), "盒")));
        FinishedGoodsBatch boxBatch = batch("b1", WH_WKS_ID, new BigDecimal("4454.5"));
        boxBatch.setUnit("盒");
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(FID, PRODUCT_ID, "WH-RD"))
                .thenReturn(List.of(boxBatch));

        // 目标(发货行)单位 = kg, 需求 10kg — 远小于该批换算后的 66.8175kg 可用量.
        var result = service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("10"), "kg", null);

        assertEquals(1, result.size());
        assertEquals("kg", result.get(0).get("unit"));
        assertEquals("盒", result.get(0).get("batchNativeUnit"));
        BigDecimal available = (BigDecimal) result.get(0).get("availableQuantity");
        BigDecimal recommended = (BigDecimal) result.get(0).get("recommendedQuantity");
        assertEquals(0, new BigDecimal("66.8175").compareTo(available));
        // 只需 10kg, 换算后单批可用 66.8175kg 已够 — 推荐量 = 10 (未超发, 未把裸盒数当 kg 用).
        assertEquals(0, new BigDecimal("10").compareTo(recommended));
    }

    @Test
    void recommendFifo_unitMismatchWithoutGramsPerUnit_skipsBatchHonestly() {
        // 产品无 gramsPerUnit 配置 → 该 kg 批次无法换算到目标单位 盒, 应被跳过 (诚实 null), 不裸混入.
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(productTypeWithGramsPerUnit(null, "盒")));
        FinishedGoodsBatch kgBatch = batch("b1", WH_WKS_ID, new BigDecimal("0.45"));
        kgBatch.setUnit("kg");
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(FID, PRODUCT_ID, "WH-RD"))
                .thenReturn(List.of(kgBatch));

        var result = service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("10"), "盒", null);

        assertEquals(0, result.size(), "单位不可换算的批次不应出现在推荐列表里 (诚实空态, 不裸混入)");
    }

    @Test
    void allocateBatches_convertsBatchNativeUnitForAvailabilityCheck_accepts() {
        // 发货行单位=盒, 批次原生单位=kg (produced=1kg), 每盒15g → 可用换算 = 1000/15 = 66.67盒.
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("60"));
        item.setUnit("盒");
        item.setProductTypeId(PRODUCT_ID);
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(productTypeWithGramsPerUnit(new BigDecimal("15"), "盒")));

        FinishedGoodsBatch kgBatch = batch("b1", WH_WKS_ID, new BigDecimal("1"));
        kgBatch.setUnit("kg");
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(kgBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("60")); // 60盒 < 66.67盒 可用 → 应通过

        assertDoesNotThrow(() -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
    }

    @Test
    void allocateBatches_convertsBatchNativeUnitForAvailabilityCheck_rejectsWhenInsufficient() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("70"));
        item.setUnit("盒");
        item.setProductTypeId(PRODUCT_ID);
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(productTypeWithGramsPerUnit(new BigDecimal("15"), "盒")));

        FinishedGoodsBatch kgBatch = batch("b1", WH_WKS_ID, new BigDecimal("1"));
        kgBatch.setUnit("kg");
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(kgBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("70")); // 70盒 > 66.67盒 可用 → 应 409

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("可用库存不足"));
    }

    @Test
    void allocateBatches_unitMismatchWithoutGramsPerUnit_throws409WithHint() {
        SalesDeliveryItem item = deliveryItem(null, new BigDecimal("10"));
        item.setUnit("盒");
        item.setProductTypeId(PRODUCT_ID);
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveRdId(FID)).thenReturn(WH_RD_ID);
        // 产品无 gramsPerUnit 配置 → 无法把 kg 批次换算为 盒
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(productTypeWithGramsPerUnit(null, "盒")));

        FinishedGoodsBatch kgBatch = batch("b1", WH_WKS_ID, new BigDecimal("1"));
        kgBatch.setUnit("kg");
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("b1", FID)).thenReturn(Optional.of(kgBatch));

        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("b1");
        dto.setAllocatedQty(new BigDecimal("10"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.allocateBatches(FID, ITEM_ID, List.of(dto)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("单位"));
    }
}
