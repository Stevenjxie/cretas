package com.cretas.aims.service.impl;

import com.cretas.aims.dto.laborefficiency.LaborEfficiencyCompareDTO;
import com.cretas.aims.dto.laborefficiency.LaborEfficiencyOrderAggregateDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP9 M3 / SP3 M3b — 人工双口径对比 + 订单级聚合服务单元测试 (pure POJO, no Spring context).
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
}
