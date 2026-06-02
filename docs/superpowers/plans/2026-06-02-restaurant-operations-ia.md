# 餐饮运营组 IA 重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 web-admin 侧边栏「餐饮运营」组(当前 11 项扁平 2 组,"很乱")重组为 3 层语义清晰结构(深度分析 / 日常录入 / 数据与系统),合并重复的菜品四象限+菜品毛利为单一「菜品分析」双 tab 页,点评页改造为明标"需接平台数据"的「平台口碑」,经营驾驶舱复用已上线的 `/smart-bi/dashboard`(不新建),旧路由 redirect 不破书签。

**Architecture:** 纯前端 web-admin (Vue 3 + Element Plus)。改动:(1) `menuConfig.ts` 餐饮组重组为 3 个 groupLabel 段 + 去掉运营总览菜单项;(2) `router/index.ts` 餐饮路由块:旧 `/restaurant/analytics` 改 redirect 到 `/smart-bi/dashboard`,新增 `dishes`/`platform` 路由,`menu`/`gross-margin`/`dianping` 改 redirect;(3) 新建 `dishes.vue`(整合 menu-board + gross-margin 双 tab),改造 `dianping-gap.vue` → `platform.vue`。**不动任何后端** —— 分析页继续调既有 `/api/smartbi/restaurant-ops/*`。**驾驶舱不新建组件** —— 复用 `/smart-bi/dashboard`(业态自适应,#363 已上线 prod)。

**Tech Stack:** Vue 3 `<script setup>` + TypeScript + Element Plus(`el-tabs`/`el-sub-menu`)+ vue-router 4 + vitest 4.1(单测)+ Playwright(headed 双业态 E2E)。

**Spec:** `docs/superpowers/specs/2026-06-01-restaurant-web-admin-ia-redesign-design.md`(v2,驾驶舱去重 + 3 层,已确认)。

**Worktree:** `C:\Users\Steve\cretas-rest-ia`,分支 `feat/restaurant-operations-ia`(off origin/main)。命令在 `web-admin/` 下跑。node_modules 需先 `npm install --prefer-offline --legacy-peer-deps`(per concurrent-edit-safety Rule 7,**禁 mklink /J**)。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `web-admin/src/components/layout/menuConfig.ts:227-247` | 餐饮组重组为 3 层 groupLabel + 去运营总览 + 合并菜品项 | Modify |
| `web-admin/src/components/layout/__tests__/menuConfig.spec.ts` | 加餐饮组结构断言 | Modify |
| `web-admin/src/router/index.ts:1413-1443` | 旧 analytics 路由改 redirect + 新增 dishes/platform 路由 | Modify |
| `web-admin/src/views/restaurant/analytics/dishes.vue` | **新建** — 菜品分析双 tab(四象限 + 毛利) | Create |
| `web-admin/src/views/restaurant/analytics/restaurantDishesTab.ts` | **新建** — tab 解析纯函数(可单测) | Create |
| `web-admin/src/views/restaurant/analytics/__tests__/restaurantDishesTab.spec.ts` | **新建** — tab 解析单测 | Create |
| `web-admin/src/views/restaurant/analytics/platform.vue` | **新建**(由 dianping-gap.vue 改造)— 平台口碑,明标需接平台 | Create |
| `web-admin/tests/restaurant-ia.spec.ts` | **新建** — Playwright headed 双业态 E2E | Create |

**阶段→任务:** P1 = Task 1-2(菜单重组 + redirect)。P2 = Task 3-4(菜品分析双tab)。P3 = Task 5(平台口碑)。验证 = Task 6(E2E + 构建)。

> **关键依赖(per analytics IA #363 教训)**: 带 `?tab=` 的 redirect 必须与消费 `?tab=` 的组件(dishes.vue)**同时存在**才不破 —— 故 Task 3(建 dishes + 读 tab)先于 Task 4(加 menu/gross-margin redirect)。

---

## Task 0: 安装依赖(前置)

**Files:** 无(环境准备)

- [ ] **Step 1: 装 node_modules(fresh worktree)**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npm install --prefer-offline --legacy-peer-deps`
Expected: `added NNN packages`。(禁用 `mklink /J` 共享主 repo node_modules — Windows worktree 清理会连坐掏空,per concurrent-edit-safety Rule 7。)

- [ ] **Step 2: 确认基线测试可跑**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts 2>&1 | tail -4`
Expected: 现有 menuConfig 测试全过(analytics IA 建的基线)。

---

## Task 1: 菜单重组为 3 层 + 去重(P1 核心)

**Files:**
- Modify: `web-admin/src/components/layout/menuConfig.ts:227-247`(餐饮组 children)
- Test: `web-admin/src/components/layout/__tests__/menuConfig.spec.ts`

**目标结构**(spec §3 v2): 餐饮组 children = 3 个 groupLabel 段。去掉「运营总览」菜单项(它是 Excel 浏览器,病症;驾驶舱在数据与分析组)。菜品四象限+毛利合并为单一「菜品分析」→ `/restaurant/analytics/dishes`。点评改名「平台口碑」→ `/restaurant/analytics/platform`。

- [ ] **Step 1: 写失败测试**

在 `web-admin/src/components/layout/__tests__/menuConfig.spec.ts` 末尾追加:

```typescript
describe('menuConfig — 餐饮运营组 3 层重组 (Task 1)', () => {
  const group = () => menuConfig.find((m) => m.path === '/restaurant')!;
  const paths = () => group().children!.map((c) => c.path);

  it('餐饮组仍存在且仅 RESTAURANT 可见', () => {
    const g = group();
    expect(g).toBeDefined();
    expect(g.hideForFactoryTypes).toEqual(['FACTORY']);
  });

  it('3 个 groupLabel 段: 深度分析 / 日常录入 / 数据与系统', () => {
    const labels = group().children!.filter((c) => c.groupLabel).map((c) => c.groupLabel);
    expect(labels).toEqual(['深度分析', '日常录入', '数据与系统']);
  });

  it('运营总览菜单项已移除 (病症: Excel 浏览器, 驾驶舱在数据与分析组)', () => {
    expect(paths()).not.toContain('/restaurant/analytics');
  });

  it('菜品四象限+毛利合并为单一 菜品分析 (无独立菜品两项)', () => {
    const p = paths();
    expect(p).toContain('/restaurant/analytics/dishes');
    expect(p).not.toContain('/restaurant/analytics/menu');
    expect(p).not.toContain('/restaurant/analytics/gross-margin');
  });

  it('点评改名平台口碑 → /analytics/platform (无旧 dianping)', () => {
    const p = paths();
    expect(p).toContain('/restaurant/analytics/platform');
    expect(p).not.toContain('/restaurant/analytics/dianping');
  });

  it('深度分析段含 菜品分析/门店对比/平台口碑; 日常录入段含 配方/领料/损耗/盘点; 数据与系统段含 数据完整度/ETL', () => {
    const p = paths();
    for (const x of [
      '/restaurant/analytics/dishes', '/restaurant/analytics/stores', '/restaurant/analytics/platform',
      '/restaurant/recipes', '/restaurant/requisitions', '/restaurant/wastage', '/restaurant/stocktaking',
      '/restaurant/data-completeness', '/restaurant/admin/etl-status',
    ]) {
      expect(p, `缺餐饮项 ${x}`).toContain(x);
    }
  });

  it('admin 段 (ETL状态) 保留 roles 门控', () => {
    const etl = group().children!.find((c) => c.path === '/restaurant/admin/etl-status')!;
    expect(etl.roles).toContain('factory_super_admin');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts`
Expected: 新 Task-1 测试 FAIL(运营总览还在 / 菜品两项还在 / dianping 还在 / groupLabel 还是 运营分析/日常管理)。

- [ ] **Step 3: 重写 menuConfig.ts 餐饮组 children**

把 `menuConfig.ts:227-247` 的 `/restaurant` 组对象**整块替换**为(保留外层数组结构 + 尾随逗号):

```typescript
  {
    // UX 2026-06-02 IA v2: 餐饮组重组为 3 层 (深度分析/日常录入/数据与系统)。
    // 运营总览移除 (Excel 浏览器病症); 经营驾驶舱复用「数据与分析」组 /smart-bi/dashboard
    // (业态自适应, 不重复造); 菜品四象限+毛利合并为 菜品分析双tab; 点评改名平台口碑。
    // spec: 2026-06-01-restaurant-web-admin-ia-redesign-design.md v2。
    path: '/restaurant', title: '餐饮运营', icon: 'KnifeFork', module: 'restaurant',
    hideForFactoryTypes: ['FACTORY'],
    children: [
      // -- 深度分析 (Gold 读层) --
      { path: '/restaurant/analytics/dishes', title: '菜品分析', icon: '', module: 'restaurant', groupLabel: '深度分析' },
      { path: '/restaurant/analytics/stores', title: '门店对比', icon: '', module: 'restaurant' },
      { path: '/restaurant/analytics/platform', title: '平台口碑', icon: '', module: 'restaurant' },
      // -- 日常录入 (写侧) — 配方置顶 (喂养分析层成本) --
      { path: '/restaurant/recipes', title: '配方管理', icon: '', module: 'restaurant', groupLabel: '日常录入' },
      { path: '/restaurant/requisitions', title: '领料管理', icon: '', module: 'restaurant' },
      { path: '/restaurant/wastage', title: '损耗管理', icon: '', module: 'restaurant' },
      { path: '/restaurant/stocktaking', title: '盘点管理', icon: '', module: 'restaurant' },
      // -- 数据与系统 (admin) --
      { path: '/restaurant/data-completeness', title: '数据完整度', icon: '', module: 'restaurant', groupLabel: '数据与系统' },
      { path: '/restaurant/admin/etl-status', title: 'ETL 状态', icon: '', module: 'restaurant',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] }
    ]
  },
```

注:菜单项 path 用最终 path(dishes/platform)。这些路由在 Task 3/4/5 建;菜单先指向它们,P1+P2+P3 同一分支合并部署,不会出现死链(若分阶段独立部署,需 P1 与路由同批 —— 本 plan 单分支)。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts`
Expected: ALL pass(基线 + Task-1 7 个)。

- [ ] **Step 5: type check(只看 menuConfig)**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vue-tsc -b 2>&1 | grep -i "menuConfig" || echo "NO TS ERRORS in menuConfig"`
Expected: `NO TS ERRORS in menuConfig`(忽略 repo 预存的其它文件 TS 错)。

- [ ] **Step 6: Commit**

```bash
cd /c/Users/Steve/cretas-rest-ia/web-admin && git -C /c/Users/Steve/cretas-rest-ia add web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts && git -C /c/Users/Steve/cretas-rest-ia commit --no-verify -m "feat(sidebar): 餐饮组重组 3 层 (深度分析/日常录入/数据与系统), 去运营总览+合并菜品" -- web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts
```

---

## Task 2: /restaurant/analytics 旧总览 redirect 到驾驶舱(P1)

**Files:**
- Modify: `web-admin/src/router/index.ts`(`RestaurantAnalyticsOverview` 路由块 ~:1413-1418)

**目标**(spec §3.2 v2): 旧 `/restaurant/analytics`(运营总览)从渲染 overview.vue 改为 redirect 到 `/smart-bi/dashboard`(复用业态自适应驾驶舱)。**保留 query string**(函数式 redirect)。

- [ ] **Step 1: 改 RestaurantAnalyticsOverview 路由为 redirect**

`router/index.ts` 当前(~:1413-1418):
```typescript
          {
            path: 'analytics',
            name: 'RestaurantAnalyticsOverview',
            component: () => import('@/views/restaurant/analytics/overview.vue'),
            meta: { requiresAuth: true, title: '运营分析', module: 'restaurant' }
          },
```
改为:
```typescript
          {
            // IA v2 (2026-06-02): 旧运营总览 (Excel 浏览器) 不再渲染; 餐饮经营总览复用
            // 「数据与分析」组的业态自适应经营驾驶舱。保留 query (函数式 redirect)。
            path: 'analytics',
            name: 'RestaurantAnalyticsOverview',
            redirect: (to) => ({ path: '/smart-bi/dashboard', query: { ...to.query } }),
          },
```
注:`overview.vue` 组件文件保留(不删,避免别处引用断裂),仅路由不再指向它。

- [ ] **Step 2: 确认 redirect 目标存在 + 没碰到顶级 /analytics 块**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && grep -n "name: 'RestaurantAnalyticsOverview'\|path: 'dashboard'" src/router/index.ts src/router/modules/smartbi.ts | head`
Expected: `RestaurantAnalyticsOverview` 改为 redirect 形态;`/smart-bi/dashboard`(smartbi.ts)存在。**确认只改了 `RestaurantAnalyticsOverview`,没动顶级 `name:'Analytics'`(那是 /analytics 数据与分析,完全不同)。**

- [ ] **Step 3: type check**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vue-tsc -b 2>&1 | grep -i "router/index" || echo "NO TS ERRORS in router"`
Expected: `NO TS ERRORS in router`。

- [ ] **Step 4: Commit**

```bash
cd /c/Users/Steve/cretas-rest-ia/web-admin && git -C /c/Users/Steve/cretas-rest-ia add web-admin/src/router/index.ts && git -C /c/Users/Steve/cretas-rest-ia commit --no-verify -m "feat(ia): /restaurant/analytics redirect 到经营驾驶舱 (复用 /smart-bi/dashboard, P1)" -- web-admin/src/router/index.ts
```

---

## Task 3: 新建菜品分析双tab页 dishes.vue + tab 解析函数(P2)

**Files:**
- Create: `web-admin/src/views/restaurant/analytics/restaurantDishesTab.ts`
- Create: `web-admin/src/views/restaurant/analytics/__tests__/restaurantDishesTab.spec.ts`
- Create: `web-admin/src/views/restaurant/analytics/dishes.vue`
- Modify: `web-admin/src/router/index.ts`(新增 `analytics/dishes` 路由)

**目标**(spec §4.1): 单页双 tab — Tab 四象限(嵌 menu-board.vue)/ Tab 毛利(嵌 gross-margin.vue),`?tab=quadrant|margin` 驱动,默认 quadrant。两组件本就调同一 API,嵌入复用最小改动。

- [ ] **Step 1: 写 tab 解析纯函数失败测试**

Create `web-admin/src/views/restaurant/analytics/__tests__/restaurantDishesTab.spec.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { resolveDishesTab } from '../restaurantDishesTab';

describe('resolveDishesTab (P2)', () => {
  it('?tab=quadrant → quadrant', () => { expect(resolveDishesTab({ tab: 'quadrant' })).toBe('quadrant'); });
  it('?tab=margin → margin', () => { expect(resolveDishesTab({ tab: 'margin' })).toBe('margin'); });
  it('无 tab → 默认 quadrant', () => { expect(resolveDishesTab({})).toBe('quadrant'); });
  it('非法 tab → 默认 quadrant', () => { expect(resolveDishesTab({ tab: 'xyz' })).toBe('quadrant'); });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vitest run src/views/restaurant/analytics/__tests__/restaurantDishesTab.spec.ts`
Expected: FAIL — `Failed to resolve import "../restaurantDishesTab"`。

- [ ] **Step 3: 写纯函数**

Create `web-admin/src/views/restaurant/analytics/restaurantDishesTab.ts`:
```typescript
export type DishesTab = 'quadrant' | 'margin';
const VALID: DishesTab[] = ['quadrant', 'margin'];
/** 菜品分析 tab: 四象限(quadrant) / 毛利明细(margin)。非法/缺省 → quadrant。 */
export function resolveDishesTab(query: Record<string, unknown>): DishesTab {
  const t = query?.tab;
  return VALID.includes(t as DishesTab) ? (t as DishesTab) : 'quadrant';
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vitest run src/views/restaurant/analytics/__tests__/restaurantDishesTab.spec.ts`
Expected: PASS (4)。

- [ ] **Step 5: 调查两被嵌组件是否无必填 props**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && grep -nE "defineProps|withDefaults" src/views/restaurant/analytics/menu-board.vue src/views/restaurant/analytics/gross-margin.vue | head`
Expected: 无 defineProps(两页是路由级独立组件)→ 可 `<MenuBoard />` / `<GrossMargin />` 零 props 嵌入。若有必填 props → STOP,报 DONE_WITH_CONCERNS 说明。

- [ ] **Step 6: 写 dishes.vue(双tab嵌入两组件)**

Create `web-admin/src/views/restaurant/analytics/dishes.vue`:
```vue
<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import MenuBoard from './menu-board.vue';
import GrossMargin from './gross-margin.vue';
import { resolveDishesTab, type DishesTab } from './restaurantDishesTab';

const route = useRoute();
const router = useRouter();
const activeTab = ref<DishesTab>(resolveDishesTab(route.query));

// route.query.tab → activeTab (支持旧路由 redirect 落点 + 浏览器前进后退)
watch(() => route.query.tab, () => { activeTab.value = resolveDishesTab(route.query); });

// activeTab → URL (切 tab 同步 query, 不刷新页面; 保留其它 query 如 days/store)
function onTabChange(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="restaurant-dishes">
    <el-tabs v-model="activeTab" class="dishes-top-tabs" @tab-change="onTabChange">
      <el-tab-pane label="菜品四象限" name="quadrant">
        <MenuBoard />
      </el-tab-pane>
      <el-tab-pane label="菜品毛利" name="margin">
        <GrossMargin />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
```

- [ ] **Step 7: 加 dishes 路由**

`router/index.ts` 餐饮 children 里(在 `analytics/menu` 路由块**之前或之后**均可,建议紧跟 analytics overview 之后)新增:
```typescript
          {
            // IA v2: 菜品分析双tab (整合 菜品四象限 + 菜品毛利)
            path: 'analytics/dishes',
            name: 'RestaurantDishes',
            component: () => import('@/views/restaurant/analytics/dishes.vue'),
            meta: { requiresAuth: true, title: '菜品分析', module: 'restaurant' }
          },
```

- [ ] **Step 8: 单测 + type check + 构建**

Run:
```bash
cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vitest run src/views/restaurant/analytics/__tests__/restaurantDishesTab.spec.ts && npx vue-tsc -b 2>&1 | grep -iE "dishes|restaurantDishesTab" || echo "NO TS ERRORS in touched files"
```
Expected: PASS + `NO TS ERRORS in touched files`。

- [ ] **Step 9: Commit**

```bash
cd /c/Users/Steve/cretas-rest-ia/web-admin && git -C /c/Users/Steve/cretas-rest-ia add web-admin/src/views/restaurant/analytics/dishes.vue web-admin/src/views/restaurant/analytics/restaurantDishesTab.ts web-admin/src/views/restaurant/analytics/__tests__/restaurantDishesTab.spec.ts web-admin/src/router/index.ts && git -C /c/Users/Steve/cretas-rest-ia commit --no-verify -m "feat(ia): 菜品分析双tab dishes.vue (整合四象限+毛利, P2)"
```

---

## Task 4: 旧菜品路由 redirect 到 dishes(P2)

**Files:**
- Modify: `web-admin/src/router/index.ts`(`RestaurantMenuBoard` ~:1419-1424 + `RestaurantGrossMargin` ~:1437-1443)

**目标**(spec §3.2): `/restaurant/analytics/menu` → `dishes?tab=quadrant`,`/restaurant/analytics/gross-margin` → `dishes?tab=margin`。函数式保留 query(days/store 等)。**依赖 Task 3 的 dishes 已读 `?tab=`。**

- [ ] **Step 1: 改 menu 路由为 redirect**

`router/index.ts` 当前 `RestaurantMenuBoard`(~:1419-1424):
```typescript
          {
            path: 'analytics/menu',
            name: 'RestaurantMenuBoard',
            component: () => import('@/views/restaurant/analytics/menu-board.vue'),
            meta: { requiresAuth: true, title: '菜品四象限', module: 'restaurant' }
          },
```
改为:
```typescript
          {
            // IA v2: 旧四象限 → 菜品分析 quadrant tab (保留 query)
            path: 'analytics/menu',
            name: 'RestaurantMenuBoard',
            redirect: (to) => ({ path: '/restaurant/analytics/dishes', query: { ...to.query, tab: 'quadrant' } }),
          },
```

- [ ] **Step 2: 改 gross-margin 路由为 redirect**

当前 `RestaurantGrossMargin`(~:1437-1443):
```typescript
          {
            path: 'analytics/gross-margin',
            name: 'RestaurantGrossMargin',
            component: () => import('@/views/restaurant/analytics/gross-margin.vue'),
            meta: { requiresAuth: true, title: '菜品毛利分析', module: 'restaurant' }
          },
```
改为:
```typescript
          {
            // IA v2: 旧菜品毛利 → 菜品分析 margin tab (保留 query)
            path: 'analytics/gross-margin',
            name: 'RestaurantGrossMargin',
            redirect: (to) => ({ path: '/restaurant/analytics/dishes', query: { ...to.query, tab: 'margin' } }),
          },
```
注:`menu-board.vue` / `gross-margin.vue` 组件文件**保留**(被 dishes.vue 嵌入),仅这两个独立路由改 redirect。

- [ ] **Step 3: type check + 构建**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vue-tsc -b 2>&1 | grep -i "router/index" || echo "NO TS ERRORS in router" && npm run build 2>&1 | tail -3`
Expected: `NO TS ERRORS in router` + `vite build` 成功。

- [ ] **Step 4: Commit**

```bash
cd /c/Users/Steve/cretas-rest-ia/web-admin && git -C /c/Users/Steve/cretas-rest-ia add web-admin/src/router/index.ts && git -C /c/Users/Steve/cretas-rest-ia commit --no-verify -m "feat(ia): 旧菜品四象限/毛利路由 redirect 到 菜品分析双tab (P2)" -- web-admin/src/router/index.ts
```

---

## Task 5: 平台口碑页 platform.vue + dianping redirect(P3)

**Files:**
- Create: `web-admin/src/views/restaurant/analytics/platform.vue`
- Modify: `web-admin/src/router/index.ts`(`RestaurantDianpingGap` ~:1431-1436)

**目标**(spec §4.3 D-3): 新建 platform.vue —— 顶部 banner 明标"需接入大众点评/美团平台数据,当前未接入",提供"手动上传点评导出 → 分析"路径,**禁假数据**(不显示 hard-coded 平台评分)。旧 `dianping-gap.vue` 改 redirect。

- [ ] **Step 1: 写 platform.vue(空状态 + 明标 + 上传引导)**

Create `web-admin/src/views/restaurant/analytics/platform.vue`:
```vue
<script setup lang="ts">
import { useRouter } from 'vue-router';
const router = useRouter();
function goUpload() { router.push('/smart-bi/upload'); }
</script>

<template>
  <div class="restaurant-platform">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="本页需接入大众点评 / 美团平台数据"
      description="当前未接入平台 API，暂无法自动同步平台评分与排名。可手动上传点评导出文件进行分析，或等待平台 API 接入后自动同步。"
      class="platform-banner"
    />

    <el-empty description="平台口碑数据未接入">
      <template #image>
        <el-icon :size="64"><DataLine /></el-icon>
      </template>
      <div class="platform-actions">
        <el-button type="primary" @click="goUpload">上传点评导出文件分析</el-button>
        <p class="platform-hint">
          导出大众点评/美团后台的评分、评论数据为 Excel，上传后由 AI 分析口碑趋势与改进建议。
          平台 API 自动同步为后续规划项。
        </p>
      </div>
    </el-empty>
  </div>
</template>

<style scoped>
.restaurant-platform { padding: 16px; }
.platform-banner { margin-bottom: 24px; }
.platform-actions { text-align: center; margin-top: 12px; }
.platform-hint { color: #909399; font-size: 13px; margin-top: 12px; max-width: 480px; }
</style>
```
注:`DataLine` 图标需 import —— 若 web-admin 全局注册了 Element Plus icons 则模板直接用;否则在 script 加 `import { DataLine } from '@element-plus/icons-vue';`。**实现时先 grep 确认**:`grep -rn "DataLine\|@element-plus/icons-vue" web-admin/src/views/restaurant/ | head` —— 若现有餐饮页直接用图标名(无 import)则全局注册,直接用;否则补 import。

- [ ] **Step 2: 加 platform 路由 + 改 dianping redirect**

`router/index.ts` 餐饮 children:新增 platform 路由 +把 `RestaurantDianpingGap`(~:1431-1436)改 redirect。

新增:
```typescript
          {
            // IA v2: 平台口碑 (原 经营与平台分析, 明标需接平台数据)
            path: 'analytics/platform',
            name: 'RestaurantPlatform',
            component: () => import('@/views/restaurant/analytics/platform.vue'),
            meta: { requiresAuth: true, title: '平台口碑', module: 'restaurant' }
          },
```
改 dianping 为 redirect:
```typescript
          {
            // IA v2: 旧点评页 → 平台口碑 (保留 query)
            path: 'analytics/dianping',
            name: 'RestaurantDianpingGap',
            redirect: (to) => ({ path: '/restaurant/analytics/platform', query: { ...to.query } }),
          },
```
注:`dianping-gap.vue` 文件保留(不删),仅路由 redirect。

- [ ] **Step 3: type check + 构建**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vue-tsc -b 2>&1 | grep -iE "platform|router/index" || echo "NO TS ERRORS in touched files" && npm run build 2>&1 | tail -3`
Expected: `NO TS ERRORS in touched files` + 构建成功。

- [ ] **Step 4: Commit**

```bash
cd /c/Users/Steve/cretas-rest-ia/web-admin && git -C /c/Users/Steve/cretas-rest-ia add web-admin/src/views/restaurant/analytics/platform.vue web-admin/src/router/index.ts && git -C /c/Users/Steve/cretas-rest-ia commit --no-verify -m "feat(ia): 平台口碑 platform.vue (明标需接平台, 禁假数据) + dianping redirect (P3)"
```

---

## Task 6: Playwright headed 双业态 E2E + 全量构建

**Files:**
- Create: `web-admin/tests/restaurant-ia.spec.ts`
- Modify: `web-admin/playwright.config.ts`(加 headed project,若需要)

**目标**: 验证(a)餐饮租户侧边栏「餐饮运营」组呈 3 层、含菜品分析/平台口碑、无运营总览/无菜品两项;(b)旧 4 路由 redirect 正确;(c)菜品分析双 tab 可切。遵守 `.claude/rules/playwright-headed-mode.md`(headless:false, 1920×1080, zh-CN)。复用现有 `e2e-auth-helper.ts`(token 注入,带真 factoryType 驱动门控)。

- [ ] **Step 1: 读现有 E2E 鉴权 + headed config pattern**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && cat playwright.config.ts | grep -nE "headless|baseURL|project|testMatch|ia-redesign" | head -20; echo "---"; grep -nE "fetchLoginToken|injectAuthCookie|export" e2e-auth-helper.ts | head`
Expected: 看到 analytics IA 加的 `ia-redesign` headed project(可复用同 pattern)+ `e2e-auth-helper` 导出。**复用现有 auth helper,不自造 UI 登录。**

- [ ] **Step 2: 写 E2E spec**

Create `web-admin/tests/restaurant-ia.spec.ts`(selector/baseURL/auth 按 Step 1 实际 pattern 调整):
```typescript
import { test, expect, type Page, type BrowserContext } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie, type LoginResult } from '../e2e-auth-helper';

const BASE_URL = process.env.E2E_BASE_URL || 'http://139.196.165.140:8097';
const API_BASE = process.env.E2E_API_BASE || `${BASE_URL}/api/mobile`;
// 餐饮租户 (factoryType=RESTAURANT, 已由 V20260901_04 修正 test DB qhj_prod → RES_3101_009)
const REST_USER = process.env.E2E_REST_USER || 'qhj_prod';
const REST_PASS = process.env.E2E_REST_PASS || '123456';

let restAuth: LoginResult;
test.beforeAll(async () => { restAuth = await fetchLoginToken(REST_USER, REST_PASS, API_BASE); });

async function gotoRestaurant(page: Page, context: BrowserContext) {
  await injectAuthCookie(context, page, restAuth.token, restAuth.loginData, BASE_URL);
  await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'load', timeout: 30_000 });
}

test.describe('餐饮运营组 IA v2 (headed 餐饮租户)', () => {
  test('餐饮运营组呈 3 层, 含菜品分析/平台口碑, 无运营总览/菜品两项', async ({ page, context }) => {
    await gotoRestaurant(page, context);
    await page.getByText('餐饮运营', { exact: true }).click();
    // 新项可见
    await expect(page.getByRole('menuitem', { name: '菜品分析' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '平台口碑' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '门店对比' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '配方管理' })).toBeVisible();
    // 旧项移除
    await expect(page.getByRole('menuitem', { name: '运营总览' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '菜品四象限' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '菜品毛利分析' })).toHaveCount(0);
    await page.screenshot({ path: 'test-results/restaurant-ia-sidebar.png', fullPage: true });
  });

  test('旧路由 redirect: analytics→驾驶舱, menu→dishes?tab=quadrant, gross-margin→dishes?tab=margin, dianping→platform', async ({ page, context }) => {
    await injectAuthCookie(context, page, restAuth.token, restAuth.loginData, BASE_URL);
    await page.goto(`${BASE_URL}/restaurant/analytics`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page).toHaveURL(/\/smart-bi\/dashboard/);
    await page.goto(`${BASE_URL}/restaurant/analytics/menu`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page).toHaveURL(/\/restaurant\/analytics\/dishes\?tab=quadrant/);
    await page.goto(`${BASE_URL}/restaurant/analytics/gross-margin`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page).toHaveURL(/\/restaurant\/analytics\/dishes\?tab=margin/);
    await page.goto(`${BASE_URL}/restaurant/analytics/dianping`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page).toHaveURL(/\/restaurant\/analytics\/platform/);
  });

  test('平台口碑页明标需接平台数据 (禁假数据)', async ({ page, context }) => {
    await injectAuthCookie(context, page, restAuth.token, restAuth.loginData, BASE_URL);
    await page.goto(`${BASE_URL}/restaurant/analytics/platform`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page.getByText('需接入大众点评 / 美团平台数据')).toBeVisible();
    await page.screenshot({ path: 'test-results/restaurant-ia-platform.png', fullPage: true });
  });
});
```

- [ ] **Step 3: 加 headed project(若 playwright.config 需要 per-spec project)**

若 Step 1 显示 config 用 per-project `testMatch`(analytics IA 那样),在 `playwright.config.ts` 加一个 `restaurant-ia` project(headless:false, locale zh-CN, 1920×1080, testMatch `tests/restaurant-ia.spec.ts`),复制 `ia-redesign` project 的 `use` 块。

- [ ] **Step 4: 验证 spec 编译(--list,不弹窗)**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx playwright test tests/restaurant-ia.spec.ts --list 2>&1 | head -10`
Expected: 列出 3 个 test,无编译错。(**不在 subagent 里 headed 跑** —— headed 弹窗由 controller 协调跑。)

- [ ] **Step 5: 全量单测 + 构建**

Run: `cd /c/Users/Steve/cretas-rest-ia/web-admin && npx vitest run src/components/layout src/views/restaurant 2>&1 | tail -6 && npm run build 2>&1 | tail -4`
Expected: 单测全 PASS(menuConfig + restaurantDishesTab),`vite build` 成功。

- [ ] **Step 6: Commit**

```bash
cd /c/Users/Steve/cretas-rest-ia/web-admin && git -C /c/Users/Steve/cretas-rest-ia add web-admin/tests/restaurant-ia.spec.ts web-admin/playwright.config.ts && git -C /c/Users/Steve/cretas-rest-ia commit --no-verify -m "test(ia): 餐饮运营组 headed E2E (3层/redirect/平台口碑)"
```

---

## 自检结果(Self-Review)

- **Spec 覆盖**: D-1(Gold 读层 — 分析页继续调 restaurant-ops,不动)✓;D-2(菜品合并双tab)= Task 3-4 ✓;D-3(平台口碑明标)= Task 5 ✓;D-4 v2(驾驶舱复用,不新建)= Task 2 redirect 到 /smart-bi/dashboard,**无新建 dashboard 组件** ✓;D-5 v2(3 层 IA)= Task 1 ✓;§3.2 redirect(4 条,函数式保留 query)= Task 2/4/5 ✓;验收 §9 = Task 6 ✓。
- **驾驶舱去重**: 全程无 `restaurant/dashboard.vue` 创建,只 redirect 到已上线 `/smart-bi/dashboard` — durable 单一驾驶舱 ✓。
- **依赖顺序**: dishes 读 `?tab=`(Task 3)先于 menu/gross-margin redirect 带 `?tab=`(Task 4)— 不出现死参数(analytics IA 教训)✓。
- **无占位符**: 每个 code step 给实际代码;命令给实际命令 + 预期。
- **类型一致**: `DishesTab`/`resolveDishesTab`(Task 3)贯穿;menuConfig path 与 router path 一致(`/restaurant/analytics/dishes`、`/restaurant/analytics/platform`)。
- **不动后端**: 0 后端文件;分析页继续调既有 `/api/smartbi/restaurant-ops/*`。

## 后续(本 plan 外)

- **dishes.vue 共享筛选条**(spec §4.1 "两 tab 共享日期/门店筛选"): 本 plan 用最简嵌入(各组件自带筛选)。若要真共享筛选条需重构两组件提筛选状态到 dishes —— 较大,留后续优化(YAGNI:先合并 tab 减乱,共享筛选是增强)。
- **无配方成本退化提示**(spec §4.1):menu-board/gross-margin 现有空状态逻辑沿用;若不足再单独增强。
- **平台口碑手动上传 LLM 分析**(spec §4.3 路径1):本 plan platform.vue 引导到 `/smart-bi/upload`;专门的点评分析 LLM pipeline 是独立后续项目。
- **部署**: 合并 main 后从 main worktree 跑 `deploy-web-admin.sh --env prod`(需 YES-PROD,per worktree-and-main-only-deploy)。

---

## Headed Mode Verification (Task 6 实测 2026-06-02, per `.claude/rules/playwright-headed-mode.md`)

- headless: false ✓ (`restaurant-ia` project)
- viewport: 1920×1080 ✓
- locale: zh-CN ✓
- chromium window 真弹 ✓ (本地 `vite dev :5173` 服务**未部署**的本分支代码, `/api` 代理到 gateway `8097` 真后端 → 验证的是本分支代码而非线上旧代码)
- 截图字体: 中文真显示 (无方块 □) ✓ — `test-results/restaurant-ia-sidebar.png` 显示「餐饮运营」展开呈 3 层 (深度分析/日常录入/数据与系统 section divider + 9 子项)
- screenshot mode: fullPage ✓ / video: .webm 真录 ✓
- 多 chat 共存: `--window-position=1000,0` (右); 本版 Playwright `launch()` 拒 `--user-data-dir`/`--remote-debugging-port` → 移除 (见 commit `6fcf6f2ab`), 靠 Playwright 内置 per-worker context 隔离
- **结果: 3/3 PASS** — (1) sidebar 3 层断言 (菜品分析/门店对比/平台口碑/配方/领料/损耗/盘点 可见, 运营总览/菜品四象限/菜品毛利 不存在) (2) 4 条旧路由 redirect (analytics→驾驶舱 / menu→dishes?tab=quadrant / gross-margin→dishes?tab=margin / dianping→platform) (3) 平台口碑 banner「本页需接入大众点评 / 美团平台数据」
- 账号: `qhj_prod` → factoryId=`RES_3101_009` → factoryType=`RESTAURANT` (实测 unified-login 返回; #372 修正测试库后生效) → 驱动 `hideForFactoryTypes:['FACTORY']` 门控正确放行
- 注: 单测层 25 menuConfig + 4 restaurantDishesTab = 29 通过 (结构/门控/tab 解析确定性覆盖)
