/**
 * 生产分析 & 人效分析 路由模块
 * 独立于SmartBI的生产数据分析模块
 */
import type { RouteRecordRaw } from 'vue-router';

const productionAnalyticsRoutes: RouteRecordRaw[] = [
  {
    path: 'production-analytics',
    name: 'ProductionAnalytics',
    redirect: '/production-analytics/production',
    meta: { requiresAuth: true, title: '生产分析', icon: 'Histogram', module: 'analytics' },
    children: [
      {
        path: 'production',
        name: 'PAProduction',
        component: () => import('@/views/production-analytics/ProductionAnalysis.vue'),
        meta: { requiresAuth: true, title: '生产数据分析', icon: 'Histogram', module: 'analytics' },
      },
      {
        path: 'efficiency',
        name: 'PAEfficiency',
        component: () => import('@/views/production-analytics/EfficiencyAnalysis.vue'),
        meta: { requiresAuth: true, title: '人效分析', icon: 'User', module: 'analytics' },
      },
      {
        path: 'cost',
        name: 'PACost',
        component: () => import('@/views/production-analytics/FactoryCostAnalysis.vue'),
        meta: { requiresAuth: true, title: '成本分析', icon: 'Coin', module: 'analytics' },
      },
      {
        path: 'yield-cost',
        name: 'PAYieldCost',
        component: () => import('@/views/production-analytics/M67YieldCost.vue'),
        meta: { requiresAuth: true, title: '成品出厂核算', icon: 'Coin', module: 'analytics' },
      },
    ],
  },
];

export default productionAnalyticsRoutes;
