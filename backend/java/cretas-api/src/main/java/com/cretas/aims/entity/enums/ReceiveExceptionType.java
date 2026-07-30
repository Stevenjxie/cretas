package com.cretas.aims.entity.enums;

/**
 * 采购收货异常类型（SP6）
 */
public enum ReceiveExceptionType {
    OVER_RECEIVE("超收"),
    UNDER_RECEIVE("少收");

    private final String displayName;

    ReceiveExceptionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
