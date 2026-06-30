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

        BigDecimal remainingSemiRawEquiv = foldRemainingToRawEquiv(items, minOrder);
        BigDecimal denom = totalRawInput.subtract(remainingSemiRawEquiv);
        BigDecimal realYield = denom.signum() > 0
                ? totalFinishedOutput.multiply(new BigDecimal("100"))
                    .divide(denom, 2, java.math.RoundingMode.HALF_UP)
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
                .remainingSemiRawEquiv(remainingSemiRawEquiv)
                .realYieldRate(realYield)
                .totalCost(totalCost)
                .batches(lines)
                .priceMasked(maskPrice)
                .build();
    }

    /** 方案 R: 剩余 WIP(非成品行)按 cumulativeYieldRate 折回首道原料当量。
     *  折回原料 = remaining ÷ (cumulativeYieldRate/100)。成品行(COMPLETED)与首道行不计。 */
    private BigDecimal foldRemainingToRawEquiv(java.util.List<ProcessSheetInventoryItem> items, int minOrder) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ProcessSheetInventoryItem i : items) {
            if ("COMPLETED".equals(i.getStatus())) continue;
            if (i.getProcessOrder() != null && i.getProcessOrder().intValue() == minOrder) continue;
            BigDecimal rem = nz(i.getRemaining());
            BigDecimal cum = i.getCumulativeYieldRate();
            if (rem.signum() <= 0 || cum == null || cum.signum() <= 0) continue;
            sum = sum.add(rem.multiply(new BigDecimal("100")).divide(cum, 4, java.math.RoundingMode.HALF_UP));
        }
        return sum.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
