/**
 * SmartBI 智能BI路由模块
 * 面向财务主管的经营分析工具
 */
import type { RouteRecordRaw } from 'vue-router';

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
      {
        path: 'sales',
        name: 'SmartBISales',
        component: () => import('@/views/smart-bi/SalesAnalysis.vue'),
        meta: { requiresAuth: true, title: '销售分析', icon: 'TrendCharts', module: 'analytics' },
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
        meta: { requiresAuth: true, title: '行为校准监控', icon: 'Aim', module: 'analytics' },
      },
      {
        path: 'financial-dashboard',
        name: 'FinancialDashboardPBI',
        component: () => import('@/views/smart-bi/FinancialDashboardPBI.vue'),
        meta: { requiresAuth: true, title: '财务 PBI 看板', icon: 'TrendCharts', module: 'analytics' },
      },
      {
        path: 'whatif',
        name: 'SmartBIWhatIf',
        component: () => import('@/views/smart-bi/WhatIfSimulator.vue'),
        meta: { requiresAuth: true, title: 'What-If模拟', icon: 'TrendCharts', module: 'analytics' },
      },
      {
        path: 'restaurant-v2',
        name: 'SmartBIRestaurantV2',
        component: () => import('@/views/smart-bi/RestaurantV2Dashboard.vue'),
        meta: { requiresAuth: true, title: '餐饮 V2 Dashboard', icon: 'DataAnalysis', module: 'analytics' },
      },
      {
        path: 'gold-preview',
        name: 'SmartBIGoldPreview',
        component: () => import('@/views/smart-bi/GoldPreview.vue'),
        meta: { requiresAuth: true, title: 'Gold 预览', icon: 'Cpu', module: 'analytics' },
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
          hideForFactoryTypes: ['FACTORY'],  // restaurant tenants only
        },
      },
    ],
  },
];

export const smartBIRedirects: RouteRecordRaw[] = [
  // P3: AI问答合并入 AI探索 query tab。function-form 保留 incoming query (e.g. AI 快捷入口的 ?q=<问题>)。
  { path: '/smart-bi/query', redirect: (to) => ({ path: '/smart-bi/analysis', query: { ...to.query, tab: 'query' } }) },
  // P4: 财务数据分析合并入财务看板 analysis section。function-form 保留 incoming ?tab=cost (FinanceAnalysis 内部子 tab) + 加 section=analysis (section 选择器)。
  { path: '/smart-bi/finance', redirect: (to) => ({ path: '/smart-bi/financial-dashboard', query: { ...to.query, section: 'analysis' } }) },
];

export default smartBIRoutes;
