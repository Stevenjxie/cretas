package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 产出量超收预检响应 — 防呆 Rule 1: dialog 打开即显示边界.
 *
 * <p>前端根据 {@code maxAllowed}/{@code remaining} 设置 input 的 max 约束,
 * 并将 {@code message} 显示在投入量输入框旁.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YieldLimitsDTO {

    /** 工序任务 ID, 回显确认调用方传的是哪道工序. */
    private Long workProcessTaskId;

    /** 目标产出量 = inputQuantity × standardYieldMax; null=无基准 (投入量为空或未配置出成上限). */
    private BigDecimal targetQuantity;

    /** WorkProcess.standardYieldMax, null=该工序未配置. */
    private BigDecimal standardYieldMax;

    /** WorkProcess.unit — 产出单位, 用于前端提示文字. */
    private String unit;

    /** Σ 该工序任务已提交 YIELD 报工的 outputQuantity. */
    private BigDecimal alreadyReported;

    /** 实际生效容差比例 (0.30 = 30%). */
    private BigDecimal toleranceRate;

    /** 超收容差上限 = targetQuantity × (1 + toleranceRate); null if targetQuantity null. */
    private BigDecimal maxAllowed;

    /** 剩余可报量 = maxAllowed − alreadyReported; null if maxAllowed null. */
    private BigDecimal remaining;

    /**
     * G7 防呆 Rule 1: 本道可领的源 WIP 余额 (Σ 上道工序产出的 AVAILABLE WIP available_quantity)。
     * 供 RN 领用 input 的 {@code :max} 约束 ("告诉他这个东西你要收多少就行")。
     * <p>null = 首道 (无上道 WIP, 领原料不受 WIP 余额约束); 0 = 上道 WIP 已被领空。</p>
     */
    private BigDecimal wipAvailable;

    /**
     * G7 跨单位防呆: 源 WIP 余额的单位 (= 上道 outputUnit)。
     * 供 RN 报工时 banner / input :max 用源 WIP 的真实单位 (而非本道 WorkProcess.unit),
     * 避免跨单位 (kg→份) 时 :max 用错单位误导操作员。
     * <p>null = 无源 WIP (首道) / 上道 WIP 行未记单位。</p>
     */
    private String wipAvailableUnit;

    /**
     * G7 Wave 4: 本道应领用的源 WIP 工序批次号 (上道唯一 AVAILABLE WIP 的 intermediate_batch_no)。
     * 供 RN 报工 req 直接带 {@code sourceWipNo} (无需前端再查 WIP 列表)。
     * <p>null = 首道 (无源 WIP) / 上道无 WIP 行 / 上道有多笔 AVAILABLE WIP (歧义, 由前端经
     * {@code GET /wip} 显式选择, 不自动猜); 非 null = 上道恰有一笔可领 WIP, 直接回显该号。</p>
     */
    private String sourceWipNo;

    /** 人性化提示文字, 直接在 dialog 中展示给操作员. */
    private String message;
}
