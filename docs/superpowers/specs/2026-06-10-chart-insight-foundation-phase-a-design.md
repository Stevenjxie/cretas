# Chart-Insight 全局化 — Phase A 地基 设计

**日期**: 2026-06-10
**状态**: 设计定稿（brainstorm 后，待 Steve spec review → writing-plans）
**触发**: Steve 要"全局所有图表分析都是 图表+洞察，且都跑自蒸馏飞轮"。Organizer 接管后 real-path 求证发现现状远未达成 → 拆成多阶段程序，本 spec 是 **Phase A 地基**。

---

## 0. 背景 / 现状全景（两份只读分析 agent 综合）

- **真正有 chart-insight Tier1 的只有 6 个图**（驾驶舱 销售趋势+产品类别占比 / 餐饮营收 门店排行+渠道占比 + 本批刚做）。
- **~44 个图表面零洞察**，渲染机制 5-6 种（chartInsight Tier1 / 执行摘要 LLM panel / DynamicChartRenderer / 直接 echarts.init / TemplateCard / 后端洞察数组）。完整盘点见 dispatch 分析（`aa32e73e` agent 输出，51 行表）。
- **上传分析（SmartBIAnalysis.vue）的 mini-insight 接了但 `chart.meta=null` → 实际全空**（死接线）。
- **Tier2 自蒸馏飞轮**：表 `ai_insight_templates` + service + 端点都建好、已部署，但有 **3 个真 bug** 且**当前零调用方**。

### Steve 拍板的范围/策略
1. **范围 = 字面全部 ~44 面** + 给 Tier1 补全图表族（含后续异形图 Gantt/Sankey/热力新族 = Phase D）。
2. **Tier2 触发 = Tier1 主 + Tier2-on-null 自动**（每图先 Tier1 0-token 即时；null 才自动调 Tier2 LLM → 蒸馏入库 → 下次同签名 0 token）。真飞轮。

### 程序路线图（每阶段自己的 spec→plan→分发）
- **Phase A（本 spec）**：飞轮修复 + 可复用洞察机制 + Tier1 族补全 + meta 推断器 + 上传分析 meta + 现有 6 图接回 Tier2。**地基**：让"有洞察的面"都进飞轮，且后续每加一面 ~3 行。
- **Phase B**：铺 Trivial + 高价值 Moderate（趋势/平台口碑/门店对比/目标拆解/销售排行/财务 PBI 图族）。
- **Phase C**：剩余 Moderate（DynamicChartRenderer 系 / FinanceAnalysis / 餐饮运营 ~10 图 / 生产·人效分析）。
- **Phase D**：异形图新族（Gantt/Sankey/热力/WhatIf）—— 建新 Tier1 族或走 Tier2-LLM。

> B/C/D 在 Phase A 地基之上是**机械 fan-out**（每面 ~3 行：构 meta + 挂 `<ChartInsight>`），可批量并行分发。

---

## 1. 架构 — 单一复用数据流

```
任意图表 surface
  → useChartInsight(getData, metaOrDeriver, perms)   ← 唯一接入方式（复用 composable）
      ├ 1. meta = 显式传入 或 deriveChartMeta(图表线索)
      ├ 2. Tier1: buildChartInsight(chart, perms)      ── 0 LLM token, 即时, 确定性
      │     命中 → InsightResult(source='rules', tier:1)
      └ 3. null → Tier2: fetchTier2Insight(chart, perms, factoryId)  ── LLM→蒸馏入库
            命中模板 → source='template'(tier:2) | 新生成 → source='llm'(tier:2) | 失败 → null(诚实空)
  → reactive { insight, loading } → <ChartInsight :insight :loading depth>
```

**关键不变量**：
- 每个 surface 只依赖 `useChartInsight` + `<ChartInsight>` 两个接口，不碰 chartInsight.ts / chart_insight_service.py 内部。
- Tier1 永远先跑（0 token）；Tier2 仅在 Tier1 null 时自动触发，且受成本护栏约束。
- 诚实空：任一层 null → 不显（禁降级/禁假数据）。
- RBAC by construction：洞察只用 %/倍/标签，非财务角色绝不出绝对 ¥；Tier2 permission_tier 从 JWT，server gates。

---

## 2. 单元（U1–U6）与契约

### U1 — Python 飞轮修复（🔒 蒸馏入库 / 防毒 / RBAC）
**文件**: `backend/python/smartbi/services/insights/chart_insight_service.py`（+ 端点 `api/chart_insight.py`）。
**三个独立修复**（来自深挖，~35 行，无 schema 变更，仅 Python 重启）：

1. **约束 LLM slot 词表**（prompt, ~line 661-684）：当前 prompt 只给一个例子 + 自由 `{placeholder}`，LLM 自造 `{topChannel}/{topProduct}` 等 `_compute_slot_values` 不认的名字。改为在 prompt 显式给**穷举白名单**（严格限于）：
   `{topName} {botName} {topShare} {ratio} {concLevel} {growthRate} {changeAmt} {changeDir}`，并明确"禁止使用列表外占位符"。**这是根治**（防 LLM 一开始就造错名）。
2. **填充安全网**（`_fill_slots` 后, 4 个 call site: ~line 339-341 / 419-421）：新增 `_safe_fill(tpl, slot_values)` —— 填完用 `_SLOT_RE` 扫，**若仍有 `{...}` 未填 → 返 None**（finding 为 None 则整个 InsightResult 返 None；implication/suggestion 单字段返 None）。**绝不把 `{topChannel}` 原样显给用户**。
3. **budget tracker None 守卫**（`get_insight` ~line 310 / 328）：`_budget_tracker` 在 pool 晚初始化时可能为 None → `check_budget` 调 None 抛 AttributeError → 走 null 路径（冷启动偶发 null 根因）。包 `_check_budget`：tracker 为 None 时 **fail-open**（允许 LLM 调用）。
- **阈值**：`CHART_INSIGHT_PROMOTE_THRESHOLD` env，演示租户=1（首次 LLM 即提升），prod 默认=3。
- **suggestion 门**：保留 `has_suggestion → 需 is_verified 才 auto-promote`（prod 安全，正确）。finding-only 模板可纯自动提升。
- **验收**：单测 —— 非法 slot 模板被安全网拦(返 null 不显破) / budget None 不崩(fail-open) / finding-only 在阈值=1 提升 / suggestion 模板不自动提升。

### U2 — Tier1 图表族补全（🔒 RBAC）
**文件**: `web-admin/src/views/smart-bi/components/chartInsight.ts`（+ spec.ts）。
现状只有 TREND + RANKING。补 **PROPORTION / COMPARISON / KPI**（spec v1 §2.3 列为 Phase2，现纳入地基）：

| 族 | 触发(meta) | 最小数据契约 | 骨架（全 %/倍/标签，禁绝对¥，观察动词，诚实 null） |
|---|---|---|---|
| PROPORTION | chartType PIE 或 xDim 占比语义 | ≥2 切片 | 主导占比% + 长尾（"X 占 N%，前二占 M%"） |
| COMPARISON | ≥2 可比系列 | 2 系列对齐 | 差异方向+幅度%（"A 较 B 高 N%"） |
| KPI | 单值/仪表 | actual + (target 或 上期) | 达成度%/同比%（"达成 N%，环比+M%"） |
- **建议动词白名单**：关注/排查/分析/了解；**禁** 复制/引流/加大/扩张/推广（沿用现有正则断言）。
- **PIE guard 调整**：现 `buildChartInsight` 对 chartType=PIE 直接返 null（占位 Phase2）。补 PROPORTION 后，PIE 走 PROPORTION 族（不再无条件 null）。
- **验收**：spec.ts 加三族正例 + null 契约 + 无因果词断言 + PIE→PROPORTION（不再 null）。现有 TREND/RANKING 测试不回归。

### U3 — `deriveChartMeta` 共享推断器
**文件**: `chartInsight.ts`（export 新函数）。
从图表线索 heuristic 推 `ChartMeta{xDim,yMetric,aggregation,domain}`：
- `xDim`: xField/列名含 月|日期|时间|date 或在 monthlyColumns/dateColumns → `time`；含 门店|店|store → `store`；产品|品名|物料|product → `product`；渠道|平台|channel → `channel`；类别|品类|category → `category`；否则 `other`。
- `yMetric`: yField 含 营收|收入|revenue|金额 → `revenue`；利润|毛利|margin → `margin`；成本|cost → `cost`；数量|qty|件|箱|kg → `quantity`；占比|比例|%|pct → `pct`；数|count → `count`；否则 `other`。
- `aggregation`: 默认 `sum`（SmartBI 绝大多数场景正确）。
- `domain`: 上传分析默认 `factory`（上传多为工厂，餐饮走 gold）；surface 可显式覆盖（如 RestaurantGoldGrid 传 `restaurant`）。
- **总函数、永不抛**；线索不足返 `null`（→ 上层诚实空）。
- **验收**：spec.ts 覆盖各 xDim/yMetric 分支 + 线索不足返 null。

### U4 — `useChartInsight` composable（复用编排核心 + 🔒 成本护栏）
**文件**: 新建 `web-admin/src/composables/useChartInsight.ts`（或 smart-bi 下）。
**签名（契约）**：
```ts
useChartInsight(
  source: () => { chart: ChartWithMeta } | null,   // reactive getter（图表数据+meta，或用 deriveChartMeta）
  perms: () => UserPermissions,
  opts?: { factoryId: () => string; autoTier2?: boolean }  // autoTier2 默认 true
): { insight: Ref<InsightResult|null>; loading: Ref<boolean> }
```
**职责**：
- watch source → 跑 Tier1 `buildChartInsight`（同步）。
- Tier1 null 且 autoTier2 → `loading=true` → `await fetchTier2Insight(chart, perms, factoryId)` → 应用结果。
- **stale-guard**：捕获请求 token / factoryId，await 回来若已变则丢弃。
- **冷启动限流**：模块级 Tier2 并发闸（如 max 3 in-flight + 队列）+ 去抖，防 44 面同时冷启动 LLM 爆发。Tier2 失败/null → 诚实空。
- **RBAC**：perms 透传给 Tier1 + Tier2（permission_tier）。
- **验收**：composable 单测（mock buildChartInsight/fetchTier2Insight）：Tier1 命中不调 Tier2 / Tier1 null 调 Tier2 / 限流不超并发 / stale 丢弃 / 错误诚实空。

### U5 — 上传分析 meta 修复（Option A 纯前端）
**文件**: `web-admin/src/api/smartbi/analysis.ts`（chartsLane）。
后端发的是 legacy `{chartType,data,xaxisField,yaxisField}` / chartsLane 客户端构 `{chartType,title,config,xField}` **无 meta**。改：chartsLane 组装 chart 对象时用 `deriveChartMeta(plan, monthlyColumns, dataInfo)` 挂 `meta`（3 个 call site：主路径 ~1574 / 单图 fallback ~1585 / retry ~1633）。→ SmartBIAnalysis 的 ChartGridItem mini-insight 真出洞察（机制首个端到端验证）。
- **验收**：headed 验上传一张表 → 图表下出现数据驱动洞察（之前全空）。

### U6 — 现有 6 图迁到 composable + 接回 Tier2（🔒 部署）
**文件**: `RestaurantGoldGrid.vue`（门店排行+渠道占比）+ `Dashboard.vue`（销售趋势+产品类别占比）。
把现有手搓的 trend/category/store/channel insight 计算迁到 `useChartInsight`（autoTier2=true）→ **Tier2-on-null 自动飞轮在这 6 图真跑**。渠道占比无数据仍诚实空。
- **验收**：headed 验 —— 6 图仍显（Tier1）；故意造一个 Tier1-null 图 → 见 Tier2 LLM「AI生成」→ 刷新→「数据驱动·已学习」（飞轮闭环，阈值=1 demo 租户）。

---

## 3. 错误处理 / 诚实（禁降级）
- 各层 null → 不显（U1 安全网 / Tier1 契约 / Tier2 失败）。绝不显 `{slot}` 原样、绝不假数据。
- Tier2 失败/超时/budget blocked → 诚实空（不阻塞页面）。
- 冷启动限流：超并发的 Tier2 排队，不丢（除非 stale）。

## 4. RBAC（🔒 红线，不可妥协）
- finance 性质 yMetric（revenue/margin/cost）洞察默认只比率/%；绝对 ¥ 仅 `finance:read_write`。
- Tier2 permission_tier 从 caller 权限；Python 端 factoryId 从 JWT（非请求体）；跨租户 403。
- composable 强制传 perms；Tier1 三族新生成器同样 %/倍 by construction。

## 5. 测试
- 前端 `chartInsight.spec.ts`：U2 三族正例/null 契约/无因果词；U3 deriveChartMeta 分支；U4 composable 编排（mock）。
- Python `test_chart_insight_service.py`：U1 slot 安全网/budget 守卫/promote 阈值/suggestion 门。
- headed 验：U5 上传分析一张表出洞察；U6 6 图 + 一个 Tier2 闭环。

## 6. 🚦 分发总览（并行 + scope 锁）
| 单元 | 模型 | 并行 | scope 锁 | 🔒 |
|---|---|---|---|---|
| U1 飞轮 | Sonnet in-harness | ✅ | chart_insight_service.py + api/chart_insight.py | 🔒 蒸馏/防毒/RBAC |
| U2 族补全 | Sonnet in-harness | ✅(与U1/U3) | chartInsight.ts + spec.ts | 🔒 RBAC |
| U3 deriveChartMeta | Sonnet | ✅(与U1) | chartInsight.ts(同 U2 文件→U2/U3 串行或合一) | |
| U4 composable | Sonnet | 依赖 U2+U3 | composables/useChartInsight.ts | 🔒 成本护栏 |
| U5 上传 meta | Sonnet | 依赖 U3 | analysis.ts | 禁假数据 |
| U6 迁移6图+接回Tier2 | Sonnet→Opus | 依赖 U4 | RestaurantGoldGrid.vue + Dashboard.vue | 🔒 部署 |
> ⚠️ U2 + U3 同改 chartInsight.ts → **合并为一个 subagent 或串行**（防撞同文件）。
> 隔离：各 worktree off origin/main；commit 锁 scope；prod 从 main，Opus gate 红线单元 + headed 验真（不信自报——本程序已多次被"单测过但 prod 不 fire"骗，real-path 验真是铁律）。

## 7. 残留风险（知情）
- 冷启动 LLM 成本峰值（U4 限流 + budget cap + 蒸馏衰减缓解；上线后观察）。
- deriveChartMeta heuristic 对非常规列名可能误判 xDim/yMetric → 洞察措辞略偏（诚实空兜底，不出错答）；可后续按真实数据迭代词表。
- domain 上传默认 factory（餐饮走 gold 不受影响）；若将来上传含餐饮，加后端 businessType 透传。
- Tier2 LLM 不确定性：填充安全网 + 诚实空把"破/错"降为"不显"，不会显错洞察。

## 8. 🔒 红线（Opus 终审，不可外包）
- U1 飞轮（蒸馏入库/防毒/is_verified 门/RBAC）；U2 RBAC；U4 成本护栏；U5 禁假数据；U6 prod 部署 + 飞轮闭环 headed 验。
