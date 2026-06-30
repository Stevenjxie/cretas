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

        return ProductionSummaryDTO.builder()
                .planId(planId)
                .totalRawInput(totalRawInput)
                .totalFinishedOutput(totalFinishedOutput)
                .priceMasked(maskPrice)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
