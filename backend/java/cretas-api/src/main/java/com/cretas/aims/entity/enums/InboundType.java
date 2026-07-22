package com.cretas.aims.entity.enums;

/**
 * Inbound batch source type.
 */
public enum InboundType {
    /** Purchase receiving inbound. */
    PURCHASE_ORDER,
    /** Customer-owned material received against an approved sales-order requirement. */
    CUSTOMER_SUPPLIED,
    /** Inventory count gain inbound. */
    INVENTORY_COUNT,
    /** Supplier return inbound. */
    SUPPLIER_RETURN,
    /** Factory return inbound. */
    FACTORY_RETURN,
    /** Historical or migration import. Requires inventory:legacy_import. */
    LEGACY_IMPORT,
    /** Other inbound type. */
    OTHER
}
