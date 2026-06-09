package com.cretas.aims.entity.enums;

/**
 * 采购异常单决策枚举（SP6）
 *
 * <p>采购员对 PurchaseException 异常单作出的决策：
 * - OVER_RECEIVE 超收：ACCEPT_OVER（接受补单）或 RETURN_OVER（退回多余）
 * - UNDER_RECEIVE 少收：ACCEPT_SHORT（关闭本次）或 REQUEST_RESUPPLY（要求补货）
 */
public enum ExceptionDecision {
    ACCEPT_OVER("超收-接受补单"),
    RETURN_OVER("超收-退回多余"),
    ACCEPT_SHORT("少收-接受现有"),
    REQUEST_RESUPPLY("少收-要求补货");

    private final String displayName;

    ExceptionDecision(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
