package com.cretas.aims.entity.enums;

/**
 * 调拨物品类型（原料 or 成品）
 */
public enum TransferItemType {
    RAW_MATERIAL("原料/食材"),
    FINISHED_GOODS("成品/菜品"),
    PACKAGING_MATERIAL("包材/包装材料");

    private final String displayName;

    TransferItemType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
