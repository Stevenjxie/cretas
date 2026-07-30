package com.cretas.aims.entity.enums;

/**
 * 提醒状态 — Sprint 4 W2 S-REMIND-1.
 *
 * <p>PENDING (未处理) → SNOOZED (延后) → PENDING (再次激活).
 * PENDING / SNOOZED → DISMISSED (用户已处理, 不再提醒).
 */
public enum ReminderStatus {
    PENDING,
    SNOOZED,
    DISMISSED,
}
