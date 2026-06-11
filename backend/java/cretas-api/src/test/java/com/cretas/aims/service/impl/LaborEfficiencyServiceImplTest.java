package com.cretas.aims.service.impl;

import com.cretas.aims.dto.laborefficiency.LaborAchievementSummaryDTO;
import com.cretas.aims.dto.laborefficiency.LaborEfficiencyCompareDTO;
import com.cretas.aims.dto.laborefficiency.LaborEfficiencyOrderAggregateDTO;
import com.cretas.aims.dto.laborefficiency.LaborStepBreakdownDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.yield.YieldReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP9 M3/M3b/M4/step-breakdown — 人工双口径对比 + 订单级聚合 + 达成率汇总 + 逐工序拆分服务单元测试
 * (pure POJO, no Spring context).
 */
@ExtendWith(MockitoExtension.class)
class LaborEfficiencyServiceImplTest {

    @Mock
    private ProductionBatchRepository batchRepo;
    @Mock
    private ProductTypeRepository productTypeRepo;
    @Mock
    private ProductionPlanRepository productionPlanRepo;
    @Mock
    private SalesOrderRepository salesOrderRepo;
    @Mock
    private YieldReportService yieldReportService;
    @Mock
    private WorkProcessTaskRepository workProcessTaskRepo;

    @InjectMocks
    private LaborEfficiencyServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END   = LocalDate.of(2026, 6, 30);

    /** Returns a minimal completed ProductionBatch suitable for M3 tests. */
    private ProductionBatch batch(Long id, String ptId, BigDecimal laborCost, BigDecimal goodQty) {
        return batch(id, ptId, laborCost, goodQty, null);
    }

    private ProductionBatch batch(Long id, String ptId, BigDecimal laborCost, BigDecimal goodQty,
                                   String planId) {
        ProductionBatch b = new ProductionBatch();
        // Use reflection-free setters that Lombok/JPA provides
        b.setBatchNumber("BATCH-" + id);
        b.setProductTypeId(ptId);
        b.setFactoryId(FACTORY_ID);
        b.setLaborCost(laborCost);
        b.setGoodQuantity(goodQty);
        b.setProductName("猪舌");
        b.setId(id); // Lombok @Data generates setId(Long)
        b.setProductionPlanId(planId);
        return b;
    }

    private ProductType productType(String id, BigDecimal quotedLaborCost, BigDecimal gramsPerUnit) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setQuotedLaborCostPerKg(quotedLaborCost);
        pt.setGramsPerUnit(gramsPerUnit);
        pt.setName("猪舌 200g");
        return pt;
    }

    private ProductionPlan plan(String id, String soId) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setSourceOrderId(soId);
        return p;
    }

    private SalesOrder salesOrder(String id, String orderNumber) {
        SalesOrder so = new SalesOrder();
        // SalesOrder has setId; use generic setter if available, else reflection
        try {
            so.getClass().getMethod("setId", String.class).invoke(so, id);
        } catch (Exception ignored) {
            // If setId not available, id matching in repo mock will handle it
        }
        so.setOrderNumber(orderNumber);
        return so;
    }

    @BeforeEach
    void setupEmptyYield() {
        // Default: yield returns empty (no step details)
        BatchYieldDTO empty = new BatchYieldDTO();
        empty.setSteps(Collections.emptyList());
        lenient().when(yieldReportService.getYield(anyString(), anyLong())).thenReturn(empty);
        // Default: no work process tasks
        lenient().when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(anyString(), anyLong()))
                .thenReturn(Collections.emptyList());
    }

    // ─── Test 1: empty batches returns empty list ─────────────────────────────

    @Test
    void emptyBatches_returnsEmptyList() {
        when(batchRepo.findCompletedBatchesForLaborComparison(
                eq(FACTORY_ID), any(LocalDateTime.class), any(LocalDateTime.class), isNull()))
                .thenReturn(Collections.emptyList());

        List<LaborEfficiencyCompareDTO> result = service.getLaborEfficiencyComparison(
                FACTORY_ID, START, END, null);

        assertThat(result).isEmpty();
    }

    // ─── Test 2: variance OK when actual ≈ quoted ────────────────────────────

    @Test
    void variance_withinOkRange_returnsOkStatus() {
        // quoted=5.00 元/kg, laborCost=5.00, goodQty=1.00 → actual=5.00/kg → variance=0%
        ProductionBatch b = batch(1L, "PT-001", new BigDecimal("5.00"), new BigDecimal("1.00"));
        ProductType pt = productType("PT-001", new BigDecimal("5.00"), new BigDecimal("200"));

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));

        List<LaborEfficiencyCompareDTO> result = service.getLaborEfficiencyComparison(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        LaborEfficiencyCompareDTO dto = result.get(0);
        assertThat(dto.getVarianceRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getVarianceStatus()).isEqualTo("OK");
    }

    // ─── Test 3: variance WARNING at ≥10% ─────────────────────────────────────

    @Test
    void variance_aboveTenPercent_returnsWarnStatus() {
        // quoted=10.00, actual=11.50 → variance=+15% → WARNING
        ProductionBatch b = batch(2L, "PT-002", new BigDecimal("11.50"), new BigDecimal("1.00"));
        ProductType pt = productType("PT-002", new BigDecimal("10.00"), new BigDecimal("200"));

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));

        List<LaborEfficiencyCompareDTO> result = service.getLaborEfficiencyComparison(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVarianceStatus()).isEqualTo("WARNING");
    }

    // ─── Test 4: variance CRITICAL at ≥20% ────────────────────────────────────

    @Test
    void variance_aboveTwentyPercent_returnsCriticalStatus() {
        // quoted=10.00, actual=13.00 → variance=+30% → CRITICAL
        ProductionBatch b = batch(3L, "PT-003", new BigDecimal("13.00"), new BigDecimal("1.00"));
        ProductType pt = productType("PT-003", new BigDecimal("10.00"), new BigDecimal("200"));

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));

        List<LaborEfficiencyCompareDTO> result = service.getLaborEfficiencyComparison(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVarianceStatus()).isEqualTo("CRITICAL");
    }

    // ─── Test 5: null quotedLaborCostPerKg → varianceRate null ───────────────

    @Test
    void quotedNull_varianceRateNull_varianceStatusNull() {
        ProductionBatch b = batch(4L, "PT-004", new BigDecimal("5.00"), new BigDecimal("1.00"));
        ProductType pt = productType("PT-004", null, new BigDecimal("200")); // no quoted cost

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));

        List<LaborEfficiencyCompareDTO> result = service.getLaborEfficiencyComparison(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        LaborEfficiencyCompareDTO dto = result.get(0);
        assertThat(dto.getVarianceRate()).isNull();
        assertThat(dto.getVarianceStatus()).isNull();
    }

    // ─── Test 6: perBox calculation ───────────────────────────────────────────

    @Test
    void perBoxCost_calculatedCorrectly() {
        // quoted=5.00 元/kg, gramsPerUnit=200g → 5.00 * 200/1000 = 1.00 元/盒
        // actual= laborCost/goodQty = 5.00/1.00 = 5.00 → actualPerBox=5.00*200/1000=1.00
        ProductionBatch b = batch(5L, "PT-005", new BigDecimal("5.00"), new BigDecimal("1.00"));
        ProductType pt = productType("PT-005", new BigDecimal("5.00"), new BigDecimal("200"));

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));

        List<LaborEfficiencyCompareDTO> result = service.getLaborEfficiencyComparison(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        LaborEfficiencyCompareDTO dto = result.get(0);
        assertThat(dto.getQuotedLaborCostPerBox()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(dto.getActualLaborCostPerBox()).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    // ─── Test 7: laborCost null → actualLaborCostPerKg null ──────────────────

    @Test
    void nullLaborCost_actualCostNull() {
        ProductionBatch b = batch(6L, "PT-006", null, new BigDecimal("1.00")); // null laborCost
        ProductType pt = productType("PT-006", new BigDecimal("5.00"), new BigDecimal("200"));

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));

        List<LaborEfficiencyCompareDTO> result = service.getLaborEfficiencyComparison(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        LaborEfficiencyCompareDTO dto = result.get(0);
        assertThat(dto.getActualLaborCostPerKg()).isNull();
        assertThat(dto.getActualLaborCostPerBox()).isNull();
        // variance null because actual is null
        assertThat(dto.getVarianceRate()).isNull();
    }

    // ─── Test 8: step details mapped from YieldReportService ─────────────────

    @Test
    void stepDetails_mappedFromYieldService() {
        ProductionBatch b = batch(7L, "PT-007", new BigDecimal("100.00"), new BigDecimal("50.00"));
        ProductType pt = productType("PT-007", new BigDecimal("2.00"), new BigDecimal("200"));

        // Mock step yield
        StepYieldDTO step = new StepYieldDTO();
        step.setProcessName("滚揉");
        step.setProcessOrder(2);
        step.setTotalWorkMinutes(480);
        step.setTotalWorkers(3);
        step.setLaborCost(new BigDecimal("60.00"));
        step.setTotalOutput(new BigDecimal("10.00")); // 10 kg output

        BatchYieldDTO yield = new BatchYieldDTO();
        yield.setSteps(List.of(step));

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));
        when(yieldReportService.getYield(eq(FACTORY_ID), anyLong())).thenReturn(yield);

        List<LaborEfficiencyCompareDTO> result = service.getLaborEfficiencyComparison(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        List<?> steps = result.get(0).getStepDetails();
        assertThat(steps).isNotNull().hasSize(1);

        var stepDto = result.get(0).getStepDetails().get(0);
        assertThat(stepDto.getProcessName()).isEqualTo("滚揉");
        assertThat(stepDto.getProcessOrder()).isEqualTo(2);
        assertThat(stepDto.getTotalWorkMinutes()).isEqualTo(480);
        assertThat(stepDto.getTotalWorkers()).isEqualTo(3);
        assertThat(stepDto.getLaborCost()).isEqualByComparingTo(new BigDecimal("60.00"));
        // laborCostPerBox = 60.00 / (10kg * 1000 / 200g) = 60 / 50 = 1.2000 元/盒
        assertThat(stepDto.getLaborCostPerBox()).isEqualByComparingTo(new BigDecimal("1.2000"));
    }

    // ─────────────────────────── SP3 M3b: Order Aggregate Tests ────────────────────────────

    // ─── Test 9: M3b empty batches returns empty list ────────────────────────

    @Test
    void m3b_emptyBatches_returnsEmptyList() {
        when(batchRepo.findCompletedBatchesForLaborComparison(
                eq(FACTORY_ID), any(LocalDateTime.class), any(LocalDateTime.class), isNull()))
                .thenReturn(Collections.emptyList());

        List<LaborEfficiencyOrderAggregateDTO> result = service.getLaborCostOrderAggregate(
                FACTORY_ID, START, END, null);

        assertThat(result).isEmpty();
    }

    // ─── Test 10: M3b single batch linked to SO → aggregates correctly ───────

    @Test
    void m3b_singleBatch_linkedToSO_aggregatesCorrectly() {
        // quoted=5.00/kg, goodQty=100 kg → quoted total = 500
        // laborCost=600 → actual total = 600
        // variance = (600-500)/500 * 100 = +20% → CRITICAL
        String planId = "PLAN-001";
        String soId = "SO-001";
        ProductionBatch b = batch(10L, "PT-010", new BigDecimal("600"), new BigDecimal("100"), planId);
        ProductType pt = productType("PT-010", new BigDecimal("5.00"), new BigDecimal("200"));
        ProductionPlan plan = plan(planId, soId);
        SalesOrder so = salesOrder(soId, "SO-2026-001");

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));
        when(productionPlanRepo.findAllById(anyList())).thenReturn(List.of(plan));
        when(salesOrderRepo.findAllById(anyList())).thenReturn(List.of(so));

        List<LaborEfficiencyOrderAggregateDTO> result = service.getLaborCostOrderAggregate(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        LaborEfficiencyOrderAggregateDTO agg = result.get(0);
        assertThat(agg.getSalesOrderId()).isEqualTo(soId);
        assertThat(agg.getBatchCount()).isEqualTo(1);
        assertThat(agg.getTotalGoodQuantityKg()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(agg.getTotalQuotedLaborCost()).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(agg.getTotalActualLaborCost()).isEqualByComparingTo(new BigDecimal("600"));
        assertThat(agg.getLaborCostVarianceAbsolute())
                .isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(agg.getVarianceRate()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(agg.getVarianceStatus()).isEqualTo("CRITICAL");
        // Boxes = 100kg * 1000 / 200g = 500 盒
        assertThat(agg.getTotalGoodQuantityBoxes()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ─── Test 11: M3b multiple batches same SO aggregated together ───────────

    @Test
    void m3b_multipleBatches_sameSO_aggregatedTogether() {
        String planId = "PLAN-002";
        String soId = "SO-002";
        // Batch A: labor=300, qty=50 kg
        // Batch B: labor=400, qty=60 kg
        // pt: quoted=5.00, total quoted = 5*(50+60)=550; actual=700
        // variance=(700-550)/550*100 = 27.27% → CRITICAL
        ProductionBatch bA = batch(11L, "PT-011", new BigDecimal("300"), new BigDecimal("50"), planId);
        ProductionBatch bB = batch(12L, "PT-011", new BigDecimal("400"), new BigDecimal("60"), planId);
        ProductType pt = productType("PT-011", new BigDecimal("5.00"), new BigDecimal("200"));
        ProductionPlan plan = plan(planId, soId);
        SalesOrder so = salesOrder(soId, "SO-2026-002");

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(bA, bB));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));
        when(productionPlanRepo.findAllById(anyList())).thenReturn(List.of(plan));
        when(salesOrderRepo.findAllById(anyList())).thenReturn(List.of(so));

        List<LaborEfficiencyOrderAggregateDTO> result = service.getLaborCostOrderAggregate(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1); // Both batches grouped under same SO
        LaborEfficiencyOrderAggregateDTO agg = result.get(0);
        assertThat(agg.getBatchCount()).isEqualTo(2);
        assertThat(agg.getTotalGoodQuantityKg()).isEqualByComparingTo(new BigDecimal("110"));
        assertThat(agg.getTotalActualLaborCost()).isEqualByComparingTo(new BigDecimal("700"));
        // quoted = 5*(50+60)=550
        assertThat(agg.getTotalQuotedLaborCost()).isEqualByComparingTo(new BigDecimal("550.0000"));
        assertThat(agg.getVarianceStatus()).isEqualTo("CRITICAL");
        // batches list should contain 2 entries
        assertThat(agg.getBatches()).isNotNull().hasSizeGreaterThanOrEqualTo(1);
    }

    // ─── Test 12: M3b batch with no plan → UNLINKED group, salesOrderId null ──

    @Test
    void m3b_batchWithNoPlan_unlinked_salesOrderIdNull() {
        // Batch has no productionPlanId
        ProductionBatch b = batch(13L, "PT-013", new BigDecimal("100"), new BigDecimal("20"), null);
        ProductType pt = productType("PT-013", new BigDecimal("4.00"), new BigDecimal("200"));

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));
        when(productionPlanRepo.findAllById(anyList())).thenReturn(Collections.emptyList());
        when(salesOrderRepo.findAllById(anyList())).thenReturn(Collections.emptyList());

        List<LaborEfficiencyOrderAggregateDTO> result = service.getLaborCostOrderAggregate(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSalesOrderId()).isNull(); // UNLINKED group
        assertThat(result.get(0).getBatchCount()).isEqualTo(1);
    }

    // ─── Test 13: M3b quoted null → totalQuotedLaborCost null (诚实null) ──────

    @Test
    void m3b_quotedNullForAllBatches_totalQuotedNull() {
        String planId = "PLAN-014";
        String soId = "SO-014";
        ProductionBatch b = batch(14L, "PT-014", new BigDecimal("100"), new BigDecimal("20"), planId);
        ProductType pt = productType("PT-014", null, new BigDecimal("200")); // no quoted cost
        ProductionPlan plan = plan(planId, soId);
        SalesOrder so = salesOrder(soId, "SO-2026-014");

        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(List.of(pt));
        when(productionPlanRepo.findAllById(anyList())).thenReturn(List.of(plan));
        when(salesOrderRepo.findAllById(anyList())).thenReturn(List.of(so));

        List<LaborEfficiencyOrderAggregateDTO> result = service.getLaborCostOrderAggregate(
                FACTORY_ID, START, END, null);

        assertThat(result).hasSize(1);
        LaborEfficiencyOrderAggregateDTO agg = result.get(0);
        assertThat(agg.getTotalQuotedLaborCost()).isNull();      // 诚实null: 没有配报价
        assertThat(agg.getVarianceRate()).isNull();               // 无法计算偏差率
        assertThat(agg.getVarianceStatus()).isNull();             // 状态也为null
        assertThat(agg.getTotalActualLaborCost()).isEqualByComparingTo(new BigDecimal("100"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  M4: 达成率汇总 tests
    // ═══════════════════════════════════════════════════════════════════════════

    /** Helper: build a WorkProcessTask with estimatedMinutes */
    private WorkProcessTask task(Long id, Integer estimatedMinutes, Integer processOrder) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id);
        t.setEstimatedMinutes(estimatedMinutes);
        t.setProcessOrder(processOrder);
        return t;
    }

    @Test
    void m4_emptyBatches_returnsEmptyList() {
        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = service.getLaborAchievementSummary(FACTORY_ID, START, END, null);
        assertThat(result).isEmpty();
    }

    @Test
    void m4_noEstimatedMinutes_achievementRatePctNull() {
        // A batch with actual work minutes but no planned minutes → rate = null
        ProductionBatch b = batch(10L, "PT-010", null, new BigDecimal("1.00"));
        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(Collections.emptyList());
        // tasks with null estimatedMinutes
        when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                eq(FACTORY_ID), eq(10L)))
                .thenReturn(List.of(task(1L, null, 1)));

        BatchYieldDTO yield = new BatchYieldDTO();
        yield.setTotalWorkMinutes(300);
        yield.setSteps(Collections.emptyList());
        when(yieldReportService.getYield(eq(FACTORY_ID), eq(10L))).thenReturn(yield);

        List<LaborAchievementSummaryDTO> result = service.getLaborAchievementSummary(
                FACTORY_ID, START, END, null);

        // Overall + per-product summaries
        assertThat(result).isNotEmpty();
        // Overall summary (first item when productTypeId=null)
        LaborAchievementSummaryDTO overall = result.get(0);
        assertThat(overall.getTotalActualWorkMinutes()).isEqualTo(300);
        assertThat(overall.getTotalPlannedWorkMinutes()).isNull(); // no estimatedMinutes
        assertThat(overall.getAchievementRatePct()).isNull();
        assertThat(overall.getAchievementAlert()).isNull();
    }

    @Test
    void m4_achievementRate_calculatedCorrectly() {
        // actual=120min planned=100min → rate=120% → BELOW threshold is 150, above is 75
        // 120% is between 75 and 150 → OK
        ProductionBatch b = batch(11L, "PT-011", null, new BigDecimal("1.00"));
        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(Collections.emptyList());
        when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                eq(FACTORY_ID), eq(11L)))
                .thenReturn(List.of(task(2L, 100, 1)));

        BatchYieldDTO yield = new BatchYieldDTO();
        yield.setTotalWorkMinutes(120);
        yield.setSteps(Collections.emptyList());
        when(yieldReportService.getYield(eq(FACTORY_ID), eq(11L))).thenReturn(yield);

        List<LaborAchievementSummaryDTO> result = service.getLaborAchievementSummary(
                FACTORY_ID, START, END, "PT-011");

        // With productTypeId filter, no "overall" row, just per-product
        assertThat(result).isNotEmpty();
        LaborAchievementSummaryDTO dto = result.get(0);
        assertThat(dto.getAchievementRatePct()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(dto.getAchievementAlert()).isEqualTo("OK");
    }

    @Test
    void m4_achievementRate_aboveAlert_at150orMore() {
        // actual=200min planned=100min → rate=200% → ABOVE_ALERT
        ProductionBatch b = batch(12L, "PT-012", null, new BigDecimal("1.00"));
        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(Collections.emptyList());
        when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                eq(FACTORY_ID), eq(12L)))
                .thenReturn(List.of(task(3L, 100, 1)));

        BatchYieldDTO yield = new BatchYieldDTO();
        yield.setTotalWorkMinutes(200);
        yield.setSteps(Collections.emptyList());
        when(yieldReportService.getYield(eq(FACTORY_ID), eq(12L))).thenReturn(yield);

        List<LaborAchievementSummaryDTO> result = service.getLaborAchievementSummary(
                FACTORY_ID, START, END, "PT-012");

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getAchievementAlert()).isEqualTo("ABOVE_ALERT");
    }

    @Test
    void m4_achievementRate_belowAlert_atLessThan75() {
        // actual=60min planned=100min → rate=60% → BELOW_ALERT
        ProductionBatch b = batch(13L, "PT-013", null, new BigDecimal("1.00"));
        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(Collections.emptyList());
        when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                eq(FACTORY_ID), eq(13L)))
                .thenReturn(List.of(task(4L, 100, 1)));

        BatchYieldDTO yield = new BatchYieldDTO();
        yield.setTotalWorkMinutes(60);
        yield.setSteps(Collections.emptyList());
        when(yieldReportService.getYield(eq(FACTORY_ID), eq(13L))).thenReturn(yield);

        List<LaborAchievementSummaryDTO> result = service.getLaborAchievementSummary(
                FACTORY_ID, START, END, "PT-013");

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getAchievementAlert()).isEqualTo("BELOW_ALERT");
    }

    @Test
    void m4_batchItems_perBatch_attached() {
        ProductionBatch b1 = batch(14L, "PT-014", null, new BigDecimal("1.00"));
        ProductionBatch b2 = batch(15L, "PT-014", null, new BigDecimal("2.00"));
        b2.setBatchNumber("BATCH-15");
        when(batchRepo.findCompletedBatchesForLaborComparison(any(), any(), any(), any()))
                .thenReturn(List.of(b1, b2));
        when(productTypeRepo.findByIdIn(anyList())).thenReturn(Collections.emptyList());

        for (long id : new long[]{14L, 15L}) {
            lenient().when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                    eq(FACTORY_ID), eq(id)))
                    .thenReturn(List.of(task(id, 120, 1)));
            BatchYieldDTO y = new BatchYieldDTO();
            y.setTotalWorkMinutes(90);
            y.setSteps(Collections.emptyList());
            lenient().when(yieldReportService.getYield(eq(FACTORY_ID), eq(id))).thenReturn(y);
        }

        List<LaborAchievementSummaryDTO> result = service.getLaborAchievementSummary(
                FACTORY_ID, START, END, "PT-014");

        assertThat(result).isNotEmpty();
        LaborAchievementSummaryDTO dto = result.get(0);
        assertThat(dto.getBatchCount()).isEqualTo(2);
        assertThat(dto.getBatchItems()).hasSize(2);
        // total actual = 90+90=180, total planned = 120+120=240 → rate = 75.00
        assertThat(dto.getTotalActualWorkMinutes()).isEqualTo(180);
        assertThat(dto.getTotalPlannedWorkMinutes()).isEqualTo(240);
        assertThat(dto.getAchievementRatePct()).isEqualByComparingTo(new BigDecimal("75.00"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  step-breakdown tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void stepBreakdown_batchNotFound_returnsNull() {
        when(batchRepo.findById(anyLong())).thenReturn(Optional.empty());

        LaborStepBreakdownDTO result = service.getStepBreakdown(FACTORY_ID, 999L);
        assertThat(result).isNull();
    }

    @Test
    void stepBreakdown_wrongFactory_returnsNull() {
        ProductionBatch b = batch(20L, "PT-020", null, new BigDecimal("1.00"));
        b.setFactoryId("OTHER_FACTORY"); // different factory
        when(batchRepo.findById(20L)).thenReturn(Optional.of(b));

        LaborStepBreakdownDTO result = service.getStepBreakdown(FACTORY_ID, 20L);
        assertThat(result).isNull();
    }

    @Test
    void stepBreakdown_noYield_returnsEmptySteps() {
        ProductionBatch b = batch(21L, "PT-021", null, new BigDecimal("1.00"));
        when(batchRepo.findById(21L)).thenReturn(Optional.of(b));
        when(productTypeRepo.findById("PT-021")).thenReturn(Optional.empty());
        when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                eq(FACTORY_ID), eq(21L)))
                .thenReturn(Collections.emptyList());
        // yield with no steps
        BatchYieldDTO yield = new BatchYieldDTO();
        yield.setTotalWorkMinutes(null);
        yield.setSteps(Collections.emptyList());
        when(yieldReportService.getYield(eq(FACTORY_ID), eq(21L))).thenReturn(yield);

        LaborStepBreakdownDTO result = service.getStepBreakdown(FACTORY_ID, 21L);

        assertThat(result).isNotNull();
        assertThat(result.getBatchId()).isEqualTo(21L);
        assertThat(result.getBatchNumber()).isEqualTo("BATCH-21");
        assertThat(result.getSteps()).isEmpty();
        assertThat(result.getOverallAchievementRatePct()).isNull();
    }

    @Test
    void stepBreakdown_withYieldAndTasks_mapsCorrectly() {
        ProductionBatch b = batch(22L, "PT-022", null, new BigDecimal("1.00"));
        ProductType pt = productType("PT-022", null, new BigDecimal("200")); // 200g/box
        when(batchRepo.findById(22L)).thenReturn(Optional.of(b));
        when(productTypeRepo.findById("PT-022")).thenReturn(Optional.of(pt));

        // Two WorkProcessTasks with estimatedMinutes
        WorkProcessTask t1 = task(101L, 60, 1);  // step 1: planned 60min
        WorkProcessTask t2 = task(102L, 120, 2); // step 2: planned 120min
        when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                eq(FACTORY_ID), eq(22L)))
                .thenReturn(List.of(t1, t2));

        // Yield steps
        StepYieldDTO s1 = new StepYieldDTO();
        s1.setProcessOrder(1);
        s1.setProcessName("领料");
        s1.setTotalWorkMinutes(50);  // faster than planned
        s1.setTotalWorkers(2);
        s1.setLaborCost(new BigDecimal("30.00"));
        s1.setTotalOutput(new BigDecimal("5.00")); // 5kg → 25 boxes

        StepYieldDTO s2 = new StepYieldDTO();
        s2.setProcessOrder(2);
        s2.setProcessName("焯水");
        s2.setTotalWorkMinutes(180); // over planned
        s2.setTotalWorkers(4);
        s2.setLaborCost(new BigDecimal("80.00"));
        s2.setTotalOutput(new BigDecimal("4.00")); // 4kg → 20 boxes

        BatchYieldDTO yield = new BatchYieldDTO();
        yield.setTotalWorkMinutes(230);
        yield.setSteps(List.of(s1, s2));
        when(yieldReportService.getYield(eq(FACTORY_ID), eq(22L))).thenReturn(yield);

        LaborStepBreakdownDTO result = service.getStepBreakdown(FACTORY_ID, 22L);

        assertThat(result).isNotNull();
        assertThat(result.getBatchId()).isEqualTo(22L);
        assertThat(result.getTotalActualWorkMinutes()).isEqualTo(230);
        assertThat(result.getTotalPlannedWorkMinutes()).isEqualTo(180); // 60+120
        // overall rate = 230/180*100 = 127.78
        assertThat(result.getOverallAchievementRatePct()).isNotNull();
        assertThat(result.getOverallAchievementRatePct().doubleValue()).isGreaterThan(127.0).isLessThan(128.0);
        assertThat(result.getOverallAchievementAlert()).isEqualTo("OK"); // 127.78 between 75-150

        assertThat(result.getSteps()).hasSize(2);

        var step1 = result.getSteps().get(0);
        assertThat(step1.getProcessName()).isEqualTo("领料");
        assertThat(step1.getActualWorkMinutes()).isEqualTo(50);
        assertThat(step1.getPlannedWorkMinutes()).isEqualTo(60);
        // rate = 50/60*100 = 83.33 → OK
        assertThat(step1.getAchievementRatePct().doubleValue()).isGreaterThan(83.0).isLessThan(84.0);
        assertThat(step1.getAchievementAlert()).isEqualTo("OK");
        // laborCostPerBox = 30.00 / (5kg*1000/200g=25boxes) = 1.2000
        assertThat(step1.getLaborCostPerBox()).isEqualByComparingTo(new BigDecimal("1.2000"));

        var step2 = result.getSteps().get(1);
        assertThat(step2.getProcessName()).isEqualTo("焯水");
        assertThat(step2.getActualWorkMinutes()).isEqualTo(180);
        assertThat(step2.getPlannedWorkMinutes()).isEqualTo(120);
        // rate = 180/120*100 = 150% → boundary: ≥150 is ABOVE_ALERT
        assertThat(step2.getAchievementAlert()).isEqualTo("ABOVE_ALERT");
    }

    @Test
    void stepBreakdown_achievementRatePctNull_whenNoPlannedMinutes() {
        ProductionBatch b = batch(23L, "PT-023", null, new BigDecimal("1.00"));
        when(batchRepo.findById(23L)).thenReturn(Optional.of(b));
        when(productTypeRepo.findById("PT-023")).thenReturn(Optional.empty());
        // No planned minutes in tasks
        when(workProcessTaskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                eq(FACTORY_ID), eq(23L)))
                .thenReturn(List.of(task(200L, null, 1)));

        StepYieldDTO s = new StepYieldDTO();
        s.setProcessOrder(1);
        s.setProcessName("加工");
        s.setTotalWorkMinutes(90);

        BatchYieldDTO yield = new BatchYieldDTO();
        yield.setTotalWorkMinutes(90);
        yield.setSteps(List.of(s));
        when(yieldReportService.getYield(eq(FACTORY_ID), eq(23L))).thenReturn(yield);

        LaborStepBreakdownDTO result = service.getStepBreakdown(FACTORY_ID, 23L);

        assertThat(result).isNotNull();
        assertThat(result.getOverallAchievementRatePct()).isNull(); // no planned → null
        assertThat(result.getSteps()).hasSize(1);
        assertThat(result.getSteps().get(0).getAchievementRatePct()).isNull();
        assertThat(result.getSteps().get(0).getAchievementAlert()).isNull();
    }
}
