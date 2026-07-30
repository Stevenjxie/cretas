package com.cretas.aims.entity.hr.enums;

/**
 * 调休账户来源类型 (sourceType in CompTimeLedgerEntry).
 *
 * @author Cretas Team — #835 follow-up CompTime Balance
 * @since 2026-05-18
 */
public enum CompTimeSourceType {
    /** 来自 OvertimeRequest approve (COMPTIME 补偿) — 入账 EARN */
    OT_APPROVED,

    /** 来自 LeaveRequest approve (leaveType=COMPTIME) — 出账 USE */
    LEAVE_APPROVED,

    /** HR 手工调整 — operator_id 为调整人 */
    MANUAL_ADJUST
}
