package com.cretas.aims.entity.hr.enums;

/**
 * 员工排班分配状态.
 *
 * <p>由 AttendanceService.detectExceptions(...) 根据 ClockInRecord(TimeClockRecord) 比对结果设置:
 * <pre>
 *   SCHEDULED — 已排班尚未到考勤日
 *   CLOCKED   — 当日有正常打卡 (clockInTime/clockOutTime 完整, 在 shift 时间范围内)
 *   MISSED    — 当日无打卡, 且 workDate &lt;= today
 *   EXCEPTION — 有打卡但存在 LATE / EARLY_LEAVE / OT_OVERSCHEDULED 等异常
 * </pre>
 *
 * @author Cretas Team — P1 #41 H-ATT-FULL MVP
 * @since 2026-05-17
 */
public enum ShiftAssignmentStatus {
    SCHEDULED,
    CLOCKED,
    MISSED,
    EXCEPTION
}
