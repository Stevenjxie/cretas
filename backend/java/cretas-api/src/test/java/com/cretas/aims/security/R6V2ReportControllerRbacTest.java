package com.cretas.aims.security;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.ReportController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the R6 V2 RBAC fix (R31 6-vulnerable verify, 2026-05-13): the report
 * statistics controller is gated class-wide to roles holding price/finance
 * access.
 *
 * <p>Background (R31 prod sweep §V2): warehouse_mgr1 saw real values on
 * {@code /reports/inventory} (totalValue=¥280,160.96), {@code /reports/finance}
 * (accountsReceivable=¥5,000, accountsPayable=¥20,400, customerPrepayments=¥150,000),
 * {@code /reports/dashboard/overview} (kpi.unitCost), {@code /reports/sales}
 * (averageOrderValue), {@code /reports/cost-variance} (BOM cost ratios). 9 of 11
 * 200-OK report endpoints leaked. Warehouse_manager has only inventory + warehouse
 * + report:read; lacking finance and procurement:price:view, the class-level gate
 * blocks the entire report surface.
 *
 * @see com.cretas.aims.security.SmartBIDashboardExecutiveRbacTest reference pattern
 * @see <a href="../../../../../../../docs/qa-audits/2026-05-12-r31-6-vulnerable-prod-verify.md">R31 audit</a>
 */
@DisplayName("R6 V2 — ReportController class-level gated by finance/price")
class R6V2ReportControllerRbacTest {

    @Test
    @DisplayName("ReportController class declares @RequirePermission with finance:read OR price:view")
    void reportController_isAnnotatedClassLevel() {
        RequirePermission anno = ReportController.class.getAnnotation(RequirePermission.class);
        assertNotNull(anno,
                "@RequirePermission must be present at class level on ReportController — see R31 audit "
                        + "(docs/qa-audits/2026-05-12-r31-6-vulnerable-prod-verify.md). "
                        + "Removing the gate re-opens 9 confirmed warehouse_mgr1 value leaks.");

        var values = Arrays.asList(anno.value());
        assertTrue(values.contains("finance:read"),
                "ReportController must include finance:read — dispatcher/restaurant_manager/sales_manager need access");
        assertTrue(values.contains("finance:read_write"),
                "ReportController must include finance:read_write — finance_manager full access");
        assertTrue(values.contains("procurement:price:view"),
                "ReportController must include procurement:price:view — procurement_manager curated access");

        assertFalse(anno.requireAll(),
                "requireAll should remain default (false) — any of the listed permissions admits");
    }

    /**
     * Mirrors {@link com.cretas.aims.config.PermissionInterceptor#preHandle} line 56-60
     * resolution: method-level annotation wins, falls back to class-level. This test
     * verifies each <b>financial report</b> endpoint has NO method-level @RequirePermission
     * (so it inherits the class-level gate from {@link #reportController_isAnnotatedClassLevel()}).
     * If a future change adds a per-method gate that omits price/finance, this catches it.
     *
     * <p>NOTE (operational-dashboard access fix, 2026-06-17): the three operational
     * dashboard endpoints — {@code getDashboardOverview} / {@code getProductionDashboard}
     * / {@code getQualityDashboard} — were intentionally moved OFF the class-level
     * finance/price gate to a method-level gate that ALSO admits operational roles
     * (dashboard/warehouse/production/inventory/quality read). They are verified
     * separately by {@link #operationalDashboards_haveMethodGate_admittingOperationalAndFinanceRoles()}.
     * That fix keeps cost-field protection via runtime masking (see
     * {@code ReportController.maskOperationalDashboardCosts}), so the R31 §V2 leak
     * (kpi.unitCost) stays closed for non-price roles. The financial endpoints below
     * remain class-gated and unchanged.
     */
    @Test
    @DisplayName("Each financial report endpoint inherits class-level gate (no method override)")
    void leakingEndpoints_inheritClassLevelGate() throws Exception {
        // Financial endpoints from R31 audit §V2 — still class-gated, unchanged by the
        // operational-dashboard access fix.
        List<Method> leakingMethods = List.of(
                ReportController.class.getDeclaredMethod(
                        "getInventoryReport", String.class, LocalDate.class),
                ReportController.class.getDeclaredMethod(
                        "getFinanceReport", String.class, LocalDate.class, LocalDate.class),
                ReportController.class.getDeclaredMethod(
                        "getSalesReport", String.class, LocalDate.class, LocalDate.class),
                ReportController.class.getDeclaredMethod(
                        "getCostVarianceReport", String.class, LocalDate.class, LocalDate.class)
        );

        for (Method m : leakingMethods) {
            // Per interceptor: methodAnnotation null → falls back to class. This is the
            // expected wiring; any future explicit override on these methods must include
            // price/finance gate or the leak re-opens.
            RequirePermission methodAnno = m.getAnnotation(RequirePermission.class);
            if (methodAnno != null) {
                var values = Arrays.asList(methodAnno.value());
                assertTrue(
                        values.contains("procurement:price:view")
                                || values.contains("finance:read")
                                || values.contains("finance:read_write"),
                        m.getName() + " has method-level @RequirePermission that omits "
                                + "price/finance — overrides class-level gate per "
                                + "PermissionInterceptor line 60. Re-opens R31 §V2 leak.");
            }
        }
    }

    /**
     * Operational-dashboard access fix (2026-06-17): the three dashboard endpoints
     * give operational roles (warehouse_worker / warehouse_manager / production etc.)
     * their RN home-screen stats (今日产量/批次/在岗工人/库存预警/合格率). Before the
     * fix they inherited the class-level finance/price gate → operator roles 403 →
     * home screen silently showed 0/default (fake data, confirmed by RN E2E on the
     * LIUSHANMEN real factory).
     *
     * <p>This locks the fix: each of the three must (a) carry a method-level
     * @RequirePermission, (b) admit at least one operational role permission, and
     * (c) still admit price/finance roles so the existing financial UI keeps working.
     * Cost-field leak protection is handled at runtime by
     * {@code maskOperationalDashboardCosts}, not by the gate.
     */
    @Test
    @DisplayName("Operational dashboards have method-level gate admitting operational + finance/price roles")
    void operationalDashboards_haveMethodGate_admittingOperationalAndFinanceRoles() throws Exception {
        List<Method> dashboardMethods = List.of(
                ReportController.class.getDeclaredMethod(
                        "getDashboardOverview", String.class, String.class, String.class),
                ReportController.class.getDeclaredMethod(
                        "getProductionDashboard", String.class, String.class, String.class),
                ReportController.class.getDeclaredMethod(
                        "getQualityDashboard", String.class, String.class),
                ReportController.class.getDeclaredMethod(
                        "getTrendsDashboard", String.class, String.class, String.class, Integer.class, String.class)
        );

        for (Method m : dashboardMethods) {
            RequirePermission methodAnno = m.getAnnotation(RequirePermission.class);
            assertNotNull(methodAnno,
                    m.getName() + " must carry a method-level @RequirePermission overriding the "
                            + "class-level finance/price gate so operational roles reach their home stats.");

            var values = Arrays.asList(methodAnno.value());

            // (b) admits operational roles — at least one of these must be present.
            assertTrue(
                    values.contains("dashboard:read") || values.contains("dashboard:read_write")
                            || values.contains("warehouse:read") || values.contains("warehouse:read_write")
                            || values.contains("production:read") || values.contains("production:read_write")
                            || values.contains("inventory:read") || values.contains("inventory:read_write")
                            || values.contains("quality:read") || values.contains("quality:read_write"),
                    m.getName() + " method-level gate must admit operational roles "
                            + "(dashboard/warehouse/production/inventory/quality read) — otherwise the "
                            + "operator-home 403 fake-data bug re-opens.");

            // (c) still admits finance/price roles — existing financial UI must keep working.
            assertTrue(
                    values.contains("procurement:price:view")
                            || values.contains("finance:read")
                            || values.contains("finance:read_write"),
                    m.getName() + " method-level gate must still admit finance/price roles.");

            assertFalse(methodAnno.requireAll(),
                    m.getName() + " gate must remain OR (requireAll=false) — any listed permission admits.");
        }
    }
}
