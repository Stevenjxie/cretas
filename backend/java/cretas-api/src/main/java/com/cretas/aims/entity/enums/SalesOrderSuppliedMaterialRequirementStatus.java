package com.cretas.aims.entity.enums;

/**
 * Receipt lifecycle of a customer-supplied material requirement.
 *
 * <p>The requirement row is also the warehouse task identity. No parallel task row is created.
 */
public enum SalesOrderSuppliedMaterialRequirementStatus {
    PENDING,
    PARTIALLY_RECEIVED,
    COMPLETED,
    CANCELLED
}

