package com.cretas.aims.entity.enums;

/**
 * 采购结算方式枚举（SP6）
 *
 * <p>6 类结算属性，与 purchase_accounting_subjects 表联合映射会计科目。
 * 供 PaymentRequest / PurchaseOrder 使用。
 */
public enum SettlementType {
    PREPAID("预付"),
    CREDIT_FIRST("赊销先入库"),
    NO_INVOICE("未到票"),
    MONTHLY("月结"),
    CREDIT_PERIOD("账期"),
    IMMEDIATE("现结");

    private final String displayName;

    SettlementType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
