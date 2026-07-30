package com.cretas.aims.entity.enums;

/**
 * 开票类型 — Sprint 4 W2 S-INVOICE-CLIENT-1 expanded from 2 to 6 values.
 *
 * 历史 NORMAL/SPECIAL 数据自动兼容 (无 DB 迁移需要, enum 仅在内存层映射).
 * 新值用于 Customer.defaultInvoiceType + SalesOrder.defaultInvoiceType + InvoiceRecord.invoiceType
 * 三层 default 链 (Customer org 默认 → SalesOrder 单据级 → InvoiceRecord 行级最终).
 */
public enum InvoiceType {
    NORMAL("普通发票", "增值税普票"),
    SPECIAL("增值税专票", "增值税专用发票, 可抵扣进项"),
    DIGITAL("数电票", "电子发票 (数电化)"),
    RECEIPT("收据", "收款收据 (非税务发票)"),
    NONE("不开票", "明确不需开票 (现金/抹零等)"),
    OTHER("其他", "其他类型, 备注说明");

    private final String displayName;
    private final String description;

    InvoiceType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
