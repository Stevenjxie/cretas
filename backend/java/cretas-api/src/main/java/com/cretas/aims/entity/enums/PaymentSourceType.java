package com.cretas.aims.entity.enums;

/**
 * 付款申请来源方向（#29 销售方向付款审批）。
 *
 * <p>付款申请审批流（替代钉钉）支持两个方向：
 * <ul>
 *   <li>{@link #PURCHASE} — 采购方向：对供应商的应付付款（counterparty=SUPPLIER，
 *       关联 purchaseOrderId/supplierId，markPaid 写 AP_PAYMENT + Supplier.currentBalance）。
 *       这是 SP6/D-9 的原有行为。</li>
 *   <li>{@link #SALES} — 销售方向：对客户的 outbound 付款（退款 / 返利 / 销售费用），
 *       counterparty=CUSTOMER，关联 salesOrderId/customerId，markPaid 写 AR_CREDIT_NOTE +
 *       Customer.currentBalance。注意：客户的「收款」走独立的财务确认已收款 + 开票流，
 *       不走本付款审批流。</li>
 * </ul>
 *
 * <p>向后兼容：老数据（V20261018_xx 之前创建的付款申请）无此字段，迁移默认填 PURCHASE。
 */
public enum PaymentSourceType {

    /** 采购方向：对供应商付款（应付）。SP6/D-9 原有语义。 */
    PURCHASE("采购付款", "对供应商的应付付款"),

    /** 销售方向：对客户 outbound 付款（退款/返利/销售费用）。 */
    SALES("销售付款", "对客户的退款/返利/销售费用付款");

    private final String displayName;
    private final String description;

    PaymentSourceType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
