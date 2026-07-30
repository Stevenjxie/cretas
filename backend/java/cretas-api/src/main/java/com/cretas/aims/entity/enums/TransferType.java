package com.cretas.aims.entity.enums;

/**
 * 内部调拨类型
 */
public enum TransferType {
    HQ_TO_BRANCH("总部→分店/分厂", "总部统一调拨到下属门店或工厂"),
    BRANCH_TO_BRANCH("分店→分店", "同级门店/工厂之间的横向调拨"),
    BRANCH_TO_HQ("分店→总部", "下属门店/工厂退回总部"),
    /**
     * 工厂内部仓库间调拨 (source==target factory)。生产领料把原料从原料仓/物流仓 (WH-LOG) 转移到
     * 生产/车间仓 (WH-WKS), 以及关单退料的反向转移。此前 {@code FactoryMaterialRequisitionServiceImpl}
     * 误传字符串 "FACTORY_TO_FACTORY" (枚举里不存在) → {@code TransferType.valueOf} 抛
     * IllegalArgumentException → 每次领料调拨/退料 400。补齐为合法枚举值。
     */
    WAREHOUSE_TO_WAREHOUSE("仓库间调拨", "同一工厂内部仓库之间的物料转移 (如原料仓→生产仓领料 / 退料回原料仓)");

    private final String displayName;
    private final String description;

    TransferType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
