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
    void calculateSteps_sumsWorkMinutes_butWorkersIsMaxPeak_perStep() {
        // 同一道 (task 1) 两次报工: minutes 60+90=150 (Σ);
        // 修 M2: 人数取 MAX(2,3)=3 峰值, 不是 SUM 5 (同批人力不重复计虚高)
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptWork(1, 1, "100", "80", 60, 2),
                rptWork(1, 1, "80", "70", 90, 3)
        ));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getTotalWorkMinutes()).isEqualTo(150);
        assertThat(steps.get(0).getTotalWorkers()).isEqualTo(3);   // MAX peak, 非 SUM 5
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
    void calculateBatchYield_sumsMinutesAcrossSteps_takesMaxWorkers() {
        // 3 道 minutes [120,90,60]=270 (Σ); workers [2,3,1]: Q1 批次级取 MAX=3 (同班工人跨道不 SUM)
        List<ProductionReport> reports = List.of(
                rptWork(1, 1, "998", "935.5", 120, 2),
                rptWork(2, 2, "935.5", "900", 90, 3),
                rptWork(3, 3, "900", "382", 60, 1)
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalWorkMinutes()).isEqualTo(270);
        assertThat(dto.getTotalWorkers()).isEqualTo(3);  // Q1: MAX(2,3,1)=3, 非 SUM(6)
    }

    /**
     * Q1: 两 step workers=10+10 (同班) → 批次级应 MAX=10, 修前 SUM=20 (夸大一倍)。
     */
    @Test
    @org.junit.jupiter.api.DisplayName("Q1: 批次级 totalWorkers 取 MAX 非 SUM (同班跨道不重复计)")
    void q1_batchWorkers_isMaxNotSum_sameCrewAcrossSteps() {
        // 两道各报 10 人 (同一班组分两道工序); 批次人数应是 10, 不是 20
        List<ProductionReport> reports = List.of(
                rptWork(1, 1, "100", "90", 60, 10),
                rptWork(2, 2, "90",  "80", 60, 10)
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalWorkers())
                .as("同班工人跨道批次人数应取 MAX=10, 修前 SUM=20")
                .isEqualTo(10);
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

    // ── G8 Wave 3 (A): 整批出成率跨天/跨多次报工汇总到原料批次级 ──────────────────────

    private ProductionReport rptDated(long taskId, int order, String in, String out, String date) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(taskId).processOrder(order)
                .inputQuantity(new BigDecimal(in)).inputUnit("kg")
                .outputQuantity(new BigDecimal(out)).outputUnit("kg")
                .reportDate(java.time.LocalDate.parse(date))
                .build();
    }

    @Test
    void calculateSteps_crossDay_sameTask_aggregatesInputAndOutput() {
        // A 口径核心: 同一道 (task 1) 跨两天报工 → totalInput/totalOutput 跨天求和 (分组键=task 非日期)。
        // Day1 投 300 产 280, Day2 投 200 产 190 → totalInput=500, totalOutput=470。
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptDated(1, 1, "300", "280", "2026-06-01"),
                rptDated(1, 1, "200", "190", "2026-06-02")
        ));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getTotalInput()).isEqualByComparingTo("500");
        assertThat(steps.get(0).getTotalOutput()).isEqualByComparingTo("470");
        // 整道出成率 = 470/500 = 0.9400
        assertThat(steps.get(0).getYieldRate()).isEqualByComparingTo("0.9400");
    }

    @Test
    void calculateBatchYield_crossDay_multiStep_endToEndAggregation() {
        // A 完工口径: 首道跨两天领进 998kg, 末道跨两天产出 382.08kg → 整批 = 382.08/998 = 0.3828。
        // 首道 (task1): Day1 投 600, Day2 投 398 → 首道总投入 998。
        // 中道 (task2): 滚揉保水。
        // 末道 (task3): Day1 产 200, Day2 产 182.08 → 末道总产出 382.08。
        List<ProductionReport> reports = List.of(
                rptDated(1, 1, "600",    "560",    "2026-06-01"),
                rptDated(1, 1, "398",    "375.5",  "2026-06-02"),   // 首道总: 投998 产935.5
                rptDated(2, 2, "935.5",  "1262.9", "2026-06-02"),   // 滚揉保水 1.35
                rptDated(3, 3, "700",    "200",    "2026-06-02"),
                rptDated(3, 3, "562.9",  "182.08", "2026-06-03")    // 末道总产出 382.08
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getFirstStepInput()).isEqualByComparingTo("998");      // 跨天首道汇总
        assertThat(dto.getLastStepOutput()).isEqualByComparingTo("382.08");   // 跨天末道汇总
        assertThat(dto.getCumulativeYieldRate()).isEqualByComparingTo("0.3828");
    }

    // ── 单元 A.4/A.5: 逐道成本聚合 ─────────────────────────────────────────────────

    private ProductionReport rptCost(long taskId, int order, String in, String out,
                                     String laborCost, String materialCost) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(taskId).processOrder(order)
                .inputQuantity(new BigDecimal(in)).inputUnit("kg")
                .outputQuantity(new BigDecimal(out)).outputUnit("kg")
                .laborCost(laborCost == null ? null : new BigDecimal(laborCost))
                .materialCost(materialCost == null ? null : new BigDecimal(materialCost))
                .build();
    }

    @Test
    void calculateSteps_sumsCostsPerStep_nullSafe() {
        // 同一道 (task 1) 两次报工: labor 60+0=60, material 1000+null=1000, step cost = 1060
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptCost(1, 1, "100", "80", "60.00", "1000.00"),
                rptCost(1, 1, "80", "70", "0.00", null)
        ));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getLaborCost()).isEqualByComparingTo("60.00");
        assertThat(steps.get(0).getMaterialCost()).isEqualByComparingTo("1000.00");
        assertThat(steps.get(0).getStepCost()).isEqualByComparingTo("1060.00");
    }

    @Test
    void calculateSteps_allNullCost_keepsStepCostNull() {
        // 全 null 成本 → step 三字段保持 null (不是 0)
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rpt(1, 1, "100", "kg", "80", "kg")
        ));
        assertThat(steps.get(0).getLaborCost()).isNull();
        assertThat(steps.get(0).getMaterialCost()).isNull();
        assertThat(steps.get(0).getStepCost()).isNull();
    }

    @Test
    void calculateBatchYield_sumsCostsAcrossSteps_nullSafe() {
        // 道1 labor=60 material=1000, 道2 labor=40 material=null → batch labor=100, material=1000, total=1100
        List<ProductionReport> reports = List.of(
                rptCost(1, 1, "998", "935.5", "60.00", "1000.00"),
                rptCost(2, 2, "935.5", "382", "40.00", null)
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalLaborCost()).isEqualByComparingTo("100.00");
        assertThat(dto.getTotalMaterialCost()).isEqualByComparingTo("1000.00");
        assertThat(dto.getTotalCost()).isEqualByComparingTo("1100.00");
    }

    @Test
    void calculateBatchYield_allNullCost_batchCostNull() {
        // 全 null 成本 → batch 三字段 null (绝不默认 0)
        List<ProductionReport> reports = List.of(
                rpt(1, 1, "998", "kg", "935.5", "kg"),
                rpt(2, 2, "935.5", "kg", "382", "kg")
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalLaborCost()).isNull();
        assertThat(dto.getTotalMaterialCost()).isNull();
        assertThat(dto.getTotalCost()).isNull();
    }

    // ── 适配单元3: 证据/工时段/副产物/损耗/留样 聚合 + totalWorkers MAX (修 M2) ──────────

    private ProductionReport rptTraditional(long taskId, int order, String in, String out,
                                            List<String> photos,
                                            List<Map<String, Object>> laborSegments,
                                            List<Map<String, Object>> byproducts,
                                            String waste, Integer sampleRetain,
                                            Integer workers) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(taskId).processOrder(order)
                .inputQuantity(new BigDecimal(in)).inputUnit("kg")
                .outputQuantity(new BigDecimal(out)).outputUnit("kg")
                .photos(photos)
                .laborSegments(laborSegments)
                .byproducts(byproducts)
                .wasteQuantity(waste == null ? null : new BigDecimal(waste))
                .sampleRetainQuantity(sampleRetain)
                .totalWorkers(workers)
                .build();
    }

    @Test
    void calculateSteps_mergesPhotosByproducts_sumsWasteSample_maxWorkers() {
        // 同一道 (task 1) 两次报工:
        //   报工A: photos[p1,p2], 副产物[料头10], waste 5, sample null, workers 12
        //   报工B: photos[p2,p3], 副产物[肥油3],  waste 2, sample 4,   workers 9
        // 期望: photos 合并去重 = [p1,p2,p3]; byproducts 拼接 2 条; waste = 7; sample = 4; workers MAX = 12 (非 21)
        Map<String, Object> bpA = Map.of("name", "料头", "quantity", new BigDecimal("10"), "unit", "kg");
        Map<String, Object> bpB = Map.of("name", "肥油", "quantity", new BigDecimal("3"), "unit", "kg");
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptTraditional(1, 1, "100", "80", List.of("p1", "p2"), null, List.of(bpA), "5", null, 12),
                rptTraditional(1, 1, "80", "70", List.of("p2", "p3"), null, List.of(bpB), "2", 4, 9)
        ));
        assertThat(steps).hasSize(1);
        StepYieldDTO s = steps.get(0);
        assertThat(s.getPhotos()).containsExactly("p1", "p2", "p3");   // 去重保序
        assertThat(s.getByproducts()).hasSize(2);
        assertThat(s.getWasteQuantity()).isEqualByComparingTo("7");
        assertThat(s.getSampleRetainQuantity()).isEqualTo(4);
        assertThat(s.getTotalWorkers()).isEqualTo(12);                 // MAX peak, 不是 SUM 21 (修 M2)
    }

    @Test
    void calculateSteps_allTraditionalNull_keepsFieldsNull() {
        // 无证据/副产物/损耗/留样 → step 字段保持 null (绝不默认空 list / 0)
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rpt(1, 1, "100", "kg", "80", "kg")
        ));
        StepYieldDTO s = steps.get(0);
        assertThat(s.getPhotos()).isNull();
        assertThat(s.getByproducts()).isNull();
        assertThat(s.getWasteQuantity()).isNull();
        assertThat(s.getSampleRetainQuantity()).isNull();
    }

    @Test
    void calculateBatchYield_sumsWasteAndSampleAcrossSteps() {
        // 道1 waste 5 sample null, 道2 waste 2 sample 4 → totalWaste 7, totalSampleRetain 4
        List<ProductionReport> reports = List.of(
                rptTraditional(1, 1, "998", "935.5", null, null, null, "5", null, 12),
                rptTraditional(2, 2, "935.5", "382", null, null, null, "2", 4, 9)
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalWaste()).isEqualByComparingTo("7");
        assertThat(dto.getTotalSampleRetain()).isEqualTo(4);
    }

    @Test
    void calculateBatchYield_allNullWasteSample_batchFieldsNull() {
        List<ProductionReport> reports = List.of(
                rpt(1, 1, "998", "kg", "935.5", "kg"),
                rpt(2, 2, "935.5", "kg", "382", "kg")
        );
        BatchYieldDTO dto = svc.calculateBatchYield(reports, null);
        assertThat(dto.getTotalWaste()).isNull();
        assertThat(dto.getTotalSampleRetain()).isNull();
    }

    // ── 三阶段报工 (单元1): phase 推断 + 照片按 reportKind 分组 ──────────────────────

    private ProductionReport rptKind(long taskId, int order, String reportKind,
                                     String in, String out, List<String> photos) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(taskId).processOrder(order)
                .reportKind(reportKind)
                .inputQuantity(in == null ? null : new BigDecimal(in)).inputUnit("kg")
                .outputQuantity(out == null ? null : new BigDecimal(out)).outputUnit("kg")
                .photos(photos)
                .build();
    }

    @Test
    void calculateSteps_phase_inputOnly_isInProduction() {
        // 仅 INPUT report → IN_PRODUCTION
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptKind(1, 1, "INPUT", "998", null, null)
        ));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getPhase()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void calculateSteps_phase_inputThenOutput_isCompleted() {
        // INPUT + OUTPUT → COMPLETED
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptKind(1, 1, "INPUT", "998", null, null),
                rptKind(1, 1, "OUTPUT", null, "980", null)
        ));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getPhase()).isEqualTo("COMPLETED");
    }

    @Test
    void calculateSteps_phasedSplit_unitsOnSeparateReports_yieldRateStillComputed() {
        // 回归: 三阶段真实场景 INPUT report 只有 inputUnit(outputUnit=null), OUTPUT report 只有 outputUnit(inputUnit=null)。
        // 修复前 unit 取自 group.get(0)(INPUT)→ outputUnit=null → 误判不可比 → yieldRate 丢失。
        ProductionReport input = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1).reportKind("INPUT")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .build();   // outputUnit 故意 null
        ProductionReport output = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1).reportKind("OUTPUT")
                .outputQuantity(new BigDecimal("980")).outputUnit("kg")
                .build();   // inputUnit 故意 null
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(input, output));
        assertThat(steps).hasSize(1);
        StepYieldDTO s = steps.get(0);
        assertThat(s.getUnitComparable()).isTrue();
        assertThat(s.getYieldRate()).isNotNull();
        assertThat(s.getYieldRate()).isEqualByComparingTo(
                new BigDecimal("980").divide(new BigDecimal("998"), 4, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void calculateSteps_phase_segmentOnly_isAwaitingInput() {
        // 仅 SEGMENT (无投入无产出) → AWAITING_INPUT (尚无投入锚定)
        Map<String, Object> seg = Map.of("startTime", "08:00", "endTime", "10:00", "headcount", 3);
        ProductionReport r = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1)
                .reportKind("SEGMENT").inputUnit("kg").outputUnit("kg")
                .laborSegments(List.of(seg))
                .build();
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(r));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getPhase()).isEqualTo("AWAITING_INPUT");
    }

    @Test
    void calculateSteps_phase_legacy_inputAndOutput_isCompleted() {
        // 旧式报工 (reportKind null) 一次带投入+产出 → COMPLETED (按 input/output 有无推断)
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rpt(1, 1, "100", "kg", "80", "kg")   // reportKind 默认 null
        ));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getPhase()).isEqualTo("COMPLETED");
    }

    @Test
    void calculateSteps_phase_legacy_inputOnly_isInProduction() {
        // 旧式 reportKind null, 仅投入 → IN_PRODUCTION
        ProductionReport r = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1)
                .inputQuantity(new BigDecimal("100")).inputUnit("kg").outputUnit("kg")
                .build();
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(r));
        assertThat(steps.get(0).getPhase()).isEqualTo("IN_PRODUCTION");
    }

    @Test
    void calculateSteps_photos_groupedByReportKind() {
        // INPUT 照片 photoA → inputPhotos; OUTPUT 照片 photoB → outputPhotos
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptKind(1, 1, "INPUT", "998", null, List.of("photoA")),
                rptKind(1, 1, "OUTPUT", null, "980", List.of("photoB"))
        ));
        assertThat(steps).hasSize(1);
        StepYieldDTO s = steps.get(0);
        assertThat(s.getInputPhotos()).containsExactly("photoA");
        assertThat(s.getOutputPhotos()).containsExactly("photoB");
        // 兼容字段 photos 仍含全部 (合并去重)
        assertThat(s.getPhotos()).containsExactly("photoA", "photoB");
    }

    @Test
    void calculateSteps_photos_segmentGroupsToInputPhotos() {
        // SEGMENT 照片归 inputPhotos (与 INPUT 同组)
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(
                rptKind(1, 1, "INPUT", "998", null, List.of("pIn")),
                rptKind(1, 1, "SEGMENT", null, null, List.of("pSeg")),
                rptKind(1, 1, "OUTPUT", null, "980", List.of("pOut"))
        ));
        StepYieldDTO s = steps.get(0);
        assertThat(s.getInputPhotos()).containsExactly("pIn", "pSeg");
        assertThat(s.getOutputPhotos()).containsExactly("pOut");
    }

    @Test
    void calculateSteps_photos_legacyNullKindGroupsToInputPhotos() {
        // 旧式 reportKind null 的照片归 inputPhotos (向后兼容)
        ProductionReport r = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1)
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .photos(List.of("legacyPhoto"))
                .build();
        List<StepYieldDTO> steps = svc.calculateSteps(List.of(r));
        StepYieldDTO s = steps.get(0);
        assertThat(s.getInputPhotos()).containsExactly("legacyPhoto");
        assertThat(s.getOutputPhotos()).isNull();
    }

    @Test
    void calculateSteps_threeReportAccumulation_phasedEndToEnd() {
        // 单元1 端到端: INPUT(998, photoA) + SEGMENT(工时段, materialless) + OUTPUT(980, photoB, byproduct)
        // → totalInput 998, totalOutput 980, 出成率 980/998, inputPhotos[A], outputPhotos[B], phase COMPLETED
        Map<String, Object> seg = Map.of("startTime", "08:00", "endTime", "10:00", "headcount", 3);
        Map<String, Object> bp = Map.of("name", "骨头", "quantity", new BigDecimal("18"), "unit", "kg");
        ProductionReport input = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1).reportKind("INPUT")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg").outputUnit("kg")
                .materialCost(new BigDecimal("1000.00"))
                .photos(List.of("photoA"))
                .build();
        ProductionReport segment = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1).reportKind("SEGMENT")
                .inputUnit("kg").outputUnit("kg")
                .laborSegments(List.of(seg))
                .laborCost(new BigDecimal("180.00"))
                .totalWorkMinutes(120).totalWorkers(3)
                .build();
        ProductionReport output = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD")
                .workProcessTaskId(1L).processOrder(1).reportKind("OUTPUT")
                .outputQuantity(new BigDecimal("980")).inputUnit("kg").outputUnit("kg")
                .byproducts(List.of(bp))
                .photos(List.of("photoB"))
                .build();

        List<StepYieldDTO> steps = svc.calculateSteps(List.of(input, segment, output));
        assertThat(steps).hasSize(1);
        StepYieldDTO s = steps.get(0);
        assertThat(s.getTotalInput()).isEqualByComparingTo("998");
        assertThat(s.getTotalOutput()).isEqualByComparingTo("980");
        assertThat(s.getYieldRate()).isEqualByComparingTo("0.9820");  // 980/998
        assertThat(s.getLaborCost()).isEqualByComparingTo("180.00");
        assertThat(s.getMaterialCost()).isEqualByComparingTo("1000.00");
        assertThat(s.getInputPhotos()).containsExactly("photoA");
        assertThat(s.getOutputPhotos()).containsExactly("photoB");
        assertThat(s.getByproducts()).hasSize(1);
        assertThat(s.getPhase()).isEqualTo("COMPLETED");
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

    // ==================== 计划级免工序报工 两点出成率口径 (六扇门 Wave2 V20261017_01) ====================

    @Test
    void twoPointYield_materialInputPlusFinalOutput_cumulativeIsOutputOverInput() {
        // 免工序报工: 仅 2 个批次级哨兵任务报工
        //   task1 (MATERIAL_INPUT, order=0): INPUT report, 领料 998kg (无产出)
        //   task2 (FINAL_OUTPUT,  order=9999): OUTPUT report, 产出 382.08kg (无投入)
        // 现有 report-driven 链按 workProcessTaskId 分组, cumulative = lastOutput/firstInput = 382.08/998 = 0.3828
        // 无需改 calculateBatchYield —— 两点口径天然落在首步 input / 末步 output。
        ProductionReport inputReport = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD").reportKind("INPUT")
                .workProcessTaskId(1L).processOrder(0)
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .build();   // outputQuantity null (领料阶段无产出)
        ProductionReport outputReport = ProductionReport.builder()
                .factoryId("F006").batchId(1L).reportType("YIELD").reportKind("OUTPUT")
                .workProcessTaskId(2L).processOrder(9999)
                .outputQuantity(new BigDecimal("382.08")).outputUnit("kg")
                .build();   // inputQuantity null (产出阶段无投入)

        BatchYieldDTO dto = svc.calculateBatchYield(List.of(inputReport, outputReport), null);

        assertThat(dto.getSteps()).hasSize(2);
        assertThat(dto.getFirstStepInput()).isEqualByComparingTo("998");
        assertThat(dto.getLastStepOutput()).isEqualByComparingTo("382.08");
        assertThat(dto.getCumulativeYieldRate()).isEqualByComparingTo("0.3828");
        // 两点模式人工不报 → 全 step laborCost null → 整批 totalLaborCost null (诚实, 登下一期)
        assertThat(dto.getTotalLaborCost()).isNull();
    }

    @Test
    void twoPointYield_costOnlyMaterial_laborNullWhenNoSegmentReport() {
        // 两点模式: INPUT report 带材料成本 (领料折价), 无 SEGMENT report → laborCost null。
        // 整批成本 = 材料 only; 人工 null 诚实传播 (绝不默认 0)。
        ProductionReport inputReport = ProductionReport.builder()
                .factoryId("F006").batchId(2L).reportType("YIELD").reportKind("INPUT")
                .workProcessTaskId(10L).processOrder(0)
                .inputQuantity(new BigDecimal("500")).inputUnit("kg")
                .materialCost(new BigDecimal("3200.00"))   // 领料折价
                .build();
        ProductionReport outputReport = ProductionReport.builder()
                .factoryId("F006").batchId(2L).reportType("YIELD").reportKind("OUTPUT")
                .workProcessTaskId(11L).processOrder(9999)
                .outputQuantity(new BigDecimal("270")).outputUnit("kg")
                .build();

        BatchYieldDTO dto = svc.calculateBatchYield(List.of(inputReport, outputReport), null);

        assertThat(dto.getTotalMaterialCost()).isEqualByComparingTo("3200.00");
        assertThat(dto.getTotalLaborCost()).isNull();       // 人工两点不报
        assertThat(dto.getTotalCost()).isEqualByComparingTo("3200.00");  // = 材料 only
        assertThat(dto.getCumulativeYieldRate()).isEqualByComparingTo("0.5400");  // 270/500
    }
}
