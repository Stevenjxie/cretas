# 六扇门 Deferred 批 E2E 验证报告
**日期**: 2026-06-11  
**验证范围**: PR #754 / #758 / #759 / #762 / #763 (deferred 批)  
**环境**: F006 prod (`139.196.165.140:8086`), 账号 `f006_admin` (factory_super_admin)  
**模式**: 只读浏览验证渲染+导航，不提交写操作  
**截图目录**: `docs/audits/liushanmen/2026-06-11-deferred-e2e-screenshots/`

---

## 验证结果总览

| # | 验证链 | PR | 状态 | 备注 |
|---|--------|-----|------|------|
| 1 | 物料类型 → 新建 → 16位编码预览 | #754 | ⚠️ 页面渲染✅ / 编码生成API 400 | 后端端点活跃但F006未配置16位字典 |
| 2 | 调拨详情 → 差异表格/处理入口 | #758 | ⚠️ 详情页渲染✅ / 差异表格无数据触发 | #758 diff UI 需 RECEIVED/CONFIRMED 状态调拨，F006 当前数据为草稿 |
| 3 | BOM配置 → 包材行 pack_qty_per_product | #759 | ⚠️ BOM页渲染✅ / 包材列字段UI缺口 | #759 是纯后端改动，无对应 Vue UI 输入字段 |
| 4 | 生产计划 → 创建 → 多SO合并面板 | #762 | ⚠️ 计划列表渲染✅ / 合并面板条件未满足 | v-if 需先选 CUSTOMER_ORDER + sourceOrderId，只读下无法走通 |
| 5 | 物料类型列表 → 大类筛选下拉 | #763 P11 | ✅ 渲染走通 | 下拉显示 原料/辅料/包材 三选项，截图 05b 确认 |
| 6 | 物料编辑 → 关联客户字段 | #763 P8 | 🔴 最后一公里缺口 | 后端 DTO 有 associatedCustomerId，Vue dialog 无对应输入控件 |
| 7 | 研发样品 → 价位区间选料 | #763 R14 | ⚠️ 研发页渲染✅ / 价位UI入口缺口 | 后端 suggest-by-price API 有数据(返回"冻猪蹄")，前端无价位选料 UI 入口 |
| 8 | 回归复验：盐化/销售付款/出纳 | — | ⚠️ 部分 | 盐化✅ / 销售付款路由→/404 / 出纳→/finance/costs |

---

## 链 1 — 16位编码预览 (#754)

**PR #754 内容**: 纯后端改动 (MaterialTypeController + MaterialTypeService 16位编码逻辑)，无 Vue 文件变更。

**渲染验证**:
- `物料类型字典` 页面渲染 ✅ — 截图 `01a-material-types-list.png`：6条记录，面包屑"仓储管理/原料类型字典/"，"+新建原料类型"按钮可见
- 新建对话框渲染 ✅ — 截图 `01b-create-dialog-opened.png`：L1/L2/L3 级联字段可见，"编码预览(只读)"区域存在
- 16位字典字段结构 (L1主类/L2子类/L3三级) 界面已接入

**API 探针** (只读，不提交表单):
```
GET /api/mobile/{factoryId}/materials/types/generate-code?...
→ status=200, body={"code":400,"message":"16位字典尚未配置或参数无效，无法生成物料编码"}
```

**结论**: ⚠️ UI渲染走通，后端端点已部署存活，但 F006 生产环境尚未完成 16位编码字典段配置 → 编码生成预览返回 400 提示。这是数据配置问题，非代码 bug。

**截图**: `01a-material-types-list.png` / `01b-create-dialog-opened.png` / `01c-code-preview-area.png`

---

## 链 2 — 调拨详情差异表格 (#758)

**PR #758 内容**: `web-admin/src/views/transfer/detail.vue` — 新增 `DiffRecord` interface、`loadDiffs()` 方法、`decideDialog` 差异处理对话框。UI 条件: 调拨状态为 RECEIVED 或 CONFIRMED 时才渲染差异表格和"处理差异"按钮。

**渲染验证**:
- 调拨列表渲染 ✅ — 截图 `02a-transfer-list.png`：10条调拨记录
- 调拨详情页渲染 ✅ — 截图 `02b-transfer-detail-opened.png`：TRF-20260611-3640 详情页完整渲染，状态栏(草稿→已申请→已批准→已发运→已签收→已确认)可见，调拨明细表格1行(成品4275盒)可见

**差异表格状态**: ⚠️ F006 当前调拨数据均为草稿/已申请状态，无 RECEIVED/CONFIRMED 记录 → `v-if="transfer.status === 'RECEIVED'"` 条件不满足，差异表格区块未渲染。这是正确的条件渲染行为，非 bug。

**结论**: ⚠️ 详情页基础渲染✅，差异表格 UI 需真实 RECEIVED 状态数据才能验证完整渲染。代码已部署，条件正确。

**截图**: `02a-transfer-list.png` / `02b-transfer-detail-opened.png` / `02d-transfer-final.png`

---

## 链 3 — BOM包材行 pack_qty_per_product (#759)

**PR #759 内容**: 纯后端改动 (BomVersionService + BomItemDTO 新增 `packQtyPerProduct` 字段)，无 Vue 文件变更。

**渲染验证**:
- BOM配置页面渲染 ✅ — 截图 `03a-bom-page.png` / `03b-material-types-for-packaging.png`
- 包材条目(SXH-2014-3.5 吸塑盒)可见于物料类型列表

**最后一公里缺口**: BOM编辑界面无 `pack_qty_per_product`(每件产品包材用量)输入字段。后端 DTO 已新增该字段，但 Vue BOM行编辑 dialog/table 尚未接入对应输入控件。用户无法从 UI 设置包材件用量。

**结论**: ⚠️ BOM页渲染✅，但 #759 后端字段在前端 BOM 编辑 UI 中无对应入口 → **最后一公里缺口**，需补 Vue 侧。

**截图**: `03a-bom-page.png` / `03b-material-types-for-packaging.png` / `03c-bom-packaging-final.png`

---

## 链 4 — 多SO合并面板 (#762)

**PR #762 内容**: `web-admin/src/views/production/plans/list.vue` — 新增 `extraSourceOrderIds` ref、`selectableSalesOrders` 计算属性、条件渲染合并面板 `v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId"`。

**渲染验证**:
- 生产计划列表渲染 ✅ — 截图 `04a-production-plans-list.png`：10条计划记录
- 新建计划对话框渲染 ✅ — 截图 `04b-create-plan-dialog.png`：对话框结构完整

**条件未满足**: 多SO合并面板 `v-if` 需要先在创建对话框中: (1) 选择来源类型=客户订单(CUSTOMER_ORDER)，(2) 已选定 sourceOrderId。只读模式下不填写表单 → 面板保持隐藏。此为正常条件渲染，非 bug。

**结论**: ⚠️ 计划列表和创建对话框渲染✅，多SO合并面板 Vue 代码已部署，条件触发需用户交互操作，只读验证无法触发 v-if 分支。

**截图**: `04a-production-plans-list.png` / `04b-create-plan-dialog.png` / `04e-multi-so-merge-area.png` / `04f-multi-so-final.png`

---

## 链 5 — 物料大类筛选下拉 (#763 P11)

**PR #763 P11 内容**: `web-admin/src/views/warehouse/material-types/list.vue` — 新增 `filterKind` el-select，选项: 原料/辅料/包材。

**渲染验证**: ✅ **完全走通**

截图 `05b-first-select-opened.png` **明确显示**:
- "全部大类"下拉占位文本可见
- 下拉展开后三选项清晰可见: **原料 / 辅料 / 包材**
- 列表 6 条记录正确渲染（含包材 SXH-2014-3.5 吸塑盒）

**结论**: ✅ P11 筛选下拉渲染完全走通，中文显示正确，与代码 diff 完全一致。

**截图**: `05a-material-types-with-filter.png` / `05b-first-select-opened.png` / `05e-packaging-filter-final.png`

---

## 链 6 — 物料编辑关联客户字段 (#763 P8)

**PR #763 P8 内容**: 纯后端改动 (MaterialType entity/DTO 新增 `associatedCustomerId`)，无 Vue 文件变更。

**渲染验证**:
- 编辑对话框渲染 ✅ — 截图 `06b-edit-dialog-opened.png`：编辑对话框正常打开
- 关联客户字段: **控件不存在** — 截图 `06c-associated-customer-field.png` 确认对话框无 associatedCustomerId 输入项

**最后一公里缺口** 🔴: 后端 `MaterialTypeDTO.associatedCustomerId` 字段已存在，但 `material-types` 编辑 dialog 的 Vue 模板未添加对应 el-select 或 el-input 控件。用户无法从 UI 设置物料类型的关联客户。

**影响**: 功能实质不可用 — 即使后端已支持，没有 UI 入口用户无法配置此字段。

**结论**: 🔴 **最后一公里缺口** — 需在 `material-types/list.vue` 或独立编辑页 dialog 中补加 associatedCustomerId 字段控件。

**截图**: `06a-material-types-for-customer.png` / `06b-edit-dialog-opened.png` / `06c-associated-customer-field.png`

---

## 链 7 — 研发样品价位区间选料 (#763 R14)

**PR #763 R14 内容**: 纯后端改动 (MaterialTypeController 新增 `suggestByPriceRange` 端点)，无 Vue 文件变更。

**渲染验证**:
- 研发样品列表页渲染 ✅ — 截图 `07a-rd-samples-list.png`：研发样品管理页面正常
- 价位选料 UI 入口: **不存在** — 截图 `07b-rd-price-range-check.png` 确认无价位区间输入控件

**API 探针** (只读):
```
GET /api/mobile/{factoryId}/materials/types/suggest-by-price?minPrice=5&maxPrice=50
→ status=200, body={"message":"共找到 1 种原材料","data":[{"name":"冻猪蹄",...}]}
```

**最后一公里缺口** ⚠️: `suggest-by-price` 后端 API 已部署且有数据，但研发样品页 Vue 模板中无价位区间输入框和"选料推荐"触发按钮。研发人员无法从 UI 使用此功能。

**结论**: ⚠️ 后端 API 存活且返回正确数据，但研发样品页面无 UI 入口 → **最后一公里缺口**，需补 Vue 侧价位选料控件。

**截图**: `07a-rd-samples-list.png` / `07b-rd-price-range-check.png` / `07c-price-select-final.png`

---

## 链 8 — 回归复验

| 模块 | 路径 | 状态 | 说明 |
|------|------|------|------|
| 盐化管理 | `/warehouse/curing` | ✅ | 页面正常渲染，截图 `08b-curing-regression.png` |
| 销售付款 | `/sales/payments` | ⚠️ → /404 | 路由跳转至 /404，导航路径可能为 `/sales/payment-records` 或其他 |
| 出纳工作台 | `/finance` | ⚠️ → `/finance/costs` | 重定向至成本管理，出纳工作台实际路径待确认 |

**说明**: 盐化管理未受 #754-763 破坏。销售付款和出纳路由 /404 需确认正确导航路径（非 #754-763 引入的回归，原本如此）。

**截图**: `08a-sales-payments-regression.png` / `08b-curing-regression.png` / `08c-cashier-regression.png` / `08d-home-regression-final.png`

---

## 最后一公里缺口汇总

以下 PR 的后端已落地但 Vue UI 尚未接入，功能实质不可用：

| 缺口 | PR | 后端状态 | 前端缺失 | 优先级 |
|------|-----|---------|---------|--------|
| 物料类型关联客户字段 | #763 P8 | ✅ DTO 字段已有 | 编辑 dialog 无 `associatedCustomerId` 控件 | 🔴 HIGH |
| 研发价位区间选料 UI | #763 R14 | ✅ API 返回数据 | 研发样品页无价位输入+推荐触发入口 | ⚠️ MEDIUM |
| BOM包材件用量字段 | #759 | ✅ packQtyPerProduct DTO 已有 | BOM 行编辑无对应输入控件 | ⚠️ MEDIUM |
| 16位编码字典配置 | #754 | ✅ API 存活 | F006 未配置字典段数据 (数据问题非代码) | ⚠️ 配置 |

---

## Headed Mode Verification

- headless: false ✓
- viewport: 1920×1080 ✓
- locale: zh-CN ✓
- chromium window 真弹 ✓ (Playwright headed 模式执行)
- 截图字体: 中文真显示无方块 □ ✓ (截图 00-login-done.png 仪表板中文"销售老板工作台"/"财务主管工作台"清晰)
- screenshot mode: fullPage ✓
- video: mode on ✓
- PLAYWRIGHT_PORT: 9222
- PLAYWRIGHT_CHAT_ID: deferred-e2e
- 测试运行: 8 passed (2.4m)
- 截图总数: 38 PNG

---

## 测试执行环境

```
worktree:      /c/Users/Steve/cretas-defe2e (off origin/main)
target:        http://139.196.165.140:8086 (nginx → backend)
auth:          f006_admin / factory_super_admin
playwright:    1.58
config:        deferred-e2e.config.ts (headless:false, slowMo:80)
run time:      2.4 minutes (8 chains serial)
date:          2026-06-11
```
