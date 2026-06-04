package com.cretas.aims.security;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.restaurant.WastageRecordController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave2 损耗责任制 RBAC 锁：{@code WastageRecordController.accountability} 与 statistics
 * 同 RBAC 模型 — 整体门控 price/finance，否则按人/档口损耗成本对无价权角色 (warehouse_manager) 泄露。
 *
 * @see R6S1WastageStatisticsRbacTest sibling pattern
 */
@DisplayName("R6 sibling — WastageRecordController.accountability gated by price/finance")
class R6S2WastageAccountabilityRbacTest {

    @Test
    @DisplayName("accountability declares @RequirePermission with price:view OR finance:read")
    void accountability_isAnnotatedWithPriceOrFinance() throws Exception {
        Method method = WastageRecordController.class
                .getDeclaredMethod("accountability", String.class, LocalDate.class, LocalDate.class);

        RequirePermission anno = method.getAnnotation(RequirePermission.class);
        assertNotNull(anno,
                "@RequirePermission must be present on accountability — Wave2 损耗按人/档口成本敏感. "
                        + "Removing this gate leaks per-operator/per-section wastage cost to warehouse_mgr1.");

        var values = Arrays.asList(anno.value());
        assertTrue(values.contains("procurement:price:view"),
                "accountability must include procurement:price:view");
        assertTrue(values.contains("finance:read"),
                "accountability must include finance:read");

        assertFalse(anno.requireAll(),
                "requireAll should remain default (false) — any of the listed permissions admits");
    }
}
