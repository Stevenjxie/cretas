package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.dto.production.ProductionTransitClearingRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionTransitLedger;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.ProductionTransitLedgerRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProductionPlan 六扇门结构化结单")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanSettlementTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-SETTLE-1";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProcessTaskRepository processTaskRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private BomService bomService;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;
    @Mock private ProductionSettlementLaborRepository productionSettlementLaborRepository;
    @Mock private SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Mock private ProductionTransitLedgerRepository productionTransitLedgerRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository, processTaskRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository, bomService);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", productionSettlementConsumptionRepository);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository", productionSettlementLaborRepository);
        ReflectionTestUtils.setField(service, "semiFinishedInventoryRepository", semiFinishedInventoryRepository);
        ReflectionTestUtils.setField(service, "productionTransitLedgerRepository", productionTransitLedgerRepository);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", bomRecipeRepository);
        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRecipeItemRepository);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("F006 旧 complete 入口必须提示走核对结单")
    void completeProduction_f006RequiresSettlement() {
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.completeProduction(FACTORY_ID, PLAN_ID, new BigDecimal("90")));

        assertEquals(409, ex.getCode());
        assertEquals("PRODUCTION_SETTLEMENT_REQUIRED", ex.getErrorCode());
        verify(productionBatchRepository, never()).findByFactoryIdAndProductionPlanId(any(), any());
    }

    @Test
    @DisplayName("超计划产量未选择原因时拒绝结单")
    void settleProduction_overPlanWithoutReason_rejected() {
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan()));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());

        ProductionSettlementRequest request = baseRequest();
        request.setActualFinishedQuantity(new BigDecimal("120"));
        request.setQuantityVarianceReason(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY_ID, PLAN_ID, request, 10L));

        assertEquals("PRODUCTION_OVER_PLAN_REASON_REQUIRED", ex.getErrorCode());
        verify(productionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("结单保存原料+半成品+人效并完成计划")
    void settleProduction_validRequest_savesSettlementAndCompletesPlan() {
        ProductionPlan plan = plan();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        MaterialBatch batch = materialBatch();
        SemiFinishedInventory wip = wip();
        when(materialBatchRepository.findByIdAndFactoryId("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(semiFinishedInventoryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(wip));
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn("WH-LOG-ID");
        stubCurrentBom("RM-1");
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(semiFinishedInventoryRepository.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementConsumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementLaborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        ProductionSettlementResponse response = service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        assertEquals(new BigDecimal("90"), plan.getActualQuantity());
        assertEquals("PENDING_WAREHOUSE_RECEIPT", response.getPostingStatus());
        assertEquals(new BigDecimal("90"), response.getActualFinishedQuantity());
        assertTrue(response.getWarnings().get(0).contains("仓库确认实收"));
        assertEquals(new BigDecimal("16"), batch.getUsedQuantity());
        assertEquals(new BigDecimal("5"), wip.getConsumedQuantity());
        assertEquals(new BigDecimal("3"), wip.getAvailableQuantity());
        verify(productionSettlementConsumptionRepository).saveAll(anyList());
        verify(productionSettlementLaborRepository).saveAll(anyList());
        verify(materialBatchRepository).save(batch);
        verify(semiFinishedInventoryRepository).save(wip);
    }

    @Test
    @DisplayName("结单原料批次不在原料仓时拒绝扣料")
    void settleProduction_rawBatchOutsideLogisticsWarehouse_rejected() {
        ProductionPlan plan = plan();
        MaterialBatch batch = materialBatch();
        batch.setWarehouseId("WH-WKS-ID");
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(materialBatchRepository.findByIdAndFactoryId("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn("WH-LOG-ID");
        stubCurrentBom("RM-1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L));

        assertEquals("PRODUCTION_RAW_WAREHOUSE_REQUIRED", ex.getErrorCode());
        verify(productionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("结单原料批次不属于当前 BOM 时拒绝扣料")
    void settleProduction_rawBatchNotInCurrentBom_rejected() {
        ProductionPlan plan = plan();
        MaterialBatch batch = materialBatch();
        batch.setMaterialTypeId("RM-NOT-IN-BOM");
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(materialBatchRepository.findByIdAndFactoryId("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn("WH-LOG-ID");
        stubCurrentBom("RM-1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L));

        assertEquals("PRODUCTION_CONSUMPTION_NOT_IN_BOM", ex.getErrorCode());
        verify(productionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("同一幂等键重复提交返回原结单")
    void settleProduction_sameIdempotency_returnsExistingSettlement() {
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan()));
        ProductionSettlement existing = new ProductionSettlement();
        existing.setId("settlement-1");
        existing.setFactoryId(FACTORY_ID);
        existing.setProductionPlanId(PLAN_ID);
        existing.setPlanNumber("P-001");
        existing.setPlannedQuantity(new BigDecimal("100"));
        existing.setActualFinishedQuantity(new BigDecimal("90"));
        existing.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        existing.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        existing.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.of(existing));

        ProductionSettlementResponse response = service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L);

        assertEquals("settlement-1", response.getSettlementId());
        assertTrue(response.getWarnings().get(0).contains("已提交过"));
        verify(productionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("仓库确认实收等于报产时生成成品库存且不挂中转账")
    void confirmWarehouseReceipt_exactMatch_postsFinishedGoodsOnly() {
        ProductionPlan plan = plan();
        ProductionSettlement settlement = settled();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "FG-P-001"))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findById("PT-1")).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId(FACTORY_ID)).thenReturn("WH-WKS-ID");
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> {
            FinishedGoodsBatch batch = inv.getArgument(0);
            batch.setId("fg-1");
            return batch;
        });
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductionWarehouseReceiptResponse response = service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receiptRequest("receipt-1", "90", "kg", null, null), 11L);

        assertEquals("POSTED", response.getPostingStatus());
        assertEquals("fg-1", response.getFinishedGoodsBatchId());
        assertEquals(null, response.getTransitLedgerId());
        assertEquals(new BigDecimal("90"), response.getWarehouseReceivedQuantity());
        verify(productionTransitLedgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("仓库实收短少超过10kg容差时生成中转挂账")
    void confirmWarehouseReceipt_shortBeyondTolerance_createsTransitLedger() {
        ProductionPlan plan = plan();
        ProductionSettlement settlement = settled();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "FG-P-001"))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findById("PT-1")).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId(FACTORY_ID)).thenReturn("WH-WKS-ID");
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> {
            FinishedGoodsBatch batch = inv.getArgument(0);
            batch.setId("fg-1");
            return batch;
        });
        when(productionTransitLedgerRepository.save(any(ProductionTransitLedger.class))).thenAnswer(inv -> {
            ProductionTransitLedger ledger = inv.getArgument(0);
            ledger.setId("ledger-1");
            return ledger;
        });
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductionWarehouseReceiptResponse response = service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receiptRequest("receipt-2", "70", "kg", "仓库实收短少", "WAREHOUSE"), 11L);

        assertEquals("PENDING_CLEARING", response.getPostingStatus());
        assertEquals("fg-1", response.getFinishedGoodsBatchId());
        assertEquals("ledger-1", response.getTransitLedgerId());
        assertEquals(new BigDecimal("20"), response.getVarianceQuantity());
        assertTrue(response.getWarnings().get(0).contains("中转挂账"));
    }

    @Test
    @DisplayName("仓库实收短少超过容差但责任侧待核对时拒绝确认")
    void confirmWarehouseReceipt_shortBeyondTolerancePendingResponsibility_rejected() {
        ProductionPlan plan = plan();
        ProductionSettlement settlement = settled();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmWarehouseReceipt(
                        FACTORY_ID, PLAN_ID,
                        receiptRequest("receipt-3", "70", "kg", "仓库实收短少", "PENDING"),
                        11L));

        assertEquals("PRODUCTION_RECEIPT_RESPONSIBILITY_REQUIRED", ex.getErrorCode());
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(productionTransitLedgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("中转挂账清账后结单状态回到已过账")
    void clearProductionTransitLedger_resolvesOpenLedgerAndPostsSettlement() {
        ProductionPlan plan = plan();
        ProductionSettlement settlement = settled();
        settlement.setPostingStatus("PENDING_CLEARING");
        ProductionTransitLedger ledger = new ProductionTransitLedger();
        ledger.setId("ledger-1");
        ledger.setFactoryId(FACTORY_ID);
        ledger.setSettlementId("settlement-1");
        ledger.setStatus("OPEN");
        ledger.setNote("仓库实收短少");
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));
        when(productionTransitLedgerRepository.findOpenByFactoryIdAndSettlementIdForUpdate(
                FACTORY_ID, "settlement-1", "OPEN")).thenReturn(Optional.of(ledger));
        when(productionTransitLedgerRepository.save(any(ProductionTransitLedger.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductionTransitClearingRequest request = new ProductionTransitClearingRequest("仓库侧已处理", "盘点后确认");

        ProductionWarehouseReceiptResponse response =
                service.clearProductionTransitLedger(FACTORY_ID, PLAN_ID, request, 12L);

        assertEquals("POSTED", response.getPostingStatus());
        assertEquals("RESOLVED", ledger.getStatus());
        assertEquals("POSTED", settlement.getPostingStatus());
        assertTrue(ledger.getNote().contains("仓库侧已处理"));
    }

    @Test
    @DisplayName("同一仓库确认幂等键重复提交返回原入库结果")
    void confirmWarehouseReceipt_sameIdempotencyAfterReceipt_returnsExisting() {
        ProductionPlan plan = plan();
        ProductionSettlement settlement = settled();
        settlement.setWarehouseReceivedAt(java.time.LocalDateTime.now());
        settlement.setWarehouseReceiptIdempotencyKey("receipt-1");
        settlement.setWarehouseReceivedQuantity(new BigDecimal("90"));
        settlement.setWarehouseVarianceQuantity(BigDecimal.ZERO);
        settlement.setPostingStatus("POSTED");
        settlement.setFinishedGoodsBatchId("fg-1");
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionWarehouseReceiptResponse response = service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receiptRequest("receipt-1", "90", "kg", null, null), 11L);

        assertEquals("POSTED", response.getPostingStatus());
        assertEquals("fg-1", response.getFinishedGoodsBatchId());
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(productionTransitLedgerRepository, never()).save(any());
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("P-001");
        plan.setProductTypeId("PT-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    private ProductionSettlementRequest baseRequest() {
        return ProductionSettlementRequest.builder()
                .idempotencyKey("idem-1")
                .actualFinishedQuantity(new BigDecimal("90"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId("MB-1")
                        .quantity(new BigDecimal("12"))
                        .unit("kg")
                        .build()))
                .semiFinishedConsumptions(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .semiFinishedInventoryId(7L)
                        .quantity(new BigDecimal("5"))
                        .unit("kg")
                        .build()))
                .laborSegments(List.of(ProductionSettlementRequest.LaborSegment.builder()
                        .workerName("operator")
                        .minutes(120)
                        .headcount(2)
                        .build()))
                .build();
    }

    private ProductionSettlement settled() {
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId("settlement-1");
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        settlement.setPlanNumber("P-001");
        settlement.setPlannedQuantity(new BigDecimal("100"));
        settlement.setActualFinishedQuantity(new BigDecimal("90"));
        settlement.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        settlement.setQuantityUnit("kg");
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        return settlement;
    }

    private ProductionWarehouseReceiptRequest receiptRequest(String key, String quantity, String unit,
                                                            String reason, String responsibilitySide) {
        ProductionWarehouseReceiptRequest request = new ProductionWarehouseReceiptRequest();
        request.setIdempotencyKey(key);
        request.setReceivedQuantity(new BigDecimal(quantity));
        request.setQuantityUnit(unit);
        request.setVarianceReason(reason);
        request.setResponsibilitySide(responsibilitySide);
        return request;
    }

    private MaterialBatch materialBatch() {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("B-001");
        batch.setMaterialTypeId("RM-1");
        batch.setReceiptQuantity(new BigDecimal("30"));
        batch.setUsedQuantity(new BigDecimal("4"));
        batch.setReservedQuantity(new BigDecimal("1"));
        batch.setQuantityUnit("kg");
        batch.setWarehouseId("WH-LOG-ID");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        return batch;
    }

    private void stubCurrentBom(String materialTypeId) {
        BomRecipe recipe = BomRecipe.builder()
                .id("bom-1")
                .factoryId(FACTORY_ID)
                .recipeCode("BOM-001")
                .productTypeId("PT-1")
                .productName("Product")
                .outputQuantityPerUnit(BigDecimal.ONE)
                .build();
        BomRecipeItem item = BomRecipeItem.builder()
                .recipeId("bom-1")
                .factoryId(FACTORY_ID)
                .materialTypeId(materialTypeId)
                .standardQuantity(BigDecimal.ONE)
                .unit("kg")
                .build();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY_ID, "PT-1"))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc("bom-1"))
                .thenReturn(List.of(item));
    }

    private SemiFinishedInventory wip() {
        return SemiFinishedInventory.builder()
                .id(7L)
                .factoryId(FACTORY_ID)
                .intermediateBatchNo("WIP-001")
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("8"))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
    }
}
