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
            for (ProductionReport r : group) {
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
                    stepWorkers = (stepWorkers == null ? 0 : stepWorkers) + r.getTotalWorkers();
                }
                if (r.getLaborCost() != null) {
                    stepLaborCost = (stepLaborCost == null ? BigDecimal.ZERO : stepLaborCost).add(r.getLaborCost());
                }
                if (r.getMaterialCost() != null) {
                    stepMaterialCost = (stepMaterialCost == null ? BigDecimal.ZERO : stepMaterialCost).add(r.getMaterialCost());
                }
            }
            // 本道总成本 = labor + material (两者全 null → null; 任一非 null → 该项视为 0 参与求和)
            BigDecimal stepCost = nullSafeAdd(stepLaborCost, stepMaterialCost);
            ProductionReport head = group.get(0);
            String inUnit = head.getInputUnit();
            String outUnit = head.getOutputUnit();
            boolean comparable = inUnit != null && inUnit.equals(outUnit);
            BigDecimal yieldRate = null;
            if (comparable && totalInput.compareTo(BigDecimal.ZERO) > 0) {
                yieldRate = totalOutput.divide(totalInput, YIELD_SCALE, RoundingMode.HALF_UP);
            }
            BigDecimal carryover = prevOutput == null ? null : prevOutput.subtract(totalInput);

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

        // P1-3 (G4): 整批工时/人数 = Σ steps (全 null → null, 任一非 null 则求和)
        Integer batchMinutes = steps.stream().map(StepYieldDTO::getTotalWorkMinutes)
                .filter(Objects::nonNull).reduce(Integer::sum).orElse(null);
        Integer batchWorkers = steps.stream().map(StepYieldDTO::getTotalWorkers)
                .filter(Objects::nonNull).reduce(Integer::sum).orElse(null);

        // A.4/A.5: 整批成本 = Σ steps (全 null → null, 任一非 null 则求和; 绝不默认 0)
        BigDecimal batchLaborCost = steps.stream().map(StepYieldDTO::getLaborCost)
                .filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
        BigDecimal batchMaterialCost = steps.stream().map(StepYieldDTO::getMaterialCost)
                .filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
        BigDecimal batchTotalCost = nullSafeAdd(batchLaborCost, batchMaterialCost);

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
}
