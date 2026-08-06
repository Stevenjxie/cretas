/**
 * 权限状态管理
 *
 * 4-layer resolution (per design spec 2026-04-18-permission-matrix-ai-driven-design.md):
 * - L2 (factory override from DB) → L1 (platform default from DB) → hardcoded fallback
 *
 * Hardcoded PERMISSION_MATRIX below is kept as FALLBACK for DB-unavailable scenarios
 * (initial load race, network error, offline). Primary source is API at login:
 * GET /api/admin/role-permissions + GET /F001/canvas/role-module-override.
 */
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import {
  getPlatformPermissions,
  getFactoryOverride,
  getUserModuleAccess,
  type PlatformPermission,
  type RoleModuleOverride,
  type UserModuleAccessView,
  type PermissionLevel as ApiPermissionLevel,
} from '@/api/permissionApi';
import { PRODUCTION_MODULE_REGISTRY, resolveModuleRegistryItem } from '@/config/moduleRegistry';
import { DEPARTMENTS, DEPARTMENT_ORDER } from '@/views/restaurant/departments/departmentConfig';

// 权限矩阵 - 定义每个角色对每个模块的权限
type PermissionLevel = 'rw' | 'r' | 'w' | '-';

interface ModulePermissions {
  [key: string]: PermissionLevel;
  dashboard: PermissionLevel;
  production: PermissionLevel;
  warehouse: PermissionLevel;
  quality: PermissionLevel;
  procurement: PermissionLevel;
  sales: PermissionLevel;
  hr: PermissionLevel;
  equipment: PermissionLevel;
  finance: PermissionLevel;
  system: PermissionLevel;
  analytics: PermissionLevel;
  scheduling: PermissionLevel;
  /**
   * 餐饮板块的**整体准入上限**。
   *
   * 2026-07-31 餐饮拆成运营/市场/人事/财务四个部门后，它不再直接对应某个页面，
   * 而是四个部门的天花板：`restaurant: '-'` 一定关掉全部四个（工厂类型过滤
   * `FACTORY_TYPE_MODULE_FILTER` 正是这么用的）。每个部门可以比它更严，不能更宽。
   */
  restaurant: PermissionLevel;
  /**
   * 四个部门的细分权限。省略即跟随 `restaurant`。
   *
   * 有意做成可选：绝大多数工厂角色 `restaurant: '-'`，四个部门自然全关，不必逐行
   * 写四遍；只有真正要区分部门的餐饮角色才显式声明。DB 驱动的权限（dbPermissions）
   * 目前也不下发这四个键，跟随上限是正确的回退。
   */
  restaurantOps?: PermissionLevel;
  restaurantMarketing?: PermissionLevel;
  restaurantHr?: PermissionLevel;
  restaurantFinance?: PermissionLevel;
  /**
   * 采购部门（2026-08-06 独立成第五个部门，载体角色 restaurant_purchaser）。
   *
   * ⚠️ 加部门键时**九个已声明部门的角色都要补上新键**：省略等于跟随 `restaurant`
   * 上限，而店长/市场/财务/人事的上限都是 `rw` —— 漏一个那个角色就白捡一个部门。
   */
  restaurantProcurement?: PermissionLevel;
  rd: PermissionLevel;
}

/** 权限强弱：rw > r > w > -。用于把部门权限压到 `restaurant` 上限之下。 */
const PERMISSION_RANK: Record<PermissionLevel, number> = {
  'rw': 3, 'r': 2, 'w': 1, '-': 0,
};

function weakerOf(a: PermissionLevel, b: PermissionLevel): PermissionLevel {
  return PERMISSION_RANK[a] <= PERMISSION_RANK[b] ? a : b;
}

/**
 * 四个部门的 module 名 —— **从驾驶舱权威表派生**，不再另写一份。
 *
 * 2026-08-01: 上一版这里手写了四个字符串，注释写着「避免两处各写一份」，但实际
 * 全仓有**四处**各自维护这份清单（本文件 / menuConfig.ts / router/index.ts /
 * departments/departmentConfig.ts）。加第五个部门要改四处，漏一处的表现是
 * 「看得见点进去 403」或「菜单没有敲 URL 能进」——**两种都不报错**，正是 #2082
 * 一天返工四次的成因。
 *
 * departmentConfig.DEPARTMENTS 信息最全（key / module / 标题 / KPI / 页面入口），
 * 所以它是权威；这里派生。菜单与路由由契约测试钉住不许漂。
 */
export const RESTAURANT_DEPARTMENT_MODULES = DEPARTMENT_ORDER.map(
  (key) => DEPARTMENTS[key].module,
) as readonly string[];

const PERMISSION_MATRIX: Record<string, ModulePermissions> = {
  // Level 0 - 工厂总监 (最高权限，全模块读写)
  factory_super_admin: {
    dashboard: 'rw',
    production: 'rw',
    warehouse: 'rw',
    quality: 'rw',
    procurement: 'rw',
    sales: 'rw',
    hr: 'rw',
    equipment: 'rw',
    finance: 'rw',
    system: 'rw',
    analytics: 'rw',
    scheduling: 'rw',
    restaurant: 'rw',
    restaurantOps: 'rw',
    restaurantMarketing: 'rw',
    restaurantHr: 'rw',
    restaurantFinance: 'rw',
    restaurantProcurement: 'rw',
    rd: 'rw'
  },

  // Level 10 - 职能部门经理
  hr_admin: {
    dashboard: 'r', production: '-', warehouse: '-', quality: '-',
    procurement: '-', sales: '-', hr: 'rw', equipment: '-',
    finance: '-', system: 'r', analytics: 'r', scheduling: '-', restaurant: 'rw',
    restaurantOps: '-', restaurantMarketing: '-',
    restaurantHr: 'rw', restaurantFinance: '-', restaurantProcurement: '-',
    rd: '-'
  },
  procurement_manager: {
    dashboard: 'r', production: 'r', warehouse: 'r', quality: '-',
    procurement: 'rw', sales: '-', hr: '-', equipment: '-',
    finance: 'r', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },
  sales_manager: {
    dashboard: 'r', production: 'r', warehouse: 'r', quality: '-',
    procurement: '-', sales: 'rw', hr: '-', equipment: '-',
    finance: 'r', system: '-', analytics: 'r', scheduling: '-',
    // 对齐 L1 权威 V20261029_57: 市场经理是餐饮「市场部门」的载体角色。
    // 上一版 restaurant: '-' 会在竞态/离线窗口里把整个餐饮板块关掉(上限 '-' 时
    // 四个部门一起关) —— 市场经理登录后餐饮菜单整块消失。
    // 工厂型租户不受影响: FACTORY_TYPE_MODULE_FILTER.FACTORY 会把 restaurant 打回 '-'。
    restaurant: 'rw',
    restaurantOps: '-', restaurantMarketing: 'rw',
    restaurantHr: '-', restaurantFinance: '-', restaurantProcurement: '-',
    rd: 'rw'  // 销售驱动 RD 需求/样品
  },
  // 调度 (dispatcher) - 生产调度、数据分析、趋势监控
  // R18-B3: finance/hr/system 继续锁死 (越权敏感).
  // Apr 18 2026 bug #53 (回调): 原 R18-B3 把 sales 也设 '-', 但调度员业务上需要
  // 看 SO 并协调"财务审核通过 → 创建生产计划"闭环 (Doc3 用户测试报告 SO 审核
  // 按钮 404 的真实场景). sales 改 'rw' 与后端 dispatcherPerms 对齐, 确保调度员
  // 能提交审核/开始生产。procurement 只读: 调度需看上游物料情况。
  // Apr 24 2026: hr/finance/system 硬编码 '-' 与 DB ('r') 不一致导致 router guard 首屏错拒. 对齐 DB.
  dispatcher: {
    dashboard: 'rw', production: 'rw', warehouse: 'r', quality: 'r',
    procurement: 'r', sales: 'rw', hr: 'r', equipment: 'r',
    finance: 'r', system: 'r', analytics: 'rw', scheduling: 'rw', restaurant: '-',
    rd: 'rw'  // 调度协调 RD 样品到生产
  },
  // production_manager (已废弃，保留向后兼容，映射到 dispatcher)
  // Apr 24: 同步 dispatcher 的 DB 对齐
  production_manager: {
    dashboard: 'rw', production: 'rw', warehouse: 'r', quality: 'r',
    procurement: 'r', sales: 'rw', hr: 'r', equipment: 'r',
    finance: 'r', system: 'r', analytics: 'rw', scheduling: 'rw', restaurant: '-',
    rd: 'rw'
  },
  warehouse_manager: {
    dashboard: 'r', production: 'r', warehouse: 'rw', quality: '-',
    procurement: 'r', sales: 'r', hr: '-', equipment: '-',
    finance: '-', system: '-', analytics: '-', scheduling: 'r', restaurant: '-', rd: '-'
  },
  equipment_admin: {
    dashboard: 'r', production: 'r', warehouse: '-', quality: '-',
    procurement: '-', sales: '-', hr: '-', equipment: 'rw',
    finance: '-', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },
  quality_manager: {
    dashboard: 'r', production: 'r', warehouse: '-', quality: 'rw',
    procurement: '-', sales: '-', hr: '-', equipment: '-',
    finance: '-', system: '-', analytics: '-', scheduling: '-', restaurant: '-',
    rd: 'r'  // QA 审核样品, 只读
  },
  // Apr 24: procurement 硬编码 '-' 与 DB 'r' 不一致 (finance 经理需 read 采购数据做成本分析). 对齐 DB.
  finance_manager: {
    dashboard: 'r', production: 'r', warehouse: '-', quality: '-',
    procurement: 'r', sales: 'r', hr: '-', equipment: '-',
    finance: 'rw', system: '-', analytics: 'rw', scheduling: '-',
    // 餐饮侧只看财务口径的页(供应商对账 / 成本归因)。此前是在 router/guards.ts 的
    // ROLE_PATH_WHITELIST 里硬编码那两条路径, 现由权限模型表达, 那个补丁已删。
    //
    // restaurant 是「进餐饮 vs 进工厂」的板块准入上限, 不是权限档次 —— 财务经理
    // 在餐饮租户里当然进得去, 所以是 rw 而不是 r; 真正的收窄由下面四个部门键表达。
    // 上一版写 'r' 会把 restaurantFinance 一起压成 r(见 weakerOf 上限规则),
    // 财务经理在自己的部门里反而不可写。对齐 L1 权威 V20261029_57。
    restaurant: 'rw',
    restaurantOps: '-', restaurantMarketing: '-',
    restaurantHr: '-', restaurantFinance: 'rw', restaurantProcurement: '-',
    rd: 'r'  // 定价参考 (analytics rw 对齐后端 SmartBI 完整权限)
  },
  // 出纳 (D9 #675/#678): 付款申请 APPROVED→PAID 执行者。路由守卫先查 module 后查 roles,
  // 此 fallback 缺席时 DB 异步加载未完成的窗口里 cashier 会被打成 unactivated 全 403 (2026-06-11 演示预跑实测)。
  // procurement r = 采购付款申请页; sales r = 销售付款申请页(#739, 路由 roles 已含 cashier); 对齐 DB L1。
  cashier: {
    dashboard: 'r', production: '-', warehouse: '-', quality: '-',
    procurement: 'r', sales: 'r', hr: '-', equipment: '-',
    finance: 'rw', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },

  // 餐饮管理
  // ⚠️ 下面三个餐饮角色的**模块权限唯一权威**是
  //    backend/java/.../service/impl/PermissionServiceImpl.java 里的
  //    restaurantOwnerPerms / restaurantChefPerms / restaurantPurchaserPerms。
  //    这份是镜像 —— 改那边必须同步改这里, 有对齐用例钉住
  //    (permission.restaurant-departments.spec.ts 的 JAVA_AUTHORITY)。
  //    部门四键是前端独有的细分, Java 侧只有一个 restaurant。

  // 餐饮老板：四个部门全权
  restaurant_owner: {
    // 🔴 老板 = **全模块只读**, 逐格对齐 L1 权威 V20261029_56 (21 个模块全 'r')。
    // 该迁移注释原话:「给 rw 会让老板成为绕过部门边界的后门」。
    //
    // 此前这里是 dashboard/warehouse/procurement/finance/analytics 与四个部门
    // 全写 'rw' —— 与 L1 相反。fallback **不是死代码**: DB 权限竞态/离线/接口
    // 失败的窗口里它是真实生效值(源码 "fallback to hardcoded for race/offline/error",
    // 且来源选择是**整体二选一**不是逐键)。所以那个后门在那个窗口里就是开着的。
    // 2026-08-06 Steve 拍板:「保留吧 作为全局RW，也可以替代其它角色做OA」。
    // 老板 = 全局读写, 能替各部门执行动作(采购审批 / 月对账确认 / 报货领料审批)。
    // 三处口径本轮一起对齐: L1 迁移 V20261029_62 + Java PermissionServiceImpl
    // (改成 ALL_MODULES 全 read_write) + 本 fallback。此前三处各说各话 ——
    // L1 曾是全只读(V20261029_56, 已被 _62 推翻), Java 只列了 6 个模块 rw,
    // fallback 又是另一套, 老板替不了 production/quality/sales/hr 那些岗位。
    dashboard: 'rw', production: 'rw', warehouse: 'rw', quality: 'rw',
    procurement: 'rw', sales: 'rw', hr: 'rw', equipment: 'rw',
    finance: 'rw', system: 'rw', analytics: 'rw', scheduling: 'rw',
    restaurant: 'rw',
    restaurantOps: 'rw', restaurantMarketing: 'rw',
    restaurantHr: 'rw', restaurantFinance: 'rw', restaurantProcurement: 'rw',
    rd: 'rw'
  },

  // 厨师长：报货 / 领料 / 验收入库 —— 后厨那一摊, 不碰营销与财务
  restaurant_chef: {
    dashboard: 'r', production: '-', warehouse: 'rw', quality: '-',
    procurement: 'r', sales: '-', hr: '-', equipment: '-',
    finance: '-', system: '-', analytics: 'r', scheduling: '-',
    restaurant: 'rw',
    restaurantOps: 'rw', restaurantMarketing: '-',
    restaurantHr: '-', restaurantFinance: '-', restaurantProcurement: '-',
    rd: '-'
  },

  // 餐饮采购：请购 + 采购全链路；财务只读(看采购金额, 不做财务审核)
  restaurant_purchaser: {
    dashboard: 'r', production: '-', warehouse: 'r', quality: '-',
    procurement: 'rw', sales: '-', hr: '-', equipment: '-',
    finance: 'r', system: '-', analytics: 'r', scheduling: '-',
    restaurant: 'rw',
    // 采购 2026-08-06 成为第五个部门后, 它只拥有自己那个部门。
    // 这两格原本是 Ops 'rw' / Finance 'r' —— 那是它还是「通用餐饮角色」时的遗留,
    // 与 L1 权威(V20261029_61)相反。扩守卫到 30 行时当场抓到。
    restaurantOps: '-', restaurantMarketing: '-',
    restaurantHr: '-', restaurantFinance: '-', restaurantProcurement: 'rw',
    rd: '-'
  },

  restaurant_manager: {
    dashboard: 'r', production: '-', warehouse: '-', quality: '-',
    procurement: 'r', sales: '-', hr: '-', equipment: '-',
    finance: 'r', system: '-', analytics: 'r', scheduling: '-',
    restaurant: 'rw',
    // 对齐 L1 权威 V20261029_57: 店长管运营, 人事**只读**, 市场/财务不可见。
    // 上一版这里是 Ops/Marketing/Hr 全 rw + Finance r —— 那是拆部门之前的产品口径。
    // 人事给 'r' 而不是 '-': 预测排班(/restaurant/staffing)挂在 restaurantHr 上,
    // 而 menuConfig / router 的 roles 白名单都明写着店长 —— 给 '-' 会让店长
    // 「菜单里有、点进去 403」。副作用: 店长同时看得到人事驾驶舱, 只读。
    restaurantOps: 'rw', restaurantMarketing: '-',
    restaurantHr: 'r', restaurantFinance: '-', restaurantProcurement: '-',
    rd: '-'
  },

  // Level 20 - 车间管理 (只看计划，执行在批次/报工模块)
  workshop_supervisor: {
    dashboard: 'r', production: 'r', warehouse: 'r', quality: 'w',
    procurement: '-', sales: '-', hr: 'r', equipment: 'r',
    finance: '-', system: '-', analytics: '-', scheduling: 'r', restaurant: '-',
    rd: 'r'
  },

  // Level 25 - 大组长 (管理多个小组, 批次+报工查看)
  // R4 fix: was missing from matrix → all routes were 403 (spec §3, backend FactoryUserRole enum 25)
  team_leader: {
    dashboard: 'r', production: 'r', warehouse: '-', quality: '-',
    procurement: '-', sales: '-', hr: 'r', equipment: 'r',
    finance: '-', system: '-', analytics: '-', scheduling: 'r', restaurant: '-',
    rd: 'r'
  },

  // Level 28 - 小组长 (单组 + 批次内操作)
  // R4 fix: was missing from matrix → all routes were 403 (spec §3, backend FactoryUserRole enum 28)
  group_leader: {
    dashboard: 'r', production: 'w', warehouse: '-', quality: '-',
    procurement: '-', sales: '-', hr: '-', equipment: 'r',
    finance: '-', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },

  // Level 30 - 一线员工
  quality_inspector: {
    dashboard: 'r', production: 'r', warehouse: '-', quality: 'w',
    procurement: '-', sales: '-', hr: '-', equipment: '-',
    finance: '-', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },
  operator: {
    dashboard: 'r', production: 'w', warehouse: '-', quality: '-',
    procurement: '-', sales: '-', hr: '-', equipment: '-',
    finance: '-', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },
  warehouse_worker: {
    dashboard: 'r', production: '-', warehouse: 'w', quality: '-',
    procurement: '-', sales: '-', hr: '-', equipment: '-',
    finance: '-', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },

  // Level 50 - 查看者 (Apr 24 2026: 对齐 DB L1 platform permissions — 之前 hr/finance 硬编码 '-',
  // DB 里是 'r'. permissionStore.loadFromDb 是 async, 首屏 router guard 用 hardcoded 快路径判断时
  // 会错拒 viewer 访问 /hr/*、/finance/* 直接 redirect /403.
  // 结果 sidebar (等 DB 加载完) 显示人事/财务管理, 但点进去 /403 — 矛盾体验.
  // 对齐后: hardcoded 与 DB 一致, 初次路由正确放行, UX 一致.)
  viewer: {
    dashboard: 'r', production: 'r', warehouse: 'r', quality: 'r',
    procurement: 'r', sales: 'r', hr: 'r', equipment: 'r',
    finance: 'r', system: '-', analytics: 'r', scheduling: 'r',
    restaurant: 'r',
    // 财务部门整页都是金额, 而 viewer 不在 PRICE_VIEW_ROLES —— 进去看满屏「—」
    // 像功能坏了。价格闸也会拦, 这里显式写出来是为了读代码时一眼看见。
    restaurantOps: 'r', restaurantMarketing: 'r',
    restaurantHr: 'r', restaurantFinance: '-', restaurantProcurement: 'r',
    rd: 'r'
  },

  // 平台管理员
  platform_admin: {
    dashboard: 'rw', production: 'rw', warehouse: 'rw', quality: 'rw',
    procurement: 'rw', sales: 'rw', hr: 'rw', equipment: 'rw',
    finance: 'rw', system: 'rw', analytics: 'rw', scheduling: 'rw', restaurant: 'rw',
    rd: 'rw'
  },

  // 默认
  unactivated: {
    dashboard: '-', production: '-', warehouse: '-', quality: '-',
    procurement: '-', sales: '-', hr: '-', equipment: '-',
    finance: '-', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  }
};

export type ModuleName = Extract<keyof ModulePermissions, string>;

/**
 * 工厂类型模块过滤
 * 按 factoryType 控制哪些模块对该类型工厂不可见
 * '-' 表示该模块在此类型工厂下被屏蔽（覆盖角色权限）
 * undefined/未列出的模块保留角色原始权限
 */
const FACTORY_TYPE_MODULE_FILTER: Record<string, Partial<ModulePermissions>> = {
  FACTORY: {
    restaurant: '-',
  },
  RESTAURANT: {
    production: '-',
    warehouse: '-',
    quality: '-',
    equipment: '-',
    scheduling: '-',
  },
  LOGISTICS: {
    production: '-',
    warehouse: '-',
    quality: '-',
    procurement: '-',
    sales: '-',
    hr: '-',
    equipment: '-',
    finance: '-',
    system: '-',
    analytics: '-',
    restaurant: '-',
    rd: '-',
  },
  // HEADQUARTERS / CENTRAL_KITCHEN / BRANCH: 不做限制，保留角色原始权限
};

/**
 * Roles allowed to view price fields (totalAmount/unitPrice/taxAmount/freightAmount/
 * discountAmount/etc). Mirrors backend `PermissionServiceImpl.PRICE_VIEW_ROLES`
 * for `procurement:price:view`. Backend strips price values to null for any role
 * outside this list; frontend uses it to hide entire column headers (otherwise
 * Element Plus renders empty columns → misleading UX).
 */
const PRICE_VIEW_ROLES: ReadonlySet<string> = new Set([
  'factory_super_admin',
  'platform_admin',
  'procurement_manager',
  'finance_manager',
  'sales_manager',
  'dispatcher',
  'production_manager',
  'restaurant_manager',
  // 2026-07-31 补齐: Java PermissionServiceImpl.PRICE_VIEW_ROLES 里有这两个
  // (源码注释「餐饮老板/采购需查看采购价格」), 而前端这份漏了 —— 后端把价格值发
  // 过来, 前端却把整列藏掉。这份是镜像, 改 Java 那边必须同步改这里。
  'restaurant_owner',
  'restaurant_purchaser',
  'permission_admin',
  'department_admin',
]);

export const usePermissionStore = defineStore('permission', () => {
  // State
  const loadedRoutes = ref<string[]>([]);
  const currentRole = ref<string>('unactivated');
  const currentFactoryId = ref<string>('');
  const currentFactoryType = ref<string>('');
  const currentUserId = ref<string>('');

  // DB-driven state (Phase 3 Task 3.2)
  const dbPermissions = ref<ModulePermissions | null>(null);
  const isDbLoaded = ref(false);
  const dbLoadError = ref<string | null>(null);
  const userModuleAccess = ref<Record<string, UserModuleAccessView>>({});
  const isUserModuleAccessLoaded = ref(false);
  const lastLoadTs = ref<number>(0);
  const LOAD_DEBOUNCE_MS = 30_000;  // Avoid redundant fetches within 30s
  let dbLoadPromise: Promise<void> | null = null;
  let dbLoadIdentity = '';

  const permissionIdentity = () =>
    `${currentRole.value}\u0000${currentFactoryId.value}\u0000${currentUserId.value}`;

  function setRole(
    role: string,
    factoryId?: string,
    factoryType?: string,
    userId?: string | number,
    options?: { skipDbLoad?: boolean },
  ) {
    const roleChanged = currentRole.value !== (role || 'unactivated')
      || currentFactoryId.value !== (factoryId || '')
      || currentUserId.value !== (userId == null ? '' : String(userId));
    currentRole.value = role || 'unactivated';
    currentFactoryId.value = factoryId || '';
    currentFactoryType.value = factoryType || '';
    currentUserId.value = userId == null ? '' : String(userId);
    if (roleChanged) {
      // Invalidate DB cache when identity changes
      dbPermissions.value = null;
      isDbLoaded.value = false;
      dbLoadError.value = null;
      userModuleAccess.value = {};
      isUserModuleAccessLoaded.value = false;
      lastLoadTs.value = 0;
    }
    // Fire-and-forget async load (non-blocking)
    if (!options?.skipDbLoad && role && role !== 'unactivated' && factoryId) {
      void loadFromDb();
    }
  }

  /**
   * Load permissions from L1 + L2 API, merge for current role, fill dbPermissions.
   * Failures set dbLoadError and leave dbPermissions null (canWrite falls back to hardcoded).
   */
  function loadFromDb(): Promise<void> {
    const now = Date.now();
    if (now - lastLoadTs.value < LOAD_DEBOUNCE_MS && isDbLoaded.value) {
      return Promise.resolve();
    }

    const identity = permissionIdentity();
    if (dbLoadPromise && dbLoadIdentity === identity) return dbLoadPromise;

    lastLoadTs.value = now;
    dbLoadError.value = null;
    const role = currentRole.value;
    const factoryId = currentFactoryId.value;
    const userId = currentUserId.value;

    const request = (async () => {
      try {
        const [l1Rows, l2Map] = await Promise.all([
          getPlatformPermissions(),
          factoryId
            ? getFactoryOverride(factoryId).catch(() => ({} as RoleModuleOverride))
            : Promise.resolve({} as RoleModuleOverride),
        ]);

        let l4Rows: UserModuleAccessView[] = [];
        let l4Loaded = false;
        if (factoryId && userId) {
          try {
            l4Rows = await getUserModuleAccess(factoryId, userId, { silent: true });
            l4Loaded = true;
          } catch {
            // L4 user-specific overrides are managed from System/Canvas. Business roles
            // can run on L1/L2 permissions without read access to that admin matrix.
            l4Loaded = true;
          }
        }

        // A previous identity's slower response must never overwrite a newer login.
        if (permissionIdentity() !== identity) return;
        dbPermissions.value = mergeLayers(l1Rows, l2Map, role);
        if (l4Loaded) applyUserModuleAccess(l4Rows);
        isDbLoaded.value = true;
      } catch (e) {
        if (permissionIdentity() !== identity) return;
        dbLoadError.value = (e as Error)?.message || 'Failed to load permissions';
        isDbLoaded.value = false;
        dbPermissions.value = null;
        userModuleAccess.value = {};
        isUserModuleAccessLoaded.value = false;
      }
    })();

    const trackedRequest = request.finally(() => {
      if (dbLoadPromise === trackedRequest) {
        dbLoadPromise = null;
        dbLoadIdentity = '';
      }
    });
    dbLoadIdentity = identity;
    dbLoadPromise = trackedRequest;
    return trackedRequest;
  }

  function applyUserModuleAccess(rows: UserModuleAccessView[]): void {
    const next: Record<string, UserModuleAccessView> = {};
    for (const row of rows || []) {
      next[row.moduleCode] = row;
    }
    userModuleAccess.value = next;
    isUserModuleAccessLoaded.value = true;
  }

  /**
   * Merge L1 (platform defaults for this role) + L2 (factory override for this role).
   * Returns ModulePermissions map for the role (fallback rw/r/w/- strings).
   */
  function mergeLayers(
    l1Rows: PlatformPermission[],
    l2Map: RoleModuleOverride,
    role: string,
  ): ModulePermissions {
    const result: Partial<ModulePermissions> = {};
    // L1 — rows for this role only
    for (const p of l1Rows) {
      if (p.roleCode === role) {
        (result as Record<string, PermissionLevel>)[p.moduleCode] = p.permissionLevel;
      }
    }
    // L2 — overlay override for this role
    const override = l2Map[role];
    if (override) {
      for (const [mod, level] of Object.entries(override)) {
        (result as Record<string, PermissionLevel>)[mod] = level as PermissionLevel;
      }
    }
    return result as ModulePermissions;
  }

  // Getters
  // Phase 3: prefer DB-driven dbPermissions, fallback to hardcoded for race/offline/error.
  const currentPermissions = computed((): ModulePermissions => {
    // Source selection: DB (L1+L2 merged) when loaded and for current role, else hardcoded fallback.
    const source = (isDbLoaded.value && dbPermissions.value)
      ? dbPermissions.value
      : (PERMISSION_MATRIX[currentRole.value] || PERMISSION_MATRIX['unactivated']);
    const rolePerms: ModulePermissions = { ...source };
    const typeFilter = currentFactoryType.value ? FACTORY_TYPE_MODULE_FILTER[currentFactoryType.value] : undefined;
    if (typeFilter) {
      for (const [mod, level] of Object.entries(typeFilter)) {
        if (level === '-') {
          (rolePerms as unknown as Record<string, PermissionLevel>)[mod] = '-';
        }
      }
    }

    // 餐饮四部门的最终权限在这里一次算完, 下游(permissionLevelFor / canAccess /
    // canWrite / 菜单过滤)拿到的就是结果, 不必各自知道「上限 + 细分」这套规则 ——
    // 规则散到多个消费点就一定会漂。
    //
    //   最终 = min(restaurant 上限, 该部门声明值 ?? 上限)
    //
    // 这样两件事自动成立: 工厂类型过滤把 restaurant 打成 '-' 时四个部门一起关;
    // 没声明部门的角色(绝大多数工厂角色)跟随上限。
    const ceiling = rolePerms.restaurant ?? '-';
    for (const dept of RESTAURANT_DEPARTMENT_MODULES) {
      const declared = rolePerms[dept] ?? ceiling;
      rolePerms[dept] = weakerOf(ceiling, declared);
    }
    // 财务部门整页都是金额。不在 PRICE_VIEW_ROLES 的角色进去只会看到满屏「—」,
    // 那看起来像功能坏了而不像权限不足 —— 所以直接不给进, 菜单里也就不出现。
    if (!PRICE_VIEW_ROLES.has(currentRole.value)) {
      rolePerms.restaurantFinance = '-';
    }

    return rolePerms;
  });

  // Actions
  function moduleDefinition(module: string) {
    return resolveModuleRegistryItem(module);
  }

  function userAccessFor(module: string): UserModuleAccessView | undefined {
    const definition = moduleDefinition(module);
    if (definition) {
      return userModuleAccess.value[definition.moduleCode];
    }
    return userModuleAccess.value[module];
  }

  function permissionModuleFor(module: string): string {
    return moduleDefinition(module)?.permissionModule || module;
  }

  function permissionLevelFor(module: string): PermissionLevel {
    const permissionModule = permissionModuleFor(module);
    return currentPermissions.value[permissionModule] || '-';
  }

  function canAccess(module: ModuleName): boolean {
    const userAccess = userAccessFor(String(module));
    if (userAccess?.override === 'DENY') return false;
    if (userAccess?.override === 'GRANT') return true;
    const permission = permissionLevelFor(String(module));
    return permission !== '-';
  }

  function canWrite(module: ModuleName): boolean {
    const permission = permissionLevelFor(String(module));
    return permission === 'rw' || permission === 'w';
  }

  function hasFullAccess(module: ModuleName): boolean {
    const permission = permissionLevelFor(String(module));
    return permission === 'rw';
  }

  function getPermissionLevel(module: ModuleName): PermissionLevel {
    return permissionLevelFor(String(module));
  }

  function getAccessibleModules(): ModuleName[] {
    const modules: ModuleName[] = [
      'dashboard', 'production', 'warehouse', 'quality',
      'procurement', 'sales', 'hr', 'equipment', 'finance', 'system', 'analytics', 'scheduling', 'restaurant'
    ];
    return modules.filter(m => canAccess(m));
  }

  /**
   * 检查是否可以访问 SmartBI 模块
   * SmartBI 访问权限: 有 analytics/sales/finance 任一读权限即可
   */
  function canAccessSmartBI(): boolean {
    return canAccess('analytics') || canAccess('sales') || canAccess('finance');
  }

  /**
   * 检查是否有 SmartBI 写权限 (上传数据等)
   */
  function canWriteSmartBI(): boolean {
    return canWrite('analytics');
  }

  /**
   * AI 读写分离 (2026-07-23): 是否对任意模块拥有写权限 ('w' 或 'rw')。
   * AIQuery 双 tab 用它决定「操作」tab 是否渲染 — 纯只读账号只见「咨询」。
   */
  function hasAnyWriteAccess(): boolean {
    return Object.values(currentPermissions.value)
      .some((level) => level === 'rw' || level === 'w');
  }

  /**
   * Whether current role may see price fields (mirrors backend
   * `procurement:price:view` permission). Used by list views to hide entire
   * price columns for non-whitelisted roles (warehouse_manager etc).
   */
  const canViewPrice = computed((): boolean => PRICE_VIEW_ROLES.has(currentRole.value));

  function addLoadedRoute(routeName: string) {
    if (!loadedRoutes.value.includes(routeName)) {
      loadedRoutes.value.push(routeName);
    }
  }

  function clearLoadedRoutes() {
    loadedRoutes.value = [];
  }

  return {
    loadedRoutes,
    currentRole,
    currentPermissions,
    currentUserId,
    // DB-driven state (Phase 3)
    dbPermissions,
    isDbLoaded,
    dbLoadError,
    userModuleAccess,
    isUserModuleAccessLoaded,
    productionModuleRegistry: PRODUCTION_MODULE_REGISTRY,
    loadFromDb,
    applyUserModuleAccess,
    setRole,
    canAccess,
    canWrite,
    hasFullAccess,
    getPermissionLevel,
    getAccessibleModules,
    canAccessSmartBI,
    canWriteSmartBI,
    hasAnyWriteAccess,
    canViewPrice,
    addLoadedRoute,
    clearLoadedRoutes
  };
});

export { PERMISSION_MATRIX };
export type { ModulePermissions, PermissionLevel };
