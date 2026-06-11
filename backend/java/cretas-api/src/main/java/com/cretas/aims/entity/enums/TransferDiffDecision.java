package com.cretas.aims.entity.enums;

/**
 * 调拨差异找单决策枚举
 *
 * <p>发货量 ≠ 实收量时，责任方对差异单作出的决策：
 * - FOUND_BACK：差异货物已找回/补发
 * - WRITE_OFF_LOSS：核销为运输/储存损耗
 * - TRANSFER_DAMAGE：转报损处理（记录库存报损）
 *
 * @since 2026-06-11
 */
public enum TransferDiffDecision {
    FOUND_BACK("找回货物"),
    WRITE_OFF_LOSS("核销损耗"),
    TRANSFER_DAMAGE("转报损");

    private final String displayName;

    TransferDiffDecision(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
