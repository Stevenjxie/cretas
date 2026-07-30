package com.cretas.aims.entity.enums;

/**
 * 客户需求状态 — Sprint 4 W2 S-NEED-1.
 *
 * <pre>
 * DRAFT (客户/销售员录入)
 *   ↓ confirm
 * CONFIRMED (销售员确认可处理)
 *   ↓ convert
 * CONVERTED_TO_SO (已转为销售订单, convertedSalesOrderId 填充)
 *
 * DRAFT/CONFIRMED → CANCELLED (作废)
 * </pre>
 */
public enum SalesNeedStatus {
    DRAFT,
    CONFIRMED,
    CONVERTED_TO_SO,
    CANCELLED,
}
