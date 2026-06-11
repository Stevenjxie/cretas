package com.cretas.aims.service.impl;

import com.cretas.aims.dto.laborefficiency.LaborEfficiencyCompareDTO;
import com.cretas.aims.dto.laborefficiency.LaborEfficiencyOrderAggregateDTO;
import com.cretas.aims.dto.laborefficiency.LaborVarianceItemDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.LaborEfficiencyService;
import com.cretas.aims.service.yield.YieldReportService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * SP9: 人工双口径对比服务实现.
 *
 * <p>M3 compare: 研发预估(quotedLaborCostPerKg) vs 实际(laborCost / goodQuantityKg) 对比.</p>
 * <p>M2 rollup: 已实现 — YieldReportServiceImpl.rollupLaborCostToBatch 在 submitReport/settleDay 写回
 * ProductionBatch.laborCost = Σ YIELD 报工人工成本; actualLaborCostPerKg = laborCost / goodQuantityKg.</p>
 */
@Service
@RequiredArgsConstructor
public class LaborEfficiencyServiceImpl implements LaborEfficiencyService {

    private static final Logger log = LoggerFactory.getLogger(LaborEfficiencyServiceImpl.class);

    /** ±10% 触发 WARNING */
    private static final BigDecimal WARNING_THRESHOLD = new BigDecimal("10.00");
    /** ±20% 触发 CRITICAL */
    private static final BigDecimal CRITICAL_THRESHOLD = new BigDecimal("20.00");
    /** 达成率 < 75% 触发 BELOW_ALERT */
    private static final BigDecimal ACHIEVEMENT_BELOW = new BigDecimal("75.00");
    /** 达成率 > 150% 触发 ABOVE_ALERT */
    private static final BigDecimal ACHIEVEMENT_ABOVE = new BigDecimal("150.00");

    private final ProductionBatchRepository batchRepo;
    private final ProductTypeRepository productTypeRepo;
    private final ProductionPlanRepository productionPlanRepo;
    private final SalesOrderRepository salesOrderRepo;
    private final YieldReportService yieldReportService;

    @Override
    @Transactional(readOnly = true)
    public List<LaborEfficiencyCompareDTO> getLaborEfficiencyComparison(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            String productTypeId) {

        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        List<ProductionBatch> batches = batchRepo.findCompletedBatchesForLaborComparison(
                factoryId, startDt, endDt, productTypeId);

        if (batches.isEmpty()) {
            return new ArrayList<>();
        }

        // Batch-load all distinct productTypeIds to avoid N+1
        List<String> productTypeIds = batches.stream()
                .map(ProductionBatch::getProductTypeId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        Map<String, ProductType> productTypeMap = productTypeRepo.findByIdIn(productTypeIds)
                .stream()
                .collect(Collectors.toMap(ProductType::getId, pt -> pt));

        List<LaborEfficiencyCompareDTO> result = new ArrayList<>();
        for (ProductionBatch batch : batches) {
            try {
                LaborEfficiencyCompareDTO dto = buildCompareDTO(factoryId, batch, productTypeMap);
                result.add(dto);
            } catch (Exception e) {
                log.warn("SP9 labor compare: skip batch {} due to error: {}",
                        batch.getBatchNumber(), e.getMessage());
            }
        }
        return result;
    }

    // ─────────────────────────────── private helpers ────────────────────────────────

    private LaborEfficiencyCompareDTO buildCompareDTO(
            String factoryId,
            ProductionBatch batch,
            Map<String, ProductType> productTypeMap) {

        ProductType pt = productTypeMap.get(batch.getProductTypeId());

        BigDecimal quotedLaborCostPerKg = pt != null ? pt.getQuotedLaborCostPerKg() : null;
        BigDecimal gramsPerUnit = pt != null ? pt.getGramsPerUnit() : null;

        // actualLaborCostPerKg = batch.laborCost / goodQuantityKg
        BigDecimal actualLaborCostPerKg = calcActualLaborCostPerKg(batch);

        // per-box costs = per-kg × gramsPerUnit / 1000
        BigDecimal quotedLaborCostPerBox = calcPerBox(quotedLaborCostPerKg, gramsPerUnit);
        BigDecimal actualLaborCostPerBox = calcPerBox(actualLaborCostPerKg, gramsPerUnit);

        // variance rate = (actual - quoted) / quoted × 100
        BigDecimal varianceRate = calcVarianceRate(quotedLaborCostPerKg, actualLaborCostPerKg);
        String varianceStatus = calcVarianceStatus(varianceRate);

        // step details from yield
        List<LaborVarianceItemDTO> stepDetails = buildStepDetails(factoryId, batch, gramsPerUnit);

        return LaborEfficiencyCompareDTO.builder()
                .batchId(batch.getId())
                .batchNumber(batch.getBatchNumber())
                .productName(batch.getProductName())
                .productTypeId(batch.getProductTypeId())
                .gramsPerUnit(gramsPerUnit)
                .quotedLaborCostPerKg(quotedLaborCostPerKg)
                .actualLaborCostPerKg(actualLaborCostPerKg)
                .quotedLaborCostPerBox(quotedLaborCostPerBox)
                .actualLaborCostPerBox(actualLaborCostPerBox)
                .varianceRate(varianceRate)
                .varianceStatus(varianceStatus)
                .stepDetails(stepDetails)
                .build();
    }

    /**
     * 实际人工成本(元/kg) = batch.laborCost / goodQuantityKg.
     * <p>goodQuantityKg: goodQuantity 视为 kg (六扇门批次按 kg 称重入库);
     * goodQuantity null 时 fallback actualQuantity; 分母为 0 / null → null.</p>
     */
    private BigDecimal calcActualLaborCostPerKg(ProductionBatch batch) {
        if (batch.getLaborCost() == null) {
            return null;
        }
        BigDecimal quantityKg = batch.getGoodQuantity() != null
                ? batch.getGoodQuantity()
                : batch.getActualQuantity();
        if (quantityKg == null || quantityKg.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return batch.getLaborCost().divide(quantityKg, 4, RoundingMode.HALF_UP);
    }

    /**
     * 折盒成本 = perKg × gramsPerUnit / 1000; null-safe.
     */
    private BigDecimal calcPerBox(BigDecimal perKg, BigDecimal gramsPerUnit) {
        if (perKg == null || gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return perKg.multiply(gramsPerUnit)
                .divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP);
    }

    /**
     * 偏差率 = (actual - quoted) / quoted × 100; null = quoted 未配.
     */
    private BigDecimal calcVarianceRate(BigDecimal quoted, BigDecimal actual) {
        if (quoted == null || quoted.compareTo(BigDecimal.ZERO) == 0 || actual == null) {
            return null;
        }
        return actual.subtract(quoted)
                .divide(quoted, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String calcVarianceStatus(BigDecimal varianceRate) {
        if (varianceRate == null) {
            return null;
        }
        BigDecimal absRate = varianceRate.abs();
        if (absRate.compareTo(CRITICAL_THRESHOLD) >= 0) {
            return "CRITICAL";
        } else if (absRate.compareTo(WARNING_THRESHOLD) >= 0) {
            return "WARNING";
        }
        return "OK";
    }

    private List<LaborVarianceItemDTO> buildStepDetails(
            String factoryId,
            ProductionBatch batch,
            BigDecimal gramsPerUnit) {
        try {
            BatchYieldDTO yield = yieldReportService.getYield(factoryId, batch.getId());
            if (yield == null || yield.getSteps() == null) {
                return null;
            }
            List<LaborVarianceItemDTO> steps = new ArrayList<>();
            for (StepYieldDTO step : yield.getSteps()) {
                // laborCostPerBox = step.laborCost / step output boxes
                BigDecimal laborCostPerBox = calcStepLaborCostPerBox(step, gramsPerUnit);
                BigDecimal achievementRate = null; // M4 scope; step-level left null for now
                String achievementAlert = calcAchievementAlert(achievementRate);

                steps.add(LaborVarianceItemDTO.builder()
                        .processName(step.getProcessName())
                        .processOrder(step.getProcessOrder())
                        .totalWorkMinutes(step.getTotalWorkMinutes())
                        .totalWorkers(step.getTotalWorkers())
                        .laborCost(step.getLaborCost())
                        .laborCostPerBox(laborCostPerBox)
                        .achievementRate(achievementRate)
                        .achievementAlert(achievementAlert)
                        .build());
            }
            return steps;
        } catch (Exception e) {
            log.debug("SP9 step details unavailable for batch {}: {}", batch.getBatchNumber(), e.getMessage());
            return null;
        }
    }

    /**
     * 工序折盒: step.laborCost / (step.totalOutput kg → boxes = totalOutput*1000/gramsPerUnit).
     */
    private BigDecimal calcStepLaborCostPerBox(StepYieldDTO step, BigDecimal gramsPerUnit) {
        if (step.getLaborCost() == null || gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (step.getTotalOutput() == null || step.getTotalOutput().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        // outputBoxes = totalOutput(kg) * 1000 / gramsPerUnit
        BigDecimal outputBoxes = step.getTotalOutput()
                .multiply(new BigDecimal("1000"))
                .divide(gramsPerUnit, 4, RoundingMode.HALF_UP);
        if (outputBoxes.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return step.getLaborCost().divide(outputBoxes, 4, RoundingMode.HALF_UP);
    }

    private String calcAchievementAlert(BigDecimal achievementRate) {
        if (achievementRate == null) {
            return null;
        }
        BigDecimal rate100 = achievementRate.multiply(new BigDecimal("100"));
        if (rate100.compareTo(ACHIEVEMENT_ABOVE) > 0) {
            return "ABOVE_ALERT";
        } else if (rate100.compareTo(ACHIEVEMENT_BELOW) < 0) {
            return "BELOW_ALERT";
        }
        return "OK";
    }

    // ─────────────────────────── SP3 P1: M3b 订单级聚合 ────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<LaborEfficiencyOrderAggregateDTO> getLaborCostOrderAggregate(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            String productTypeId) {

        LocalDateTime startDt = startDate.atStartOfDay();
        LocalDateTime endDt = endDate.atTime(LocalTime.MAX);

        List<ProductionBatch> batches = batchRepo.findCompletedBatchesForLaborComparison(
                factoryId, startDt, endDt, productTypeId);
        if (batches.isEmpty()) {
            return new ArrayList<>();
        }

        // Batch-load ProductType map to avoid N+1
        List<String> ptIds = batches.stream()
                .map(ProductionBatch::getProductTypeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<String, ProductType> productTypeMap = productTypeRepo.findByIdIn(ptIds)
                .stream()
                .collect(Collectors.toMap(ProductType::getId, pt -> pt));

        // Batch-load ProductionPlan map (non-null planIds)
        List<String> planIds = batches.stream()
                .map(ProductionBatch::getProductionPlanId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<String, ProductionPlan> planMap = productionPlanRepo.findAllById(planIds)
                .stream()
                .collect(Collectors.toMap(ProductionPlan::getId, p -> p));

        // Resolve salesOrderId per batch: plan.sourceOrderId → first element of sourceOrderIds → null
        // Group by (salesOrderId or UNLINKED)
        // LinkedHashMap preserves insertion order (stable output)
        Map<String, List<ProductionBatch>> groupedBySo = new LinkedHashMap<>();
        for (ProductionBatch batch : batches) {
            String soId = resolveSalesOrderId(batch, planMap);
            // Null/unlinked batches keyed by plan ID or batch ID so they still appear
            String key = soId != null ? soId : "__UNLINKED__" + batch.getId();
            groupedBySo.computeIfAbsent(key, k -> new ArrayList<>()).add(batch);
        }

        // Batch-load SalesOrder map for all real SO IDs
        List<String> soIds = groupedBySo.keySet().stream()
                .filter(k -> !k.startsWith("__UNLINKED__"))
                .collect(Collectors.toList());
        Map<String, SalesOrder> salesOrderMap = salesOrderRepo.findAllById(soIds)
                .stream()
                .collect(Collectors.toMap(so -> so.getId(), so -> so));

        // Build batch-level compare DTOs (reusing existing logic) in a map for lookup
        Map<Long, LaborEfficiencyCompareDTO> batchCompareDtoMap = batches.stream()
                .collect(Collectors.toMap(
                        ProductionBatch::getId,
                        b -> {
                            try {
                                return buildCompareDTO(factoryId, b, productTypeMap);
                            } catch (Exception e) {
                                log.warn("SP3/M3b skip batch {} compare: {}", b.getBatchNumber(), e.getMessage());
                                return null;
                            }
                        }
                ));

        // Aggregate per SO group
        List<LaborEfficiencyOrderAggregateDTO> result = new ArrayList<>();
        for (Map.Entry<String, List<ProductionBatch>> entry : groupedBySo.entrySet()) {
            String key = entry.getKey();
            List<ProductionBatch> groupBatches = entry.getValue();
            boolean isUnlinked = key.startsWith("__UNLINKED__");
            String soId = isUnlinked ? null : key;
            SalesOrder salesOrder = soId != null ? salesOrderMap.get(soId) : null;

            LaborEfficiencyOrderAggregateDTO agg = aggregateBatchGroup(
                    soId, salesOrder, groupBatches, productTypeMap, batchCompareDtoMap);
            result.add(agg);
        }
        return result;
    }

    /**
     * Resolve the primary salesOrderId for a batch: plan.sourceOrderId > plan.sourceOrderIds[0] > null.
     */
    private String resolveSalesOrderId(ProductionBatch batch, Map<String, ProductionPlan> planMap) {
        if (batch.getProductionPlanId() == null) return null;
        ProductionPlan plan = planMap.get(batch.getProductionPlanId());
        if (plan == null) return null;
        if (plan.getSourceOrderId() != null && !plan.getSourceOrderId().isBlank()) {
            return plan.getSourceOrderId();
        }
        if (plan.getSourceOrderIds() != null && !plan.getSourceOrderIds().isEmpty()) {
            return plan.getSourceOrderIds().get(0);
        }
        return null;
    }

    /**
     * Aggregate a group of batches belonging to the same sales order (or null).
     */
    private LaborEfficiencyOrderAggregateDTO aggregateBatchGroup(
            String soId,
            SalesOrder salesOrder,
            List<ProductionBatch> groupBatches,
            Map<String, ProductType> productTypeMap,
            Map<Long, LaborEfficiencyCompareDTO> batchCompareDtoMap) {

        // Product info — use single productTypeId if all batches share one
        String singleProductTypeId = null;
        String singleProductName = null;
        BigDecimal singleGramsPerUnit = null;
        boolean multiProduct = false;
        for (ProductionBatch b : groupBatches) {
            if (singleProductTypeId == null) {
                singleProductTypeId = b.getProductTypeId();
                singleProductName = b.getProductName();
                ProductType pt = productTypeMap.get(b.getProductTypeId());
                singleGramsPerUnit = pt != null ? pt.getGramsPerUnit() : null;
            } else if (!Objects.equals(singleProductTypeId, b.getProductTypeId())) {
                multiProduct = true;
                singleProductTypeId = null; // mixed, set null
                singleProductName = "混合产品";
                singleGramsPerUnit = null;
                break;
            }
        }

        // Aggregate quantities and costs; honest null: all-null labor = null total
        BigDecimal totalGoodQty = BigDecimal.ZERO;
        boolean anyGoodQty = false;
        BigDecimal totalActualLabor = BigDecimal.ZERO;
        boolean anyActualLabor = false;
        BigDecimal totalQuotedLabor = BigDecimal.ZERO;
        boolean anyQuoted = false;

        for (ProductionBatch b : groupBatches) {
            BigDecimal goodQty = b.getGoodQuantity() != null ? b.getGoodQuantity() : b.getActualQuantity();
            if (goodQty != null) {
                totalGoodQty = totalGoodQty.add(goodQty);
                anyGoodQty = true;
            }
            if (b.getLaborCost() != null) {
                totalActualLabor = totalActualLabor.add(b.getLaborCost());
                anyActualLabor = true;
            }
            // Quoted labor for this batch = quotedLaborCostPerKg × goodQuantityKg
            ProductType pt = productTypeMap.get(b.getProductTypeId());
            if (pt != null && pt.getQuotedLaborCostPerKg() != null && goodQty != null) {
                totalQuotedLabor = totalQuotedLabor.add(
                        pt.getQuotedLaborCostPerKg().multiply(goodQty).setScale(4, RoundingMode.HALF_UP));
                anyQuoted = true;
            }
        }

        BigDecimal finalGoodQty = anyGoodQty ? totalGoodQty : null;
        BigDecimal finalActualLabor = anyActualLabor ? totalActualLabor : null;
        BigDecimal finalQuotedLabor = anyQuoted ? totalQuotedLabor : null;
        BigDecimal variance = (finalActualLabor != null && finalQuotedLabor != null)
                ? finalActualLabor.subtract(finalQuotedLabor) : null;
        BigDecimal varianceRate = calcVarianceRate(finalQuotedLabor, finalActualLabor);
        String varianceStatus = calcVarianceStatus(varianceRate);

        // Boxes = goodQty(kg) * 1000 / gramsPerUnit; only for single-product group
        BigDecimal totalBoxes = null;
        if (finalGoodQty != null && singleGramsPerUnit != null
                && singleGramsPerUnit.compareTo(BigDecimal.ZERO) > 0) {
            totalBoxes = finalGoodQty
                    .multiply(new BigDecimal("1000"))
                    .divide(singleGramsPerUnit, 2, RoundingMode.HALF_UP);
        }

        // Collect batch DTOs
        String resolvedProductionPlanId = groupBatches.isEmpty() ? null
                : groupBatches.get(0).getProductionPlanId();
        List<LaborEfficiencyCompareDTO> batchDtos = groupBatches.stream()
                .map(b -> batchCompareDtoMap.get(b.getId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return LaborEfficiencyOrderAggregateDTO.builder()
                .salesOrderId(soId)
                .salesOrderNumber(salesOrder != null ? salesOrder.getOrderNumber() : null)
                .productName(singleProductName)
                .productTypeId(multiProduct ? null : singleProductTypeId)
                .productionPlanId(resolvedProductionPlanId)
                .batchCount(groupBatches.size())
                .totalGoodQuantityKg(finalGoodQty)
                .gramsPerUnit(singleGramsPerUnit)
                .totalGoodQuantityBoxes(totalBoxes)
                .totalQuotedLaborCost(finalQuotedLabor)
                .totalActualLaborCost(finalActualLabor)
                .laborCostVarianceAbsolute(variance)
                .varianceRate(varianceRate)
                .varianceStatus(varianceStatus)
                .batches(batchDtos)
                .build();
    }
}
