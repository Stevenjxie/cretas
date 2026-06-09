package com.cretas.aims.dto.rd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 三价对比 DTO — 预报价 / 中报价 / 最终成本价.
 *
 * <p>对比维度: 成本 per kg + 偏差率 + 是否超限.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThreePriceComparisonDTO {

    /** 预报价成本 元/kg (来自 QuotationTask.totalCost / goodQuantity) */
    private BigDecimal preQuote;

    /** 中报价综合成本 元/kg (来自 ProductMidQuote.totalCostPerKg) */
    private BigDecimal midQuote;

    /**
     * 最终实际成本 元/kg (来自生产批次实际成本均值, 可为 null = 尚未生产).
     */
    private BigDecimal actualCost;

    /** 各阶段偏差预警列表 */
    @Builder.Default
    private List<VarianceAlertEntry> varianceAlerts = java.util.Collections.emptyList();

    // ==================== 内嵌 DTO ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VarianceAlertEntry {
        /** PRE_TO_MID / MID_TO_ACTUAL */
        private String stage;
        /** 偏差率 % */
        private BigDecimal variancePct;
        /** 是否超阈值 */
        private boolean alert;
    }
}
