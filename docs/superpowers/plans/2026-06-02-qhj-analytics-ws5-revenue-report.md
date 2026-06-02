# WS5 — 收入管理报表 一键默认表头 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 收入管理报表页加一个显著的"一键生成默认表头报表 (全部历史/全部门店/全部餐段)"入口,不用先选日期/门店/餐段 (#13)。默认表头已固化在后端 `qhj_revenue_v1.py` 渲染器, 本 WS 只加便捷入口 + 默认参数。

**Architecture:** `RevenueReport.vue` 现有"预览数据"/"下载 Excel"需先填日期范围 (`date_from`/`date_to` 必填)。加一个顶部醒目的"一键生成默认报表"按钮: 自动用全部历史 (从 `getGoldDataRange` 取 minDate/maxDate) + 全部门店 (空数组) + 全部餐段 (空数组) → 直接调已有 `/revenue-report/generate` 下载。后端 `GenerateRequest` 已支持空门店/空餐段=全部, 仅 date_from/date_to 必填 → 用全部历史区间填充。

**Tech Stack:** Vue 3 (RevenueReport.vue), 已有 `revenue-report.ts` API (prepare/generateAndDownload), 已有后端端点 (无需改后端)。

**依赖:** WS1 `getGoldDataRange` (已存在, 复用)。无需新后端。

**部署:** 前端 `deploy-web-admin.sh --env prod` (8086)。

---

## File Structure

| 文件 | 动作 |
|---|---|
| `web-admin/src/views/smart-bi/RevenueReport.vue` | 加"一键生成默认报表"按钮 + 默认参数逻辑 |
| `web-admin/src/api/smartbi/revenue-report.ts` | (复用现有 generateAndDownload, 无需改) |

---

## Task 1: 一键生成默认表头报表按钮 (#13)

**Files:** Modify `web-admin/src/views/smart-bi/RevenueReport.vue`

- [ ] **Step 1: 写测试**
```ts
// RevenueReport.oneclick.spec.ts
// mock getGoldDataRange → {minDate:'2025-01-01', maxDate:'2026-04-30'}
// mock generateAndDownload
// 点"一键生成默认报表"按钮 → generateAndDownload 被调用, 参数:
//   date_from='2025-01-01', date_to='2026-04-30', store_names=[], meal_periods=[]
// 即: 不需要用户先填任何东西
```
- [ ] **Step 2: 跑确认失败** → `cd web-admin && npx vitest run src/views/smart-bi/__tests__/RevenueReport.oneclick.spec.ts`
- [ ] **Step 3: 实现**
  - 在"生成"区顶部 (或页面 header) 加醒目按钮:
    ```vue
    <el-button type="primary" size="large" :loading="oneClickLoading" @click="handleOneClickDefault">
      一键生成默认收入管理报表 (全部历史)
    </el-button>
    <div class="hint">默认表头 · 全部门店 · 全部餐段 · 全部历史区间, 无需选择</div>
    ```
  - handler:
    ```ts
    async function handleOneClickDefault() {
      oneClickLoading.value = true;
      try {
        const dr = await getGoldDataRange(factoryId.value);  // 全部历史
        const params = {
          dateFrom: dr.minDate, dateTo: dr.maxDate,
          storeNames: [], mealPeriods: [],   // 空=全部
        };
        const result = await generateAndDownload(params);  // 复用现有, 触发 xlsx 下载
        // 触发浏览器下载 blob (复用现有 handleDownload 的 blob→download 逻辑)
      } catch (e) { /* 诚实错误提示, 不静默 */ }
      finally { oneClickLoading.value = false; }
    }
    ```
  - 复用现有 `handleDownload` 里的 blob→`<a download>` 逻辑 (抽成 `triggerDownload(blob, filename)` 共享)。
  - 保留原有"预览数据"/"下载 Excel"(高级: 自定义日期/门店/餐段) 在下方。
- [ ] **Step 4: 跑确认通过**
- [ ] **Step 5: Commit** `feat(revenue-report): 一键生成默认表头报表 (全部历史, 无需选择 #13)`

---

## Task 2: 部署 + prod 验证

- [ ] **Step 1: merge main + 部署前端** `deploy-web-admin.sh --env prod`
- [ ] **Step 2: headed Playwright (zh-CN, headless:false) qhj_prod / 8086 `/smart-bi/revenue-report`**:
  - 页面顶部有醒目"一键生成默认收入管理报表"按钮 ✓
  - 点击 → 不需填任何东西 → 浏览器下载 `收入管理报表_*.xlsx` ✓
  - 打开 xlsx 确认默认表头 (微软雅黑/浅蓝表头/汇总+堂食+外卖 同比环比块) 正确 ✓
  - 原高级生成 (自定义日期/门店) 仍可用 ✓
- [ ] **Step 3: 截图 + verification block 入 audit doc**

---

## Self-Review
- ✅ #13 一键默认表头 → Task 1
- 注: 后端无需改 (GenerateRequest 已支持空门店/餐段=全部, 默认表头已固化 qhj_revenue_v1.py);仅前端加便捷入口
- 注: 该页本就在侧栏 (非埋藏);用户真实痛点是"要先选才能生成", 本 WS 用一键默认解决
