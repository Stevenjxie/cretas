package com.cretas.aims.service.production;

import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionPlanPlannedUnitTest {

    @Test
    void resolvesPlanQuantityUnitAsFinishedProductOutputUnit() throws Exception {
        Method method = ProductionPlanServiceImpl.class.getDeclaredMethod(
                "resolvePlannedOutputUnit", String.class);
        method.setAccessible(true);

        // A sales-order quantity is finished-product output.  It must not become
        // the first-process input unit (for example, 400 boxes must not read as 400kg).
        assertEquals("box", method.invoke(null, "box"));
        assertEquals("kg", method.invoke(null, (Object) null));
    }
}
