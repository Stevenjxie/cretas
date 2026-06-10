package com.cretas.aims.dto.laborefficiency;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * SP9-M3: 逐工序人工成本分解项 (stepDetails 子列表元素).
 * <p>对应 StepYieldDTO 的汇总; 提供每工序折盒单价 + 目标达成率.</p>
 *
 * <p><b>RBAC 成本脱敏 (六扇门审计 Tier0 #05, 2026-06-11)</b>: 工序级人工成本绝对值
 * (laborCost 元 / laborCostPerBox 元/盒) 标记 {@link PriceSensitive}, 由
 * {@link com.cretas.aims.security.PriceFieldResponseAdvice} 对无
 * {@code procurement:price:view} 权限角色 strip 为 null。
 * 工时(分钟) / 人次 / 达成率 % / 达成率告警标签 是工作量/相对指标, 不脱敏。</p>
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

    /** Σ 本道工时(分钟); null = 未采集 (工作量, 不脱敏) */
    private Integer totalWorkMinutes;

    /** Σ 本道人次; null = 未采集 (工作量, 不脱敏) */
    private Integer totalWorkers;

    /** 本道实际人工成本(元); null = 工价/工时未配 */
    @PriceSensitive
    private BigDecimal laborCost;

    /** 本道人工成本折每盒(元/盒) = laborCost / 产出盒数; null = 无法折算 */
    @PriceSensitive
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
