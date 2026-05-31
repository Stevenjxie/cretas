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
            }
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
        BigDecimal lastOutputInFirstUnit = lastOutput;
        boolean sameUnit = first.getInputUnit() != null && first.getInputUnit().equals(last.getOutputUnit());
        if (!sameUnit && standardGramsPerUnit != null) {
            lastOutputInFirstUnit = lastOutput.multiply(standardGramsPerUnit)
                    .divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP);
        }
        BigDecimal cumulative = null;
        if (firstInput != null && firstInput.compareTo(BigDecimal.ZERO) > 0) {
            cumulative = lastOutputInFirstUnit.divide(firstInput, YIELD_SCALE, RoundingMode.HALF_UP);
        }
        boolean complete = steps.stream().allMatch(s ->
                s.getTotalInput() != null && s.getTotalInput().compareTo(BigDecimal.ZERO) > 0
                        && s.getTotalOutput() != null && s.getTotalOutput().compareTo(BigDecimal.ZERO) > 0);

        return BatchYieldDTO.builder()
                .batchId(reports.get(0).getBatchId())
                .firstStepInput(firstInput)
                .lastStepOutput(lastOutput)
                .firstStepInputUnit(first.getInputUnit())
                .lastStepOutputUnit(last.getOutputUnit())
                .cumulativeYieldRate(cumulative)
                .steps(steps)
                .complete(complete)
                .build();
    }
}
