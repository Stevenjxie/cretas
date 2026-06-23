package com.cretas.aims.service.rd.impl;

import com.cretas.aims.dto.rd.ThreePriceComparisonDTO;
import com.cretas.aims.dto.rd.ThreePriceComparisonDTO.VarianceAlertEntry;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.rd.ProductMidQuote;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.rd.ProductMidQuoteRepository;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.rd.ThreePriceComparisonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SP10: 三价对比服务实现.
 *
 * <p>对比维度: 预报价 (PRE) / 中报价 (MID) / 最终实际成本 (ACTUAL).</p>
 * <p>任一维度数据缺失时对应字段为 null, 偏差计算跳过, 诚实展示.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreePriceComparisonServiceImpl implements ThreePriceComparisonService {

    private final QuotationTaskRepository quotationTaskRepository;
    private final ProductMidQuoteRepository midQuoteRepository;
    private final ProductionBatchRepository productionBatchRepository;

    @Override
    @Transactional(readOnly = true)
    public ThreePriceComparisonDTO getThreePriceComparison(String factoryId, String sampleId) {

        // ── 1. 中报价: 最新 ProductMidQuote ────────────────────────────────
        Optional<ProductMidQuote> latestMidOpt =
                midQuoteRepository.findFirstByFactoryIdAndSampleIdOrderByCreatedAtDesc(
                        factoryId, sampleId);
        BigDecimal midQuotePerKg = latestMidOpt
                .map(ProductMidQuote::getTotalCostPerKg)
                .orElse(null);

        // ── 2. 预报价: 从 QuotationTask 取 totalCost / trialOutputKg ───────
        QuotationTask task = quotationTaskRepository.findBySampleIdAndDeletedAtIsNull(sampleId);
        BigDecimal preQuotePerKg = null;

        if (task != null && task.getTotalCost() != null) {
            // 预报价 per-kg = totalCost(整批预估) / 试制实际产出(kg).
            // D2 修复: 无 trialOutputKg 时**不能**拿整批 totalCost (元/批) 冒充 per-kg —— 单位混淆
            //   (元/批 vs 元/kg) 会让 PRE_TO_MID / 三价对比方差完全无意义。诚实留 null (待折算),
            //   等试制批次产出录入后再计算 per-kg。
            if (latestMidOpt.isPresent()
                    && latestMidOpt.get().getTrialOutputKg() != null
                    && latestMidOpt.get().getTrialOutputKg().compareTo(BigDecimal.ZERO) > 0) {
                preQuotePerKg = task.getTotalCost()
                        .divide(latestMidOpt.get().getTrialOutputKg(), 4, RoundingMode.HALF_UP);
            } else {
                // 诚实 null: 试制产出未知, 无法把整批成本折算到 per-kg → 不冒充单价.
                log.debug("[SP10] 预报价 per-kg 待折算: sampleId={} task.totalCost={} 但 trialOutputKg 缺失, 留 null",
                        sampleId, task.getTotalCost());
                preQuotePerKg = null;
            }
        }

        // ── 3. 实际成本: 试制批次的真实生产成本 per kg ──────────────────────
        //   actualCost = 批次实际总成本(逐道人工+材料) / 良品产出(kg)
        //   与预报价 per-kg 同 kg 基准 (goodQuantity), 三价同口径可比.
        //   批次/成本/产出任一缺失 → null (诚实留空, 等批次报工完成).
        BigDecimal actualCost = null;
        if (latestMidOpt.isPresent() && latestMidOpt.get().getTrialBatchId() != null) {
            Long trialBatchId = latestMidOpt.get().getTrialBatchId();
            Optional<ProductionBatch> batchOpt =
                    productionBatchRepository.findByIdAndFactoryId(trialBatchId, factoryId);
            if (batchOpt.isPresent()) {
                ProductionBatch batch = batchOpt.get();
                BigDecimal batchTotalCost = batch.getTotalCost();
                BigDecimal goodQty = batch.getGoodQuantity();
                if (batchTotalCost != null
                        && goodQty != null
                        && goodQty.compareTo(BigDecimal.ZERO) > 0) {
                    actualCost = batchTotalCost.divide(goodQty, 4, RoundingMode.HALF_UP);
                }
            }
        }

        // ── 4. 偏差预警列表 ─────────────────────────────────────────────────
        List<VarianceAlertEntry> alerts = new ArrayList<>();

        // PRE_TO_MID
        if (preQuotePerKg != null && midQuotePerKg != null
                && preQuotePerKg.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pct = midQuotePerKg.subtract(preQuotePerKg)
                    .divide(preQuotePerKg, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
            boolean alert = latestMidOpt.isPresent()
                    && latestMidOpt.get().getVarianceThresholdPct() != null
                    && pct.abs().compareTo(latestMidOpt.get().getVarianceThresholdPct()) > 0;
            alerts.add(VarianceAlertEntry.builder()
                    .stage("PRE_TO_MID")
                    .variancePct(pct)
                    .alert(alert)
                    .build());
        }

        // MID_TO_ACTUAL — 实际成本 vs 中报价偏差 (actualCost 取自试制批次真实成本)
        if (midQuotePerKg != null && actualCost != null
                && midQuotePerKg.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pct = actualCost.subtract(midQuotePerKg)
                    .divide(midQuotePerKg, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
            boolean alert = latestMidOpt.isPresent()
                    && latestMidOpt.get().getVarianceThresholdPct() != null
                    && pct.abs().compareTo(latestMidOpt.get().getVarianceThresholdPct()) > 0;
            alerts.add(VarianceAlertEntry.builder()
                    .stage("MID_TO_ACTUAL")
                    .variancePct(pct)
                    .alert(alert)
                    .build());
        }

        log.debug("[SP10] three-price sampleId={} pre={} mid={} actual={}",
                sampleId, preQuotePerKg, midQuotePerKg, actualCost);

        return ThreePriceComparisonDTO.builder()
                .preQuote(preQuotePerKg)
                .midQuote(midQuotePerKg)
                .actualCost(actualCost)
                .varianceAlerts(alerts)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ThreePriceComparisonDTO getThreePriceComparisonByTaskId(String factoryId, String quotationTaskId) {
        QuotationTask task = quotationTaskRepository.findById(quotationTaskId)
                .orElseThrow(() -> new com.cretas.aims.exception.EntityNotFoundException(
                        "报价任务不存在: " + quotationTaskId));
        // 跨租户校验: 报价任务须属于当前工厂 (防止用别厂 task 的 sampleId 越权读三价对比)
        if (task.getFactoryId() == null || !task.getFactoryId().equals(factoryId)) {
            throw new com.cretas.aims.exception.BusinessException(403, "无权操作该报价任务 / 该报价任务不属于当前工厂");
        }
        return getThreePriceComparison(factoryId, task.getSampleId());
    }
}
