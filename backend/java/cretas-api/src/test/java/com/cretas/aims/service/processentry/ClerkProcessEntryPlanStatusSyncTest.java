package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProcessEntryIdempotency;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 🟠 母计划状态同步回归测试.
 *
 * <p>{@code ClerkProcessEntryServiceImpl.createProductionBatch} 建 FINISHED 生产批次
 * (ctx.planId 非空) 时, 只建了 {@code ProductionBatch(IN_PROGRESS)}, 此前从未回写母
 * {@code ProductionPlan.status} → 计划永远卡在 PENDING, 即便生产已实际发生 (2026-07 F006
 * 事故: 49 个计划实际在产却标 PENDING, 看板/统计按 production_plans.status 过滤口径错)。
 *
 * <p>修复: 建 FINISHED 批次后, 若母计划仍 PENDING → sync 到 IN_PROGRESS (+ startTime),
 * 不调用完整 {@code ProductionPlanServiceImpl.startProduction()} (避免重放前置审批门 /
 * SP2 二次加工 WIP 二次扣减副作用 — 逐工序的实际消耗已经通过 edges 精确入账)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClerkProcessEntryServiceImpl — 母计划状态同步 (PENDING → IN_PROGRESS)")
class ClerkProcessEntryPlanStatusSyncTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-SYNC-001";
    private static final Long OPERATOR_ID = 42L;

    @Mock private ProductionBatchRepository batchRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProcessEntryIdempotencyRepository idempotencyRepo;
    @Mock private FactoryWarehouseRepository warehouseRepo;
    @Mock private BomRecipeRepository bomRecipeRepo;
    @Mock private BomSeasoningItemRepository bomSeasoningItemRepo;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private com.cretas.aims.repository.ProductionReportRepository reportRepo;

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void injectObjectMapper() throws Exception {
        var f = ClerkProcessEntryServiceImpl.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(service, objectMapper);
    }

    private void stubNoIdempotency(String key) {
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, PLAN_ID, key))
                .thenReturn(Optional.empty());
    }

    private ProductionPlan pendingPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlannedQuantity(new BigDecimal("100"));
        return plan;
    }

    private void stubWarehouse() {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("WH-WKS-1");
        wh.setFactoryId(FACTORY);
        wh.setCode("WH-WKS");
        wh.setName("车间仓");
        when(warehouseRepo.findByFactoryIdAndCodeAndDeletedAtIsNull(eq(FACTORY), anyString()))
                .thenReturn(Optional.of(wh));
    }

    private void stubBatchSave() {
        when(batchRepo.existsByFactoryIdAndBatchNumber(any(), any())).thenReturn(false);
        when(batchRepo.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            if (b.getId() == null) b.setId(System.nanoTime() % 100_000L);
            return b;
        });
    }

    private void stubConsumptionSave() {
        when(consumptionRepo.save(any(MaterialConsumption.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubIdempotencySave() {
        when(idempotencyRepo.save(any(ProcessEntryIdempotency.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubNoRecipe() {
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(any(), any()))
                .thenReturn(Optional.empty());
    }

    private MaterialBatch rawMb(String id, BigDecimal unitPrice) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(FACTORY);
        mb.setBatchNumber("RAW-" + id);
        mb.setMaterialTypeId("MT-PORK");
        mb.setReceiptQuantity(new BigDecimal("500"));
        mb.setUnitPrice(unitPrice);
        mb.setQuantityUnit("kg");
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptDate(LocalDate.now());
        return mb;
    }

    private RawInput rawInput(String mbId, String qty) {
        RawInput r = new RawInput();
        r.setMaterialBatchId(mbId);
        r.setQuantity(new BigDecimal(qty));
        return r;
    }

    private StepEntry rawStep(int order, String inQty, String outQty, List<RawInput> inputs) {
        StepEntry s = new StepEntry();
        s.setProcessOrder(order);
        s.setProcessName("领料");
        s.setInputQuantity(new BigDecimal(inQty));
        s.setOutputQuantity(new BigDecimal(outQty));
        s.setRawMaterialInputs(inputs);
        return s;
    }

    private BatchEntry finishedBatch(String key, String productTypeId, List<StepEntry> steps) {
        BatchEntry b = new BatchEntry();
        b.setClientBatchKey(key);
        b.setProductTypeId(productTypeId);
        b.setFinished(true);
        b.setSteps(steps);
        return b;
    }

    private ProcessChainEntryRequest req(String idempKey, List<BatchEntry> batches) {
        ProcessChainEntryRequest r = new ProcessChainEntryRequest();
        r.setIdempotencyKey(idempKey);
        r.setBatches(batches);
        return r;
    }

    @Test
    @DisplayName("建 FINISHED 批次后, PENDING 母计划翻转 IN_PROGRESS + 写 startTime (回归修复点)")
    void finishedBatchCreation_syncsPendingPlanToInProgress() {
        stubNoIdempotency("SYNC-KEY-1");
        ProductionPlan plan = pendingPlan();
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        stubWarehouse();
        stubBatchSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-X", new BigDecimal("10"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-X", FACTORY)).thenReturn(Optional.of(raw));

        BatchEntry finished = finishedBatch("FINAL", "PT-PRODUCT", List.of(
                rawStep(1, "100", "80", List.of(rawInput("RAW-X", "100")))
        ));

        service.recordChain(FACTORY, PLAN_ID, req("SYNC-KEY-1", List.of(finished)), OPERATOR_ID);

        assertThat(plan.getStatus())
                .as("母计划必须随 FINISHED 批次建立而翻转 IN_PROGRESS, 否则看板/统计口径永远卡 PENDING")
                .isEqualTo(ProductionPlanStatus.IN_PROGRESS);
        assertThat(plan.getStartTime()).as("首次翻转应写入 startTime").isNotNull();
        verify(planRepository, atLeastOnce()).save(plan);
    }

    @Test
    @DisplayName("母计划已是 IN_PROGRESS → 不重复 save / 不覆盖既有 startTime")
    void alreadyInProgressPlan_notReSynced() {
        stubNoIdempotency("SYNC-KEY-2");
        ProductionPlan plan = pendingPlan();
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        var originalStart = java.time.LocalDateTime.of(2026, 1, 1, 8, 0);
        plan.setStartTime(originalStart);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        stubWarehouse();
        stubBatchSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-Y", new BigDecimal("10"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-Y", FACTORY)).thenReturn(Optional.of(raw));

        BatchEntry finished = finishedBatch("FINAL2", "PT-PRODUCT", List.of(
                rawStep(1, "50", "40", List.of(rawInput("RAW-Y", "50")))
        ));

        service.recordChain(FACTORY, PLAN_ID, req("SYNC-KEY-2", List.of(finished)), OPERATOR_ID);

        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
        assertThat(plan.getStartTime())
                .as("已在产的计划, startTime 不应被本次逐工序录入覆盖")
                .isEqualTo(originalStart);
        // 状态 sync 分支只对 PENDING 生效, 已 IN_PROGRESS → planRepository.save(plan) 不应被本 sync 逻辑调用
        verify(planRepository, never()).save(plan);
    }
}
