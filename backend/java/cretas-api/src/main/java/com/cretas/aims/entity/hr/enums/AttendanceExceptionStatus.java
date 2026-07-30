package com.cretas.aims.entity.hr.enums;

/**
 * 考勤异常处理状态.
 *
 * <pre>
 *   PENDING --approve--> APPROVED  (HR 认可异常,记录在案)
 *           \-reject---> REJECTED  (HR 驳回, 视为非异常)
 * </pre>
 *
 * @author Cretas Team — P1 #41 H-ATT-FULL MVP
 * @since 2026-05-17
 */
public enum AttendanceExceptionStatus {
    PENDING,
    APPROVED,
    REJECTED
}
