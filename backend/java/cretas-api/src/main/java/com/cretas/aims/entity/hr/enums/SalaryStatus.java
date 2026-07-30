package com.cretas.aims.entity.hr.enums;

/**
 * 月度工资单状态机.
 *
 * <p>状态流转:
 * <pre>
 *   DRAFT --confirm--> CONFIRMED --pay--> PAID
 *      \---computeFromBase preview only (no state change)
 * </pre>
 *
 * <p>R4 防呆: 任意 user+yearMonth 同时只能存在 1 条 (unique constraint).
 * 已 PAID 不可改, 已 CONFIRMED 不可改基本工资但可改备注.
 *
 * @author Cretas Team — P1-40 H-WAGE-FULL MVP
 * @since 2026-05-17
 */
public enum SalaryStatus {
    /** 草稿 — HR 编辑中, 可重新 computeFromBase */
    DRAFT,

    /** 已确认 — 待发放 (基本工资 + 社保 + 个税锁定) */
    CONFIRMED,

    /** 已发放 — 不可再改 (与外部银行/财务对账后置位) */
    PAID
}
