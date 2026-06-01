# Web-Admin「数据与分析」侧边栏 / IA 重设计

**日期**: 2026-06-01
**状态**: DESIGN (v2, 已纳入对抗性审计 19-agent 裁决 wf_9793b8a5) — 待 Steve 审批后转 writing-plans
**触发**: Steve 截图「智能分析」组 (12+ 项 4 子组) + "我感觉不知这些啊" — 分析入口太多、老板分不清点哪个。
**范围**: 合并顶级两个报表/分析组 —「经营报表」(`/analytics`) +「智能分析」(`/smart-bi`) → 单一「数据与分析」组并重分层去重。**不含** 餐饮运营组 (那是另一份 doc `2026-06-01-restaurant-web-admin-ia-redesign-design.md`, 焦点不同)。

> **范围更正记录**: 本 doc 的前身误把范围当成「餐饮运营」组。Steve 截图澄清真实痛点是「智能分析」组 (`/smart-bi`)。本 doc 聚焦于此 + 与其重叠的「经营报表」组。

---

## 1. 问题 (Why) — 实证

侧边栏当前有**两个**顶级报表/分析组, 加起来 23 项, 老板分不清:

| 组 | 路由 | 项数 | 主后端 | 定义位置 |
|---|---|---|---|---|
| 经营报表 | `/analytics` | 8 | **主 Java** (`/{factoryId}/reports/*`, `/{factoryId}/ai/*`) — 部分页混合 Python | `AppSidebar.vue:270-284` |
| 智能分析 | `/smart-bi` | 15 | **主 Python** (`/api/smartbi/*`) — 含 production-analytics ×2 (path 前缀 `/production-analytics` 但物理是本组 children) | `AppSidebar.vue:320-346` |

代码注释自己都承认混淆 (`AppSidebar.vue:270`: "数据分析→经营报表 与 智能分析 消歧")。

> **后端边界纠正 (审计 M1)**: 不是整齐的 "经营报表=Java / 智能分析=Python" 二分。实证: (a) Java 真实路径前缀是 `/{factoryId}/reports/*` 与 `/{factoryId}/ai/*` (`analytics/index.vue:127-130`), **不存在** `/api/mobile/analytics/*` 字面路径; (b) analytics 组**已是 Java+Python 混合** — `analytics/index.vue:49` / `kpi/index.vue:40` / `AlertDashboard.vue:38` 都实调 `pythonFetch('/api/smartbi/restaurant-ops/summary')`; 反向 `smart-bi/SalesAnalysis.vue:10` 也 import Java `get`。**菜单合并是纯 UI/路由分组, 不触碰任何 API** — 各页继续调它既有的后端 (部分页本就混合)。

### 病症 (审计实证, file:line)

| # | 病症 | 证据 |
|---|---|---|
| **A 双报表组** | 「经营报表」(看固定报表) 与「智能分析」(AI 探索) 概念重叠, 用户不知去哪看数 | `:270` + `:320` |
| **B 4 个分析入口** | 经营驾驶舱/财务PBI看板/智能数据分析/AI问答 都像"看经营", 老板分不清 (Steve 原话痛点) | `:323-326` |
| **C 概览三件套重叠** | 经营驾驶舱(Gold) / 分析概览(`/analytics/overview` Java) / KPI看板(`/analytics/kpi` Java) 都是 KPI 概览看板 | `:323` + `:273` + `:276` |
| **D 财务看板分散 4 处** | 财务PBI看板 / 财务数据分析 (都在 smart-bi) + 财务报表 / 财务概览 (在 /finance 组) | `:324,:329` + `:224-225` |
| **E AI 探索可合并** | 智能数据分析 (`/analysis` 传Excel) 与 AI问答 (`/query` 问数) 都是对话式探索 | `:325-326` |
| **F「数据完整度」命名冲突** | `/smart-bi/data-completeness` (通用SmartBI) 与 `/restaurant/data-completeness` (餐饮Gold) 不同组件同名 | `:336` + `:314` |
| **G「行为校准」两入口** | `/smart-bi/calibration` (监控, platform_admin) 与 `/calibration/list` (管理, 系统组) | `:339` + `:262` |
| **H「质量管理」子组名误导** | smart-bi 里的"质量管理"子组实为 AI 运维 (知识库反馈/AI追问/校准), 与顶级「质量管理」(`/quality` 质检) 撞名 | `:337` |
| **I 经营报表无业态门控** | `/analytics/*` 整组无 hideForFactoryTypes → **餐饮租户看到 7 个制造导向分析页** (趋势/进销存/车间报表/KPI) = 信息过载 | `:271-284` (无门控) |

**目标**: 单一「数据与分析」组, 分层清晰 (看概览 / AI探索 / 专题报表 / 数据管理 / AI运维), 去重, 加业态门控让餐饮/制造各看各的。老板一眼知道"看经营 → 点驾驶舱"。

---

## 2. 设计决策 (已与 Steve 确认)

| # | 决策 | 理由 |
|---|---|---|
| D-1 | **「经营报表」+「智能分析」合并为单一「数据与分析」组** | 用户面合并 (一个菜单组)。**注意: 不是后端合并** — Java analytics 与 Python smartbi 后端不动, 仅菜单聚合 + 去重。 |
| D-2 | **经营驾驶舱为唯一主入口 (置顶)** | Gold 驱动业态自适应总览 (营收/订单/财务 KPI 一页全)。老板默认看板。`/smart-bi/dashboard` 已存在且业态自适应 (实证)。 |
| D-3 | **智能数据分析 + AI问答 合并为「AI 探索」(双tab)** | 都是对话式 AI 入口 (传Excel分析 / 问数据)。 |
| D-4 | **财务看板收敛** | smart-bi 内"财务PBI看板"+"财务数据分析" 合为「财务看板」(归专题报表)。`/finance` 组的财务报表/概览**不动** (那是制造业财务 CRUD, 不同后端)。 |
| D-5 | **加业态门控 (M4 修正后)** | 只对**明确制造专属**项加 `hideForFactoryTypes:['RESTAURANT']` — 进销存总览 / 车间实时生产报表 / 生产分析 / 人效 (后三者已有)。**趋势分析不加** (双业态自适应页, 加门控会移除餐饮看 POS 营收趋势的唯一入口)。餐饮专属 (收入管理报表) 已有 `['FACTORY']`。KPI 看板门控待拍板 (§3.1)。 |
| D-6 | **概览去重 (M6 保守口径)** | 「分析概览」(`/analytics/overview`) 与经营驾驶舱重叠, 但**数据源不同无法真合并、不 redirect**。先保留移到"专题报表"子组, 不与驾驶舱争主入口; **P5 凭埋点决定真删**。(早期草稿的"redirect 到 dashboard"已撤回, 三处口径统一为保守保留。) |
| D-7 | **「数据完整度」消歧 + 「行为校准」收敛** | smart-bi 版数据完整度归"数据管理"; 餐饮版改名"餐饮数据完整度"(在餐饮组)。行为校准: 监控版 (`/smart-bi/calibration`) 归"AI运维", 系统组的管理版不动。 |

---

## 3. 新 IA (目标「数据与分析」组)

```
数据与分析  (/analytics, 合并原 经营报表 + 智能分析; module: 'analytics')
│
├─ ★ 经营驾驶舱          /smart-bi/dashboard          [Gold]  主入口, 置顶 (D-2)
│
├─ AI 探索  (groupLabel)
│  ├─ AI 问答 / 数据分析  /smart-bi/analysis (双tab并 query)  [Python LLM] (D-3)
│  └─ AI 分析报告         /analytics/ai-reports          [Java LLM]
│
├─ 专题报表  (groupLabel)
│  ├─ 财务看板            /smart-bi/financial-dashboard  [Python] (并 finance, D-4)
│  ├─ 销售分析            /smart-bi/sales                [Python]
│  ├─ 收入管理报表        /smart-bi/revenue-report       [Python] (仅餐饮, 已 ['FACTORY'])
│  ├─ 趋势分析            /analytics/trends              [Java+Gold] (双业态自适应, 不门控 — 见 M4)
│  ├─ KPI 看板            /analytics/kpi                 [Java+Python] (不门控 — 餐饮有数据, 见 §3.1)
│  ├─ 异常预警            /analytics/alert-dashboard     [Python]
│  ├─ 进销存总览          /analytics/supply-chain        [Java] (制造门控 D-5)
│  ├─ 车间实时生产报表    /analytics/production-report   [Java] (制造门控, 已有 ['RESTAURANT'])  ← M3 补
│  ├─ 指标中心            /indicator-center              [Java]
│  ├─ 生产数据分析        /production-analytics/production [制造门控, 已有 ['RESTAURANT']]
│  └─ 人效分析            /production-analytics/efficiency [制造门控, 已有 ['RESTAURANT']]
│
├─ 数据管理  (groupLabel)
│  ├─ Excel 上传          /smart-bi/upload
│  ├─ 查询模板            /smart-bi/query-templates
│  └─ 数据完整度          /smart-bi/data-completeness    (通用, D-7)
│
└─ AI 运维  (groupLabel, admin/platform)
   ├─ 知识库反馈          /smart-bi/food-kb-feedback
   ├─ AI 追问日志         /smart-bi/fallback-log
   └─ 行为校准监控        /smart-bi/calibration          (platform_admin, D-7)
```

**未入菜单但路由可达的 4 个 child (M9)**: `/smart-bi/upload-status` / `/smart-bi/whatif` / `/smart-bi/restaurant-v2` / `/smart-bi/gold-preview` 在 `smartbi.ts` 注册但不在 sidebar。新 IA **保留其路由、不入菜单** (开发/预览用途), 实现时勿误删路由。

合并去重处理:
- **去重 1 (保守, M6 统一口径)**: 「分析概览」(`/analytics/overview` = `analytics/index.vue`) 与经营驾驶舱重叠最高, **但数据源不同** (overview 调 Java reports + Python restaurant-ops; dashboard 走 Gold), **不真合并、不 redirect**。先保留, 移到"专题报表"子组下不与驾驶舱争主入口; **P5 凭埋点决定真删**。(审计修正: overview 无跨模块快捷入口, 只有 3 张指向自身子页的卡, 删它损失小但仍保守保留。)
- **合并 1 (M2 依赖 P3)**: AI问答 (`/smart-bi/query`) 并入「AI 问答/数据分析」双tab — **必须与 P3 组件改造同 PR**, 否则 `?tab=query` 是死参数 (见 §4.2/§4.3)。
- **合并 2 (M2 依赖 P4)**: 财务数据分析 (`/smart-bi/finance`) 并入财务看板 — **必须与 P4 同 PR**。
- **门控 (M4 修正)**: 只对**明确制造专属**项加 `['RESTAURANT']` — 进销存总览 / 车间报表 / 生产分析 / 人效 (后三者已有)。**趋势分析不加门控** (它是双业态自适应页, 加了会移除餐饮看 POS 营收趋势的唯一入口)。

**净效果**: 餐饮租户从"两组可见项"→ 单组约 10 项 (制造项门控隐藏 + 合并); 制造租户从"两组 23 项"→ 单组约 14 项。**老板默认落在经营驾驶舱**。

### 3.1 KPI 看板门控 — 已定 (Steve 2026-06-01): 不门控

§1 病症把 KPI 看板列为"制造导向过载项", 但 D-5 又留它不门控 — 内部矛盾。实证 `kpi/index.vue` 同时调 Java `/{factoryId}/reports/kpi` + Python `restaurant-ops/summary` (双业态都有数据)。

**裁定: (a) 不门控** — KPI 看板对餐饮有真数据 (走 restaurant-ops/summary), 餐饮租户保留可见。它与经营驾驶舱并非重复 (驾驶舱=Gold 总览, KPI看板=指标明细)。§1 病症 C 中"KPI 列为过载项"的表述撤回。

---

## 4. 关键实现点

### 4.1 侧边栏定义 (`AppSidebar.vue`)
- 删除独立的 `/analytics` 顶级组 (`:270-284`), 其 children 迁入 `/smart-bi` 组并加 groupLabel。
- `/smart-bi` 组改 title「智能分析」→「数据与分析」, path 保持 `/smart-bi` 或改 `/analytics` (二选一, 见 §6 风险)。
- 5 个 groupLabel 子组 (经营驾驶舱独立顶项 + AI探索/专题报表/数据管理/AI运维)。
- 制造导向项加 `hideForFactoryTypes:['RESTAURANT']`。

### 4.2 路由 redirect (`router/index.ts`; M2 — 按依赖分阶段)

**P2 阶段 (纯 path redirect, 无 `?tab=`, 可独立先上)**:
- `/analytics` → `/smart-bi/dashboard` (旧经营报表顶级入口落到驾驶舱)。注意: `/analytics` 当前默认 redirect 到 `/analytics/overview` (`index.ts:1244`), 改这里要**精确锚定顶级 `/analytics` 块 (index.ts:1242)**, 显式避开 `/restaurant/analytics` (`index.ts:1414` RestaurantAnalyticsOverview, 完全不同) — M-MINOR。
- `/analytics/overview` **不 redirect** (D-6 保守保留, 见 §3 去重1)。

**P3/P4 阶段 (带 `?tab=` 的 redirect 必须与组件改造同 PR, 否则死参数 — M2)**:
- `/smart-bi/query` → `/smart-bi/analysis?tab=query` — **依赖 P3** (SmartBIAnalysis.vue 加 query tab + 读 route.query.tab)。
- `/smart-bi/finance` → `/smart-bi/financial-dashboard?tab=analysis` — **依赖 P4** (FinancialDashboardPBI.vue 加 analysis tab)。
- ⚠️ 当前这两个目标组件**不消费 `?tab=`**: SmartBIAnalysis.vue 的 `activeTab` 是 Excel **sheet 索引** (无 useRoute, 且 'query' 这个 tab 值不存在, 命名空间会与 sheet-tab 撞); FinancialDashboardPBI.vue 的 `activeTab=ref('budget_achievement')` 是图表类型键 (无 route.query, 'analysis' 值不存在)。实现时两组件都需新增 `useRoute() + watch(route.query.tab)` + 新增 'query'/'analysis' 业务 tab 取值, 且业务-tab 与现有 sheet/chart-tab 命名空间隔离。AIQuery.vue:9/56/532 已有 `useRoute()+route.query.q` 可作 pattern。

- 其余 `/analytics/*` 路由 path **不动** (仅菜单分组变), 降低风险 + 不破书签。

### 4.3 AI 探索双tab (`SmartBIAnalysis.vue` 主 + 整合 `AIQuery.vue`, D-3 — P3)
- 一页双 tab: "传 Excel 分析" (analysis, 上传式) / "问数据" (query, NL→结构化查询)。
- 审计实证: 两者交互模式**互补**而非重复 — analysis = Excel 上传分析 (`@/api/smartbi/analysis`), query = 自然语言查询 (`@/api/smartbi/aiQuery` → SmartQueryResponse columns/rows)。合并双tab合理。
- 共享 Python `/api/smartbi/*` 后端 + 免费链。
- 新增业务 tab 取值 `analysis`/`query`, `?tab=` query 驱动 (与 sheet-tab 命名空间隔离)。

### 4.4 财务看板合并 (`FinancialDashboardPBI.vue` 主 + 整合 `FinanceAnalysis.vue`, D-4 — P4)
- 财务PBI看板 (三表) 为主, 财务数据分析 (预定义) 作为其 tab/section。
- 新增 `analysis` tab 取值 (现 activeTab 取值域是 budget_achievement 等图表键, 需扩)。
- 注意: 与 `/finance` 组的财务报表/概览 (Java, 制造财务 CRUD) **不混** — 那是录入/凭证, 这是分析看板。

---

## 5. 数据/后端边界 (重要 — 合并不动后端)

```
经营报表项 ──► 主 Java /{factoryId}/reports/* + /{factoryId}/ai/*
              (部分页 overview/kpi/alert 同时调 Python /api/smartbi/restaurant-ops/*)
智能分析项 ──► 主 Python /api/smartbi/* (Gold / Excel 上传 / LLM)
              (部分页如 SalesAnalysis 也 import Java get)
```
合并仅是**菜单聚合**。各页继续调用它**既有的后端 (部分页本就 Java+Python 混合)**, 菜单合并**不改任何 API**。不做后端统一 (独立大项目, YAGNI)。用户看到一个"数据与分析"组, 点不同项命中各自既有后端 — 对用户透明。

> M1 纠正: 早期草稿写的二分 "经营报表=Java `/api/mobile/analytics/*` / 智能分析=Python" 不准确 — 该字面 Java 路径不存在 (真实是 `/reports/*`+`/ai/*`), 且 analytics 组多页已混合调用。结论 (合并不动后端) 不变, 只是边界描述纠正。

---

## 6. 风险 & 取舍

| 风险 | 缓解 |
|---|---|
| 合并组 path 用 `/smart-bi` 还是 `/analytics`? | 建议保 `/smart-bi` (项多在此), `/analytics/*` 子路由不变只迁菜单分组 → 改动最小。组 title 改「数据与分析」即可, path 是内部标识。 |
| **改组 title 的隐藏依赖 (M5)** | title 字面对**权限安全** (canSeeMenuItem 不读 title)。但 (a) 标题串出现在已发布操作手册 `F006_OPERATIONS_GUIDE.html:966`「菜单: 智能分析 + 经营报表」; (b) `AppHeader.vue:16-20` 面包屑读 **route meta.title**, 而 `/analytics` 的 meta.title 已是「数据分析」(`index.ts:1245`) 与 sidebar 组 title「经营报表」**本就分歧**; (c) ~13 个 tests/*.spec.ts/*.html 含这两个字面串。**实施前必须 grep 全仓 `'经营报表' '智能分析' name:'Analytics'`, 评估同步 meta.title + 手册。**「无隐藏依赖」是**有界**声明 (限权限层)。 |
| D-6「分析概览」 | 不 redirect (数据源不同), 保守保留移子组, P5 凭埋点再定。审计修正: overview 无跨模块快捷入口 (只 3 张指向自身子页的卡), 删损失小但仍保守。 |
| 业态门控加错 (M4) | **趋势分析不门控** (双业态自适应); 只对明确制造专属项加 `['RESTAURANT']`。门控回归用 Playwright 双业态 (RESTAURANT+FACTORY) 验证。 |
| `?tab=` redirect 死参数 (M2) | 带 `?tab=` 的两条 redirect 不可在 P2 独立上, 必须与 P3/P4 组件改造同 PR (目标组件当前不读 route.query)。 |
| 财务看板合并牵动 3-4 个组件 | 分阶段: 先菜单合并 (P1), 财务/AI探索组件合并 (P3/P4) 独立 PR。 |

---

## 7. 实施阶段 (A 分阶段)

| 阶段 | 内容 | 前置依赖 | 风险 | 独立交付 |
|---|---|---|---|---|
| **P1 菜单合并+门控** | AppSidebar 合并两组为「数据与分析」5子组 + 制造专属项门控 (含车间报表, 不含趋势) + 经营驾驶舱置顶 + 组 title 同步 (grep 手册/meta.title M5) | 无 | 低 (纯菜单结构) | ✓ 立即减乱 (最大收益) |
| **P2 纯 path redirect** | `/analytics`→dashboard (精确锚定 index.ts:1242 避开 restaurant/analytics)。**不含 `?tab=`** | P1 | 低 | ✓ |
| **P3 AI 探索合并** | SmartBIAnalysis 加 query tab + 读 route.query.tab + 整合 AIQuery; **同 PR 上 `/smart-bi/query` redirect** | P1 | 中 | ✓ |
| **P4 财务看板合并** | FinancialDashboardPBI 加 analysis tab + 整合 FinanceAnalysis; **同 PR 上 `/smart-bi/finance` redirect** | P1 | 中 | ✓ |
| **P5 去重决策** | 凭埋点决定「分析概览」/「KPI看板」真删或留 | 埋点数据 | 低 | ✓ |

P1 先上 = 最快解决"不知这些啊"。**M2 关键**: `?tab=` redirect 不在 P2, 跟 P3/P4 同 PR。

---

## 8. 非目标 (YAGNI)

- **不做后端统一** (Java analytics 与 Python smartbi 各保留)。
- 不动 `/finance` 组 (制造财务 CRUD)。
- 不动餐饮运营组 (另一份 doc)。
- 不动系统管理组的行为校准管理版。
- P5 不在数据支撑前盲删页面。

---

## 9. 验收标准

- [ ] 侧边栏只剩**一个**「数据与分析」组 (经营报表组消失, 项迁入)。
- [ ] 经营驾驶舱置顶为主入口。
- [ ] 5 子组 (经营驾驶舱 / AI探索 / 专题报表 / 数据管理 / AI运维) 清晰。
- [ ] 餐饮租户看不到**制造专属**页 (进销存/车间实时生产报表/生产分析/人效); **趋势分析对餐饮仍可见** (双业态自适应); 制造租户看不到餐饮专属 (收入管理报表)。
- [ ] **车间实时生产报表** (`/analytics/production-report`) 迁入"专题报表"子组且保留 `['RESTAURANT']` 门控 (M3 — 不掉项)。
- [ ] AI问答 / 财务数据分析合并项的 `?tab=` redirect **与 P3/P4 组件改造同 PR 上线** (不在 P2), 落点 tab 真生效 (书签不破, M2)。
- [ ] 「数据完整度」「行为校准」命名/位置消歧。
- [ ] 后端均无改动 (合并是纯菜单); 各页继续调既有后端 (部分页 Java+Python 混合)。
- [ ] 改组 title 已 grep 全仓同步 (`F006_OPERATIONS_GUIDE.html` 手册 + `/analytics` route meta.title, M5)。
- [ ] Playwright headed 双业态 (RESTAURANT + FACTORY) 截图验证门控不回归。

---

## 10. 关联

- 现状 audit: 本 doc §1 (2026-06-01 Explore 实证, file:line)
- 侧边栏定义: `web-admin/src/components/layout/AppSidebar.vue:270-346`
- 餐饮运营组重整 (姊妹 doc): `2026-06-01-restaurant-web-admin-ia-redesign-design.md`
- 防呆设计 (空状态 next-action): `.claude/rules/fool-proof-design.md`
- Playwright headed 双业态验证: `.claude/rules/playwright-headed-mode.md`
- 业态门控先例: revenue-report (`['FACTORY']`) / production-analytics (`['RESTAURANT']`)

---

## 11. 对抗性审计记录 (wf_9793b8a5, 19-agent, 2026-06-01)

总裁决: **方向正确, 无 CRITICAL; 5 个 MAJOR (文档/排序级) 已在本 v2 全部纳入。** 33 findings → 12 flagged → 11 confirmed。

| 编号 | 问题 | 本 doc 修订 |
|---|---|---|
| M1 | 后端二分 (Java/Python) 描述失真; `/api/mobile/analytics/*` 字面不存在; analytics 组已混合 | §1 表 + §5 改实际前缀 + 标混合 |
| M2 | `?tab=` redirect 目标组件不读 route.query, 不能在 P2 独立上 | §4.2 拆纯path(P2)/带tab(P3·P4 同PR) + §7 加前置依赖列 |
| M3 | 掉了制造项「车间实时生产报表」(`/analytics/production-report`) | §3 专题报表子组补回 + §9 验收 |
| M4 | 趋势分析门控加错方向 (它是双业态自适应, 加 `['RESTAURANT']` 会移除餐饮 POS 营收趋势唯一入口) | D-5 + §3 + §9 改"趋势不门控" |
| M5 | "无隐藏依赖"过宽 (手册/route meta.title/13 测试含字面串) | §6 改有界声明 + 实施前 grep |
| (minor) | 计数 13→15 / 21→23; 组件名 SmartAnalysis→SmartBIAnalysis 等; 4 个未入菜单 child 归属; D-6 三处口径矛盾; KPI 门控待拍板 | §1/§3/§3.1/§4.3/§4.4 全部修正 |

经核查**成立**的核心断言 (给信心): 合并不动后端 ✓ / 驾驶舱置顶可做 ✓ / 改 title 权限安全 ✓ / redirect 机制成熟 ✓ / 命名冲突判定准确 (D-7) ✓ / 门控先例真实 ✓。

**全部待定点已拍** (Steve 2026-06-01): §3.1 KPI 看板**不门控** (餐饮有数据)。设计定稿, 转 writing-plans。
