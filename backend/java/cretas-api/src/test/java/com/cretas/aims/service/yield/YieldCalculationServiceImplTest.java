package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YieldCalculationServiceImplTest {

    private final YieldCalculationService svc = new YieldCalculationServiceImpl();

    private ProductionReport rpt(long taskId, int order, String in, String inUnit,
                                 String out, String outUnit) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(taskId).processOrder(order)
                .inputQuantity(new BigDecimal(in)).inputUnit(inUnit)
                .outputQuantity(new BigDecimal(out)).outputUnit(outUnit)
                .build();
    }

    @Test
    void cumulativeYield_matchesGoldStandard_0_3828() {
        // 猪舌简化链 (首投 998kg -> 末产 382.08kg, 累计 0.3828)
        List<ProductionReport> reports = List.of(
                rpt(1, 1, "998",    "kg", "935.5",  "kg"),
                rpt(2, 2, "935.5",  "kg", "1262.9", "kg"),   // 滚揉保水 yield 1.35
                rpt(3, 3, "1262.9", "kg", "382.08", "kg")
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getFirstStepInput()).isEqualByComparingTo("998");
        assertThat(dto.getLastStepOutput()).isEqualByComparingTo("382.08");
        assertThat(dto.getCumulativeYieldRate()).isEqualByComparingTo("0.3828");
        assertThat(dto.getComplete()).isTrue();
    }

    @Test
    void perStepYield_allowsAboveOne_forWaterRetention() {
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rpt(1, 1, "935.5", "kg", "1262.9", "kg")
        ));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getYieldRate()).isEqualByComparingTo("1.3500");
        assertThat(steps.get(0).getUnitComparable()).isTrue();
    }

    @Test
    void carryover_isPriorOutputMinusThisInput() {
        // 焯水(序2)产 998, 但去舌苔(序3)只投 360 -> 序3 carryover = 998 - 360 = 638
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rpt(1, 1, "1000", "kg", "998", "kg"),
                rpt(2, 2, "998",  "kg", "998", "kg"),
                rpt(3, 3, "360",  "kg", "300", "kg")
        ));
        StepYieldDTO step3 = steps.stream().filter(s -> s.getProcessOrder() == 3).findFirst().orElseThrow();
        assertThat(step3.getCarryover()).isEqualByComparingTo("638");
    }

    @Test
    void crossUnitStep_marksNotComparable_andNullsYield() {
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rpt(1, 1, "100", "kg", "500", "盒")   // 装盒 kg->盒
        ));
        assertThat(steps.get(0).getUnitComparable()).isFalse();
        assertThat(steps.get(0).getYieldRate()).isNull();
    }

    @Test
    void crossUnitBatch_convertsLastStepWithGramsPerUnit() {
        // 末道产 3184 盒, 每盒 120g = 382.08kg; 首投 998kg -> 0.3828
        List<ProductionReport> reports = List.of(
                rpt(1, 1, "998", "kg", "3184", "盒")
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, new BigDecimal("120"));
        assertThat(dto.getCumulativeYieldRate()).isEqualByComparingTo("0.3828");
    }

    @Test
    void crossUnitBatch_nullGramsPerUnit_cumulativeIsNull() {
        // step1: kg→kg (998→935.5); step2: kg→盒 (935.5→3184); no gramsPerUnit
        // cross-unit with no conversion factor → cumulativeYieldRate must be null
        List<ProductionReport> reports = List.of(
                rpt(1, 1, "998",   "kg", "935.5", "kg"),
                rpt(2, 2, "935.5", "kg", "3184",  "盒")
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getCumulativeYieldRate()).isNull();
        assertThat(dto.getLastStepOutput()).isEqualByComparingTo("3184");
    }

    // ── P1-3 (G4): 工时/人数聚合 ───────────────────────────────────────────────────

    private ProductionReport rptWork(long taskId, int order, String in, String out,
                                     Integer minutes, Integer workers) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(taskId).processOrder(order)
                .inputQuantity(new BigDecimal(in)).inputUnit("kg")
                .outputQuantity(new BigDecimal(out)).outputUnit("kg")
                .totalWorkMinutes(minutes).totalWorkers(workers)
                .build();
    }

    @Test
    void calculateSteps_sumsWorkMinutesAndWorkers_perStep() {
        // 同一道 (task 1) 两次报工: workers 2+3=5, minutes 60+90=150
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptWork(1, 1, "100", "80", 60, 2),
                rptWork(1, 1, "80", "70", 90, 3)
        ));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getTotalWorkMinutes()).isEqualTo(150);
        assertThat(steps.get(0).getTotalWorkers()).isEqualTo(5);
    }

    @Test
    void calculateSteps_allNullWork_keepsStepWorkNull() {
        // 全 null 工时/人数 → step 两字段保持 null (不是 0)
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rpt(1, 1, "100", "kg", "80", "kg")
        ));
        assertThat(steps.get(0).getTotalWorkMinutes()).isNull();
        assertThat(steps.get(0).getTotalWorkers()).isNull();
    }

    @Test
    void calculateBatchYield_sumsWorkAcrossSteps() {
        // 3 道 minutes [120,90,60]=270, workers [2,3,1]=6
        List<ProductionReport> reports = List.of(
                rptWork(1, 1, "998", "935.5", 120, 2),
                rptWork(2, 2, "935.5", "900", 90, 3),
                rptWork(3, 3, "900", "382", 60, 1)
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalWorkMinutes()).isEqualTo(270);
        assertThat(dto.getTotalWorkers()).isEqualTo(6);
    }

    @Test
    void calculateBatchYield_partialNullWork_sumsOnlyNonNull() {
        // 道1 minutes=120/workers=2, 道2 全 null → batch minutes=120, workers=2 (只合计非 null)
        List<ProductionReport> reports = List.of(
                rptWork(1, 1, "998", "935.5", 120, 2),
                rpt(2, 2, "935.5", "kg", "382", "kg")  // null work
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalWorkMinutes()).isEqualTo(120);
        assertThat(dto.getTotalWorkers()).isEqualTo(2);
    }

    @Test
    void calculateBatchYield_allNullWork_batchWorkNull() {
        // 全 null → batch 两字段 null
        List<ProductionReport> reports = List.of(
                rpt(1, 1, "998", "kg", "935.5", "kg"),
                rpt(2, 2, "935.5", "kg", "382", "kg")
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalWorkMinutes()).isNull();
        assertThat(dto.getTotalWorkers()).isNull();
    }

    @Test
    void a3_crossBatchSourceCountsIntoCurrentStepInput() {
        // 本道投入 100 + 跨批带入 50 = 150 input; 产 120 -> yield 0.8000
        ProductionReport r = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1)
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("120")).outputUnit("kg")
                .sourceBatchRefs(List.of(Map.of(
                        "source_batch_id", 9L,
                        "quantity_from_source", 50,
                        "source_unit", "kg")))
                .build();
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(r));
        assertThat(steps.get(0).getTotalInput()).isEqualByComparingTo("150");
        assertThat(steps.get(0).getYieldRate()).isEqualByComparingTo("0.8000");
    }
}
