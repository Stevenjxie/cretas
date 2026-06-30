package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.yield.ProductionSummaryDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductionSummaryService {
    private final ProcessSheetService processSheetService;
    private final ProductionBatchRepository productionBatchRepository;
    private final OrderCostBreakdownService orderCostBreakdownService;

    public ProductionSummaryDTO computeSummary(String factoryId, String planId, boolean maskPrice) {
        List<ProcessSheetInventoryItem> items = processSheetService.getInventoryYieldCard(factoryId, planId);

        int minOrder = items.stream()
                .filter(i -> i.getProcessOrder() != null)
                .map(ProcessSheetInventoryItem::getProcessOrder)
                .min(Comparator.naturalOrder()).orElse(0);

        BigDecimal totalRawInput = items.stream()
                .filter(i -> i.getProcessOrder() != null && i.getProcessOrder().intValue() == minOrder)
                .map(i -> nz(i.getInputQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ProductionBatch> planBatches =
                productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
        BigDecimal totalFinishedOutput = planBatches.stream()
                .filter(b -> "REGULAR".equals(b.getBatchType()))
                .map(b -> nz(b.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 剩余半成品 = Σ 在制(非成品)道的剩余量, 按 kg 半成品本身展示(不折算)
        BigDecimal remainingSemiFinished = items.stream()
                .filter(i -> !"COMPLETED".equals(i.getStatus()))
                .map(i -> nz(i.getRemaining()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 真实总出成率(方案A) = 总产出成品 ÷ 总投入原料 × 100, 半成品不折进
        BigDecimal realYield = totalRawInput.signum() > 0
                ? totalFinishedOutput.multiply(new BigDecimal("100"))
                    .divide(totalRawInput, 2, java.math.RoundingMode.HALF_UP)
                : null;

        BigDecimal totalCost = null;
        if (!maskPrice) {
            totalCost = planBatches.stream()
                    .filter(b -> "REGULAR".equals(b.getBatchType()) && b.getBatchNumber() != null)
                    .map(b -> orderCostBreakdownService.computeByBatch(factoryId, b.getBatchNumber(), false))
                    .filter(java.util.Objects::nonNull)
                    .map(cb -> nz(cb.getTotalCost()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        java.util.List<ProductionSummaryDTO.BatchLine> lines = items.stream()
                .map(i -> ProductionSummaryDTO.BatchLine.builder()
                        .batchNumber(i.getBatchNumber()).processOrder(i.getProcessOrder())
                        .processName(i.getProcessName()).produced(i.getProduced())
                        .remaining(i.getRemaining()).status(i.getStatus())
                        .cumulativeYieldRate(i.getCumulativeYieldRate()).build())
                .toList();

        return ProductionSummaryDTO.builder()
                .planId(planId)
                .totalRawInput(totalRawInput)
                .totalFinishedOutput(totalFinishedOutput)
                .remainingSemiFinished(remainingSemiFinished)
                .realYieldRate(realYield)
                .totalCost(totalCost)
                .batches(lines)
                .priceMasked(maskPrice)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
