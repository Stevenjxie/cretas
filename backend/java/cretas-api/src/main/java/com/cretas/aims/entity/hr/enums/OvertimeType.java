package com.cretas.aims.entity.hr.enums;

/**
 * 加班类型 — 影响加班工资倍数 / 调休换算系数.
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-OVT-1)
 * @since 2026-05-16
 */
public enum OvertimeType {
    /** 工作日加班 — 1.5x 工资 or 1:1 调休 */
    WEEKDAY,

    /** 周末加班 — 2x 工资 or 1:2 调休 */
    WEEKEND,

    /** 法定节假日加班 — 3x 工资 (不可转调休) */
    HOLIDAY
}
