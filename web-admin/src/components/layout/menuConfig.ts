import type { ModuleName } from '@/store/modules/permission';
import {
  RESTAURANT_ALL_ROLES,
  RESTAURANT_DATA_STEWARD_ROLES,
  RESTAURANT_DECISION_ROLES,
} from '@/views/restaurant/restaurantRoleExperience';

// 菜单配置
export interface MenuItem {
  path: string;
  title: string;
  icon: string;
  module: ModuleName;
  roles?: string[];
  /** 仅 factoryType=RESTAURANT 时叠加的角色白名单，不影响工厂同一路由。 */
  restaurantRoles?: string[];
  hideForFactoryTypes?: string[];
  children?: MenuItem[];
  groupLabel?: string;
}

const PLATFORM_ADMIN_ONLY = ['platform_admin'];
const RESTAURANT_ADMIN_ROLES = ['factory_super_admin', 'platform_admin', 'permission_admin'];
const RESTAURANT_ANALYTICS_ALL = [...RESTAURANT_ADMIN_ROLES, ...RESTAURANT_ALL_ROLES];
const RESTAURANT_ANALYTICS_DECISION = [...RESTAURANT_ADMIN_ROLES, ...RESTAURANT_DECISION_ROLES];
const RESTAURANT_ANALYTICS_STEWARDS = [...RESTAURANT_ADMIN_ROLES, ...RESTAURANT_DATA_STEWARD_ROLES];

// 财务主管专用菜单 - 简化版
// WS4: 财务看板/财务分析/销售分析 合并为单一「经营分析」hub (财务/销售/趋势/KPI tab)。
export const financeManagerMenu: MenuItem[] = [
  {
    path: '/workflow', title: '个人 OA', icon: 'Checked', module: 'dashboard',
    children: [
      { path: '/workflow/pending', title: '待我审批', icon: '', module: 'dashboard' },
      { path: '/workflow/my-created', title: '我发起的', icon: '', module: 'dashboard' },
      { path: '/workflow/acted', title: '已处理', icon: '', module: 'dashboard' },
      { path: '/workflow/copied', title: '抄送我的', icon: '', module: 'dashboard' },
    ],
  },
  { path: '/smart-bi/dashboard', title: '经营驾驶舱', icon: 'Odometer', module: 'analytics' },
  { path: '/smart-bi/analysis-hub', title: '经营分析', icon: 'TrendCharts', module: 'analytics' },
  { path: '/smart-bi/query', title: 'AI问答', icon: 'ChatDotRound', module: 'analytics' },
  { path: '/smart-bi/query-templates', title: '查询模板管理', icon: 'Tickets', module: 'analytics' },
  { path: '/smart-bi/analysis', title: '智能数据分析', icon: 'DataAnalysis', module: 'analytics' },
  { path: '/restaurant/supplier-reconciliation', title: '供应商月对账', icon: 'Money', module: 'finance' },
  { path: '/restaurant/cost-attribution', title: '成本归因', icon: 'Histogram', module: 'finance' },
  // Bug #40: finance_manager 需审核开票申请, 加 ERP 财务操作入口
  { path: '/finance/invoices?status=REQUESTED', title: '开票审核', icon: 'Tickets', module: 'finance' },
  { path: '/finance/payments', title: '收款管理', icon: 'Money', module: 'finance' },
  // Smoke v2 Bug #2: 财务审核采购单 — finance_manager 是该列表的主审核人
  { path: '/procurement/finance-review', title: '财务待审采购单', icon: 'ShoppingCart', module: 'finance' },
  // Sprint4-H F-AR-1: 财务审核销售单 — finance_manager 复核成本/利润/BOM 标准
  { path: '/sales/finance-review', title: '财务待审销售单', icon: 'Goods', module: 'finance' }
];

const rawMenuConfig: MenuItem[] = [
  { path: '/dashboard', title: '首页', icon: 'House', module: 'dashboard', hideForFactoryTypes: ['LOGISTICS'] },
  // 🔴 2026-08-07: 这一项此前**只有路由没有菜单入口** —— `router/index.ts` 里
  // `dashboard/ai-value` 声明齐全(title/icon/module 都有)，但 menuConfig 里零命中，
  // 于是用户**点不到它**。小蓝店长第 ③ 块「AI 价值汇总」的页面一直在，却够不着。
  //
  // 判据(本轮反复用到的那条): **「代码在那儿」不等于「用户到得了」**。
  // 判可达性要从菜单/路由两侧一起看 —— 只看路由会以为它通了。
  { path: '/dashboard/ai-value', title: 'AI 工作台', icon: 'MagicStick', module: 'dashboard' },
  {
    path: '/workdesk',
    title: '工作台',
    icon: 'Odometer',
    module: 'dashboard',
    children: [
      { path: '/workdesk/sales-owner', title: '销售老板工作台', icon: '', module: 'sales', groupLabel: '经营管理' },
      { path: '/workdesk/finance-manager', title: '财务主管工作台', icon: '', module: 'finance' },
      { path: '/workdesk/quality-manager', title: '质量主管工作台', icon: '', module: 'quality' },
      { path: '/workdesk/production-manager', title: '生产经理工作台', icon: '', module: 'production' },
      { path: '/workdesk/warehouse-keeper', title: '仓管员工作台', icon: '', module: 'warehouse', groupLabel: '一线执行' },
      { path: '/workdesk/purchaser', title: '采购员工作台', icon: '', module: 'procurement' },
      { path: '/workdesk/quality-chief', title: '质检主管工作台', icon: '', module: 'quality' },
    ],
  },
  {
    // Personal OA is available to every authenticated role through dashboard access.
    // Backend workflow role/user/factory rules decide which tasks are visible/actionable.
    path: '/workflow', title: '个人 OA', icon: 'Checked', module: 'dashboard',
    children: [
      { path: '/workflow/pending', title: '待我审批', icon: '', module: 'dashboard' },
      { path: '/workflow/my-created', title: '我发起的', icon: '', module: 'dashboard' },
      { path: '/workflow/acted', title: '已处理', icon: '', module: 'dashboard' },
      { path: '/workflow/copied', title: '抄送我的', icon: '', module: 'dashboard' },
    ],
  },
  {
    // P1-5: restaurants 默认不见 "生产管理" (BOM/批次是 manufacturing 语言,
    // 餐饮用配方/备餐在 /restaurant/recipes)
    // UX P2-5: 合并"研发管理" 1-item 组, 减少顶级菜单
    path: '/production', title: '生产管理', icon: 'Operation', module: 'production',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/production/plans', title: '生产计划', icon: '', module: 'production', groupLabel: '生产过程' },
      { path: '/production/batches', title: '生产批次', icon: '', module: 'production' },
      { path: '/production/pending-queue', title: '未完成计划队列', icon: '', module: 'production' },
      { path: '/production/restock-board', title: '备货看板', icon: '', module: 'production' },
      { path: '/production/delivery-warnings', title: '交货预警', icon: '', module: 'production' },
      { path: '/production/approval', title: '报工审批', icon: '', module: 'production' },
      { path: '/production/reversals', title: '撤回审批', icon: '', module: 'production' },
      { path: '/production/material-requisitions', title: '物料需求单', icon: '', module: 'production' },
      { path: '/production/material-returns', title: '退料记录', icon: '', module: 'production' },
      // 这3项是「生产管理」下的生产配置项 (虽路由在 /system/*), module 归 'production' 而非 'system' —
      // 否则会被 demo 策展 DEMO_HIDE_MODULES_BY_TYPE['FACTORY'] 的 'system' 规则连带隐藏 (本意只藏系统管理顶级组)。
      { path: '/system/products', title: 'SKU 管理', icon: '', module: 'production', groupLabel: '生产配置' },
      { path: '/system/work-processes', title: '工序管理', icon: '', module: 'production',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/system/product-processes', title: '产品-工序配置', icon: '', module: 'production',
        hideForFactoryTypes: ['RESTAURANT'] },
      // 调料维护已并入产品-工序配置画布的「辅料 cell」；旧路由仅作兼容重定向
      // T125: 转换率配置菜单入口早已隐藏 — 后端 API/表/fallback 仍保留 (F001等老工厂
      // BomExpansionService 依赖)。2026-08-05 拍板转换率不再往画布搬, 维护走 API/DB。
      // BOM 配置已并入产品-工序配置画布(辅料/包材 cell + 草稿生效横幅), 菜单入口于
      // 2026-08-05 摘除, 页面本体于 2026-08-07 阶段 5 删除。
      // 老地址(/production/bom 及 versions/tree)改成 redirect 到画布, 既有深链仍不 404
      // —— 判据见 workflow/__tests__/bomMenuRetired.source.spec.ts。
      // (副产 2026-08-07 阶段 2 起是工序上的真实产出节点, 不再是 BOM 的一类 cell。)
      { path: '/production/bom/ecns', title: '工程变更通知', icon: '', module: 'production' },
      { path: '/rd/samples', title: '研发样品', icon: '', module: 'production' },
      { path: '/analytics/production-report', title: '车间实时生产报表', icon: '', module: 'analytics',
        groupLabel: '生产分析', hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/production-analytics/production', title: '生产数据分析', icon: '', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/production/bom-achievement', title: 'BOM达成率分析', icon: '', module: 'production' },
      { path: '/production/process-io', title: '工序投入产出对比', icon: '', module: 'production' },
      // SP9: 人效双口径对比 (报价 quotedLaborCost vs 实际 actualLaborCost)
      { path: '/production/labor-efficiency', title: '人效双口径对比', icon: '', module: 'production' },
      { path: '/production-analytics/yield-cost', title: '成品出厂核算', icon: '', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/production-analytics/cost-summary', title: '成本汇总', icon: '', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
    ]
  },
  {
    // P1-5: restaurants 默认不见 "仓储管理" (食材库存在 /restaurant/stocktaking)
    // UX P2-5: 合并"调拨管理" 1-item 组到这里,减少顶级菜单数
    path: '/warehouse', title: '仓储管理', icon: 'Box', module: 'warehouse',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/warehouse/unordered-inbound-applications', title: '无订单入库申请', icon: '', module: 'warehouse', groupLabel: '仓储作业' },
      { path: '/warehouse/materials', title: '原料 / 物料入库与批次', icon: '', module: 'warehouse' },
      { path: '/warehouse/shipments', title: '出货管理', icon: '', module: 'warehouse' },
      { path: '/transfer/list', title: '调拨单', icon: '', module: 'warehouse' },
      // SP7 六扇门 ERP-lite 报损管理 (仓库→财务 / 生产→厂长 双轨)
      { path: '/warehouse/wastage-reports', title: '报损管理', icon: '', module: 'warehouse' },
      { path: '/inventory/by-warehouse', title: '分仓库存查询', icon: '', module: 'warehouse', groupLabel: '库存盘点' },
      // F006 六膳门 — 总库存查询 (工厂级原料总库存, 按物料聚合, 跨所有仓库)
      { path: '/warehouse/inventory-total', title: '总库存查询', icon: '', module: 'warehouse' },
      { path: '/warehouse/inventory', title: '库存批次查询', icon: '', module: 'warehouse' },
      { path: '/warehouse/stocktakes', title: '盘点与期初库存', icon: '', module: 'warehouse' },
      { path: '/warehouse/material-types', title: '原料类型字典', icon: '', module: 'warehouse', groupLabel: '仓储配置' },
      { path: '/warehouse/material-segments', title: '物料分类字典', icon: '', module: 'warehouse' },
      { path: '/warehouse/material-price-trend', title: '物料均价趋势', icon: '', module: 'warehouse', groupLabel: '仓储分析' },
    ]
  },
  {
    // P1-5: restaurants 默认不见 "质量管理" (食品安全走 食检,不是 ISO 质检)
    path: '/quality', title: '质量管理', icon: 'Checked', module: 'quality',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/quality/inspections', title: '质检记录', icon: '', module: 'quality', groupLabel: '质量检验' },
      { path: '/quality/label-qc', title: '包装标签拍检', icon: '', module: 'quality' },
      // Sprint4-H Q-PROCESS-1: 工序质检不良闭环
      { path: '/quality/defects', title: '工序质检不良', icon: '', module: 'quality' },
      // Sprint4-H Q-RETURN-1: 质检退回单 (上游退回, 不含客户销售退货)
      { path: '/quality/returns', title: '质检退回单', icon: '', module: 'quality' },
      { path: '/quality/disposals', title: '废弃处理', icon: '', module: 'quality', groupLabel: '处置闭环' },
      { path: '/quality/standards', title: '质检标准', icon: '', module: 'quality', groupLabel: '质量配置' }
    ]
  },
  {
    // P1-5 fix: restaurants 默认不见 "采购管理" (进货走 /restaurant/requisitions)
    // Canvas 管理员可细粒度开启 purchase_order 码解锁.
    path: '/procurement', title: '采购管理', icon: 'ShoppingCart', module: 'procurement',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/procurement/orders', title: '采购订单', icon: '', module: 'procurement', groupLabel: '采购执行' },
      { path: '/procurement/finance-review', title: '财务待审采购单', icon: '', module: 'finance', groupLabel: '采购审批' },
      { path: '/procurement/suppliers', title: '供应商管理', icon: '', module: 'procurement', groupLabel: '供应商与价格' },
      { path: '/procurement/price-lists', title: '价格表管理', icon: '', module: 'procurement' }
    ]
  },
  {
    path: '/sales', title: '销售管理', icon: 'Goods', module: 'sales',
    children: [
      { path: '/sales/orders', title: '销售订单', icon: '', module: 'sales', groupLabel: '销售业务' },
      // 2026-06-17: 「成品库存」从销售菜单移除 (Steve: 不需要留在销售)。
      // 成品/库存归仓储管理口径; 路由 /sales/finished-goods 保留 (深链可达), 仅撤销售侧入口。
      // Apr 24 UX: /sales/shipments 等 manufacturing-only 概念对餐饮隐藏 (无批次/发货)。
      { path: '/sales/customers', title: '客户管理', icon: '', module: 'sales' },
      { path: '/sales/shipments', title: '出货记录', icon: '', module: 'sales',
        hideForFactoryTypes: ['RESTAURANT'] },
      // T-RTA fix (audit B2 BLOCKER 2026-05-13): /sales/returns route was added by
      // PR #549 but NEVER surfaced in sidebar — customer service / finance roles
      // had no menu entry, only "申请退货" button on individual sales order detail.
      // Now discoverable for 历史退货 review + status-tracking workflows.
      { path: '/sales/returns', title: '销售退货', icon: '', module: 'sales' },
      // #739/#746: 销售方向付款申请 (退款/返利/销售费用) — roles 镜像 router/index.ts SalesPaymentRequests
      { path: '/sales/payment-requests', title: '销售付款申请', icon: '', module: 'sales',
        roles: ['factory_super_admin', 'platform_admin', 'sales_manager', 'salesperson', 'finance_manager', 'cashier'] },
      // Sprint4-H F-AR-1: 销售单财务审核 (镜像 /procurement/finance-review).
      // module:'finance' 让 finance_manager 在 /sales 组下也可见. 销售员可看但
      // 后端 @RequirePermission("finance:read_write") 限制操作权.
      { path: '/sales/finance-review', title: '财务待审销售单', icon: '', module: 'finance', groupLabel: '销售审批' },
      { path: '/sales/vehicles', title: '车辆字典', icon: '', module: 'sales', groupLabel: '销售配置',
        hideForFactoryTypes: ['RESTAURANT'] }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "人事管理" (小连锁常无 HR 系统,Canvas 可开启 hr_employee)
    path: '/hr', title: '人事管理', icon: 'User', module: 'hr',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/system/users', title: '账号管理', icon: '', module: 'system', groupLabel: '账号权限' },
      { path: '/system/roles', title: '角色权限', icon: '', module: 'system' },
      { path: '/hr/employees', title: '员工档案', icon: '', module: 'hr', groupLabel: '员工组织' },
      { path: '/hr/departments', title: '部门管理', icon: '', module: 'hr' },
      { path: '/hr/work-types', title: '工种字典', icon: '', module: 'hr' },
      { path: '/hr/attendance', title: '考勤管理', icon: '', module: 'hr', groupLabel: '入职考勤' },
      { path: '/hr/whitelist', title: '账号邀请', icon: '', module: 'hr' }
    ]
  },
  // UX P2-5 merged into 仓储管理: /transfer 原独立顶级组 (1 项), 合并节省 1 顶级项
  {
    // P1-5: restaurants 默认不见 "设备管理" (manufacturing 专属)
    path: '/equipment', title: '设备管理', icon: 'Monitor', module: 'equipment',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/equipment/list', title: '设备列表', icon: '', module: 'equipment', groupLabel: '设备台账' },
      { path: '/equipment/maintenance', title: '维护记录', icon: '', module: 'equipment', groupLabel: '维护监控' },
      { path: '/equipment/alerts', title: '告警管理', icon: '', module: 'equipment' }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "财务管理" (SmartBI 有 /smart-bi/finance 简化版,
    // manufacturing 的 ar-ap/invoices/sku-margin 对餐饮无意义)
    path: '/finance', title: '财务管理', icon: 'Money', module: 'finance',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/sales/finance-review', title: '待审销售单', icon: '', module: 'finance', groupLabel: '审核队列' },
      { path: '/procurement/finance-review', title: '待审采购单', icon: '', module: 'finance' },
      { path: '/finance/adjustments', title: '调整审批', icon: '', module: 'finance' },
      { path: '/finance/costs', title: '财务概览', icon: '', module: 'finance', groupLabel: '财务核算' },
      { path: '/finance/three-statements', title: '财务报表', icon: '', module: 'finance' },
      { path: '/finance/reports', title: '财务分析(Excel)', icon: '', module: 'finance' },
      { path: '/finance/ar-ap', title: '应收应付', icon: '', module: 'finance' },
      { path: '/finance/invoices', title: '开票管理', icon: '', module: 'finance' },
      { path: '/finance/payments', title: '收款管理', icon: '', module: 'finance' },
      { path: '/finance/sku-margin', title: 'SKU毛利率分析', icon: '', module: 'finance', groupLabel: '财务分析' },
      { path: '/finance/gross-margin-redline', title: '毛利红线配置', icon: '', module: 'finance', groupLabel: '财务配置' }
    ]
  },
  // UX P2-5 merged into 生产管理: /rd 原独立顶级组 (1 项), 研发样品并入生产
  {
    path: '/system', title: '系统管理', icon: 'Setting', module: 'system',
    children: [
      { path: '/system/logs', title: '操作日志', icon: '', module: 'system', groupLabel: '系统运维' },
      { path: '/system/settings', title: '系统设置', icon: '', module: 'system', groupLabel: '工厂配置' },
      { path: '/system/approval-chains', title: '审批链配置', icon: '', module: 'system' },
      { path: '/system/workflow-designer', title: '工作流设计器', icon: '', module: 'system' },
      { path: '/system/features', title: '功能模块配置', icon: '', module: 'system' },
      // Apr 18 2026 bug #48: Canvas 编辑器 router 限制 roles, sidebar 跟上不让 dispatcher/
      // 其他 system:read 角色看到菜单 (否则点进去 /403 体验差)
      { path: '/canvas-editor', title: 'Canvas 配置编辑器', icon: '', module: 'system', roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      { path: '/system/badge-generator', title: '员工工牌生成', icon: '', module: 'system',
        hideForFactoryTypes: ['RESTAURANT'] },
      // T123: 计量单位字典 — 客户要求"留个给我自己修改"的单位配置模块
      // 后端: GET/POST/PUT/DELETE /api/mobile/{factoryId}/system-config/units
      { path: '/unit-dictionary', title: '计量单位字典', icon: '', module: 'system',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      { path: '/system/ai-intents', title: 'AI意图配置', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY, groupLabel: '平台治理' },
      { path: '/system/skill-tools', title: 'Skill/Tool治理', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/llm-usage', title: 'LLM 用量监控', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      // 卡5 (2026-07-28 飞轮回接方案 P4 批): 飞轮运营台 5 个子页, 单一菜单入口跳总览, 页内 tab 切换其余 4 页.
      { path: '/system/ai-flywheel', title: 'AI 飞轮运营台', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/encoding-rules', title: '编码规则字典', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/ai-quota', title: 'AI 配额规则', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/pos', title: 'POS集成', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/smartbi-config', title: 'SmartBI配置', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      // UX P2-5: 行为校准 (1 项) 合并入系统管理下, 不单做顶级组
      { path: '/calibration/list', title: '行为校准', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY,
        hideForFactoryTypes: ['RESTAURANT'] },
      // 餐饮 Phase A A-3 Task 3.5: data quality queue admin page
      { path: '/system/data-quality-queue', title: '数据质量队列', icon: '', module: 'system',
        roles: PLATFORM_ADMIN_ONLY }
    ]
  },
  {
    path: '/scheduling', title: '智能调度', icon: 'Calendar', module: 'scheduling',
    children: [
      { path: '/scheduling/logistics/workbench', title: '排线工作台', icon: '', module: 'scheduling', groupLabel: '日常调度',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/logistics/records', title: '调度记录', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/logistics/orders', title: '门店与订单', icon: '', module: 'scheduling', groupLabel: '基础资料',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/logistics/resources', title: '车辆与司机', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/logistics/stores', title: '门店库', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/overview', title: '调度中心', icon: '', module: 'scheduling', groupLabel: '工厂智能调度',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/plans', title: '调度计划', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/realtime', title: '实时监控', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/workers', title: '人员分配', icon: '', module: 'scheduling', groupLabel: '资源与预警',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/alerts', title: '告警管理', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/settings', title: '排产设置', icon: '', module: 'scheduling', groupLabel: '调度配置',
        hideForFactoryTypes: ['LOGISTICS'] }
    ]
  },
  {
    // UX 2026-06-02 IA v2: 餐饮组重组为 3 层 (深度分析/日常录入/数据与系统)。
    // 运营总览移除 (Excel 浏览器病症); 经营驾驶舱复用「数据与分析」组 /smart-bi/dashboard
    // (业态自适应, 不重复造); 菜品四象限+毛利合并为 菜品分析双tab; 点评口碑保留显性入口。
    // spec: 2026-06-01-restaurant-web-admin-ia-redesign-design.md v2。
    path: '/restaurant', title: '餐饮运营', icon: 'KnifeFork', module: 'dashboard',
    // 2026-07-31: 补上 owner / purchaser / chef。`roles` 是**允许式白名单**
    // (AppSidebar.canSeeMenuItem: 写了就一票否决), 此前这三个角色即使模块权限
    // 给对了也看不见餐饮组 —— 权限有两个承载点, #2082/#2083 只改了矩阵那一个。
    roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner', 'hr_admin', 'restaurant_purchaser', 'restaurant_chef', 'warehouse_manager', 'procurement_manager', 'finance_manager', 'sales_manager'],
    hideForFactoryTypes: ['FACTORY'],
    children: [
      // -- 部门驾驶舱 (2026-07-31) --
      // 不写 roles: 由 module 权限门控即可(四个部门键各自决定谁能看见), 再叠一层
      // 角色白名单只会变成第二处要同步的地方 —— 那正是 #2084 修的那个坑。
      { path: '/restaurant/ops', title: '运营', icon: 'Bowl', module: 'restaurantOps', groupLabel: '部门驾驶舱' },
      { path: '/restaurant/marketing', title: '市场', icon: 'TrendCharts', module: 'restaurantMarketing' },
      // 采购 2026-08-06 独立成第五个部门 (Steve 拍板)。同样不写 roles, 由 module 门控。
      { path: '/restaurant/procurement', title: '采购', icon: 'ShoppingCart', module: 'restaurantProcurement' },
      { path: '/restaurant/hr', title: '人事', icon: 'User', module: 'restaurantHr' },
      { path: '/restaurant/staffing', title: '预测排班', icon: 'Calendar', module: 'restaurantHr',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner', 'hr_admin'] },
      { path: '/restaurant/finance', title: '财务', icon: 'Money', module: 'restaurantFinance' },
      // ── 运营 ────────────────────────────────────────────────────
      // 后厨供应链: 领料 / 损耗 / 盘点 / 配方
      { path: '/restaurant/requisitions', title: '领料管理', icon: '', module: 'restaurantOps', groupLabel: '运营' },
      { path: '/restaurant/wastage', title: '损耗管理', icon: '', module: 'restaurantOps' },
      { path: '/restaurant/stocktaking', title: '盘点管理', icon: '', module: 'restaurantOps' },
      { path: '/restaurant/recipes', title: '配方管理', icon: '', module: 'restaurantOps' },
      // ⚠️ 下面两项**刻意不改 module**: 它们跨工厂/餐饮两侧使用
      // (warehouse_manager / procurement_manager 也在用, 而这些角色 restaurant='-')。
      // 改成 restaurantOps 会断掉他们的访问 —— 本轮只归组, 标签留待单独评估。
      // ⛔ 这里曾写着「2026-08-06 Steve 拍板: 采购职责并入市场(sales_manager),
      //    厨师长/餐饮采购退役」并据此摘掉了 restaurant_purchaser —— **那一侧是败的**。
      //    同日更晚的 V20261029_58/_63 把采购立为**第五个部门**, 载体角色就是
      //    restaurant_purchaser, 权威 departmentConfig.ts 也把本页列为采购部门的
      //    第一个动作。两处口径打架, 用户可见后果是采购部长点自己部门的动作打不开。
      //    2026-08-07 按五部门口径收口, 把它加回来。
      // module 仍不改(见上方 ⚠️): sales_manager / restaurant_purchaser 都有 dashboard:r。
      { path: '/restaurant/supplier-delivery', title: '供应商进货录入', icon: '', module: 'dashboard',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner', 'sales_manager', 'restaurant_purchaser', 'warehouse_manager', 'procurement_manager'] },
      // ⚠️ 本项 module='procurement' 而 sales_manager **没有** procurement 权限,
      // 所以即使加进 roles 也会被 module 闸挡在前面(守卫先查 module 后查 roles)。
      // 餐饮侧要不要保留这条、还是改走 /restaurant/requisitions(module=restaurantOps,
      // 店长已有), 待定 —— 本轮只摘掉两个退役角色, 不擅自改 module 断工厂侧。
      // restaurant_purchaser 同样是被上面那条败的注释一起摘掉的 —— 报货/采购计划
      // 正是采购部门的活。V20261029_64 给了它 procurement:'rw', module 闸能过。
      { path: '/procurement/requisitions/my', title: '报货/采购计划', icon: '', module: 'procurement',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner', 'restaurant_purchaser', 'warehouse_manager', 'procurement_manager'] },

      // ── 市场 ────────────────────────────────────────────────────
      // 经营看板归市场: 6 KPI 里日营收/客单价/订单数三项是营收侧, 占多数。
      { path: '/restaurant/analytics/role-kpi', title: '经营看板', icon: '', module: 'restaurantMarketing', groupLabel: '市场' },
      { path: '/restaurant/analytics/dishes', title: '菜品分析', icon: '', module: 'restaurantMarketing' },
      { path: '/restaurant/analytics/stores', title: '门店对比', icon: '', module: 'restaurantMarketing' },
      { path: '/restaurant/analytics/platform', title: '平台分析', icon: 'ChatDotRound', module: 'restaurantMarketing' },
      // 🔴 2026-08-07: 这一项此前**有路由、有组件、但没有菜单入口, 也没有任何
      // 其它页面链接过去** —— 用户到不了。与 `/dashboard/ai-value` 同一类缺陷,
      // 是可达性扫描扫出来的第二个。
      // ⚠️ 我第一版写的是 module: 'restaurant'(跟随路由 meta 的板块准入),
      // 被 `restaurantMenuRouteAlignment.spec.ts` 当场判红 —— 那条契约要求
      // **每个餐饮功能页都挂在五个部门之一**。营销员提成属市场。
      // 菜单可见性(restaurantMarketing)比守卫放行(restaurant)窄是可以的:
      // 部门隔离本来就只做 UI 级防误点, 菜单是入口不是闸。
      { path: '/restaurant/commission', title: '营销员提成', icon: '', module: 'restaurantMarketing' },

      // ── 财务 ────────────────────────────────────────────────────
      // 三项都是金额口径, 而 restaurantFinance 的准入本身就要求 PRICE_VIEW_ROLES,
      // 所以不必再逐项写 roles 白名单 —— 少一处要同步的地方。
      { path: '/restaurant/cost-attribution', title: '成本归因', icon: '', module: 'restaurantFinance', groupLabel: '财务' },
      { path: '/restaurant/supplier-reconciliation', title: '供应商月对账', icon: '', module: 'restaurantFinance' },
      { path: '/restaurant/price-anomaly', title: '价格异常预警', icon: '', module: 'restaurantFinance' },

      // ── 数据与系统 ──────────────────────────────────────────────
      // 跨部门的数据基础设施, 不归属任何业务部门 —— 归进某一个部门会让其余三个
      // 部门的分析师找不到它。
      { path: '/restaurant/data-completeness', title: '数据完整度', icon: '', module: 'restaurant', groupLabel: '数据与系统' },
      { path: '/restaurant/admin/etl-status', title: 'ETL 状态', icon: '', module: 'restaurant',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      { path: '/restaurant/admin/name-resolution', title: '菜品名称匹配', icon: '', module: 'restaurant',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] }
    ]
  },
  {
    // UX 2026-06-01: 合并「经营报表」(/analytics) + 「智能分析」(/smart-bi) 为单一
    // 「数据与分析」组 (spec 2026-06-01-web-admin-analytics-ia-redesign-design.md)。
    // WS4 (2026-06-02): 财务看板/销售分析/趋势分析/KPI看板/指标中心 6 页合并为单一
    // 「经营分析」(BusinessAnalysisHub, 4 tab) — 菜单项移除, 由 redirect 桥接。
    // 删 AI分析报告 (#9)。AI 运维 3 项收 platform_admin/permission_admin 门控 (移 admin 区)。
    // 经营驾驶舱置顶主入口。各页后端不变 (部分页 Java reports + Python 混合)。
    path: '/smart-bi', title: '数据与分析', icon: 'TrendCharts', module: 'analytics',
    children: [
      // ★ 主入口 (无 groupLabel, 置顶)
      { path: '/smart-bi/dashboard', title: '经营驾驶舱', icon: 'Monitor', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_ALL },
      // -- AI 探索 --
      // P3: /smart-bi/query 已合并入此页 query tab (redirect /smart-bi/query → /smart-bi/analysis?tab=query), 菜单项移除
      { path: '/smart-bi/analysis', title: 'AI 问答 / 数据分析', icon: 'DataAnalysis', module: 'analytics', groupLabel: 'AI 探索',
        restaurantRoles: RESTAURANT_ANALYTICS_ALL },
      // -- 经营分析 --
      // WS4: 财务/销售/趋势/KPI·指标 合并为单一 hub。旧路径 (financial-dashboard/sales/
      // trends/kpi/indicator-center) 由 router redirect 保书签 → /smart-bi/analysis-hub?tab=。
      { path: '/smart-bi/analysis-hub', title: '经营分析', icon: 'TrendCharts', module: 'analytics', groupLabel: '经营分析',
        restaurantRoles: RESTAURANT_ANALYTICS_DECISION },
      { path: '/smart-bi/revenue-report', title: '收入管理报表', icon: 'Money', module: 'analytics',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager', 'restaurant_manager', 'restaurant_owner'],
        restaurantRoles: [...RESTAURANT_ANALYTICS_DECISION, 'finance_manager'],
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/smart-bi/health-report', title: 'AI 经营体检', icon: 'FirstAidKit', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_DECISION,
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/analytics/alert-dashboard', title: '异常预警', icon: 'Warning', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/analytics/supply-chain', title: '进销存总览', icon: 'Histogram', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      // -- 数据管理 --
      { path: '/smart-bi/upload', title: 'Excel 上传', icon: 'Upload', module: 'analytics', groupLabel: '数据管理',
        restaurantRoles: RESTAURANT_ANALYTICS_STEWARDS },
      { path: '/smart-bi/query-templates', title: '查询模板', icon: 'Tickets', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_STEWARDS },
      { path: '/smart-bi/data-completeness', title: '数据完整度', icon: 'DataAnalysis', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_STEWARDS },
      // Phase 0: 字段映射复核 — Excel 列无法自动映射时由人工确认并写入 pin (2026-06-15)
      { path: '/smart-bi/mapping-review', title: '字段映射复核', icon: 'EditPen', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_STEWARDS },
      // D-6 保守保留: 分析概览 (与驾驶舱/hub 重叠但数据源不同, P5 凭埋点再决定真删)
      { path: '/analytics/overview', title: '分析概览', icon: 'DataAnalysis', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_DECISION },
      // -- AI 运维 (admin) — WS4: 收 admin 门控 (普通经营用户不需要) --
      { path: '/smart-bi/food-kb-feedback', title: '知识库反馈', icon: 'ChatDotRound', module: 'analytics', groupLabel: 'AI 运维',
        roles: ['platform_admin', 'permission_admin', 'factory_super_admin'] },
      { path: '/smart-bi/fallback-log', title: 'AI 追问日志', icon: 'DataLine', module: 'analytics',
        roles: ['platform_admin', 'permission_admin', 'factory_super_admin'] },
      { path: '/smart-bi/calibration', title: '行为校准监控', icon: 'Aim', module: 'analytics', roles: ['platform_admin'] },
    ]
  },
  {
    // CRM P0「会员与营销」(2026-07-11): 首个 top-level 入口, 会员分析 (RFM 客群分层 +
    // 三维散点 + 生命周期 + 会员画像). Scope-minimal: 只加这一个新顶级组, 不重排其它模块
    // (完整 5-模块 IA 重构是后续 follow-up)。仅餐饮/demo 租户可见 — 与 revenue-report /
    // health-report 同一套 hideForFactoryTypes + roles 白名单 (会员储值/消费金额敏感)。
    path: '/crm', title: '会员与营销', icon: 'User', module: 'analytics',
    hideForFactoryTypes: ['FACTORY'],
    roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager', 'restaurant_manager'],
    children: [
      { path: '/crm/member-analysis', title: '会员分析', icon: '', module: 'analytics' },
    ]
  },
  {
    // 运营分析 (2026-07-12): 撤单稽核 + 区域坪效, 从 经营驾驶舱(RestaurantGoldGrid)
    // 迁出的独立顶级组 (驾驶舱瘦身)。与 /crm 同一批 hybrid bullet-point analysis
    // pattern (确定性 bullets + AI 解读 + 整页分析面板), 同一套门控 (会员储值/
    // 撤单金额同样敏感)。Scope-minimal: 只加这一个新顶级组, 不重排其它模块。
    path: '/ops', title: '运营分析', icon: 'Operation', module: 'analytics',
    hideForFactoryTypes: ['FACTORY'],
    roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager', 'restaurant_manager'],
    children: [
      { path: '/ops/operations-analysis', title: '运营分析', icon: '', module: 'analytics' },
    ]
  }
];

const TOP_LEVEL_FLOW_ORDER: Record<string, number> = {
  '/dashboard': 10,
  '/workdesk': 20,
  '/workflow': 25,
  '/procurement': 30,
  '/sales': 40,
  '/finance': 50,
  '/production': 60,
  '/warehouse': 70,
  '/quality': 80,
  '/smart-bi': 90,
  '/system': 100,
  '/scheduling': 110,
  '/hr': 120,
  '/equipment': 130,
  '/restaurant': 140,
  '/ops': 143,
  '/crm': 145,
};

function sortTopLevelMenu(items: MenuItem[]): MenuItem[] {
  return [...items].sort((a, b) => {
    const left = TOP_LEVEL_FLOW_ORDER[a.path] ?? 1000;
    const right = TOP_LEVEL_FLOW_ORDER[b.path] ?? 1000;
    return left - right;
  });
}

export const menuConfig: MenuItem[] = sortTopLevelMenu(rawMenuConfig);
