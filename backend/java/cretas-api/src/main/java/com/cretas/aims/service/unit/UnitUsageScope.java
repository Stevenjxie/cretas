package com.cretas.aims.service.unit;

/**
 * Business fields in which a unit may be used. A unit dimension alone is not
 * sufficient: for example millimetres are valid in specifications but are not
 * a safe default inventory quantity for every material.
 */
public enum UnitUsageScope {
    INVENTORY_QUANTITY,
    PURCHASE_QUANTITY,
    BOM_QUANTITY,
    PROCESS_DURATION,
    STORAGE_TEMPERATURE,
    YIELD_RATE,
    SPECIFICATION
}
