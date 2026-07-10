package com.cretas.aims.service.production;

import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionPlanPlannedUnitTest {

    @Test
    void resolvesPlanQuantityUnitFromFirstProcessThenProductThenLegacyKg() throws Exception {
        Method method = ProductionPlanServiceImpl.class.getDeclaredMethod(
                "resolvePlannedUnit", String.class, String.class, String.class);
        method.setAccessible(true);

        assertEquals("kg", method.invoke(null, "kg", "包", null));
        assertEquals("包", method.invoke(null, null, "包", null));
        assertEquals("kg", method.invoke(null, null, null, null));
        assertEquals("盒", method.invoke(null, "kg", "包", "盒"));
    }
}
