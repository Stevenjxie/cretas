# WS4 — 经营分析模块合并 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 把散落的销售分析/趋势分析/KPI看板/指标中心 + 财务看板 合并成一个 tab 化"经营分析"模块 (全 gold + 默认全部历史, 修 #8/#10/#11/#12);删 AI分析报告/What-If/餐饮V2/Gold预览 (#9);异常预警折叠;知识库反馈/AI日志/校准移系统管理。

**Architecture:** 新建 `BusinessAnalysisHub.vue` 用两级 el-tabs (模板参考 `FinancialDashboardPBI.vue` 的 section-tabs 模式);各 tab 内嵌现有(已 gold 化的)子视图组件;默认日期范围全部历史 (复用 WS1 `useGoldAnalytics` / `getGoldDataRange`)。删除页用"路由保留 component 但 menuConfig 隐藏 + 函数式 redirect 到替代页"(参考 `smartBIRedirects` 模式, 保书签)。menuConfig 精简 analytics group。

**Tech Stack:** Vue 3 (新 BusinessAnalysisHub.vue + 现有子视图), Vue Router (redirects), menuConfig.ts。

**依赖:** WS1 (gold 端点 + 默认全部历史 + composable)。建议 WS2/WS3 先做完 (复用其 gold 化组件经验)。

**部署:** 前端 `deploy-web-admin.sh --env prod` (8086)。headed Playwright (9223) 验证侧栏精简 + 各 tab 出数 + 旧路径 redirect。

---

## File Structure

| 文件 | 动作 |
|---|---|
| `web-admin/src/views/smart-bi/BusinessAnalysisHub.vue` | **新建** 两级 tab 容器 (财务/销售/趋势/KPI·指标) |
| `web-admin/src/views/smart-bi/SalesAnalysis.vue:630-634` | 默认日期 30 天 → 全部历史 (#11) |
| `web-admin/src/views/analytics/trends/index.vue:28` | 默认 'week' → 全部历史 (#12) |
| `web-admin/src/views/analytics/kpi/index.vue:40` | `days=30` → 全部历史 (#10 类) |
| `web-admin/src/views/smart-bi/FinancialDashboardPBI.vue` | 默认时间全部历史 (#10) |
| `web-admin/src/router/modules/smartbi.ts` | 加 BusinessAnalysisHub 路由 + redirects (旧页→hub tab) |
| `web-admin/src/router/index.ts:1254-1300` | trends/kpi/indicator/ai-reports 路由处理 (redirect/保留) |
| `web-admin/src/components/layout/menuConfig.ts:250-291` | analytics group 精简: 合并 6 项为"经营分析", 删 AI分析报告, 移 AI运维到系统管理 |

---

## Task 1: 各子页默认全部历史 (#10/#11/#12)

先把要合并的页各自默认时间改成全部历史 (独立可测, 降风险),再合并。

**Files:** SalesAnalysis.vue, trends/index.vue, kpi/index.vue, FinancialDashboardPBI.vue

- [ ] **Step 1: 写测试 (SalesAnalysis 默认全部历史)**
```ts
// SalesAnalysis: mount → 默认 dateRange 不是"近30天", 而是从 getGoldDataRange 得到的全部区间
// 断言: 不出现 "所选区间无销售数据" (当全部历史有数据时)
```
- [ ] **Step 2: 跑确认失败** (当前 line 630-634 硬编码近 30 天)
- [ ] **Step 3: 实现**
  - SalesAnalysis.vue 630-634: 删 `start.setTime(... -30天)`,改 `const dr = await getGoldDataRange(factoryId); dateRange.value = [dr.minDate, dr.maxDate]` (全部历史);保留 shortcuts 供手动缩小。
  - trends/index.vue:28 `selectedPeriod='week'` → 默认全部历史 (或 'all' 选项),调 WS1 `/trend-bundle` (默认不传日期=全部)。
  - kpi/index.vue:40 `?days=30` → 去掉 days 限制或传大窗口=全部 (调 gold kpi-summary 不传日期)。
  - FinancialDashboardPBI.vue: PeriodSelector 默认 type 从 'year' → 全部历史 (或保留 year 但加"全部"选项作默认)。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `fix(analysis): 销售/趋势/KPI/财务默认全部历史 (修无数据 #10/#11/#12)`

---

## Task 2: 新建 BusinessAnalysisHub 两级 tab 容器

**Files:** Create `web-admin/src/views/smart-bi/BusinessAnalysisHub.vue`
模板参考: `FinancialDashboardPBI.vue` 的 section-tabs (el-tabs v-model=sectionTab + ?section= query 同步)。

- [ ] **Step 1: 写测试** — 挂载 hub → 渲染 4 个 tab (财务/销售/趋势/KPI·指标);`?tab=sales` query → 默认激活销售 tab;切 tab 更新 query (保书签)。
- [ ] **Step 2: 跑确认失败** (组件不存在)
- [ ] **Step 3: 实现**
```vue
<!-- BusinessAnalysisHub.vue -->
<template>
  <div class="business-analysis-hub">
    <el-tabs v-model="activeTab" @tab-change="syncQuery">
      <el-tab-pane label="财务" name="finance"><FinancialDashboardPBI /></el-tab-pane>
      <el-tab-pane label="销售" name="sales"><SalesAnalysis /></el-tab-pane>
      <el-tab-pane label="趋势" name="trend"><TrendsView /></el-tab-pane>
      <el-tab-pane label="KPI·指标" name="kpi"><KpiView /></el-tab-pane>
    </el-tabs>
  </div>
</template>
<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import FinancialDashboardPBI from './FinancialDashboardPBI.vue';
import SalesAnalysis from './SalesAnalysis.vue';
import TrendsView from '@/views/analytics/trends/index.vue';
import KpiView from '@/views/analytics/kpi/index.vue';
const route = useRoute(); const router = useRouter();
const VALID = ['finance','sales','trend','kpi'];
const activeTab = ref(VALID.includes(String(route.query.tab)) ? String(route.query.tab) : 'finance');
function syncQuery(name: string) { router.replace({ query: { ...route.query, tab: name } }); }
</script>
```
(指标中心 IndicatorCenterDashboard 若内容独立, 作为 KPI tab 的子 tab 或并入 kpi/index;实现者按内容量决定 KPI·指标 是合一还是内层 tab)
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(analysis): BusinessAnalysisHub 两级 tab 容器 (财务/销售/趋势/KPI)`

---

## Task 3: 路由 + redirect (合并入口, 保书签)

**Files:** `web-admin/src/router/modules/smartbi.ts`, `web-admin/src/router/index.ts`

- [ ] **Step 1: 写测试** — 路由表含 `/smart-bi/analysis-hub` → BusinessAnalysisHub;旧路径 redirect 保 query:
  - `/smart-bi/sales` → `/smart-bi/analysis-hub?tab=sales`
  - `/analytics/trends` → `/smart-bi/analysis-hub?tab=trend`
  - `/analytics/kpi` → `/smart-bi/analysis-hub?tab=kpi`
  - `/smart-bi/financial-dashboard` → `/smart-bi/analysis-hub?tab=finance`
  - `/indicator-center` → `/smart-bi/analysis-hub?tab=kpi`
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现** — 加 hub 路由 (smartbi.ts children);加函数式 redirects (参考现有 `smartBIRedirects` line 116-121 的 `(to)=>({path,query:{...to.query}})` 模式)。原 component 路由保留 (redirect 优先) 以防直接深链。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(analysis): analysis-hub 路由 + 6 页 redirect 保书签`

---

## Task 4: 删除冗余页 (#9) — 隐藏 + redirect

**Files:** `web-admin/src/router/modules/smartbi.ts`, `menuConfig.ts`

删: AI分析报告 (`/analytics/ai-reports`), What-If (`/smart-bi/whatif`), 餐饮V2 (`/smart-bi/restaurant-v2`), Gold预览 (`/smart-bi/gold-preview`)。What-If/V2/GoldPreview 已不在 menuConfig (仅路由存在);AI分析报告在 menuConfig line 261。

- [ ] **Step 1: 写测试** — menuConfig analytics group **不含** AI分析报告/What-If/V2/Gold预览;`/analytics/ai-reports` redirect 到 `/smart-bi/analysis-hub` (或 AI问答);其余直链 redirect 到 hub/dashboard。
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现** — menuConfig 删 AI分析报告条 (261);加 4 条 redirect (旧路径→hub 或 dashboard);component 路由可保留但 menu 隐藏 (低风险, 可回滚)。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(analysis): 删冗余页 AI分析报告/What-If/餐饮V2/Gold预览 (menu 隐藏+redirect #9)`

---

## Task 5: menuConfig analytics group 精简

**Files:** `web-admin/src/components/layout/menuConfig.ts:250-291`

目标 analytics group (大刀后):
```
经营驾驶舱
[AI 探索] AI 问答/数据分析
[经营分析] 经营分析 (新 hub, 含财务/销售/趋势/KPI)
            收入管理报表 (hideForFactoryTypes FACTORY)
            异常预警 (折叠保留)
            进销存总览/车间报表/生产分析/人效 (hideForFactoryTypes RESTAURANT — 工厂侧, 不动)
[数据管理] Excel 上传 / 查询模板 / 数据完整度
```
移出到系统管理 (或 AI 运维子组保留但移到末尾/admin-only): 知识库反馈 / AI 追问日志 / 行为校准。

- [ ] **Step 1: 写测试** — `menuConfig.spec.ts`: analytics group 含"经营分析"项 (path `/smart-bi/analysis-hub`);**不含** 销售分析/趋势分析/KPI看板/指标中心/财务看板 独立项 (已并入 hub);不含 AI分析报告;餐饮租户 (RESTAURANT) 看不到 进销存/车间/生产/人效 (hideForFactoryTypes)。
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现** — 改 menuConfig 250-291: 删 6 项独立条,加"经营分析"一条;删 AI分析报告;AI运维三项移到 admin 区或加 `roles:['platform_admin']`。保留 hideForFactoryTypes 门控。
  - ⚠️ 同步 `menuConfig.spec.ts` 里硬编码的菜单名断言 (l1-smoke fixture, 见 feedback_e2e_pr_gate),否则 CI 红。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(analysis): menuConfig analytics group 大刀精简 (6页合1+删冗余+移AI运维)`

---

## Task 6: 部署 + headed prod 验证

- [ ] **Step 1: merge main + 部署前端** `deploy-web-admin.sh --env prod`
- [ ] **Step 2: headed Playwright (9223, zh-CN, headless:false) qhj_prod / 8086**:
  - 侧栏 analytics group 精简: 只剩 经营驾驶舱 / AI问答 / 经营分析 / 收入管理报表 / 异常预警 / 数据管理 (无 销售分析/趋势/KPI/指标中心/财务看板 独立项, 无 AI分析报告) ✓
  - 点"经营分析" → 4 tab (财务/销售/趋势/KPI), 各 tab 出真数据 (默认全部历史, 无"所选区间无数据") ✓
  - 旧书签 `/smart-bi/sales` → redirect 到 analysis-hub?tab=sales ✓
  - `/analytics/ai-reports` → redirect 不 404 ✓
  - fullPage 截图
- [ ] **Step 3: 截图 + verification block 入 audit doc**

---

## Self-Review
- ✅ #8 AI问答慢/默认分析 → 注: AI问答页 (smart-bi/analysis) gold 化 + 缓存属 WS1 缓存 + 本 WS 默认分析; 若 #8 深度未覆盖, 单列 follow-up
- ✅ #9 删冗余 → Task 4
- ✅ #10/#11/#12 默认全部历史 + 趋势定位 → Task 1 + 合并入 hub
- ✅ 6页合1 → Task 2/3/5
- ✅ menuConfig spec 同步 → Task 5 Step 3 (防 CI 红)
- ✅ headed 验证 → Task 6
- 注意: 财务分析(FinanceAnalysis)已 redirect 入 FinancialDashboardPBI;AI问答已 redirect 入 smart-bi/analysis — 这两个已合并, 本 WS 把它们再归到 hub 下统一
