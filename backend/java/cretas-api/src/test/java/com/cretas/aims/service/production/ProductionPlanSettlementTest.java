package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
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
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

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
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private OrderCostBreakdownService orderCostBreakdownService;
    // R2 (2026-07-04): 结单族 process-row SFI/FG 投料扣减依赖 (其它测试不 set → null → deduct no-op, 零回归)
    @Mock private com.cretas.aims.repository.ProcessSheetRowRepository processSheetRowRepository;
    @Mock private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;
    @Mock private com.cretas.aims.service.inventory.FinishedGoodsFeedService finishedGoodsFeedService;

    private final com.fasterxml.jackson.databind.ObjectMapper r2Mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        ReflectionTestUtils.setField(service, "orderCostBreakdownService", orderCostBreakdownService);
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
        verify(inventoryLowStockEventPublisher).publishIfLowStock(FACTORY_ID, batch, "OUT");
        verify(applicationEventPublisher).publishEvent(argThat(event ->
                event instanceof com.cretas.aims.event.ProductionSettledEvent settled
                        && FACTORY_ID.equals(settled.getFactoryId())
                        && PLAN_ID.equals(settled.getPlanId())
                        && "PT-1".equals(settled.getProductTypeId())
                        && settled.getSettlementId() != null));
        // 结单族根修 (#1216↔#1217): plan() sourceType=null (非 SAFETY_STOCK) → 结单时给报工消耗行打戳,
        //   使 interimSettledAt IS NULL 统一 = 待扣减 (关单守卫/盘点减除据此对结单族不再误判)。
        verify(materialConsumptionRepository).stampInterimSettledForPlan(eq(FACTORY_ID), eq(PLAN_ID), any());
    }

    @Test
    @DisplayName("🔒🔒 Gap1: SAFETY_STOCK 存货计划走结单 → 400 拒绝 (须走小结; 结单的 postConsumptionToInventory 会再扣一次=原料双重扣减)")
    void settleProduction_safetyStockPlan_rejected() {
        // 存货生产 (SAFETY_STOCK) 逐批小结已扣 usedQuantity; 若放行结单, postConsumptionToInventory 再扣一次
        //   → 原料双重扣减 + 财务口径腐蚀。UI 隐藏结单按钮但 API 开放, 服务层必须锁死。此前只有 line 1619
        //   的防御性"不打戳"分支 (提前打戳会漏扣), 现在从前门直接 400 拒收, 该分支对 SAFETY_STOCK 不再可达。
        ProductionPlan plan = plan();
        plan.setSourceType(com.cretas.aims.entity.enums.PlanSourceType.SAFETY_STOCK);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L));

        assertEquals(400, ex.getCode(), "存货计划结单应 400");
        assertEquals("SAFETY_STOCK_MUST_INTERIM_SETTLE", ex.getErrorCode());
        // 关键: 越过守卫前就拒收 — 无任何扣减/入库/打戳副作用
        verify(productionSettlementRepository, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
        verify(materialConsumptionRepository, never()).stampInterimSettledForPlan(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────
    // R2 (2026-07-04): 结单族 process-row SFI/FG 投料严格扣减 (防 phantom 库存腐蚀)
    // ─────────────────────────────────────────────────────────────

    /** R2 wiring: 注入 process-row / SFI / FG 扣减依赖 + 结单保存链 stubs (plan 已 stub findByIdAndFactoryId)。 */
    private void wireR2(ProductionPlan plan, ProcessSheetRow... rows) {
        ReflectionTestUtils.setField(service, "processSheetRowRepository", processSheetRowRepository);
        ReflectionTestUtils.setField(service, "wipInventoryService", wipInventoryService);
        ReflectionTestUtils.setField(service, "finishedGoodsFeedService", finishedGoodsFeedService);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-r2")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementConsumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementLaborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID)).thenReturn(List.of(rows));
        // isCrossUnitPlan (validateSettlementRequest) → 空批次列表 → false (跳过超产裸比)
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(new java.util.ArrayList<>());
    }

    /** 结单请求: 无原料/半成品消耗行 (聚焦 process-row 投料扣减), finished 90 (<计划100, 无需超产原因)。 */
    private ProductionSettlementRequest r2Request(List<ProductionSettlementRequest.ConsumptionLine> semi) {
        return ProductionSettlementRequest.builder()
                .idempotencyKey("idem-r2")
                .actualFinishedQuantity(new BigDecimal("90"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(new java.util.ArrayList<>())
                .semiFinishedConsumptions(semi)
                .materialVarianceReason("无差异")        // 无原料消耗行时过 validateSettlementRequest
                .laborDeferredReason("工时稍后补录")       // 无工时段时过 validateSettlementRequest
                .laborSegments(new java.util.ArrayList<>())
                .build();
    }

    /** 一行 process-row: 单条 SFI 或 FG 投料 upstreamSource。 */
    private ProcessSheetRow feedRow(String srcBatchNo, String feedKg, boolean semi, boolean fg) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("r-" + srcBatchNo);
        req.setProcessCode("qidiao");
        req.setProcessOrder(9);
        req.setProductTypeId("PT-1");
        req.setFinished(fg);
        req.setOutputQuantity(new BigDecimal("10"));
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(srcBatchNo);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        ref.setSemiFinished(semi);
        ref.setFinishedGoods(fg);
        req.setUpstreamSources(List.of(ref));
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY_ID);
        row.setPlanId(PLAN_ID);
        row.setBatchId(555L);
        row.setBatchNumber("CLK-B-" + srcBatchNo);
        row.setRowStatus("SAVED");
        try {
            row.setRowPayload(r2Mapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return row;
    }

    @Test
    @DisplayName("R2 结单族: process-row 的 SFI/FG 投料被严格扣减 (防 phantom); prefill 留空的投料不再永不扣")
    void settleProduction_deductsProcessSheetSfiAndFgFeeds() {
        wireR2(plan(),
                feedRow("SFI-STOCK", "30", true, false),
                feedRow("FG-STOCK", "20", false, true));
        when(wipInventoryService.consumeClerkSemiStrict(eq(FACTORY_ID), eq("SFI-STOCK"), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(finishedGoodsFeedService.consumeForFeedStrict(eq(FACTORY_ID), eq("FG-STOCK"), any(), eq("kg")))
                .thenAnswer(inv -> inv.getArgument(2));

        // 无手工 SFI 领用 → 自动从 process-row 扣
        service.settleProduction(FACTORY_ID, PLAN_ID, r2Request(new java.util.ArrayList<>()), 10L);

        verify(wipInventoryService).consumeClerkSemiStrict(FACTORY_ID, "SFI-STOCK", new BigDecimal("30"));
        verify(finishedGoodsFeedService).consumeForFeedStrict(FACTORY_ID, "FG-STOCK", new BigDecimal("20"), "kg");
    }

    @Test
    @DisplayName("R2 防双扣: 结单请求带手工 SFI 领用 → 跳过 process-row SFI 自动扣 (FG 仍扣)")
    void settleProduction_manualSemiSkipsAutoSfiButStillFg() {
        wireR2(plan(),
                feedRow("SFI-STOCK", "30", true, false),
                feedRow("FG-STOCK", "20", false, true));
        when(finishedGoodsFeedService.consumeForFeedStrict(eq(FACTORY_ID), eq("FG-STOCK"), any(), eq("kg")))
                .thenAnswer(inv -> inv.getArgument(2));
        // 手工 SFI 领用行 (postSemiFinishedConsumption 走另一路径, 此处仅测跳过自动扣)
        SemiFinishedInventory wip = wip();
        when(semiFinishedInventoryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(wip));
        when(semiFinishedInventoryRepository.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        List<ProductionSettlementRequest.ConsumptionLine> manual = List.of(
                ProductionSettlementRequest.ConsumptionLine.builder()
                        .semiFinishedInventoryId(7L).quantity(new BigDecimal("5")).unit("kg").build());

        service.settleProduction(FACTORY_ID, PLAN_ID, r2Request(manual), 10L);

        verify(wipInventoryService, never()).consumeClerkSemiStrict(any(), any(), any());
        verify(finishedGoodsFeedService).consumeForFeedStrict(FACTORY_ID, "FG-STOCK", new BigDecimal("20"), "kg");
    }

    @Test
    @DisplayName("R2 守卫: SAFETY_STOCK 计划走结单不自动扣 process-row 投料 (扣减由小结完成, 防双扣)")
    void settleProduction_safetyStockDoesNotAutoDeductFeeds() {
        ProductionPlan plan = plan();
        plan.setSourceType(com.cretas.aims.entity.enums.PlanSourceType.SAFETY_STOCK);
        wireR2(plan, feedRow("SFI-STOCK", "30", true, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY_ID, PLAN_ID, r2Request(new java.util.ArrayList<>()), 10L));

        assertTrue(ex.getMessage().contains("小结"), "safety-stock settlement must direct users to batch settlement");
        verify(wipInventoryService, never()).consumeClerkSemiStrict(any(), any(), any());
        verify(finishedGoodsFeedService, never()).consumeForFeedStrict(any(), any(), any(), any());
    }

    @Test
    @DisplayName("结单原料批次在无关仓库 (成品仓/研发库) 时拒绝扣料")
    void settleProduction_rawBatchInUnrelatedWarehouse_rejected() {
        // 无关仓库 = 非原料仓/物流仓 (isRawOrLogisticsWarehouse=false) 且非生产仓 (isWorkshopWarehouse=false),
        // 如成品仓 (FINISHED) / 研发库 (RD)。结单闸仍须拒收, 防结单从无关仓库扣错料。
        ProductionPlan plan = plan();
        MaterialBatch batch = materialBatch();
        batch.setWarehouseId("WH-FG-ID");
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(materialBatchRepository.findByIdAndFactoryId("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn("WH-LOG-ID");
        when(warehouseResolver.isRawOrLogisticsWarehouse(FACTORY_ID, "WH-FG-ID")).thenReturn(false);
        when(warehouseResolver.isWorkshopWarehouse(FACTORY_ID, "WH-FG-ID")).thenReturn(false);
        stubCurrentBom("RM-1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L));

        assertEquals("PRODUCTION_RAW_WAREHOUSE_REQUIRED", ex.getErrorCode());
        verify(productionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("结单接受生产仓 (WORKSHOP/WH-WKS) 批次 — 领料→调拨→生产仓→报工→结单 料流打通")
    void settleProduction_workshopBatchFromRequisition_accepted() {
        // 料流对齐 (2026-07-03): 生产领料把原料从原料仓迁到生产仓 (WORKSHOP/WH-WKS) 后, 结单预填从报工
        //   materialBatchRefs 带入的正是该 WKS 批次。此前结单闸只认原料仓单一仓 → 自我 409 拒收 (预填产出被
        //   结单拒绝的矛盾)。现结单闸放行 WORKSHOP 类型仓批次, 与逐道报工闸 (ensureRawMaterialWarehouse) 对齐。
        ProductionPlan plan = plan();
        MaterialBatch batch = materialBatch();
        batch.setWarehouseId("WH-WKS-ID");
        SemiFinishedInventory wip = wip();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(materialBatchRepository.findByIdAndFactoryId("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(semiFinishedInventoryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(wip));
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn("WH-LOG-ID");
        // 生产仓批次: isRawOrLogisticsWarehouse=false, isWorkshopWarehouse=true → 放行。
        when(warehouseResolver.isRawOrLogisticsWarehouse(FACTORY_ID, "WH-WKS-ID")).thenReturn(false);
        when(warehouseResolver.isWorkshopWarehouse(FACTORY_ID, "WH-WKS-ID")).thenReturn(true);
        stubCurrentBom("RM-1");
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(semiFinishedInventoryRepository.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementConsumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementLaborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        ProductionSettlementResponse response = service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L);

        // 结单成功 (200, 非 409): 计划完成 + WKS 批次精确扣减 (4 已用 + 12 本次 = 16)。
        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        assertEquals(new BigDecimal("90"), response.getActualFinishedQuantity());
        assertEquals(new BigDecimal("16"), batch.getUsedQuantity());
        verify(materialBatchRepository).save(batch);
        // #1220 结单族打戳仍触发 (plan sourceType=null 非 SAFETY_STOCK)。
        verify(materialConsumptionRepository).stampInterimSettledForPlan(eq(FACTORY_ID), eq(PLAN_ID), any());
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
    @DisplayName("混 SKU 逐道预填原料按行级产品 BOM 校验")
    void settleProduction_mixedSkuRawLineUsesLineProductTypeBom() {
        ProductionPlan plan = plan();
        MaterialBatch batch = materialBatch();
        batch.setMaterialTypeId("RM-2");
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-mixed")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(materialBatchRepository.findByIdAndFactoryId("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID)).thenReturn(Optional.of(batch));
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn("WH-LOG-ID");
        stubCurrentBomFor("PT-1", "bom-1", "RM-1");
        stubCurrentBomFor("PT-2", "bom-2", "RM-2");
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementConsumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementLaborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        ProductionSettlementRequest request = ProductionSettlementRequest.builder()
                .idempotencyKey("idem-mixed")
                .actualFinishedQuantity(new BigDecimal("90"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .laborDeferredReason("工时稍后补录")
                .rawMaterialConsumptions(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId("MB-1")
                        .productTypeId("PT-2")
                        .quantity(new BigDecimal("12"))
                        .unit("kg")
                        .build()))
                .build();

        ProductionSettlementResponse response = service.settleProduction(FACTORY_ID, PLAN_ID, request, 10L);

        assertEquals("PENDING_WAREHOUSE_RECEIPT", response.getPostingStatus());
        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        assertEquals(new BigDecimal("16"), batch.getUsedQuantity());
        verify(productionSettlementRepository).save(any(ProductionSettlement.class));
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
    @DisplayName("🔴 结单族成本传导: 仓库确认实收成品 unitCost = 计划权威生产成本 / 入库量 (非 null)")
    void confirmWarehouseReceipt_propagatesUnitCost() {
        ProductionPlan plan = plan();
        ProductionSettlement settlement = settled();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "FG-P-001"))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findById("PT-1")).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId(FACTORY_ID)).thenReturn("WH-WKS-ID");
        // 权威生产成本 900, 入库 90 → unitCost = 900/90 = 10.0000 (与 SAFETY_STOCK 小结同基准)。
        when(orderCostBreakdownService.computeByPlan(FACTORY_ID, PLAN_ID, false))
                .thenReturn(OrderCostBreakdownDTO.builder()
                        .hasData(true).totalCost(new BigDecimal("900")).build());
        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        when(finishedGoodsBatchRepository.save(captor.capture())).thenAnswer(inv -> {
            FinishedGoodsBatch batch = inv.getArgument(0);
            batch.setId("fg-1");
            return batch;
        });
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receiptRequest("receipt-1", "90", "kg", null, null), 11L);

        FinishedGoodsBatch saved = captor.getValue();
        assertEquals(0, new BigDecimal("10.0000").compareTo(saved.getUnitCost()),
                "结单族成品应携带传导成本 10.0000, 而非 null");
    }

    @Test
    @DisplayName("🔴 结单族成本传导诚实 null: 计划无成本基准 (总成本≤0 / 全未定价) → unitCost null 不伪造 ¥0")
    void confirmWarehouseReceipt_honestNullWhenNoCostBasis() {
        ProductionPlan plan = plan();
        ProductionSettlement settlement = settled();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "FG-P-001"))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findById("PT-1")).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId(FACTORY_ID)).thenReturn("WH-WKS-ID");
        // 权威成本 0 (全部投入未定价 / 无批次) → 诚实 null, 期末 COGS 结转 honest-null 排除。
        when(orderCostBreakdownService.computeByPlan(FACTORY_ID, PLAN_ID, false))
                .thenReturn(OrderCostBreakdownDTO.builder()
                        .hasData(true).totalCost(BigDecimal.ZERO).build());
        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        when(finishedGoodsBatchRepository.save(captor.capture())).thenAnswer(inv -> {
            FinishedGoodsBatch batch = inv.getArgument(0);
            batch.setId("fg-1");
            return batch;
        });
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receiptRequest("receipt-1", "90", "kg", null, null), 11L);

        assertNull(captor.getValue().getUnitCost(), "无成本基准时 unitCost 必须诚实 null, 不伪造 ¥0");
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
        stubCurrentBomFor("PT-1", "bom-1", materialTypeId);
    }

    private void stubCurrentBomFor(String productTypeId, String recipeId, String materialTypeId) {
        BomRecipe recipe = BomRecipe.builder()
                .id(recipeId)
                .factoryId(FACTORY_ID)
                .recipeCode("BOM-" + productTypeId)
                .productTypeId(productTypeId)
                .productName("Product")
                .outputQuantityPerUnit(BigDecimal.ONE)
                .build();
        BomRecipeItem item = BomRecipeItem.builder()
                .recipeId(recipeId)
                .factoryId(FACTORY_ID)
                .materialTypeId(materialTypeId)
                .standardQuantity(BigDecimal.ONE)
                .unit("kg")
                .build();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY_ID, productTypeId))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(recipeId))
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
