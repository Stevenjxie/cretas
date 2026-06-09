package com.cretas.aims.dto.laborefficiency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * SP9-M3: 逐工序人工成本分解项 (stepDetails 子列表元素).
 * <p>对应 StepYieldDTO 的汇总; 提供每工序折盒单价 + 目标达成率.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborVarianceItemDTO {

    /** 工序名称 */
    private String processName;

    /** 工序序号 */
    private Integer processOrder;

    /** Σ 本道工时(分钟); null = 未采集 */
    private Integer totalWorkMinutes;

    /** Σ 本道人次; null = 未采集 */
    private Integer totalWorkers;

    /** 本道实际人工成本(元); null = 工价/工时未配 */
    private BigDecimal laborCost;

    /** 本道人工成本折每盒(元/盒) = laborCost / 产出盒数; null = 无法折算 */
    private BigDecimal laborCostPerBox;

    /**
     * 目标达成率 = 计划产量(盒) / 实际工时(h) / 标准时产(盒/h); null = 未配标准时产.
     * <p>已迁移到批次级达成率(M4); 此处保留工序级原始数据供详情展示.</p>
     */
    private BigDecimal achievementRate;

    /**
     * 达成率告警: BELOW_ALERT (<75%) / ABOVE_ALERT (>150%) / OK / null (未计算).
     */
    private String achievementAlert;
}
