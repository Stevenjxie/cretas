# qhj 餐饮分析侧 gold 重构 — 设计 (2026-06-02)

**Goal:** 把 qhj (RES_3101_009) 整个"数据与分析 + 餐饮运营"分析面从"手选上传 CSV + 窄默认时间"改成"自动从 gold 层聚合 + 默认全部历史 + 默认出图出洞察",并大刀合并散落页面、删冗余。

**Status:** 设计已与 Steve 评审通过 (2026-06-02)。决策: ① 默认时间=全部历史 ② 大刀合并+删冗余 ③ 平台口碑=直接展示已有 gold 点评+保留上传。

---

## 1. 背景与病根

Steve 逐页评审 qhj_prod 发现 13 个问题 (详见 `docs/audits/2026-06-02-qhj-analytics-ux-review.md`)。统一病根:**几乎每个分析页都要求用户"选择数据源(上传的 CSV)"或选窄日期范围(默认近 7/30 天→常无数据),而 qhj 全量数据(POS 全量 + 19845 条大众点评)早已在 gold 层。**

关键发现 (降低范围): **绝大多数 page-level gold 端点已存在** (`backend/python/smartbi/api/gold_reads.py`):
`finance-summary, daily-trend, top-products, channel-breakdown, discount-breakdown, order-type-mix, staff-ranking, review-* (12 个), store-review-revenue, kpi-summary, data-range`。
所以本重构**主要是前端改线(删选择器、默认全部、调已有 gold 端点) + 补少量缺失端点 + 内容修复 + IA 合并**,不是从零建 gold。

`Dashboard.vue` 已**部分** gold 化 (底部门店排行/渠道、kpi gold 卡、`data-range` 端点、`dateRange` 带 shortcuts),但顶部仍残留"选择数据源"选择器 + 上传依赖 KPI 卡。

---

## 2. 统一架构

```
旧: 页面 → 选上传 CSV → Python 分析该文件 → 窄日期默认 → 常无数据 / 慢 / 要手动
新: 页面 → useGoldAnalytics(scope, range=ALL) → 调 gold 端点(全量聚合,默认全部历史) → 缓存秒回 → 默认出图+洞察
```

### 2.1 前端共享层 `useGoldAnalytics` (新)
- 位置: `web-admin/src/composables/useGoldAnalytics.ts`
- 职责: 统一封装 gold 端点调用 + 默认时间 + loading/error + 缓存。
- 接口:
  ```ts
  useGoldAnalytics(opts: {
    endpoints: string[]          // e.g. ['finance-summary','daily-trend']
    range?: [string,string]|null // 默认 null = 全部历史
    autoLoad?: boolean           // 默认 true (打开即加载)
  }): { data, loading, error, reload, range }
  ```
- 默认 `range=null` → 后端解释为全部历史 (见 2.3)。
- **彻底删除各页的"选择数据源(upload)"选择器**;时间范围保留为**可选过滤**(默认全部,顶部一个 date-range,带"全部/近一年/近一季"shortcut,默认选"全部")。

### 2.2 后端 gold 端点
- 复用已有的 23 个端点 (上面列表)。
- **缺失需补建** (WS1):
  - `dish-quadrant` (菜品四象限 BCG: 销量×毛利率分象限) — 复用 `agg_product` + 配方毛利 (若无配方,按均收入版退化,前端已有"按品均收入/按毛利率"切换)。
  - `multi-store-comparison` (门店对比: 多门店营收/客单/渠道并排) — 复用 `finance-summary` 多店展开 + `store-revenue-rank`。
  - `trend-bundle` (趋势分析: 时间趋势/营收对比/周末平日/月度异常 一次返回) — 复用 `daily-trend` + `weekday_weekend` + 异常检测。
  - (财务/销售/KPI tab 复用 finance-summary/top-products/kpi-summary,无需新端点)
- 所有端点: 按 `factory_id` 租户隔离 (gold RLS),`X-User-Role` 经 `GoldFinanceClient` 转发做营收脱敏 (price-view 角色看全额,否则剥零),`start/end` 可空 (空=全部历史)。

### 2.3 默认全部历史
- gold 查询 `start/end` 为空时聚合全部历史 (现有 `_validate_range` 改成允许 null → 不加日期过滤)。
- 前端默认不传 range → 全部。Steve 决策: 默认全部历史。

### 2.4 缓存 (#8 几分钟加载不出)
- gold 端点结果按 `(factory_id, endpoint, range, role)` 缓存 (复用现有物化/narrative 缓存层模式;新增 `gold_read_cache` L1 进程内 + 可选 L2 表)。
- 解决 AI 问答/数据分析页 (#8) 慢 + 图表没聚合。

---

## 3. 目标 IA (大刀合并)

| 现状 (散) | 重构后 | 处置 |
|---|---|---|
| 经营驾驶舱 | **经营驾驶舱** | 留 + 全 gold 化 (去选择器, KPI/洞察/门店/渠道/评价全自动) |
| 财务分析 + 财务 PBI 看板 + 销售分析 + 趋势分析 + KPI 看板 + 指标中心 | **经营分析** (新, tab: 财务/销售/趋势/KPI·指标) | 6 页合 1, 全 gold + 默认全部 |
| 菜品四象限/毛利 · 门店对比 · 平台口碑 (餐饮运营→深度分析) | 同名保留 | 全 gold 化, 删选择器 |
| AI 问答 | **AI 问答** | 留, gold 化 (无文件选, 缓存) |
| 收入管理报表 | **收入管理报表** | 留, 改入口/默认表头 (#13) |
| Excel 上传 / 数据完整度 / ETL 状态 / 查询模板管理 / 上传状态 | **数据管理** (子组) | 留 (数据录入/运维侧, 上传合理) |
| AI 分析报告 · What-If 模拟 · 餐饮 V2 Dashboard · Gold 预览 | — | **删/下线** (用处不明/被取代) |
| 异常预警 | 折进 经营分析 (tab) 或 驾驶舱告警 | 合并 |
| 知识库反馈 / AI 追问日志 / 行为校准监控 | 移到 系统管理 (非用户分析面) | 移出 |

> 删除采用"路由保留 + 侧边栏隐藏 + 旧路径 redirect 到替代页"(保书签不破),不物理删组件 (降风险, 可回滚)。

---

## 4. 工作流分解 (5 个 WS, 每个独立可交付)

### WS1 — gold 聚合地基 (基础, 先做)
- `useGoldAnalytics` composable。
- 补建缺失端点: `dish-quadrant`, `multi-store-comparison`, `trend-bundle`。
- gold 端点 `start/end` 允许 null = 全部历史。
- `gold_read_cache` 缓存层。
- **验收**: composable 单测; 新端点 prod 真库返真数据 (qhj); 默认全部历史口径正确。

### WS2 — 经营驾驶舱 全 gold + 内容修复
- 删顶部"选择数据源"选择器 + 上传依赖 KPI 卡 → 全部走 gold (默认全部历史)。
- #5 AI 洞察括号标记 (`[净/实收][按金额][毛/应收]`) 清理 (insight prompt + 后处理 strip)。
- #6 快捷问答 chip 点击卡住/不跳转 (前端 handler bug)。
- #7 餐饮营收分析 (门店排行+渠道) 加配套洞察。
- **验收**: qhj 打开驾驶舱无任何"选数据源", KPI/洞察/门店/渠道/评价全自动出; chip 秒跳; 洞察无括号标记; headed 截图。

### WS3 — 餐饮深度分析 gold 化
- #1 菜品四象限/毛利: 调 `dish-quadrant` (默认全部), 删选择器, 修加载失败。
- #2 门店对比: 调 `multi-store-comparison`, 删选择器/"无数据"。
- #3 平台口碑: 默认展示 gold 已有点评 (review-summary/trend/store-ranking/good-tags), 诚实标注来源; 保留"上传点评导出"作为补充更新。
- **验收**: 三页 qhj 打开即出真数据无选择器; 平台口碑显示 19845 条点评聚合; headed 截图。

### WS4 — 经营分析模块合并 (最大, 最后)
- 新建 `经营分析` 页 (tab: 财务/销售/趋势/KPI·指标), 6 页内容并入, 全 gold + 默认全部。
- #8 AI 问答/数据分析: gold 化 + 缓存 + 同比/因果默认生成 (不另点)。
- #9/#12 删 AI 分析报告 / What-If / 餐饮 V2 / Gold 预览 (侧栏隐藏+redirect); 趋势并入经营分析。
- #10/#11 财务/销售默认全部历史 (修"无数据")。
- 侧边栏 menuConfig + 路由 redirect 调整 (保书签)。
- **验收**: 侧栏只剩精简项; 6 合 1 模块各 tab gold 出数; 旧路径 redirect; headed 截图。

### WS5 — 收入管理报表入口/默认表头 (#13)
- 默认表头报表 (参考 `收入管理报表.xlsx`) 的生成+下载从"生成报表"深处提到页面显著位置 (一键生成默认表头报表)。
- **验收**: qhj 一键拿到默认表头收入管理报表。

---

## 5. 时序

```
WS1 (地基) ──┬─→ WS2 (驾驶舱)
             ├─→ WS3 (餐饮深度)      ← WS2/3/5 可并行
             ├─→ WS5 (收入报表)
             └─→ WS4 (经营分析合并, 最大, 依赖 WS1 + 复用 WS2/3 组件)
```

每个 WS 一个实施计划 (writing-plans) + subagent 并行实施 + 两段式 review + headed E2E + prod 验证。

---

## 6. 关键技术约束 (必须遵守)

- **部署**: Java 改动从 main 部署 (蓝绿); Python `deploy-smartbi-python`; web-admin `deploy-web-admin --env prod` (8086 prod, 8097 test)。worktree off origin/main, 完成 merge main 再部署 prod。
- **营收脱敏**: gold 端点经 `GoldFinanceClient` 转发 `X-User-Role`; 非 price-view 角色营收剥零 (沿用现有)。
- **业态门控**: 餐饮意图/页 `business_type=RESTAURANT`; FACTORY 租户 `hideForFactoryTypes` 隐藏餐饮分析页。
- **gold 新表/列**: 若需新增, 迁移必带 `GRANT INSERT/UPDATE + sequence` 给 smartbi_user (否则静默写失败), 经 `apply-smartbi-migrations.sh`。
- **验证**: 一律 prod 真库 + headed Playwright (中文字体, `headless:false`, 9222/9223/9224 多 chat) 实测, 不信日志/单测。
- **诚实降级**: 无数据明确提示 + next-action, 禁假数据 (沿用防呆规范)。

---

## 7. 范围外 / 风险

- **范围外**: 工厂业态 (FACTORY) 的分析页保持现状 (本次只动餐饮 qhj 关注的面); 数据录入侧 (Excel 上传/ETL) 保留上传 (合理)。
- **风险**: ① 删页/合并改 IA 可能漏 redirect → 旧书签 404 (用函数式 redirect 保); ② gold 默认全部历史在超大数据量下可能慢 → 缓存 + 必要时分页/限时; ③ 菜品四象限毛利需配方数据, qhj 可能无 → 退化"按品均收入"版 (前端已有切换); ④ 并发 session 部署冲突 → 严守 main-only deploy。
- **已完成 (前置)**: 门店营收排行 AI 问答路由 (PR #426, 已 merge main)。
