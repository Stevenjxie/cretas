package com.cretas.aims.dto.rd;

import com.cretas.aims.security.PriceSensitive;
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
 *
 * <p><b>RBAC 成本脱敏 (六扇门审计 Tier0 #05, 2026-06-11)</b>: 三价绝对成本值
 * (preQuote / midQuote / actualCost, 单位 元/kg) 标记 {@link PriceSensitive},
 * 由 {@link com.cretas.aims.security.PriceFieldResponseAdvice} 对无
 * {@code procurement:price:view} 权限角色 (warehouse_manager / warehouse_worker /
 * quality_inspector / operator / viewer 等运营角色) strip 为 null。
 * 偏差率 % (variancePct) 与超限布尔 (alert) 是相对指标/标签, 不脱敏。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThreePriceComparisonDTO {

    /** 预报价成本 元/kg (来自 QuotationTask.totalCost / goodQuantity) */
    @PriceSensitive
    private BigDecimal preQuote;

    /** 中报价综合成本 元/kg (来自 ProductMidQuote.totalCostPerKg) */
    @PriceSensitive
    private BigDecimal midQuote;

    /**
     * 最终实际成本 元/kg (来自试制批次真实生产成本 totalCost/goodQuantity,
     * 可为 null = 批次尚未报工完成 / 无成本数据).
     */
    @PriceSensitive
    private BigDecimal actualCost;

    /** 各阶段偏差预警列表 (偏差率 % + 超限布尔, 相对指标不脱敏) */
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
