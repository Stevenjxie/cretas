package com.cretas.aims.entity.foodsafety;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdditiveLimit entity unit tests — Sprint 8 P3 Phase A.
 */
class AdditiveLimitTest {

    @Test
    void builderSetsRequiredFields() {
        AdditiveLimit a = AdditiveLimit.builder()
                .additiveName("亚硝酸钠")
                .additiveCode("INS 250")
                .foodCategory("08.02 熟肉制品")
                .maxLimit(new BigDecimal("30.0000"))
                .unit("mg/kg")
                .regulationRef("GB 2760-2014 表 A.1")
                .build();
        assertNull(a.getId());
        assertEquals("亚硝酸钠", a.getAdditiveName());
        assertEquals("INS 250", a.getAdditiveCode());
        assertEquals("08.02 熟肉制品", a.getFoodCategory());
        assertEquals(new BigDecimal("30.0000"), a.getMaxLimit());
        assertEquals("mg/kg", a.getUnit());
        assertEquals("GB 2760-2014 表 A.1", a.getRegulationRef());
        assertTrue(a.isActive()); // default true
    }

    @Test
    void zeroMaxLimitMeansAppropriateUse() {
        // GB 2760 标记"适量"的添加剂 max_limit=0
        AdditiveLimit msg = AdditiveLimit.builder()
                .additiveName("谷氨酸钠 MSG")
                .additiveCode("INS 621")
                .foodCategory("08.02 熟肉制品")
                .maxLimit(BigDecimal.ZERO)
                .unit("mg/kg")
                .regulationRef("GB 2760-2014 适量使用")
                .build();
        assertEquals(BigDecimal.ZERO, msg.getMaxLimit());
    }

    @Test
    void inactiveFlagForObsoletedRegulation() {
        AdditiveLimit a = AdditiveLimit.builder()
                .additiveName("废止添加剂")
                .additiveCode("INS XXX")
                .foodCategory("08.02 熟肉制品")
                .maxLimit(BigDecimal.ONE)
                .unit("mg/kg")
                .regulationRef("GB 2760 已废止")
                .active(false)
                .build();
        assertFalse(a.isActive());
    }

    @Test
    void softDeleteSetsDeletedAt() {
        AdditiveLimit a = AdditiveLimit.builder()
                .additiveName("亚硝酸钠")
                .additiveCode("INS 250")
                .foodCategory("08.02 熟肉制品")
                .maxLimit(new BigDecimal("30"))
                .unit("mg/kg")
                .regulationRef("GB 2760-2014 表 A.1")
                .build();
        assertNull(a.getDeletedAt());
        a.softDelete();
        assertNotNull(a.getDeletedAt());
        assertTrue(a.isDeleted());
    }
}
