package com.cretas.aims.entity.enums;

/**
 * R23 audit C2: approval state for manual AR/AP adjustments.
 *
 * <p>Pre-R23, recordAdjustment immediately mutated customer.currentBalance /
 * supplier.currentBalance — bypassing dual-control. R23 introduces this enum
 * to enforce a 2nd-approver gate on AR_ADJUSTMENT / AP_ADJUSTMENT transactions:
 *
 * <ul>
 *   <li><b>APPROVED</b> — default for non-adjustment txn types (AR_INVOICE/AR_PAYMENT/etc.
 *       remain immediate-apply). For AR_ADJUSTMENT/AP_ADJUSTMENT, this state means the
 *       balance delta has been applied to the counterparty.</li>
 *   <li><b>PENDING</b> — adjustment recorded but not yet applied. Customer/supplier balance
 *       unchanged. Visible in approver dashboard. Only valid for AR_ADJUSTMENT/AP_ADJUSTMENT
 *       (DB CHECK constraint enforces this).</li>
 *   <li><b>REJECTED</b> — approver declined the adjustment. Balance never changes; row
 *       persists as historical evidence with reject reason in remark.</li>
 * </ul>
 */
public enum ArApApprovalStatus {
    APPROVED,
    PENDING,
    REJECTED
}
