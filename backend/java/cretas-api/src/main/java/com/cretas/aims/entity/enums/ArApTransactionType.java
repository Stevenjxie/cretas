package com.cretas.aims.entity.enums;

/**
 * 应收应付交易类型
 */
public enum ArApTransactionType {
    AR_INVOICE("应收挂账", "销售出货产生应收"),
    AR_PAYMENT("应收收款", "客户付款冲减应收"),
    AR_ADJUSTMENT("应收调整", "手工调整应收金额"),
    AP_INVOICE("应付挂账", "采购入库产生应付"),
    AP_PAYMENT("应付付款", "向供应商付款冲减应付"),
    AP_ADJUSTMENT("应付调整", "手工调整应付金额"),
    AP_CREDIT_NOTE("应付冲减", "采购退货冲减应付"),
    AR_CREDIT_NOTE("应收冲减", "销售退货冲减应收");

    private final String displayName;
    private final String description;

    ArApTransactionType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    public boolean isAR() {
        return this == AR_INVOICE || this == AR_PAYMENT || this == AR_ADJUSTMENT || this == AR_CREDIT_NOTE;
    }

    public boolean isAP() {
        return this == AP_INVOICE || this == AP_PAYMENT || this == AP_ADJUSTMENT || this == AP_CREDIT_NOTE;
    }

    /** 是否增加余额（挂账增加，付款/调整可能减少） */
    public boolean isDebit() {
        return this == AR_INVOICE || this == AP_PAYMENT;
    }
}
