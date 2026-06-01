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

    /** 人性化提示文字, 直接在 dialog 中展示给操作员. */
    private String message;
}
