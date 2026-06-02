# WS3 — 餐饮深度分析 gold 化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 菜品四象限 (#1)、门店对比 (#2)、平台口碑 (#3) 三页删"选择数据源" CSV 选择器,默认全部历史走 gold;平台口碑默认展示已有 19845 条点评 + 保留上传。

**Architecture:** 三页现用共享 composable `useRestaurantAnalytics`(列上传 CSV + POST `/restaurant-analytics/{uploadId}`)→ 改成走 gold:
- 菜品四象限收入模式 → WS1 新 `/restaurant-ops/menu-quadrant`(毛利模式已有 `/restaurant-ops/gross-margin`);
- 门店对比 → WS1 新 `/restaurant-ops/store-comparison`(毛利列已有 `/restaurant-ops/store-margin`);
- 平台口碑 → 已有 `gold/review-*` 端点 (summary/trend/store-ranking/good-tags/platform)。
餐饮页用 `pythonFetch` 直连 Python gold (不走 Java)。

**Tech Stack:** Vue 3 (menu-board.vue / store-comparison.vue / platform.vue), `pythonFetch`, WS1 gold 端点。

**依赖:** WS1 (menu-quadrant / store-comparison 端点)。

**部署:** 前端 `deploy-web-admin.sh --env prod` (8086)。headed Playwright (9224, zh-CN) 验证。

---

## File Structure

| 文件 | 动作 |
|---|---|
| `web-admin/src/views/restaurant/analytics/menu-board.vue` | 四象限收入模式: 删 CSV 选择器, 调 `/restaurant-ops/menu-quadrant` (默认全部) |
| `web-admin/src/views/restaurant/analytics/store-comparison.vue` | 删 CSV 选择器, 调 `/restaurant-ops/store-comparison` (默认全部) |
| `web-admin/src/views/restaurant/analytics/platform.vue` | 默认调 `gold/review-*` 展示已有点评; 保留上传按钮为"补充更新" |
| `web-admin/src/composables/useRestaurantAnalytics.ts` | (可选) 标注 deprecated 或保留给真需上传的场景 |

---

## Task 1: 菜品四象限 → gold (#1)

**Files:** Modify `web-admin/src/views/restaurant/analytics/menu-board.vue`

现状: 毛利模式已 gold (`/restaurant-ops/gross-margin?days=365`, line 155-175);四象限(收入模式)仍 CSV (`useRestaurantAnalytics`, line 204) → 无 CSV 时"分析数据加载失败"。

- [ ] **Step 1: 写测试** — mock `pythonFetch('/api/smartbi/restaurant-ops/menu-quadrant')` 返 items → 组件渲染四象限点, 无"选择数据源" select, 无"分析数据加载失败"。
- [ ] **Step 2: 跑确认失败** → `cd web-admin && npx vitest run`(菜品四象限测试)
- [ ] **Step 3: 实现**
  - 删 menu-board.vue line 28 的 `<el-select v-model="selectedUploadId">` 数据源选择器 + `handleSelectUpload` + `useRestaurantAnalytics` 的四象限用法 (line 204)。
  - 四象限数据改: `const res = await pythonFetch('/api/smartbi/restaurant-ops/menu-quadrant?factory_id=' + factoryId)` (默认不传日期=全部历史, WS1 已支持);用 `res.data.items` (含 name/qty/revenue/quadrant) 画散点;`qtyMedian`/`revenueMedian` 画分割线。
  - 保留"按品均收入 / 按毛利率"切换:收入模式调 menu-quadrant,毛利模式继续调 gross-margin。
  - 可选日期过滤器默认"全部",可手动缩小。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(restaurant): 菜品四象限收入模式走 gold menu-quadrant, 删 CSV 选择器 (#1)`

---

## Task 2: 门店对比 → gold (#2)

**Files:** Modify `web-admin/src/views/restaurant/analytics/store-comparison.vue`

现状: 主数据 CSV (`useRestaurantAnalytics`, line 116) → "无数据";毛利列已 gold (`/restaurant-ops/store-margin`, line 125-150)。

- [ ] **Step 1: 写测试** — mock `pythonFetch('/store-comparison')` 返 stores → 渲染门店对比表 (营收/单数/客单), 无"选择数据源", 无"请选择数据源"空态。
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现**
  - 删 line 13 数据源 `<el-select>` + `handleSelectUpload` + `useRestaurantAnalytics` 主用法 (116) + line 22 的 `<el-empty description="请选择数据源">`。
  - 主数据改: `pythonFetch('/api/smartbi/restaurant-ops/store-comparison?factory_id=' + factoryId)` (默认全部);用 `res.data.stores` 渲染表 (name/revenue/orderCount/avgTicket);`weakStores` 高亮;保留已有 store-margin 毛利列 merge (line 123 storeMarginMap)。
  - 默认全部历史, 可选日期过滤。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(restaurant): 门店对比走 gold store-comparison, 删 CSV 选择器 (#2)`

---

## Task 3: 平台口碑 → 默认展示已有 gold 点评 (#3)

**Files:** Modify `web-admin/src/views/restaurant/analytics/platform.vue`

现状 (line 1-40): 纯空态 "平台口碑数据未接入" + "上传点评导出文件分析" 按钮。我们 gold 已有 19845 条点评。

- [ ] **Step 1: 写测试** — mock `gold/review-summary` + `review-good-tags` + `review-store-ranking` + `review-platform` + `review-trend` 返数据 → 页面渲染评分总览/高频好评词/门店口碑排名/平台对比/趋势, 不再显示"未接入"空态;上传按钮仍在 (标"补充更新")。
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现**
  - 删"平台口碑数据未接入"空态主导地位;改成 onMounted 并行调:
    ```ts
    const eps = ['review-summary','review-platform','review-good-tags','review-store-ranking','review-trend'];
    const data = await Promise.all(eps.map(ep => pythonFetch(`/api/smartbi/gold/${ep}?factory_id=${factoryId}`)));
    ```
    (或直接复用 WS1 `useGoldAnalytics({endpoints: eps, factoryId})`)
  - 渲染: 平均星级/服务/环境/口味分 (review-summary) + 平台对比柱 (review-platform) + 高频好评词 (review-good-tags) + 门店口碑排名 (review-store-ranking) + 评分趋势线 (review-trend)。诚实标注"来源: 大众点评/美团 导出, 共 N 条"。
  - 保留上传按钮,文案改"上传最新点评导出 (补充更新)";`goUpload` 不变。
  - 无数据 (非 qhj 餐厅) 才回退空态 + 上传引导。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(restaurant): 平台口碑默认展示已有 gold 点评 + 保留上传 (#3)`

---

## Task 4: 部署 + headed prod 验证

- [ ] **Step 1: merge main + 部署前端** `deploy-web-admin.sh --env prod` (依赖 WS1 端点已部署)
- [ ] **Step 2: headed Playwright (9224, zh-CN, headless:false) qhj_prod / 8086**:
  - `/restaurant/analytics/dishes` 菜品四象限: **无**"选择数据源", 散点出真菜品 + 四象限, **无**"分析数据加载失败" ✓
  - 菜品毛利 tab 仍正常 ✓
  - `/restaurant/analytics/stores` 门店对比: **无**"选择数据源"/"请选择数据源", 出 28 家店营收/客单对比 ✓
  - `/restaurant/analytics/platform` 平台口碑: 出评分总览 + 平台对比 + 好评词 + 门店排名 (19845 条点评), 上传按钮仍在 ✓
  - 中文无方块, fullPage 截图
- [ ] **Step 3: 截图 + verification block 入 audit doc**

---

## Self-Review
- ✅ #1 菜品四象限 → Task 1 (依赖 WS1 menu-quadrant)
- ✅ #2 门店对比 → Task 2 (依赖 WS1 store-comparison)
- ✅ #3 平台口碑 → Task 3 (复用已有 review-* 端点, 无需 WS1 新端点)
- ✅ headed 验证 → Task 4
- 注: 毛利/store-margin 端点已存在, 复用不重建
