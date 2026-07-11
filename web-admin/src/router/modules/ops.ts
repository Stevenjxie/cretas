/**
 * 运营分析路由模块 (2026-07-12 — 撤单稽核 + 区域坪效)
 *
 * 门控 (mirrors crm.ts member-analysis pattern exactly):
 *   - module: 'analytics' (FACTORY_TYPE_MODULE_FILTER 对 FACTORY/RESTAURANT 都不清零 analytics,
 *     用 hideForFactoryTypes 做业态收紧, 而不是 module:'restaurant' 那种更严格的角色矩阵)
 *   - roles: 与 crm.ts / revenue-report 相同的财务/餐饮管理角色白名单 (撤单/营收数据敏感)
 *   - hideForFactoryTypes: ['FACTORY'] — 仅餐饮/demo 租户可达, 制造业租户 sidebar 不显示 +
 *     手输 URL 也被 guards.ts 路由级黑名单拦截 (见 router/guards.ts 141-147 行)
 */
import type { RouteRecordRaw } from 'vue-router';

const restaurantAnalyticsRoles = [
  'factory_super_admin',
  'platform_admin',
  'permission_admin',
  'finance_manager',
  'restaurant_manager',
];

const opsRoutes: RouteRecordRaw[] = [
  {
    path: 'ops/operations-analysis',
    name: 'OpsOperationsAnalysis',
    component: () => import('@/views/ops/operations-analysis/index.vue'),
    meta: {
      requiresAuth: true,
      title: '运营分析',
      icon: 'Operation',
      module: 'analytics',
      roles: restaurantAnalyticsRoles,
      hideForFactoryTypes: ['FACTORY'], // restaurant/demo tenants only
    },
  },
];

export default opsRoutes;
