package com.cretas.aims.entity.enums;

/**
 * 交易对手类型
 */
public enum CounterpartyType {
    CUSTOMER("客户"),
    SUPPLIER("供应商");

    private final String displayName;

    CounterpartyType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
