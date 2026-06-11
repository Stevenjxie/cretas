package com.cretas.aims.dto.laborefficiency;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * SP9 step-breakdown: 批次逐工序人效拆分 DTO.
 *
 * <p>单批次所有工序的工时 / 人次 / 达成率 / 成本明细.
 * 成本绝对值标注 {@code @PriceSensitive}; 工时/人次/达成率不脱敏.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborStepBreakdownDTO {

    private Long batchId;
    private String batchNumber;
    private String productName;

    /**
     * Σ 全批次实际工时(分钟); null = 无任何工序有工时数据.
     */
    private Integer totalActualWorkMinutes;

    /**
     * Σ 全批次计划工时(分钟) = 各工序 estimatedMinutes 之和.
     * null = 无工序配置 estimatedMinutes.
     */
    private Integer totalPlannedWorkMinutes;

    /**
     * 批次整体达成率 = totalActualWorkMinutes / totalPlannedWorkMinutes × 100; null = 无法计算.
     */
    private BigDecimal overallAchievementRatePct;

    private String overallAchievementAlert;

    /** 逐工序明细 */
    private List<StepBreakdownItem> steps;

    // ─────────── 逐工序明细 ───────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepBreakdownItem {

        private Integer processOrder;
        private String processName;

        /** 本道实际工时(分钟); null = 未采集 */
        private Integer actualWorkMinutes;

        /** 本道人次; null = 未采集 */
        private Integer actualWorkers;

        /** 本道计划工时(分钟) = WorkProcess.estimatedMinutes; null = 未配置 */
        private Integer plannedWorkMinutes;

        /**
         * 本道达成率 = actualWorkMinutes / plannedWorkMinutes × 100; null = 无法计算.
         */
        private BigDecimal achievementRatePct;

        private String achievementAlert;

        /**
         * 本道实际人工成本(元); null = 工价/工时未配.
         * @PriceSensitive
         */
        @PriceSensitive
        private BigDecimal laborCost;

        /**
         * 本道人工成本折盒(元/盒); null = 无法折算.
         * @PriceSensitive
         */
        @PriceSensitive
        private BigDecimal laborCostPerBox;
    }
}
