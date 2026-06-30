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

        // 成品重(kg) = Σ 末道(finished/COMPLETED)行 productWeight。盒数 totalFinishedOutput 保留作展示。
        BigDecimal totalFinishedWeight = items.stream()
                .filter(i -> "COMPLETED".equals(i.getStatus()))
                .map(i -> nz(i.getProductWeight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 真实出成率 = 成品重 ÷ 原料投入重 × 100 (单位守卫: 成品重未录 → null + note, 绝不显错数)
        BigDecimal realYield = null;
        String yieldNote = null;
        if (totalFinishedWeight.signum() > 0 && totalRawInput.signum() > 0) {
            realYield = totalFinishedWeight.multiply(new BigDecimal("100"))
                    .divide(totalRawInput, 2, java.math.RoundingMode.HALF_UP);
        } else if (totalRawInput.signum() > 0) {
            yieldNote = "成品重量未录入,无法按重量算真实出成率";
        }

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
                .totalFinishedWeight(totalFinishedWeight.signum() > 0 ? totalFinishedWeight : null)
                .remainingSemiFinished(remainingSemiFinished)
                .realYieldRate(realYield)
                .yieldNote(yieldNote)
                .totalCost(totalCost)
                .batches(lines)
                .priceMasked(maskPrice)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
