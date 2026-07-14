package com.cretas.aims.service.production;

import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import com.cretas.aims.exception.BusinessException;

class ProductionPlanPlannedUnitTest {

    @Test
    void resolvesPlanQuantityUnitAsFinishedProductOutputUnit() throws Exception {
        Method method = ProductionPlanServiceImpl.class.getDeclaredMethod(
                "resolvePlannedOutputUnit", String.class);
        method.setAccessible(true);

        // A sales-order quantity is finished-product output.  It must not become
        // the first-process input unit (for example, 400 boxes must not read as 400kg).
        assertEquals("box", method.invoke(null, "box"));
        InvocationTargetException missing = assertThrows(
                InvocationTargetException.class,
                () -> method.invoke(null, (Object) null));
        BusinessException cause = assertInstanceOf(BusinessException.class, missing.getCause());
        assertEquals("PRODUCTION_UNIT_NOT_CONFIGURED", cause.getErrorCode());
    }
}
