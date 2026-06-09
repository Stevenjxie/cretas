package com.cretas.aims.entity.enums;

/**
 * 采购收货异常决策状态（SP6）
 *
 * <p>记录在 PurchaseReceiveRecord 上，标记整单入库后的异常处理进展。
 */
public enum ReceiveDecisionStatus {
    PENDING_DECISION("待决策"),
    ACCEPTED("已接受"),
    RETURNED("已退回");

    private final String displayName;

    ReceiveDecisionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
