package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_plans.status} — mirrors DB CHECK {@code ck_lp_status}
 * (V20261028_01).
 */
public enum PlanStatus {
    DRAFT,
    NEEDS_ACTION,
    CONFIRMED,
    EXPORTED,
    CANCELLED
}
