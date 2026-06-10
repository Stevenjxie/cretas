package com.cretas.aims.service.impl;

import com.cretas.aims.dto.laborefficiency.LaborEfficiencyCompareDTO;
import com.cretas.aims.dto.laborefficiency.LaborVarianceItemDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
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
import java.util.List;
import java.util.Map;
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
}
