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
    /** ①d 复用半成品前段出成率/血缘接续 (READ-ONLY 派生)。 */
    private final ReusedSemiLineageService reusedSemiLineageService;

    /**
     * 计划自身首道原料投入重(kg) = Σ 最小 processOrder 行 inputQuantity。
     * 提为 public static 供 {@link ReusedSemiLineageService} 反查来源计划前段时同口径复用。
     * 空/无投入 → BigDecimal.ZERO (调用方自行判 signum)。
     */
    public static BigDecimal sumFirstProcessRawInput(List<ProcessSheetInventoryItem> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int minOrder = items.stream()
                .filter(i -> i.getProcessOrder() != null)
                .map(ProcessSheetInventoryItem::getProcessOrder)
                .min(Comparator.naturalOrder()).orElse(0);
        return items.stream()
                .filter(i -> i.getProcessOrder() != null && i.getProcessOrder().intValue() == minOrder)
                .map(ProcessSheetInventoryItem::getInputQuantity)
                .map(ProductionSummaryService::nz)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public ProductionSummaryDTO computeSummary(String factoryId, String planId, boolean maskPrice) {
        List<ProcessSheetInventoryItem> items = processSheetService.getInventoryYieldCard(factoryId, planId);

        BigDecimal totalRawInput = sumFirstProcessRawInput(items);

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

        // ①d 复用半成品前段接续: 被复用外部 SFI 批次的前段原料按领用比例接进分母 (READ-ONLY 派生)。
        //   缺 provenance 的批次诚实不接 + note 点名, 绝不伪造前段数 (绝不显错数)。
        ReusedSemiLineageService.ReusedFrontLineage lineage =
                reusedSemiLineageService.resolve(factoryId, planId);
        BigDecimal reusedFrontRaw = lineage == null ? BigDecimal.ZERO
                : nz(lineage.getTotalIncludedFrontRaw());
        // 真实出成率分母 = 本计划自身原料 + 被复用批次前段原料(按领用比例)。
        //   客户: 「因为我选的是这个批次的半成品, 所以前面的数据是有的, 再接上后面算」。
        BigDecimal yieldDenominator = totalRawInput.add(reusedFrontRaw);

        // 真实出成率 = 成品重 ÷ 分母 × 100 (单位守卫: 成品重未录 → null + note, 绝不显错数)
        BigDecimal realYield = null;
        String yieldNote = null;
        if (totalFinishedWeight.signum() > 0 && yieldDenominator.signum() > 0) {
            realYield = totalFinishedWeight.multiply(new BigDecimal("100"))
                    .divide(yieldDenominator, 2, java.math.RoundingMode.HALF_UP);
        } else if (yieldDenominator.signum() > 0) {
            yieldNote = "成品重量未录入,无法按重量算真实出成率";
        }
        // 复用批次前段缺失 → 追加诚实告知 (即便出成率已算出, 也让用户知道分母未含某批前段)。
        if (lineage != null && lineage.getNote() != null) {
            yieldNote = yieldNote == null ? lineage.getNote() : yieldNote + "; " + lineage.getNote();
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
                .reusedFrontRawInput(reusedFrontRaw)
                .yieldRawDenominator(yieldDenominator)
                .totalFinishedOutput(totalFinishedOutput)
                .totalFinishedWeight(totalFinishedWeight.signum() > 0 ? totalFinishedWeight : null)
                .remainingSemiFinished(remainingSemiFinished)
                .realYieldRate(realYield)
                .yieldNote(yieldNote)
                .totalCost(totalCost)
                .batches(lines)
                .reusedSemiLineages(lineage == null ? null : lineage.getLineages())
                .priceMasked(maskPrice)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
