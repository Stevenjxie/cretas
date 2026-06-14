package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1 两阶段批次选择 (PR #309 B1=C, 2026-05-11) — TransferServiceImpl SHIP-flow tests.
 *
 * 覆盖:
 * 1. sourceBatchId=null → FEFO preserved (consume oldest first)
 * 2. sourceBatchId=X → specific X consumed first, FEFO fallback for shortfall
 * 3. Invalid preselected batch (not in FEFO list) → BusinessException 409
 * 4. updateItemSourceBatch (APPROVED only, with validation)
 * 5. getAvailableBatchesForItem returns warehouse-filtered list
 */
@DisplayName("TransferServiceImpl B1 — 两阶段批次选择")
@ExtendWith(MockitoExtension.class)
class TransferShipBatchSelectionTest {

    @Mock private InternalTransferRepository transferRepository;
    @Mock private InternalTransferItemRepository transferItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(
                transferRepository,
                transferItemRepository,
                materialBatchRepository,
                finishedGoodsBatchRepository,
                applicationEventPublisher,
                materialBatchService,
                rawMaterialTypeRepository);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
    }

    // ===== helpers =====

    private InternalTransfer buildTransfer(String factoryId, String sourceWarehouseId,
                                            TransferStatus status, InternalTransferItem item) {
        InternalTransfer t = new InternalTransfer();
        t.setId("T_B1_001");
        t.setTransferNumber("TR-B1-001");
        t.setSourceFactoryId(factoryId);
        t.setTargetFactoryId("F002");
        t.setSourceWarehouseId(sourceWarehouseId);
        t.setStatus(status);
        t.setTransferType(TransferType.HQ_TO_BRANCH);
        item.setTransferId(t.getId());
        t.getItems().add(item);
        return t;
    }

    private InternalTransferItem rawMaterialItem(Long id, String materialTypeId,
                                                  BigDecimal qty, String preselected) {
        InternalTransferItem item = new InternalTransferItem();
        item.setId(id);
        item.setItemType(TransferItemType.RAW_MATERIAL);
        item.setMaterialTypeId(materialTypeId);
        item.setQuantity(qty);
        item.setUnit("kg");
        item.setSourceBatchId(preselected);
        return item;
    }

    private MaterialBatch materialBatch(String id, String factoryId, String warehouseId,
                                         String materialTypeId, BigDecimal receipt,
                                         BigDecimal used, LocalDate expire) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setBatchNumber("BN-" + id);
        b.setFactoryId(factoryId);
        b.setWarehouseId(warehouseId);
        b.setMaterialTypeId(materialTypeId);
        b.setReceiptQuantity(receipt);
        b.setUsedQuantity(used);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setExpireDate(expire);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        return b;
    }

    // ===== T1: sourceBatchId=null → FEFO preserved =====

    @Test
    @DisplayName("T1: sourceBatchId=null → FEFO 顺序保留 (最早过期的批次先扣减)")
    void shipWithoutPreselection_consumesFefoOrder() {
        InternalTransferItem item = rawMaterialItem(101L, "MT_001", new BigDecimal("50"), null);
        InternalTransfer t = buildTransfer("F001", "WH_A", TransferStatus.APPROVED, item);

        MaterialBatch oldest = materialBatch("B_OLD", "F001", "WH_A", "MT_001",
                new BigDecimal("100"), BigDecimal.ZERO, LocalDate.now().plusDays(10));
        MaterialBatch newest = materialBatch("B_NEW", "F001", "WH_A", "MT_001",
                new BigDecimal("100"), BigDecimal.ZERO, LocalDate.now().plusDays(60));

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse("F001", "MT_001", "WH_A"))
                .thenReturn(List.of(oldest, newest));
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));

        service.shipTransfer("F001", "T_B1_001", 99L);

        // Oldest batch consumed (FEFO)
        assertThat(item.getSourceBatchId()).isEqualTo("B_OLD");
        assertThat(oldest.getUsedQuantity()).isEqualByComparingTo("50");
        assertThat(newest.getUsedQuantity()).isEqualByComparingTo("0");
        verify(inventoryLowStockEventPublisher).publishIfLowStock("F001", oldest, "TRANSFER_OUT");
    }

    // ===== T2: sourceBatchId=X → specific X consumed first =====

    @Test
    @DisplayName("T2: sourceBatchId=B_NEW → 优先消耗 B_NEW (覆盖 FEFO 默认行为)")
    void shipWithPreselection_consumesPreselectedFirst() {
        InternalTransferItem item = rawMaterialItem(102L, "MT_001", new BigDecimal("50"), "B_NEW");
        InternalTransfer t = buildTransfer("F001", "WH_A", TransferStatus.APPROVED, item);

        MaterialBatch oldest = materialBatch("B_OLD", "F001", "WH_A", "MT_001",
                new BigDecimal("100"), BigDecimal.ZERO, LocalDate.now().plusDays(10));
        MaterialBatch newest = materialBatch("B_NEW", "F001", "WH_A", "MT_001",
                new BigDecimal("100"), BigDecimal.ZERO, LocalDate.now().plusDays(60));

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse("F001", "MT_001", "WH_A"))
                .thenReturn(List.of(oldest, newest));
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));

        service.shipTransfer("F001", "T_B1_001", 99L);

        // 预选 B_NEW 被消耗, B_OLD 未动
        assertThat(item.getSourceBatchId()).isEqualTo("B_NEW");
        assertThat(newest.getUsedQuantity()).isEqualByComparingTo("50");
        assertThat(oldest.getUsedQuantity()).isEqualByComparingTo("0");
        verify(inventoryLowStockEventPublisher).publishIfLowStock("F001", newest, "TRANSFER_OUT");
    }

    // ===== T3: Invalid preselected → BusinessException =====

    @Test
    @DisplayName("T3: 预选批次不存在于 FEFO 列表 → BusinessException 409")
    void shipWithInvalidPreselection_throwsBusinessException() {
        InternalTransferItem item = rawMaterialItem(103L, "MT_001", new BigDecimal("50"), "B_GHOST");
        InternalTransfer t = buildTransfer("F001", "WH_A", TransferStatus.APPROVED, item);

        MaterialBatch real = materialBatch("B_REAL", "F001", "WH_A", "MT_001",
                new BigDecimal("100"), BigDecimal.ZERO, LocalDate.now().plusDays(30));

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse("F001", "MT_001", "WH_A"))
                .thenReturn(List.of(real));

        assertThatThrownBy(() -> service.shipTransfer("F001", "T_B1_001", 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("B_GHOST");
    }

    // ===== T4: updateItemSourceBatch only when APPROVED + validates batch =====

    @Test
    @DisplayName("T4a: updateItemSourceBatch null → 清除预选 (走 FEFO)")
    void updateItemSourceBatch_nullClearsPreselection() {
        InternalTransferItem item = rawMaterialItem(104L, "MT_001", new BigDecimal("50"), "B_OLD_PRE");
        InternalTransfer t = buildTransfer("F001", "WH_A", TransferStatus.APPROVED, item);

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));
        when(transferItemRepository.save(any(InternalTransferItem.class))).thenAnswer(i -> i.getArgument(0));

        InternalTransferItem updated = service.updateItemSourceBatch("F001", "T_B1_001", 104L, null);

        assertThat(updated.getSourceBatchId()).isNull();
        verify(transferItemRepository).save(item);
    }

    @Test
    @DisplayName("T4b: updateItemSourceBatch status=SHIPPED → BusinessException (只允许 APPROVED 改批次)")
    void updateItemSourceBatch_shippedStatus_throws() {
        InternalTransferItem item = rawMaterialItem(105L, "MT_001", new BigDecimal("50"), null);
        InternalTransfer t = buildTransfer("F001", "WH_A", TransferStatus.SHIPPED, item);

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.updateItemSourceBatch("F001", "T_B1_001", 105L, "B_X"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许修改批次");
    }

    @Test
    @DisplayName("T4c: updateItemSourceBatch 校验批次属于 source warehouse")
    void updateItemSourceBatch_wrongWarehouse_throws() {
        InternalTransferItem item = rawMaterialItem(106L, "MT_001", new BigDecimal("50"), null);
        InternalTransfer t = buildTransfer("F001", "WH_A", TransferStatus.APPROVED, item);

        MaterialBatch wrongWh = materialBatch("B_WRONG_WH", "F001", "WH_B", "MT_001",
                new BigDecimal("100"), BigDecimal.ZERO, LocalDate.now().plusDays(30));

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));
        when(materialBatchRepository.findById("B_WRONG_WH")).thenReturn(Optional.of(wrongWh));

        assertThatThrownBy(() -> service.updateItemSourceBatch("F001", "T_B1_001", 106L, "B_WRONG_WH"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("源仓库");
    }

    // ===== T5: available-batches endpoint returns warehouse-filtered =====

    @Test
    @DisplayName("T5: getAvailableBatchesForItem 返回源仓库的可用批次列表 (FEFO 顺序)")
    void getAvailableBatchesForItem_returnsWarehouseFilteredFefoList() {
        InternalTransferItem item = rawMaterialItem(107L, "MT_001", new BigDecimal("50"), null);
        InternalTransfer t = buildTransfer("F001", "WH_A", TransferStatus.APPROVED, item);

        MaterialBatch b1 = materialBatch("B_A1", "F001", "WH_A", "MT_001",
                new BigDecimal("100"), new BigDecimal("20"), LocalDate.now().plusDays(15));
        MaterialBatch b2 = materialBatch("B_A2", "F001", "WH_A", "MT_001",
                new BigDecimal("100"), BigDecimal.ZERO, LocalDate.now().plusDays(45));

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse("F001", "MT_001", "WH_A"))
                .thenReturn(List.of(b1, b2));

        List<Map<String, Object>> result = service.getAvailableBatchesForItem("F001", "T_B1_001", 107L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("batchId")).isEqualTo("B_A1");
        assertThat(result.get(0).get("availableQuantity")).isEqualTo(new BigDecimal("80"));
        assertThat(result.get(0).get("warehouseId")).isEqualTo("WH_A");
        assertThat(result.get(1).get("batchId")).isEqualTo("B_A2");
        assertThat(result.get(1).get("availableQuantity")).isEqualTo(new BigDecimal("100"));
    }
}
