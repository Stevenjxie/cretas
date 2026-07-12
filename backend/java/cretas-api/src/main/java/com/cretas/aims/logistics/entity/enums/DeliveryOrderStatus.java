package com.cretas.aims.logistics.entity.enums;

/**
 * {@code logistics_delivery_orders.status} — mirrors DB CHECK {@code ck_ldo_status}
 * (V20261028_01).
 */
public enum DeliveryOrderStatus {
    IMPORTED,
    PLANNED,
    CONFIRMED,
    CANCELLED
}
