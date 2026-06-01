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

/**
 * A3 跨批料归因集成测试 (spec §3.4, 6 用例).
 *
 * 背景场景:
 *   批次 A 道1 (焯水): 投入 998 kg, 保水产出 1126 kg
 *   批次 A 道2: 只取 520 kg 投入, 余料 carryover = 1126 - 520 = 606 kg
 *   批次 B 道1: 自有 100 kg + 引用 A 批次余料 606 kg (sourceBatchRefs), 产出 300 kg
 *
 * 数据层: YieldCalculationServiceImpl.calculateSteps 已实现 A3 跨批累加 (line 41-46).
 * 本测试为纯单元测试 (无 mock, 直接 build ProductionReport 对象调 calcSvc).
 */
class YieldCalculationCrossBatchTest {

    private final YieldCalculationService calcSvc = new YieldCalculationServiceImpl();

    /** Helper: 构造普通 YIELD 报工 (无 sourceBatchRefs). */
    private ProductionReport rpt(long taskId, int order, String in, String inUnit,
                                 String out, String outUnit) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(taskId).processOrder(order)
                .inputQuantity(new BigDecimal(in)).inputUnit(inUnit)
                .outputQuantity(new BigDecimal(out)).outputUnit(outUnit)
                .build();
    }

    /** Helper: 构造带单条 sourceBatchRef 的 YIELD 报工. */
    private ProductionReport rptWithSource(long taskId, int order,
                                           String in, String inUnit,
                                           String out, String outUnit,
                                           List<Map<String, Object>> sourceBatchRefs) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(2L).reportType("YIELD")
                .workProcessTaskId(taskId).processOrder(order)
                .inputQuantity(new BigDecimal(in)).inputUnit(inUnit)
                .outputQuantity(new BigDecimal(out)).outputUnit(outUnit)
                .sourceBatchRefs(sourceBatchRefs)
                .build();
    }

    /**
     * 用例 1: 跨批带入量计入 totalInput.
     * B道1: inputQty=100 + sourceBatchRefs=[{quantity_from_source:606}] → totalInput=706.
     */
    @Test
    void crossBatch_sourceAddsIntoTotalInput() {
        ProductionReport reportB1 = rptWithSource(10L, 1,
                "100", "kg", "300", "kg",
                List.of(Map.of(
                        "source_batch_id", 1L,
                        "source_work_process_task_id", 2L,
                        "quantity_from_source", 606,
                        "source_unit", "kg")));

        List<StepYieldDTO> steps = calcSvc.calculateSteps(List.of(reportB1));

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getTotalInput()).isEqualByComparingTo("706");
    }

    /**
     * 用例 2: 跨批出成率正确.
     * B道1: output=300, totalInput=706 → yieldRate = 300/706 = 0.4249 (scale 4 HALF_UP).
     */
    @Test
    void crossBatch_yieldRateCorrect() {
        ProductionReport reportB1 = rptWithSource(10L, 1,
                "100", "kg", "300", "kg",
                List.of(Map.of(
                        "source_batch_id", 1L,
                        "quantity_from_source", 606,
                        "source_unit", "kg")));

        List<StepYieldDTO> steps = calcSvc.calculateSteps(List.of(reportB1));

        // 300 / 706 = 0.42493... → HALF_UP scale 4 = 0.4249
        assertThat(steps.get(0).getYieldRate()).isEqualByComparingTo("0.4249");
    }

    /**
     * 用例 3: 无跨批时不受影响 (sourceBatchRefs=null → totalInput = own inputQuantity).
     */
    @Test
    void noCrossBatch_totalInputEqualsOwnInput() {
        ProductionReport report = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(20L).processOrder(1)
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .sourceBatchRefs(null)
                .build();

        List<StepYieldDTO> steps = calcSvc.calculateSteps(List.of(report));

        assertThat(steps.get(0).getTotalInput()).isEqualByComparingTo("100");
        assertThat(steps.get(0).getYieldRate()).isEqualByComparingTo("0.8000");
    }

    /**
     * 用例 4: 金标准回归 (猪舌无跨批, 3道).
     * 998→935.5→1262.9→382.08 (同单位 kg), cumulativeYieldRate=0.3828.
     * 确认 A 批次纯内部路径不被 B 批次 sourceBatchRefs 干扰.
     */
    @Test
    void goldStandard_cumulativeYieldRate_0_3828() {
        List<ProductionReport> reportsA = List.of(
                rpt(1L, 1, "998",    "kg", "935.5",  "kg"),
                rpt(2L, 2, "935.5",  "kg", "1262.9", "kg"),   // 滚揉保水 yieldRate > 1
                rpt(3L, 3, "1262.9", "kg", "382.08", "kg")
        );

        BatchYieldDTO dtoA = calcSvc.calculateBatchYield(reportsA, null);

        assertThat(dtoA.getFirstStepInput()).isEqualByComparingTo("998");
        assertThat(dtoA.getLastStepOutput()).isEqualByComparingTo("382.08");
        assertThat(dtoA.getCumulativeYieldRate()).isEqualByComparingTo("0.3828");
        assertThat(dtoA.getComplete()).isTrue();
    }

    /**
     * 用例 5: A 批次余料 carryover 正确.
     * 道1 输出 1126 kg, 道2 投入 520 kg → 道2 step.carryover = 1126 - 520 = 606.
     */
    @Test
    void batchA_carryover_isCorrect() {
        List<ProductionReport> reportsA = List.of(
                rpt(1L, 1, "998",  "kg", "1126", "kg"),   // 道1 焯水保水产出 1126
                rpt(2L, 2, "520",  "kg", "382",  "kg")    // 道2 只取 520 kg
        );

        List<StepYieldDTO> steps = calcSvc.calculateSteps(reportsA);

        StepYieldDTO step2 = steps.stream()
                .filter(s -> s.getProcessOrder() == 2)
                .findFirst().orElseThrow();
        // carryover = prevOutput(1126) - thisInput(520) = 606
        assertThat(step2.getCarryover()).isEqualByComparingTo("606");
    }

    /**
     * 用例 6: 多条 sourceBatchRef 累加.
     * 2 个来源批次 refs [{qfs:606},{qfs:200}] + own input=100 → totalInput = 906.
     */
    @Test
    void multipleSourceRefs_allAddedIntoTotalInput() {
        ProductionReport reportB1 = rptWithSource(10L, 1,
                "100", "kg", "450", "kg",
                List.of(
                        Map.of("source_batch_id", 1L, "quantity_from_source", 606, "source_unit", "kg"),
                        Map.of("source_batch_id", 2L, "quantity_from_source", 200, "source_unit", "kg")
                ));

        List<StepYieldDTO> steps = calcSvc.calculateSteps(List.of(reportB1));

        // totalInput = 100 (own) + 606 + 200 = 906
        assertThat(steps.get(0).getTotalInput()).isEqualByComparingTo("906");
    }
}
