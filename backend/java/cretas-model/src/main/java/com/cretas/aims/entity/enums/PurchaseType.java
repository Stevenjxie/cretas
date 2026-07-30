package com.cretas.aims.entity.enums;

/**
 * 采购类型枚举
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
public enum PurchaseType {
    /** 直接采购（单店/单厂自行采购） */
    DIRECT("直接采购", "独立组织自行采购"),
    /** 总部统采（总部统一下单，分配到各分店） */
    HQ_UNIFIED("总部统采", "总部统一采购，配送至分店"),
    /** 紧急采购 */
    URGENT("紧急采购", "紧急补货采购");

    private final String displayName;
    private final String description;

    PurchaseType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
