package com.cretas.aims.entity.foodsafety;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecallEvent entity unit tests — Sprint 8 P3 Phase A.
 */
class RecallEventTest {

    @Test
    void builderSetsRequiredFieldsAndDefaultStatus() {
        LocalDateTime now = LocalDateTime.now();
        RecallEvent e = RecallEvent.builder()
                .factoryId("F006")
                .eventCode("RECALL-20260518-001")
                .triggerReason("客户投诉: 拉肚子 / 鲜湘缘餐厅 5/18 卤猪蹄")
                .affectedProductCategory("卤猪蹄")
                .triggerTime(now)
                .triggeredByUserId(100L)
                .build();
        assertNull(e.getId());
        assertEquals("F006", e.getFactoryId());
        assertEquals("RECALL-20260518-001", e.getEventCode());
        assertEquals("INVESTIGATING", e.getStatus()); // default
        assertNull(e.getCompletedAt());
        assertEquals(now, e.getTriggerTime());
    }

    @Test
    void statusTransitionToCompleted() {
        RecallEvent e = RecallEvent.builder()
                .factoryId("F006")
                .eventCode("RECALL-20260518-001")
                .triggerReason("内部发现")
                .affectedProductCategory("卤猪蹄")
                .triggerTime(LocalDateTime.now())
                .triggeredByUserId(100L)
                .status("COMPLETED")
                .completedAt(LocalDateTime.now().plusHours(2))
                .build();
        assertEquals("COMPLETED", e.getStatus());
        assertNotNull(e.getCompletedAt());
    }

    @Test
    void estimatedLossWithTwoDecimalScale() {
        RecallEvent e = RecallEvent.builder()
                .factoryId("F006")
                .eventCode("RECALL-20260518-001")
                .triggerReason("客户投诉")
                .affectedProductCategory("卤猪蹄")
                .triggerTime(LocalDateTime.now())
                .triggeredByUserId(100L)
                .estimatedLoss(new BigDecimal("12345.67"))
                .build();
        assertEquals(new BigDecimal("12345.67"), e.getEstimatedLoss());
    }

    @Test
    void softDeleteSetsDeletedAt() {
        RecallEvent e = RecallEvent.builder()
                .factoryId("F006")
                .eventCode("RECALL-20260518-001")
                .triggerReason("test")
                .affectedProductCategory("卤猪蹄")
                .triggerTime(LocalDateTime.now())
                .triggeredByUserId(100L)
                .build();
        assertNull(e.getDeletedAt());
        e.softDelete();
        assertNotNull(e.getDeletedAt());
        assertTrue(e.isDeleted());
    }
}
