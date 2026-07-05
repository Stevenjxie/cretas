package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC fix (2026-07-05, F006 六扇门客户场景): 仓库员(warehouse_worker) — 张权描述的
 * "仓管收多少就收多少" 角色 — 之前在 采购收货(purchase receive) 流程上全程 403, 因为
 * {@code GET /purchase/orders/by-status}(选采购单收货列表) 和
 * {@code GET /purchase/orders/{orderId}}(采购单详情, 收货页预填) 只认
 * {@code procurement:read(_write)}, 而仓库员本职只有 warehouse/inventory 权限.
 *
 * <p>修复: {@link com.cretas.aims.controller.inventory.PurchaseController#listOrdersByStatus}
 * 和 {@link com.cretas.aims.controller.inventory.PurchaseController#getOrder} 的
 * {@code @RequirePermission} 加入 {@code warehouse:read_write}/{@code warehouse:read}
 * 作为替代分支 —— 与既有 {@code getOrderByNumber}(扫码收货) 设计一致: 收货是仓库操作,
 * 仓管读取采购单是收货前必要前置, 不构成越权(创建采购单/审批采购单仍需 procurement:read_write,
 * 未变).
 *
 * <p>本测试验证 hardcoded fallback matrix ({@link PermissionServiceImpl#PERMISSION_MATRIX})
 * 下 warehouse_worker 能满足这两个端点的 OR 权限列表(即 {@code hasAnyPermission} 语义,
 * 对应 {@code RequirePermission(requireAll=false)} 的默认行为), 且没有被过度授权
 * (procurement 本身仍是 none — 仓库员不能创建/审批采购单; hr/finance 等无关模块不变).
 *
 * <p>Test 用 hardcoded matrix fallback(未 mock DB layer), 是
 * {@link PermissionServiceImpl#hasPermission} 在 L2(工厂覆盖)和 L1(platform_role_permissions)
 * 都缺失时的兜底路径 —— 与 fresh test profile(无 Flyway seed)一致。生产环境 L1 优先命中,
 * 同样已被 Flyway V20261024_22(warehouse/inventory rw)覆盖到, 与本测试断言一致。
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceImplWarehouseWorkerReceiveTest {

    @InjectMocks
    private PermissionServiceImpl permissionService;

    private User makeWarehouseWorker() {
        User u = new User();
        u.setId(101L);
        u.setRoleCode("warehouse_worker");
        u.setFactoryId("F006");
        return u;
    }

    private User makeQualityInspector() {
        // Genuinely-unrelated role: no warehouse, no procurement permission in the
        // fallback matrix — used as a negative control to prove the fix doesn't
        // accidentally open the endpoint to every role (still 403s as expected).
        User u = new User();
        u.setId(102L);
        u.setRoleCode("quality_inspector");
        u.setFactoryId("F006");
        return u;
    }

    @Test
    @DisplayName("warehouse_worker satisfies listOrdersByStatus permission set (procurement:rw/r OR warehouse:rw/r)")
    void warehouseWorker_can_list_orders_by_status() {
        assertTrue(permissionService.hasAnyPermission(
                makeWarehouseWorker(),
                "procurement:read_write", "procurement:read", "warehouse:read_write", "warehouse:read"));
    }

    @Test
    @DisplayName("warehouse_worker satisfies getOrder (order detail) permission set")
    void warehouseWorker_can_get_order_detail() {
        assertTrue(permissionService.hasAnyPermission(
                makeWarehouseWorker(),
                "procurement:read_write", "procurement:read", "warehouse:read_write", "warehouse:read"));
    }

    @Test
    @DisplayName("warehouse_worker still cannot create/approve purchase orders (procurement:read_write false)")
    void warehouseWorker_cannot_write_procurement() {
        // Not over-granted: creating/submitting/approving/cancelling POs still requires
        // procurement:read_write, which warehouse_worker does not have.
        assertFalse(permissionService.hasPermission(makeWarehouseWorker(), "procurement:read_write"));
    }

    @Test
    @DisplayName("warehouse_worker can create + confirm receives (already had inventory:write pre-fix)")
    void warehouseWorker_can_receive_and_confirm() {
        assertTrue(permissionService.hasAnyPermission(
                makeWarehouseWorker(), "procurement:read_write", "inventory:write"));
    }

    @Test
    @DisplayName("regression guard: a role with neither procurement nor warehouse still 403s (not over-granted globally)")
    void unrelated_role_still_denied() {
        assertFalse(permissionService.hasAnyPermission(
                makeQualityInspector(),
                "procurement:read_write", "procurement:read", "warehouse:read_write", "warehouse:read"));
    }
}
