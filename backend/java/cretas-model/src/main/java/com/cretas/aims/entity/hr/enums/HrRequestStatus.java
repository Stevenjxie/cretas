package com.cretas.aims.entity.hr.enums;

/**
 * HR 自助流程通用申请状态 (LeaveRequest / OvertimeRequest / ExpenseRequest 共用).
 *
 * <p>状态流转:
 * <pre>
 *   DRAFT --submit--> SUBMITTED --approve--> APPROVED [--pay--> PAID (仅 ExpenseRequest)]
 *                                       \-->REJECTED
 *                              \--cancel--> CANCELLED
 * </pre>
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE+OVT+EXP)
 * @since 2026-05-16
 */
public enum HrRequestStatus {
    /** 草稿 — 员工编辑中,未提交审批 */
    DRAFT,

    /** 已提交 — 等待审批 */
    SUBMITTED,

    /** 已批准 */
    APPROVED,

    /** 已拒绝 */
    REJECTED,

    /** 已取消 — 提交人主动撤回 */
    CANCELLED,

    /** 已付款 — 仅 ExpenseRequest, APPROVED + PaymentRecord VERIFIED */
    PAID
}
