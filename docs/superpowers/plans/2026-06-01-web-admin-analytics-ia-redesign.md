# Web-Admin「数据与分析」IA 重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 web-admin 侧边栏的两个报表/分析组(顶级「经营报表」`/analytics` 8 项 + 「智能分析」`/smart-bi` 15 项)合并为单一「数据与分析」组,5 个语义子组,经营驾驶舱置顶为主入口,加业态门控让餐饮/制造各看各的,旧路由 redirect 不破书签。

**Architecture:** 纯前端 web-admin (Vue 3 + Element Plus)。改动集中在 (1) 把内联 `menuConfig` 抽到可单测的 `menuConfig.ts` 模块;(2) 在该模块里合并两组 + 加 groupLabel + 业态门控;(3) router 加 redirect;(4) 两个组件 (SmartBIAnalysis / FinancialDashboardPBI) 加 `?tab=` 消费做内容合并。**不动任何后端** —— 各页继续调它既有的 API (部分页本就 Java reports + Python smartbi 混合)。

**Tech Stack:** Vue 3 `<script setup>` + TypeScript + Element Plus (`el-menu`/`el-sub-menu`/`el-tabs`) + vue-router 4 + vitest 4.1 (单测) + Playwright (headed 双业态 E2E)。

**Spec:** `docs/superpowers/specs/2026-06-01-web-admin-analytics-ia-redesign-design.md` (v2, 已过 19-agent 对抗性审计)。

**Worktree:** `C:\Users\Steve\cretas-restaurant-ia`, 分支 `feat/restaurant-ia-redesign` (off origin/main)。所有命令在 `web-admin/` 下跑。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `web-admin/src/components/layout/menuConfig.ts` | **新建** — 抽出静态 `menuConfig` 数组 + `MenuItem` interface (纯数据, 可单测) | Create |
| `web-admin/src/components/layout/__tests__/menuConfig.spec.ts` | **新建** — 菜单结构/门控/子组 单测 | Create |
| `web-admin/src/components/layout/AppSidebar.vue` | 删内联 menuConfig, 改 import; canSeeMenuItem/渲染逻辑不动 | Modify |
| `web-admin/src/router/modules/smartbi.ts` | 填 `smartBIRedirects` 数组 (P2/P3/P4 redirect) | Modify |
| `web-admin/src/views/smart-bi/SmartBIAnalysis.vue` | 加 `?tab=` 消费 + query 业务 tab (P3) | Modify |
| `web-admin/src/views/smart-bi/FinancialDashboardPBI.vue` | 加 `?tab=` 消费 + analysis tab (P4) | Modify |
| `web-admin/tests/e2e-ia-redesign.spec.ts` | **新建** — Playwright headed 双业态门控验证 | Create |

**阶段→任务映射:**
- **P0 (前置)**: Task 0 — 抽 menuConfig 到可单测模块。
- **P1 (菜单合并+门控, 最大收益)**: Task 1 (合并两组+5子组), Task 2 (业态门控), Task 3 (AppSidebar 引用 + title 同步 grep)。
- **P2 (纯 path redirect)**: Task 4。
- **P3 (AI 探索双tab)**: Task 5。
- **P4 (财务看板合并)**: Task 6。
- **P5 (去重决策)**: 不在本 plan 实现 (需埋点数据), 见末尾「后续」。
- **收尾**: Task 7 (Playwright 双业态 E2E + 构建验证)。

---

## Task 0: 抽出 menuConfig 到可单测模块

**Why:** `menuConfig` 当前是 `AppSidebar.vue` `<script setup>` 内的 `const` (`:86-347`), 无法被 vitest import 单测。抽成纯数据模块后, 后续每次菜单改动都能 TDD 断言结构。`canSeeMenuItem` 等依赖 store 的逻辑**留在 .vue**, 只抽纯数据 (`MenuItem` interface + `menuConfig` 数组 + `financeManagerMenu`)。

**Files:**
- Create: `web-admin/src/components/layout/menuConfig.ts`
- Create: `web-admin/src/components/layout/__tests__/menuConfig.spec.ts`
- Modify: `web-admin/src/components/layout/AppSidebar.vue:57-347` (删内联, 改 import)

- [ ] **Step 1: 写失败测试 — menuConfig 模块存在且可 import**

Create `web-admin/src/components/layout/__tests__/menuConfig.spec.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { menuConfig, type MenuItem } from '../menuConfig';

function findGroup(path: string): MenuItem | undefined {
  return menuConfig.find((m) => m.path === path);
}

describe('menuConfig — baseline structure (pre-merge)', () => {
  it('exports a non-empty menuConfig array', () => {
    expect(Array.isArray(menuConfig)).toBe(true);
    expect(menuConfig.length).toBeGreaterThan(0);
  });

  it('每个顶级项有 path/title/module', () => {
    for (const item of menuConfig) {
      expect(item.path, `item missing path`).toBeTruthy();
      expect(item.title, `${item.path} missing title`).toBeTruthy();
      expect(item.module, `${item.path} missing module`).toBeTruthy();
    }
  });

  it('当前存在 /restaurant 组 (回归基线)', () => {
    expect(findGroup('/restaurant')).toBeDefined();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts`
Expected: FAIL — `Failed to resolve import "../menuConfig"` (模块还不存在)。

- [ ] **Step 3: 创建 menuConfig.ts (从 AppSidebar.vue 原样剪切 MenuItem + menuConfig + financeManagerMenu)**

Create `web-admin/src/components/layout/menuConfig.ts`. 把 `AppSidebar.vue` 当前 `:57-66` 的 `MenuItem` interface、`:68-84` 的 `financeManagerMenu`、`:86-347` 的 `menuConfig` **原样移过来** (内容此刻不改, 仅迁移 + export)。顶部加 `ModuleName` import:

```typescript
import type { ModuleName } from '@/types/permission'; // 与 AppSidebar 现用同一来源 — 见 Step 3a 核对

export interface MenuItem {
  path: string;
  title: string;
  icon: string;
  module: ModuleName;
  roles?: string[];
  hideForFactoryTypes?: string[];
  children?: MenuItem[];
  groupLabel?: string;
}

// 财务主管专用菜单 - 简化版 (原 AppSidebar.vue:68-84 原样)
export const financeManagerMenu: MenuItem[] = [
  // ... 原样粘贴 financeManagerMenu 全部内容 ...
];

// 主菜单 (原 AppSidebar.vue:86-347 原样)
export const menuConfig: MenuItem[] = [
  // ... 原样粘贴 menuConfig 全部内容 ...
];
```

> **Step 3a — 核对 ModuleName 来源**: 先 `grep -n "ModuleName" web-admin/src/components/layout/AppSidebar.vue` 找它当前从哪 import (可能是 `@/stores/permission` 或 `@/types`)。用**完全相同**的 import 路径, 否则 TS 报错。若 `ModuleName` 是 .vue 内本地定义的, 则一并迁到 menuConfig.ts 并 export。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts`
Expected: PASS (3 个测试)。

- [ ] **Step 5: 改 AppSidebar.vue 用 import 替代内联定义**

在 `AppSidebar.vue` `<script setup>`: (a) 删除 `:57-66` 的 `interface MenuItem`、`:68-84` 的 `financeManagerMenu`、`:86-347` 的 `menuConfig`; (b) 顶部加 import:

```typescript
import { menuConfig, financeManagerMenu, type MenuItem } from './menuConfig';
```

`canSeeMenuItem` / `filteredMenu` / `RESTAURANT_TITLE_OVERRIDES` / `titleForItem` / `defaultOpeneds` / template **全部不动** (它们引用 `menuConfig`/`MenuItem`/`financeManagerMenu` 现在来自 import, 签名不变)。

- [ ] **Step 6: 跑 type check + 构建确认无回归**

Run: `cd web-admin && npx vue-tsc -b 2>&1 | grep -i "menuConfig\|AppSidebar" || echo "NO TS ERRORS in touched files"`
Expected: `NO TS ERRORS in touched files` (注: 仓库有预存 TS 错误在别的文件, 只看我们碰的两个文件干净)。

- [ ] **Step 7: Commit**

```bash
git add web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts web-admin/src/components/layout/AppSidebar.vue
git commit -m "refactor(sidebar): 抽 menuConfig 到可单测模块 (IA 重设计前置)" -- web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts web-admin/src/components/layout/AppSidebar.vue
```

---

## Task 1: 合并两组为「数据与分析」+ 5 子组 (P1 核心)

**Files:**
- Modify: `web-admin/src/components/layout/menuConfig.ts` (删 `/analytics` 顶级组 + 重建 `/smart-bi` 组 children)
- Test: `web-admin/src/components/layout/__tests__/menuConfig.spec.ts`

**目标结构** (spec §3): 单一组 `path:'/smart-bi'` title「数据与分析」, children 顺序 = 经营驾驶舱(顶) → AI探索(2) → 专题报表(10) → 数据管理(3) → AI运维(3)。`/analytics` 顶级组**整个删除**, 其 7 个 analytics children + indicator-center 迁入。

- [ ] **Step 1: 写失败测试 — 合并后的组结构**

在 `menuConfig.spec.ts` 末尾追加:

```typescript
describe('menuConfig — merged 数据与分析 group (Task 1)', () => {
  it('顶级 /analytics 组已删除 (合并入 /smart-bi)', () => {
    expect(menuConfig.find((m) => m.path === '/analytics')).toBeUndefined();
  });

  it('/smart-bi 组改名「数据与分析」', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi');
    expect(g).toBeDefined();
    expect(g!.title).toBe('数据与分析');
  });

  it('经营驾驶舱是第一个 child (主入口置顶)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children![0].path).toBe('/smart-bi/dashboard');
  });

  it('含 5 个 groupLabel 子组 (经营驾驶舱无 label, 其余 4 段)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const labels = g.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel);
    expect(labels).toEqual(['AI 探索', '专题报表', '数据管理', 'AI 运维']);
  });

  it('原 /analytics 的 children 全部迁入 (无掉项, 含车间报表+指标中心)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    for (const p of [
      '/analytics/ai-reports', '/analytics/trends', '/analytics/kpi',
      '/analytics/alert-dashboard', '/analytics/supply-chain',
      '/analytics/production-report', '/indicator-center',
    ]) {
      expect(paths, `missing migrated child ${p}`).toContain(p);
    }
  });

  it('原 /analytics/overview 仍保留 (D-6 保守, 不删不 redirect)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children!.map((c) => c.path)).toContain('/analytics/overview');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts`
Expected: FAIL — `/analytics` 组还在 + title 还是「智能分析」等。

- [ ] **Step 3: 实现合并 — 删 /analytics 顶级组, 重建 /smart-bi children**

在 `menuConfig.ts`: (a) **删除**整个 `/analytics` 顶级组 (原 `AppSidebar.vue:269-284` 对应块)。(b) 把 `/smart-bi` 组替换为:

```typescript
  {
    // UX 2026-06-01: 合并「经营报表」(/analytics) + 「智能分析」(/smart-bi) 为单一
    // 「数据与分析」组 (spec 2026-06-01-web-admin-analytics-ia-redesign-design.md)。
    // 经营驾驶舱置顶主入口; 5 子组。各页后端不变 (部分页 Java reports + Python 混合)。
    path: '/smart-bi', title: '数据与分析', icon: 'TrendCharts', module: 'analytics',
    children: [
      // ★ 主入口 (无 groupLabel, 置顶)
      { path: '/smart-bi/dashboard', title: '经营驾驶舱', icon: 'Monitor', module: 'analytics' },
      // -- AI 探索 --
      { path: '/smart-bi/analysis', title: 'AI 问答 / 数据分析', icon: 'DataAnalysis', module: 'analytics', groupLabel: 'AI 探索' },
      { path: '/analytics/ai-reports', title: 'AI 分析报告', icon: 'Document', module: 'analytics' },
      // -- 专题报表 --
      { path: '/smart-bi/financial-dashboard', title: '财务看板', icon: 'TrendCharts', module: 'analytics', groupLabel: '专题报表' },
      { path: '/smart-bi/sales', title: '销售分析', icon: 'Sell', module: 'analytics' },
      { path: '/smart-bi/revenue-report', title: '收入管理报表', icon: 'Money', module: 'analytics',
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/analytics/trends', title: '趋势分析', icon: 'TrendCharts', module: 'analytics' },
      { path: '/analytics/kpi', title: 'KPI 看板', icon: 'Histogram', module: 'analytics' },
      { path: '/analytics/alert-dashboard', title: '异常预警', icon: 'Warning', module: 'analytics' },
      { path: '/analytics/supply-chain', title: '进销存总览', icon: 'Histogram', module: 'analytics' },
      { path: '/analytics/production-report', title: '车间实时生产报表', icon: 'Operation', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/indicator-center', title: '指标中心', icon: 'Histogram', module: 'analytics' },
      { path: '/production-analytics/production', title: '生产数据分析', icon: 'Histogram', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/production-analytics/efficiency', title: '人效分析', icon: 'User', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      // -- 数据管理 --
      { path: '/smart-bi/upload', title: 'Excel 上传', icon: 'Upload', module: 'analytics', groupLabel: '数据管理' },
      { path: '/smart-bi/query-templates', title: '查询模板', icon: 'Tickets', module: 'analytics' },
      { path: '/smart-bi/data-completeness', title: '数据完整度', icon: 'DataAnalysis', module: 'analytics' },
      // -- AI 运维 --
      { path: '/smart-bi/food-kb-feedback', title: '知识库反馈', icon: 'ChatDotRound', module: 'analytics', groupLabel: 'AI 运维' },
      { path: '/smart-bi/fallback-log', title: 'AI 追问日志', icon: 'DataLine', module: 'analytics' },
      { path: '/smart-bi/calibration', title: '行为校准监控', icon: 'Aim', module: 'analytics', roles: ['platform_admin'] },
      // D-6 保守保留: 分析概览 (与驾驶舱重叠但数据源不同, P5 凭埋点再决定真删)
      { path: '/analytics/overview', title: '分析概览', icon: 'DataAnalysis', module: 'analytics' },
    ]
  }
```

> 注: `/smart-bi/query` (旧 AI问答) 与 `/smart-bi/finance` (旧财务数据分析) **不再单列菜单项** — 它们由 P3/P4 合并入 analysis / financial-dashboard, 并在 Task 4/5/6 加 redirect。路由本身仍在 (smartbi.ts), 此处仅菜单不列。
> 注: `icon` 用 Element Plus 已注册名 (与现有项一致即可); 若某 icon 名 iconMap 没有会渲染空, 不影响功能 —— Task 7 截图验证。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts`
Expected: PASS (全部, 含 Task 1 新增 6 个)。

- [ ] **Step 5: Commit**

```bash
git add web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts
git commit -m "feat(sidebar): 合并经营报表+智能分析为「数据与分析」5子组, 驾驶舱置顶" -- web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts
```

---

## Task 2: 业态门控正确性 (P1, M4 修正)

**Files:**
- Test: `web-admin/src/components/layout/__tests__/menuConfig.spec.ts`
- Modify: `web-admin/src/components/layout/menuConfig.ts` (若 Task 1 已对则仅加测试锁定)

**目标** (spec D-5 + M4): 只对**明确制造专属**项加 `['RESTAURANT']`; **趋势分析 / KPI 看板不门控** (双业态有数据); 收入管理报表 `['FACTORY']`。

- [ ] **Step 1: 写失败测试 — 门控方向断言**

在 `menuConfig.spec.ts` 末尾追加:

```typescript
describe('menuConfig — 业态门控方向 (Task 2, M4)', () => {
  const group = () => menuConfig.find((m) => m.path === '/smart-bi')!;
  const child = (p: string) => group().children!.find((c) => c.path === p)!;

  it.each([
    '/analytics/production-report',
    '/analytics/supply-chain',
    '/production-analytics/production',
    '/production-analytics/efficiency',
  ])('制造专属项 %s 对餐饮隐藏 (hideForFactoryTypes 含 RESTAURANT)', (p) => {
    expect(child(p).hideForFactoryTypes).toContain('RESTAURANT');
  });

  it.each([
    '/analytics/trends',   // 双业态自适应 — 餐饮看 POS 营收趋势的唯一入口
    '/analytics/kpi',      // 餐饮有 restaurant-ops/summary 数据 (Steve 定: 不门控)
  ])('双业态项 %s 不门控 (餐饮仍可见)', (p) => {
    expect(child(p).hideForFactoryTypes).toBeUndefined();
  });

  it('收入管理报表对制造隐藏 (FACTORY)', () => {
    expect(child('/smart-bi/revenue-report').hideForFactoryTypes).toContain('FACTORY');
  });

  it('行为校准监控保留 platform_admin 门控', () => {
    expect(child('/smart-bi/calibration').roles).toContain('platform_admin');
  });
});
```

- [ ] **Step 2: 跑测试**

Run: `cd web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts`
Expected: 若 Task 1 的 children 已按上面写则全 PASS; 若有门控写错 (如给 trends 加了 RESTAURANT) 则对应用例 FAIL。

- [ ] **Step 3: 修正门控 (仅当 Step 2 有 FAIL)**

按测试期望调整 `menuConfig.ts` 对应 child 的 `hideForFactoryTypes`: 确保 `/analytics/trends` 和 `/analytics/kpi` **无** `hideForFactoryTypes`; 4 个制造专属项**有** `['RESTAURANT']`; revenue-report 有 `['FACTORY']`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/components/layout/__tests__/menuConfig.spec.ts`
Expected: PASS (全部)。

- [ ] **Step 5: Commit**

```bash
git add web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts
git commit -m "test(sidebar): 锁定业态门控方向 — 趋势/KPI 不门控, 制造专属项隐藏餐饮" -- web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts
```

---

## Task 3: 标题/手册一致性 grep (P1, M5)

**Why:** 组 title 从「智能分析」/「经营报表」改为「数据与分析」后, 旧串散落在操作手册 + route meta.title + 测试。改名不同步会让手册过时、面包屑不一致。

**Files:**
- 调查 (grep): 全 `web-admin/`
- Modify (按 grep 结果): `web-admin/src/router/index.ts:1245` (`/analytics` 顶级 meta.title) + 可能的 `public/**/*.html` 手册

- [ ] **Step 1: grep 全仓找旧标题串的所有引用**

Run:
```bash
cd web-admin && grep -rn "经营报表\|'智能分析'\|name: 'Analytics'\|title: '数据分析'" src/ public/ tests/ --include="*.ts" --include="*.vue" --include="*.html" --include="*.spec.ts" 2>/dev/null
```
Expected: 列出所有命中 (审计称 ~13 处含 `F006_OPERATIONS_GUIDE.html:966` + `index.ts:1245`)。**记录每一处** 决定是否同步。

- [ ] **Step 2: 同步 router meta.title (面包屑一致)**

`router/index.ts:1245` 当前 `meta: { ..., title: '数据分析', ... }` (顶级 `/analytics` redirect 块)。该 `/analytics` 顶级 redirect 路由本身保留 (Task 4 改其 redirect target), 但其 meta.title 与新菜单组名对齐:

```typescript
// router/index.ts — /analytics 顶级 redirect 块的 meta
meta: { requiresAuth: true, title: '数据与分析', icon: 'DataAnalysis', module: 'analytics' },
```

- [ ] **Step 3: 同步操作手册 (若 Step 1 命中 public/*.html)**

若 `F006_OPERATIONS_GUIDE.html` (或类似手册) 含「菜单: 智能分析 + 经营报表」, 改为「菜单: 数据与分析」。**仅文案, 不动结构。** (若手册命中是 docs/ 下归档文件而非 public/ 实际部署文件, 记录但可不改 — 由 Step 1 输出判断。)

- [ ] **Step 4: 确认无遗漏的硬编码菜单 path 依赖**

Run:
```bash
cd web-admin && grep -rn "router.push('/analytics')\|name: 'Analytics'" src/ --include="*.ts" --include="*.vue" 2>/dev/null
```
Expected: 确认没有组件用 `router.push('/analytics')` 直跳 (有的话 Task 4 redirect 会接住, 但记录)。

- [ ] **Step 5: Commit**

```bash
git add web-admin/src/router/index.ts
# 若改了手册也 add 对应 .html
git commit -m "fix(ia): 同步「数据与分析」组名到 route meta.title + 操作手册 (M5)" -- web-admin/src/router/index.ts
```

---

## Task 4: 纯 path redirect — /analytics → 驾驶舱 (P2)

**Files:**
- Modify: `web-admin/src/router/modules/smartbi.ts:129` (`smartBIRedirects` 数组) **或** `web-admin/src/router/index.ts:1244` (`/analytics` 顶级 redirect target)
- Test: 手动 + Playwright (Task 7)

**目标** (spec §4.2 P2): `/analytics` 顶级入口从 redirect `/analytics/overview` 改为 redirect `/smart-bi/dashboard` (旧经营报表入口落到驾驶舱)。**不含 `?tab=`** (那些在 P3/P4)。`/analytics/overview` 子路由本身**不动** (D-6 保守保留)。

- [ ] **Step 1: 改 /analytics 顶级 redirect target**

`router/index.ts:1244` 当前:
```typescript
        path: 'analytics',
        name: 'Analytics',
        redirect: '/analytics/overview',
```
改为:
```typescript
        path: 'analytics',
        name: 'Analytics',
        redirect: '/smart-bi/dashboard',   // IA 合并: 旧经营报表顶级入口落到经营驾驶舱 (P2)
```

> **精确锚定 (M-MINOR)**: 这是嵌套在调度父路由下、`name:'Analytics'` 的块 (`index.ts:1242`)。**勿改** `index.ts:1414` 的 `/restaurant/analytics` (`name:'RestaurantAnalyticsOverview'`, 完全不同的餐饮路由)。改前 `grep -n "name: 'Analytics'" web-admin/src/router/index.ts` 确认只命中 1242 这一处。

- [ ] **Step 2: 验证 redirect 落点路由存在**

Run: `cd web-admin && grep -n "smart-bi/dashboard\|path: 'dashboard'" src/router/modules/smartbi.ts`
Expected: 命中 `redirect: '/smart-bi/dashboard'` (smartbi.ts:12) + dashboard 路由定义 — 落点有效。

- [ ] **Step 3: type check**

Run: `cd web-admin && npx vue-tsc -b 2>&1 | grep -i "router/index\|smartbi.ts" || echo "NO TS ERRORS in touched files"`
Expected: `NO TS ERRORS in touched files`。

- [ ] **Step 4: Commit**

```bash
git add web-admin/src/router/index.ts
git commit -m "feat(ia): /analytics 顶级入口 redirect 到经营驾驶舱 (P2 纯path)" -- web-admin/src/router/index.ts
```

---

## Task 5: AI 探索双tab — analysis 消费 ?tab=query (P3, M2)

**Files:**
- Modify: `web-admin/src/views/smart-bi/SmartBIAnalysis.vue` (加 `useRoute` + `?tab=` watch + query 业务 tab)
- Modify: `web-admin/src/router/modules/smartbi.ts` (加 `/smart-bi/query → /smart-bi/analysis?tab=query` redirect)
- 调查前置: 读 `AIQuery.vue` (要整合的 NL 查询逻辑) + `SmartBIAnalysis.vue` 现有 `activeTab` 用法

**关键 (M2)**: SmartBIAnalysis 的 `activeTab` 当前是 **Excel sheet 索引** (`String(sheetIndex)`), 命名空间被占。新业务 tab 用**独立变量** `topTab` (取值 `'analysis'`/`'query'`), 不碰 sheet-tab。

- [ ] **Step 1: 调查 — 读两组件确认整合点**

Run:
```bash
cd web-admin && grep -n "activeTab\|useRoute\|route.query\|el-tabs\|parsedSheets\|queryNaturalLanguage" src/views/smart-bi/SmartBIAnalysis.vue src/views/smart-bi/AIQuery.vue | head -40
```
记录: SmartBIAnalysis 的 sheet-tab 变量名 + el-tabs 结构; AIQuery 的 NL 查询调用 (`queryNaturalLanguage` from `@/api/smartbi/aiQuery`) — 这段逻辑要嵌入 analysis 的 query tab。

- [ ] **Step 2: 写失败测试 — analysis 页读 ?tab= 切顶层 tab**

Create `web-admin/src/views/smart-bi/__tests__/SmartBIAnalysis.tab.spec.ts`:

```typescript
import { describe, it, expect, vi } from 'vitest';

// 轻量单测: 验证 tab 解析纯函数 (从 route.query.tab 解析顶层 tab, 默认 analysis)
import { resolveTopTab } from '../smartBIAnalysisTab';

describe('SmartBIAnalysis topTab resolution (P3)', () => {
  it('?tab=query → query', () => {
    expect(resolveTopTab({ tab: 'query' })).toBe('query');
  });
  it('?tab=analysis → analysis', () => {
    expect(resolveTopTab({ tab: 'analysis' })).toBe('analysis');
  });
  it('无 tab → 默认 analysis', () => {
    expect(resolveTopTab({})).toBe('analysis');
  });
  it('非法 tab → 默认 analysis (不破坏)', () => {
    expect(resolveTopTab({ tab: 'garbage' })).toBe('analysis');
  });
});
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/views/smart-bi/__tests__/SmartBIAnalysis.tab.spec.ts`
Expected: FAIL — `Failed to resolve import "../smartBIAnalysisTab"`。

- [ ] **Step 4: 写 tab 解析纯函数**

Create `web-admin/src/views/smart-bi/smartBIAnalysisTab.ts`:

```typescript
export type TopTab = 'analysis' | 'query';
const VALID: TopTab[] = ['analysis', 'query'];

/** 从 route.query 解析顶层 tab (AI探索: 上传分析 / 问数据)。非法值降级 analysis。 */
export function resolveTopTab(query: Record<string, unknown>): TopTab {
  const t = query?.tab;
  return VALID.includes(t as TopTab) ? (t as TopTab) : 'analysis';
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/views/smart-bi/__tests__/SmartBIAnalysis.tab.spec.ts`
Expected: PASS (4 个)。

- [ ] **Step 6: SmartBIAnalysis.vue 加顶层双tab + 整合 AIQuery**

在 `SmartBIAnalysis.vue` `<script setup>`: (a) import `useRoute` + `resolveTopTab` + AIQuery 的查询逻辑/组件; (b) 加 `const route = useRoute(); const topTab = ref(resolveTopTab(route.query)); watch(() => route.query.tab, () => { topTab.value = resolveTopTab(route.query); });`。

template 最外层包一个 `<el-tabs v-model="topTab">`:
```html
<el-tabs v-model="topTab" class="top-explore-tabs">
  <el-tab-pane label="传 Excel 分析" name="analysis">
    <!-- 原有 SmartBIAnalysis 上传/sheet 分析内容 (含原 sheet-tab, 不动) -->
  </el-tab-pane>
  <el-tab-pane label="问数据 (AI)" name="query">
    <!-- 整合 AIQuery 的 NL 查询 UI (复用 queryNaturalLanguage 调用) -->
  </el-tab-pane>
</el-tabs>
```
> 整合方式二选一: (i) 直接 `<AIQuery />` 作为子组件嵌入 query pane (改动最小, 推荐); (ii) 把 AIQuery 逻辑搬进来。**推荐 (i)** — 保 AIQuery.vue 不动, analysis 页 import 它当 query pane 内容, `/smart-bi/query` 路由后续 redirect 过来。

- [ ] **Step 7: 加 redirect /smart-bi/query → /smart-bi/analysis?tab=query**

`smartbi.ts` `smartBIRedirects` 数组 (现 `:129` 为空) 填:
```typescript
export const smartBIRedirects: RouteRecordRaw[] = [
  // P3: AI问答合并入 AI探索 query tab
  { path: '/smart-bi/query', redirect: '/smart-bi/analysis?tab=query' },
];
```
> 确认 `smartBIRedirects` 被 router 实际挂载 (`grep -n smartBIRedirects web-admin/src/router/index.ts` — 若没被 import/spread 进 routes, 需补挂载, 见审计 M6 note: 该数组当前 empty 可能未被消费, 实现时核实)。

- [ ] **Step 8: 跑单测 + type check**

Run: `cd web-admin && npx vitest run src/views/smart-bi/__tests__/SmartBIAnalysis.tab.spec.ts && npx vue-tsc -b 2>&1 | grep -i "SmartBIAnalysis\|smartbi.ts\|smartBIAnalysisTab" || echo "NO TS ERRORS in touched files"`
Expected: PASS + `NO TS ERRORS in touched files`。

- [ ] **Step 9: Commit**

```bash
git add web-admin/src/views/smart-bi/SmartBIAnalysis.vue web-admin/src/views/smart-bi/smartBIAnalysisTab.ts web-admin/src/views/smart-bi/__tests__/SmartBIAnalysis.tab.spec.ts web-admin/src/router/modules/smartbi.ts
git commit -m "feat(ia): AI探索双tab — analysis 整合 AI问答, /smart-bi/query redirect (P3)"
```

---

## Task 6: 财务看板合并 — financial-dashboard 消费 ?tab=analysis (P4, M2)

**Files:**
- Modify: `web-admin/src/views/smart-bi/FinancialDashboardPBI.vue` (加 `useRoute` + `?tab=` + analysis section)
- Modify: `web-admin/src/router/modules/smartbi.ts` (加 `/smart-bi/finance → /smart-bi/financial-dashboard?tab=analysis`)
- 前置调查: 读 `FinancialDashboardPBI.vue` (现 `activeTab=ref('budget_achievement')` 图表键) + `FinanceAnalysis.vue` (要整合内容)

**关键 (M2)**: FinancialDashboardPBI 的 `activeTab` 是图表类型键。新增独立 `sectionTab` (`'dashboard'`/`'analysis'`), 不碰图表 activeTab。

- [ ] **Step 1: 调查两组件**

Run:
```bash
cd web-admin && grep -n "activeTab\|viewMode\|useRoute\|route.query\|el-tabs" src/views/smart-bi/FinancialDashboardPBI.vue src/views/smart-bi/FinanceAnalysis.vue | head -30
```
记录现有 tab 变量名 + el-tabs 结构。

- [ ] **Step 2: 写失败测试 — section tab 解析**

Create `web-admin/src/views/smart-bi/__tests__/FinancialDashboard.tab.spec.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { resolveFinanceSection } from '../financeDashboardSection';

describe('FinancialDashboard sectionTab (P4)', () => {
  it('?tab=analysis → analysis', () => expect(resolveFinanceSection({ tab: 'analysis' })).toBe('analysis'));
  it('无 tab → dashboard (默认 PBI)', () => expect(resolveFinanceSection({})).toBe('dashboard'));
  it('非法 → dashboard', () => expect(resolveFinanceSection({ tab: 'x' })).toBe('dashboard'));
});
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/views/smart-bi/__tests__/FinancialDashboard.tab.spec.ts`
Expected: FAIL — import 不存在。

- [ ] **Step 4: 写解析纯函数**

Create `web-admin/src/views/smart-bi/financeDashboardSection.ts`:
```typescript
export type FinanceSection = 'dashboard' | 'analysis';
const VALID: FinanceSection[] = ['dashboard', 'analysis'];
/** 财务看板 section: PBI 看板(dashboard) / 财务数据分析(analysis)。默认 dashboard。 */
export function resolveFinanceSection(query: Record<string, unknown>): FinanceSection {
  const t = query?.tab;
  return VALID.includes(t as FinanceSection) ? (t as FinanceSection) : 'dashboard';
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/views/smart-bi/__tests__/FinancialDashboard.tab.spec.ts`
Expected: PASS (3 个)。

- [ ] **Step 6: FinancialDashboardPBI.vue 加 section tab + 整合 FinanceAnalysis**

`<script setup>` 加 `const route = useRoute(); const sectionTab = ref(resolveFinanceSection(route.query)); watch(() => route.query.tab, () => { sectionTab.value = resolveFinanceSection(route.query); });`

template 最外层包:
```html
<el-tabs v-model="sectionTab" class="finance-section-tabs">
  <el-tab-pane label="财务 PBI 看板" name="dashboard">
    <!-- 原有 PBI 看板内容 (含原图表 activeTab, 不动) -->
  </el-tab-pane>
  <el-tab-pane label="财务数据分析" name="analysis">
    <FinanceAnalysis />  <!-- 整合, 推荐子组件嵌入 -->
  </el-tab-pane>
</el-tabs>
```

- [ ] **Step 7: 加 redirect /smart-bi/finance → /smart-bi/financial-dashboard?tab=analysis**

`smartbi.ts` `smartBIRedirects` 数组追加:
```typescript
  // P4: 财务数据分析合并入财务看板 analysis section
  { path: '/smart-bi/finance', redirect: '/smart-bi/financial-dashboard?tab=analysis' },
```

- [ ] **Step 8: 跑单测 + type check**

Run: `cd web-admin && npx vitest run src/views/smart-bi/__tests__/FinancialDashboard.tab.spec.ts && npx vue-tsc -b 2>&1 | grep -i "FinancialDashboard\|financeDashboardSection\|smartbi.ts" || echo "NO TS ERRORS in touched files"`
Expected: PASS + `NO TS ERRORS in touched files`。

- [ ] **Step 9: Commit**

```bash
git add web-admin/src/views/smart-bi/FinancialDashboardPBI.vue web-admin/src/views/smart-bi/financeDashboardSection.ts web-admin/src/views/smart-bi/__tests__/FinancialDashboard.tab.spec.ts web-admin/src/router/modules/smartbi.ts
git commit -m "feat(ia): 财务看板合并 — PBI 看板整合财务数据分析, /smart-bi/finance redirect (P4)"
```

---

## Task 7: Playwright headed 双业态 E2E + 全量构建验证

**Files:**
- Create: `web-admin/tests/e2e-ia-redesign.spec.ts`
- 配置: 遵守 `.claude/rules/playwright-headed-mode.md` (headless:false, viewport 1920×1080, lang zh-CN)

- [ ] **Step 1: 写 E2E spec — 双业态侧边栏 + 门控 + redirect**

Create `web-admin/tests/e2e-ia-redesign.spec.ts`:
```typescript
import { test, expect } from '@playwright/test';

// 凭证: 制造 factory_admin1/123456 (F001); 餐饮 qhj_prod/123456 (RES_3101_009)
// per memory reference_prod_no_real_customers_yet。prod UI 登录偶 flaky — 失败重试。

async function login(page, username: string, password: string) {
  await page.goto('/login');
  await page.fill('input[type="text"]', username);
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/\/(dashboard|smart-bi)/, { timeout: 15000 });
}

test.describe('IA 重设计 — 数据与分析组 (headed 双业态)', () => {
  test('制造租户: 单一「数据与分析」组, 经营报表组消失, 制造项可见', async ({ page }) => {
    await login(page, 'factory_admin1', '123456');
    // 只剩一个「数据与分析」, 无「经营报表」「智能分析」旧名
    await expect(page.getByText('数据与分析', { exact: true })).toBeVisible();
    await expect(page.getByText('经营报表', { exact: true })).toHaveCount(0);
    // 展开组, 经营驾驶舱第一项
    await page.getByText('数据与分析', { exact: true }).click();
    await expect(page.getByRole('menuitem', { name: '经营驾驶舱' })).toBeVisible();
    // 制造专属项可见
    await expect(page.getByRole('menuitem', { name: '车间实时生产报表' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '生产数据分析' })).toBeVisible();
    await page.screenshot({ path: 'test-results/ia-factory-sidebar.png', fullPage: true });
  });

  test('餐饮租户: 制造专属项隐藏, 趋势分析仍可见', async ({ page }) => {
    await login(page, 'qhj_prod', '123456');
    await page.getByText('数据与分析', { exact: true }).click();
    // 制造专属隐藏
    await expect(page.getByRole('menuitem', { name: '车间实时生产报表' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '生产数据分析' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '人效分析' })).toHaveCount(0);
    // 双业态项仍可见 (M4)
    await expect(page.getByRole('menuitem', { name: '趋势分析' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: 'KPI 看板' })).toBeVisible();
    // 餐饮专属可见
    await expect(page.getByRole('menuitem', { name: '收入管理报表' })).toBeVisible();
    await page.screenshot({ path: 'test-results/ia-restaurant-sidebar.png', fullPage: true });
  });

  test('redirect: /analytics → 经营驾驶舱; /smart-bi/query → analysis?tab=query', async ({ page }) => {
    await login(page, 'factory_admin1', '123456');
    await page.goto('/analytics');
    await expect(page).toHaveURL(/\/smart-bi\/dashboard/);
    await page.goto('/smart-bi/query');
    await expect(page).toHaveURL(/\/smart-bi\/analysis\?tab=query/);
    await page.goto('/smart-bi/finance');
    await expect(page).toHaveURL(/\/smart-bi\/financial-dashboard\?tab=analysis/);
  });
});
```
> base URL 用 prod (`http://47.100.235.168` 经网关) 或本地 dev (`npm run dev` 起 3010) — 按 playwright.config.ts 现有 baseURL。中文 selector 用真文案。登录 selector 按实际 login 页调整 (Step 2 先手动核对一次)。

- [ ] **Step 2: 本地 dev 起服务 + 手动核对 login selector**

Run: `cd web-admin && npm run dev` (后台), 浏览器开 localhost:3010/login 确认 input/button selector 与 spec 一致, 不一致则改 spec。

- [ ] **Step 3: 跑 E2E (headed, 遵守 rule)**

Run: `cd web-admin && PLAYWRIGHT_PORT=9223 PLAYWRIGHT_CHAT_ID=ia-redesign npx playwright test tests/e2e-ia-redesign.spec.ts --headed`
Expected: 3 个 test PASS, `test-results/ia-{factory,restaurant}-sidebar.png` 生成, 中文字体真显示 (无方块 □)。

- [ ] **Step 4: 全量单测 + 构建检查**

Run: `cd web-admin && npx vitest run src/components/layout src/views/smart-bi && npm run build`
Expected: 单测全 PASS; `vite build` 成功 (vue-tsc 预存错误在别处文件不阻断 `vite build`; 若 `build:check` 被预存 TS 错误卡, 用 `npm run build` 不带 type check)。

- [ ] **Step 5: Commit**

```bash
git add web-admin/tests/e2e-ia-redesign.spec.ts
git commit -m "test(ia): Playwright headed 双业态 E2E — 门控 + redirect 验证"
```

---

## 自检结果 (Self-Review)

- **Spec 覆盖**: P1 (Task 1-3) / P2 (Task 4) / P3 (Task 5) / P4 (Task 6) / 验证 (Task 7) 全覆盖。P5 去重决策按 spec 需埋点数据, 不在本 plan (见下「后续」)。M1-M5 审计修正全部落到对应 Task (M1 后端边界=不动后端无 code 改; M2=Task 5/6 ?tab= 与组件同 PR; M3=Task 1 车间报表迁入; M4=Task 2 门控方向; M5=Task 3 grep)。
- **类型一致**: `MenuItem` (Task 0 定义) 贯穿; `resolveTopTab`/`TopTab` (Task 5)、`resolveFinanceSection`/`FinanceSection` (Task 6) 各自闭环; redirect 数组 `smartBIRedirects` (Task 5/6 共用)。
- **无占位符**: 每个 code step 给了实际代码; grep/命令给了实际命令 + 预期输出。

## 后续 (本 plan 外)

- **P5 去重决策**: 「分析概览」(`/analytics/overview`) / 「KPI 看板」是否真删, 需先加使用埋点收集数据, 再独立小 PR 决定。本 plan 保守保留二者。
- **隐藏可达页** (`/smart-bi/upload-status`/`whatif`/`restaurant-v2`/`gold-preview`): 保留路由不入菜单, 本 plan 不动。
- **部署**: 全部合并 main 后, 从 main worktree 跑 `deploy-web-admin.sh --env prod` (per worktree-and-main-only-deploy rule)。
