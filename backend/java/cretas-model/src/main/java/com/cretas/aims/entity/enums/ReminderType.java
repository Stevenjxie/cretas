package com.cretas.aims.entity.enums;

/**
 * 提醒类型 — Sprint 4 W2 S-REMIND-1.
 *
 * <p>当前仅 PAYMENT_DUE (收款逾期/即将到期). 未来可扩展 ORDER_DELIVERY /
 * QUALITY_RECHECK / ... 等业务事件提醒。
 */
public enum ReminderType {
    /** 收款提醒 — 销售订单回款逾期或即将到期. */
    PAYMENT_DUE,
}
