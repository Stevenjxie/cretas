package com.cretas.aims.entity.enums;

/**
 * Sprint4-H Q-RETURN-1: 质检退回目标类型.
 *
 * <p>区分上游退回方向: 原料供应商 vs 委外加工厂.
 * 与 T-RTA (customer sales-returns) 区分 — 那是下游客户退货, 这是上游退回.
 */
public enum QualityReturnTargetType {
    SUPPLIER("退回供应商", "原料质检不合格, 退回采购供应商"),
    SUBCONTRACT("退回委外", "委外加工成品不合格, 退回加工厂");

    private final String displayName;
    private final String description;

    QualityReturnTargetType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
