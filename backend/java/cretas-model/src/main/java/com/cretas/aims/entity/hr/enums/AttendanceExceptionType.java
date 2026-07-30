package com.cretas.aims.entity.hr.enums;

/**
 * 考勤异常类型 (R3 防呆 enum dropdown).
 *
 * <ul>
 *   <li>LATE — 上班打卡晚于 shift.startTime</li>
 *   <li>EARLY_LEAVE — 下班打卡早于 shift.endTime</li>
 *   <li>ABSENT — 整日无任何打卡记录</li>
 *   <li>OT_OVERSCHEDULED — 加班超出排班计划工时 (workDurationMinutes > shift expected)</li>
 *   <li>OTHER — 其他 (手工新增, 需填写 notes)</li>
 * </ul>
 *
 * @author Cretas Team — P1 #41 H-ATT-FULL MVP
 * @since 2026-05-17
 */
public enum AttendanceExceptionType {
    LATE,
    EARLY_LEAVE,
    ABSENT,
    OT_OVERSCHEDULED,
    OTHER
}
