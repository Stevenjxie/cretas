package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.production.ProductionMaterialReturn;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FactoryMaterialRequisition transfer (physical WH-LOG→WH-WKS move) and returns")
class FactoryMaterialRequisitionTransferIntegrationTest {

    private static final String FACTORY_ID = "F001";
    private static final String MR_ID = "mr-001";
    private static final String PLAN_ID = "plan-001";
    private static final String WH_LOGISTICS = "wh-logistics";
    private static final String WH_WORKSHOP = "wh-workshop";
    private static final Long OPERATOR = 99L;

    @Mock
    private FactoryMaterialRequisitionRepository repository;
    @Mock
    private FactoryMaterialRequisitionItemRepository itemRepository;
    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private BomRecipeItemRepository bomItemRepository;
    @Mock
    private TransferService transferService;
    @Mock
    private FactoryWarehouseRepository warehouseRepository;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock
    private ProductionMaterialReturnRepository productionMaterialReturnRepository;

    @InjectMocks
    private FactoryMaterialRequisitionServiceImpl service;

    /** Simulated persistent batch store so we can assert both-warehouse quantities after a move. */
    private final Map<String, MaterialBatch> batchStore = new HashMap<>();

    @BeforeEach
    void setup() {
        batchStore.clear();
        ProductionPlan defaultPlan = new ProductionPlan();
        defaultPlan.setId(PLAN_ID);
        defaultPlan.setFactoryId(FACTORY_ID);
        defaultPlan.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        lenient().when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(defaultPlan));
        InternalTransfer stubTransfer = new InternalTransfer();
        stubTransfer.setId("tr-stub-1");
        lenient().when(transferService.createTransfer(any(), any(), any())).thenReturn(stubTransfer);
        lenient().when(materialBatchRepository.findByIdAndFactoryIdForUpdate(any(), eq(FACTORY_ID)))
                .thenAnswer(inv -> Optional.ofNullable(batchStore.get(inv.getArgument(0, String.class))));
        lenient().when(materialBatchRepository.save(any())).thenAnswer(inv -> {
            MaterialBatch b = inv.getArgument(0);
            batchStore.put(b.getId(), b);
            return b;
        });
        lenient().when(materialConsumptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productionMaterialReturnRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== transferToFactory: physical move ====================

    @Test
    @DisplayName("transferToFactory relocates picked stock: WH-LOG down, WH-WKS batch created, valid enum")
    void transferToFactory_movesStockToWorkshop() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "0.00", "3.50"));
        batchStore.put("batch-2", batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00"));

        FactoryMaterialRequisition mr = buildMrInPicking();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR);

        // status + issued
        assertEquals(Status.TRANSFERRED, mr.getStatus());
        assertEquals(new BigDecimal("10.00"), mr.getItems().get(0).getIssuedQty());

        // WH-LOG source deducted by moved qty
        assertEquals(new BigDecimal("10.00"), batchStore.get("batch-1").getUsedQuantity());
        assertEquals(new BigDecimal("0.00"), batchStore.get("batch-1").getCurrentQuantity());
        assertEquals(MaterialBatchStatus.DEPLETED, batchStore.get("batch-1").getStatus());
        assertEquals(new BigDecimal("0.50"), batchStore.get("batch-2").getUsedQuantity());

        // A new WH-WKS batch exists for each material, price/unit preserved
        MaterialBatch wks1 = findWorkshopBatch("MAT-001");
        assertNotNull(wks1, "workshop batch for MAT-001 must be created");
        assertEquals(WH_WORKSHOP, wks1.getWarehouseId());
        assertEquals(new BigDecimal("10.00"), wks1.getReceiptQuantity());
        assertEquals(new BigDecimal("10.00"), wks1.getCurrentQuantity());
        assertEquals(new BigDecimal("3.50"), wks1.getUnitPrice());
        assertEquals("kg", wks1.getQuantityUnit());
        MaterialBatch wks2 = findWorkshopBatch("MAT-002");
        assertNotNull(wks2);
        assertEquals(new BigDecimal("0.50"), wks2.getReceiptQuantity());

        // workshopBatchId recorded on the item batch rows (for reversal)
        Object recorded = mr.getItems().get(0).getBatchNumbers().get(0).get("workshopBatchId");
        assertNotNull(recorded);
        assertEquals(wks1.getId(), recorded);

        // 🔒🔒 bug #3 双扣防呆: 领料只做物理迁移, 绝不再创建一张可被仓管「走完」的备料调出 InternalTransfer.
        // 若创建 DRAFT transfer, 仓管 提交→审批→发货 会二次 deductSourceInventory (FEFO 扣真实库存) +
        // 签收再建生产仓批次 = 原料双扣 + 生产仓重复批次, 全程 HTTP 200 零拦截 (#1177 后被触发).
        verify(transferService, never()).createTransfer(any(), any(), any());
        assertNull(mr.getOutboundTransferId(), "领料迁移不得留下可走完的备料调出单");
        // 源仓恰好扣一次 (batch-1 used=10, 上方已断言), 生产仓 MAT-001 恰好一张新批次 — 无重复入库
        long mat001WorkshopBatches = batchStore.values().stream()
                .filter(b -> WH_WORKSHOP.equals(b.getWarehouseId()))
                .filter(b -> "MAT-001".equals(b.getMaterialTypeId()))
                .count();
        assertEquals(1L, mat001WorkshopBatches, "MAT-001 生产仓批次必须恰好一张 (无重复入库)");
    }

    @Test
    @DisplayName("transferToFactory is idempotent: re-move skips rows already carrying workshopBatchId")
    void transferToFactory_idempotentOnAlreadyMovedRows() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        batchStore.put("batch-2", batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00"));
        MaterialBatch existingWks = batch("wks-existing", "MAT-001", WH_WORKSHOP, "10.00", "0.00", "3.50");
        batchStore.put("wks-existing", existingWks);

        FactoryMaterialRequisition mr = buildMrInPicking();
        // it-1 already moved (row carries workshopBatchId)
        Map<String, Object> movedRow = new LinkedHashMap<>();
        movedRow.put("batchId", "batch-1");
        movedRow.put("qty", "10.00");
        movedRow.put("workshopBatchId", "wks-existing");
        mr.getItems().get(0).setBatchNumbers(new ArrayList<>(List.of(movedRow)));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR);

        // batch-1 must NOT be deducted again (still fully used = 10, no double move)
        assertEquals(new BigDecimal("10.00"), batchStore.get("batch-1").getUsedQuantity());
        // it-2 still moved normally
        assertEquals(new BigDecimal("0.50"), batchStore.get("batch-2").getUsedQuantity());
        assertEquals("wks-existing", mr.getItems().get(0).getBatchNumbers().get(0).get("workshopBatchId"));
    }

    @Test
    @DisplayName("transferToFactory blocks when source stock is insufficient (honest, no partial move)")
    void transferToFactory_insufficientStock_blocks() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "9.00", "3.50")); // avail 1 < 10
        batchStore.put("batch-2", batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00"));
        FactoryMaterialRequisition mr = buildMrInPicking();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR));
        assertTrue(ex.getMessage().contains("不足"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("transferToFactory blocks (no false success) when picking was never confirmed")
    void transferToFactory_withoutConfirmedPicking_blocks() {
        FactoryMaterialRequisition mr = buildMrInPicking();
        // 仓管跳过「确认领料」直接点调拨: picked_qty 全为 null
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            it.setPickedQty(null);
            it.setBatchNumbers(new ArrayList<>());
        }
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR));
        assertTrue(ex.getMessage().contains("确认领料"), "message must tell 仓管 to confirm picking first");
        // no state change, no phantom success
        assertEquals(Status.PICKING, mr.getStatus());
        verify(repository, never()).save(any());
        verify(transferService, never()).createTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("transferToFactory auto-allocates batches (FEFO) when 仓管 only entered picked qty, then moves stock")
    void transferToFactory_autoAllocatesBatchesWhenMissing_movesStock() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "0.00", "3.50"));
        batchStore.put("batch-2", batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00"));

        FactoryMaterialRequisition mr = buildMrInPicking();
        // confirm-picking 只录了数量 (picked_qty 已存在), 未选批次 → batch_numbers 空
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            it.setBatchNumbers(new ArrayList<>());
        }
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 系统按 FEFO 从原料仓自动分配领料批次
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(FACTORY_ID, "MAT-001", WH_LOGISTICS))
                .thenReturn(List.of(batchStore.get("batch-1")));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(FACTORY_ID, "MAT-002", WH_LOGISTICS))
                .thenReturn(List.of(batchStore.get("batch-2")));

        service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR);

        assertEquals(Status.TRANSFERRED, mr.getStatus());
        // WH-LOG source deducted (auto-picked batch划出)
        assertEquals(new BigDecimal("10.00"), batchStore.get("batch-1").getUsedQuantity());
        assertEquals(MaterialBatchStatus.DEPLETED, batchStore.get("batch-1").getStatus());
        assertEquals(new BigDecimal("0.50"), batchStore.get("batch-2").getUsedQuantity());
        // WH-WKS batch created for the auto-allocated material
        MaterialBatch wks1 = findWorkshopBatch("MAT-001");
        assertNotNull(wks1, "workshop batch created from auto-allocated picking");
        assertEquals(WH_WORKSHOP, wks1.getWarehouseId());
        assertEquals(new BigDecimal("10.00"), wks1.getReceiptQuantity());
        // the auto-allocated batchId is recorded on the item rows (for downstream reversal)
        assertEquals("batch-1", mr.getItems().get(0).getBatchNumbers().get(0).get("batchId"));
        assertNotNull(mr.getItems().get(0).getBatchNumbers().get(0).get("workshopBatchId"));
    }

    @Test
    @DisplayName("transferToFactory auto-allocation blocks honestly when raw stock cannot cover picked qty")
    void transferToFactory_autoAllocationInsufficient_blocks() {
        // only 3 available for MAT-001 but picked 10
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "7.00", "3.50"));
        FactoryMaterialRequisition mr = buildMrInPicking();
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            it.setBatchNumbers(new ArrayList<>());
        }
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(FACTORY_ID, "MAT-001", WH_LOGISTICS))
                .thenReturn(List.of(batchStore.get("batch-1")));
        lenient().when(materialBatchRepository.findAvailableBatchesFEFO(FACTORY_ID, "MAT-001"))
                .thenReturn(List.of(batchStore.get("batch-1")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR));
        assertTrue(ex.getMessage().contains("不足"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("factory-supplied plan rejects manually selected customer-owned stock")
    void transferToFactory_factoryPlanRejectsCustomerOwnedBatch() {
        MaterialBatch customerBatch = batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "0.00", "3.50");
        customerBatch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        customerBatch.setOwnerCustomerId("customer-1");
        customerBatch.setSourceSalesOrderId("so-1");
        batchStore.put("batch-1", customerBatch);
        batchStore.put("batch-2", batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00"));
        FactoryMaterialRequisition mr = buildMrInPicking();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR));

        assertEquals("PRODUCTION_REQUISITION_BATCH_OWNERSHIP_MISMATCH", ex.getErrorCode());
        assertEquals(new BigDecimal("0.00"), customerBatch.getUsedQuantity());
    }

    @Test
    @DisplayName("customer-supplied plan uses only matching order stock and preserves lineage in workshop")
    void transferToFactory_customerPlanPreservesOwnershipAndLineage() {
        ProductionPlan customerPlan = new ProductionPlan();
        customerPlan.setId(PLAN_ID);
        customerPlan.setFactoryId(FACTORY_ID);
        customerPlan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        customerPlan.setCustomerId("customer-1");
        customerPlan.setSourceOrderId("so-1");
        customerPlan.setSourceOrderItemId("so-item-1");
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(customerPlan));

        MaterialBatch customerBatch = batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "0.00", "3.50");
        customerBatch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        customerBatch.setOwnerCustomerId("customer-1");
        customerBatch.setSourceSalesOrderId("so-1");
        customerBatch.setSourceSalesOrderItemId("so-item-1");
        batchStore.put("batch-1", customerBatch);
        MaterialBatch customerBatch2 = batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00");
        customerBatch2.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        customerBatch2.setOwnerCustomerId("customer-1");
        customerBatch2.setSourceSalesOrderId("so-1");
        customerBatch2.setSourceSalesOrderItemId("so-item-1");
        batchStore.put("batch-2", customerBatch2);
        FactoryMaterialRequisition mr = buildMrInPicking();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR);

        MaterialBatch workshop = findWorkshopBatch("MAT-001");
        assertEquals(InventoryOwnership.CUSTOMER_OWNED, workshop.getOwnership());
        assertEquals("customer-1", workshop.getOwnerCustomerId());
        assertEquals("so-1", workshop.getSourceSalesOrderId());
        assertEquals("so-item-1", workshop.getSourceSalesOrderItemId());
    }

    // ==================== close: return + workshop drawdown ====================

    @Test
    @DisplayName("close restores WH-LOG and draws down WH-WKS batch, keeping inventory balanced")
    void close_withReturnedQty_balancesBothWarehouses() {
        // Post-transfer state: LOG source fully issued out (used 10), WKS batch received 10, prod consumed 8
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "8.00", "3.50"));

        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        assertEquals(new BigDecimal("2.00"), mr.getItems().get(0).getReturnedQty());

        // WH-LOG restored by returned (10 - 2 = 8)
        assertEquals(new BigDecimal("8.00"), batchStore.get("batch-1").getUsedQuantity());
        // WH-WKS drawn down by returned (used 8 → 10, current 0) — no phantom stock left
        assertEquals(new BigDecimal("10.00"), batchStore.get("wks-1").getUsedQuantity());
        assertEquals(new BigDecimal("0.00"), batchStore.get("wks-1").getCurrentQuantity());

        // 🔒🔒 bug #3 同因: 退料只做物理回库 (executeMaterialReturn 减回原料仓 + drawDown 划平生产仓),
        // 不再创建一张可被走完的退料调入 InternalTransfer (否则退料被处理两遍).
        verify(transferService, never()).createTransfer(any(), any(), any());
        assertNull(mr.getReturnTransferId(), "退料回库不得留下可走完的退料调入单");

        // trace + return records preserved
        ArgumentCaptor<MaterialConsumption> consumptionCaptor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(materialConsumptionRepository).save(consumptionCaptor.capture());
        assertEquals(new BigDecimal("-2.00"), consumptionCaptor.getValue().getQuantity());
        assertEquals("MATERIAL_RETURN", consumptionCaptor.getValue().getSourceType());
        assertEquals("batch-1", consumptionCaptor.getValue().getBatchId());

        ArgumentCaptor<ProductionMaterialReturn> returnCaptor = ArgumentCaptor.forClass(ProductionMaterialReturn.class);
        verify(productionMaterialReturnRepository).save(returnCaptor.capture());
        assertEquals(ProductionMaterialReturn.ReturnStatus.EXECUTED, returnCaptor.getValue().getReturnStatus());
        assertEquals(new BigDecimal("2.00"), returnCaptor.getValue().getReturnQuantity());
    }

    @Test
    @DisplayName("bug #1: close derives return from WKS balance (issued 100, consumed 60 → return EXACTLY 40, no phantom) even with consumedQty unset")
    void close_derivesReturnFromWorkshopBalance_noPhantom() {
        // 领料 100 → 调拨 (WKS 建批 100) → 报工/小结 consume 60 (WKS used 60 → remaining 40) → 关单 wastage 0。
        batchStore.put("raw-1", batch("raw-1", "MAT-001", WH_LOGISTICS, "100.00", "100.00", "3.00"));
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "100.00", "60.00", "3.00"));

        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setId(MR_ID);
        mr.setFactoryId(FACTORY_ID);
        mr.setProductionPlanId(PLAN_ID);
        mr.setStatus(Status.ISSUED);
        mr.setSourceWarehouseId(WH_LOGISTICS);
        mr.setTargetWarehouseId(WH_WORKSHOP);
        FactoryMaterialRequisitionItem it = new FactoryMaterialRequisitionItem();
        it.setId("it-1");
        it.setRequisition(mr);
        it.setMaterialTypeId("MAT-001");
        it.setMaterialName("Material A");
        it.setUnit("kg");
        it.setIssuedQty(new BigDecimal("100.00"));
        it.setConsumedQty(null); // dead field, deliberately unset — proves fix does not rely on it
        it.setBatchNumbers(new ArrayList<>(List.of(mutableRow("raw-1", "100.00", "wks-1"))));
        List<FactoryMaterialRequisitionItem> items = new ArrayList<>();
        items.add(it);
        mr.setItems(items);

        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        // returned = WKS remaining (40) − wastage (0) = 40 (NOT the buggy full issued 100)
        assertEquals(new BigDecimal("40.00"), mr.getItems().get(0).getReturnedQty());
        // consumed derived from physical WKS balance (100 − 40) and written back into the dead field
        assertEquals(new BigDecimal("60.00"), mr.getItems().get(0).getConsumedQty());
        // WH-RAW restored by EXACTLY 40 (used 100 → 60) — NO phantom +40
        assertEquals(new BigDecimal("60.00"), batchStore.get("raw-1").getUsedQuantity());
        // WKS drawn to zero (used 60 → 100, current 0)
        assertEquals(new BigDecimal("0.00"), batchStore.get("wks-1").getCurrentQuantity());

        // return trace = -40 against the raw source batch
        ArgumentCaptor<MaterialConsumption> consumptionCaptor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(materialConsumptionRepository).save(consumptionCaptor.capture());
        assertEquals(new BigDecimal("-40.00"), consumptionCaptor.getValue().getQuantity());
        assertEquals("raw-1", consumptionCaptor.getValue().getBatchId());
    }

    @Test
    @DisplayName("close draws down WH-WKS by returned + wastage so the workshop batch zeroes out")
    void close_wastage_alsoDrawsDownWorkshop() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        // WKS received 10, prod consumed 8 → 2 remaining = returned(1.25) + wastage(0.75)
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "8.00", "3.50"));

        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR,
                List.of(Map.of("itemId", "it-1", "wastageQty", "0.75")));

        assertEquals(new BigDecimal("0.75"), mr.getItems().get(0).getWastageQty());
        assertEquals(new BigDecimal("1.25"), mr.getItems().get(0).getReturnedQty());
        // WKS drawn by returned(1.25) + wastage(0.75) = 2.0 → used 8 → 10, current 0
        assertEquals(new BigDecimal("0.00"), batchStore.get("wks-1").getCurrentQuantity());
        // LOG restored by returned only (10 - 1.25 = 8.75)
        assertEquals(new BigDecimal("8.75"), batchStore.get("batch-1").getUsedQuantity());
    }

    @Test
    @DisplayName("close rejects negative returns when wastage exceeds the WKS unconsumed remainder")
    void close_shouldRejectWhenConsumedAndWastageExceedIssued() {
        // WKS received 10, prod consumed 9.5 → remaining 0.5; wastage 1.0 > 0.5 → returned -0.5 → honest-fail.
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "9.50", "3.50"));
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("9.50"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));

        assertThrows(BusinessException.class, () -> service.close(FACTORY_ID, MR_ID, OPERATOR,
                List.of(Map.of("itemId", "it-1", "wastageQty", "1.00"))));

        verify(repository, never()).save(any());
        verify(transferService, never()).createTransfer(any(), any(), any());
        verify(materialConsumptionRepository, never()).save(any());
        verify(productionMaterialReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("close is fail-closed when material return persistence fails")
    void close_shouldPropagateMaterialReturnPersistenceFailure() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "8.00", "3.50"));
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        doThrow(new RuntimeException("return ledger write failed"))
                .when(productionMaterialReturnRepository).save(any(ProductionMaterialReturn.class));

        assertThrows(RuntimeException.class, () -> service.close(FACTORY_ID, MR_ID, OPERATOR, List.of()));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("close without returned material does not create return transfer")
    void close_withoutReturnedQty_shouldNotCreateTransfer() {
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", "wks-2");
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        verify(transferService, never()).createTransfer(any(), any(), any());
        verify(materialBatchRepository, never()).save(any());
        verify(materialConsumptionRepository, never()).save(any());
        verify(productionMaterialReturnRepository, never()).save(any());
        assertNull(mr.getReturnTransferId());
    }

    // ==================== close-before-settle guard (🔴🔒🔒 phantom + 409-deadlock) ====================

    @Test
    @DisplayName("bug: close BEFORE settle is loud-blocked (unsettled 报工 consumption on WKS batch) — no phantom return, no 409-deadlock")
    void close_beforeSettle_isBlocked_noPhantom() {
        // Deferred-deduction: 领料 issue 10 → 报工 consume 6 (MaterialConsumption unposted, batchId=wks-1),
        // but WKS still used=0/current=10 pre-settle (deduction not yet applied). Closing now would
        // (bug) return full 10 to raw (+6 phantom) + drain WKS, then settle would 409 forever.
        batchStore.put("raw-1", batch("raw-1", "MAT-001", WH_LOGISTICS, "100.00", "10.00", "3.00"));
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "0.00", "3.00"));

        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setId(MR_ID);
        mr.setFactoryId(FACTORY_ID);
        mr.setProductionPlanId(PLAN_ID);
        mr.setStatus(Status.ISSUED);
        mr.setSourceWarehouseId(WH_LOGISTICS);
        mr.setTargetWarehouseId(WH_WORKSHOP);
        FactoryMaterialRequisitionItem it = new FactoryMaterialRequisitionItem();
        it.setId("it-1");
        it.setRequisition(mr);
        it.setMaterialTypeId("MAT-001");
        it.setMaterialName("Material A");
        it.setUnit("kg");
        it.setIssuedQty(new BigDecimal("10.00"));
        it.setBatchNumbers(new ArrayList<>(List.of(mutableRow("raw-1", "10.00", "wks-1"))));
        List<FactoryMaterialRequisitionItem> items = new ArrayList<>();
        items.add(it);
        mr.setItems(items);

        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        // 存货生产 (SAFETY_STOCK) 计划 — 唯一有「小结-待结算」窗口的计划族, 守卫才对其生效。
        stubPlanSourceType(PlanSourceType.SAFETY_STOCK);
        // 1 unsettled 报工 consumption still references the WKS batch → close must be blocked.
        when(materialConsumptionRepository.countUnsettledConsumptionByBatchIds(FACTORY_ID, List.of("wks-1")))
                .thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.close(FACTORY_ID, MR_ID, OPERATOR, List.of()));
        assertTrue(ex.getMessage().contains("未小结"), "message must tell 仓管 to settle first: " + ex.getMessage());
        assertEquals("PRODUCTION_REQUISITION_CLOSE_BEFORE_SETTLE", ex.getErrorCode());

        // No state change: status stays ISSUED, no phantom return, no batch mutation, no return ledger.
        assertEquals(Status.ISSUED, mr.getStatus());
        assertEquals(new BigDecimal("10.00"), batchStore.get("raw-1").getUsedQuantity()); // NOT restored (no phantom +10)
        assertEquals(new BigDecimal("10.00"), batchStore.get("wks-1").getCurrentQuantity()); // NOT drained
        verify(repository, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
        verify(materialConsumptionRepository, never()).save(any());
        verify(productionMaterialReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("settled-then-close still works: guard queries WKS batch ids, count 0 → close proceeds (no regression on #1202 good path)")
    void close_afterSettle_stillWorks() {
        // Post-settle: WKS used=8 (deduction landed), current=2 → returned = 2 − 0 = 2 (correct #1202 path).
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "8.00", "3.50"));

        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 存货生产计划 → 守卫生效; all consumption already settled → 0 unsettled on the WKS batch.
        stubPlanSourceType(PlanSourceType.SAFETY_STOCK);
        when(materialConsumptionRepository.countUnsettledConsumptionByBatchIds(FACTORY_ID, List.of("wks-1")))
                .thenReturn(0L);

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        // guard consulted with the WKS batch ids; close proceeded down the correct post-settle path.
        verify(materialConsumptionRepository).countUnsettledConsumptionByBatchIds(FACTORY_ID, List.of("wks-1"));
        assertEquals(Status.CLOSED, mr.getStatus());
        assertEquals(new BigDecimal("2.00"), mr.getItems().get(0).getReturnedQty());
        assertEquals(new BigDecimal("0.00"), batchStore.get("wks-1").getCurrentQuantity());
    }

    @Test
    @DisplayName("F1 narrow (#1215 over-block fix): 结单-family plan (CUSTOMER_ORDER) with unsettled MC → close PROCEEDS, guard never consulted (not permanently stuck)")
    void close_nonStockPlan_notBlocked_guardGatedToSafetyStock() {
        // 结单-family: consumption is deducted immediately at 结单 (WKS used=8/current=2), interimSettledAt
        // is NEVER stamped for this family → its MC rows stay interimSettledAt IS NULL forever. If the guard
        // fired on family-agnostic `interimSettledAt IS NULL`, this requisition would be stuck ISSUED forever
        // (its 小结 path 400s: 仅存货生产可小结). The narrowed guard must NOT fire for non-SAFETY_STOCK plans.
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "8.00", "3.50"));

        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 结单-family plan → guard gated out entirely.
        stubPlanSourceType(PlanSourceType.CUSTOMER_ORDER);

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        // Close proceeded (WKS current already reflects consumption at 结单 → returned = 2), and the
        // unsettled-count guard was NEVER consulted for a 结单-family plan.
        assertEquals(Status.CLOSED, mr.getStatus());
        assertEquals(new BigDecimal("2.00"), mr.getItems().get(0).getReturnedQty());
        verify(materialConsumptionRepository, never()).countUnsettledConsumptionByBatchIds(any(), any());
    }

    // ==================== builders ====================

    /** 存根本单关联生产计划的来源类型 (F1 守卫的计划族门控)。 */
    private void stubPlanSourceType(PlanSourceType sourceType) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setSourceType(sourceType);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
    }

    private FactoryMaterialRequisition buildMrInPicking() {
        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setId(MR_ID);
        mr.setFactoryId(FACTORY_ID);
        mr.setProductionPlanId(PLAN_ID);
        mr.setRequisitionNo("MR-20260411-0001");
        mr.setStatus(Status.PICKING);
        mr.setSourceWarehouseId(WH_LOGISTICS);
        mr.setTargetWarehouseId(WH_WORKSHOP);

        FactoryMaterialRequisitionItem it1 = new FactoryMaterialRequisitionItem();
        it1.setId("it-1");
        it1.setRequisition(mr);
        it1.setMaterialTypeId("MAT-001");
        it1.setMaterialName("Material A");
        it1.setPickedQty(new BigDecimal("10.00"));
        it1.setUnit("kg");
        it1.setBatchNumbers(new ArrayList<>(List.of(mutableRow("batch-1", "10.00", null))));

        FactoryMaterialRequisitionItem it2 = new FactoryMaterialRequisitionItem();
        it2.setId("it-2");
        it2.setRequisition(mr);
        it2.setMaterialTypeId("MAT-002");
        it2.setMaterialName("Material B");
        it2.setPickedQty(new BigDecimal("0.50"));
        it2.setUnit("kg");
        it2.setBatchNumbers(new ArrayList<>(List.of(mutableRow("batch-2", "0.50", null))));

        List<FactoryMaterialRequisitionItem> items = new ArrayList<>();
        items.add(it1);
        items.add(it2);
        mr.setItems(items);
        return mr;
    }

    private FactoryMaterialRequisition buildMrInIssued(BigDecimal it1Issued, BigDecimal it1Consumed,
                                                       BigDecimal it2Issued, BigDecimal it2Consumed,
                                                       String wks1Id, String wks2Id) {
        FactoryMaterialRequisition mr = buildMrInPicking();
        mr.setStatus(Status.ISSUED);
        mr.getItems().get(0).setIssuedQty(it1Issued);
        mr.getItems().get(0).setConsumedQty(it1Consumed);
        mr.getItems().get(0).setBatchNumbers(new ArrayList<>(List.of(mutableRow("batch-1", "10.00", wks1Id))));
        mr.getItems().get(1).setIssuedQty(it2Issued);
        mr.getItems().get(1).setConsumedQty(it2Consumed);
        mr.getItems().get(1).setBatchNumbers(new ArrayList<>(List.of(mutableRow("batch-2", "0.50", wks2Id))));
        return mr;
    }

    private Map<String, Object> mutableRow(String batchId, String qty, String workshopBatchId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", batchId);
        row.put("qty", qty);
        if (workshopBatchId != null) {
            row.put("workshopBatchId", workshopBatchId);
        }
        return row;
    }

    private MaterialBatch findWorkshopBatch(String materialTypeId) {
        return batchStore.values().stream()
                .filter(b -> WH_WORKSHOP.equals(b.getWarehouseId()))
                .filter(b -> materialTypeId.equals(b.getMaterialTypeId()))
                .filter(b -> !b.getId().startsWith("wks-existing"))
                .findFirst()
                .orElse(null);
    }

    private MaterialBatch batch(String id, String materialTypeId, String warehouseId,
                                String receiptQty, String usedQty, String unitPrice) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY_ID);
        batch.setMaterialTypeId(materialTypeId);
        batch.setBatchNumber(id + "-no");
        batch.setWarehouseId(warehouseId);
        batch.setReceiptDate(LocalDate.now());
        batch.setReceiptQuantity(new BigDecimal(receiptQty));
        batch.setUsedQuantity(new BigDecimal(usedQty));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setUnitPrice(new BigDecimal(unitPrice));
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(OPERATOR);
        return batch;
    }
}
