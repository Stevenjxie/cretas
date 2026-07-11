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
    SUPPLIER_PAYABLE_DUE,

    /**
     * 餐饮经营体检异常 (含反回扣) — 由 Python
     * {@code GET /api/smartbi/restaurant/{factoryId}/health-check-report} 的
     * {@code DiagnosticsEngine} 输出的 critical/warning 诊断项驱动 (食材成本率 /
     * 渠道收款率-佣金估算 / 折扣率 / 食材损耗率 等). businessEntityType=
     * "HEALTH_METRIC", businessEntityId=metricKey (不含 period, 保证同一指标持续
     * 异常时是"一条 standing 事件"而不是每次 sweep 重开一条).
     *
     * @since 2026-07-11 (餐饮经营体检预警推送)
     */
    RESTAURANT_HEALTH_CHECK
}
