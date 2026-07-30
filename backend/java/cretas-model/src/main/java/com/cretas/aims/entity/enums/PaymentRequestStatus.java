package com.cretas.aims.entity.enums;

/**
 * 付款申请状态枚举（SP6 P0 轻量状态机）
 *
 * <p>状态流转：PENDING → FINANCE_REVIEW → APPROVED → PAID / REJECTED
 * CANCELLED 仅允许申请人本人在 PENDING 时撤回。
 */
public enum PaymentRequestStatus {
    PENDING("待财务初审"),
    FINANCE_REVIEW("财务审核中"),
    APPROVED("已批准（待付款）"),
    PAID("已付款"),
    REJECTED("已拒绝"),
    CANCELLED("已撤回");

    private final String displayName;

    PaymentRequestStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
