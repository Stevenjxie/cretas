package com.cretas.aims.security;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.SmartBIUploadController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * BUG-01-RBAC-001 fix: raw finance upload data must require {@code finance:read_write},
 * not the weaker {@code analytics:read}.
 *
 * <p>Root cause: {@code sales_manager} holds {@code analytics:read} (PermissionServiceImpl
 * line 218) but NOT {@code finance:read_write}. The original {@code analytics:read} gate on
 * {@code getUploadData} and {@code getUploadFields} let any sales / restaurant_manager /
 * viewer reach raw Excel rows containing Revenue, FoodCost, Labor amounts.
 *
 * <p>Severity: HIGH — RBAC finance data leak.
 *
 * <p>Fix: {@code getUploadData} and {@code getUploadFields} upgraded to
 * {@code finance:read_write}. Only {@code finance_manager}, {@code factory_super_admin},
 * and {@code restaurant_owner} hold that permission. The low-sensitivity
 * {@code getUploadHistory} (filename/status metadata) and {@code getUploadsMissingFields}
 * (counts only) remain at {@code analytics:read} — no finance amounts exposed there.
 *
 * @see com.cretas.aims.controller.finance.FinanceReportController finance:read guard precedent
 * @see com.cretas.aims.service.impl.PermissionServiceImpl role→permission matrix
 */
@DisplayName("BUG-01-RBAC-001 — SmartBIUpload finance raw-data endpoints require finance:read_write")
class SmartBIUploadReadRbacTest {

    private static final String ANALYTICS_READ = "analytics:read";
    private static final String FINANCE_READ_WRITE = "finance:read_write";

    // ==================== Finance-sensitive endpoints (must be finance:read_write) ====================

    /**
     * /uploads/{uploadId}/data — returns raw Excel rows with finance amounts.
     * CONFIRMED VULNERABLE (BUG-01-RBAC-001): qhj_sales_mgr received HTTP 200 with
     * Revenue/FoodCost/Labor rows before fix.
     */
    @Test
    @DisplayName("getUploadData (/uploads/{uploadId}/data) requires finance:read_write — not analytics:read")
    void getUploadData_requiresFinanceReadWrite() throws Exception {
        Method method = SmartBIUploadController.class.getDeclaredMethod(
                "getUploadData", String.class, Long.class, int.class, int.class);
        assertRequiresFinanceReadWrite(method, "/uploads/{uploadId}/data");
    }

    /**
     * /uploads/{uploadId}/fields — returns field/column definitions (e.g. "金额", "营业收入").
     * Reveals the schema of finance data; guarded at the same level as the data itself.
     */
    @Test
    @DisplayName("getUploadFields (/uploads/{uploadId}/fields) requires finance:read_write — not analytics:read")
    void getUploadFields_requiresFinanceReadWrite() throws Exception {
        Method method = SmartBIUploadController.class.getDeclaredMethod(
                "getUploadFields", String.class, Long.class);
        assertRequiresFinanceReadWrite(method, "/uploads/{uploadId}/fields");
    }

    // ==================== Low-sensitivity endpoints (analytics:read sufficient) ====================

    /**
     * /uploads — returns upload history (filename, status, timestamps). No finance amounts.
     * Kept at analytics:read so dispatchers, sales, restaurant_manager can view upload history.
     */
    @Test
    @DisplayName("getUploadHistory (/uploads) retains analytics:read guard (metadata only, no amounts)")
    void getUploadHistory_retainsAnalyticsRead() throws Exception {
        Method method = SmartBIUploadController.class.getDeclaredMethod(
                "getUploadHistory", String.class, String.class, int.class, int.class);
        assertGatedByAnalyticsRead(method, "/uploads");
    }

    /**
     * /uploads-missing-fields — returns diagnostic counts only (no finance amounts).
     * Kept at analytics:read.
     */
    @Test
    @DisplayName("getUploadsMissingFields (/uploads-missing-fields) retains analytics:read (counts only)")
    void getUploadsMissingFields_retainsAnalyticsRead() throws Exception {
        Method method = SmartBIUploadController.class.getDeclaredMethod(
                "getUploadsMissingFields", String.class);
        assertGatedByAnalyticsRead(method, "/uploads-missing-fields");
    }

    // ==================== Helpers ====================

    private static void assertRequiresFinanceReadWrite(Method method, String route) {
        RequirePermission anno = method.getAnnotation(RequirePermission.class);
        assertNotNull(anno,
                "@RequirePermission must be present on " + method.getName() + " (" + route + ")");

        assertArrayEquals(new String[]{FINANCE_READ_WRITE}, anno.value(),
                method.getName() + " (" + route + ") must require exactly [finance:read_write]. "
                        + "This endpoint exposes raw finance rows (Revenue/FoodCost/Labor). "
                        + "analytics:read is NOT sufficient — sales_manager holds analytics:read "
                        + "but must be blocked (BUG-01-RBAC-001).");

        assertFalse(anno.requireAll(),
                "requireAll should remain default (false) — single permission declared");
    }

    private static void assertGatedByAnalyticsRead(Method method, String route) {
        RequirePermission anno = method.getAnnotation(RequirePermission.class);
        assertNotNull(anno,
                "@RequirePermission must be present on " + method.getName() + " (" + route + ") — "
                        + "missing gate re-opens the upload metadata leak.");

        assertArrayEquals(new String[]{ANALYTICS_READ}, anno.value(),
                method.getName() + " (" + route + ") should require exactly [analytics:read] — "
                        + "this endpoint exposes only metadata (filename/status/counts), not finance amounts.");

        assertFalse(anno.requireAll(),
                "requireAll should remain default (false) — only one permission is declared");
    }
}
