package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 5 Track D — PurchaseRequisitionStatus.canTransitionTo() truth table.
 */
@DisplayName("PurchaseRequisitionStatus state transitions")
class PurchaseRequisitionStatusTest {

    @Test
    @DisplayName("DRAFT 仅能 → PENDING_APPROVAL")
    void draftTransitions() {
        PurchaseRequisitionStatus s = PurchaseRequisitionStatus.DRAFT;
        assertTrue(s.canTransitionTo(PurchaseRequisitionStatus.PENDING_APPROVAL));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.APPROVED));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.CONVERTED_TO_PO));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.REJECTED));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.DRAFT));
        assertFalse(s.canTransitionTo(null));
    }

    @Test
    @DisplayName("PENDING_APPROVAL → APPROVED 或 REJECTED")
    void pendingApprovalTransitions() {
        PurchaseRequisitionStatus s = PurchaseRequisitionStatus.PENDING_APPROVAL;
        assertTrue(s.canTransitionTo(PurchaseRequisitionStatus.APPROVED));
        assertTrue(s.canTransitionTo(PurchaseRequisitionStatus.REJECTED));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.CONVERTED_TO_PO));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.DRAFT));
    }

    @Test
    @DisplayName("APPROVED 仅 → CONVERTED_TO_PO")
    void approvedTransitions() {
        PurchaseRequisitionStatus s = PurchaseRequisitionStatus.APPROVED;
        assertTrue(s.canTransitionTo(PurchaseRequisitionStatus.CONVERTED_TO_PO));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.PENDING_APPROVAL));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.REJECTED));
        assertFalse(s.canTransitionTo(PurchaseRequisitionStatus.DRAFT));
    }

    @Test
    @DisplayName("CONVERTED_TO_PO / REJECTED 是终态")
    void terminalStates() {
        for (PurchaseRequisitionStatus target : PurchaseRequisitionStatus.values()) {
            assertFalse(PurchaseRequisitionStatus.CONVERTED_TO_PO.canTransitionTo(target),
                    "CONVERTED_TO_PO 应是终态, 不可 → " + target);
            assertFalse(PurchaseRequisitionStatus.REJECTED.canTransitionTo(target),
                    "REJECTED 应是终态, 不可 → " + target);
        }
    }
}
