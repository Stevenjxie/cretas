# 缺上游依赖 → 创建并返回（Create-and-Return Upstream）通用模式 — 设计文档

**日期**: 2026-06-22
**状态**: 设计已批准，待写实现计划
**触发**: 新建生产计划弹窗，来源=销售订单时，选中的订单「产品行」下拉显示「无数据」（该订单没有任何订单行），用户卡死——无法继续建计划，也没有任何引导去补产品行。
**关联规则**: `.claude/rules/fool-proof-design.md`（Rule 5 dead-end→导航 / Rule 2 上下文）、`.claude/rules/multi-model-dispatch.md`（权限/RLS 红线）

---

## 1. 背景与问题

生产计划依赖一条上游链：

```
产品类型/产品  →  销售订单(绑产品行)  →  生产计划
```

当某一级上游为空时，下游表单出现「无数据」死路。截图实例：销售订单 `KPIDEMO-DEM-20260607-025` 没有任何产品行 → 生产计划的「产品行」下拉「无数据」→ 用户无法继续，且无任何下一步引导。

这种「缺上游依赖」的死路**不止生产计划**，在销售、餐馆、采购、生产等多个模块普遍存在（见 §7 清单）。因此需要一套**可复用的机制**，而不是在生产计划里写死一份。

### 关键约束：跳转的权限边界（客户明确要求）

普通用户权限按模块隔离：销售的人只管销售、生产的人只管生产、餐馆的人只管餐馆。**跨模块跳转（如生产→销售）只有多模块权限的管理员才能用。** 把一个只有生产权限的用户甩到销售页面 = 他没权限、跳过去也是死路。

因此：
- **有目标页权限** → 显示「去创建 →」跳转按钮
- **无目标页权限** → 显示「请联系 XX 补充」提示，**绝不给点不动的跳转**

---

## 2. 目标 / 非目标

**目标**
1. 做出一套**可复用**的「缺上游 → 创建并返回」机制（composable + 空状态组件），任何模块拖进去即可用。
2. 每个跳转**带权限门**，兑现上面的权限边界约束。
3. 在**生产计划**场景落地验证（pilot），解决截图死路。
4. 产出其他「缺上游」场景的**盘点清单**作为后续 backlog。

**非目标**
- 本次不实现生产计划以外的接入点（仅出清单）。
- 不改后端（复用已有 `PUT /sales/orders/:id`，DRAFT-only，已含 items）。
- 不引入新的全局状态库或路由架构；只在现有 `_returnTo`/`ReturnBanner` 之上封装。

---

## 3. 复用现有基建（0 或极少改动）

| 复用项 | 位置 | 说明 |
|---|---|---|
| `_returnTo` query 约定 + `ReturnBanner.vue` | `web-admin/src/components/layout/ReturnBanner.vue`（已挂 `AppLayout`） | `_returnTo = encodeURIComponent(route.fullPath)`，banner 提供「返回原页面」。**链式返回天然成立**：每跳一级把上一级（连同它自己的 `_returnTo`）整个编码进去，逐级回退，无需额外栈逻辑。 |
| `goConfigureProduct()` 先例 | `web-admin/src/views/sales/orders/list.vue:102` | 已用 `router.push('/system/products?_returnTo='+encodeURIComponent(route.fullPath))` 跳产品页再返回，并用 `permissionStore.canAccess('system')` 做权限门。新机制把这个先例正式封装。 |
| 后端 `PUT /orders/{orderId}`（`updateOrder`） | `controller/inventory/SalesController.java:351`，`@Operation(... "仅 DRAFT 状态可编辑")`，`@RequirePermission("sales:read_write")`，`UpdateSalesOrderRequest.items` 字段在 | 给草稿订单加产品行**后端已支持**，0 改动。 |
| 产品类型页 `/system/products`（弹窗式创建） | `web-admin/src/views/system/products/index.vue` | `ReturnBanner` 通用，建完点 banner 返回。0 改动。 |

---

## 4. 通用机制设计

### 4.1 composable `useCreateAndReturn`

新文件：`web-admin/src/composables/useCreateAndReturn.ts`

职责（薄封装，无业务）：
- `canReach(module: string, opts?: { write?: boolean }): boolean` — 查 `permissionStore.canAccess(module)`（默认）或 `canWrite(module)`（`write:true`）。**权限按【模块】判断**——仓库无 code 级 `'sales:read_write'` 检查，统一用 `canAccess`/`canWrite`。
- `goCreate(targetPath: string, opts?: { reopen?: string }): void` — 跳到 `targetPath`，自动附加 `_returnTo = encodeURIComponent(reopen ?? route.fullPath)`。`reopen` 用于「返回后需要重开弹窗」的场景（携带重开意图的 URL，见 §5.1）。
  - 若 `targetPath` 已含 query，正确合并 `_returnTo`（用 `URL`/手工拼接，保证 encode 一致）。

接口契约（消费者只需知道这三点）：输入=目标路径+可选重开意图；输出=完成导航；依赖=vue-router + permissionStore。不持有任何业务状态。

### 4.2 空状态组件 `<UpstreamMissingHint>`

新文件：`web-admin/src/components/common/UpstreamMissingHint.vue`

Props：
| prop | 类型 | 说明 |
|---|---|---|
| `description` | string | 缺什么，如「该订单暂无产品行」 |
| `targetModule` | string | 目标页所属模块（如 `sales` / `system`） |
| `requireWrite` | boolean | 是否需写权限（默认 false=只需 `canAccess`；true=需 `canWrite`） |
| `actionText` | string | 有权限时按钮文案，如「去销售订单添加产品行」 |
| `contactText` | string | 无权限时提示文案，如「请联系销售或管理员为该订单补充产品行」 |

事件：`@action` — 有权限且用户点击按钮时触发（消费者在这里调 `goCreate`）。

渲染逻辑（把权限边界焊进 UI）：
- `canReach(targetModule, { write: requireWrite }) === true` → 渲染 `description` + 主按钮（点击 emit `action`）。
- 否则 → 渲染 `description` + `contactText`（灰字提示，无按钮）。

这是 fool-proof Rule 5（dead-end → 要么导航、要么明确「找谁」）的标准化落点。

### 4.3 不改 `ReturnBanner`

返回腿完全复用，无改动。

---

## 5. 试点：生产计划

### 5.1 生产计划弹窗 `web-admin/src/views/production/plans/list.vue`

**(a) 空产品行的死路改成 `<UpstreamMissingHint>`**

当 `sourceType==='CUSTOMER_ORDER'` 且已选订单且 `selectedOrderItems.length===0` 时，在「产品行」表单项下渲染：

```vue
<UpstreamMissingHint
  description="该订单暂无产品行，无法据此排产"
  target-module="sales"
  require-write
  action-text="去销售订单添加产品行"
  contact-text="请联系销售或管理员为该订单补充产品行后再排产"
  @action="goAddOrderItems(planForm.sourceOrderId)" />
```

```ts
function goAddOrderItems(soId: string) {
  const reopen = `/production/plans?reopenPlan=1&planSO=${soId}`;
  goCreate(`/sales/orders/${soId}?editItems=1`, { reopen });
}
```

> 生产→销售本就是跨模块：纯生产用户 `canReach('sales', { write: true })===false` → 看到「联系销售补充」；多模块管理员 → 看到跳转按钮。**完全符合 §1 权限约束。**

**(b) `onMounted` 读 query 自动重开弹窗（统一入口）**

新增 `useRoute`。`onMounted` 末尾检查 query，命中以下任一即自动 `handleCreate()` → 设 `sourceType='CUSTOMER_ORDER'` → 设 `sourceOrderId` → 调 `handleSalesOrderSelect(soId)` 重新加载（此时已非空的）产品行 → 用 `router.replace` 清除该 query（避免刷新重复触发）：
- `reopenPlan=1 & planSO=<id>`（本设计的返回流）
- `salesOrderId=<id> & action=create`（**SO 详情页 `detail.vue:332` 已有但目前没人接的按钮**，顺手修好，统一成同一套处理）

### 5.2 销售订单详情页 `web-admin/src/views/sales/orders/detail.vue`

**(a) DRAFT 订单可编辑产品行**

仅当 `order.status === 'DRAFT'` 时，在（当前只读的）产品行表上方加「编辑产品行 / 添加产品行」按钮 → 打开编辑弹窗（见 5.3 共享组件）→ 保存调 `PUT /${factoryId}/sales/orders/:id`，body=`{ items, version }`（带乐观锁 version）。
- 非 DRAFT 的空订单（罕见，数据异常）→ 显示说明文案，不给编辑（Rule 5：解释而非死路）。
- 到达本页时若 URL 含 `editItems=1`，自动打开编辑弹窗（来自 5.1a 的跳转意图）。

**(b) 产品下拉为空 → 再套一级 `<UpstreamMissingHint>`**

编辑弹窗里产品类型下拉的数据源为空（整厂无产品）时：

```vue
<UpstreamMissingHint
  description="本工厂暂无产品类型"
  target-module="system"
  action-text="去创建产品类型"
  contact-text="请联系管理员先创建产品类型"
  @action="goCreate('/system/products')" />
```

因为本页 `route.fullPath` 已含 `_returnTo=生产计划`，跳产品页时 `_returnTo` = 本 SO 详情页 URL（其内嵌 `_returnTo=计划`）→ **返回链自动 计划 ← 订单 ← 产品**，逐级回退。

**(c) 返回**：保存产品行后刷新订单；顶部 `ReturnBanner`（到达时带 `_returnTo` 已显示）→ 用户点「返回「生产计划」」。

### 5.3 共享组件 `OrderItemsEditor.vue`（抽取，服务「复用/干净」诉求）

新文件：`web-admin/src/views/sales/orders/components/OrderItemsEditor.vue`（或 `components/sales/`）。

把 `sales/orders/list.vue` 新建弹窗里已有的「产品行行编辑 UI」（产品类型下拉、数量/规格/单位/单价/税率、`addItem`/`removeItem`/`ensureTrailingEmptyRow`）抽成受控组件：
- Props: `modelValue: items[]`、`products[]`（产品下拉源）；Emits: `update:modelValue`。
- **新建订单弹窗** 和 **详情页 DRAFT 编辑** 共用一份，避免两处漂移。

> 这是本次「代码更干净、好维护」的核心收益。若实现时发现新建弹窗耦合过深、抽取风险大，回退到「详情页单独写一份精简编辑器」，并在计划里记录该决定。

---

## 6. 防呆 / 安全对齐

| 维度 | 落实 |
|---|---|
| Rule 5（dead-end→导航/找谁） | 每个空状态都用 `<UpstreamMissingHint>`：有权限→按钮，无权限→「联系谁」 |
| Rule 2（上下文） | SO 编辑弹窗标题带订单号；按钮文案说清做什么 |
| 权限边界（客户约束） | 跳转按钮一律经 `canReach(module, {write})` 门控；跨模块对无权限用户隐藏 |
| 多租户/状态安全 | SO 行编辑仅 DRAFT（对齐后端 `PUT` 约束），不碰已发货/已开票订单；后端 `@RequirePermission("sales:read_write")` 仍是权威闸 |
| 幂等 | 重开弹窗用后即 `router.replace` 清 query，防刷新重复触发 |

---

## 7. 其他「缺上游」场景盘点（仅清单，后续 backlog）

快速扫描 `web-admin/src/views/**/{list,detail,index}.vue` 得到候选（命中「无数据/请先选择/请先创建」等）。每条标注模块归属 + 是否跨模块（决定权限门），后续逐个接入本机制：

| 场景 | 缺的上游 | 目标页 | 跨模块? |
|---|---|---|---|
| 生产计划（本次试点） | 销售订单产品行 → 产品类型 | sales / system | 是（admin） |
| 新建销售订单 | 客户 / 产品 | customers / system | 客户=同模块；产品=跨 |
| 餐馆 领料/报损/盘点/配方 | 菜品/原料/配方 | restaurant.* | 多为同模块 |
| 采购收货/采购单 | 供应商 / 物料 | suppliers / material-types | 视权限划分 |
| 仓库 调拨/出货/物料 | 物料类型 / 仓库 | material-types / warehouse | 多为同模块 |
| 质检 检验/标准 | 质检标准 / 批次 | quality.* | 同模块 |

> 清单后续在各自模块以 `<UpstreamMissingHint>` + `useCreateAndReturn` 接入；同模块优先（权限天然可达），跨模块自动降级为「联系谁」。

---

## 8. 改动文件清单

**新增**
- `web-admin/src/composables/useCreateAndReturn.ts`
- `web-admin/src/components/common/UpstreamMissingHint.vue`
- `web-admin/src/views/sales/orders/components/OrderItemsEditor.vue`

**修改**
- `web-admin/src/views/production/plans/list.vue`（空状态组件 + onMounted 重开 + 统一 query 处理）
- `web-admin/src/views/sales/orders/detail.vue`（DRAFT 编辑产品行弹窗 + 产品为空时的二级 hint + editItems 自动打开）
- `web-admin/src/views/sales/orders/list.vue`（新建弹窗改用 `OrderItemsEditor`；`goConfigureProduct` 可改为复用 `useCreateAndReturn`）

**后端**：无改动。

---

## 9. 测试

- **单元/组件**：`UpstreamMissingHint` 在有/无权限两态分别渲染按钮 vs 联系文案；`useCreateAndReturn.goCreate` 生成的 `_returnTo` 编码正确、含 reopen 意图。
- **e2e（headed，按 playwright-headed-mode 规则）**：
  1. 多模块管理员：生产计划选空订单 → 见跳转按钮 → 跳 SO 详情加产品行 →（若无产品）跳产品页建产品 → 逐级返回 → 回到生产计划弹窗且订单已有产品行可选。
  2. 纯生产权限用户：同场景 → 只见「联系销售补充」，无跳转按钮。
  3. SO 详情 DRAFT 编辑产品行保存 → `PUT` 成功 → 列表刷新。
- 用 **F006 测试租户**（不碰 LIUSHANMEN 真客户）。

---

## 10. Out of scope

- 生产计划以外接入点的实现（§7 清单留 backlog）。
- 非 DRAFT 订单的产品行编辑（后端不支持，且有发货/开票联动风险）。
- 后端任何改动。
