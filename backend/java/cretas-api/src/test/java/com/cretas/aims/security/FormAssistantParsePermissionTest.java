package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /api/mobile/{factoryId}/form-assistant/parse} 的权限口径回归。
 *
 * <p>2026-07-28: web-admin 的 AI 建单抽屉 (AiEntryDrawer × 9 页) 从
 * {@code /api/mobile/ai/chat} 迁到 {@code /form-assistant/parse}。原来那条注解是
 * {@code analytics:read_write} —— 只有 super_admin / dispatcher / finance_manager 一类拿得到,
 * 而挂抽屉的页面主力是采购/销售/仓库主管。照搬就是三条线整个 403。
 *
 * <p>现在的口径是「至少能写点什么」(hasAnyPermission OR)。下面盯两头:
 * 9 个页面的角色都进得来, 纯只读角色进不来。
 */
@DisplayName("form-assistant/parse — 建单角色可用, 只读角色挡住")
class FormAssistantParsePermissionTest {

    /** 与 FormAssistantController 上 @RequirePermission 的清单保持一致。 */
    private static final String[] FORM_FILL_PERMISSIONS = {
            "analytics:read_write",
            "production:write", "procurement:write", "sales:write",
            "warehouse:write", "inventory:write", "system:write", "restaurant:write"
    };

    private final PermissionServiceImpl permissionService = new PermissionServiceImpl();

    private User userWithRole(FactoryUserRole role) {
        User u = new User();
        u.setId(1L);
        u.setFactoryId("F006");
        u.setRoleCode(role.name());
        return u;
    }

    private boolean canParse(FactoryUserRole role) {
        return permissionService.hasAnyPermission(userWithRole(role), FORM_FILL_PERMISSIONS);
    }

    @Test
    @DisplayName("采购单抽屉: procurement_manager 可用 (旧的 analytics:read_write 会把他 403)")
    void procurementManagerCanParse() {
        assertTrue(canParse(FactoryUserRole.procurement_manager));
    }

    @Test
    @DisplayName("销售单抽屉: sales_manager 可用 (他的 analytics 只有 read)")
    void salesManagerCanParse() {
        assertTrue(canParse(FactoryUserRole.sales_manager));
    }

    @Test
    @DisplayName("盘点/入库抽屉: warehouse_manager 可用 (他根本没有 analytics)")
    void warehouseManagerCanParse() {
        assertTrue(canParse(FactoryUserRole.warehouse_manager));
    }

    @Test
    @DisplayName("生产计划/批次抽屉: dispatcher 与 workshop_supervisor 都可用")
    void productionRolesCanParse() {
        assertTrue(canParse(FactoryUserRole.dispatcher));
        assertTrue(canParse(FactoryUserRole.workshop_supervisor));
    }

    @Test
    @DisplayName("产品录入抽屉: factory_super_admin 可用")
    void superAdminCanParse() {
        assertTrue(canParse(FactoryUserRole.factory_super_admin));
    }

    @Test
    @DisplayName("viewer 全模块只读 → 不能烧 LLM 配额去填一张他提交不了的表")
    void viewerCannotParse() {
        assertFalse(canParse(FactoryUserRole.viewer));
    }

    @Test
    @DisplayName("unactivated 一律拒绝")
    void unactivatedCannotParse() {
        assertFalse(canParse(FactoryUserRole.unactivated));
    }
}
