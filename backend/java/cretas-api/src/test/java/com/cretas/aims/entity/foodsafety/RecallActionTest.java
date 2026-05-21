package com.cretas.aims.entity.foodsafety;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecallAction entity unit tests — Sprint 8 P3 Phase A.
 */
class RecallActionTest {

    @Test
    void builderSetsRequiredFieldsAndDefaultStatus() {
        RecallAction a = RecallAction.builder()
                .recallEventId(1L)
                .actionType("FREEZE_INVENTORY")
                .targetEntityType("BATCH")
                .targetEntityId(500L)
                .build();
        assertNull(a.getId());
        assertEquals(1L, a.getRecallEventId());
        assertEquals("FREEZE_INVENTORY", a.getActionType());
        assertEquals("BATCH", a.getTargetEntityType());
        assertEquals(500L, a.getTargetEntityId());
        assertEquals("PENDING", a.getStatus()); // default
        assertNull(a.getExecutedAt());
    }

    @Test
    void allActionTypesValid() {
        for (String type : new String[]{"FREEZE_INVENTORY", "NOTIFY_CUSTOMER",
                "REGULATORY_REPORT", "CUSTOMER_REPLY", "COMPLETION_AUDIT"}) {
            RecallAction a = RecallAction.builder()
                    .recallEventId(1L)
                    .actionType(type)
                    .targetEntityType("CUSTOMER")
                    .targetEntityId(1L)
                    .build();
            assertEquals(type, a.getActionType());
        }
    }

    @Test
    void executedStatusWithTimestamp() {
        LocalDateTime executed = LocalDateTime.now();
        RecallAction a = RecallAction.builder()
                .recallEventId(1L)
                .actionType("NOTIFY_CUSTOMER")
                .targetEntityType("CUSTOMER")
                .targetEntityId(500L)
                .status("COMPLETED")
                .executedAt(executed)
                .executionNotes("已发送 12 条短信")
                .build();
        assertEquals("COMPLETED", a.getStatus());
        assertEquals(executed, a.getExecutedAt());
        assertEquals("已发送 12 条短信", a.getExecutionNotes());
    }

    @Test
    void failedStatusFlow() {
        RecallAction a = RecallAction.builder()
                .recallEventId(1L)
                .actionType("REGULATORY_REPORT")
                .targetEntityType("BATCH")
                .targetEntityId(500L)
                .status("FAILED")
                .executionNotes("监管 API 返 500")
                .build();
        assertEquals("FAILED", a.getStatus());
    }

    @Test
    void softDeleteSetsDeletedAt() {
        RecallAction a = RecallAction.builder()
                .recallEventId(1L)
                .actionType("FREEZE_INVENTORY")
                .targetEntityType("BATCH")
                .targetEntityId(500L)
                .build();
        assertNull(a.getDeletedAt());
        a.softDelete();
        assertNotNull(a.getDeletedAt());
        assertTrue(a.isDeleted());
    }
}
