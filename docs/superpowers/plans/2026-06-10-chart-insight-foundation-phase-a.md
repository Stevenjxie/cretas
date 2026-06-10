# Chart-Insight 全局化 Phase A 地基 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (in-harness Sonnet, fresh subagent per unit, Opus two-stage review). Steps use `- [ ]`. **Workers have the spec + `.claude/rules` + codebase** — implement via TDD against the contracts below; don't re-derive decisions. 🔒 单元只做到"实现+自测+PR off origin/main", 不自部署 prod — Opus 终审 + 从 main 部署 + headed real-path 验真。

**Goal:** 彻底硬化自蒸馏飞轮(8 项含红线) + 抽可复用洞察机制(单图 composable + 数组直调) + 补全 Tier1 族(PROPORTION/COMPARISON/KPI) + 共享 deriveChartMeta + 修上传分析 meta 死接线 + 现有 6 图接回 Tier2 自动飞轮。

**Architecture:** Tier1 规则(0 token)主 + Tier2-on-null 自动(LLM→蒸馏入库, **跨租户模板**)。单图响应式面用 `useChartInsight` composable；数组面(SmartBIAnalysis)直调 deriveChartMeta+buildChartInsight+fetchTier2。

**Tech Stack:** Python/FastAPI/asyncpg (smartbi 8083) · Vue3/TS (web-admin) · PostgreSQL (smartbi_db) · DashScope LLM。

**Spec:** `docs/superpowers/specs/2026-06-10-chart-insight-foundation-phase-a-design.md` (v2，4-critic 审计后；含审计裁决表 + 全部 file:line)。

---

## 文件结构
| 文件 | 职责 | 单元 |
|---|---|---|
| `backend/python/smartbi/services/insights/chart_insight_service.py` (改) | 飞轮硬化 8 项 (slot 白名单/安全网/budget/poison-¥-全字段/token/不覆盖/签名去 factoryId/temperature) | U1 |
| `backend/python/smartbi/api/chart_insight.py` (改) | permission_tier 服务端推; eager init | U1 |
| `backend/python/smartbi/database/migrations/V<新号>__chart_insight_cross_factory.sql` (新, 若需) | UNIQUE 去 factory_id (跨租户模板) | U1 |
| `backend/python/smartbi/tests/test_chart_insight_service.py` (改) | U1 单测 + fixture data_pattern 修 | U1 |
| `web-admin/src/views/smart-bi/components/chartInsight.ts` (改) | Tier1 族补全(PROPORTION/COMPARISON/KPI) + deriveChartMeta + showAbsolute 死码清 + slot% 约定 | U2+U3 |
| `web-admin/src/views/smart-bi/components/__tests__/chartInsight.spec.ts` (改) | U2+U3 单测 | U2+U3 |
| `web-admin/src/composables/useChartInsight.ts` (新) | 单图复用编排 + 2-slot 限流 + stale-guard + perms fail-safe | U4 |
| `web-admin/src/composables/__tests__/useChartInsight.spec.ts` (新) | U4 单测 | U4 |
| `web-admin/src/api/smartbi/analysis.ts` (改) + `SmartBIAnalysis.vue` (类型) | chartsLane 后处理挂 meta | U5 |
| `web-admin/src/views/smart-bi/components/RestaurantGoldGrid.vue` + `Dashboard.vue` (改) | 6 图迁 composable + 接回 Tier2 | U6 |

## 契约（锁定决策 — 所有单元遵守，勿再决）
- **签名** (U1): `SHA256(chartType|xDim|yMetric|aggregation|domain|dataPattern|permission_tier)` — **无 factoryId**(跨租户模板)。
- **permission_tier**: 服务端按 `caller_role ∈ FINANCE_ROLES ? 'finance_visible' : 'finance_hidden'` 推; 忽略 body。
- **slot 白名单**(唯一合法): `topName botName topShare ratio concLevel growthRate changeAmt changeDir`; 值含 `%`(模板勿再加)。
- **budget None**: eager init + 兜底 fail-closed(返 None+WARN, 不 LLM)。
- **poison + 绝对¥ 校验**: finding+implication+suggestion **三字段全查**(capture+promote)。
- **InsightResult/ChartMeta** 类型同现有 chartInsight.ts (不改契约)。
- **诚实空**: 任一层 null → 不显; 禁假数据; 禁显 `{slot}`。RBAC: 只 %/倍/标签, KPI 族非财务无 ¥。

---

## Task U1 — 完整飞轮硬化 (🔒 RBAC/蒸馏/防毒, Python)
**Files:** Modify `chart_insight_service.py` + `api/chart_insight.py`; Test `tests/test_chart_insight_service.py`; (若 UNIQUE 需改) new migration `V<现有最高+1>__chart_insight_cross_factory.sql`(合并前查 origin/main 最高号防撞)。

- [ ] **U1.1 slot 白名单 (prompt)**: 在 `_build_insight_prompt` (~661-684) 加穷举白名单指令 + 禁列表外。写测试: 给 mock LLM 返含 `{topChannel}` 的模板 → 经 U1.2 安全网 → `get_insight` 返 None(不显破)。
- [ ] **U1.2 填充安全网**: 新增 `_safe_fill(tpl, slot_values) -> str|None`(填后 `_SLOT_RE.search` 命中→None)。替换 2 个逻辑 call site(~339-341 / ~419-421)的 `_fill_slots`; finding 为 None→整个 InsightResult None, implication/suggestion 单字段 None。测试: 含未知 slot→None; 全合法 slot→正常填。
- [ ] **U1.3 budget None 守卫**: `_get_service` 改 eager(pool 为 None 不缓存 `_service`, 返 None→端点 503); `get_insight` ~310/328 包 `_check_budget`/`_consume`: tracker None → **fail-closed**(check 返 blocked=False 但记 WARN? 不——裁定 fail-closed: 返 None 不调 LLM)。测试: tracker=None → get_insight 返 None 不抛。
- [ ] **U1.4 permission_tier 服务端推 (🔴)**: `api/chart_insight.py` 端点按 `caller_role` 推 permission_tier(定义 `FINANCE_ROLES` 集), 覆盖 ctx.permission_tier, 忽略 body(或从 `ChartInsightRequest` 移除该字段)。内部调用(auth_method=='internal')从 X-User-Role 同理推。测试: 非财务 role + body permission_tier='finance_visible' → 实际用 'finance_hidden'。
- [ ] **U1.5 poison+¥ 全字段**: `_capture_template`(~331) + `_maybe_promote`(~621) 的 `_contains_poison` 与 `_ABSOLUTE_AMOUNT_RE` 扩到 finding+implication+suggestion 三字段, 任一命中→拒捕获/不 promote。测试: implication 含"扩张"→拒; suggestion 含"¥12345万"→拒。
- [ ] **U1.6 token 真实计量**: `consume` 用真实 in+out 估算(无精确则 flat ~800), 非 100。测试: 一次 LLM → consume ≥600。
- [ ] **U1.7 模板不覆盖**: `ON CONFLICT DO UPDATE`(~563) 去掉 `insight_template = EXCLUDED.insight_template`, 仅 `proposal_count = +1, updated_at = NOW()`。测试: 同签名二次捕获 → insight_template 不变, proposal_count++。
- [ ] **U1.8 签名去 factoryId**: `compute_signature`(~109-125) 移除 factoryId。lookup(`_lookup_template` ~369-399) 不再 factory-scope(去 WHERE factory_id 或 UNIQUE 改 signature_hash 单列 — 若改 UNIQUE 写 migration)。测试: 两个 factory 同 (chartType/xDim/.../permission_tier) → 同 signature_hash → 命中同模板。**🔒 迁移**: UNIQUE(signature_hash, factory_id)→UNIQUE(signature_hash); Opus 审 SQL + 合并前查号防撞(`git ls-tree origin/main backend/python/smartbi/database/migrations | grep -oE 'V[0-9]{8}_[0-9]{2}'|sort|uniq -d`)。
- [ ] **U1.9 杂项**: temperature→0.1(`_call_llm` ~478); lookup 加 `AND (suggestion_tpl IS NULL OR is_verified)`; 测试 fixture `cat-count:4-8`→`n4-8`(对齐前端 computeDataPattern)。
- [ ] **U1.10 跑全部 U1 单测 PASS** (`python -m pytest backend/python/smartbi/tests/test_chart_insight_service.py -v`) + commit(scope: 2 py + 1 test + 可能 migration)。
**验收:** 非法 slot→安全网 None / budget None→不崩 / poison+¥ 三字段拦 / permission_tier 从 role 推 / 跨租户同签名命中 / 模板不覆盖 / promote 阈值=3。**🔒 Opus 终审 SQL+RBAC+迁移号。**

## Task U2+U3 — Tier1 族补全 + deriveChartMeta (🔒 RBAC, 同文件合并)
**Files:** Modify `chartInsight.ts`; Test `__tests__/chartInsight.spec.ts`。⚠️ 一个 subagent 串行做(同文件)。

- [ ] **U2.1 PROPORTION 族**: 写失败测试(PIE/占比, ≥2 切片 → "X 占 N%, 前二占 M%", 无绝对¥, 观察动词)。实现 `buildProportionInsight` + 在 `buildChartInsight` 路由(chartType PIE → PROPORTION, 不再 null)。跑 PASS。
- [ ] **U2.2 COMPARISON 族**: 失败测试(2 系列 → "A 较 B 高 N%")。实现 `buildComparisonInsight` + 路由(meta 含 2 可比系列)。PASS。
- [ ] **U2.3 KPI 族 (🔴 ¥守卫)**: 失败测试(actual+target → "达成 N%"; **isFinanceMetric && !canViewFinance → 只 %, 断言无 ¥**)。实现 `buildKpiInsight`。PASS。
- [ ] **U2.4 修 showAbsolute 死码**: RANKING 两分支现同串 → 删 `showAbsolute` 或实现差异(本期: 删, 统一 %/倍)。回归测试 RANKING 不变。
- [ ] **U3.1 deriveChartMeta**: 失败测试(各 xDim/yMetric 分支 + 扩词表: 供应商/客户/批次/部门/工序/原材料/科目/渠道/平台 → xDim; 损耗/领料/采购/费用/应收/应付 → yMetric; 线索不足→null)。实现 `export function deriveChartMeta(plan, monthlyColumns, dataInfo): ChartMeta|null`(总函数永不抛, aggregation 默认 sum, domain 默认 factory)。PASS。
- [ ] **U2U3.5 全 spec 跑** (`npx vitest run .../chartInsight.spec.ts`) + `npx vue-tsc --noEmit` + commit(scope: chartInsight.ts + spec.ts)。
**验收:** 三族正例/null 契约/无因果词/KPI 非财务无¥; deriveChartMeta 扩词表分支; TREND/RANKING 不回归。**🔒 Opus 审 RBAC(KPI ¥守卫)。**

## Task U4 — useChartInsight composable (🔒 成本护栏)
**Files:** Create `composables/useChartInsight.ts` + `composables/__tests__/useChartInsight.spec.ts`。依赖 U2+U3。
- [ ] **U4.1 签名+Tier1**: 失败测试(source 有 chart → 调 buildChartInsight → insight set; Tier1 命中不调 Tier2)。实现 watch source → Tier1。PASS。
- [ ] **U4.2 Tier2-on-null + loading**: 测试(Tier1 null + autoTier2 → loading true → await fetchTier2 → insight; 失败→null 诚实空)。实现。PASS。
- [ ] **U4.3 stale-guard**: 测试(factoryId 变 → 旧 await 结果丢弃)。实现 token 捕获。PASS。
- [ ] **U4.4 2-slot 限流**: 测试(模块级信号量 max 2 in-flight + 去抖; 第 3 个排队)。实现。PASS。
- [ ] **U4.5 perms fail-safe**: 测试(perms 缺省 → canViewFinance=false=finance_hidden, 不泄露)。实现。PASS。
- [ ] **U4.6 commit**(scope: useChartInsight.ts + spec)。
**验收:** Tier1 命中不调 Tier2 / null 调 Tier2 / 2-slot 不超并发 / stale 丢弃 / 错误诚实空 / 缺 perms→finance_hidden。**🔒 Opus 审成本护栏。**

## Task U5 — 上传分析 meta 修复 (禁假数据)
**Files:** Modify `web-admin/src/api/smartbi/analysis.ts` + `SmartBIAnalysis.vue`(类型)。依赖 U3。
- [ ] **U5.1 类型扩**: `flowResult.charts` 元素类型(analysis.ts:1568 + SmartBIAnalysis.vue:687)加 `meta?: ChartMeta | null`。`npx vue-tsc` 通过。
- [ ] **U5.2 后处理挂 meta**: 在 chartsLane 末尾(~1717, **onProgress 之后单遍**)对所有 charts `chart.meta = deriveChartMeta(plan, monthlyColumns, dataInfo)`(**非 3 个 build site 内联**)。
- [ ] **U5.3 build + commit**(`npm run build`; scope: 2 文件)。
**验收(headed real-path)**: 上传一张含日期/营收列的表 → 图表下出现数据驱动洞察(之前全空)。Opus headed 验。

## Task U6 — 现有 6 图迁 composable + 接回 Tier2 (🔒 部署)
**Files:** Modify `RestaurantGoldGrid.vue`(storeInsight/channelInsight) + `Dashboard.vue`(trendInsight/categoryInsight)。依赖 U4。
- [ ] **U6.1 RestaurantGoldGrid 迁移**: 2 卡改用 `useChartInsight`(autoTier2=true), 保留现有 explicit meta(store/channel)。vue-tsc + 现有渲染不回归。
- [ ] **U6.2 Dashboard 迁移**: trend/category 改用 composable, **保留 categoryInsight PIE→BAR 归一化**(Dashboard.vue:538-604)喂归一后数据给 composable。vue-tsc。
- [ ] **U6.3 build + commit**(scope: 2 vue)。
**验收(headed real-path)**: 6 图仍显(Tier1); 造一个 Tier1-null 图 → Tier2「AI生成」→刷新→「数据驱动·已学习」(跨租户模板 threshold 收敛)。**🔒 Opus gate + 从 main 部署 + headed 验飞轮闭环。**

---

## Self-Review
- **Spec 覆盖:** U1=飞轮 8 项+杂项✅ / U2=三族+KPI守卫+showAbsolute✅ / U3=deriveChartMeta 扩词表✅ / U4=composable 5 行为✅ / U5=meta 后处理+类型✅ / U6=迁移+Tier2+PIE归一✅。审计裁决表每条 BLOCKER→对应 U1.x。✅
- **占位符:** 无 TBD; 契约/file:line/验收具体; 红线标注。✅
- **类型一致:** InsightResult/ChartMeta/deriveChartMeta 签名跨单元一致。✅
- **红线:** U1(RBAC/迁移)/U2(KPI¥)/U4(护栏)/U5(假数据)/U6(部署) 全标 Opus 终审 + headed 验真。✅

## 🚦 分发总览
| 单元 | 模型 | 并行 | 依赖 | scope 锁 | 🔒 |
|---|---|---|---|---|---|
| U1 | Sonnet in-harness | ✅ | — | chart_insight_service.py + api/chart_insight.py + test(+migration) | 🔒 |
| U2+U3 | Sonnet in-harness | ✅(与U1) | — | chartInsight.ts + spec.ts | 🔒 |
| U4 | Sonnet | ❌ | U2+U3 | composables/useChartInsight.ts | 🔒 |
| U5 | Sonnet | ✅(与U4, 不同文件) | U3 | analysis.ts + SmartBIAnalysis.vue | — |
| U6 | Sonnet→Opus | ❌ | U4 | RestaurantGoldGrid.vue + Dashboard.vue | 🔒 |
> 隔离: 各 worktree off origin/main; commit 锁 scope(`git commit -- F1 F2`); prod 从 main, 红线单元 Opus gate + headed real-path 验真(本程序多次被"单测过 prod 不 fire"骗)。
> 关键路径: U1 ‖ (U2+U3) → U4 → U6; U5 跟 U4 并行(依赖 U3)。
