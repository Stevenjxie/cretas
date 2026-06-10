# Chart-Insight 全局化 — Phase A 地基 设计 (v2，4-critic 对抗审计后定稿)

**日期**: 2026-06-10
**状态**: v2 — 已过 4-critic superpowers 对抗审计，BLOCKER/MAJOR 修复已折入，待 Steve spec review → writing-plans
**触发**: Steve 要"全局所有图表分析都是 图表+洞察，且都跑自蒸馏飞轮"。Organizer 接管 real-path 求证 → 拆多阶段程序，本 spec 是 **Phase A 地基**。

---

## 0. 背景 / 现状全景（两份只读分析 + 4-critic 审计综合）

- **真正有 chart-insight Tier1 的只有 6 个图**（驾驶舱 销售趋势+产品类别占比 / 餐饮营收 门店排行+渠道占比）。
- **~44 个图表面零洞察**，渲染机制 5-6 种（chartInsight Tier1 / 执行摘要 LLM panel / **DynamicChartRenderer** / 直接 **echarts.init** 裸初始化 / **TemplateCard**(自有 backend insights) / 后端洞察数组）。
- **上传分析（SmartBIAnalysis.vue）mini-insight 接了但 `chart.meta=null` → 全空**（死接线，wiring critic F1 实证）。
- **Tier2 自蒸馏飞轮**：表+service+端点建好已部署，但**有 ~8 个真 bug**（见 U1），且**当前零调用方**（`RestaurantGoldGrid.vue:139` 注释实证因 slot-fill bug 主动禁用 Tier2）。

### Steve 拍板（含审计后）
1. **范围 = 字面全部 ~44 面** + 给 Tier1 补全图表族（异形图 Gantt/Sankey/热力新族 = Phase D）。
2. **Tier2 触发 = Tier1 主 + Tier2-on-null 自动**（每图先 Tier1 0-token；null 才自动 Tier2 → 蒸馏入库 → 下次同签名 0 token）。
3. **签名 = 跨租户模板**（去掉 factoryId；安全靠 required_permission + 服务端推 permission_tier；threshold 保持 3 即可收敛）。
4. **Phase A = 原富范围**（U1-U6 全做，含补族 + composable），但所有审计 BLOCKER/MAJOR 折入。

### 程序路线图（每阶段自己 spec→plan→分发）
- **Phase A（本 spec）**：完整硬化飞轮 + 可复用机制(composable+直调) + Tier1 族补全 + meta 推断器 + 上传分析 meta + 现有 6 图接回 Tier2。
- **Phase B**：铺 Trivial + 高价值 Moderate（趋势/平台口碑/门店对比/目标拆解/销售排行/财务PBI图族）。**前置**：独立 chart-insight 预算子桶 + 服务端 Tier2 节流（防 44 面冷启动爆发）。
- **Phase C**：剩余 Moderate（DynamicChartRenderer 系 / FinanceAnalysis / 餐饮运营 ~10 图 / 生产·人效）。
- **Phase D**：异形图新族（Gantt/Sankey/热力/WhatIf）。

> ⚠️ **rollout 工量诚实化（审计 F4/F6 纠正）**：**没有万能机制**。"~3 行/面" **仅适用上传分析 + 单图响应式 gold 面**。其余：DynamicChartRenderer 面需加 `meta` prop + insight slot ≈15 行/面；`echarts.init` 裸初始化面需手写 meta ≈10-20 行/图；TemplateCard 是另一套（backend insights）需设计决策。B/C/D 远大于 "~3 行×44"。

---

## 1. 审计裁决（4-critic 收敛 + Opus 交叉裁定）

| ID | 审计发现 | 裁定 → 折入 |
|---|---|---|
| 🔴 B1 | **permission_tier 客户端可控**：端点收 `body.permission_tier` 未从 JWT role 推 → 非财务用户可自抬到 finance_visible 桶 | U1-④ 服务端按 caller_role 推导，忽略/移除 body 字段（RBAC 红线） |
| 🔴 B2 | 防毒+绝对¥ 校验只查 `finding_tpl`，漏 `implication_tpl`/`suggestion_tpl` → ¥ 泄露+处方词逃逸 | U1-⑤ 全字段查 `_contains_poison` + `_ABSOLUTE_AMOUNT_RE` |
| 🔴 B3 | slot 白名单未约束 + `_safe_fill` 不存在 → 原样 `{topChannel}` 给用户 | U1-①② prompt 白名单 + 填充安全网（剩 `{...}`→null），4 字段全 |
| 🔴 B4 | `budget_tracker=None` → AttributeError 500 | U1-③ eager init 根治 + 兜底 fail-closed+日志（**裁定：非 fail-open**） |
| 🟠 M1 | 飞轮不收敛：签名含 factoryId+permissionTier+细桶, threshold=3 永不凑 | U1-⑧ **去 factoryId 跨租户模板**（Steve 决策）；threshold 保持 3 |
| 🟠 M2 | threshold=1 进程全局 → demo 设置波及 prod 租户 | 跨租户模板后无需全局=1；如需 per-factory 阈值用 factory-config（Phase B+，A 不需要） |
| 🟠 M3 | token 少算 7x（consume 100 vs ~700） | U1-⑥ 真实计量(~800) |
| 🟠 M4 | `ON CONFLICT` 每次覆盖 insight_template → 漂移不收敛 | U1-⑦ 首次捕获后不覆盖内容，仅 bump proposal_count/updated_at |
| 🟠 M5 | "~3 行/面"假 + composable 非万能（数组面用不了）+ deriveChartMeta 漏 40-60% 列名 | §0 rollout 诚实化 + U3 扩词表 + U4 澄清适用边界 |
| 🟡 m | temperature 0.3 不确定 / is_verified lookup 不 gate / 内部调用 permission_tier / TS 缺 price_hidden / 测试 fixture `cat-count`≠`n4-8` / showAbsolute 死代码 | U1/U2 各折入（见下） |

> **确认非 spec 漏判**：B2/B3/B4 的"代码缺失"正是 U1 要建的；F1 deriveChartMeta 未写正是 U5。审计确认这些 fix 必需且方向对。

---

## 2. 架构 — 复用机制（澄清边界）

```
单图响应式面(gold/驾驶舱/Phase B 单图)
  → useChartInsight(source, perms, {factoryId, autoTier2})   ← 单图 composable
数组面(SmartBIAnalysis N 图循环)
  → 直调 deriveChartMeta + buildChartInsight + fetchTier2     ← 不能用 composable(Vue watch 须 top-level)
两者共用:
  ├ Tier1 buildChartInsight(chart, perms)  ── 0 token 即时
  └ null → Tier2 fetchTier2Insight(...)    ── LLM→蒸馏入库, 跨租户模板
  → <ChartInsight :insight :loading depth>
```
**不变量**：Tier1 永远先跑；Tier2 仅 null 时自动；诚实空（任一层 null → 不显，禁假数据/禁显 `{slot}`）；RBAC by construction（只 %/倍/标签；permission_tier 服务端推；跨租户模板零租户数据）。

---

## 3. 单元（U1–U6）

### U1 — 完整飞轮硬化（🔒 RBAC/蒸馏/防毒，Python，Opus 终审）
**文件**: `chart_insight_service.py` + `api/chart_insight.py`。**8 项**：
1. **prompt 锁 slot 白名单**（~line 661-684）：穷举 `{topName}{botName}{topShare}{ratio}{concLevel}{growthRate}{changeAmt}{changeDir}`，明令禁列表外。
2. **填充安全网** `_safe_fill`：`_fill_slots` 后 `_SLOT_RE` 扫，剩 `{...}`→该字段 None（finding None→整个 InsightResult None）。**两个逻辑 call site**（2b ~339-341 / 2a ~419-421，各 3 字段）。
3. **budget None 根治**：`_get_service` eager init（pool 就绪才建 service，否则不缓存 `_service`）；`get_insight` 仍加 `if _budget_tracker is None` 兜底 → **fail-closed**（返 None + WARN，不 LLM）。
4. **permission_tier 服务端推**（🔴）：端点按 `caller_role ∈ FINANCE_ROLES ? 'finance_visible' : 'finance_hidden'`（内部调用同理从 X-User-Role 推），**忽略 body.permission_tier**（或移除该字段）。
5. **防毒+绝对¥ 全字段**：`_contains_poison` 与 `_ABSOLUTE_AMOUNT_RE` 在 capture+promote 都查 finding+implication+suggestion 三字段，任一含 → 拒捕获/不 promote。
6. **token 真实计量**：`consume` 用真实 input+output 估算(~800)，非 flat 100。
7. **模板不覆盖**：`ON CONFLICT DO UPDATE` 只更新 proposal_count/updated_at，**不覆盖 insight_template**（首次捕获后固定，防漂移）。
8. **签名去 factoryId**（Steve 决策）：`compute_signature` = `chartType|xDim|yMetric|aggregation|domain|dataPattern|permission_tier`（**无 factoryId**）→ 跨租户模板共享，收敛快。`ai_insight_templates` 仍按 factory 存行但 lookup 不再 factory-scope（或去 factory 列；迁移决定见 plan）。**安全论证**：参数化模板只含 %/倍/slot 名零租户数据；required_permission(按 y_metric) + permission_tier(服务端推) 双门控；跨租户复用不泄露。
- 杂项：temperature → 0.1（降不确定）；lookup 加 `AND (suggestion_tpl IS NULL OR is_verified)` gate；阈值 env 保持（threshold=3，跨租户够收敛；演示可 1）；测试 fixture data_pattern 改 `n4-8` 对齐前端。
- **验收**：单测 — 非法 slot→安全网拦 / budget None→fail-closed 不崩 / poison 在 implication 被拒 / ¥ 在 suggestion 被拒 / permission_tier 从 role 推(body 被忽略) / 跨租户同签名命中同模板 / 模板不被二次覆盖 / promote 阈值。

### U2 — Tier1 族补全（🔒 RBAC，chartInsight.ts）
补 **PROPORTION / COMPARISON / KPI**（现 TREND/RANKING）。全 %/倍/标签，禁绝对¥+因果词，诚实 null+最小契约（PROPORTION ≥2 切片 / COMPARISON 2 系列 / KPI actual+target或上期）。
- **KPI 族强制 ¥ 守卫**（🔴 RBAC-M4）：`isFinanceMetric && !canViewFinance` → 只出 %（达成度/同比），绝不绝对 ¥。**顺手修 RANKING 的 showAbsolute 死代码**（现两分支同串=空操作）。
- slot 值约定：`{topShare}` 等已含 `%`，模板勿再加（避免 `65%%`）。PIE 不再无条件 null → 走 PROPORTION。
- **验收**：spec.ts 三族正例/null 契约/无因果词/KPI 非财务无¥；TREND/RANKING 不回归。

### U3 — `deriveChartMeta` 共享推断器（chartInsight.ts）
从 xField/yFields/chartType/列名 heuristic 推 ChartMeta。**词表扩充**（审计 F5：现漏 40-60%）：xDim 加 供应商|客户|批次|部门|工序|原材料|科目|渠道|平台 等；yMetric 加 损耗|领料|采购|费用|应收|应付 等。总函数永不抛；线索不足返 null。
- **Phase A 消费者 = U5 only**（6 图已手填 explicit meta，不经此函数 —— 勿"顺手"改它们的 meta）。
- domain：上传默认 factory；surface 可显式覆盖。
- **B/C 适配现实**（F6）：本函数签名只配 ChartPlanItem(有 xField/yFields)。DashboardChartConfig 面需另写 `deriveMetaFromDashboardConfig`(读 series[0].name/xAxis)；echarts.init 裸面无字段名 → 手写 meta。这些是 B/C 的事，Phase A 不做。
- **验收**：spec.ts 覆盖扩充词表分支 + 线索不足 null。

### U4 — `useChartInsight` composable（单图，🔒 成本护栏）
新建 `composables/useChartInsight.ts`。**仅单图响应式面用**（数组面直调，见 §2）。职责：watch source→Tier1→null 调 Tier2(autoTier2 默认 true)→`{insight,loading}`；stale-guard；**Phase A 限流=简单 2-slot 信号量+去抖**（Phase A 实际 Tier2 仅 0-2 次；44 面爆发的服务端节流+预算子桶 = Phase B 前置，不在 A）；RBAC perms 透传，**perms 缺省 fail-safe = finance_hidden**（不泄露）。
- **验收**：composable 单测(mock)：Tier1 命中不调 Tier2 / null 调 Tier2 / 2-slot 不超并发 / stale 丢弃 / 错误诚实空 / 缺 perms→finance_hidden。

### U5 — 上传分析 meta 修复（analysis.ts，禁假数据）
chartsLane 用 `deriveChartMeta(plan, monthlyColumns, dataInfo)` 挂 meta。**审计 F1/MINOR-3 纠正**：(a) 扩 `flowResult.charts` TS 类型加 `meta?: ChartMeta|null`（SmartBIAnalysis.vue:687 + analysis.ts:1568）；(b) **以单遍后处理**在 chartsLane 末尾(~1717)对所有 charts 挂 meta，**不在 3 个 build site 内联**（避开 onProgress 偏序 → 部分回调拿不到 meta）。
- **验收**：headed 验上传一张表 → 图表下出现数据驱动洞察（之前全空）。

### U6 — 现有 6 图迁 composable + 接回 Tier2（🔒 部署）
6 图改用 `useChartInsight`(autoTier2=true) → 飞轮在已有面真跑（demo 现场闭环）。**风险已由飞轮硬化(U1)消除**。**审计 U6-LOW 纠正**：迁移时**保留 `Dashboard.vue:538-604` categoryInsight 的 PIE→BAR 归一化**（喂归一后数据给 composable，否则 PIE→null）。
- **验收**：headed — 6 图仍显(Tier1)；造一个 Tier1-null 图 → Tier2「AI生成」→刷新→「数据驱动·已学习」(跨租户模板,threshold 收敛后)。

---

## 4. 错误处理/诚实（禁降级）
各层 null→不显；U1 安全网防 `{slot}`；Tier2 失败/budget→诚实空；限流排队不丢(除 stale)。

## 5. RBAC（🔒 红线）
- finance yMetric 默认只 %/倍；绝对¥ 仅 finance:read_write（含 U2 新 KPI 族强制守卫）。
- permission_tier **服务端按 JWT role 推**（B1 修）；factoryId 从 JWT；跨租户 403（body 不可信）。
- 防毒+¥ 校验**全字段**（B2 修）。跨租户模板安全靠参数化(零租户数据)+required_permission+permission_tier。
- composable perms 缺省 = finance_hidden（fail-safe）。

## 6. 测试
- 前端 spec.ts：U2 三族(含 KPI 非财务无¥)/U3 扩词表/U4 composable 编排+缺 perms fail-safe。
- Python：U1 全部 8 项（slot 安全网/budget fail-closed/poison-全字段/¥-全字段/permission_tier-推/跨租户命中/不覆盖/promote）。
- headed：U5 上传出洞察；U6 6 图+一个 Tier2 跨租户闭环。

## 7. 🚦 分发总览（并行 + scope 锁）
| 单元 | 模型 | 并行 | scope 锁 | 🔒 |
|---|---|---|---|---|
| U1 飞轮硬化 | Sonnet in-harness | ✅(与U2合并组前) | chart_insight_service.py + api/chart_insight.py (+ 可能迁移去 factoryId) | 🔒 RBAC/蒸馏/防毒/迁移 |
| **U2+U3** | Sonnet in-harness | **❌ 串行/合并(同 chartInsight.ts)** | chartInsight.ts + spec.ts | 🔒 RBAC |
| U4 composable | Sonnet | 依赖 U2+U3 | composables/useChartInsight.ts | 🔒 成本护栏 |
| U5 上传 meta | Sonnet | 依赖 U3 | analysis.ts + SmartBIAnalysis.vue(类型) | 禁假数据 |
| U6 迁6图+接Tier2 | Sonnet→Opus | 依赖 U4 | RestaurantGoldGrid.vue + Dashboard.vue | 🔒 部署 |
> **U2+U3 同改 chartInsight.ts → 合并为一个 subagent（审计 BLOCKER-1 修，不并行）。** 各 worktree off origin/main；commit 锁 scope；prod 从 main，红线单元 Opus gate + **headed real-path 验真**（本程序已多次被"单测过但 prod 不 fire"骗，real-path 是铁律）。

## 8. 残留风险（知情）
- deriveChartMeta heuristic 即便扩词表仍可能误判 → 诚实空兜底(不出错答)；按真实数据迭代。
- 跨租户模板：理论上 A 学的模板服务 B —— 参数化+零租户数据+双门控保证安全；若将来模板含敏感措辞，poison/¥ 全字段校验 + is_verified 门兜底。
- 冷启动成本：Phase A 仅 0-2 次 Tier2（6 图大多 Tier1 命中）；**44 面爆发是 Phase B**，前置服务端节流+独立预算子桶（已列 §0）。
- Tier2 LLM 不确定性：安全网把"破/错"降为"不显"。

## 9. 🔒 红线（Opus 终审，不可外包）
U1（permission_tier/poison-¥-全字段/budget/签名迁移/蒸馏入库）、U2 RBAC（KPI ¥ 守卫）、U4 成本护栏、U5 禁假数据、U6 prod 部署 + 飞轮闭环 headed 验真。
