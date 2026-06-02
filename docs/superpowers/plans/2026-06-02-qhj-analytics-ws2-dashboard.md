# WS2 — 经营驾驶舱 全 gold + 内容修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 经营驾驶舱删"选择数据源"选择器、默认全部历史走 gold;修 AI 洞察括号标记 (#5)、快捷问答 chip 卡住 (#6)、给餐饮营收分析段加洞察 (#7)。

**Architecture:** `Dashboard.vue` 删 `selectedDataSource`/`onDataSourceChange`/`loadDataSources`,所有路径走 gold (`loadDashboardData` + `getGoldDataRange` 默认全部历史);括号标记 `[毛/应收][按营业额]` 在 `factbook.py` 渲染 + `orchestrator.py` SYSTEM_PROMPT 强制 → 从 prompt + factbook 移除 (根治, 不前端 strip);chip `goToAIQuery` 跳 AIQuery 后确认 AIQuery 读 `route.query.q` 并自动提交;`RestaurantGoldGrid` 加一个轻量洞察调用。

**Tech Stack:** Vue 3 (Dashboard.vue / RestaurantGoldGrid.vue / AIQuery.vue), Python (factbook.py / orchestrator.py)。

**依赖:** WS1 (端点全部历史 + 缓存)。

**部署:** 前端 `deploy-web-admin.sh --env prod` (8086);Python `deploy-smartbi-python.sh --env prod`。验证 headed Playwright (9222, zh-CN, headless:false)。

---

## File Structure

| 文件 | 动作 |
|---|---|
| `web-admin/src/views/smart-bi/Dashboard.vue` | 删数据源选择器 (127,126,1138-1159,1775-1793,loadDataSources);默认全部历史 |
| `backend/python/smartbi/agent/factbook.py:191,201,220,223` | 去 `[毛/应收][毛][按营业额]` 括号标记 |
| `backend/python/smartbi/agent/orchestrator.py:113-118` | SYSTEM_PROMPT 去"必须紧跟 [毛]/[净]"要求,改为禁止方括号标记 |
| `web-admin/src/views/smart-bi/AIQuery.vue:60-65` | 确认 mount 读 route.query.q 并自动提交 (修 chip 卡住) |
| `web-admin/src/views/smart-bi/components/RestaurantGoldGrid.vue` | 加营收分析洞察块 (#7) |

---

## Task 1: 删除数据源选择器, 默认全部历史 gold

**Files:** Modify `web-admin/src/views/smart-bi/Dashboard.vue`

- [ ] **Step 1: 写组件测试 (vitest)** — 断言挂载后不渲染"选择数据源"select, 且默认调 gold 路径。

```ts
// web-admin/src/views/smart-bi/__tests__/Dashboard.datasource.spec.ts
import { mount } from '@vue/test-utils';
// mock getGoldDataRange / loadDashboardData 依赖; 断言:
// 1. wrapper.find('[data-test="datasource-select"]').exists() === false
// 2. loadDashboardData 被调用 (gold), loadDynamicDashboardData 不被调用
```
(实现者补 `data-test="datasource-select"` 标记到原 select 以便先红后绿;若全删则断言 text 不含"选择数据源")

- [ ] **Step 2: 跑确认失败** → `cd web-admin && npx vitest run src/views/smart-bi/__tests__/Dashboard.datasource.spec.ts`

- [ ] **Step 3: 删选择器 + 状态 + 改默认全部历史**
  - 删模板 `Dashboard.vue:1775-1793` (整个 `<el-select v-model="selectedDataSource" @change="onDataSourceChange">` 块)。
  - 删 `selectedDataSource` (127)、`dataSources` (126)、`onDataSourceChange` (1138-1159)、`loadDataSources` (1117-1136) 及其在 `onMounted`/初始化里的调用。
  - 所有数据加载固定走 gold: 直接 `loadDashboardData()`。
  - 默认全部历史: 复用已有 `getGoldDataRange(factoryId)` (694-710) 设 `dateRange.value = [dr.minDate, dr.maxDate]` (而非 `period=month`);若 `loadDashboardData` 用 `period` 参数,改成传 custom range = 全部。保留 `dateRange` 选择器 + shortcuts (用户可手动缩小),默认值 = 全部历史。

- [ ] **Step 4: 跑确认通过** → 测试绿

- [ ] **Step 5: Commit**
```bash
git add web-admin/src/views/smart-bi/Dashboard.vue web-admin/src/views/smart-bi/__tests__/Dashboard.datasource.spec.ts
git commit -m "feat(dashboard): 删数据源选择器, 默认全部历史走 gold (#4)"
```

---

## Task 2: KPI 卡全 gold (去"需上传"占位)

**Files:** Modify `web-admin/src/views/smart-bi/Dashboard.vue:1878-2000`, 参考 `web-admin/src/components/CapabilityGate.vue:46-61`

四张 KPI 卡 (营业额/客单价/订单数/门店数) 现用 `<CapabilityGate :requires=...>` 包,qhj 走 gold 时 `findCard('total_revenue')` 等已有值,但 CapabilityGate 的 `requires` (如 `source_bill_no`) 在某些卡上误判 → 显示"此分析需上传含...的数据"。

- [ ] **Step 1: 测试** — gold 数据存在时四卡都渲染数值, 不渲染"需上传"占位。
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现** — gold KPI 数据 (kpi-summary 端点, WS1) 直接喂四卡;当 gold 值存在时**跳过 CapabilityGate**(gold 模式不需要 upload 能力门控)。即: `<CapabilityGate>` 仅在非 gold (上传分析) 模式包裹;gold 模式直接渲染卡。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(dashboard): KPI 卡 gold 模式直出, 跳过 upload 能力门控`

---

## Task 3: 去 AI 洞察括号标记 (#5) — 根治在 Python

**Files:** Modify `backend/python/smartbi/agent/factbook.py`, `backend/python/smartbi/agent/orchestrator.py`
**Test:** `backend/python/tests/test_factbook_no_brackets.py`

`[毛/应收]`/`[毛]`/`[按营业额]` 来自 factbook 渲染 (191,201,220,223) + orchestrator SYSTEM_PROMPT 强制 (113-118)。客户觉得像 JSON 难读。根治: factbook 不再加方括号基准标记;prompt 改为"用自然语言说明口径 (如'按营业额计'),禁止输出方括号标记"。grounding 对账 (fact_reconciler) 不依赖方括号本身 (它按指标名+数字匹配),所以去掉安全。

- [ ] **Step 1: 写失败测试**
```python
# test_factbook_no_brackets.py
import re
from smartbi.agent.factbook import _render_finance, _render_sales  # 按实际导出调整
def test_finance_no_bracket_tags():
    txt = _render_finance({...合成 finance facts...})
    assert not re.search(r'\[(毛|净|按[^\]]+|毛/应收)\]', txt)
def test_sales_no_bracket_tags():
    txt = _render_sales({...合成 sales facts...})
    assert not re.search(r'\[(毛|净|按[^\]]+)\]', txt)
```
- [ ] **Step 2: 跑确认失败** (当前含 `[毛/应收]` 等)
- [ ] **Step 3: 实现**
  - factbook.py 191/201/220/223: 去掉 `[毛/应收]`/`[毛]`/`[按营业额]` 后缀 (改成普通数字 + 必要时自然语言"(按营业额)")。
  - orchestrator.py SYSTEM_PROMPT 113-118: 删"金额必须紧跟 [毛]/[净]、百分比必须紧跟 [按营业额]"的要求,改为:"口径用自然语言简述 (如'按营业额'),**禁止输出方括号标记如 [毛]/[按金额]**"。
  - 更新 prompt 内的 few-shot 正/反例同步。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: 清缓存** — 改 prompt 后必须清 narrative/insight 缓存否则旧文案残留: `DELETE FROM narrative_cache WHERE factory_id='RES_3101_009'` (prod, 部署后)。记入 Task 6 验证。
- [ ] **Step 6: Commit** `fix(insight): 去 AI 洞察方括号基准标记, 改自然语言口径 (#5)`

---

## Task 4: 修快捷问答 chip 卡住 (#6)

**Files:** `web-admin/src/views/smart-bi/Dashboard.vue:1721-1727` (goToAIQuery) + `web-admin/src/views/smart-bi/AIQuery.vue:60-65` (route.query.q 处理)

chip @click → `goToAIQuery(q.text)` → `router.push({name:'SmartBIQuery', query:{q}})`。"卡住/不跳"根因: AIQuery 已挂载时 (同路由 name 不同 query) Vue 不重建组件 → 不会重新读 q;或 mount 时读了 q 但没自动提交。

- [ ] **Step 1: 写测试** — AIQuery 挂载带 `route.query.q='畅销品 Top 5'` → `inputQuery` 被填充且 `handleSendMessage` 被调用; 且 `watch(route.query.q)` 在已挂载时也触发。
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现** — AIQuery.vue: `onMounted` 读 `route.query.q` → 填 `inputQuery` + 自动 `handleSendMessage()`;并加 `watch(() => route.query.q, (q)=>{ if(q){ inputQuery.value=q; handleSendMessage(); }})` 处理已挂载情况。Dashboard goToAIQuery 不变。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `fix(aiquery): chip 跳转后自动读 q 并提交 (修卡住 #6)`

---

## Task 5: 餐饮营收分析段加洞察 (#7)

**Files:** `web-admin/src/views/smart-bi/components/RestaurantGoldGrid.vue`

该组件 (门店营收排行 + 渠道占比) 现只取 finance-summary + channel-breakdown,无洞察。加一个轻量洞察 (顶部一两句: 最高/最低门店差距、堂食外卖占比解读)。优先**本地规则生成** (rules-first, 0 LLM): 从已有 finance-summary/channel 数据算出关键差距,模板化成一句话;数据不足才留空 (诚实, 不编)。

- [ ] **Step 1: 写测试** — 给定 finance-summary (门店营收数组) + channel,`buildRevenueInsight()` 返回含最高门店名 + 堂食占比的句子;空数据返 null (不渲染)。
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现** — RestaurantGoldGrid 加 `buildRevenueInsight(finance, channel)` 纯函数 + 顶部洞察条 (有内容才渲染)。规则: "营收最高 {top} ¥{x}万, 是末位 {bottom} 的 {n} 倍;堂食占 {p}%。" 数字用已有数据, 不调 LLM。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(dashboard): 餐饮营收分析段加规则洞察 (#7)`

---

## Task 6: 部署 + headed prod 验证

- [ ] **Step 1: merge main + 部署** — Python (factbook/orchestrator) `deploy-smartbi-python.sh --env prod`;前端 `deploy-web-admin.sh --env prod`。
- [ ] **Step 2: 清缓存** — `DELETE FROM narrative_cache WHERE factory_id='RES_3101_009'` (prod, 否则旧括号文案残留)。
- [ ] **Step 3: headed Playwright (9222, zh-CN, headless:false) 验证 qhj_prod / 8086**:
  - 驾驶舱**无**"选择数据源"选择器 ✓
  - KPI 四卡出真数值 (无"需上传") ✓
  - AI 洞察**无** `[毛/应收][按营业额]` 方括号 ✓
  - 点快捷问答 chip → **秒跳** AI 问答并出答案 ✓
  - 餐饮营收分析段有洞察句 ✓
  - 中文字体无方块, fullPage 截图存档
- [ ] **Step 4: 截图 + verification block 入 audit doc**

---

## Self-Review
- ✅ #4 数据源/默认全部 → Task 1/2
- ✅ #5 括号标记 → Task 3
- ✅ #6 chip 卡住 → Task 4
- ✅ #7 营收洞察 → Task 5
- ✅ headed 验证 → Task 6 (playwright-headed-mode 规则)
- 缓存清理 (改 prompt 必须) → Task 6 Step 2
