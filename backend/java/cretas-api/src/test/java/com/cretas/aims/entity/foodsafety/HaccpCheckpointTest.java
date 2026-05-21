package com.cretas.aims.entity.foodsafety;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HaccpCheckpoint entity unit tests — Sprint 8 P3 Phase A.
 * 仅校验 builder / default value / 字段映射, 不测 DB.
 */
class HaccpCheckpointTest {

    @Test
    void builderSetsRequiredFieldsAndDefaults() {
        HaccpCheckpoint ccp = HaccpCheckpoint.builder()
                .factoryId("F006")
                .checkpointCode("CCP-01")
                .name("中心温度")
                .hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal("75.0000"))
                .criticalLimitMax(new BigDecimal("100.0000"))
                .unit("℃")
                .build();
        assertNull(ccp.getId());
        assertEquals("F006", ccp.getFactoryId());
        assertEquals("CCP-01", ccp.getCheckpointCode());
        assertEquals("中心温度", ccp.getName());
        assertEquals("BIOLOGICAL", ccp.getHazardType());
        assertEquals(new BigDecimal("75.0000"), ccp.getCriticalLimitMin());
        assertEquals(new BigDecimal("100.0000"), ccp.getCriticalLimitMax());
        assertEquals("℃", ccp.getUnit());
        assertTrue(ccp.isActive()); // default true
    }

    @Test
    void criticalLimitMinLessThanMax() {
        HaccpCheckpoint ccp = HaccpCheckpoint.builder()
                .factoryId("F006")
                .checkpointCode("CCP-02")
                .name("冷却时间")
                .hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal("0"))
                .criticalLimitMax(new BigDecimal("90"))
                .unit("min")
                .build();
        assertTrue(ccp.getCriticalLimitMin().compareTo(ccp.getCriticalLimitMax()) < 0,
                "critical_limit_min should be less than critical_limit_max");
    }

    @Test
    void hazardTypeAcceptsAllThreeStandardValues() {
        for (String hazard : new String[]{"BIOLOGICAL", "CHEMICAL", "PHYSICAL"}) {
            HaccpCheckpoint ccp = HaccpCheckpoint.builder()
                    .factoryId("F006")
                    .checkpointCode("CCP-" + hazard)
                    .name("test")
                    .hazardType(hazard)
                    .criticalLimitMin(BigDecimal.ZERO)
                    .criticalLimitMax(BigDecimal.TEN)
                    .unit("unit")
                    .build();
            assertEquals(hazard, ccp.getHazardType());
        }
    }

    @Test
    void softDeleteSetsDeletedAt() {
        HaccpCheckpoint ccp = HaccpCheckpoint.builder()
                .factoryId("F006")
                .checkpointCode("CCP-01")
                .name("中心温度")
                .hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal("75"))
                .criticalLimitMax(new BigDecimal("100"))
                .unit("℃")
                .build();
        assertNull(ccp.getDeletedAt());
        ccp.softDelete();
        assertNotNull(ccp.getDeletedAt());
        assertTrue(ccp.isDeleted());
    }

    @Test
    void inactiveFlagToggle() {
        HaccpCheckpoint ccp = HaccpCheckpoint.builder()
                .factoryId("F006")
                .checkpointCode("CCP-01")
                .name("中心温度")
                .hazardType("BIOLOGICAL")
                .criticalLimitMin(BigDecimal.ZERO)
                .criticalLimitMax(BigDecimal.ONE)
                .unit("℃")
                .active(false)
                .build();
        assertFalse(ccp.isActive());
    }
}
