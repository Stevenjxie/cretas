package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_delivery_orders.location_status} — mirrors DB CHECK
 * {@code ck_ldo_loc_status} (V20261028_01).
 */
public enum LocationStatus {
    RESOLVED,
    UNRESOLVED,
    OUT_OF_BOUNDS
}
