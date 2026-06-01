# 餐饮 Web-Admin 侧边栏 / IA 重设计

**日期**: 2026-06-01
**状态**: DESIGN — 待 Steve 审批后转 writing-plans
**触发**: Steve "我们去整理一下餐饮的 web 端吧，我看目前有很多内容，而且很乱"
**范围**: 餐饮 (RESTAURANT 业态) web-admin 侧边栏 + 信息架构 (IA) 重整。**不含** 制造业 (FACTORY) 侧边栏，不含后端 API 改动 (除非分层暴露出 API gap)。

---

## 1. 问题 (Why)

当前餐饮侧边栏 11 项、2 组，"很乱"。具体病症 (audit 实证，见 §6 现状):

| 病症 | 证据 |
|---|---|
| **菜品维度重复** | "菜品四象限" (`menu-board.vue`) 与 "菜品毛利分析" (`gross-margin.vue`) 都是菜品级、读同一 agg 表，分两项 |
| **总览页是上传浏览器不是驾驶舱** | "运营总览" (`overview.vue`) 实为 Excel upload 选择器 + 通用分析，不是 Gold 驱动的经营概览 |
| **分析与录入混在一起无层次** | "运营分析" 5 项 + "日常管理" 6 项平铺，分析 (看数) 和录入 (录数) 没有清晰分层 |
| **外部数据页与 Gold 页混放** | "经营与平台分析" (点评) 需外部平台评分 (现为 mockup 无真实数据)，跟读 Gold 的分析页放一起，用户分不清哪些有真数据 |
| **admin 页混在业务项里** | "ETL 状态" / "数据完整度" 是运维/审计页，混在日常管理组 |

**目标**: 分层清晰的 IA — 用户一眼分清「看经营 (驾驶舱)」「钻分析 (深度)」「录数据 (日常)」「管系统 (admin)」，并去掉重复项。

---

## 2. 设计决策 (已与 Steve 确认)

| # | 决策 | 理由 |
|---|---|---|
| D-1 | **Gold 为唯一读层 — 但只约束「分析层」** | 分析页 (驾驶舱/菜品/门店) 必须读 Gold (`fact_pos_item` / `agg_*`)，禁止读裸 Excel upload。录入 CRUD 与 admin 页豁免 (它们是写侧/运维)。 |
| D-2 | **菜品四象限 + 菜品毛利 合并为「菜品分析」(双 tab)** | 两页都是菜品级、读同一 `agg_restaurant_product_cost`，重叠。合一页双 tab (四象限 / 毛利) 减乱。 |
| D-3 | **点评页保留 + 明标「需接入平台数据」** | 唯一真正需要外部 (大众点评/美团) 数据的页，当前 mockup。保留于深度分析层，空状态明确提示需接平台 API + 可手动上传截图分析。**禁假数据** (不装真实平台评分)。 |
| D-4 | **新增「经营驾驶舱」取代「运营总览」** | Gold 驱动的单一经营概览入口 (营收/订单/门店/翻台 KPI + 趋势)，替换上传浏览器式的旧总览。 |
| D-5 | **分 4 层 IA** | 驾驶舱 / 深度分析 / 日常录入 / 数据与系统 — 见 §3。 |

---

## 3. 新 IA (目标侧边栏结构)

```
餐饮运营  (hideForFactoryTypes: ['FACTORY'] — 仅 RESTAURANT 业态可见)
│
├─ 经营驾驶舱            /restaurant/dashboard         [Gold]  ← 单一经营概览入口 (D-4)
│
├─ 深度分析  (groupLabel, Gold 读层 D-1)
│  ├─ 菜品分析           /restaurant/analytics/dishes  [Gold]  ← 合并四象限+毛利, 双 tab (D-2)
│  ├─ 门店对比           /restaurant/analytics/stores  [Gold]
│  └─ 平台口碑 ⚠️        /restaurant/analytics/platform [外部]  ← 点评, 明标需接平台 (D-3)
│
├─ 日常录入  (groupLabel, 写侧豁免)
│  ├─ 配方管理           /restaurant/recipes
│  ├─ 领料管理           /restaurant/requisitions
│  ├─ 损耗管理           /restaurant/wastage
│  └─ 盘点管理           /restaurant/stocktaking
│
└─ 数据与系统  (groupLabel, admin — roles 门控)
   ├─ 数据完整度         /restaurant/data-completeness
   └─ ETL 状态           /restaurant/admin/etl-status
```

**从 11 项 → 10 项** (合并菜品 -1)，但**层次从 2 组扁平 → 4 个语义清晰的层**。

### 3.1 各项映射 (旧 → 新)

| 旧项 | 旧路由 | 新项 | 新路由 | 处理 |
|---|---|---|---|---|
| 运营总览 | `/restaurant/analytics` | 经营驾驶舱 | `/restaurant/dashboard` | **重做** (D-4): Gold KPI 驾驶舱取代上传浏览器 |
| 菜品四象限 | `/restaurant/analytics/menu` | 菜品分析·四象限 tab | `/restaurant/analytics/dishes` | **合并** (D-2) |
| 菜品毛利分析 | `/restaurant/analytics/gross-margin` | 菜品分析·毛利 tab | `/restaurant/analytics/dishes` | **合并** (D-2) |
| 门店对比 | `/restaurant/analytics/stores` | 门店对比 | `/restaurant/analytics/stores` | 不变 |
| 经营与平台分析 | `/restaurant/analytics/dianping` | 平台口碑 | `/restaurant/analytics/platform` | 改名 + 明标 (D-3) |
| 领料管理 | `/restaurant/requisitions` | 领料管理 | `/restaurant/requisitions` | 移到「日常录入」 |
| 损耗管理 | `/restaurant/wastage` | 损耗管理 | `/restaurant/wastage` | 移到「日常录入」 |
| 配方管理 | `/restaurant/recipes` | 配方管理 | `/restaurant/recipes` | 移到「日常录入」(置顶, 它喂养分析层成本) |
| 盘点管理 | `/restaurant/stocktaking` | 盘点管理 | `/restaurant/stocktaking` | 移到「日常录入」 |
| 数据完整度 | `/restaurant/data-completeness` | 数据完整度 | `/restaurant/data-completeness` | 移到「数据与系统」 |
| ETL 状态 | `/restaurant/admin/etl-status` | ETL 状态 | `/restaurant/admin/etl-status` | 移到「数据与系统」 |

**路由变更最小化**: 仅 2 处真实变化 — (a) 新增 `/restaurant/dashboard`; (b) `gross-margin` 内容并入 `dishes`。其余仅侧边栏**分组/排序/命名**变化，路由 path 不动 (降低实现风险 + 不破坏书签)。

### 3.2 旧路由兼容 (防破坏书签/外链)

- `/restaurant/analytics` (旧总览) → redirect `/restaurant/dashboard`
- `/restaurant/analytics/menu` (旧四象限) → redirect `/restaurant/analytics/dishes?tab=quadrant`
- `/restaurant/analytics/gross-margin` (旧毛利) → redirect `/restaurant/analytics/dishes?tab=margin`
- `/restaurant/analytics/dianping` (旧点评) → redirect `/restaurant/analytics/platform`

---

## 4. 关键组件设计

### 4.1 菜品分析页 (合并, D-2)

`views/restaurant/analytics/dishes.vue` (新建，整合两旧页):

```
菜品分析  (订单号/门店/日期范围筛选条 — 共享)
├─ Tab 1: 四象限 (BCG)      ← 复用 menu-board.vue 的 BCG 散点 (销量 × 毛利率)
└─ Tab 2: 毛利明细          ← 复用 gross-margin.vue 的菜品级毛利表 (营收 − 食材成本)
```

- 两 tab 共享筛选条 (日期范围 / 门店 / 天数) — 切 tab 不重置筛选。
- 数据源 (Gold, D-1): `GET /api/smartbi/restaurant-ops/gross-margin?days=N` (两 tab 同一 API，前端按 tab 渲染不同视图)。
- `tab` 由 query param 驱动 (`?tab=quadrant|margin`)，支持旧路由 redirect 落点 (§3.2)。
- 无配方成本时: 四象限退化为"仅销量" + 提示"配置配方后显示毛利率"，毛利 tab 显示空状态引导去「配方管理」(防呆 Rule 5 — next action)。

### 4.2 经营驾驶舱 (D-4)

`views/restaurant/dashboard.vue` (新建):

- Gold 驱动 KPI 卡 (营收 / 订单数 / 门店数 / 客单价 / 翻台率) + 趋势图 + 门店营收排行。
- 数据源: 复用现有 `gold.ts` helper (`getFinanceSummary` / `getChannelBreakdown` / `getDataRange`) — 与已上线的 RN/制造业驾驶舱默认全量出图逻辑一致 (见 memory: 餐饮驾驶舱默认全量出图)。
- **MVP 取舍**: 若工期紧，dashboard 可先复用现有 `Dashboard.vue` 餐厅分支 (它已做 Gold 探测 + 默认时间区间)，仅在侧边栏新增入口指向它；后续再做独立 `restaurant/dashboard.vue`。**这是 §7 阶段划分的决策点。**

### 4.3 平台口碑页 (D-3)

`views/restaurant/analytics/platform.vue` (由 `dianping-gap.vue` 改名/改造):

- 顶部明确 banner: "本页需接入大众点评/美团平台数据。当前未接入平台 API。"
- 提供两条可用路径 (防呆 Rule 5 — 不留死胡同):
  1. 手动上传点评截图/导出 → LLM 分析 (走免费链)。
  2. (未来) 接入平台 API 后自动同步。
- **禁假数据**: 不显示任何 hard-coded 平台评分/benchmark；无数据时显示空状态 + 上述引导。

### 4.4 侧边栏定义改动

`web-admin/src/components/layout/AppSidebar.vue` (现 lines 296-315 餐饮组):

- 把单一「餐饮运营」组的 children 重组为 4 个 `groupLabel` 段 (深度分析 / 日常录入 / 数据与系统 + 驾驶舱独立顶项)。
- `hideForFactoryTypes: ['FACTORY']` 不变。
- admin 段 (数据与系统) 保留 `roles: ['factory_super_admin','platform_admin','permission_admin']` 门控。
- 「平台口碑」项加 ⚠️ 角标/tooltip 提示需接平台数据。

---

## 5. 数据流 & 一致性

```
POS 上传 ──ETL──► Gold (fact_pos_item, agg_daily, agg_restaurant_product_cost)
                    │
   配方录入 ────────┤ (日常录入·配方 → 喂养 agg_restaurant_product_cost 成本)
                    │
                    ├─► 经营驾驶舱  (getFinanceSummary / getChannelBreakdown)
                    ├─► 菜品分析    (restaurant-ops/gross-margin)
                    └─► 门店对比    (restaurant-ops/store-margin)

外部平台 (点评/美团) ──[未接入]──► 平台口碑 (空状态 + 手动上传, D-3)

录入 CRUD (领料/损耗/盘点) ──► 各自业务表 (写侧, 不受 Gold 读层约束)

Gold 状态 ──► 数据完整度 / ETL 状态 (admin 审计)
```

**D-1 验证标准**: 三个分析页 (驾驶舱/菜品/门店) 的网络请求只命中 `/gold/*` 或 `/restaurant-ops/*` (读 Gold agg)，不得出现 `/smart-bi/upload` 裸 Excel 读取。

---

## 6. 现状 (审计实证, 2026-06-01)

侧边栏定义: `web-admin/src/components/layout/AppSidebar.vue:296-315`
路由定义: `web-admin/src/router/index.ts:1382-1465`

| 页面 | 组件 | 数据源 | 读层归类 |
|---|---|---|---|
| 运营总览 | `analytics/overview.vue` | `/smart-bi/upload` 列表 (Excel 浏览器) | 混合 (待改驾驶舱) |
| 菜品四象限 | `analytics/menu-board.vue` | `/restaurant-ops/gross-margin` | **Gold** |
| 门店对比 | `analytics/store-comparison.vue` | `/restaurant-ops/store-margin` | **Gold** |
| 经营与平台分析 | `analytics/dianping-gap.vue` | Excel + hard-coded benchmark | **外部 (mockup)** |
| 菜品毛利分析 | `analytics/gross-margin.vue` | `/restaurant-ops/gross-margin` | **Gold** |
| 领料管理 | `requisitions/list.vue` | 业务 CRUD | 写侧 |
| 损耗管理 | `wastage/list.vue` | 业务 CRUD | 写侧 |
| 配方管理 | `recipes/list.vue` | `/restaurant-ops/recipes/*` | 写侧 (喂养成本) |
| 盘点管理 | `stocktaking/list.vue` | 业务 CRUD | 写侧 |
| 数据完整度 | `data-completeness.vue` | `/restaurant-ops/completeness-check` | Gold 审计 |
| ETL 状态 | `admin/etl-status.vue` | `/restaurant-ops/etl-status` | Gold 审计 |

**关键发现 — D3 比预想轻**: 菜品/门店/毛利 三页读的 `agg_restaurant_product_cost` **本就是 Gold agg 表** (由配方录入物化)，是 Gold 读层合规；"非 Gold" 的只是配方**录入** (写侧)。唯一真外部数据例外是点评页 (D-3)。

---

## 7. 实施阶段 (A 分阶段, 待 plan 细化)

| 阶段 | 内容 | 风险 | 可独立交付 |
|---|---|---|---|
| **P1 侧边栏重组** | AppSidebar.vue 4 层分组 + 命名 + 旧路由 redirect (§3.2) | 低 (纯 UI 结构) | ✓ 立即减乱 |
| **P2 菜品分析合并** | 新建 `dishes.vue` 双 tab, 整合 menu-board + gross-margin, 旧路由 redirect | 中 (整合两组件) | ✓ |
| **P3 经营驾驶舱** | 新 `dashboard.vue` (或先复用 Dashboard.vue 餐厅分支, §4.2 取舍) | 中 | ✓ |
| **P4 平台口碑改造** | dianping-gap → platform.vue, 空状态 + 明标 + 手动上传 | 低 | ✓ |

每阶段独立 PR + 独立验证 (Playwright headed per `.claude/rules/playwright-headed-mode.md`)。P1 先上 = 最快见效。

---

## 8. 非目标 (YAGNI)

- 不动制造业 (FACTORY) 侧边栏。
- 不接入大众点评/美团平台 API (P4 只做空状态 + 手动上传; 真接入是独立后续项目)。
- 不重写录入 CRUD 页 (领料/损耗/配方/盘点) 的业务逻辑，仅移动其侧边栏分组。
- 不改后端 Gold API (除非 P2/P3 暴露 gap，届时单列)。

---

## 9. 验收标准

- [ ] 侧边栏呈现 4 层 (驾驶舱 / 深度分析 / 日常录入 / 数据与系统)，10 项，无重复菜品项。
- [ ] 菜品分析页双 tab (四象限/毛利) 共享筛选，读 Gold。
- [ ] 经营驾驶舱 Gold 驱动，默认全量出图 (非空)。
- [ ] 平台口碑页无假数据，空状态明标需接平台 + 手动上传可用。
- [ ] 旧 4 个路由 redirect 到新位置 (书签不破)。
- [ ] 三分析页网络请求只读 Gold (`/gold/*` 或 `/restaurant-ops/*`)，无裸 Excel 读。
- [ ] RESTAURANT 业态可见，FACTORY 业态不可见 (业态门控不回归)。
- [ ] Playwright headed 截图验证 (中文字体真显示)。

---

## 10. 关联

- 现状 audit: 本 doc §6 (2026-06-01 Explore 实证)
- 防呆设计: `.claude/rules/fool-proof-design.md` (Rule 5 空状态 next-action 用于点评页/无配方态)
- Playwright headed: `.claude/rules/playwright-headed-mode.md` (验证)
- Gold helper: `web-admin/src/api/gold.ts` (驾驶舱复用)
- 餐饮驾驶舱默认全量出图 (已上线逻辑, 驾驶舱阶段复用): memory 2026-05-29 条
- 业态门控 (RESTAURANT vs FACTORY): memory Sprint 13 #305
