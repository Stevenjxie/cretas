package com.cretas.aims.entity.hr.enums;

/**
 * 请假类型.
 *
 * <p>仅 {@link #ANNUAL}, {@link #SICK}, {@link #COMPTIME}, {@link #OTHER}
 * 需要跟踪 LeaveBalance (其它一次性事件型假期不累计余额).
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE-1)
 * @since 2026-05-16
 */
public enum LeaveType {
    /** 病假 — 跟踪 LeaveBalance 月度上限 */
    SICK,

    /** 事假 — 不跟踪余额, 仅记录 */
    PERSONAL,

    /** 年假 — 跟踪 LeaveBalance, 入职年限发放 */
    ANNUAL,

    /** 婚假 */
    MARRIAGE,

    /** 丧假 */
    FUNERAL,

    /** 产假 */
    MATERNITY,

    /** 陪产假 */
    PATERNITY,

    /** 调休 — 跟踪 LeaveBalance, OvertimeRequest(COMPTIME) approve 累计 */
    COMPTIME,

    /** 其它 */
    OTHER
}
