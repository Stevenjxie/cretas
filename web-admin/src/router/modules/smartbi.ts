/**
 * SmartBI 智能BI路由模块
 * 面向财务主管的经营分析工具
 */
import type { RouteRecordRaw } from 'vue-router';
import { buildHubRedirect, buildPathRedirect } from '../analysisHubRedirect';

const platformAdminOnlyRoles = ['platform_admin'];

const smartBIRoutes: RouteRecordRaw[] = [
  // SmartBI 主模块
  {
    path: 'smart-bi',
    name: 'SmartBI',
    redirect: '/smart-bi/dashboard',
    meta: { requiresAuth: true, title: '数据与分析', icon: 'TrendCharts', module: 'analytics' },
    children: [
      {
        path: 'dashboard',
        name: 'SmartBIDashboard',
        component: () => import('@/views/smart-bi/Dashboard.vue'),
        meta: { requiresAuth: true, title: '经营驾驶舱', icon: 'Odometer', module: 'analytics' },
      },
      // WS4: 经营分析合并模块入口 (财务/销售/趋势/KPI·指标 tab 化)。
      {
        path: 'analysis-hub',
        name: 'SmartBIAnalysisHub',
        component: () => import('@/views/smart-bi/BusinessAnalysisHub.vue'),
        meta: { requiresAuth: true, title: '经营分析', icon: 'TrendCharts', module: 'analytics' },
      },
      // WS4: 销售分析已合并入经营分析 hub (redirect 保书签 → ?tab=sales)。组件经 hub 加载。
      {
        path: 'sales',
        name: 'SmartBISales',
        redirect: (to) => buildHubRedirect(to, 'sales'),
      },
      {
        path: 'query-templates',
        name: 'SmartBIQueryTemplates',
        component: () => import('@/views/smart-bi/QueryTemplateManager.vue'),
        meta: { requiresAuth: true, title: '查询模板管理', icon: 'Tickets', module: 'analytics' },
      },
      {
        path: 'analysis',
        name: 'SmartBIAnalysis',
        component: () => import('@/views/smart-bi/SmartBIAnalysis.vue'),
        meta: { requiresAuth: true, title: '智能数据分析', icon: 'DataAnalysis', module: 'analytics' },
      },
      {
        path: 'upload',
        name: 'SmartBIExcelUpload',
        component: () => import('@/views/smart-bi/ExcelUpload.vue'),
        meta: { requiresAuth: true, title: 'Excel上传', icon: 'Upload', module: 'analytics' },
      },
      {
        path: 'data-completeness',
        name: 'SmartBIDataCompleteness',
        component: () => import('@/views/smart-bi/DataCompletenessView.vue'),
        meta: { requiresAuth: true, title: '数据完整度', icon: 'Checked', module: 'analytics' },
      },
      {
        path: 'upload-status',
        name: 'SmartBIUploadStatus',
        component: () => import('@/views/smart-bi/UploadStatusDashboard.vue'),
        meta: { requiresAuth: true, title: '上传状态', icon: 'Document', module: 'analytics' },
      },
      {
        path: 'food-kb-feedback',
        name: 'SmartBIFoodKBFeedback',
        component: () => import('@/views/smart-bi/FoodKBFeedback.vue'),
        meta: { requiresAuth: true, title: '知识库反馈', icon: 'ChatDotRound', module: 'analytics' },
      },
      {
        path: 'fallback-log',
        name: 'SmartBIFallbackLog',
        component: () => import('@/views/smart-bi/FallbackLogAdmin.vue'),
        meta: { requiresAuth: true, title: 'AI 追问日志', icon: 'DataLine', module: 'analytics' },
      },
      {
        path: 'calibration',
        name: 'SmartBICalibration',
        component: () => import('@/views/calibration/CalibrationListView.vue'),
        meta: { requiresAuth: true, title: '行为校准监控', icon: 'Aim', module: 'analytics', roles: platformAdminOnlyRoles },
      },
      // WS4: 财务看板已合并入经营分析 hub (redirect 保书签 → ?tab=finance, 保留 ?section=)。
      {
        path: 'financial-dashboard',
        name: 'FinancialDashboardPBI',
        redirect: (to) => buildHubRedirect(to, 'finance'),
      },
      // WS4 #9: What-If/餐饮V2/Gold预览删除 — redirect 到经营驾驶舱 (component 已不在 menu;
      // 直链 redirect 不 404, 保书签 query)。
      {
        path: 'whatif',
        name: 'SmartBIWhatIf',
        redirect: (to) => buildPathRedirect(to, '/smart-bi/dashboard'),
      },
      {
        path: 'restaurant-v2',
        name: 'SmartBIRestaurantV2',
        component: () => import('@/views/smart-bi/RestaurantV2Dashboard.vue'),
        meta: {
          requiresAuth: true,
          title: '餐饮综合分析',
          icon: 'DataAnalysis',
          module: 'analytics',
          hideForFactoryTypes: ['FACTORY'],
        },
      },
      {
        path: 'gold-preview',
        name: 'SmartBIGoldPreview',
        redirect: (to) => buildPathRedirect(to, '/smart-bi/dashboard'),
      },
      // QHJ 收入管理报表 (青花椒 / R_*_REAL restaurant chains; Phase I, 2026-05-13)
      {
        path: 'revenue-report',
        name: 'SmartBIRevenueReport',
        component: () => import('@/views/smart-bi/RevenueReport.vue'),
        meta: {
          requiresAuth: true,
          title: '收入管理报表',
          icon: 'Money',
          module: 'analytics',
          roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager', 'restaurant_manager'],
          hideForFactoryTypes: ['FACTORY'],  // restaurant tenants only
        },
      },
      // G4 AI 经营体检表 (餐饮诊断报告; 2026-06-03)
      {
        path: 'health-report',
        name: 'SmartBIHealthReport',
        component: () => import('@/views/smart-bi/HealthReportView.vue'),
        meta: {
          requiresAuth: true,
          title: 'AI 经营体检',
          icon: 'FirstAidKit',
          module: 'analytics',
          hideForFactoryTypes: ['FACTORY'],  // restaurant tenants only
        },
      },
      // Phase 0: 字段映射复核队列 (2026-06-15) — 闭合自改进环
      {
        path: 'mapping-review',
        name: 'SmartBIMappingReview',
        component: () => import('@/views/smart-bi/MappingReviewQueue.vue'),
        meta: { requiresAuth: true, title: '字段映射复核', icon: 'EditPen', module: 'analytics' },
      },
    ],
  },
];

export const smartBIRedirects: RouteRecordRaw[] = [
  // P3: AI问答合并入 AI探索 query tab。function-form 保留 incoming query (e.g. AI 快捷入口的 ?q=<问题>)。
  { path: '/smart-bi/query', redirect: (to) => ({ path: '/smart-bi/analysis', query: { ...to.query, tab: 'query' } }) },
  // P4 + WS4: 财务数据分析合并入经营分析 hub 财务 tab analysis section。保留 incoming ?tab=cost
  // (FinanceAnalysis 内部子 tab) + 加 section=analysis (FinanceAnalysis section 选择器)。
  { path: '/smart-bi/finance', redirect: (to) => ({ path: '/smart-bi/analysis-hub', query: { ...to.query, tab: 'finance', section: 'analysis' } }) },
];

export default smartBIRoutes;
