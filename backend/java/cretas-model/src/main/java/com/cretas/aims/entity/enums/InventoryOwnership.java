package com.cretas.aims.entity.enums;

/**
 * Canonical legal ownership of inventory.
 *
 * <p>Persisted legacy rows may have a {@code null} value. New inventory objects
 * default to {@link #COMPANY_OWNED}; customer ownership must always be inherited
 * from an authoritative business document snapshot.</p>
 */
public enum InventoryOwnership {
    COMPANY_OWNED,
    CUSTOMER_OWNED
}

