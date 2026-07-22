package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
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
import static org.mockito.Mockito.never;
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

    // ===== FG-SHORTAGE: 成品库存不足 → 409 (对齐 RAW_MATERIAL, 修复静默少发) =====

    @Test
    @DisplayName("FG-SHORTAGE: 成品库存不足时 throw 409 (此前仅 log.warn 静默少发)")
    void shipFinishedGoods_insufficientStock_throws() {
        InternalTransferItem item = finishedGoodsItem(201L, "PT_001", new BigDecimal("50"));
        InternalTransfer t = buildTransfer("F001", "WH_FG", TransferStatus.APPROVED, item);

        FinishedGoodsBatch only30 = finishedGoodsBatch("FG_1", new BigDecimal("30"));

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));
        when(finishedGoodsBatchRepository.findAvailableBatchesByWarehouse("F001", "PT_001", "WH_FG"))
                .thenReturn(List.of(only30));
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> service.shipTransfer("F001", "T_B1_001", 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成品库存不足");
    }

    private InternalTransferItem finishedGoodsItem(Long id, String productTypeId, BigDecimal qty) {
        InternalTransferItem item = new InternalTransferItem();
        item.setId(id);
        item.setItemType(TransferItemType.FINISHED_GOODS);
        item.setProductTypeId(productTypeId);
        item.setQuantity(qty);
        item.setUnit("件");
        return item;
    }

    private FinishedGoodsBatch finishedGoodsBatch(String id, BigDecimal produced) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setProducedQuantity(produced);
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setStatus("AVAILABLE");
        return b;
    }

    // ===== MES↔ERP Fix #4: 同厂成品调拨不污染 shippedQuantity + 保 unitCost =====

    @Test
    @DisplayName("M08: 5盒成品从生产仓完整调拨到成品仓 — productTypeId/canonical unit/总量/幂等")
    void finishedGoodsWarehouseTransfer_fullLifecycle_preservesFiveBoxesExactlyOnce() {
        FinishedGoodsBatch source = finishedGoodsBatch("FG-F006-ONLY", new BigDecimal("5"));
        source.setFactoryId("F006");
        source.setProductTypeId("CPF0060015");
        source.setProductName("E2E-MVP-20260719-2111-黄油鸡-成品800g");
        source.setWarehouseId("WH-PRODUCTION");
        source.setUnit("box");
        source.setUnitCost(new BigDecimal("11.2000"));

        CreateTransferRequest.TransferItemDTO line = new CreateTransferRequest.TransferItemDTO();
        line.setItemType("FINISHED_GOODS");
        line.setProductTypeId("CPF0060015");
        line.setItemName(source.getProductName());
        line.setQuantity(new BigDecimal("5"));
        line.setUnit("box");
        CreateTransferRequest request = new CreateTransferRequest();
        request.setTransferType("WAREHOUSE_TO_WAREHOUSE");
        request.setTargetFactoryId("F006");
        request.setSourceWarehouseId("WH-PRODUCTION");
        request.setTargetWarehouseId("WH-FINISHED");
        request.setTransferDate(LocalDate.now());
        request.setItems(List.of(line));

        when(transferRepository.findRecentDuplicates(eq("F006"), eq("F006"), eq(99L), any(), any()))
                .thenReturn(List.of());
        when(finishedGoodsBatchRepository.findAvailableBatchesByWarehouse(
                "F006", "CPF0060015", "WH-PRODUCTION"))
                .thenReturn(List.of(source));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(invocation -> {
            InternalTransfer transfer = invocation.getArgument(0);
            if (transfer.getId() == null) transfer.setId("TR-M08-F006");
            return transfer;
        });

        InternalTransfer transfer = service.createTransfer("F006", request, 99L);
        when(transferRepository.findByIdAndEitherFactoryId("TR-M08-F006", "F006"))
                .thenReturn(Optional.of(transfer));
        when(finishedGoodsBatchRepository.findById("FG-F006-ONLY")).thenReturn(Optional.of(source));
        List<FinishedGoodsBatch> savedBatches = new ArrayList<>();
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(invocation -> {
            FinishedGoodsBatch saved = invocation.getArgument(0);
            savedBatches.add(saved);
            return saved;
        });
        when(transferItemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(transfer.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getItemType()).isEqualTo(TransferItemType.FINISHED_GOODS);
            assertThat(item.getProductTypeId()).isEqualTo("CPF0060015");
            assertThat(item.getMaterialTypeId()).isNull();
            assertThat(item.getQuantity()).isEqualByComparingTo("5");
            assertThat(item.getUnit()).isEqualTo("box");
        });

        service.requestTransfer("F006", transfer.getId(), 99L);
        service.approveTransfer("F006", transfer.getId(), 99L);
        service.shipTransfer("F006", transfer.getId(), 99L);
        assertThat(source.getProducedQuantity()).isEqualByComparingTo("0");
        assertThat(source.getShippedQuantity()).isEqualByComparingTo("0");
        assertThat(source.getStatus()).isEqualTo("DEPLETED");

        service.receiveTransfer("F006", transfer.getId(), 99L);
        service.confirmTransfer("F006", transfer.getId(), 99L);
        FinishedGoodsBatch target = savedBatches.stream()
                .filter(batch -> batch != source)
                .findFirst()
                .orElseThrow();
        assertThat(target.getProductTypeId()).isEqualTo("CPF0060015");
        assertThat(target.getWarehouseId()).isEqualTo("WH-FINISHED");
        assertThat(target.getProducedQuantity()).isEqualByComparingTo("5");
        assertThat(target.getUnit()).isEqualTo("box");
        assertThat(source.getProducedQuantity().add(target.getProducedQuantity()))
                .isEqualByComparingTo("5");

        assertThatThrownBy(() -> service.confirmTransfer("F006", transfer.getId(), 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许确认");
        verify(finishedGoodsBatchRepository, never()).findAvailableBatchesByWarehouse(
                "F006", null, "WH-PRODUCTION");
    }

    @Test
    @DisplayName("M08: 成品误用 materialTypeId 在创建阶段返回明确 400")
    void createFinishedGoods_withMaterialIdentityOnly_rejectedBeforeDraft() {
        CreateTransferRequest.TransferItemDTO line = new CreateTransferRequest.TransferItemDTO();
        line.setItemType("FINISHED_GOODS");
        line.setMaterialTypeId("WRONG-MATERIAL-ID");
        line.setQuantity(BigDecimal.ONE);
        line.setUnit("box");
        CreateTransferRequest request = new CreateTransferRequest();
        request.setTransferType("WAREHOUSE_TO_WAREHOUSE");
        request.setTargetFactoryId("F006");
        request.setSourceWarehouseId("WH-PRODUCTION");
        request.setTargetWarehouseId("WH-FINISHED");
        request.setTransferDate(LocalDate.now());
        request.setItems(List.of(line));
        when(transferRepository.findRecentDuplicates(eq("F006"), eq("F006"), eq(99L), any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.createTransfer("F006", request, 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("productTypeId");
        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Fix#4: 同厂成品调拨 (生产仓→物流仓) — 源 shippedQuantity 不动 + 减 producedQuantity + 目标继承 unitCost + Σproduced 守恒")
    void intraFactoryFinishedGoodsTransfer_noShippedPollution_preservesUnitCost() {
        // 同厂: sourceFactoryId == targetFactoryId == F001 (生产仓 WH-WKS → 物流仓 WH-LOG 内部搬托)
        InternalTransferItem item = finishedGoodsItem(301L, "PT_001", new BigDecimal("10"));
        InternalTransfer t = new InternalTransfer();
        t.setId("T_FIX4_001");
        t.setTransferNumber("TR-FIX4-001");
        t.setSourceFactoryId("F001");
        t.setTargetFactoryId("F001");           // ← 同厂
        t.setSourceWarehouseId("WH-WKS");       // 生产仓
        t.setTargetWarehouseId("WH-LOG");       // 物流仓 (设置后 createTargetInventory 无需 warehouseResolver)
        t.setStatus(TransferStatus.APPROVED);
        t.setTransferType(TransferType.HQ_TO_BRANCH);
        item.setTransferId(t.getId());
        t.getItems().add(item);

        // 源成品批次: produced=100, shipped=0, unitCost=8.5 (成本血缘), 生产仓
        FinishedGoodsBatch src = finishedGoodsBatch("FG_SRC", new BigDecimal("100"));
        src.setProductTypeId("PT_001");
        src.setUnitCost(new BigDecimal("8.5000"));
        src.setUnit("件");

        when(transferRepository.findByIdAndEitherFactoryId("T_FIX4_001", "F001")).thenReturn(Optional.of(t));
        when(finishedGoodsBatchRepository.findAvailableBatchesByWarehouse("F001", "PT_001", "WH-WKS"))
                .thenReturn(List.of(src));
        when(finishedGoodsBatchRepository.findById("FG_SRC")).thenReturn(Optional.of(src));
        // 捕获 confirm 阶段创建的目标批次
        final List<FinishedGoodsBatch> saved = new ArrayList<>();
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // SHIP (扣源) → RECEIVE → CONFIRM (建目标)
        service.shipTransfer("F001", "T_FIX4_001", 99L);

        // 源: shippedQuantity 未动 (不虚增销售); producedQuantity 减 10
        assertThat(src.getShippedQuantity()).isEqualByComparingTo("0");
        assertThat(src.getProducedQuantity()).isEqualByComparingTo("90");
        assertThat(item.getSourceBatchId()).isEqualTo("FG_SRC");

        service.receiveTransfer("F001", "T_FIX4_001", 99L);
        service.confirmTransfer("F001", "T_FIX4_001", 99L);

        // 目标批次: 继承源 unitCost (成本血缘不断), producedQuantity=10
        FinishedGoodsBatch target = saved.stream()
                .filter(b -> !"FG_SRC".equals(b.getId()))
                .reduce((a, b) -> b)   // 最后创建的 = 目标
                .orElseThrow();
        assertThat(target.getUnitCost()).isNotNull();
        assertThat(target.getUnitCost()).isEqualByComparingTo("8.5000");
        assertThat(target.getProducedQuantity()).isEqualByComparingTo("10");
        assertThat(target.getWarehouseId()).isEqualTo("WH-LOG");

        // 工厂 Σproduced 守恒: 源 90 + 目标 10 = 100 (未因内部搬库 +10 膨胀)
        assertThat(src.getProducedQuantity().add(target.getProducedQuantity()))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("Fix#4: 跨厂成品调拨仍记 shippedQuantity (原行为不变)")
    void crossFactoryFinishedGoodsTransfer_stillBumpsShipped() {
        InternalTransferItem item = finishedGoodsItem(302L, "PT_001", new BigDecimal("10"));
        InternalTransfer t = buildTransfer("F001", "WH_FG", TransferStatus.APPROVED, item); // target=F002 (跨厂)

        FinishedGoodsBatch src = finishedGoodsBatch("FG_X", new BigDecimal("100"));
        src.setProductTypeId("PT_001");

        when(transferRepository.findByIdAndEitherFactoryId("T_B1_001", "F001")).thenReturn(Optional.of(t));
        when(finishedGoodsBatchRepository.findAvailableBatchesByWarehouse("F001", "PT_001", "WH_FG"))
                .thenReturn(List.of(src));
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));

        service.shipTransfer("F001", "T_B1_001", 99L);

        // 跨厂: 成品离厂 → shippedQuantity 记账 (produced 不动)
        assertThat(src.getShippedQuantity()).isEqualByComparingTo("10");
        assertThat(src.getProducedQuantity()).isEqualByComparingTo("100");
    }
}
