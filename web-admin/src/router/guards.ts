/**
 * 路由守卫
 * 注意：不在顶层导入 store，避免在 pinia 初始化前访问
 */
import type { Router } from 'vue-router';
import type { ModuleName } from '@/store/modules/permission';
import { resolveModuleRegistryItemByRoute } from '@/config/moduleRegistry';

// 白名单路由 - 不需要登录
const whiteList = ['/login', '/403', '/404', '/mobile-only', '/demo'];

// Mobile 专属角色 - 不允许登录 Web 端
// 参考原型文档: docs/prd/prototype/index.html - 14角色权限矩阵
const MOBILE_ONLY_ROLES = [
  'operator',
  'quality_inspector',
  'warehouse_worker'
];

// 角色路由白名单 — 限制特定角色只能访问指定路径前缀
// 防止通过 URL 直接访问超出菜单范围的路由
const ROLE_PATH_WHITELIST: Record<string, string[]> = {
  finance_manager: [
    '/dashboard',
    '/finance',
    '/procurement/finance-review',
    '/smart-bi/dashboard',
    '/smart-bi/analysis-hub',   // WS4: 经营分析合并模块 (财务/销售/趋势/KPI) — finance_manager 主入口
    '/smart-bi/finance',
    '/smart-bi/financial-dashboard',
    '/smart-bi/sales',
    '/smart-bi/query',
    '/smart-bi/query-templates',
    '/smart-bi/analysis',
    '/restaurant/supplier-reconciliation',
    '/restaurant/cost-attribution',
    '/403',
    '/404',
  ],
};

export function setupRouterGuards(router: Router) {
  // 前置守卫
  router.beforeEach(async (to, _from, next) => {
    // 设置页面标题
    document.title = to.meta.title ? `${to.meta.title} - 白垩纪AI Agent` : '白垩纪AI Agent';

    // R40 BUG-7 fix: ElMessage.error patched to sticky (Bug #312, 2026-04-18)
    // means toasts persist across navigation. On route change, dismiss any
    // visible error/notification toasts so they don't accumulate (R40 audit
    // saw 4+ stale toasts visible simultaneously after a few clicks).
    if (typeof document !== 'undefined') {
      document.querySelectorAll('.el-message, .el-notification').forEach((el) => el.remove());
    }

    // 白名单路由直接放行
    if (whiteList.includes(to.path)) {
      next();
      return;
    }

    // 路由 meta 标记不需要认证的也直接放行 (如分享页)
    if (to.meta.requiresAuth === false) {
      next();
      return;
    }

    // 动态导入 store，确保 pinia 已初始化
    const { useAuthStore } = await import('@/store/modules/auth');
    const { usePermissionStore } = await import('@/store/modules/permission');

    // 检查是否登录
    const authStore = useAuthStore();

    if (!authStore.isAuthenticated) {
      // 未登录，跳转登录页（避免重复跳转）
      if (to.path !== '/login') {
        next({ path: '/login', query: { redirect: to.fullPath } });
      } else {
        next();
      }
      return;
    }

    // 同步角色、工厂ID、工厂类型到权限 store
    const permissionStore = usePermissionStore();
    permissionStore.setRole(authStore.currentRole, authStore.factoryId, authStore.factoryType, authStore.user?.id);
    await permissionStore.loadFromDb();

    // 检查是否是 Mobile 专属角色 (一线员工: 操作员、质检员、仓库工人)
    // 这些角色只能使用移动端 App，不能登录 Web 管理后台
    if (MOBILE_ONLY_ROLES.includes(authStore.currentRole)) {
      // 清除登录状态
      authStore.clearAuth();
      // 跳转到 Mobile 专属提示页面
      next({ path: '/mobile-only', query: { role: authStore.currentRole } });
      return;
    }

    // 检查角色路由白名单
    const roleWhitelist = ROLE_PATH_WHITELIST[authStore.currentRole];
    if (roleWhitelist) {
      const pathAllowed = roleWhitelist.some(prefix => to.path === prefix || to.path.startsWith(prefix + '/'));
      if (!pathAllowed) {
        next('/403');
        return;
      }
    }

    // 检查二级模块权限
    const moduleCode = (to.meta.moduleCode as string | undefined)
      || resolveModuleRegistryItemByRoute(to.path)?.moduleCode;
    if (moduleCode && !permissionStore.canAccessModuleCode(moduleCode)) {
      next('/403');
      return;
    }

    // 检查模块权限
    const module = to.meta.module as ModuleName | undefined;
    if (module) {
      if (!permissionStore.canAccess(module)) {
        next('/403');
        return;
      }
    }

    // Apr 24 UX: 工厂类型黑名单 (sidebar hideForFactoryTypes 的 route-level 对应)
    // 防止餐饮租户手输 URL 绕过侧边栏访问 manufacturing-only 页面
    const hideForTypes = to.meta.hideForFactoryTypes as string[] | undefined;
    if (hideForTypes && authStore.factoryType && hideForTypes.includes(authStore.factoryType)) {
      next('/403');
      return;
    }

    // 检查角色权限（如果路由指定了 roles）
    const allowedRoles = to.meta.roles as string[] | undefined;
    if (allowedRoles && allowedRoles.length > 0) {
      if (!allowedRoles.includes(authStore.currentRole)) {
        next('/403');
        return;
      }
    }

    next();
  });
}
