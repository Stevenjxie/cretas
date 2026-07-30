package com.cretas.aims.entity.enums;

/**
 * 采购入库单状态枚举
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
public enum PurchaseReceiveStatus {
    /** 草稿 */
    DRAFT("草稿", "入库单草稿"),
    /** 待质检 */
    PENDING_QC("待质检", "等待质量检验"),
    /** 已确认入库 */
    CONFIRMED("已确认", "已确认入库，库存已增加"),
    /** 已退回 */
    REJECTED("已退回", "质检不合格，退回供应商");

    private final String displayName;
    private final String description;

    PurchaseReceiveStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
