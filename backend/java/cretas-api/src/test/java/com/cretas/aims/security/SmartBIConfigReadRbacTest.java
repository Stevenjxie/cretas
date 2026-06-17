package com.cretas.aims.security;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.SmartBIConfigController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Locks the PR #486 deferred sweep follow-up (2026-05-12): the 12 read endpoints
 * in {@link SmartBIConfigController} that previously had no RBAC gate must each
 * be guarded by {@code analytics:read}.
 *
 * <p>Background: the controller class Javadoc claims "此 API 仅限管理员访问，请在
 * SecurityConfig 中配置权限控制" — but no class-level
 * {@code @RequireRole}/{@code @RequirePermission} was ever added, and the GET
 * endpoints carried no method-level gate. CUD endpoints already have
 * {@code analytics:read_write}; only the GETs were leaking.
 *
 * <p>The reads cover intents, thresholds, incentive rules, field mappings,
 * metric formulas, chart templates (+ recommend + for-metric), config status,
 * and data sources (list + by-id). All use {@code analytics:read} to match the
 * PR #480/#486 sibling convention — config metadata is admin/finance/dispatcher
 * territory but the broader read permission allows sales/restaurant/viewer to
 * inspect templates for their own dashboards.
 *
 * @see SmartBIDashboardSiblingsRbacTest reference sibling-sweep pattern (PR #486)
 * @see com.cretas.aims.config.PermissionInterceptor 4位一体 response builder
 */
@DisplayName("PR #486 deferred — SmartBIConfig read endpoints gated by analytics:read")
class SmartBIConfigReadRbacTest {

    private static final String ANALYTICS_READ = "analytics:read";

    @Test
    @DisplayName("listIntents (/intents) declares @RequirePermission(analytics:read)")
    void listIntents_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "listIntents", HttpServletRequest.class, String.class);
        assertGatedByAnalyticsRead(method, "/intents");
    }

    @Test
    @DisplayName("listThresholds (/thresholds) declares @RequirePermission(analytics:read)")
    void listThresholds_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "listThresholds", HttpServletRequest.class, String.class);
        assertGatedByAnalyticsRead(method, "/thresholds");
    }

    @Test
    @DisplayName("listIncentiveRules (/incentive-rules) declares @RequirePermission(analytics:read)")
    void listIncentiveRules_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "listIncentiveRules", HttpServletRequest.class, String.class);
        assertGatedByAnalyticsRead(method, "/incentive-rules");
    }

    @Test
    @DisplayName("listFieldMappings (/field-mappings) declares @RequirePermission(analytics:read)")
    void listFieldMappings_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "listFieldMappings", HttpServletRequest.class, String.class);
        assertGatedByAnalyticsRead(method, "/field-mappings");
    }

    @Test
    @DisplayName("listMetricFormulas (/metric-formulas) declares @RequirePermission(analytics:read)")
    void listMetricFormulas_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "listMetricFormulas", HttpServletRequest.class, String.class);
        assertGatedByAnalyticsRead(method, "/metric-formulas");
    }

    @Test
    @DisplayName("listChartTemplates (/chart-templates) declares @RequirePermission(analytics:read)")
    void listChartTemplates_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "listChartTemplates", HttpServletRequest.class, String.class, String.class);
        assertGatedByAnalyticsRead(method, "/chart-templates");
    }

    @Test
    @DisplayName("getChartTemplate (/chart-templates/{code}) declares @RequirePermission(analytics:read)")
    void getChartTemplate_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "getChartTemplate", String.class, String.class);
        assertGatedByAnalyticsRead(method, "/chart-templates/{code}");
    }

    @Test
    @DisplayName("recommendChart (/chart-templates/recommend) declares @RequirePermission(analytics:read)")
    void recommendChart_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "recommendChart", String.class, int.class, boolean.class);
        assertGatedByAnalyticsRead(method, "/chart-templates/recommend");
    }

    @Test
    @DisplayName("getChartTemplatesForMetric (/chart-templates/for-metric/{metricCode}) declares @RequirePermission(analytics:read)")
    void getChartTemplatesForMetric_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "getChartTemplatesForMetric", String.class);
        assertGatedByAnalyticsRead(method, "/chart-templates/for-metric/{metricCode}");
    }

    @Test
    @DisplayName("getConfigStatus (/status) declares @RequirePermission(analytics:read)")
    void getConfigStatus_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "getConfigStatus");
        assertGatedByAnalyticsRead(method, "/status");
    }

    @Test
    @DisplayName("listDataSources (/data-sources) declares @RequirePermission(analytics:read)")
    void listDataSources_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "listDataSources",
                HttpServletRequest.class, String.class, String.class, String.class, Boolean.class, int.class, int.class);
        assertGatedByAnalyticsRead(method, "/data-sources");
    }

    @Test
    @DisplayName("getDataSource (/data-sources/{id}) declares @RequirePermission(analytics:read)")
    void getDataSource_isAnnotatedWithAnalyticsRead() throws Exception {
        Method method = SmartBIConfigController.class.getDeclaredMethod(
                "getDataSource", HttpServletRequest.class, Long.class, String.class);
        assertGatedByAnalyticsRead(method, "/data-sources/{id}");
    }

    private static void assertGatedByAnalyticsRead(Method method, String routeForMessage) {
        RequirePermission anno = method.getAnnotation(RequirePermission.class);
        assertNotNull(anno,
                "@RequirePermission must be present on " + method.getName() + " (" + routeForMessage + ") — "
                        + "see PR #486 deferred sweep. Removing this gate re-opens the warehouse_mgr1 "
                        + "config/metadata read leak (intent names, threshold values, dictionary mappings, "
                        + "chart templates, data source configs).");

        assertArrayEquals(new String[]{ANALYTICS_READ}, anno.value(),
                method.getName() + " must require exactly [analytics:read] — "
                        + "stricter (read_write) blocks sales_manager / finance_manager / "
                        + "restaurant_manager / viewer who legitimately need to read templates "
                        + "and config metadata for their own dashboards.");

        assertFalse(anno.requireAll(),
                "requireAll should remain default (false) — only one permission is declared");
    }
}
