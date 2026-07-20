package com.cretas.aims.entity.enums;

/** Payment state of an AP invoice open item. */
public enum PayablePaymentStatus {
    /** Legacy payable whose historical payment allocations are not trustworthy yet. */
    NEEDS_RECONCILIATION,
    UNPAID,
    PARTIALLY_PAID,
    PAID
}
