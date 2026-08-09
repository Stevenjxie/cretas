package com.cretas.aims.entity.enums;

/** Lifecycle of an operations-created, non-order customer material arrival notice. */
public enum CustomerMaterialArrivalStatus {
    PENDING_APPROVAL,
    OPEN,
    PARTIALLY_RECEIVED,
    RECEIVED,
    REJECTED,
    CANCELLED
}
