package com.cretas.aims.dto.laborefficiency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * SP9-M4: 人效达成率汇总 DTO.
 *
 * <p>按 [factoryId, startDate, endDate, productTypeId?] 聚合已完工批次的达成率:
 * <ul>
 *   <li>批次级: 实际总工时(分钟) / 计划工时(分钟) → 达成率 %; 以报工 totalWorkMinutes 汇总;</li>
 *   <li>产品维度: 按产品类型聚合的达成率均值 / 样本数;</li>
 *   <li>达成率 = 实际工时 / 计划工时(estimatedMinutes 之和) × 100; 无计划 → null.</li>
 * </ul>
 *
 * <p><b>脱敏</b>: 达成率 % 与工时(分钟/人次) 是工作量指标, <b>不脱敏</b>.
 * 成本绝对值字段仍在 {@link LaborEfficiencyCompareDTO} 中并标注 {@code @PriceSensitive}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborAchievementSummaryDTO {

    /** 汇总口径: 全部产品 = null; 特定产品类型 = productTypeId */
    private String productTypeId;

    /** 产品名称; productTypeId 为 null 时为"全部产品" */
    private String productName;

    /**
     * Σ 实际工时(分钟); 各批次 batch.totalWorkMinutes 加总.
     * null = 无任何批次有工时数据.
     */
    private Integer totalActualWorkMinutes;

    /**
     * Σ 计划工时(分钟); 各批次 estimatedMinutes × batchCount 加总.
     * null = 无任何批次有 WorkProcess.estimatedMinutes 配置.
     */
    private Integer totalPlannedWorkMinutes;

    /**
     * 达成率 = totalActualWorkMinutes / totalPlannedWorkMinutes × 100.
     * <p>null = 无法计算(计划工时未配置或无数据).</p>
     * <p>正常 ≈ 100%; >100% = 实际工时超出计划(低效); <100% = 快于计划(高效).</p>
     */
    private BigDecimal achievementRatePct;

    /**
     * 达成率告警级别.
     * <ul>
     *   <li>OK: 达成率在 [75%, 150%) 范围内</li>
     *   <li>BELOW_ALERT: 达成率 < 75% (效率异常偏高, 数据可疑或真正异常)</li>
     *   <li>ABOVE_ALERT: 达成率 ≥ 150% (工时严重超出计划)</li>
     *   <li>null: 无法计算</li>
     * </ul>
     */
    private String achievementAlert;

    /** 参与汇总的批次数量 */
    private int batchCount;

    /** 各批次达成率明细 (可选, 前端看板用) */
    private List<BatchAchievementItemDTO> batchItems;

    // ─────────── 内嵌批次级明细 ───────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchAchievementItemDTO {
        private String batchNumber;
        private String productName;
        private Integer actualWorkMinutes;
        private Integer plannedWorkMinutes;
        /** 批次达成率 %; null = 计划工时未配置 */
        private BigDecimal achievementRatePct;
        private String achievementAlert;
    }
}
