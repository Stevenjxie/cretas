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
  restaurant: PermissionLevel;
  rd: PermissionLevel;
}

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
    rd: 'rw'
  },

  // Level 10 - 职能部门经理
  hr_admin: {
    dashboard: 'r', production: '-', warehouse: '-', quality: '-',
    procurement: '-', sales: '-', hr: 'rw', equipment: '-',
    finance: '-', system: 'r', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },
  procurement_manager: {
    dashboard: 'r', production: 'r', warehouse: 'r', quality: '-',
    procurement: 'rw', sales: '-', hr: '-', equipment: '-',
    finance: 'r', system: '-', analytics: '-', scheduling: '-', restaurant: '-', rd: '-'
  },
  sales_manager: {
    dashboard: 'r', production: 'r', warehouse: 'r', quality: '-',
    procurement: '-', sales: 'rw', hr: '-', equipment: '-',
    finance: 'r', system: '-', analytics: 'r', scheduling: '-', restaurant: '-',
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
    finance: 'rw', system: '-', analytics: 'rw', scheduling: '-', restaurant: '-',
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
  restaurant_manager: {
    dashboard: 'r', production: '-', warehouse: '-', quality: '-',
    procurement: 'r', sales: '-', hr: '-', equipment: '-',
    finance: 'r', system: '-', analytics: 'r', scheduling: '-', restaurant: 'rw', rd: '-'
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
    finance: 'r', system: '-', analytics: 'r', scheduling: 'r', restaurant: 'r',
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
  async function loadFromDb(): Promise<void> {
    const now = Date.now();
    if (now - lastLoadTs.value < LOAD_DEBOUNCE_MS && isDbLoaded.value) return;
    lastLoadTs.value = now;
    dbLoadError.value = null;
    try {
      const [l1Rows, l2Map] = await Promise.all([
        getPlatformPermissions(),
        currentFactoryId.value
          ? getFactoryOverride(currentFactoryId.value).catch(() => ({} as RoleModuleOverride))
          : Promise.resolve({} as RoleModuleOverride),
      ]);
      dbPermissions.value = mergeLayers(l1Rows, l2Map, currentRole.value);
      if (currentFactoryId.value && currentUserId.value) {
        try {
          const l4Rows = await getUserModuleAccess(currentFactoryId.value, currentUserId.value, { silent: true });
          applyUserModuleAccess(l4Rows);
        } catch {
          // L4 user-specific overrides are managed from System/Canvas. Business roles
          // can run on L1/L2 permissions without read access to that admin matrix.
          userModuleAccess.value = {};
          isUserModuleAccessLoaded.value = true;
        }
      }
      isDbLoaded.value = true;
    } catch (e) {
      dbLoadError.value = (e as Error)?.message || 'Failed to load permissions';
      isDbLoaded.value = false;
      dbPermissions.value = null;
      userModuleAccess.value = {};
      isUserModuleAccessLoaded.value = false;
    }
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
    canViewPrice,
    addLoadedRoute,
    clearLoadedRoutes
  };
});

export { PERMISSION_MATRIX };
export type { ModulePermissions, PermissionLevel };
