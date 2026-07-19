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

const agentOpsAdminRoles = [
  'factory_super_admin',
  'platform_admin',
  'permission_admin',
  'restaurant_manager',
  'restaurant_owner',
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
  {
    path: 'ops/agent-ops',
    name: 'AgentOps',
    component: () => import('@/views/platform/agent-ops/AgentOpsShell.vue'),
    redirect: '/ops/agent-ops/eval-sets',
    meta: {
      requiresAuth: true,
      title: 'Agent 运行与评测',
      icon: 'DataAnalysis',
      module: 'analytics',
      roles: agentOpsAdminRoles,
      hideForFactoryTypes: ['FACTORY'],
      businessDomain: 'RESTAURANT',
    },
    children: [
      {
        path: 'eval-sets',
        name: 'AgentOpsEvalSets',
        component: () => import('@/views/platform/agent-ops/EvalSetsView.vue'),
        meta: { requiresAuth: true, title: 'Eval Sets', module: 'analytics', roles: agentOpsAdminRoles, hidden: true },
      },
      {
        path: 'experiments',
        name: 'AgentOpsExperiments',
        component: () => import('@/views/platform/agent-ops/ExperimentsView.vue'),
        meta: { requiresAuth: true, title: 'Experiments', module: 'analytics', roles: agentOpsAdminRoles, hidden: true },
      },
      {
        path: 'run-trace',
        name: 'AgentOpsRunTrace',
        component: () => import('@/views/platform/agent-ops/RunTraceView.vue'),
        meta: { requiresAuth: true, title: 'Run Trace', module: 'analytics', roles: agentOpsAdminRoles, hidden: true },
      },
    ],
  },
];

export default opsRoutes;
