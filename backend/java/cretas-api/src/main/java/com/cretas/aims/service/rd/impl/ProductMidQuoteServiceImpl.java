package com.cretas.aims.service.rd.impl;

import com.cretas.aims.dto.rd.MidQuoteCalculationResultDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.rd.ProductMidQuote;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.rd.ProductMidQuoteRepository;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.rd.ProductMidQuoteService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * SP10: 产品中报价服务实现.
 *
 * <p>职责: 根据试制批次数据汇算中报价成本, 计算与预报价的方差预警.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductMidQuoteServiceImpl implements ProductMidQuoteService {

    private final ProductMidQuoteRepository midQuoteRepository;
    private final QuotationTaskRepository quotationTaskRepository;
    private final ProductionBatchRepository productionBatchRepository;

    @Override
    @Transactional
    public MidQuoteCalculationResultDTO calculate(
            String factoryId,
            Long trialBatchId,
            String quotationTaskId,
            BigDecimal materialCostPerKg,
            BigDecimal laborCostPerKg,
            BigDecimal overheadCostPerKg,
            BigDecimal varianceThresholdPct) {

        // ── 1. 幂等检查 ────────────────────────────────────────────────────
        var existing = midQuoteRepository.findByFactoryIdAndTrialBatchId(factoryId, trialBatchId);
        if (existing.isPresent() && "CALCULATED".equals(existing.get().getStatus())) {
            log.info("[SP10] midQuote already CALCULATED for batch={}, returning existing id={}",
                    trialBatchId, existing.get().getId());
            return MidQuoteCalculationResultDTO.builder()
                    .midQuote(existing.get())
                    .build();
        }

        // ── 2. 加载依赖实体 ────────────────────────────────────────────────
        QuotationTask task = quotationTaskRepository.findById(quotationTaskId)
                .orElseThrow(() -> new EntityNotFoundException("报价任务不存在: " + quotationTaskId));
        // 跨租户校验: 报价任务须属于当前工厂 (防止把别厂 task 的 sampleId 写进本厂 midQuote)
        if (task.getFactoryId() == null || !task.getFactoryId().equals(factoryId)) {
            throw new com.cretas.aims.exception.BusinessException(403, "无权操作该报价任务 / 该报价任务不属于当前工厂");
        }

        // 跨租户校验: 试制批次须属于当前工厂 (findByIdAndFactoryId)
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(trialBatchId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("试制批次不存在: " + trialBatchId));

        // ── 3. 批次必须已完成 (有产出量) ───────────────────────────────────
        if (batch.getActualQuantity() == null) {
            throw new IllegalStateException(
                    "试制批次尚未完成(actualQuantity=null), 无法汇算中报价, batchId=" + trialBatchId);
        }

        // ── 4. 构建 warnings ───────────────────────────────────────────────
        List<String> warnings = new ArrayList<>();
        if (laborCostPerKg == null) warnings.add("人工成本数据缺失, totalCostPerKg 暂无法计算");
        if (overheadCostPerKg == null) warnings.add("制造费用数据缺失, totalCostPerKg 暂无法计算");
        if (materialCostPerKg == null) warnings.add("原材料成本数据缺失, totalCostPerKg 暂无法计算");

        // ── 5. 计算综合成本 ────────────────────────────────────────────────
        BigDecimal totalCostPerKg = computeTotal(materialCostPerKg, laborCostPerKg, overheadCostPerKg);

        // ── 6. 计算方差 ────────────────────────────────────────────────────
        BigDecimal variancePct = null;
        boolean varianceAlert = false;

        if (totalCostPerKg != null && task.getTotalCost() != null
                && batch.getGoodQuantity() != null
                && batch.getGoodQuantity().compareTo(BigDecimal.ZERO) > 0) {
            // 预报价 per kg = totalCost(整批) / goodQuantity(kg)
            BigDecimal preQuotePerKg = task.getTotalCost()
                    .divide(batch.getGoodQuantity(), 4, RoundingMode.HALF_UP);
            if (preQuotePerKg.compareTo(BigDecimal.ZERO) > 0) {
                // variancePct = (mid - pre) / pre * 100
                variancePct = totalCostPerKg.subtract(preQuotePerKg)
                        .divide(preQuotePerKg, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);
                if (varianceThresholdPct != null) {
                    varianceAlert = variancePct.abs().compareTo(varianceThresholdPct) > 0;
                }
            }
        }

        // ── 7. 构建并保存中报价实体 ────────────────────────────────────────
        ProductMidQuote midQuote = existing.orElse(new ProductMidQuote());
        midQuote.setFactoryId(factoryId);
        midQuote.setSampleId(task.getSampleId());
        midQuote.setTrialBatchId(trialBatchId);
        midQuote.setQuotationTaskId(quotationTaskId);
        midQuote.setMaterialCostPerKg(materialCostPerKg);
        midQuote.setLaborCostPerKg(laborCostPerKg);
        midQuote.setOverheadCostPerKg(overheadCostPerKg);
        midQuote.setTotalCostPerKg(totalCostPerKg);
        midQuote.setTrialOutputKg(batch.getGoodQuantity());
        midQuote.setCostVariancePct(variancePct);
        midQuote.setVarianceAlert(varianceAlert);
        midQuote.setVarianceThresholdPct(varianceThresholdPct);
        midQuote.setStatus("CALCULATED");

        midQuote = midQuoteRepository.save(midQuote);
        log.info("[SP10] midQuote CALCULATED id={} batch={} totalPerKg={} variancePct={} alert={}",
                midQuote.getId(), trialBatchId, totalCostPerKg, variancePct, varianceAlert);

        return MidQuoteCalculationResultDTO.builder()
                .midQuote(midQuote)
                .warnings(warnings)
                .build();
    }

    // ==================== 确认 + 查询 ====================

    @Override
    @Transactional
    public com.cretas.aims.entity.rd.ProductMidQuote confirmMidQuote(
            String factoryId, String midQuoteId, String notes, Long confirmedBy) {

        ProductMidQuote midQuote = midQuoteRepository.findById(midQuoteId)
                .filter(q -> factoryId.equals(q.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException(
                        "中报价不存在或不属于该工厂: id=" + midQuoteId + " factoryId=" + factoryId));

        if (!"CALCULATED".equals(midQuote.getStatus())) {
            throw new IllegalStateException(
                    "中报价状态必须为 CALCULATED 才能确认, 当前状态: " + midQuote.getStatus()
                            + " (id=" + midQuoteId + ")");
        }

        midQuote.setStatus("CONFIRMED");
        if (notes != null && !notes.isBlank()) {
            midQuote.setNotes(notes);
        }
        midQuote.setConfirmedBy(confirmedBy);
        midQuote.setConfirmedAt(java.time.LocalDateTime.now());

        final ProductMidQuote savedMidQuote = midQuoteRepository.save(midQuote);

        // SP10: 中报价确认后, 联动推进报价任务 quoteStage PRE → MID + 回填 midQuoteId
        if (savedMidQuote.getQuotationTaskId() != null) {
            quotationTaskRepository.findById(savedMidQuote.getQuotationTaskId()).ifPresent(task -> {
                task.setQuoteStage("MID");
                task.setMidQuoteId(savedMidQuote.getId());
                quotationTaskRepository.save(task);
                log.info("[SP10] quotationTask {} quoteStage → MID (midQuoteId={})",
                        task.getId(), savedMidQuote.getId());
            });
        }

        log.info("[SP10] midQuote CONFIRMED id={} confirmedBy={}", midQuoteId, confirmedBy);
        return savedMidQuote;
    }

    @Override
    @Transactional(readOnly = true)
    public com.cretas.aims.entity.rd.ProductMidQuote getMidQuoteById(
            String factoryId, String midQuoteId) {

        return midQuoteRepository.findById(midQuoteId)
                .filter(q -> factoryId.equals(q.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException(
                        "中报价不存在或不属于该工厂: id=" + midQuoteId + " factoryId=" + factoryId));
    }

    /**
     * 汇总三项成本. 任一为 null → 整体 null (诚实空值规则).
     */
    private BigDecimal computeTotal(BigDecimal material, BigDecimal labor, BigDecimal overhead) {
        if (material == null || labor == null || overhead == null) {
            return null;
        }
        return material.add(labor).add(overhead).setScale(4, RoundingMode.HALF_UP);
    }
}
