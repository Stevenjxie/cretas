package com.cretas.aims.entity.rules;

/**
 * Canvas-Rules scope enum.
 *
 * Each scope is hooked to one or more Service methods via @RuleEvaluate(scope name).
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §2.1
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
public enum RuleScope {
    /** Purchase / Sales orders (PurchaseServiceImpl.submitOrder, SalesServiceImpl.createOrder) */
    ORDER,

    /** Inventory mutations (updateStock, MaterialBatch.consume) */
    INVENTORY,

    /** Customer entity (tier upgrades, ytd recalc) */
    CUSTOMER,

    /** Caller-provided custom scope (per-rule scope hint) */
    CUSTOM
}
