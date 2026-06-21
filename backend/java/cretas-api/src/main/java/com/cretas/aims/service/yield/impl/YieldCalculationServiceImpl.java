package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.service.yield.YieldCalculationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class YieldCalculationServiceImpl implements YieldCalculationService {

    private static final int YIELD_SCALE = 4;

    @Override
    public List<StepYieldDTO> calculateSteps(List<ProductionReport> reports) {
        // 按 workProcessTaskId 分组, 保持 processOrder 升序
        Map<Long, List<ProductionReport>> byTask = new LinkedHashMap<>();
        reports.stream()
                .sorted((a, b) -> {
                    int ao = a.getProcessOrder() == null ? 0 : a.getProcessOrder();
                    int bo = b.getProcessOrder() == null ? 0 : b.getProcessOrder();
                    return Integer.compare(ao, bo);
                })
                .forEach(r -> byTask.computeIfAbsent(r.getWorkProcessTaskId(), k -> new ArrayList<>()).add(r));

        List<StepYieldDTO> steps = new ArrayList<>();
        BigDecimal prevOutput = null;
        for (Map.Entry<Long, List<ProductionReport>> e : byTask.entrySet()) {
            List<ProductionReport> group = e.getValue();
            BigDecimal totalInput = BigDecimal.ZERO;
            BigDecimal totalOutput = BigDecimal.ZERO;
            // P1-3 (G4): null-safe 工时/人数聚合 — 全 null 保持 null, 任一非 null 则求和
            Integer stepMinutes = null;
            Integer stepWorkers = null;
            // A.4/A.5: null-safe 成本聚合 — 全 null 保持 null, 任一非 null 则求和 (绝不默认 0)
            BigDecimal stepLaborCost = null;
            BigDecimal stepMaterialCost = null;
            // 适配单元3: 证据/工时段/副产物 合并 — 全空保持 null; 损耗/留样 null-safe Σ; 人数 MAX peak (修 M2)
            List<String> stepPhotos = null;          // 合并去重 (保序)
            BigDecimal stepProcessedQuantity = null;
            String stepProcessedUnit = null;
            BigDecimal stepStageOutputQuantity = null;
            String stepStageOutputUnit = null;
            BigDecimal stepSegmentWasteQuantity = null;
            String stepSegmentWasteUnit = null;
            List<Map<String, Object>> stepLaborSegments = null;  // 拼接 (全段明细)
            List<Map<String, Object>> stepByproducts = null;     // 拼接
            BigDecimal stepWaste = null;
            Integer stepSampleRetain = null;
            String stepCostCategory = null;          // CALC-003: 本道成本类别 (取首个非 null)
            List<Map<String, Object>> stepPackagingDetail = null;  // AUDIT-002: 包装明细拼接
            Integer stepWorkersMax = null;           // MAX headcount across reports (peak, 非 SUM)
            // 三阶段 (单元1): 照片按 reportKind 分组 (INPUT/SEGMENT/legacy → inputPhotos; OUTPUT → outputPhotos)
            List<String> stepInputPhotos = null;     // 去重保序
            List<String> stepOutputPhotos = null;    // 去重保序
            // T161 per-photo annotation 与照片并行分组 (保序; null = 无标注)
            List<Map<String, Object>> stepInputPhotoAnnotations = null;
            List<Map<String, Object>> stepOutputPhotoAnnotations = null;
            // SP1 双产出聚合 (本道各次报工: outputKind 取首个非 null; semiOutputQuantity Σ)
            String stepOutputKind = null;
            BigDecimal stepSemiOutputQuantity = null;
            String stepSemiOutputUnit = null;
            String stepSemiCode = null;
            // 三阶段 phase 推断信号: 是否有 INPUT 报工 / 有 OUTPUT 报工 (或 legacy: 有投入 / 有产出)
            boolean hasInputSignal = false;
            boolean hasOutputSignal = false;
            for (ProductionReport r : group) {
                String kind = r.getReportKind();
                boolean kindOutput = "OUTPUT".equals(kind);
                boolean kindInput = "INPUT".equals(kind);
                // phase 信号: 三阶段按 reportKind; legacy (kind null) 按 input/output 有无 (与三阶段语义一致)
                if (kindOutput || (kind == null && r.getOutputQuantity() != null
                        && r.getOutputQuantity().compareTo(BigDecimal.ZERO) > 0)) {
                    hasOutputSignal = true;
                }
                if (kindInput || (kind == null && r.getInputQuantity() != null
                        && r.getInputQuantity().compareTo(BigDecimal.ZERO) > 0)) {
                    hasInputSignal = true;
                }
                // 照片分组: OUTPUT 报工 → outputPhotos; INPUT/SEGMENT/legacy(null) → inputPhotos
                if (r.getPhotos() != null && !r.getPhotos().isEmpty()) {
                    if (kindOutput) {
                        if (stepOutputPhotos == null) stepOutputPhotos = new ArrayList<>();
                        for (String p : r.getPhotos()) {
                            if (p != null && !stepOutputPhotos.contains(p)) stepOutputPhotos.add(p);
                        }
                    } else {
                        if (stepInputPhotos == null) stepInputPhotos = new ArrayList<>();
                        for (String p : r.getPhotos()) {
                            if (p != null && !stepInputPhotos.contains(p)) stepInputPhotos.add(p);
                        }
                    }
                }
                // T161 per-photo annotation: 同 kindOutput 逻辑与 photos 并行分组 (保全量, 无去重)
                if (r.getPhotoAnnotations() != null && !r.getPhotoAnnotations().isEmpty()) {
                    if (kindOutput) {
                        if (stepOutputPhotoAnnotations == null) stepOutputPhotoAnnotations = new ArrayList<>();
                        stepOutputPhotoAnnotations.addAll(r.getPhotoAnnotations());
                    } else {
                        if (stepInputPhotoAnnotations == null) stepInputPhotoAnnotations = new ArrayList<>();
                        stepInputPhotoAnnotations.addAll(r.getPhotoAnnotations());
                    }
                }
                if (r.getInputQuantity() != null) totalInput = totalInput.add(r.getInputQuantity());
                // A3: 跨批带入计入当前道 input
                if (r.getSourceBatchRefs() != null) {
                    for (Map<String, Object> ref : r.getSourceBatchRefs()) {
                        Object q = ref.get("quantity_from_source");
                        if (q != null) totalInput = totalInput.add(new BigDecimal(q.toString()));
                    }
                }
                if (r.getOutputQuantity() != null) totalOutput = totalOutput.add(r.getOutputQuantity());
                if (r.getTotalWorkMinutes() != null) {
                    stepMinutes = (stepMinutes == null ? 0 : stepMinutes) + r.getTotalWorkMinutes();
                }
                if (r.getTotalWorkers() != null) {
                    // 修 M2: 同一道多次报工的人数取 MAX (峰值人力), 不是 SUM (避免重复计同批人力虚高)。
                    // submitReport 已把多段工时的本道人数收敛为 MAX headcount; 这里跨次再取 MAX。
                    stepWorkersMax = (stepWorkersMax == null ? r.getTotalWorkers()
                            : Math.max(stepWorkersMax, r.getTotalWorkers()));
                }
                if (r.getLaborCost() != null) {
                    stepLaborCost = (stepLaborCost == null ? BigDecimal.ZERO : stepLaborCost).add(r.getLaborCost());
                }
                if (r.getMaterialCost() != null) {
                    stepMaterialCost = (stepMaterialCost == null ? BigDecimal.ZERO : stepMaterialCost).add(r.getMaterialCost());
                }
                // 适配单元3: 证据合并去重 (保序)
                if (r.getPhotos() != null && !r.getPhotos().isEmpty()) {
                    if (stepPhotos == null) stepPhotos = new ArrayList<>();
                    for (String p : r.getPhotos()) {
                        if (p != null && !stepPhotos.contains(p)) stepPhotos.add(p);
                    }
                }
                // 工时段拼接 (全段明细, 不去重 — 每段独立)
                if (r.getLaborSegments() != null && !r.getLaborSegments().isEmpty()) {
                    if (stepLaborSegments == null) stepLaborSegments = new ArrayList<>();
                    stepLaborSegments.addAll(r.getLaborSegments());
                    for (Map<String, Object> segment : r.getLaborSegments()) {
                        stepProcessedQuantity = addMapDecimal(stepProcessedQuantity, segment, "processedQuantity");
                        stepProcessedUnit = firstMapString(stepProcessedUnit, segment, "processedUnit");
                        stepStageOutputQuantity = addMapDecimal(stepStageOutputQuantity, segment, "stageOutputQuantity");
                        stepStageOutputUnit = firstMapString(stepStageOutputUnit, segment, "stageOutputUnit");
                        stepSegmentWasteQuantity = addMapDecimal(stepSegmentWasteQuantity, segment, "segmentWasteQuantity");
                        stepSegmentWasteUnit = firstMapString(stepSegmentWasteUnit, segment, "segmentWasteUnit");
                    }
                }
                // 副产物拼接
                if (r.getByproducts() != null && !r.getByproducts().isEmpty()) {
                    if (stepByproducts == null) stepByproducts = new ArrayList<>();
                    stepByproducts.addAll(r.getByproducts());
                }
                // 损耗 null-safe Σ
                if (r.getWasteQuantity() != null) {
                    stepWaste = (stepWaste == null ? BigDecimal.ZERO : stepWaste).add(r.getWasteQuantity());
                }
                // 留样 null-safe Σ (通常仅末道有)
                if (r.getSampleRetainQuantity() != null) {
                    stepSampleRetain = (stepSampleRetain == null ? 0 : stepSampleRetain) + r.getSampleRetainQuantity();
                }
                // CALC-003 成本类别: 本道取首个非 null (同道各次报工应一致)
                if (stepCostCategory == null && r.getCostCategory() != null) {
                    stepCostCategory = r.getCostCategory();
                }
                // AUDIT-002 包装明细拼接 (本道各次报工)
                if (r.getPackagingDetail() != null && !r.getPackagingDetail().isEmpty()) {
                    if (stepPackagingDetail == null) stepPackagingDetail = new ArrayList<>();
                    stepPackagingDetail.addAll(r.getPackagingDetail());
                }
                // SP1 双产出: outputKind 取首个非 null; semiOutputQuantity Σ; semiCode/semiOutputUnit 取首个非 null
                if (stepOutputKind == null && r.getOutputKind() != null) {
                    stepOutputKind = r.getOutputKind();
                }
                if (r.getSemiOutputQuantity() != null) {
                    stepSemiOutputQuantity = (stepSemiOutputQuantity == null ? BigDecimal.ZERO : stepSemiOutputQuantity)
                            .add(r.getSemiOutputQuantity());
                }
                if (stepSemiOutputUnit == null && r.getSemiOutputUnit() != null) {
                    stepSemiOutputUnit = r.getSemiOutputUnit();
                }
                if (stepSemiCode == null && r.getSemiCode() != null) {
                    stepSemiCode = r.getSemiCode();
                }
            }
            stepWorkers = stepWorkersMax;
            // 本道总成本 = labor + material (两者全 null → null; 任一非 null → 该项视为 0 参与求和)
            BigDecimal stepCost = nullSafeAdd(stepLaborCost, stepMaterialCost);
            ProductionReport head = group.get(0);
            // 三阶段 (单元1): 投入单位在 INPUT report、产出单位在 OUTPUT report (各自另一侧 null),
            // 不能只看 group.get(0)(否则 INPUT report 的 outputUnit=null → 误判不可比 → 出成率丢失)。
            // 扫全组取首个非 null 的 inputUnit / outputUnit。
            String inUnit = group.stream().map(ProductionReport::getInputUnit)
                    .filter(u -> u != null).findFirst().orElse(null);
            String outUnit = group.stream().map(ProductionReport::getOutputUnit)
                    .filter(u -> u != null).findFirst().orElse(null);
            boolean comparable = inUnit != null && inUnit.equals(outUnit);
            BigDecimal yieldRate = null;
            if (comparable && totalInput.compareTo(BigDecimal.ZERO) > 0) {
                yieldRate = totalOutput.divide(totalInput, YIELD_SCALE, RoundingMode.HALF_UP);
            }
            BigDecimal carryover = prevOutput == null ? null : prevOutput.subtract(totalInput);

            // 三阶段 (单元1): phase 推断 — 有产出信号 → COMPLETED; 有投入信号(无产出) → IN_PRODUCTION; 否则 AWAITING_INPUT
            String phase = hasOutputSignal ? "COMPLETED"
                    : (hasInputSignal ? "IN_PRODUCTION" : "AWAITING_INPUT");

            steps.add(StepYieldDTO.builder()
                    .workProcessTaskId(head.getWorkProcessTaskId())
                    .processOrder(head.getProcessOrder())
                    .totalInput(totalInput)
                    .totalOutput(totalOutput)
                    .inputUnit(inUnit)
                    .outputUnit(outUnit)
                    .yieldRate(yieldRate)
                    .unitComparable(comparable)
                    .carryover(carryover)
                    .totalWorkMinutes(stepMinutes)
                    .totalWorkers(stepWorkers)
                    .laborCost(stepLaborCost)
                    .materialCost(stepMaterialCost)
                    .stepCost(stepCost)
                    .photos(stepPhotos)
                    .laborSegments(stepLaborSegments)
                    .processedQuantity(stepProcessedQuantity)
                    .processedUnit(stepProcessedUnit)
                    .stageOutputQuantity(stepStageOutputQuantity)
                    .stageOutputUnit(stepStageOutputUnit)
                    .segmentWasteQuantity(stepSegmentWasteQuantity)
                    .segmentWasteUnit(stepSegmentWasteUnit)
                    .byproducts(stepByproducts)
                    .wasteQuantity(stepWaste)
                    .sampleRetainQuantity(stepSampleRetain)
                    .costCategory(stepCostCategory)
                    .packagingDetail(stepPackagingDetail)
                    // 三阶段 (单元1): phase 推断 + 照片按 reportKind 分组
                    .phase(phase)
                    .inputPhotos(stepInputPhotos)
                    .outputPhotos(stepOutputPhotos)
                    // T161 per-photo annotation (并行分组结果)
                    .inputPhotoAnnotations(stepInputPhotoAnnotations)
                    .outputPhotoAnnotations(stepOutputPhotoAnnotations)
                    // SP1 双产出
                    .outputKind(stepOutputKind)
                    .semiOutputQuantity(stepSemiOutputQuantity)
                    .semiOutputUnit(stepSemiOutputUnit)
                    .semiCode(stepSemiCode)
                    .build());
            prevOutput = totalOutput;
        }
        return steps;
    }

    @Override
    public BatchYieldDTO calculateBatchYield(List<ProductionReport> reports, BigDecimal standardGramsPerUnit) {
        List<StepYieldDTO> steps = calculateSteps(reports);
        if (steps.isEmpty()) {
            return BatchYieldDTO.builder().steps(steps).complete(false).build();
        }
        StepYieldDTO first = steps.get(0);
        StepYieldDTO last = steps.get(steps.size() - 1);
        BigDecimal firstInput = first.getTotalInput();
        BigDecimal lastOutput = last.getTotalOutput();

        // 末道折算到首道单位 (盒->kg): output_盒 * gramsPerUnit / 1000
        boolean sameUnit = first.getInputUnit() != null
                && last.getOutputUnit() != null
                && first.getInputUnit().equals(last.getOutputUnit());
        // audit YIELD-1: 跨单位且无折算系数时不可比, cumulative 留 null (不输出 lastOutput/firstInput 的混单位错误值)
        boolean canComputeCumulative = sameUnit || standardGramsPerUnit != null;
        BigDecimal cumulative = null;
        if (canComputeCumulative && firstInput != null && firstInput.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal lastOutputInFirstUnit = lastOutput;
            if (!sameUnit) {  // 此分支 standardGramsPerUnit 必非 null
                lastOutputInFirstUnit = lastOutput.multiply(standardGramsPerUnit)
                        .divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP);
            }
            cumulative = lastOutputInFirstUnit.divide(firstInput, YIELD_SCALE, RoundingMode.HALF_UP);
        }
        boolean complete = steps.stream().allMatch(s ->
                s.getTotalInput() != null && s.getTotalInput().compareTo(BigDecimal.ZERO) > 0
                        && s.getTotalOutput() != null && s.getTotalOutput().compareTo(BigDecimal.ZERO) > 0);

        // P1-3 (G4): 整批工时 = Σ steps (全 null → null, 任一非 null 则求和)
        // Q1: 整批人数 = MAX steps (同班工人参与多道工序, SUM 虚高 N 倍; 取峰值与 step 级 MAX headcount 同语义)
        Integer batchMinutes = steps.stream().map(StepYieldDTO::getTotalWorkMinutes)
                .filter(Objects::nonNull).reduce(Integer::sum).orElse(null);
        Integer batchWorkers = steps.stream().map(StepYieldDTO::getTotalWorkers)
                .filter(Objects::nonNull).reduce(Integer::max).orElse(null);

        // A.4/A.5: 整批成本 = Σ steps (全 null → null, 任一非 null 则求和; 绝不默认 0)
        BigDecimal batchLaborCost = steps.stream().map(StepYieldDTO::getLaborCost)
                .filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
        BigDecimal batchMaterialCost = steps.stream().map(StepYieldDTO::getMaterialCost)
                .filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
        BigDecimal batchTotalCost = nullSafeAdd(batchLaborCost, batchMaterialCost);

        // 适配单元3: 整批损耗/留样 = Σ steps (全 null → null, 任一非 null 则求和; 绝不默认 0)
        BigDecimal batchWaste = steps.stream().map(StepYieldDTO::getWasteQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
        Integer batchSampleRetain = steps.stream().map(StepYieldDTO::getSampleRetainQuantity)
                .filter(Objects::nonNull).reduce(Integer::sum).orElse(null);

        return BatchYieldDTO.builder()
                .batchId(reports.get(0).getBatchId())
                .firstStepInput(firstInput)
                .lastStepOutput(lastOutput)
                .firstStepInputUnit(first.getInputUnit())
                .lastStepOutputUnit(last.getOutputUnit())
                .cumulativeYieldRate(cumulative)
                .steps(steps)
                .complete(complete)
                .totalWorkMinutes(batchMinutes)
                .totalWorkers(batchWorkers)
                .totalLaborCost(batchLaborCost)
                .totalMaterialCost(batchMaterialCost)
                .totalCost(batchTotalCost)
                .totalWaste(batchWaste)
                .totalSampleRetain(batchSampleRetain)
                .build();
    }

    /**
     * null-safe 加和: 全部参数 null → null; 否则把 null 视为 0 求和。
     * <p>用于成本聚合 (绝不默认 0 — 全无数据时保持 null 诚实显示"无成本数据")。</p>
     */
    static BigDecimal nullSafeAdd(BigDecimal... vals) {
        BigDecimal sum = null;
        for (BigDecimal v : vals) {
            if (v != null) {
                sum = (sum == null ? BigDecimal.ZERO : sum).add(v);
            }
        }
        return sum;
    }

    private static BigDecimal addMapDecimal(BigDecimal current, Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) {
            return current;
        }
        BigDecimal value;
        Object raw = map.get(key);
        if (raw instanceof BigDecimal decimal) {
            value = decimal;
        } else if (raw instanceof Number number) {
            value = new BigDecimal(number.toString());
        } else {
            String s = raw.toString();
            if (s.isBlank()) {
                return current;
            }
            value = new BigDecimal(s);
        }
        return (current == null ? BigDecimal.ZERO : current).add(value);
    }

    private static String firstMapString(String current, Map<String, Object> map, String key) {
        if (current != null || map == null || map.get(key) == null) {
            return current;
        }
        String value = map.get(key).toString();
        return value.isBlank() ? null : value;
    }
}
