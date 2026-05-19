package com.cretas.aims.entity.alerts;

/**
 * 告警类型 — Phase 2 Canvas-Alerts 支持的 8 大业务预警场景.
 *
 * <p>每个 type 对应 1 套 trigger 逻辑 (event-driven 或 scheduled scan).
 * Service 层 / Tool 层根据 type 路由到具体业务上下文检查.
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
public enum AlertType {
    /** 低库存预警 — material / finished_good < minStockLevel. */
    INVENTORY_LOW,

    /** 临期预警 — expiryDate <= today + warningDays. */
    INVENTORY_EXPIRING,

    /** 质量异常 — inspection FAIL / pass rate < threshold. */
    QUALITY_ANOMALY,

    /** 采购金额超限 — PurchaseOrder amount >= threshold. */
    PO_AMOUNT_THRESHOLD,

    /** 销售金额异常 — SalesOrder amount >= threshold. */
    SO_AMOUNT_THRESHOLD,

    /** 销售下滑 — period-over-period sales drop > threshold%. */
    SALES_DECLINE,

    /** 客户应收逾期 — receivable aging > N days. */
    CUSTOMER_PAYMENT_OVERDUE,

    /** 供应商应付到期 — payable due <= today + N. */
    SUPPLIER_PAYABLE_DUE
}
