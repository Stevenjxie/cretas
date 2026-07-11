package com.cretas.aims.entity.enums;

/**
 * 组织类型枚举
 * 支持食品工厂和餐饮门店两种业态，以及总部-分店层级关系
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-02-19
 */
public enum FactoryType {
    /** 食品工厂（独立） */
    FACTORY("工厂", "独立食品加工工厂"),
    /** 餐饮门店（独立） */
    RESTAURANT("门店", "独立餐饮门店"),
    /** 总部 */
    HEADQUARTERS("总部", "连锁总部/集团公司"),
    /** 分店/分公司 */
    BRANCH("分店", "隶属于总部的分店或分公司"),
    /** 中央厨房 */
    CENTRAL_KITCHEN("中央厨房", "集中生产配送的中央厨房"),
    /** 物流配送调度中心（独立） — 一加物流排线 MVP (2026-07-11) */
    LOGISTICS("物流", "独立物流配送调度中心");

    private final String displayName;
    private final String description;

    FactoryType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 是否为独立运营组织（无层级关系）
     */
    public boolean isStandalone() {
        return this == FACTORY || this == RESTAURANT || this == LOGISTICS;
    }

    /**
     * 是否为总部级组织（可以有下级）
     */
    public boolean isHeadquarters() {
        return this == HEADQUARTERS;
    }

    /**
     * 是否为下级组织（隶属于总部）
     */
    public boolean isSubordinate() {
        return this == BRANCH || this == CENTRAL_KITCHEN;
    }

    /**
     * 是否具有生产能力
     */
    public boolean hasProductionCapability() {
        return this == FACTORY || this == CENTRAL_KITCHEN;
    }
}
