package com.cretas.aims.entity.enums;

/**
 * 销售目标周期 enum — Sprint 7 wave 2 Track T5 (2026-05-20).
 *
 * <p>HJ Round 13 §15 业绩 6 项: 月/季/年 sales target.
 *
 * <p>periodNum 取值规则:
 * <ul>
 *   <li>MONTH: periodNum = 1..12</li>
 *   <li>QUARTER: periodNum = 1..4</li>
 *   <li>YEAR: periodNum = 1 (单值, 用于 UNIQUE 约束兼容)</li>
 * </ul>
 */
public enum TargetPeriod {
    MONTH("月度", 12),
    QUARTER("季度", 4),
    YEAR("年度", 1);

    private final String displayName;
    private final int maxPeriodNum;

    TargetPeriod(String displayName, int maxPeriodNum) {
        this.displayName = displayName;
        this.maxPeriodNum = maxPeriodNum;
    }

    public String getDisplayName() { return displayName; }
    public int getMaxPeriodNum() { return maxPeriodNum; }

    /** 校验 periodNum 在合法范围. */
    public boolean isValidPeriodNum(int periodNum) {
        return periodNum >= 1 && periodNum <= maxPeriodNum;
    }
}
