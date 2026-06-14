package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whitelist tests for {@code procurement:price:view} permission.
 *
 * <p>PR #415 Option B (2026-05-12): backend ResponseAdvice strips price fields
 * for roles outside the whitelist. This test verifies the whitelist itself.
 *
 * @author Cretas Team
 * @since 2026-05-12
 */
@DisplayName("procurement:price:view — role whitelist (PR #415 Option B)")
class PriceViewPermissionTest {

    private static final String PERM = "procurement:price:view";

    private final PermissionServiceImpl permissionService = new PermissionServiceImpl();

    private User userWithRole(String roleCode) {
        User u = new User();
        u.setId(1L);
        u.setFactoryId("F006");
        u.setRoleCode(roleCode);
        return u;
    }

    // ───── Whitelisted roles (should HAVE the permission) ─────

    @Test
    @DisplayName("factory_super_admin: HAS procurement:price:view (super-admin short-circuit)")
    void superAdmin_hasPermission() {
        assertTrue(permissionService.hasPermission(
                userWithRole(FactoryUserRole.factory_super_admin.name()), PERM));
    }

    @Test
    @DisplayName("procurement_manager: HAS procurement:price:view")
    void procurementManager_hasPermission() {
        assertTrue(permissionService.hasPermission(
                userWithRole(FactoryUserRole.procurement_manager.name()), PERM));
    }

    @Test
    @DisplayName("finance_manager: HAS procurement:price:view")
    void financeManager_hasPermission() {
        assertTrue(permissionService.hasPermission(
                userWithRole(FactoryUserRole.finance_manager.name()), PERM));
    }

    @Test
    @DisplayName("sales_manager: HAS procurement:price:view (三价对比 needs cost)")
    void salesManager_hasPermission() {
        assertTrue(permissionService.hasPermission(
                userWithRole(FactoryUserRole.sales_manager.name()), PERM));
    }

    @Test
    @DisplayName("dispatcher: HAS procurement:price:view (调度需协调成本)")
    void dispatcher_hasPermission() {
        assertTrue(permissionService.hasPermission(
                userWithRole(FactoryUserRole.dispatcher.name()), PERM));
    }

    @Test
    @DisplayName("restaurant_manager: HAS procurement:price:view (菜品成本)")
    void restaurantManager_hasPermission() {
        assertTrue(permissionService.hasPermission(
                userWithRole(FactoryUserRole.restaurant_manager.name()), PERM));
    }

    // ───── Non-whitelisted roles (should NOT have the permission) ─────

    @Test
    @DisplayName("warehouse_manager: LACKS procurement:price:view (PR #415 RED finding)")
    void warehouseManager_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.warehouse_manager.name()), PERM),
                "f006_warehouse_mgr 不应看到价格字段 (PR #415 Option B)");
    }

    @Test
    @DisplayName("warehouse_worker: LACKS procurement:price:view")
    void warehouseWorker_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.warehouse_worker.name()), PERM));
    }

    @Test
    @DisplayName("quality_manager: LACKS procurement:price:view")
    void qualityManager_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.quality_manager.name()), PERM));
    }

    @Test
    @DisplayName("quality_inspector: LACKS procurement:price:view")
    void qualityInspector_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.quality_inspector.name()), PERM));
    }

    @Test
    @DisplayName("operator: LACKS procurement:price:view")
    void operator_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.operator.name()), PERM));
    }

    @Test
    @DisplayName("hr_admin: LACKS procurement:price:view")
    void hrAdmin_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.hr_admin.name()), PERM));
    }

    @Test
    @DisplayName("equipment_admin: LACKS procurement:price:view")
    void equipmentAdmin_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.equipment_admin.name()), PERM));
    }

    @Test
    @DisplayName("viewer: LACKS procurement:price:view (read-only but no $$)")
    void viewer_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.viewer.name()), PERM));
    }

    @Test
    @DisplayName("unactivated: LACKS procurement:price:view")
    void unactivated_lacksPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.unactivated.name()), PERM));
    }

    @Test
    @DisplayName("null user: returns false")
    void nullUser_returnsFalse() {
        assertFalse(permissionService.hasPermission(null, PERM));
    }

    @Test
    @DisplayName("Standard permission still works (regression: warehouse_manager has warehouse:read)")
    void regression_standardPermissionsUnaffected() {
        assertTrue(permissionService.hasPermission(
                userWithRole(FactoryUserRole.warehouse_manager.name()), "warehouse:read"),
                "warehouse_manager 仓库读权限 应该仍然有效 (回归检查)");
        // warehouse_manager 没有 finance:write (cross-module)
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.warehouse_manager.name()), "finance:write"));
    }

    @Test
    @DisplayName("warehouse_manager: LACKS inventory:legacy_import despite inventory:read_write")
    void warehouseManager_lacksLegacyImportPermission() {
        assertFalse(permissionService.hasPermission(
                userWithRole(FactoryUserRole.warehouse_manager.name()), "inventory:legacy_import"),
                "inventory:legacy_import must be a dedicated import/migration permission, not implied by inventory:read_write");
    }

    @Test
    @DisplayName("factory_super_admin: HAS inventory:legacy_import")
    void factorySuperAdmin_hasLegacyImportPermission() {
        assertTrue(permissionService.hasPermission(
                userWithRole(FactoryUserRole.factory_super_admin.name()), "inventory:legacy_import"));
    }
}
