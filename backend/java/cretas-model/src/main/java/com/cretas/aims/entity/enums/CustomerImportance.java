package com.cretas.aims.entity.enums;

/**
 * 客户重要程度分级 (Sprint 4 W2 S-CRM-FULL-1)
 *
 * 与 Customer.rating (1-5 数字评分) 互补 — rating 是综合评分,
 * importance 是销售人工标记的战略价值。
 */
public enum CustomerImportance {
    VIP("VIP", "战略级核心客户, 高优先级资源倾斜"),
    IMPORTANT("重要", "重要客户, 优先服务"),
    NORMAL("普通", "常规服务"),
    LOW("低", "维护成本敏感, 低优先级");

    private final String displayName;
    private final String description;

    CustomerImportance(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
