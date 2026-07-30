package com.cretas.aims.entity.hr.enums;

/**
 * 调休账户交易类型.
 *
 * @author Cretas Team — #835 follow-up CompTime Balance
 * @since 2026-05-18
 */
public enum CompTimeTransactionType {
    /** 入账 — 加班审批通过 (compensationType=COMPTIME), 累计调休余额 */
    EARN,

    /** 出账 — 请假审批通过 (leaveType=COMPTIME), 扣减调休余额 */
    USE
}
