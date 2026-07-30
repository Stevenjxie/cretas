package com.cretas.aims.entity.enums;

/**
 * POS订单同步状态
 */
public enum PosSyncStatus {
    PENDING("待处理"),
    SUCCESS("同步成功"),
    FAILED("同步失败"),
    DUPLICATE("重复订单");

    private final String displayName;

    PosSyncStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
