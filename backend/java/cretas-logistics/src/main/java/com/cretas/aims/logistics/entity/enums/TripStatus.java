package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_trips.status} — mirrors DB CHECK {@code ck_lt_status}
 * (V20261028_01). {@code NEEDS_ROUTE_DATA} = distance edge honesty gate (spec §4,
 * §6 决策 6) — 缺边不伪造公里数, 车次落此态。
 */
public enum TripStatus {
    DRAFT,
    NEEDS_VEHICLE,
    NEEDS_DRIVER,
    NEEDS_ROUTE_DATA,
    CONFIRMED
}
