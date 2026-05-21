package com.cretas.aims.entity.foodsafety;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HaccpMonitoringRecord entity unit tests — Sprint 8 P3 Phase A.
 */
class HaccpMonitoringRecordTest {

    @Test
    void builderSetsRequiredFields() {
        LocalDateTime now = LocalDateTime.now();
        HaccpMonitoringRecord r = HaccpMonitoringRecord.builder()
                .factoryId("F006")
                .checkpointId(1L)
                .batchNumber("B-20260518-A03")
                .monitoringTime(now)
                .measuredValue(new BigDecimal("82.5000"))
                .operatorUserId(100L)
                .build();
        assertNull(r.getId());
        assertEquals("F006", r.getFactoryId());
        assertEquals(1L, r.getCheckpointId());
        assertEquals("B-20260518-A03", r.getBatchNumber());
        assertEquals(now, r.getMonitoringTime());
        assertEquals(new BigDecimal("82.5000"), r.getMeasuredValue());
        assertEquals(100L, r.getOperatorUserId());
        assertFalse(r.isDeviation()); // default false
    }

    @Test
    void deviationFlagWithAction() {
        HaccpMonitoringRecord r = HaccpMonitoringRecord.builder()
                .factoryId("F006")
                .checkpointId(1L)
                .batchNumber("B-20260518-A03")
                .monitoringTime(LocalDateTime.now())
                .measuredValue(new BigDecimal("70.0000"))  // 低于 critical_limit_min=75
                .operatorUserId(100L)
                .isDeviation(true)
                .deviationAction("继续加热 5 min 重测")
                .build();
        assertTrue(r.isDeviation());
        assertEquals("继续加热 5 min 重测", r.getDeviationAction());
    }

    @Test
    void notesFieldOptional() {
        HaccpMonitoringRecord r = HaccpMonitoringRecord.builder()
                .factoryId("F006")
                .checkpointId(1L)
                .batchNumber("B-20260518-A03")
                .monitoringTime(LocalDateTime.now())
                .measuredValue(new BigDecimal("80"))
                .operatorUserId(100L)
                .notes("正常监控, 中心温度达标")
                .build();
        assertEquals("正常监控, 中心温度达标", r.getNotes());
    }

    @Test
    void softDeleteSetsDeletedAt() {
        HaccpMonitoringRecord r = HaccpMonitoringRecord.builder()
                .factoryId("F006")
                .checkpointId(1L)
                .batchNumber("B-20260518-A03")
                .monitoringTime(LocalDateTime.now())
                .measuredValue(new BigDecimal("80"))
                .operatorUserId(100L)
                .build();
        assertNull(r.getDeletedAt());
        r.softDelete();
        assertNotNull(r.getDeletedAt());
        assertTrue(r.isDeleted());
    }
}
