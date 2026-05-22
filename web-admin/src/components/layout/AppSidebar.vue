<script setup lang="ts">
import { computed, ref, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore, ModuleName } from '@/store/modules/permission';
import { get } from '@/api/request';
import {
  House, Operation, Box, Checked, ShoppingCart, Goods,
  User, Monitor, Money, Setting, DataAnalysis, Calendar,
  TrendCharts, Sell, Upload, ChatDotRound, Aim, Odometer, Tickets,
  Histogram, KnifeFork
} from '@element-plus/icons-vue';

const router = useRouter();
const route = useRoute();
const appStore = useAppStore();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

// 当前用户角色
const roleCode = computed(() => authStore.currentRole);

// R19: Canvas module enable/disable — hide disabled modules from sidebar
const disabledModuleCodes = ref<string[]>([]);
const MODULE_CODE_TO_SIDEBAR: Record<string, string> = {
  sales_order: 'sales', purchase_order: 'procurement', production_plan: 'production',
  quality_inspection: 'quality', hr_employee: 'hr', equipment: 'equipment',
  finance_ar: 'finance', finance_ap: 'finance', warehouse: 'warehouse',
  scheduling: 'scheduling', restaurant: 'restaurant',
};
onMounted(async () => {
  if (!authStore.factoryId) return;
  try {
    const res = await get(`/${authStore.factoryId}/config/disabled-modules`);
    if (res.success && Array.isArray(res.data)) disabledModuleCodes.value = res.data;
  } catch { /* config not set up for this factory */ }
});
const disabledSidebarModules = computed(() => {
  const set = new Set<string>();
  for (const code of disabledModuleCodes.value) {
    const sidebar = MODULE_CODE_TO_SIDEBAR[code];
    if (sidebar) set.add(sidebar);
  }
  return set;
});

// 图标映射
const iconMap: Record<string, any> = {
  House, Operation, Box, Checked, ShoppingCart, Goods,
  User, Monitor, Money, Setting, DataAnalysis, Calendar,
  TrendCharts, Sell, Upload, ChatDotRound, Aim, Odometer, Tickets,
  Histogram, KnifeFork
};

// 菜单配置
interface MenuItem {
  path: string;
  title: string;
  icon: string;
  module: ModuleName;
  roles?: string[];
  hideForFactoryTypes?: string[];
  children?: MenuItem[];
  groupLabel?: string;
}

// 财务主管专用菜单 - 简化版
const financeManagerMenu: MenuItem[] = [
  { path: '/smart-bi/dashboard', title: '经营驾驶舱', icon: 'Odometer', module: 'analytics' },
  { path: '/smart-bi/financial-dashboard', title: '财务 PBI 看板', icon: 'TrendCharts', module: 'analytics' },
  { path: '/smart-bi/finance', title: '财务分析', icon: 'Money', module: 'analytics' },
  { path: '/smart-bi/sales', title: '销售分析', icon: 'TrendCharts', module: 'sales' },
  { path: '/smart-bi/query', title: 'AI问答', icon: 'ChatDotRound', module: 'analytics' },
  { path: '/smart-bi/query-templates', title: '查询模板管理', icon: 'Tickets', module: 'analytics' },
  { path: '/smart-bi/analysis', title: '智能数据分析', icon: 'DataAnalysis', module: 'analytics' },
  // Bug #40: finance_manager 需审核开票申请, 加 ERP 财务操作入口
  { path: '/finance/invoices?status=REQUESTED', title: '开票审核', icon: 'Tickets', module: 'finance' },
  { path: '/finance/payments', title: '收款管理', icon: 'Money', module: 'finance' },
  // Smoke v2 Bug #2: 财务审核采购单 — finance_manager 是该列表的主审核人
  { path: '/procurement/finance-review', title: '财务待审采购单', icon: 'ShoppingCart', module: 'finance' },
  // Sprint4-H F-AR-1: 财务审核销售单 — finance_manager 复核成本/利润/BOM 标准
  { path: '/sales/finance-review', title: '财务待审销售单', icon: 'Goods', module: 'finance' }
];

const menuConfig: MenuItem[] = [
  { path: '/dashboard', title: '首页', icon: 'House', module: 'dashboard' },
  // 🏪 Sprint 8 P1 (2026-05-20) — 销售老板 Workdesk V1 (F006 真场景 demo).
  // module 借 'sales' 走现有权限 (workdesk 不是 ModuleName).
  { path: '/workdesk/sales-owner', title: '🏪 销售老板工作台', icon: 'Sell', module: 'sales' },
  // 💼 Sprint 8 P2 (2026-05-20) — 财务主管 Workdesk (monthly close + 三表 + 应收账龄).
  // module 借 'finance' 走现有权限 (workdesk 不是 ModuleName).
  { path: '/workdesk/finance-manager', title: '💼 财务主管工作台', icon: 'Money', module: 'finance' },
  // 🚨 Sprint 8 P3 (2026-05-20) — 质量主管 Workdesk (食品安全召回闭环 — Boss 演示杀手锏).
  // food-safety-recall Skill 串 8 Tool. module 借 'quality' 走现有权限.
  { path: '/workdesk/quality-manager', title: '🚨 质量主管工作台', icon: 'Warning', module: 'quality' },
  // 🏭 Sprint 8 P4a (2026-05-20) — 仓管员 Workdesk (R1 max + 一键扫码 + 临期建议).
  // 客户原话 (F006 张权): "告诉他要收多少就行". module 借 'inventory' 走现有权限.
  { path: '/workdesk/warehouse-keeper', title: '🏭 仓管员工作台', icon: 'Box', module: 'inventory' },
  // 🛒 Sprint 8 P4b (2026-05-20) — 采购员 Workdesk (5 品类预警 + 一键请购).
  // 采购员小赵场景 (F006): "下周采购什么? 系统直接告诉我". module 借 'inventory'.
  { path: '/workdesk/purchaser', title: '🛒 采购员工作台', icon: 'ShoppingCart', module: 'inventory' },
  // 🔬 Sprint 8 P4c (2026-05-20) — 质量主管 Workdesk (4 项综合 audit + 一键放行/退货).
  // 质量主管李工程师场景 (F006): "这批卤猪蹄能放行吗?". module 借 'quality' 走现有权限.
  { path: '/workdesk/quality-chief', title: '🔬 质量主管工作台', icon: 'Aim', module: 'quality' },
  {
    // P1-5: restaurants 默认不见 "生产管理" (BOM/批次是 manufacturing 语言,
    // 餐饮用配方/备餐在 /restaurant/recipes)
    // UX P2-5: 合并"研发管理" 1-item 组, 减少顶级菜单
    path: '/production', title: '生产管理', icon: 'Operation', module: 'production',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/production/batches', title: '生产批次', icon: '', module: 'production' },
      { path: '/production/plans', title: '生产计划', icon: '', module: 'production' },
      { path: '/production/conversions', title: '转换率配置', icon: '', module: 'production' },
      { path: '/production/bom', title: 'BOM成本管理', icon: '', module: 'production' },
      { path: '/production/approval', title: '报工审批', icon: '', module: 'production' },
      { path: '/production/bom-achievement', title: 'BOM达成率分析', icon: '', module: 'production' },
      { path: '/production/process-io', title: '工序投入产出对比', icon: '', module: 'production' },
      { path: '/production/material-requisitions', title: '物料需求单', icon: '', module: 'production' },
      { path: '/rd/samples', title: '研发样品', icon: '', module: 'production' }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "仓储管理" (食材库存在 /restaurant/stocktaking)
    // UX P2-5: 合并"调拨管理" 1-item 组到这里,减少顶级菜单数
    path: '/warehouse', title: '仓储管理', icon: 'Box', module: 'warehouse',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/warehouse/material-types', title: '原料类型字典 (新建原料)', icon: '', module: 'warehouse' },
      { path: '/warehouse/materials', title: '原料入库登记 (具体批次)', icon: '', module: 'warehouse' },
      { path: '/warehouse/shipments', title: '出货管理', icon: '', module: 'warehouse' },
      { path: '/warehouse/inventory', title: '盘点管理', icon: '', module: 'warehouse' },
      { path: '/inventory/by-warehouse', title: '分仓库存查询', icon: '', module: 'warehouse' },
      { path: '/warehouse/material-price-trend', title: '物料均价趋势', icon: '', module: 'warehouse' },
      { path: '/transfer/list', title: '调拨单', icon: '', module: 'warehouse' }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "质量管理" (食品安全走 食检,不是 ISO 质检)
    path: '/quality', title: '质量管理', icon: 'Checked', module: 'quality',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/quality/inspections', title: '质检记录', icon: '', module: 'quality' },
      { path: '/quality/disposals', title: '废弃处理', icon: '', module: 'quality' },
      { path: '/quality/standards', title: '质检标准', icon: '', module: 'quality' },
      // Sprint4-H Q-PROCESS-1: 工序质检不良闭环
      { path: '/quality/defects', title: '工序质检不良', icon: '', module: 'quality' },
      // Sprint4-H Q-RETURN-1: 质检退回单 (上游退回, 不含客户销售退货)
      { path: '/quality/returns', title: '质检退回单', icon: '', module: 'quality' }
    ]
  },
  {
    // P1-5 fix: restaurants 默认不见 "采购管理" (进货走 /restaurant/requisitions)
    // Canvas 管理员可细粒度开启 purchase_order 码解锁.
    path: '/procurement', title: '采购管理', icon: 'ShoppingCart', module: 'procurement',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/procurement/orders', title: '采购订单', icon: '', module: 'procurement' },
      { path: '/procurement/receives', title: '采购入库', icon: '', module: 'procurement' },
      { path: '/procurement/finance-review', title: '财务待审采购单', icon: '', module: 'finance' },
      { path: '/procurement/suppliers', title: '供应商管理', icon: '', module: 'procurement' },
      { path: '/procurement/price-lists', title: '价格表管理', icon: '', module: 'procurement' }
    ]
  },
  {
    path: '/sales', title: '销售管理', icon: 'Goods', module: 'sales',
    children: [
      { path: '/sales/orders', title: '销售订单', icon: '', module: 'sales' },
      // Apr 24 UX: manufacturing-only concepts (批次号/生产数量/库位 / 物流发货).
      // Restaurants don't carry finished-goods batch inventory or ship physical
      // product, so hide from restaurant sidebar. Also /sales/shipments returns
      // 400 "数据处理失败" for F002 (no data) — hiding removes the bad toast.
      { path: '/sales/finished-goods', title: '成品库存', icon: '', module: 'sales',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/sales/customers', title: '客户管理', icon: '', module: 'sales' },
      { path: '/sales/shipments', title: '出货记录', icon: '', module: 'sales',
        hideForFactoryTypes: ['RESTAURANT'] },
      // T-RTA fix (audit B2 BLOCKER 2026-05-13): /sales/returns route was added by
      // PR #549 but NEVER surfaced in sidebar — customer service / finance roles
      // had no menu entry, only "申请退货" button on individual sales order detail.
      // Now discoverable for 历史退货 review + status-tracking workflows.
      { path: '/sales/returns', title: '销售退货', icon: '', module: 'sales' },
      { path: '/sales/vehicles', title: '车辆字典', icon: '', module: 'sales',
        hideForFactoryTypes: ['RESTAURANT'] },
      // Sprint4-H F-AR-1: 销售单财务审核 (镜像 /procurement/finance-review).
      // module:'finance' 让 finance_manager 在 /sales 组下也可见. 销售员可看但
      // 后端 @RequirePermission("finance:read_write") 限制操作权.
      { path: '/sales/finance-review', title: '财务待审销售单', icon: '', module: 'finance' }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "人事管理" (小连锁常无 HR 系统,Canvas 可开启 hr_employee)
    path: '/hr', title: '人事管理', icon: 'User', module: 'hr',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/hr/employees', title: '员工管理', icon: '', module: 'hr' },
      { path: '/hr/attendance', title: '考勤管理', icon: '', module: 'hr' },
      { path: '/hr/whitelist', title: '白名单管理', icon: '', module: 'hr' },
      { path: '/hr/departments', title: '部门管理', icon: '', module: 'hr' },
      { path: '/hr/work-types', title: '工种字典', icon: '', module: 'hr' }
    ]
  },
  // UX P2-5 merged into 仓储管理: /transfer 原独立顶级组 (1 项), 合并节省 1 顶级项
  {
    // P1-5: restaurants 默认不见 "设备管理" (manufacturing 专属)
    path: '/equipment', title: '设备管理', icon: 'Monitor', module: 'equipment',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/equipment/list', title: '设备列表', icon: '', module: 'equipment' },
      { path: '/equipment/maintenance', title: '维护记录', icon: '', module: 'equipment' },
      { path: '/equipment/alerts', title: '告警管理', icon: '', module: 'equipment' }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "财务管理" (SmartBI 有 /smart-bi/finance 简化版,
    // manufacturing 的 ar-ap/invoices/sku-margin 对餐饮无意义)
    path: '/finance', title: '财务管理', icon: 'Money', module: 'finance',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/finance/costs', title: '财务概览', icon: '', module: 'finance' },
      { path: '/finance/reports', title: '财务报表', icon: '', module: 'finance' },
      { path: '/finance/ar-ap', title: '应收应付', icon: '', module: 'finance' },
      { path: '/finance/invoices', title: '开票管理', icon: '', module: 'finance' },
      { path: '/finance/payments', title: '收款管理', icon: '', module: 'finance' },
      { path: '/finance/adjustments', title: '调整审批', icon: '', module: 'finance' },
      { path: '/finance/sku-margin', title: 'SKU毛利率分析', icon: '', module: 'finance' }
    ]
  },
  // UX P2-5 merged into 生产管理: /rd 原独立顶级组 (1 项), 研发样品并入生产
  {
    path: '/system', title: '系统管理', icon: 'Setting', module: 'system',
    children: [
      { path: '/system/users', title: '用户管理', icon: '', module: 'system' },
      { path: '/system/roles', title: '角色管理', icon: '', module: 'system' },
      { path: '/system/logs', title: '操作日志', icon: '', module: 'system' },
      { path: '/system/settings', title: '系统设置', icon: '', module: 'system' },
      { path: '/system/ai-intents', title: 'AI意图配置', icon: '', module: 'system' },
      { path: '/system/skill-tools', title: 'Skill/Tool治理', icon: '', module: 'system' },
      { path: '/system/llm-usage', title: 'LLM 用量监控', icon: '', module: 'system' },
      { path: '/system/encoding-rules', title: '编码规则字典', icon: '', module: 'system' },
      { path: '/system/approval-chains', title: '审批链配置', icon: '', module: 'system' },
      { path: '/system/ai-quota', title: 'AI 配额规则', icon: '', module: 'system' },
      { path: '/system/products', title: '成品 / SKU (本厂生产)', icon: '', module: 'system' },
      { path: '/system/work-processes', title: '工序管理', icon: '', module: 'system',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/system/product-processes', title: '产品-工序配置', icon: '', module: 'system',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/system/workflow-designer', title: '工作流设计器', icon: '', module: 'system' },
      { path: '/system/features', title: '功能模块配置', icon: '', module: 'system' },
      // Apr 18 2026 bug #48: Canvas 编辑器 router 限制 roles, sidebar 跟上不让 dispatcher/
      // 其他 system:read 角色看到菜单 (否则点进去 /403 体验差)
      { path: '/canvas-editor', title: 'Canvas 配置编辑器', icon: '', module: 'system', roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      { path: '/system/pos', title: 'POS集成', icon: '', module: 'system' },
      { path: '/system/smartbi-config', title: 'SmartBI配置', icon: '', module: 'system' },
      { path: '/system/badge-generator', title: '员工工牌生成', icon: '', module: 'system',
        hideForFactoryTypes: ['RESTAURANT'] },
      // UX P2-5: 行为校准 (1 项) 合并入系统管理下, 不单做顶级组
      { path: '/calibration/list', title: '行为校准', icon: '', module: 'system',
        hideForFactoryTypes: ['RESTAURANT'] },
      // 餐饮 Phase A A-3 Task 3.5: data quality queue admin page
      { path: '/system/data-quality-queue', title: '数据质量队列', icon: '', module: 'system',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] }
    ]
  },
  {
    // UX Round 4 改名: "数据分析" → "经营报表" 与 "智能分析" 消歧 (固定报表 vs AI 探索)
    path: '/analytics', title: '经营报表', icon: 'Histogram', module: 'analytics',
    children: [
      { path: '/analytics/overview', title: '分析概览', icon: '', module: 'analytics' },
      { path: '/analytics/trends', title: '趋势分析', icon: '', module: 'analytics' },
      { path: '/analytics/ai-reports', title: 'AI分析报告', icon: '', module: 'analytics' },
      { path: '/analytics/kpi', title: 'KPI看板', icon: '', module: 'analytics' },
      { path: '/analytics/production-report', title: '车间实时生产报表', icon: '', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/analytics/alert-dashboard', title: '异常预警', icon: '', module: 'analytics' },
      { path: '/analytics/supply-chain', title: '进销存闭环总览', icon: '', module: 'analytics' },
      // Phase 1 Track B.3 (2026-05-22): 指标中心 UI 入口 — surfaces Phase 1 backend (PR #155)
      { path: '/indicator-center', title: '指标中心', icon: 'Histogram', module: 'analytics' }
    ]
  },
  {
    path: '/scheduling', title: '智能调度', icon: 'Calendar', module: 'scheduling',
    children: [
      { path: '/scheduling/overview', title: '调度中心', icon: '', module: 'scheduling' },
      { path: '/scheduling/plans', title: '调度计划', icon: '', module: 'scheduling' },
      { path: '/scheduling/realtime', title: '实时监控', icon: '', module: 'scheduling' },
      { path: '/scheduling/workers', title: '人员分配', icon: '', module: 'scheduling' },
      { path: '/scheduling/alerts', title: '告警管理', icon: '', module: 'scheduling' },
      { path: '/scheduling/settings', title: '排产设置', icon: '', module: 'scheduling' }
    ]
  },
  {
    path: '/restaurant', title: '餐饮运营', icon: 'KnifeFork', module: 'restaurant',
    // Restaurant-only group: hide for pure FACTORY tenants (manufacturing)
    hideForFactoryTypes: ['FACTORY'],
    children: [
      { path: '/restaurant/analytics', title: '运营总览', icon: '', module: 'restaurant', groupLabel: '运营分析' },
      { path: '/restaurant/analytics/menu', title: '菜品四象限', icon: '', module: 'restaurant' },
      { path: '/restaurant/analytics/stores', title: '门店对比', icon: '', module: 'restaurant' },
      { path: '/restaurant/analytics/dianping', title: '经营与平台分析', icon: '', module: 'restaurant' },
      { path: '/restaurant/analytics/gross-margin', title: '菜品毛利分析', icon: '', module: 'restaurant' },
      { path: '/restaurant/requisitions', title: '领料管理', icon: '', module: 'restaurant', groupLabel: '日常管理' },
      { path: '/restaurant/wastage', title: '损耗管理', icon: '', module: 'restaurant' },
      { path: '/restaurant/recipes', title: '配方管理', icon: '', module: 'restaurant' },
      { path: '/restaurant/stocktaking', title: '盘点管理', icon: '', module: 'restaurant' },
      // 餐饮 Phase A-1 Task 1.5: ETL admin page (admin-only)
      { path: '/restaurant/admin/etl-status', title: 'ETL 状态', icon: '', module: 'restaurant',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      // 餐饮 Phase A-2 Task 2.2: data completeness page
      { path: '/restaurant/data-completeness', title: '数据完整度', icon: '', module: 'restaurant' }
    ]
  },
  // UX P2-5 merged: /calibration 并入 系统管理, /production-analytics 并入 智能BI
  {
    // UX Round 4 改名: "智能BI" → "智能分析" (AI 问答 / Excel 探索 / 追问)
    path: '/smart-bi', title: '智能分析', icon: 'TrendCharts', module: 'analytics',
    children: [
      // -- 分析入口 --
      { path: '/smart-bi/dashboard', title: '经营驾驶舱', icon: 'Monitor', module: 'analytics', groupLabel: '分析入口' },
      { path: '/smart-bi/financial-dashboard', title: '财务 PBI 看板', icon: 'TrendCharts', module: 'analytics' },
      { path: '/smart-bi/analysis', title: '智能数据分析', icon: 'DataAnalysis', module: 'analytics' },
      { path: '/smart-bi/query', title: 'AI问答', icon: 'ChatDotRound', module: 'analytics' },
      // -- 预定义报表 --
      { path: '/smart-bi/sales', title: '销售数据分析', icon: 'Sell', module: 'analytics', groupLabel: '预定义报表' },
      { path: '/smart-bi/finance', title: '财务数据分析', icon: 'Money', module: 'analytics' },
      // QHJ 收入管理报表 (Phase I) — restaurant tenants only (青花椒 / R_*_REAL chains).
      { path: '/smart-bi/revenue-report', title: '收入管理报表', icon: 'Money', module: 'analytics',
        hideForFactoryTypes: ['FACTORY'] },
      // -- 数据管理 --
      { path: '/smart-bi/upload', title: 'Excel上传', icon: 'Upload', module: 'analytics', groupLabel: '数据管理' },
      { path: '/smart-bi/query-templates', title: '查询模板', icon: 'Tickets', module: 'analytics' },
      { path: '/smart-bi/data-completeness', title: '数据完整度', icon: 'DataAnalysis', module: 'analytics' },
      { path: '/smart-bi/food-kb-feedback', title: '知识库反馈', icon: 'ChatDotRound', module: 'analytics', groupLabel: '质量管理' },
      { path: '/smart-bi/fallback-log', title: 'AI 追问日志', icon: 'DataLine', module: 'analytics' },
      { path: '/smart-bi/calibration', title: '行为校准监控', icon: 'Aim', module: 'analytics', roles: ['platform_admin'] },
      // UX P2-5: 生产分析 (2 项) 合并入智能BI, 不单做顶级组
      { path: '/production-analytics/production', title: '生产数据分析', icon: 'Histogram', module: 'analytics', groupLabel: '生产分析',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/production-analytics/efficiency', title: '人效分析', icon: 'User', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] }
    ]
  }
];

// 检查菜单项是否可见（基于角色限制 + 工厂类型限制）
function canSeeMenuItem(item: MenuItem): boolean {
  if (item.hideForFactoryTypes?.includes(authStore.factoryType)) {
    return false;
  }
  // R19: Canvas module enable/disable — hide disabled modules
  if (disabledSidebarModules.value.has(item.module)) {
    return false;
  }
  if (!item.roles || item.roles.length === 0) {
    return permissionStore.canAccess(item.module);
  }
  return item.roles.includes(permissionStore.currentRole) && permissionStore.canAccess(item.module);
}

// 过滤有权限的菜单
const filteredMenu = computed(() => {
  // 财务主管使用简化菜单
  if (roleCode.value === 'finance_manager') {
    return financeManagerMenu;
  }

  return menuConfig
    .filter(item => canSeeMenuItem(item))
    .map(item => {
      if (!item.children) return item;
      // 过滤子菜单中有角色限制的项
      const filteredChildren = item.children.filter(child => canSeeMenuItem(child));
      return { ...item, children: filteredChildren };
    })
    .filter(item => !item.children || item.children.length > 0);  // 移除没有可见子菜单的父菜单
});

// Apr 24 2026 Plan C: restaurant-specific sidebar title overrides for
// manufacturing-origin pages that stay shared. "产品" makes no sense in
// restaurant context — dishes are the product. One-line map kept close to
// filteredMenu so it's obvious how to add more overrides.
const RESTAURANT_TITLE_OVERRIDES: Record<string, string> = {
  '/system/products': '菜品信息管理',
};

function titleForItem(item: MenuItem): string {
  if (authStore.factoryType === 'RESTAURANT' && RESTAURANT_TITLE_OVERRIDES[item.path]) {
    return RESTAURANT_TITLE_OVERRIDES[item.path];
  }
  return item.title;
}

// 当前激活的菜单
const activeMenu = computed(() => route.path);

// 默认展开的菜单
const defaultOpeneds = computed(() => {
  const path = route.path;
  const parent = menuConfig.find(item =>
    item.children?.some(child => path.startsWith(child.path))
  );
  return parent ? [parent.path] : [];
});

function handleSelect(path: string) {
  router.push(path);
  // 移动端点击菜单项后自动关闭抽屉
  if (appStore.isMobile) {
    appStore.closeMobileMenu();
  }
}
</script>

<template>
  <!-- 移动端遮罩层 -->
  <div
    v-if="appStore.isMobile && appStore.mobileMenuOpen"
    class="sidebar-overlay"
    @click="appStore.closeMobileMenu()"
  />

  <aside
    class="app-sidebar"
    :class="{
      'is-collapsed': appStore.sidebarCollapsed && !appStore.isMobile,
      'mobile-open': appStore.isMobile && appStore.mobileMenuOpen
    }"
  >
    <!-- Logo -->
    <div class="sidebar-logo">
      <img src="/logo.svg" alt="Logo" class="logo-icon" />
      <span v-if="!appStore.sidebarCollapsed || appStore.isMobile" class="logo-text">白垩纪AI Agent</span>
    </div>

    <!-- 菜单 -->
    <el-scrollbar class="sidebar-menu-wrap" @wheel.stop>
      <el-menu
        :default-active="activeMenu"
        :default-openeds="defaultOpeneds"
        :collapse="appStore.sidebarCollapsed && !appStore.isMobile"
        unique-opened
        background-color="transparent"
        text-color="#ffffffa6"
        active-text-color="#ffffff"
        @select="handleSelect"
      >
        <template v-for="item in filteredMenu" :key="item.path">
          <!-- 有子菜单 -->
          <el-sub-menu v-if="item.children?.length" :index="item.path">
            <template #title>
              <el-icon><component :is="iconMap[item.icon]" /></el-icon>
              <span>{{ titleForItem(item) }}</span>
            </template>
            <template v-for="child in item.children" :key="child.path">
              <div v-if="child.groupLabel && !appStore.sidebarCollapsed" class="menu-group-label">
                {{ child.groupLabel }}
              </div>
              <el-menu-item :index="child.path">
                {{ titleForItem(child) }}
              </el-menu-item>
            </template>
          </el-sub-menu>

          <!-- 无子菜单 -->
          <el-menu-item v-else :index="item.path">
            <el-icon><component :is="iconMap[item.icon]" /></el-icon>
            <template #title>{{ titleForItem(item) }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-scrollbar>
  </aside>
</template>

<style lang="scss" scoped>
.app-sidebar {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: 220px;
  background: linear-gradient(180deg, #0C1929 0%, #132238 60%, #0F1D2E 100%);
  transition: width 0.3s;
  z-index: 100;
  display: flex;
  flex-direction: column;

  &.is-collapsed {
    width: 64px;
  }
}

.sidebar-logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  position: relative;

  // Gradient glow line instead of solid border
  &::after {
    content: '';
    position: absolute;
    bottom: 0;
    left: 16px;
    right: 16px;
    height: 1px;
    background: linear-gradient(90deg, transparent, rgba(43, 126, 193, 0.4), transparent);
  }

  .logo-icon {
    width: 32px;
    height: 32px;
  }

  .logo-text {
    margin-left: 12px;
    color: #fff;
    font-size: 16px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    letter-spacing: 0.5px;
  }
}

.sidebar-menu-wrap {
  flex: 1;
  padding: 8px;
}

:deep(.el-menu) {
  border-right: none;

  .el-menu-item,
  .el-sub-menu__title {
    margin: 2px 0;
    border-radius: 8px;
    transition: all 0.2s ease;

    &:hover {
      background-color: rgba(255, 255, 255, 0.06) !important;
    }
  }

  .el-menu-item.is-active {
    background-color: rgba(27, 101, 168, 0.25) !important;
    position: relative;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 25%;
      height: 50%;
      width: 3px;
      border-radius: 0 3px 3px 0;
      background: #2B7EC1;
    }
  }

  // Nested sub-menu items
  .el-sub-menu .el-menu-item {
    margin: 1px 4px;
    border-radius: 6px;
    padding-left: 48px !important;
  }
}

.menu-group-label {
  padding: 8px 12px 4px 36px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  user-select: none;

  &:not(:first-child) {
    margin-top: 4px;
    border-top: 1px solid rgba(255, 255, 255, 0.04);
    padding-top: 10px;
  }
}

// 移动端遮罩
.sidebar-overlay {
  display: none;
}

@media (max-width: 768px) {
  .app-sidebar {
    transform: translateX(-100%);
    z-index: 1001;
    width: 260px;

    &.mobile-open {
      transform: translateX(0);
    }
  }

  .sidebar-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    z-index: 1000;
  }
}
</style>
