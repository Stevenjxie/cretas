package com.cretas.aims.entity.enums;

public enum ReturnType {
    PURCHASE_RETURN("采购退货"),
    SALES_RETURN("销售退货");

    private final String displayName;

    ReturnType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
