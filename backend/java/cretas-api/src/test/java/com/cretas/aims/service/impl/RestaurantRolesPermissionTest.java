package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: 餐饮专属角色权限矩阵验证
 *
 * <p>Tests the three new restaurant-domain roles added for the restaurant
 * procurement-to-payment workflow:
 * <ul>
 *   <li>{@code restaurant_chef}   — 厨师长: warehouse:rw (报货/领料/验收), restaurant:rw</li>
 *   <li>{@code restaurant_purchaser} — 餐饮采购: procurement:rw (请购/采购), restaurant:rw</li>
 *   <li>{@code restaurant_owner}  — 餐饮老板: full restaurant ops incl. finance:rw (月对账)</li>
 * </ul>
 *
 * <p>Uses the hardcoded fallback matrix (no DB layers mocked), which is what
 * {@link PermissionServiceImpl#hasPermission} falls back to when L1/L2 absent.
 * Mirrors the pattern established by {@link PermissionServiceImplQualityInspectorTest}.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantRolesPermissionTest {

    @InjectMocks
    private PermissionServiceImpl permissionService;

    // ===== Helper factories =====

    private User makeUser(FactoryUserRole role) {
        User u = new User();
        u.setId(100L);
        u.setRoleCode(role.name());
        u.setFactoryId("RES_3101_009");
        return u;
    }

    // ===================================================================
    // restaurant_chef (厨师长)
    // ===================================================================

    @Nested
    @DisplayName("restaurant_chef — 厨师长")
    class ChefTests {

        @Test
        @DisplayName("chef: restaurant:read_write (RestaurantModule 访问)")
        void chef_restaurant_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "restaurant:read_write"));
        }

        @Test
        @DisplayName("chef: warehouse:read_write (报货create+submit+approve 需 warehouse:rw)")
        void chef_warehouse_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "warehouse:read_write"));
        }

        @Test
        @DisplayName("chef: warehouse:read (验收入库 GET /limits 需 warehouse:read)")
        void chef_warehouse_read() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "warehouse:read"));
        }

        @Test
        @DisplayName("chef: hasAnyPermission for 验收入库(confirm) endpoint [warehouse:read_write OR inventory:write]")
        void chef_can_confirm_receive() {
            // SupplierDeliveryNoteController confirm = @RequirePermission({"warehouse:read_write"})
            assertTrue(permissionService.hasAnyPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "warehouse:read_write"));
        }

        @Test
        @DisplayName("chef: 不能使用 procurement:read_write (不审采购单)")
        void chef_no_procurement_read_write() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "procurement:read_write"));
        }

        @Test
        @DisplayName("chef: 不能使用 finance:read_write (不做月对账确认)")
        void chef_no_finance_read_write() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "finance:read_write"));
        }

        @Test
        @DisplayName("chef: procurement:read (只读采购单列表)")
        void chef_procurement_read_only() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "procurement:read"));
        }

        @Test
        @DisplayName("chef: 价格字段查看 — 不在 PRICE_VIEW_ROLES (厨师长不需查单价)")
        void chef_no_price_view() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "procurement:price:view"));
        }

        @Test
        @DisplayName("chef: enum 已定义 — fromRoleCode 能正确解析")
        void chef_enum_resolvable() {
            assertEquals(FactoryUserRole.restaurant_chef,
                    FactoryUserRole.fromRoleCode("restaurant_chef"));
        }

        @Test
        @DisplayName("chef: department = restaurant")
        void chef_department() {
            assertEquals("restaurant", FactoryUserRole.restaurant_chef.getDepartment());
        }

        @Test
        @DisplayName("chef: level = 15 (中级角色, 低于 manager level=10)")
        void chef_level() {
            assertEquals(15, FactoryUserRole.restaurant_chef.getLevel());
        }
    }

    // ===================================================================
    // restaurant_purchaser (餐饮采购)
    // ===================================================================

    @Nested
    @DisplayName("restaurant_purchaser — 餐饮采购")
    class PurchaserTests {

        @Test
        @DisplayName("purchaser: restaurant:read_write (RestaurantModule 访问)")
        void purchaser_restaurant_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "restaurant:read_write"));
        }

        @Test
        @DisplayName("purchaser: procurement:read_write (请购 create/submit/approve + 采购 confirm/approve)")
        void purchaser_procurement_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "procurement:read_write"));
        }

        @Test
        @DisplayName("purchaser: procurement:approve (审批请购单 PENDING_APPROVAL→APPROVED)")
        void purchaser_procurement_approve() {
            // checkAction("read_write", "approve") → permType.equals("read_write") → true
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "procurement:approve"));
        }

        @Test
        @DisplayName("purchaser: 价格字段可见 (PRICE_VIEW_ROLES 含 restaurant_purchaser)")
        void purchaser_price_view() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "procurement:price:view"));
        }

        @Test
        @DisplayName("purchaser: 不能使用 warehouse:read_write (不验收入库, 只读库存)")
        void purchaser_no_warehouse_read_write() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "warehouse:read_write"));
        }

        @Test
        @DisplayName("purchaser: warehouse:read (可查看库存状态)")
        void purchaser_warehouse_read_only() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "warehouse:read"));
        }

        @Test
        @DisplayName("purchaser: 不能使用 finance:read_write (不做月对账确认)")
        void purchaser_no_finance_read_write() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "finance:read_write"));
        }

        @Test
        @DisplayName("purchaser: finance:read (可查看采购金额 — 触发 finance_review_view 白名单)")
        void purchaser_finance_read_only() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "finance:read"));
        }

        @Test
        @DisplayName("purchaser: hasAnyPermission SupplierDeliveryNote price-anomaly submit endpoint [warehouse:read_write OR procurement:write]")
        void purchaser_can_submit_price_anomaly() {
            // price-anomaly/submit = @RequirePermission({"warehouse:read_write", "procurement:write"})
            // purchaser has procurement:read_write (contains "write") → procurement:write passes
            assertTrue(permissionService.hasAnyPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser),
                    "warehouse:read_write", "procurement:write"));
        }

        @Test
        @DisplayName("purchaser: enum 已定义 — fromRoleCode 能正确解析")
        void purchaser_enum_resolvable() {
            assertEquals(FactoryUserRole.restaurant_purchaser,
                    FactoryUserRole.fromRoleCode("restaurant_purchaser"));
        }

        @Test
        @DisplayName("purchaser: department = restaurant")
        void purchaser_department() {
            assertEquals("restaurant", FactoryUserRole.restaurant_purchaser.getDepartment());
        }
    }

    // ===================================================================
    // restaurant_owner (餐饮老板)
    // ===================================================================

    @Nested
    @DisplayName("restaurant_owner — 餐饮老板")
    class OwnerTests {

        @Test
        @DisplayName("owner: restaurant:read_write")
        void owner_restaurant_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "restaurant:read_write"));
        }

        @Test
        @DisplayName("owner: procurement:read_write (采购审批)")
        void owner_procurement_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "procurement:read_write"));
        }

        @Test
        @DisplayName("owner: finance:read_write (月对账确认 + 财务审核通过/驳回)")
        void owner_finance_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "finance:read_write"));
        }

        @Test
        @DisplayName("owner: warehouse:read_write (报货审批 + 验收入库)")
        void owner_warehouse_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "warehouse:read_write"));
        }

        @Test
        @DisplayName("owner: analytics:read_write (经营分析)")
        void owner_analytics_read_write() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "analytics:read_write"));
        }

        @Test
        @DisplayName("owner: 价格字段可见 (PRICE_VIEW_ROLES 含 restaurant_owner)")
        void owner_price_view() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "procurement:price:view"));
        }

        @Test
        @DisplayName("owner: finance_review_view (FINANCE_REVIEW_VIEW_ROLES 含 restaurant_owner)")
        void owner_finance_review_view() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner),
                    PermissionServiceImpl.FINANCE_REVIEW_VIEW_PERMISSION));
        }

        @Test
        @DisplayName("owner: hasAnyPermission 月对账 GET endpoint [finance:read OR finance:read_write]")
        void owner_can_read_monthly_reconciliation() {
            // SupplierMonthlyReconciliationController list = @RequirePermission({"finance:read","finance:read_write"})
            assertTrue(permissionService.hasAnyPermission(
                    makeUser(FactoryUserRole.restaurant_owner),
                    "finance:read", "finance:read_write"));
        }

        @Test
        @DisplayName("owner: 月对账确认 POST /confirm endpoint [finance:read_write]")
        void owner_can_confirm_monthly_reconciliation() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "finance:read_write"));
        }

        @Test
        @DisplayName("owner: 价格异常审批 approve endpoint [warehouse:rw OR finance:rw OR warehouse:r OR finance:r]")
        void owner_can_approve_price_anomaly() {
            // price-anomaly/approve = @RequirePermission({"warehouse:read","warehouse:read_write","finance:read","finance:read_write"})
            assertTrue(permissionService.hasAnyPermission(
                    makeUser(FactoryUserRole.restaurant_owner),
                    "warehouse:read", "warehouse:read_write", "finance:read", "finance:read_write"));
        }

        @Test
        @DisplayName("owner: finance:approve → checkAction(read_write, approve) = true")
        void owner_can_approve_finance() {
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "finance:approve"));
        }

        @Test
        @DisplayName("owner: level = 5 (高于 manager level=10, 仅次于 factory_super_admin)")
        void owner_level() {
            assertEquals(5, FactoryUserRole.restaurant_owner.getLevel());
        }

        @Test
        @DisplayName("owner: department = restaurant")
        void owner_department() {
            assertEquals("restaurant", FactoryUserRole.restaurant_owner.getDepartment());
        }

        @Test
        @DisplayName("owner: enum 已定义 — fromRoleCode 能正确解析")
        void owner_enum_resolvable() {
            assertEquals(FactoryUserRole.restaurant_owner,
                    FactoryUserRole.fromRoleCode("restaurant_owner"));
        }

        @Test
        @DisplayName("owner: getRolePermissions 包含 finance:read + finance:write")
        void owner_getRolePermissions_contains_finance() {
            var perms = permissionService.getRolePermissions(FactoryUserRole.restaurant_owner);
            assertTrue(perms.contains("finance:read"), "owner should have finance:read");
            assertTrue(perms.contains("finance:write"), "owner should have finance:write");
        }
    }

    // ===================================================================
    // Isolation guard — factory roles must NOT get restaurant module access
    // ===================================================================

    @Nested
    @DisplayName("隔离保护 — 新餐饮角色不能访问制造业核心模块")
    class IsolationGuard {

        @Test
        @DisplayName("restaurant_chef 不能访问 production:write (工厂生产模块)")
        void chef_no_production_write() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_chef), "production:write"));
        }

        @Test
        @DisplayName("restaurant_purchaser 不能访问 production:write")
        void purchaser_no_production_write() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_purchaser), "production:write"));
        }

        @Test
        @DisplayName("restaurant_owner 不能访问 hr:read_write (不管人事)")
        void owner_no_hr_write() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_owner), "hr:read_write"));
        }

        @Test
        @DisplayName("现有 restaurant_manager 权限未被污染 — 仍只有 procurement:read (不是 read_write)")
        void existing_manager_procurement_read_not_write() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_manager), "procurement:read_write"),
                    "restaurant_manager should NOT have procurement:read_write (regression guard)");
            assertTrue(permissionService.hasPermission(
                    makeUser(FactoryUserRole.restaurant_manager), "procurement:read"),
                    "restaurant_manager should still have procurement:read");
        }

        @Test
        @DisplayName("现有 warehouse_manager 角色不受影响 — 仍无 restaurant:read_write")
        void warehouse_manager_no_restaurant_module() {
            assertFalse(permissionService.hasPermission(
                    makeUser(FactoryUserRole.warehouse_manager), "restaurant:read_write"),
                    "warehouse_manager should NOT gain restaurant access (regression guard)");
        }
    }
}
