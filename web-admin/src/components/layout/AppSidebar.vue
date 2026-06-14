<script setup lang="ts">
import { computed, ref, watch } from 'vue';
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
import { menuConfig, financeManagerMenu, type MenuItem } from './menuConfig';

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

// 路演 demo 租户 (DEMO_*) 侧边栏策展: 隐藏内部/无数据模块, 让可见模块都有充足数据。
// 工厂 demo (DEMO_FACTORY2, 源 F006 六扇门 卤味加工厂) 以生产报工链为核心 — 财务无凭证 →
// 藏 finance; 餐饮 demo (DEMO_REST) 无工厂销售订单 → 藏 sales。system 内部管理两业态都藏。
const DEMO_HIDE_MODULES_BY_TYPE: Record<string, string[]> = {
  RESTAURANT: ['sales', 'system'],
  // 财务模块已补数据 (ar_ap_transactions 204 / invoice 97 / payment 97 → 财务概览/应收应付/
  // 开票/收款 有数据) → 不再整组藏 finance, 只藏空的 reports/adjustments 子页 (见 PATHS)。
  FACTORY: ['quality', 'equipment', 'scheduling', 'system'],
};
// 路径前缀级隐藏 (按业态). 2026-06-14 DB ground-truth (每表 count) + headed 逐页核实:
//   工厂 (DEMO_FACTORY2, F006): 报工链是亮点 — 生产批次(104)/计划(133)/报工审批·工序投入产出·
//     人效双口径(production_reports 335)/BOM达成率/生产数据分析(fact_production_report 335)/
//     成品(30)/采购(30)/销售订单(97)/客户(16)/物料(73)/经营驾驶舱(agg_daily 合成 ¥710万)/
//     AI问答/进销存/员工(36) 都有数据 → 留。藏 (count=0): 物料需求/研发样品/备货看板(当日空)/
//     报损/盘点/经营分析hub(smart_bi_finance=0)/异常预警/车间报表(当日空)/成本分析(fact_cost_line=0)/
//     人效分析(数据与分析组, 与生产组人效双口径重复) + 数据管理/AI运维工具组。
//   餐饮 (DEMO_REST): 留 经营驾驶舱/经营分析hub/收入报表/AI问答/菜品分析/门店对比/价格预警/
//     配方/损耗。藏 AI体检(降级)/异常预警(空)/经营看板role-kpi(毛利计算异常)/平台口碑(无点评)/
//     ETL状态·菜品名称匹配·数据完整度(内部工具) + 数据管理/AI运维工具组。
const DEMO_HIDE_PATHS_BY_TYPE: Record<string, string[]> = {
  RESTAURANT: [
    '/workdesk', '/sales/finished-goods',
    '/smart-bi/health-report', '/analytics/alert-dashboard',
    '/restaurant/analytics/role-kpi', '/restaurant/analytics/platform',
    '/restaurant/data-completeness', '/restaurant/admin/etl-status', '/restaurant/admin/name-resolution',
    '/smart-bi/upload', '/smart-bi/query-templates', '/smart-bi/data-completeness',
    '/analytics/overview', '/smart-bi/food-kb-feedback', '/smart-bi/fallback-log',
    '/smart-bi/calibration',
  ],
  FACTORY: [
    '/hr/attendance', '/hr/departments', '/hr/work-types', '/hr/whitelist',
    '/sales/payment-requests', '/sales/returns',
    // Phase2/3 已补数据 → 不再隐藏: 报损/调整审批/财务报表/备货看板 + 异常预警(production_alerts
    // 8 条卤味生产异常: 猪舌出成率/掌中宝成本/卤猪蹄合格率/OEE — 才是 dashboard 真实源, 非 alert_events)
    // + 车间实时报表(shift 完工批次 created_at 到当日 → 4 产品今日产量)。
    // 仍隐藏 (2 项, 真需专用管道 / 本就是内部工具):
    '/smart-bi/analysis-hub',             // 经营分析hub: 财务tab "请选择数据源" 需完整 Excel 上传管道 (smart_bi_pg_excel_uploads 数据源选择); 且与经营驾驶舱功能重复 (旗舰分析页已覆盖)
    '/smart-bi/upload', '/smart-bi/query-templates', '/smart-bi/data-completeness',
    '/analytics/overview', '/smart-bi/food-kb-feedback', '/smart-bi/fallback-log',
    '/smart-bi/calibration',              // 以上 7 项 = 内部上传/查询模板/数据质量/AI运维工具, 非客户数据页 (评委看到反而困惑, 隐藏是对的 UX)
  ],
};
function isDemoTenant(factoryId: string | undefined): boolean {
  return /^DEMO_/.test(factoryId || '');
}
// WS6 reactivity fix: previously this ran in onMounted and bailed out with
// `if (!authStore.factoryId) return;`. If the sidebar mounted before `user`
// hydrated (factoryId still ''), the fetch never ran AND never retried →
// disabled modules stayed visible until a manual page refresh. Watching
// factoryId (immediate) re-runs the fetch the moment it becomes available,
// and re-runs again if the user/factory identity changes (re-login). Updates
// the reactive `disabledModuleCodes` ref so `disabledSidebarModules` (and thus
// `filteredMenu`) recompute automatically.
let disabledFetchToken = 0;
async function fetchDisabledModules(factoryId: string) {
  const token = ++disabledFetchToken;
  if (!factoryId) {
    disabledModuleCodes.value = [];
    return;
  }
  try {
    const res = await get(`/${factoryId}/config/disabled-modules`);
    // Guard against stale responses if factoryId changed mid-flight (re-login).
    if (token !== disabledFetchToken) return;
    if (res.success && Array.isArray(res.data)) {
      disabledModuleCodes.value = res.data;
    } else {
      disabledModuleCodes.value = [];
    }
  } catch {
    /* config not set up for this factory */
    if (token === disabledFetchToken) disabledModuleCodes.value = [];
  }
}
watch(
  () => authStore.factoryId,
  (factoryId) => { void fetchDisabledModules(factoryId); },
  { immediate: true },
);
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

// 菜单配置已抽到 ./menuConfig.ts (可单测) — MenuItem / menuConfig / financeManagerMenu 由顶部 import 引入

// 检查菜单项是否可见（基于角色限制 + 工厂类型限制）
//
// WS6 reactivity fix: read every reactive source EAGERLY up-front before any
// short-circuit, so Vue registers `filteredMenu`'s dependency on all of them on
// the very first evaluation. Previously the early `return false` branches and
// the `&&` short-circuit meant `permissionStore.canAccess(...)` /
// `permissionStore.currentRole` were sometimes never read for an item when
// auth/permission state was still stale on first render → that access path was
// never tracked → the menu could fail to re-evaluate when permissions finished
// loading, requiring a manual refresh. Reading them all unconditionally makes
// the computed depend on factoryType, the disabled-module set, currentRole and
// the (DB-or-fallback) permission level for the module — so any of them
// changing reliably re-runs the filter.
function canSeeMenuItem(item: MenuItem): boolean {
  const factoryType = authStore.factoryType;
  const disabledSet = disabledSidebarModules.value;
  const currentRole = permissionStore.currentRole;
  const canAccess = permissionStore.canAccess(item.module);

  // 路演 demo 租户策展: 隐藏内部/无数据模块 (按业态)
  if (isDemoTenant(authStore.factoryId)) {
    const hidePaths = DEMO_HIDE_PATHS_BY_TYPE[factoryType] || [];
    if (hidePaths.some(p => item.path === p || item.path.startsWith(p + '/'))) {
      return false;
    }
    if ((DEMO_HIDE_MODULES_BY_TYPE[factoryType] || []).includes(item.module)) {
      return false;
    }
  }

  if (item.hideForFactoryTypes?.includes(factoryType)) {
    return false;
  }
  // R19: Canvas module enable/disable — hide disabled modules
  if (disabledSet.has(item.module)) {
    return false;
  }
  if (!item.roles || item.roles.length === 0) {
    return canAccess;
  }
  return item.roles.includes(currentRole) && canAccess;
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
