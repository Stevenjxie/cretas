/**
 * Vue Router 配置
 */
import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router';
import { setupRouterGuards } from './guards';
import smartBIRoutes, { smartBIRedirects } from './modules/smartbi';
import productionAnalyticsRoutes from './modules/production-analytics';

// 基础路由 - 不需要权限
const baseRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { requiresAuth: false, title: '登录' }
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { requiresAuth: false, title: '无权限' }
  },
  {
    path: '/404',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { requiresAuth: false, title: '页面不存在' }
  },
  {
    path: '/mobile-only',
    name: 'MobileOnly',
    component: () => import('@/views/error/mobile-only.vue'),
    meta: { requiresAuth: false, title: '请使用移动端' }
  },
  // PR #872 (#860 follow-up): 公开分享只读视图. requiresAuth=false → guards.ts
  // 跳过 auth/permission/role 检查. token 本身 (32 char base62) 是唯一凭证.
  {
    path: '/share/:token',
    name: 'PublicShare',
    component: () => import('@/views/share/SharePage.vue'),
    meta: { requiresAuth: false, title: '分享内容' }
  }
];

// 业务路由 - 需要权限
const businessRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/components/layout/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      // Dashboard
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { requiresAuth: true, title: '首页', icon: 'House', module: 'dashboard' }
      },
      {
        path: 'dashboard/production-progress',
        name: 'ProductionProgressDashboard',
        component: () => import('@/views/dashboard/production-progress.vue'),
        meta: { requiresAuth: true, title: '生产进度看板', icon: 'Monitor', module: 'dashboard' }
      },
      {
        path: 'dashboard/widgets',
        name: 'WidgetDemoDashboard',
        component: () => import('@/views/dashboard/widget-demo.vue'),
        meta: { requiresAuth: true, title: '自定义看板', icon: 'Grid', module: 'dashboard' }
      },

      // 生产管理
      {
        path: 'production',
        name: 'Production',
        redirect: '/production/batches',
        meta: { requiresAuth: true, title: '生产管理', icon: 'Operation', module: 'production' },
        children: [
          {
            path: 'batches',
            name: 'ProductionBatches',
            component: () => import('@/views/production/batches/list.vue'),
            meta: { requiresAuth: true, title: '生产批次', module: 'production' }
          },
          {
            path: 'batches/:id',
            name: 'ProductionBatchDetail',
            component: () => import('@/views/production/batches/detail.vue'),
            meta: { requiresAuth: true, title: '批次详情', module: 'production', hidden: true }
          },
          {
            path: 'plans',
            name: 'ProductionPlans',
            component: () => import('@/views/production/plans/list.vue'),
            meta: { requiresAuth: true, title: '生产计划', module: 'production' }
          },
          {
            path: 'delivery-warnings',
            name: 'ProductionDeliveryWarnings',
            component: () => import('@/views/production/delivery-warnings/list.vue'),
            meta: { requiresAuth: true, title: '交货预警', module: 'production' }
          },
          {
            path: 'conversions',
            name: 'ProductionConversions',
            redirect: '/production/bom?tab=conversion',
            meta: { requiresAuth: true, title: '转换率配置', module: 'production', hidden: true }
          },
          {
            path: 'bom',
            name: 'BomManagement',
            component: () => import('@/views/production/bom-unified/index.vue'),
            meta: { requiresAuth: true, title: 'BOM配方管理', module: 'production' }
          },
          {
            path: 'bom/tree',
            name: 'BomTreeExpansion',
            component: () => import('@/views/production/bom/tree.vue'),
            meta: { requiresAuth: true, title: '多级 BOM 展开', module: 'production' }
          },
          {
            path: 'approval',
            name: 'ProductionApproval',
            component: () => import('@/views/production/approval/list.vue'),
            meta: { requiresAuth: true, title: '报工审批', module: 'production' }
          },
          {
            path: 'bom-achievement',
            name: 'BomAchievement',
            component: () => import('@/views/production/BomAchievementView.vue'),
            meta: { requiresAuth: true, title: 'BOM达成率分析', module: 'production' }
          },
          {
            path: 'process-io',
            name: 'ProcessIOComparison',
            component: () => import('@/views/production/ProcessIOComparison.vue'),
            meta: { requiresAuth: true, title: '工序投入产出对比', module: 'production' }
          },
          {
            path: 'material-requisitions',
            name: 'FactoryMaterialRequisitions',
            component: () => import('@/views/factory/material-requisitions/list.vue'),
            meta: { requiresAuth: true, title: '物料需求单', module: 'production' }
          }
        ]
      },

      // 仓储管理
      {
        path: 'warehouse',
        name: 'Warehouse',
        redirect: '/warehouse/materials',
        meta: { requiresAuth: true, title: '仓储管理', icon: 'Box', module: 'warehouse' },
        children: [
          {
            path: 'material-types',
            name: 'WarehouseMaterialTypes',
            component: () => import('@/views/warehouse/material-types/list.vue'),
            meta: { requiresAuth: true, title: '原料类型字典', module: 'warehouse' }
          },
          {
            path: 'materials',
            name: 'WarehouseMaterials',
            component: () => import('@/views/warehouse/materials/list.vue'),
            meta: { requiresAuth: true, title: '原材料批次', module: 'warehouse' }
          },
          {
            path: 'wip-batches',
            name: 'WarehouseWipBatches',
            component: () => import('@/views/warehouse/materials/wip-list.vue'),
            meta: { requiresAuth: true, title: '在制品 (WIP)', module: 'warehouse' }
          },
          {
            path: 'shipments',
            name: 'WarehouseShipments',
            component: () => import('@/views/warehouse/shipments/list.vue'),
            meta: { requiresAuth: true, title: '出货管理', module: 'warehouse' }
          },
          {
            path: 'inventory',
            name: 'WarehouseInventory',
            component: () => import('@/views/warehouse/inventory/index.vue'),
            meta: { requiresAuth: true, title: '盘点管理', module: 'warehouse' }
          },
          {
            path: 'reusable-containers',
            name: 'WarehouseReusableContainers',
            component: () => import('@/views/warehouse/reusable-containers/list.vue'),
            meta: { requiresAuth: true, title: '周转耗材管理', module: 'warehouse' }
          },
          {
            path: 'material-price-trend',
            name: 'MaterialPriceTrend',
            component: () => import('@/views/warehouse/MaterialPriceTrendView.vue'),
            meta: { requiresAuth: true, title: '物料均价趋势', module: 'warehouse' }
          }
        ]
      },

      // PR #309 B2 — 分仓库存查询 (Dropdown 跨 factoryId × warehouseId)
      {
        path: 'inventory',
        name: 'Inventory',
        redirect: '/inventory/by-warehouse',
        meta: { requiresAuth: true, title: '分仓库存查询', module: 'warehouse' },
        children: [
          {
            path: 'by-warehouse',
            name: 'InventoryByWarehouse',
            component: () => import('@/views/inventory/by-warehouse/index.vue'),
            meta: { requiresAuth: true, title: '分仓库存查询', module: 'warehouse' }
          }
        ]
      },

      // 调拨管理
      {
        path: 'transfer',
        name: 'Transfer',
        redirect: '/transfer/list',
        meta: { requiresAuth: true, title: '调拨管理', icon: 'Sell', module: 'warehouse' },
        children: [
          {
            path: 'list',
            name: 'TransferList',
            component: () => import('@/views/transfer/list.vue'),
            meta: { requiresAuth: true, title: '调拨单列表', module: 'warehouse' }
          },
          {
            path: ':id',
            name: 'TransferDetail',
            component: () => import('@/views/transfer/detail.vue'),
            meta: { requiresAuth: true, title: '调拨详情', module: 'warehouse', hidden: true }
          }
        ]
      },

      // 质量管理
      {
        path: 'quality',
        name: 'Quality',
        redirect: '/quality/inspections',
        meta: { requiresAuth: true, title: '质量管理', icon: 'Checked', module: 'quality' },
        children: [
          {
            path: 'inspections',
            name: 'QualityInspections',
            component: () => import('@/views/quality/inspections/list.vue'),
            meta: { requiresAuth: true, title: '质检记录', module: 'quality' }
          },
          {
            path: 'disposals',
            name: 'QualityDisposals',
            component: () => import('@/views/quality/disposals/list.vue'),
            meta: { requiresAuth: true, title: '废弃处理', module: 'quality' }
          },
          {
            path: 'standards',
            name: 'QualityStandards',
            component: () => import('@/views/quality/standards/list.vue'),
            meta: { requiresAuth: true, title: '质检标准', module: 'quality' }
          },
          // Sprint4-H Q-PROCESS-1: 工序质检不良记录闭环
          {
            path: 'defects',
            name: 'QualityDefects',
            component: () => import('@/views/quality/defects/list.vue'),
            meta: { requiresAuth: true, title: '工序质检不良', module: 'quality' }
          },
          // Sprint4-H Q-RETURN-1: 质检退回单 (上游退回, 区分客户销售退货)
          {
            path: 'returns',
            name: 'QualityReturns',
            component: () => import('@/views/quality/returns/list.vue'),
            meta: { requiresAuth: true, title: '质检退回单', module: 'quality' }
          }
        ]
      },

      // 采购管理
      {
        path: 'procurement',
        name: 'Procurement',
        redirect: '/procurement/orders',
        meta: { requiresAuth: true, title: '采购管理', icon: 'ShoppingCart', module: 'procurement' },
        children: [
          {
            path: 'orders',
            name: 'ProcurementOrders',
            component: () => import('@/views/procurement/orders/list.vue'),
            meta: { requiresAuth: true, title: '采购订单', module: 'procurement' }
          },
          {
            path: 'orders/:id',
            name: 'ProcurementOrderDetail',
            component: () => import('@/views/procurement/orders/detail.vue'),
            meta: { requiresAuth: true, title: '采购订单详情', module: 'procurement', hidden: true }
          },
          {
            path: 'suppliers',
            name: 'ProcurementSuppliers',
            component: () => import('@/views/procurement/suppliers/list.vue'),
            meta: { requiresAuth: true, title: '供应商管理', module: 'procurement' }
          },
          {
            path: 'price-lists',
            name: 'ProcurementPriceLists',
            component: () => import('@/views/procurement/price-lists/list.vue'),
            meta: { requiresAuth: true, title: '价格表管理', module: 'procurement' }
          },
          // 六扇门 V1 #9 — 采购入库管理 (audit fix 2026-04-26)
          {
            path: 'receives',
            name: 'ProcurementReceives',
            component: () => import('@/views/procurement/receives/list.vue'),
            meta: { requiresAuth: true, title: '采购入库', module: 'procurement' }
          },
          // Sprint2-J P-FIN-1 follow-up (Chat 6 Vue): 财务审核 PC 入口
          // 后端 approveOrder 触发条件 (priceAlert OR totalAmount > 阈值) 满足时
          // 自动进 PENDING_FINANCE_REVIEW, 财务在此审核. RBAC 由 detail.vue v-if + 后端
          // @RequirePermission("finance:read_write") 双层保护. module: 'finance' 让
          // finance_manager 可见 (web-admin 当前 matrix 把 finance_manager 的 finance
          // 设为 'none', 上线后需配套调整 matrix 或 backend pull permissions).
          {
            // Issue #736 fix (2026-05-17): 显式 roles 白名单, 防 viewer 凭 finance:r 进入.
            // 后端 PurchaseController.listOrdersByStatus 也加了 procurement:finance_review:view
            // 双层防御 (前端路由 + 后端 API 都拒绝 viewer).
            path: 'finance-review',
            name: 'PurchaseOrderFinanceReviewList',
            component: () => import('@/views/procurement/finance-review/list.vue'),
            meta: {
              requiresAuth: true,
              title: '财务待审采购单',
              module: 'finance',
              roles: ['factory_super_admin', 'platform_admin', 'procurement_manager', 'finance_manager', 'dispatcher', 'permission_admin', 'department_admin']
            }
          },
          {
            path: 'finance-review/:id',
            name: 'PurchaseOrderFinanceReviewDetail',
            component: () => import('@/views/procurement/finance-review/detail.vue'),
            meta: {
              requiresAuth: true,
              title: '财务审核详情',
              module: 'finance',
              hidden: true,
              roles: ['factory_super_admin', 'platform_admin', 'procurement_manager', 'finance_manager', 'dispatcher', 'permission_admin', 'department_admin']
            }
          },
          // P-NUCLEAR-1 (28-Backlog #30): 核价单 询价 → 核价 → 采购 pipeline
          {
            path: 'inquiry-quotes',
            name: 'InquiryQuoteList',
            component: () => import('@/views/procurement/inquiry-quotes/list.vue'),
            meta: { requiresAuth: true, title: '核价单', module: 'procurement' }
          },
          {
            path: 'inquiry-quotes/:id',
            name: 'InquiryQuoteDetail',
            component: () => import('@/views/procurement/inquiry-quotes/detail.vue'),
            meta: { requiresAuth: true, title: '核价单详情', module: 'procurement', hidden: true }
          },
          // 采购审批规则 自由配置 (F006 客户要求, 替代硬编码 10% 阈值)
          {
            path: 'approval-rules',
            name: 'PurchaseApprovalRules',
            component: () => import('@/views/procurement/approval-rules/list.vue'),
            meta: {
              requiresAuth: true,
              title: '采购审批规则',
              module: 'procurement',
              roles: ['factory_super_admin', 'permission_admin', 'procurement_manager', 'finance_manager']
            }
          }
        ]
      },

      // 销售管理
      {
        path: 'sales',
        name: 'Sales',
        redirect: '/sales/orders',
        meta: { requiresAuth: true, title: '销售管理', icon: 'Goods', module: 'sales' },
        children: [
          {
            path: 'orders',
            name: 'SalesOrders',
            component: () => import('@/views/sales/orders/list.vue'),
            meta: { requiresAuth: true, title: '销售订单', module: 'sales' }
          },
          {
            path: 'orders/:id',
            name: 'SalesOrderDetail',
            component: () => import('@/views/sales/orders/detail.vue'),
            meta: { requiresAuth: true, title: '销售订单详情', module: 'sales', hidden: true }
          },
          // Sprint 4 Wave 2 S-PROFIT-DETAIL-1: per-product profit drill-down (11 cols).
          // Hidden from sidebar; reached via button on detail.vue.
          {
            path: 'orders/:id/profit',
            name: 'SalesOrderProfitDetail',
            component: () => import('@/views/sales/orders/profit-detail.vue'),
            meta: { requiresAuth: true, title: '产品级利润详情', module: 'sales', hidden: true }
          },
          {
            path: 'quotes',
            name: 'SalesQuotes',
            component: () => import('@/views/sales/quotes/list.vue'),
            meta: { requiresAuth: true, title: '运营报价', module: 'sales' }
          },
          // T-RTA (issue #531): 退货流程 — F006 customer feedback 第四次会议 956-1037.
          // Backend ReturnOrderController existed (covers both 采购退货 + 销售退货 since 2026-Q1)
          // but no frontend view shipped. These 2 routes close the F006 gap for SALES_RETURN side.
          {
            path: 'returns',
            name: 'SalesReturns',
            component: () => import('@/views/sales/returns/list.vue'),
            meta: { requiresAuth: true, title: '销售退货', module: 'sales' }
          },
          {
            path: 'returns/:id',
            name: 'SalesReturnDetail',
            component: () => import('@/views/sales/returns/detail.vue'),
            meta: { requiresAuth: true, title: '退货单详情', module: 'sales', hidden: true }
          },
          {
            path: 'finished-goods',
            name: 'SalesFinishedGoods',
            component: () => import('@/views/sales/finished-goods/list.vue'),
            meta: { requiresAuth: true, title: '成品库存', module: 'sales', hideForFactoryTypes: ['RESTAURANT'] }
          },
          // Issue #761: 独立 detail route — PR #756 之前只用 ElMessageBox.alert 占位
          {
            path: 'finished-goods/:id',
            name: 'SalesFinishedGoodsDetail',
            component: () => import('@/views/sales/finished-goods/detail.vue'),
            meta: { requiresAuth: true, title: '成品批次详情', module: 'sales', hidden: true, hideForFactoryTypes: ['RESTAURANT'] }
          },
          {
            path: 'customers',
            name: 'SalesCustomers',
            component: () => import('@/views/sales/customers/list.vue'),
            meta: { requiresAuth: true, title: '客户管理', module: 'sales' }
          },
          // Sprint 4 W1 S-CUSTOMER-TAB-1: customer 360° 21-tab detail view
          {
            path: 'customers/:id',
            name: 'SalesCustomerDetail',
            component: () => import('@/views/sales/customers/detail.vue'),
            meta: {
              requiresAuth: true,
              title: '客户详情',
              module: 'sales',
              hidden: true,
              activeMenu: '/sales/customers'
            }
          },
          // P1 #21 S-CRM-1: customer tracking record Vue UI (backend Sprint 4 W2 #727)
          {
            path: 'customer-tracking',
            name: 'SalesCustomerTracking',
            component: () => import('@/views/sales/customer-tracking/list.vue'),
            meta: { requiresAuth: true, title: '客户跟踪记录', module: 'sales' }
          },
          {
            path: 'shipments',
            name: 'SalesShipments',
            component: () => import('@/views/sales/shipments/list.vue'),
            meta: { requiresAuth: true, title: '出货记录', module: 'sales', hideForFactoryTypes: ['RESTAURANT'] }
          },
          {
            path: 'vehicles',
            name: 'SalesVehicles',
            component: () => import('@/views/sales/vehicles/list.vue'),
            meta: { requiresAuth: true, title: '车辆字典', module: 'sales', hideForFactoryTypes: ['RESTAURANT'] }
          },
          // Sprint4-H F-AR-1 (Chat H): 销售订单财务审核 PC 入口.
          // 销售员 submitForFinanceReview 后状态进 PENDING_FINANCE_REVIEW,
          // 财务在此查看成本核算 (BOM 标准/预估/实际/利润) 并 approve/reject.
          // RBAC: detail.vue v-if + 后端 @RequirePermission("finance:read_write") 双层保护.
          {
            path: 'finance-review',
            name: 'SalesOrderFinanceReviewList',
            component: () => import('@/views/sales/finance-review/list.vue'),
            meta: { requiresAuth: true, title: '财务待审销售单', module: 'finance' }
          },
          {
            path: 'finance-review/:id',
            name: 'SalesOrderFinanceReviewDetail',
            component: () => import('@/views/sales/finance-review/detail.vue'),
            meta: { requiresAuth: true, title: '销售财务审核详情', module: 'finance', hidden: true }
          },
          // Sprint 4 W2 S-REMIND-1: 我的提醒
          {
            path: 'reminders',
            name: 'SalesReminders',
            component: () => import('@/views/sales/reminders/MyReminders.vue'),
            meta: { requiresAuth: true, title: '我的提醒', module: 'sales' }
          },
          // Sprint 4 W2 S-NEED-1: 客户需求
          {
            path: 'needs',
            name: 'SalesNeeds',
            component: () => import('@/views/sales/needs/list.vue'),
            meta: { requiresAuth: true, title: '客户需求', module: 'sales' }
          },
          // Sprint 4 W2 S-REPORTS-PRESETS: 销售报表 (5 active + 9 Sprint 5 stub)
          {
            path: 'reports',
            name: 'SalesReports',
            component: () => import('@/views/sales/reports/dashboard.vue'),
            meta: { requiresAuth: true, title: '销售报表', module: 'sales' }
          },
          {
            path: 'reports/daily',
            name: 'SalesReportDaily',
            component: () => import('@/views/sales/reports/daily.vue'),
            meta: { requiresAuth: true, title: '销售额日报', module: 'sales', hidden: true }
          },
          {
            path: 'reports/monthly',
            name: 'SalesReportMonthly',
            component: () => import('@/views/sales/reports/monthly.vue'),
            meta: { requiresAuth: true, title: '销售额月报', module: 'sales', hidden: true }
          },
          {
            path: 'reports/yearly',
            name: 'SalesReportYearly',
            component: () => import('@/views/sales/reports/yearly.vue'),
            meta: { requiresAuth: true, title: '销售额年报', module: 'sales', hidden: true }
          },
          {
            path: 'reports/customer-rank',
            name: 'SalesReportCustomerRank',
            component: () => import('@/views/sales/reports/customer-rank.vue'),
            meta: { requiresAuth: true, title: '客户销售排行', module: 'sales', hidden: true }
          },
          {
            path: 'reports/product-rank',
            name: 'SalesReportProductRank',
            component: () => import('@/views/sales/reports/product-rank.vue'),
            meta: { requiresAuth: true, title: '产品销售排行', module: 'sales', hidden: true }
          }
        ]
      },

      // 人事管理
      {
        path: 'hr',
        name: 'HR',
        redirect: '/hr/employees',
        meta: { requiresAuth: true, title: '人事管理', icon: 'User', module: 'hr' },
        children: [
          {
            path: 'employees',
            name: 'HREmployees',
            component: () => import('@/views/hr/employees/list.vue'),
            meta: { requiresAuth: true, title: '员工管理', module: 'hr' }
          },
          {
            path: 'attendance',
            name: 'HRAttendance',
            component: () => import('@/views/hr/attendance/list.vue'),
            meta: { requiresAuth: true, title: '考勤管理', module: 'hr' }
          },
          {
            path: 'attendance-exceptions',
            name: 'HRAttendanceExceptions',
            component: () => import('@/views/hr/attendance/exceptions.vue'),
            meta: { requiresAuth: true, title: '考勤异常处理', module: 'hr' }
          },
          {
            path: 'whitelist',
            name: 'HRWhitelist',
            component: () => import('@/views/hr/whitelist/index.vue'),
            meta: { requiresAuth: true, title: '白名单管理', module: 'hr' }
          },
          {
            path: 'departments',
            name: 'HRDepartments',
            component: () => import('@/views/hr/departments/index.vue'),
            meta: { requiresAuth: true, title: '部门管理', module: 'hr' }
          },
          {
            path: 'work-types',
            name: 'HRWorkTypes',
            component: () => import('@/views/hr/work-types/list.vue'),
            meta: { requiresAuth: true, title: '工种字典', module: 'hr' }
          },
          {
            path: 'leave-requests',
            name: 'HRLeaveRequests',
            component: () => import('@/views/hr/leave-requests/list.vue'),
            meta: { requiresAuth: true, title: '请假申请', module: 'hr' }
          },
          {
            path: 'overtime-requests',
            name: 'HROvertimeRequests',
            component: () => import('@/views/hr/overtime-requests/list.vue'),
            meta: { requiresAuth: true, title: '加班/调休申请', module: 'hr' }
          },
          {
            path: 'expense-requests',
            name: 'HRExpenseRequests',
            component: () => import('@/views/hr/expense-requests/list.vue'),
            meta: { requiresAuth: true, title: '报销申请', module: 'hr' }
          },
          {
            path: 'leave-balances',
            name: 'HRLeaveBalances',
            component: () => import('@/views/hr/leave-balances/list.vue'),
            meta: { requiresAuth: true, title: '假期余额', module: 'hr' }
          },
          {
            // #835 follow-up — 调休账单 (CompTime Balance + Ledger)
            path: 'comptime-balance',
            name: 'HRCompTimeBalance',
            component: () => import('@/views/hr/attendance/comptime-balance.vue'),
            meta: { requiresAuth: true, title: '调休账单', module: 'hr' }
          },
          {
            // #835 follow-up — 高级排班可视化日历 (月视图: 员工 × 日期 × 班次)
            path: 'shift-calendar',
            name: 'HRShiftCalendar',
            component: () => import('@/views/hr/attendance/shift-calendar.vue'),
            meta: { requiresAuth: true, title: '排班日历', module: 'hr' }
          },
          {
            // P1-40 H-WAGE-FULL MVP — 月度工资单 (基本工资 + 社保 + 个税 + 实发)
            path: 'salaries',
            name: 'HRSalaries',
            component: () => import('@/views/hr/salaries/list.vue'),
            meta: { requiresAuth: true, title: '工资管理', module: 'hr' }
          },
          {
            // P1-40 H-WAGE 专项扣除 follow-up — 个税 6 大专项附加扣除 (月度计税自动减项)
            path: 'special-deductions',
            name: 'HRSpecialDeductions',
            component: () => import('@/views/hr/special-deductions/list.vue'),
            meta: { requiresAuth: true, title: '专项扣除', module: 'hr' }
          },
          {
            // #833 H-WAGE follow-up — 工厂级 社保 / 公积金 费率配置 (替代硬编码)
            path: 'config/insurance-rates',
            name: 'HRInsuranceConfig',
            component: () => import('@/views/hr/config/insurance-rates.vue'),
            meta: { requiresAuth: true, title: '社保公积金配置', module: 'hr' }
          },
          {
            // #863 follow-up — 城市差异化 社保 / 公积金 覆盖 (基数 + 部分费率)
            path: 'config/city-overrides',
            name: 'HRCityInsuranceOverrides',
            component: () => import('@/views/hr/config/city-overrides.vue'),
            meta: { requiresAuth: true, title: '城市差异化社保', module: 'hr' }
          },
          {
            // #833 H-WAGE follow-up — 年度汇算 (综合所得 annual settlement)
            path: 'annual-settlement',
            name: 'HRAnnualSettlement',
            component: () => import('@/views/hr/salaries/annual-settlement.vue'),
            meta: { requiresAuth: true, title: '年度汇算', module: 'hr' }
          }
        ]
      },

      // 设备管理
      {
        path: 'equipment',
        name: 'Equipment',
        redirect: '/equipment/list',
        meta: { requiresAuth: true, title: '设备管理', icon: 'Monitor', module: 'equipment' },
        children: [
          {
            path: 'list',
            name: 'EquipmentList',
            component: () => import('@/views/equipment/list/index.vue'),
            meta: { requiresAuth: true, title: '设备列表', module: 'equipment' }
          },
          {
            path: 'maintenance',
            name: 'EquipmentMaintenance',
            component: () => import('@/views/equipment/maintenance/list.vue'),
            meta: { requiresAuth: true, title: '维护记录', module: 'equipment' }
          },
          {
            path: 'alerts',
            name: 'EquipmentAlerts',
            component: () => import('@/views/equipment/alerts/index.vue'),
            meta: { requiresAuth: true, title: '告警管理', module: 'equipment' }
          }
        ]
      },

      // 财务管理 (Web专属)
      {
        path: 'finance',
        name: 'Finance',
        redirect: '/finance/costs',
        meta: { requiresAuth: true, title: '财务管理', icon: 'Money', module: 'finance' },
        children: [
          {
            path: 'costs',
            name: 'FinanceCosts',
            component: () => import('@/views/finance/costs/index.vue'),
            meta: { requiresAuth: true, title: '财务概览', module: 'finance' }
          },
          {
            path: 'reports',
            name: 'FinanceReports',
            component: () => import('@/views/finance/reports/index.vue'),
            meta: { requiresAuth: true, title: '财务报表', module: 'finance' }
          },
          {
            path: 'ar-ap',
            name: 'FinanceArAp',
            component: () => import('@/views/finance/ar-ap/index.vue'),
            meta: { requiresAuth: true, title: '应收应付', module: 'finance' }
          },
          {
            path: 'sku-margin',
            name: 'FinanceSkuMargin',
            component: () => import('@/views/finance/sku-margin/index.vue'),
            meta: { requiresAuth: true, title: 'SKU毛利率分析', module: 'finance' }
          },
          {
            path: 'invoices',
            name: 'FinanceInvoices',
            component: () => import('@/views/finance/invoices/list.vue'),
            meta: { requiresAuth: true, title: '开票管理', module: 'finance' }
          },
          {
            path: 'payments',
            name: 'FinancePayments',
            component: () => import('@/views/finance/payments/list.vue'),
            meta: { requiresAuth: true, title: '收款管理', module: 'finance' }
          },
          // R28 P2 (R23 P5 deferred): PENDING adjustment approval queue
          {
            path: 'adjustments',
            name: 'FinanceAdjustments',
            component: () => import('@/views/finance/adjustments/list.vue'),
            meta: { requiresAuth: true, title: '调整审批', module: 'finance' }
          }
        ]
      },

      // 研发管理 (v1 §2.1 — 2 页结构: 研发样品管理 + 已转样品库)
      {
        path: 'rd',
        name: 'RD',
        redirect: '/rd/samples',
        meta: { requiresAuth: true, title: '研发管理', module: 'production' },
        children: [
          {
            path: 'samples',
            name: 'RdSamples',
            component: () => import('@/views/rd/samples/list.vue'),
            meta: { requiresAuth: true, title: '研发样品管理', module: 'production' }
          },
          {
            path: 'converted',
            name: 'RdConverted',
            component: () => import('@/views/rd/converted/list.vue'),
            meta: { requiresAuth: true, title: '已转样品库', module: 'production' }
          }
        ]
      },

      // 系统管理 (Web专属)
      {
        path: 'system',
        name: 'System',
        redirect: '/system/users',
        meta: { requiresAuth: true, title: '系统管理', icon: 'Setting', module: 'system' },
        children: [
          {
            path: 'users',
            name: 'SystemUsers',
            component: () => import('@/views/system/users/list.vue'),
            meta: { requiresAuth: true, title: '用户管理', module: 'system' }
          },
          {
            path: 'roles',
            name: 'SystemRoles',
            component: () => import('@/views/system/roles/list.vue'),
            meta: { requiresAuth: true, title: '角色管理', module: 'system' }
          },
          {
            path: 'logs',
            name: 'SystemLogs',
            component: () => import('@/views/system/logs/index.vue'),
            meta: { requiresAuth: true, title: '操作日志', module: 'system' }
          },
          {
            path: 'encoding-rules',
            name: 'SystemEncodingRules',
            component: () => import('@/views/system/encoding-rules/list.vue'),
            meta: { requiresAuth: true, title: '编码规则字典', module: 'system' }
          },
          {
            path: 'approval-chains',
            name: 'SystemApprovalChains',
            component: () => import('@/views/system/approval-chains/list.vue'),
            meta: { requiresAuth: true, title: '审批链配置', module: 'system' }
          },
          {
            path: 'ai-quota',
            name: 'SystemAIQuota',
            component: () => import('@/views/system/ai-quota/list.vue'),
            meta: { requiresAuth: true, title: 'AI 配额规则', module: 'system' }
          },
          {
            path: 'role-permissions',
            name: 'SystemRolePermissions',
            component: () => import('@/views/system/role-permissions/index.vue'),
            meta: { requiresAuth: true, title: '全局权限矩阵 (L1)', module: 'system' }
          },
          {
            path: 'settings',
            name: 'SystemSettings',
            component: () => import('@/views/system/settings/index.vue'),
            meta: { requiresAuth: true, title: '系统设置', module: 'system' }
          },
          {
            path: 'ai-intents',
            name: 'SystemAIIntents',
            component: () => import('@/views/system/ai-intents/index.vue'),
            meta: { requiresAuth: true, title: 'AI意图配置', module: 'system' }
          },
          {
            path: 'skill-tools',
            name: 'SystemSkillTools',
            component: () => import('@/views/system/skill-tools/index.vue'),
            meta: { requiresAuth: true, title: 'Skill/Tool 治理', module: 'system' }
          },
          {
            path: 'llm-usage',
            name: 'SystemLLMUsage',
            component: () => import('@/views/system/llm-usage/index.vue'),
            meta: { requiresAuth: true, title: 'LLM 用量监控', module: 'system' }
          },
          {
            // Sprint 4 Chat K C-LOG-AUDIT-1 (2026-05-16): 系统操作日志独立 menu.
            // Tracks entity-level CRUD via @Loggable AOP. backend: OperationLogController.
            path: 'operation-log',
            name: 'SystemOperationLog',
            component: () => import('@/views/system/operation-log/index.vue'),
            meta: { requiresAuth: true, title: '操作日志', module: 'system' }
          },
          {
            // Sprint 4 Chat K C-EXPORT-CENTER-1 (2026-05-16): 用户可配置的跨模块导出规则.
            // backend: ExportRuleController + ExportExecutor (EntityManager + SpEL + EasyExcel).
            path: 'export-center',
            name: 'SystemExportCenter',
            component: () => import('@/views/system/export-center/index.vue'),
            meta: { requiresAuth: true, title: '导出中心', module: 'system' }
          },
          {
            // Sprint 4 Chat K C-IMPORT-CENTER-1 (2026-05-16): 用户可配置的 Excel 导入规则 +
            // dryrun + commit. backend: ImportRuleController + ImportExecutor + ImportCommitter.
            path: 'import-center',
            name: 'SystemImportCenter',
            component: () => import('@/views/system/import-center/index.vue'),
            meta: { requiresAuth: true, title: '导入中心', module: 'system' }
          },
          {
            // 数据织网 A spec admin audit page (Phase 3 Day 10 + Phase 4.5 wire-up)
            path: 'data-fabric/capability-audit',
            name: 'CapabilityAudit',
            component: () => import('@/views/system/data-fabric/capability-audit.vue'),
            meta: { requiresAuth: true, title: '能力驱动渲染审计', module: 'system' }
          },
          {
            // 数据织网 C spec §6.3 — cell-level lineage detail (Day 26).
            // Reached via TrustIndicator's "查看来源" button which pushes
            // /audit/cell?type=&id=&field= (see the top-level CellAudit
            // route below). Hidden from sidebar; admin-only via meta.roles.
            //
            // This sidebar entry is intentionally hidden — the canonical
            // path is the top-level /audit/cell to match the spec NS-7 URL.
            path: 'data-fabric/cell-audit',
            name: 'CellAuditSystem',
            component: () => import('@/views/system/data-fabric/cell-audit.vue'),
            meta: {
              requiresAuth: true,
              title: '字段血统审计',
              module: 'system',
              hidden: true,
              roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
            },
          },
          {
            // 数据织网 C spec §6.4 — factory provenance config admin page (Day 27).
            // Sidebar-discoverable (NOT hidden) — admins need to find this.
            // GET + PUT live at /api/smartbi/factory-config/provenance.
            path: 'data-fabric/provenance-config',
            name: 'ProvenanceConfig',
            component: () => import('@/views/system/data-fabric/provenance-config.vue'),
            meta: {
              requiresAuth: true,
              title: 'Provenance 配置',
              module: 'system',
              roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
            },
          },
          {
            // 餐饮 Phase A A-3 Task 3.5: data quality queue admin page
            path: 'data-quality-queue',
            name: 'AdminDataQualityQueue',
            component: () => import('@/views/admin/data-quality-queue.vue'),
            meta: {
              requiresAuth: true,
              title: '数据质量队列',
              module: 'system',
              hidden: false,
              roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
            },
          },
          {
            // 餐饮 Phase A A-3 Task 3.6: data quality queue detail page (history)
            path: 'data-quality-queue/:id',
            name: 'AdminDataQualityQueueDetail',
            component: () => import('@/views/admin/data-quality-queue-detail.vue'),
            meta: {
              requiresAuth: true,
              title: '数据质量队列详情',
              module: 'system',
              hidden: true,
              roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
            },
          },
          {
            path: 'products',
            name: 'ProductManagement',
            component: () => import('@/views/system/products/index.vue'),
            meta: { requiresAuth: true, title: '产品信息管理', module: 'system' }
          },
          {
            path: 'features',
            name: 'SystemFeatures',
            component: () => import('@/views/system/features/index.vue'),
            meta: { requiresAuth: true, title: '功能模块配置', module: 'system' }
          },
          {
            path: 'pos',
            name: 'SystemPos',
            component: () => import('@/views/system/pos/list.vue'),
            meta: { requiresAuth: true, title: 'POS集成', module: 'system' }
          },
          {
            path: 'work-processes',
            name: 'WorkProcesses',
            component: () => import('@/views/system/work-processes/index.vue'),
            meta: { requiresAuth: true, title: '工序管理', module: 'system' }
          },
          {
            path: 'product-processes',
            name: 'ProductProcesses',
            component: () => import('@/views/system/product-processes/index.vue'),
            meta: { requiresAuth: true, title: '产品-工序配置', module: 'system' }
          },
          {
            path: 'workflow-designer',
            name: 'WorkflowDesigner',
            component: () => import('@/views/system/workflow-designer/index.vue'),
            meta: { requiresAuth: true, title: '工作流设计器', module: 'system' }
          },
          {
            path: 'smartbi-config',
            name: 'SmartBIConfig',
            component: () => import('@/views/smartbi-config/SmartBIConfigView.vue'),
            meta: { requiresAuth: true, title: 'SmartBI配置', module: 'system' }
          },
          {
            path: 'smartbi-config/data-sources',
            name: 'SmartBIDataSources',
            component: () => import('@/views/smartbi-config/DataSourceConfigView.vue'),
            meta: { requiresAuth: true, title: '数据源配置', module: 'system' }
          },
          {
            path: 'smartbi-config/chart-templates',
            name: 'SmartBIChartTemplates',
            component: () => import('@/views/smartbi-config/ChartTemplateView.vue'),
            meta: { requiresAuth: true, title: '图表模板', module: 'system' }
          },
          {
            path: 'badge-generator',
            name: 'BadgeGenerator',
            component: () => import('@/views/system/employees/BadgeGenerator.vue'),
            meta: { requiresAuth: true, title: '员工工牌生成', module: 'system' }
          }
        ]
      },

      // 数据分析中心 (调度专属)
      {
        path: 'analytics',
        name: 'Analytics',
        redirect: '/analytics/overview',
        meta: { requiresAuth: true, title: '数据分析', icon: 'DataAnalysis', module: 'analytics' },
        children: [
          {
            path: 'overview',
            name: 'AnalyticsOverview',
            component: () => import('@/views/analytics/index.vue'),
            meta: { requiresAuth: true, title: '分析概览', module: 'analytics' }
          },
          {
            path: 'trends',
            name: 'AnalyticsTrends',
            component: () => import('@/views/analytics/trends/index.vue'),
            meta: { requiresAuth: true, title: '趋势分析', module: 'analytics' }
          },
          {
            path: 'ai-reports',
            name: 'AnalyticsAIReports',
            component: () => import('@/views/analytics/ai-reports/index.vue'),
            meta: { requiresAuth: true, title: 'AI分析报告', module: 'analytics' }
          },
          {
            path: 'kpi',
            name: 'AnalyticsKPI',
            component: () => import('@/views/analytics/kpi/index.vue'),
            meta: { requiresAuth: true, title: 'KPI看板', module: 'analytics' }
          },
          {
            path: 'production-report',
            name: 'ProductionReport',
            component: () => import('@/views/analytics/production-report/index.vue'),
            meta: { requiresAuth: true, title: '车间实时生产报表', module: 'analytics' }
          },
          {
            path: 'alert-dashboard',
            name: 'AlertDashboard',
            component: () => import('@/views/analytics/AlertDashboard.vue'),
            meta: { requiresAuth: true, title: '生产异常预警', module: 'analytics' }
          },
          {
            path: 'supply-chain',
            name: 'SupplyChainOverview',
            component: () => import('@/views/analytics/SupplyChainOverview.vue'),
            meta: { requiresAuth: true, title: '进销存闭环总览', module: 'analytics' }
          }
        ]
      },

      // 行为校准管理
      {
        path: 'calibration',
        name: 'Calibration',
        redirect: '/calibration/list',
        meta: { requiresAuth: true, title: '行为校准', icon: 'Aim', module: 'system' },
        children: [
          {
            path: 'list',
            name: 'CalibrationList',
            component: () => import('@/views/calibration/CalibrationListView.vue'),
            meta: { requiresAuth: true, title: '校准列表', module: 'system' }
          },
          {
            path: ':id',
            name: 'CalibrationDetail',
            component: () => import('@/views/calibration/CalibrationDetailView.vue'),
            meta: { requiresAuth: true, title: '校准详情', module: 'system', hidden: true }
          }
        ]
      },

      // 智能调度
      {
        path: 'scheduling',
        name: 'Scheduling',
        redirect: '/scheduling/overview',
        meta: { requiresAuth: true, title: '智能调度', icon: 'Calendar', module: 'scheduling' },
        children: [
          {
            path: 'overview',
            name: 'SchedulingOverview',
            component: () => import('@/views/scheduling/index.vue'),
            meta: { requiresAuth: true, title: '调度中心', module: 'scheduling' }
          },
          {
            path: 'plans',
            name: 'SchedulingPlans',
            component: () => import('@/views/scheduling/plans/list.vue'),
            meta: { requiresAuth: true, title: '调度计划', module: 'scheduling' }
          },
          {
            path: 'plans/create',
            name: 'SchedulingPlanCreate',
            component: () => import('@/views/scheduling/plans/create.vue'),
            meta: { requiresAuth: true, title: '创建计划', module: 'scheduling', hidden: true }
          },
          {
            path: 'plans/:id',
            name: 'SchedulingPlanDetail',
            component: () => import('@/views/scheduling/plans/detail.vue'),
            meta: { requiresAuth: true, title: '计划详情', module: 'scheduling', hidden: true }
          },
          {
            path: 'realtime',
            name: 'SchedulingRealtime',
            component: () => import('@/views/scheduling/realtime/index.vue'),
            meta: { requiresAuth: true, title: '实时监控', module: 'scheduling' }
          },
          {
            path: 'workers',
            name: 'SchedulingWorkers',
            component: () => import('@/views/scheduling/workers/assignment.vue'),
            meta: { requiresAuth: true, title: '人员分配', module: 'scheduling' }
          },
          {
            path: 'alerts',
            name: 'SchedulingAlerts',
            component: () => import('@/views/scheduling/alerts/index.vue'),
            meta: { requiresAuth: true, title: '告警管理', module: 'scheduling' }
          },
          {
            path: 'settings',
            name: 'SchedulingSettings',
            component: () => import('@/views/scheduling/settings/index.vue'),
            meta: { requiresAuth: true, title: '排产设置', module: 'scheduling' }
          }
        ]
      },

      // 餐饮运营
      {
        path: 'restaurant',
        name: 'Restaurant',
        redirect: '/restaurant/requisitions',
        meta: { requiresAuth: true, title: '餐饮运营', icon: 'Bowl', module: 'restaurant' },
        children: [
          {
            path: 'requisitions',
            name: 'RestaurantRequisitions',
            component: () => import('@/views/restaurant/requisitions/list.vue'),
            meta: { requiresAuth: true, title: '领料管理', module: 'restaurant' }
          },
          {
            path: 'wastage',
            name: 'RestaurantWastage',
            component: () => import('@/views/restaurant/wastage/list.vue'),
            meta: { requiresAuth: true, title: '损耗管理', module: 'restaurant' }
          },
          {
            path: 'recipes',
            name: 'RestaurantRecipes',
            component: () => import('@/views/restaurant/recipes/list.vue'),
            meta: { requiresAuth: true, title: '配方管理', module: 'restaurant' }
          },
          {
            path: 'stocktaking',
            name: 'RestaurantStocktaking',
            component: () => import('@/views/restaurant/stocktaking/list.vue'),
            meta: { requiresAuth: true, title: '盘点管理', module: 'restaurant' }
          },
          {
            path: 'analytics',
            name: 'RestaurantAnalyticsOverview',
            component: () => import('@/views/restaurant/analytics/overview.vue'),
            meta: { requiresAuth: true, title: '运营分析', module: 'restaurant' }
          },
          {
            path: 'analytics/menu',
            name: 'RestaurantMenuBoard',
            component: () => import('@/views/restaurant/analytics/menu-board.vue'),
            meta: { requiresAuth: true, title: '菜品四象限', module: 'restaurant' }
          },
          {
            path: 'analytics/stores',
            name: 'RestaurantStoreComparison',
            component: () => import('@/views/restaurant/analytics/store-comparison.vue'),
            meta: { requiresAuth: true, title: '门店对比', module: 'restaurant' }
          },
          {
            path: 'analytics/dianping',
            name: 'RestaurantDianpingGap',
            component: () => import('@/views/restaurant/analytics/dianping-gap.vue'),
            meta: { requiresAuth: true, title: '经营与平台分析', module: 'restaurant' }
          },
          {
            // Apr 24 2026 Plan C Phase 7+: cross-module POS × food cost gross margin
            path: 'analytics/gross-margin',
            name: 'RestaurantGrossMargin',
            component: () => import('@/views/restaurant/analytics/gross-margin.vue'),
            meta: { requiresAuth: true, title: '菜品毛利分析', module: 'restaurant' }
          },
          {
            // 餐饮 Phase A-1 Task 1.5: ETL admin status page
            path: 'admin/etl-status',
            name: 'RestaurantETLStatus',
            component: () => import('@/views/restaurant/admin/etl-status.vue'),
            meta: {
              requiresAuth: true,
              title: '餐饮 ETL 状态',
              module: 'restaurant',
              roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
              hidden: false,
            },
          },
          {
            // 餐饮 Phase A-2 Task 2.2: data completeness page
            path: 'data-completeness',
            name: 'RestaurantDataCompleteness',
            component: () => import('@/views/restaurant/data-completeness.vue'),
            meta: { requiresAuth: true, title: '数据完整度', module: 'restaurant' },
          }
        ]
      },

      // Canvas 配置编辑器
      // Round 7a P0 fix: `roles` meta was missing — any authenticated user could load
      // the editor regardless of role. Restricted to canvas config admins only.
      // (Backend @RequireRole already gates the mutation endpoints, but the UI should
      //  not even render for unprivileged users to avoid "button visible then 403" UX.)
      //
      // R18 fix (2026-04-15): roles list must match backend @RequireRole on
      // ConfigController / CanvasAIController, which is
      // {"factory_super_admin", "permission_admin"}. The prior list
      // ['platform_admin', 'permission_admin'] desynced router from backend and
      // locked factory_super_admin (the highest FACTORY role) out of the UI even
      // though the backend would have accepted their requests. `platform_admin`
      // is kept for platform-level operators; `factory_super_admin` restored.
      {
        path: 'canvas-editor',
        name: 'CanvasEditor',
        component: () => import('@/views/platform/canvas-editor/index.vue'),
        meta: {
          title: 'Canvas 配置编辑器',
          icon: 'Setting',
          requiresAuth: true,
          showInMenu: true,
          roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
        },
      },
      // Sprint 3 Track-I (C-APPROVAL-EDITOR-1) — graph-native 审批工作流编辑器.
      // Backend: ApprovalWorkflowController + ApprovalWorkflowExecutor (4 modes).
      //
      // Phase 1 Canvas-Workflow (2026-05-18): UI 入口迁移到 Canvas Editor 的
      // "审批工作流" Tab. 此独立路由仍保留 (供旧书签访问 / 全屏调试), 但
      // showInMenu:false 不再出现在侧边菜单 — 用户通过 /platform/canvas-editor 进入.
      // Sprint 4+ 完全移除独立路由前请确认: 无对此 path 的硬编码引用.
      {
        path: 'approval-workflow-editor',
        name: 'ApprovalWorkflowEditor',
        component: () => import('@/views/platform/approval-workflow-editor/index.vue'),
        meta: {
          title: '审批工作流编辑器 (已迁移到 Canvas)',
          icon: 'Connection',
          requiresAuth: true,
          showInMenu: false,
          roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
        },
      },
      // Sprint 3 Track-J C-PRT-EDITOR-1 — print template visual designer.
      // Schema-driven PDF print, FormTemplate entity reused with PRINT_* prefix.
      // RBAC gates editor by role; backend FormTemplateController PUT requires system:read_write.
      {
        path: 'print-template-editor',
        name: 'PrintTemplateEditor',
        component: () => import('@/views/platform/print-template-editor/index.vue'),
        meta: {
          title: '打印模板设计器',
          icon: 'Printer',
          requiresAuth: true,
          showInMenu: true,
          roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
        },
      },
      // 动态模块页 (Canvas配置系统)
      {
        path: 'modules/:moduleCode',
        name: 'DynamicModule',
        component: () => import('@/views/modules/DynamicModulePage.vue'),
        meta: {
          title: '动态模块',
          requiresAuth: true,
        },
      },

      // 数据织网 C spec §6.3 — cell-level lineage detail page (Day 26).
      // Canonical URL per spec NS-7 — /audit/cell?type=&id=&field=
      // (encodeURIComponent-safe). Reached from any TrustIndicator's
      // "查看来源" button. Admin-only, hidden from sidebar.
      {
        path: 'audit/cell',
        name: 'CellAudit',
        component: () => import('@/views/system/data-fabric/cell-audit.vue'),
        meta: {
          title: '字段血统审计',
          requiresAuth: true,
          hidden: true,
          roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
        },
      },

      // 生产分析 & 人效分析 (独立模块)
      ...productionAnalyticsRoutes,

      // SmartBI 智能BI (导入自模块)
      ...smartBIRoutes
    ]
  },

  // SmartBI 快捷入口重定向
  ...smartBIRedirects,

  // 404 兜底
  {
    path: '/:pathMatch(.*)*',
    redirect: '/404'
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes: [...baseRoutes, ...businessRoutes]
});

// 注意：路由守卫需要在 pinia 初始化后设置，因此移到 main.ts 中调用
// setupRouterGuards(router);  // 已移至 main.ts

export default router;

// 导出 setupRouterGuards 供 main.ts 使用
export { setupRouterGuards };
