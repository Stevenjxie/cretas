package com.cretas.aims.service.wip.impl;

import com.cretas.aims.dto.yield.OutputOptionsResponse;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WipInventoryServiceImpl")
class WipInventoryServiceImplTest {

    private static final String FACTORY_ID = "F001";

    @Mock
    private SemiFinishedInventoryRepository wipRepo;

    @Mock
    private SemiFinishedInventoryTransactionRepository txnRepo;

    @Mock
    private ProductionReportRepository reportRepo;

    @Mock
    private BatchLineageEdgeRepository lineageEdgeRepo;

    @Mock
    private WorkProcessTaskRepository taskRepo;

    @Mock
    private WorkProcessRepository workProcessRepo;

    // SP3 added ApplicationEventPublisher.publishEvent(ProductionCostUpdatedEvent) to
    // WipInventoryServiceImpl.postSemiOutputLedger — mock required so @InjectMocks can inject it.
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WipInventoryServiceImpl service;

    @Captor
    private ArgumentCaptor<SemiFinishedInventory> wipCaptor;

    @Captor
    private ArgumentCaptor<SemiFinishedInventoryTransaction> txnCaptor;

    /**
     * F1 修复后 FINISHED 路径 first-IN 桩: findForUpdate(empty) → ensureRowExists saveAndFlush 占位行
     * → 再 findForUpdate 拿占位行 → upsertProducedWip 累加 → save。FINISHED 不写 ledger / 不查幂等。
     */
    private void stubFinishedFirstIn(String wipNo) {
        final SemiFinishedInventory[] holder = new SemiFinishedInventory[1];
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, wipNo))
                .thenAnswer(inv -> Optional.ofNullable(holder[0]));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(6666L);
            }
            holder[0] = s;
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(6666L);
            }
            return s;
        });
    }

    @Test
    @DisplayName("validateSourceWip rejects input quantity above available WIP")
    void validateSourceWip_rejectsOverInput() {
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-S1")
                .availableQuantity(new BigDecimal("20"))
                .producedQuantity(new BigDecimal("50"))
                .consumedQuantity(new BigDecimal("30"))
                .unit("kg")
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("WIP-S1"))
                .thenReturn(Optional.of(source));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateSourceWip("WIP-S1", new BigDecimal("21"), "kg"));

        assertEquals(409, ex.getCode());
        assertEquals("WIP_INSUFFICIENT", ex.getErrorCode());
    }

    @Test
    @DisplayName("validateSourceWip requires input quantity when source WIP is selected")
    void validateSourceWip_requiresInputQuantity() {
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-S1")
                .availableQuantity(new BigDecimal("20"))
                .producedQuantity(new BigDecimal("50"))
                .consumedQuantity(new BigDecimal("30"))
                .unit("kg")
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("WIP-S1"))
                .thenReturn(Optional.of(source));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateSourceWip("WIP-S1", null, "kg"));

        assertEquals(409, ex.getCode());
        assertEquals("WIP_INPUT_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("validateSourceWip subtracts pending report reservations before allowing new input")
    void validateSourceWip_blocksWhenPendingReservationsWouldOverClaim() {
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-S1")
                .availableQuantity(new BigDecimal("100"))
                .producedQuantity(new BigDecimal("100"))
                .consumedQuantity(BigDecimal.ZERO)
                .unit("kg")
                .build();
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "WIP-S1"))
                .thenReturn(Optional.of(source));
        when(reportRepo.sumPendingInputBySourceWipNo(FACTORY_ID, "WIP-S1", null))
                .thenReturn(new BigDecimal("70"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateSourceWip(FACTORY_ID, "WIP-S1", new BigDecimal("40"), "kg", null));

        assertEquals(409, ex.getCode());
        assertEquals("WIP_RESERVED_INSUFFICIENT", ex.getErrorCode());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getActionHint().contains("30 kg"));
    }

    @Test
    @DisplayName("validateSourceWip scopes source WIP lookup by factory")
    void validateSourceWip_scopesSourceLookupByFactory() {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "WIP-SAME"))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateSourceWip(FACTORY_ID, "WIP-SAME", new BigDecimal("10"), "kg", null));

        assertEquals(404, ex.getCode());
        verify(wipRepo, never()).findForUpdateByIntermediateBatchNoAndDeletedAtIsNull(anyString());
    }

    @Test
    @DisplayName("N2 double picking consumes sourceWipQuantity instead of total inputQuantity")
    void postApprovedOutput_sourceWipQuantity_consumesOnlySemiQuantity() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7000L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9000L)
                .workProcessId("WP-000")
                .productTypeId("PROD-000")
                .processOrder(1)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(499L)
                .batchId(9000L)
                .workProcessTaskId(7000L)
                .sourceWipNo("WIP-S1")
                .inputQuantity(new BigDecimal("130"))
                .inputUnit("kg")
                .customFields(Map.of("sourceWipQuantity", new BigDecimal("80")))
                .build();
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-S1")
                .availableQuantity(new BigDecimal("100"))
                .producedQuantity(new BigDecimal("100"))
                .consumedQuantity(BigDecimal.ZERO)
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "WIP-S1"))
                .thenReturn(Optional.of(source));
        when(reportRepo.sumPendingInputBySourceWipNo(FACTORY_ID, "WIP-S1", 499L))
                .thenReturn(BigDecimal.ZERO);
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 10L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        assertEquals(new BigDecimal("80"), saved.getConsumedQuantity());
        assertEquals(new BigDecimal("20"), saved.getAvailableQuantity());
    }

    @Test
    @DisplayName("N2 double picking rejects sourceWipQuantity above report inputQuantity")
    void postApprovedOutput_sourceWipQuantityCannotExceedTotalInput() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7000L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9000L)
                .workProcessId("WP-000")
                .productTypeId("PROD-000")
                .processOrder(1)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(499L)
                .batchId(9000L)
                .workProcessTaskId(7000L)
                .sourceWipNo("WIP-S1")
                .inputQuantity(new BigDecimal("50"))
                .inputUnit("kg")
                .customFields(Map.of("sourceWipQuantity", new BigDecimal("80")))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.postApprovedOutput(FACTORY_ID, report, task, 10L));

        assertEquals(409, ex.getCode());
        assertEquals("WIP_INPUT_EXCEEDS_TOTAL", ex.getErrorCode());
        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
    }

    @Test
    @DisplayName("postApprovedOutput creates a produced WIP row for approved output")
    void postApprovedOutput_createsProducedWip() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .workProcessId("WP-001")
                .productTypeId("PROD-001")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(500L)
                .batchId(9001L)
                .workProcessTaskId(7001L)
                .outputQuantity(new BigDecimal("75"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("30"))
                .materialCost(new BigDecimal("45"))
                .build();
        // F1: FINISHED 新行走 ensure-row-then-lock (findForUpdate + saveAndFlush 占位)
        stubFinishedFirstIn("PROD-001-B9001-S2-7001");

        service.postApprovedOutput(FACTORY_ID, report, task, 10L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        assertEquals("PROD-001-B9001-S2-7001", saved.getIntermediateBatchNo());
        assertEquals(9001L, saved.getBatchId());
        assertEquals(7001L, saved.getSourceWorkProcessTaskId());
        assertEquals(new BigDecimal("75"), saved.getProducedQuantity());
        assertEquals(new BigDecimal("75"), saved.getAvailableQuantity());
        assertEquals(new BigDecimal("75"), saved.getAccumulatedCost());
        assertEquals(new BigDecimal("1.0000"), saved.getUnitCost());
    }

    @Test
    @DisplayName("postApprovedOutput is idempotent when report is already WIP posted")
    void postApprovedOutput_skipsAlreadyPostedReport() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .productTypeId("PROD-001")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(501L)
                .workProcessTaskId(7001L)
                .outputQuantity(new BigDecimal("75"))
                .customFields(Map.of("wipPosted", true))
                .build();

        service.postApprovedOutput(FACTORY_ID, report, task, 10L);

        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        verify(wipRepo, never()).findByIntermediateBatchNoAndDeletedAtIsNull(anyString());
        verify(wipRepo, never()).findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    @DisplayName("postApprovedOutput rolls up INPUT material and SEGMENT labor cost for OUTPUT reports")
    void postApprovedOutput_outputReportRollsTaskCosts() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .workProcessId("WP-001")
                .productTypeId("PROD-001")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(502L)
                .batchId(9001L)
                .workProcessTaskId(7001L)
                .reportKind("OUTPUT")
                .outputQuantity(new BigDecimal("75"))
                .outputUnit("kg")
                .build();
        when(reportRepo.findYieldReportsByTask(FACTORY_ID, 7001L)).thenReturn(List.of(
                ProductionReport.builder().reportKind("INPUT").materialCost(new BigDecimal("100.00")).build(),
                ProductionReport.builder().reportKind("SEGMENT").laborCost(new BigDecimal("20.00")).build()
        ));
        // OUTPUT report 还会聚合同批次 INPUT 料 (findYieldReportsByBatch); 此处自身 task rollup 已含 INPUT 100
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, 9001L)).thenReturn(List.of());
        // F1: FINISHED 新行走 ensure-row-then-lock
        stubFinishedFirstIn("PROD-001-B9001-S2-7001");

        service.postApprovedOutput(FACTORY_ID, output, task, 10L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        assertEquals(new BigDecimal("120.00"), saved.getAccumulatedCost());
        assertEquals(new BigDecimal("1.6000"), saved.getUnitCost());
        assertEquals(Boolean.TRUE, output.getCustomFields().get("wipPosted"));
    }

    @Test
    @DisplayName("P0 两点模式: OUTPUT 滚动补加同批次 INPUT-kind 报工料成本 (按 reportKind=INPUT 聚合)")
    void postApprovedOutput_twoPoint_rollsInputSentinelMaterial() {
        // 两点模式: 料记在 INPUT-kind 报工 (两点为 __MATERIAL_INPUT__ 哨兵 task), 产出在 OUTPUT task(8002), 同批次 8000。
        // 修后聚合按 reportKind=INPUT (不依赖 task 是否哨兵) → 同时覆盖两点哨兵 + 二次加工普通 task 的 INPUT 报工。
        WorkProcessTask outputTask = WorkProcessTask.builder()
                .id(8002L).factoryId(FACTORY_ID).productionBatchId(8000L)
                .workProcessId("__FINAL_OUTPUT__").productTypeId("PROD-X")
                .processOrder(9999).plannedUnit("kg").build();
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID).id(602L).batchId(8000L).workProcessTaskId(8002L)
                .reportKind("OUTPUT").outputQuantity(new BigDecimal("10")).outputUnit("kg").build();

        // OUTPUT task 自身无料 report (空); 同批次有一条 INPUT-kind 料报工 0.50 (+ OUTPUT 报工不计料)
        when(reportRepo.findYieldReportsByTask(FACTORY_ID, 8002L)).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, 8000L)).thenReturn(List.of(
                ProductionReport.builder().reportKind("INPUT").materialCost(new BigDecimal("0.50")).build(),
                output  // OUTPUT-kind, 料 null → 不计入 INPUT 聚合
        ));
        // F1: FINISHED 新行走 ensure-row-then-lock
        stubFinishedFirstIn("PROD-X-B8000-S9999-8002");

        service.postApprovedOutput(FACTORY_ID, output, outputTask, 10L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        // 修前: material=null → accumulatedCost=null → unitCost=null (Codex 实测阻断)
        // 修后: 补加 INPUT-kind 料 0.50 → accumulatedCost=0.50, unitCost=0.50/10=0.0500
        assertEquals(new BigDecimal("0.50"), saved.getAccumulatedCost());
        assertEquals(new BigDecimal("0.0500"), saved.getUnitCost());
    }

    @Test
    @DisplayName("Gap B 多段链: 二次加工 OUTPUT 滚动补加同批次普通 task 的 INPUT 报工料 (上游WIP消耗+本段原料)")
    void postApprovedOutput_secondaryStage_rollsBatchInputMaterial() {
        // 二次加工 semiB 批次 (7000): 两条 INPUT-kind 报工在普通 task 上 —— 545(消耗上游 semiA, 料 5.00) +
        // 546(本段原料, 料 10.00); OUTPUT 报工产 semiB 5kg。期望 unitCost=(5+10)/5=3.0000 (Codex 多段 rerun 场景)。
        WorkProcessTask outputTask = WorkProcessTask.builder()
                .id(7002L).factoryId(FACTORY_ID).productionBatchId(7000L)
                .workProcessId("WP-REGULAR").productTypeId("PROD-Y")
                .processOrder(5).plannedUnit("kg").build();
        ProductionReport semiBOutput = ProductionReport.builder()
                .factoryId(FACTORY_ID).id(549L).batchId(7000L).workProcessTaskId(7002L)
                .reportKind("OUTPUT").outputKind("SEMI").semiCode("DEMO-SEMI-B")
                .semiOutputQuantity(new BigDecimal("5")).semiOutputUnit("kg")
                .outputQuantity(new BigDecimal("5")).outputUnit("kg").build();

        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 549L)).thenReturn(Collections.emptyList());
        when(reportRepo.findYieldReportsByTask(FACTORY_ID, 7002L)).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, 7000L)).thenReturn(List.of(
                ProductionReport.builder().reportKind("INPUT").materialCost(new BigDecimal("5.00")).build(),
                ProductionReport.builder().reportKind("INPUT").materialCost(new BigDecimal("10.00")).build(),
                semiBOutput
        ));
        // SEMI first-IN 占位行 holder 模式 (镜像 firstIn 测试): findForUpdate(empty) → saveAndFlush 建占位 → 再 findForUpdate 拿占位 → 累加
        final SemiFinishedInventory[] holder = new SemiFinishedInventory[1];
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "DEMO-SEMI-B"))
                .thenAnswer(inv -> Optional.ofNullable(holder[0]));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory sfi = inv.getArgument(0);
            sfi.setId(7777L);
            holder[0] = sfi;
            return sfi;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, semiBOutput, outputTask, 10L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        // (5 + 10) / 5 = 3.0000 —— 上游 semiA 消耗料 (5) 已在 INPUT 报工 materialCost, + 本段原料 (10)
        assertEquals(new BigDecimal("3.0000"), saved.getUnitCost());
    }

    // ==================== W4 cost-chain: FINISHED-path SP3 event ====================

    @Test
    @DisplayName("W4: FINISHED/legacy path publishes ProductionCostUpdatedEvent when unitCost computed")
    void postApprovedOutput_finishedPath_publishesCostEvent() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7101L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9101L)
                .workProcessId("WP-002")
                .productTypeId("PROD-010")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        // outputKind = null → legacy FINISHED path (upsertProducedWip); report carries direct costs
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(701L)
                .batchId(9101L)
                .workProcessTaskId(7101L)
                .outputQuantity(new BigDecimal("50"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("80.00"))
                .materialCost(new BigDecimal("120.00"))
                .build();
        // F1: FINISHED 新行走 ensure-row-then-lock
        stubFinishedFirstIn("PROD-010-B9101-S2-7101");

        service.postApprovedOutput(FACTORY_ID, output, task, 11L);

        // unitCost = (80+120)/50 = 4.0000 → event must fire with batch/product/unitCost
        ArgumentCaptor<ProductionCostUpdatedEvent> evtCaptor =
                ArgumentCaptor.forClass(ProductionCostUpdatedEvent.class);
        verify(eventPublisher).publishEvent(evtCaptor.capture());
        ProductionCostUpdatedEvent evt = evtCaptor.getValue();
        assertEquals(9101L, evt.getProductionBatchId());
        assertEquals("PROD-010", evt.getProductTypeId());
        assertEquals(new BigDecimal("4.0000"), evt.getActualUnitCost());
        assertEquals(new BigDecimal("200.00"), evt.getActualTotalCost());
    }

    @Test
    @DisplayName("W4: FINISHED path does NOT publish cost event when unitCost null (honest null propagation)")
    void postApprovedOutput_finishedPath_noEventWhenUnitCostNull() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7102L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9102L)
                .workProcessId("WP-003")
                .productTypeId("PROD-011")
                .processOrder(1)
                .plannedUnit("kg")
                .build();
        // No labor/material cost → accumulatedCost null → unitCost null → no event (nothing to backfill)
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(702L)
                .batchId(9102L)
                .workProcessTaskId(7102L)
                .outputQuantity(new BigDecimal("40"))
                .outputUnit("kg")
                .build();
        // F1: FINISHED 新行走 ensure-row-then-lock; reportKind=null (非 OUTPUT) → 直接用 report 自身成本 (全 null)
        stubFinishedFirstIn("PROD-011-B9102-S1-7102");

        service.postApprovedOutput(FACTORY_ID, output, task, 11L);

        verify(eventPublisher, never()).publishEvent(any(ProductionCostUpdatedEvent.class));
    }

    // ==================== F1: FINISHED-path ensure-row-then-lock 并发安全 ====================

    @Test
    @DisplayName("F1: FINISHED 新行竞争 — 输掉 insert race (DataIntegrityViolation) → catch → 重拿锁累加, 不丢入账")
    void postApprovedOutput_finishedPath_lostInsertRace_retriesIntoExistingRow() {
        // 模拟并发: 本线程 findForUpdate(empty) → ensureRowExists 子事务 insert 撞 unique 约束
        //   (对方先建好行) → catch DataIntegrityViolationException → 重 findForUpdate 拿到对方建好的占位行
        //   → 在该行上累加本次产出 (不丢入账, 不抛错)。
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7201L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9201L)
                .productTypeId("PROD-020")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(720L)
                .batchId(9201L)
                .workProcessTaskId(7201L)
                .outputQuantity(new BigDecimal("30"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("60.00"))
                .materialCost(new BigDecimal("30.00"))
                .build();
        String wipNo = "PROD-020-B9201-S2-7201";

        // 对方并发线程已建好的占位行 (0 量), 第二次 findForUpdate 才出现
        SemiFinishedInventory winnerPlaceholder = SemiFinishedInventory.builder()
                .id(5201L)
                .factoryId(FACTORY_ID)
                .intermediateBatchNo(wipNo)
                .batchId(9201L)
                .sourceWorkProcessTaskId(7201L)
                .processOrder(2)
                .productTypeId("PROD-020")
                .producedQuantity(BigDecimal.ZERO)
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(BigDecimal.ZERO)
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        // 1st findForUpdate: empty (行不存在 → 触发 insert) ; 2nd: 对方已建好的占位行 (我撞约束后重拿锁)
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, wipNo))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerPlaceholder));
        // 我的 insert 输掉 race → saveAndFlush 撞 unique 约束 (self==null 时直调 commitEmptySemiRow → saveAndFlush)
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class)))
                .thenThrow(new DataIntegrityViolationException("uq_sfi_factory_intermediate_batch_no"));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, output, task, 12L);

        // 断言: 在对方建好的占位行上累加, 入账不丢 (produced=30, unitCost=(60+30)/30=3.0000), 不抛错
        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        assertEquals(wipNo, saved.getIntermediateBatchNo());
        assertEquals(new BigDecimal("30"), saved.getProducedQuantity(),
                "[F1] 输掉 race 后在对方占位行上累加, produced=30 (入账不丢)");
        assertEquals(new BigDecimal("3.0000"), saved.getUnitCost(),
                "[F1] unitCost = (60+30)/30 = 3.0000");
        // 事件发出 (有 unitCost → 回填 costUnitPrice)
        verify(eventPublisher).publishEvent(any(ProductionCostUpdatedEvent.class));
    }

    @Test
    @DisplayName("F1: FINISHED 既有行 (跨天/二次报工同 wipNo) — findForUpdate 悲观锁累加, 产出滚动不丢")
    void postApprovedOutput_finishedPath_existingRow_accumulatesUnderLock() {
        // task 级稳定 wipNo: 同 task 第二次报工命中既有行 → findForUpdate 悲观锁直接累加 (无乐观锁冲突)。
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7202L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9202L)
                .productTypeId("PROD-021")
                .processOrder(3)
                .plannedUnit("kg")
                .build();
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(721L)
                .batchId(9202L)
                .workProcessTaskId(7202L)
                .outputQuantity(new BigDecimal("50"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("100"))
                .materialCost(new BigDecimal("150"))
                .build();
        String wipNo = "PROD-021-B9202-S3-7202";
        SemiFinishedInventory existing = SemiFinishedInventory.builder()
                .id(5202L)
                .factoryId(FACTORY_ID)
                .intermediateBatchNo(wipNo)
                .producedQuantity(new BigDecimal("100"))
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("100"))
                .accumulatedCost(new BigDecimal("800.00"))
                .unitCost(new BigDecimal("8.0000"))
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        // 既有行: findForUpdate 直接命中 (不走 ensureRowExists / saveAndFlush)
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, wipNo))
                .thenReturn(Optional.of(existing));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, output, task, 12L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        assertEquals(new BigDecimal("150"), saved.getProducedQuantity(), "produced = 100 + 50");
        // accumulated = 800 + 250 = 1050, unitCost = 1050/150 = 7.0000 (与 SC-11 一致)
        assertEquals(new BigDecimal("7.0000"), saved.getUnitCost(),
                "[F1] 既有行累加 unitCost = (800+250)/150 = 7.0000");
        // 既有行路径不建占位行
        verify(wipRepo, never()).saveAndFlush(any(SemiFinishedInventory.class));
    }

    // ==================== SP1 tests (T3) ====================

    @Test
    @DisplayName("SP1-SEMI: postApprovedOutput writes SFI row + IN ledger txn on first SEMI report")
    void postApprovedOutput_semiOutputKind_firstIn_createsNewSfiAndTxn() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9100L)
                .productTypeId("PROD-002")
                .processOrder(3)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(600L)
                .batchId(9100L)
                .workProcessTaskId(8001L)
                .outputKind("SEMI")
                .semiCode("SEMI-BATCH-001")
                .semiOutputQuantity(new BigDecimal("50"))
                .semiOutputUnit("kg")
                .laborCost(new BigDecimal("100"))
                .materialCost(new BigDecimal("150"))
                .build();

        // Idempotency guard (BUG-GOLD-RERUN-WEIGHTED-AVG-SKIP fix): keyed on report_id now.
        // This report (600) has no prior IN txn → proceed.
        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 600L))
                .thenReturn(Collections.emptyList());
        // W8 BUG-SP1-NEW-ROW 修复后 first-IN: findForUpdate(empty) → ensureSemiRowExists 建 0 量占位行
        // (saveAndFlush) → 再 findForUpdate 拿占位行 → applyMovingAverageIn 累加 → save。
        final SemiFinishedInventory[] holder = new SemiFinishedInventory[1];
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-BATCH-001"))
                .thenAnswer(inv -> Optional.ofNullable(holder[0]));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory sfi = inv.getArgument(0);
            sfi.setId(7777L); // simulate DB-assigned ID
            holder[0] = sfi;
            return sfi;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory sfi = inv.getArgument(0);
            if (sfi.getId() == null) {
                sfi.setId(7777L);
            }
            return sfi;
        });
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 20L);

        // SFI created (累加后全量值)
        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory sfi = wipCaptor.getValue();
        assertEquals("SEMI-BATCH-001", sfi.getIntermediateBatchNo());
        assertEquals(new BigDecimal("50"), sfi.getProducedQuantity());
        assertEquals(new BigDecimal("50"), sfi.getAvailableQuantity());
        // unitCost = (100+150)/50 = 5.0000
        assertEquals(new BigDecimal("5.0000"), sfi.getUnitCost());

        // Ledger IN txn saved
        verify(txnRepo).save(txnCaptor.capture());
        SemiFinishedInventoryTransaction txn = txnCaptor.getValue();
        assertEquals(SemiFinishedInventoryTransaction.TxnType.IN, txn.getTxnType());
        assertEquals(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT, txn.getSourceType());
        assertEquals("SEMI-BATCH-001", txn.getSourceRef());
        assertEquals(new BigDecimal("50"), txn.getQuantity());
        assertEquals(new BigDecimal("5.0000"), txn.getUnitCostAtTxn());
        assertEquals(600L, txn.getReportId());
        assertEquals(20L, txn.getOperatorId());

        // Legacy FINISHED path should NOT run (outputKind=SEMI → no upsertProducedWip)
        verify(wipRepo, never()).findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    @DisplayName("SP1-SEMI: postSemiOutputLedger uses moving-average cost on second IN")
    void postApprovedOutput_semiOutputKind_secondIn_movingAverageCost() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8002L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9101L)
                .productTypeId("PROD-003")
                .processOrder(1)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(601L)
                .batchId(9101L)
                .workProcessTaskId(8002L)
                .outputKind("SEMI")
                .semiCode("SEMI-BATCH-002")
                .semiOutputQuantity(new BigDecimal("40"))
                .semiOutputUnit("kg")
                .laborCost(new BigDecimal("80"))
                .materialCost(new BigDecimal("40"))
                // totalCost=120, unitCost=120/40=3.0000
                .build();

        // report 601 not yet posted → proceed (idempotency keyed on report_id)
        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 601L))
                .thenReturn(Collections.emptyList());

        // Existing SFI: 60kg @4.0000/kg — SAME semiCode SEMI-BATCH-002 as a prior batch,
        // but a DIFFERENT report → must accumulate (this is the weighted-average scenario the bug broke).
        SemiFinishedInventory existing = SemiFinishedInventory.builder()
                .id(555L)
                .factoryId(FACTORY_ID)
                .intermediateBatchNo("SEMI-BATCH-002")
                .producedQuantity(new BigDecimal("60"))
                .consumedQuantity(new BigDecimal("10"))
                .availableQuantity(new BigDecimal("50"))
                .unitCost(new BigDecimal("4.0000"))
                .accumulatedCost(new BigDecimal("240.00"))
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-BATCH-002"))
                .thenReturn(Optional.of(existing));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 20L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();

        // producedQuantity = 60 + 40 = 100
        assertEquals(new BigDecimal("100"), saved.getProducedQuantity());
        // availableQuantity = 100 - 10 = 90
        assertEquals(new BigDecimal("90"), saved.getAvailableQuantity());
        // Moving average: (60*4.0000 + 40*3.0000) / 100 = (240 + 120) / 100 = 3.6000
        assertEquals(new BigDecimal("3.6000"), saved.getUnitCost());
    }

    @Test
    @DisplayName("SP1-SEMI: idempotency guard skips when THIS report already posted (keyed on report_id, not semiCode)")
    void postApprovedOutput_semiOutputKind_idempotentSkip() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8003L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9102L)
                .productTypeId("PROD-004")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(602L)
                .outputKind("SEMI")
                .semiCode("SEMI-BATCH-003")
                .semiOutputQuantity(new BigDecimal("30"))
                .laborCost(new BigDecimal("60"))
                .build();

        // THIS report (602) already has an IN txn → idempotent skip (re-approval / retry of same report)
        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 602L))
                .thenReturn(List.of(SemiFinishedInventoryTransaction.builder()
                        .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                        .build()));

        service.postApprovedOutput(FACTORY_ID, report, task, 20L);

        // Should not write any new SFI or txn
        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("SP1-SEMI: null semiCode skips postSemiOutputLedger gracefully (R4 backward compat)")
    void postApprovedOutput_semiOutputKind_nullSemiCode_skipsGracefully() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8004L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9103L)
                .productTypeId("PROD-005")
                .processOrder(1)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(603L)
                .outputKind("SEMI")
                .semiCode(null)   // missing → skip
                .semiOutputQuantity(new BigDecimal("20"))
                .build();

        service.postApprovedOutput(FACTORY_ID, report, task, 20L);

        verify(txnRepo, never()).findByFactoryIdAndReportId(anyString(), anyLong());
        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("SP1-BOTH: postApprovedOutput runs SEMI ledger AND finished WIP paths")
    void postApprovedOutput_bothOutputKind_runsBothPaths() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8005L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9104L)
                .productTypeId("PROD-006")
                .processOrder(4)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(604L)
                .batchId(9104L)
                .workProcessTaskId(8005L)
                .outputKind("BOTH")
                .semiCode("SEMI-BATCH-004")
                .semiOutputQuantity(new BigDecimal("20"))
                .semiOutputUnit("kg")
                .outputQuantity(new BigDecimal("80"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("50"))
                .materialCost(new BigDecimal("50"))
                .build();

        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 604L))
                .thenReturn(Collections.emptyList());
        // W8 + F1: 两条路径 (SEMI semiCode + FINISHED wipNo) 各自走 ensure-row-then-lock 占位行 (saveAndFlush)。
        // 按 intermediateBatchNo 分别维护占位 holder (key 区分两条路径)。
        final java.util.Map<String, SemiFinishedInventory> holders = new java.util.HashMap<>();
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(FACTORY_ID), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(holders.get(inv.<String>getArgument(1))));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory sfi = inv.getArgument(0);
            if (sfi.getId() == null) sfi.setId(8887L);
            holders.put(sfi.getIntermediateBatchNo(), sfi);
            return sfi;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory sfi = inv.getArgument(0);
            if (sfi.getId() == null) sfi.setId(8888L);
            return sfi;
        });
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 30L);

        // wipRepo.save called TWICE: 一次 SEMI SFI 累加, 一次 FINISHED WIP 累加 (占位行另走 saveAndFlush, 不计入 save)
        verify(wipRepo, times(2)).save(any(SemiFinishedInventory.class));
        // F1: SEMI + FINISHED 两条路径各建一次 0 量占位行 → saveAndFlush 共 2 次
        verify(wipRepo, times(2)).saveAndFlush(any(SemiFinishedInventory.class));
        // txnRepo.save called ONCE: for SEMI IN ledger
        verify(txnRepo, times(1)).save(any(SemiFinishedInventoryTransaction.class));
    }

    @Test
    @DisplayName("SP1: null outputKind (legacy) skips SEMI path and runs existing WIP path")
    void postApprovedOutput_nullOutputKind_legacyPathOnly() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .productTypeId("PROD-001")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(500L)
                .batchId(9001L)
                .workProcessTaskId(7001L)
                .outputKind(null)   // legacy — no SP1 fields
                .outputQuantity(new BigDecimal("75"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("30"))
                .materialCost(new BigDecimal("45"))
                .build();
        // F1: FINISHED/legacy 新行走 ensure-row-then-lock
        stubFinishedFirstIn("PROD-001-B9001-S2-7001");

        service.postApprovedOutput(FACTORY_ID, report, task, 10L);

        // No txnRepo interaction for legacy path
        verify(txnRepo, never()).findByFactoryIdAndReportId(anyString(), anyLong());
        verify(txnRepo, never()).save(any());
        // Regular WIP path runs (累加后 save)
        verify(wipRepo).save(any(SemiFinishedInventory.class));
    }

    // ========== SP1 T4: getOutputOptions ==========

    @Test
    @DisplayName("T4: getOutputOptions returns items only for tasks whose WorkProcess has semiFinishedOutputCode")
    void getOutputOptions_returnsOnlyTasksWithSemiCode() {
        Long batchId = 200L;

        WorkProcessTask task1 = WorkProcessTask.builder()
                .id(1L).factoryId(FACTORY_ID).productionBatchId(batchId)
                .workProcessId("WP-01").processOrder(1).build();
        WorkProcessTask task2 = WorkProcessTask.builder()
                .id(2L).factoryId(FACTORY_ID).productionBatchId(batchId)
                .workProcessId("WP-02").processOrder(2).build();

        WorkProcess wp1 = WorkProcess.builder()
                .id("WP-01").factoryId(FACTORY_ID).processName("焯水")
                .semiFinishedOutputCode("SEMI-01").build();
        WorkProcess wp2 = WorkProcess.builder()
                .id("WP-02").factoryId(FACTORY_ID).processName("腌制")
                .semiFinishedOutputCode(null).build();   // no semi code → excluded

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(task1, task2));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY_ID, "WP-01")).thenReturn(Optional.of(wp1));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY_ID, "WP-02")).thenReturn(Optional.of(wp2));

        OutputOptionsResponse resp = service.getOutputOptions(FACTORY_ID, batchId);

        assertEquals(1, resp.getItems().size());
        OutputOptionsResponse.OutputOptionItem item = resp.getItems().get(0);
        assertEquals(1L, item.getTaskId());
        assertEquals("焯水", item.getProcessName());
        assertEquals("SEMI-01", item.getSemiCode());
        assertEquals(1, item.getProcessOrder());
    }

    @Test
    @DisplayName("T4: getOutputOptions returns empty list when no tasks have semiFinishedOutputCode")
    void getOutputOptions_emptyWhenNoSemiCodeConfigured() {
        Long batchId = 201L;

        WorkProcessTask task = WorkProcessTask.builder()
                .id(3L).factoryId(FACTORY_ID).productionBatchId(batchId)
                .workProcessId("WP-03").processOrder(1).build();

        WorkProcess wp = WorkProcess.builder()
                .id("WP-03").factoryId(FACTORY_ID).processName("切割")
                .semiFinishedOutputCode("   ").build();  // blank → excluded

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(task));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY_ID, "WP-03")).thenReturn(Optional.of(wp));

        OutputOptionsResponse resp = service.getOutputOptions(FACTORY_ID, batchId);

        assertEquals(0, resp.getItems().size());
    }

    @Test
    @DisplayName("T4: getOutputOptions returns empty list when batch has no tasks")
    void getOutputOptions_emptyWhenNoTasks() {
        Long batchId = 202L;

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of());

        OutputOptionsResponse resp = service.getOutputOptions(FACTORY_ID, batchId);

        assertEquals(0, resp.getItems().size());
        verify(workProcessRepo, never()).findByFactoryIdAndId(anyString(), anyString());
    }

    @Test
    @DisplayName("T4: getOutputOptions skips tasks with null workProcessId")
    void getOutputOptions_skipsTasksWithNullWorkProcessId() {
        Long batchId = 203L;

        WorkProcessTask task = WorkProcessTask.builder()
                .id(4L).factoryId(FACTORY_ID).productionBatchId(batchId)
                .workProcessId(null).processOrder(1).build();  // no workProcessId

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(task));

        OutputOptionsResponse resp = service.getOutputOptions(FACTORY_ID, batchId);

        assertEquals(0, resp.getItems().size());
        verify(workProcessRepo, never()).findByFactoryIdAndId(anyString(), anyString());
    }
}
