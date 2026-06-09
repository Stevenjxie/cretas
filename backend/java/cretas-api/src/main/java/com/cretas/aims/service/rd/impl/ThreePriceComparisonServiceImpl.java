package com.cretas.aims.service.rd.impl;

import com.cretas.aims.dto.rd.ThreePriceComparisonDTO;
import com.cretas.aims.dto.rd.ThreePriceComparisonDTO.VarianceAlertEntry;
import com.cretas.aims.entity.rd.ProductMidQuote;
import com.cretas.aims.entity.rd.QuotationTask;
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
            // 预报价 per-kg = totalCost(整批预估) / 试制实际产出(kg)
            // 若试制批次产出尚无, fallback 为 totalCost 原值 (整批价格展示)
            if (latestMidOpt.isPresent()
                    && latestMidOpt.get().getTrialOutputKg() != null
                    && latestMidOpt.get().getTrialOutputKg().compareTo(BigDecimal.ZERO) > 0) {
                preQuotePerKg = task.getTotalCost()
                        .divide(latestMidOpt.get().getTrialOutputKg(), 4, RoundingMode.HALF_UP);
            } else {
                // fallback: totalCost as-is (整批成本，前端展示时需注明)
                preQuotePerKg = task.getTotalCost();
            }
        }

        // ── 3. 实际成本: 暂无自动计算源 (Phase 2 由生产批次成本模块补全) ────
        BigDecimal actualCost = null;

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

        // MID_TO_ACTUAL — skipped until actualCost is populated (Phase 2)
        if (midQuotePerKg != null && actualCost != null
                && midQuotePerKg.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pct = actualCost.subtract(midQuotePerKg)
                    .divide(midQuotePerKg, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
            alerts.add(VarianceAlertEntry.builder()
                    .stage("MID_TO_ACTUAL")
                    .variancePct(pct)
                    .alert(false)
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
}
