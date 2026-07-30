package com.cretas.aims.entity.enums;

/**
 * 提成 status enum — Sprint 7 wave 2 Track T5 (2026-05-20).
 */
public enum CommissionStatus {
    PENDING("待发放"),
    PAID("已发放"),
    CANCELLED("已取消");

    private final String displayName;

    CommissionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
