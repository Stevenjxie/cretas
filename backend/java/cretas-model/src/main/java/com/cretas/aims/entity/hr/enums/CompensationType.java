package com.cretas.aims.entity.hr.enums;

/**
 * 加班补偿方式.
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-OVT-1)
 * @since 2026-05-16
 */
public enum CompensationType {
    /** 转加班工资 — 走 Payroll */
    CASH,

    /** 转调休余额 — 累计入 LeaveBalance(leaveType=COMPTIME) */
    COMPTIME
}
