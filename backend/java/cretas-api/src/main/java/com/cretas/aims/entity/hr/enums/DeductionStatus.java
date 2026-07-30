package com.cretas.aims.entity.hr.enums;

/**
 * 专项附加扣除生命周期状态.
 *
 * <p>状态流转:
 * <pre>
 *   ACTIVE --用户手动结束--> EXPIRED
 *          --HR 撤销--> CANCELLED
 * </pre>
 *
 * <p>仅 ACTIVE 状态的扣除项参与月度计税 (见 {@code computeTotalDeductionForMonth}).
 * 状态结束后保留历史记录以便审计 / 年度汇算回溯.
 *
 * @author Cretas Team — P1-40 H-WAGE 专项扣除 follow-up
 * @since 2026-05-17
 */
public enum DeductionStatus {
    /** 生效中 — 参与月度计税 */
    ACTIVE,

    /** 已过期 — 自然到期或用户手动结束 (validTo 已过) */
    EXPIRED,

    /** 已撤销 — HR 撤销 (例如填错或员工提供假证明) */
    CANCELLED
}
