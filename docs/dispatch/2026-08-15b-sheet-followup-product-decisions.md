# Google Sheet 后续三项 + 财务审核后半步 —— 评估与长期方案（待 owner 拍板）

**日期**: 2026-08-15（承接 `2026-08-15-sheet-findings-product-decisions.md`）
**基线**: `origin/main` = `f9b0e41f60`（含 PR #2660）
**状态**: 已核实，**未实施**。按「产品决策先出长期方案 + mark，审核时再讨论」处理。

本轮已直接修掉、不属产品决策的两件（另见 commit）：
- 「新建计划产品类型无法滑动」= 同向嵌套 ScrollView 缺 `nestedScrollEnabled`（4 处 + AST 闸）
- skills 里的 F001 死凭证表（7 个文件）

---

## 一、采购订单新建 409

### ⚠️ 先说一件必须校正的事：「已定位」还差一步

交接件写的是「已定位：60s 防双击幂等闸」。**这个定位没有被证据支持** ——
`createPurchaseOrder` 这条路上能抛 409 的有**五处**，文案各不相同：

| # | 位置 | 文案 | 触发条件 |
|---|---|---|---|
| 1 | `PurchaseServiceImpl:323` | 「60 秒内已对该供应商创建采购单 (PO-…, 状态 DRAFT)」 | 幂等闸 |
| 2 | `requireActiveSupplier` | 供应商已停用 | 选了停用供应商 |
| 3 | `assertSupplierMaterialActive` | 「该供应商未启用所选物料的供应关系」 | 供应关系没开 |
| 4 | `applySupplierPurchaseContract` | 「供应商与物料的供应关系不存在」 | 同上，另一分支 |
| 5 | 同上 | 「供应商包装规格与原料包装换算不一致」 | 包装换算不一致 |

**这五条的修法互不相同。** 如果用户撞的是 3/4/5，改幂等键一点用都没有。

⇒ **请求 owner 提供 Sheet 里那条反馈的原始文案或截图**（后端每条都带 hint，一眼可辨）。
拿不到的话，下面的评估仍然成立 —— 因为**第 1 条不管是不是用户撞的那个，它本身就是缺陷**，理由见下。

我没有做线上复现：需要 `TEST_FACTORY_ADMIN_PASS`，按约束不处理明文口令。

### 幂等闸本身：它是这一族里唯一没有内容维度的

真实的键（`PurchaseOrderRepository:73`）比交接件写的多两个条件：

```
factoryId + supplierId + createdBy + status = DRAFT + deletedAt IS NULL + createdAt >= now-60s
```

`status = DRAFT` 这一条交接件漏了。但它**救不了场**：web-admin 里「创建」与「提交」是两个动作
（`orders/list.vue:715` 创建 → `:1210` 另一个「提交」按钮），单据创建后**就是停在 DRAFT**。
所以误拦窗口是活的。

把同族的 7 道 R4 闸摆在一起看：

| 实体 | 键里的内容维度 | 窗口 |
|---|---|---|
| **PurchaseOrder** | **无** ❌ | 60s |
| ShipmentRecord | `quantity` ✅ | 60s |
| PaymentRecord | `amount` ✅ | 60s |
| InquiryQuote | `materialTypeId + quantity` ✅ | 5min |
| ExpenseRequest | `category + amount + expenseDate` ✅ | — |
| FoodSample | `batchNumber` ✅ | 5min |
| ~~InternalTransfer~~ | 无 ❌ | **已于 2026-06-18 移除** |

**决定性的先例**：调拨那道闸是同一个毛病，`TransferServiceImpl:193` 留了完整的墓志铭 ——

> 去重键是 (源厂 + 目标厂 + 请求人 + 调拨日期), **不含任何内容维度** …
> 2026-08-02 实测: 先建一张只含冻猪蹄的草稿, 紧接着建成品盒的调拨即被拒 … **备料被彻底卡住**。
> 同模式的兄弟实现都带内容维度 … **唯独调拨没有 —— 它是异类**。
> Steve 2026-08-03 拍板: 调拨不做重复提交拦截。

⚠️ 那句「唯独调拨没有」**当时就不成立** —— 采购订单是第二个异类，只是没被数到。
（形态 D：同一条约定 N 处实现，漏掉的那处从任何一侧看都像已经修好了。）

### 三个选项

| | 做法 | 保护 | 误拦 | 代价 |
|---|---|---|---|---|
| A | 放宽窗口 60s→10s | 弱化（超时重试普遍 >10s） | 变窄但仍在 | 最小 |
| **B（推荐）** | **加内容维度** | **不变** | **消除** | 一次比对，候选集通常 0~1 条 |
| C | 照调拨先例整道移除 | 无 | 无 | 双击多一张草稿 |

**推荐 B**：把 60s 内的候选单加载行项目，与请求的 `(materialTypeId, quantity)` 多重集比对，
**只有内容相同才判为双击**。这正是其余 6 个兄弟实现的做法，不需要迁移（无新列）。

- 双击 → 内容必然相同 → 照样 409，保护不减
- 同一供应商连下两张不同的单 → 内容不同 → 放行，误拦消失

**C 也可接受**，而且与 Steve 8-03 对调拨的拍板完全一致；前端本来就有
`submitting` 守卫 + `:loading` 禁用（`orders/list.vue:638/1451`），双击风险已被覆盖一层。
选 C 的话建议连带把「60s 窗口」这条约定从 R4 规范里撤掉，别再长出第三个。

⇒ **需要 owner 拍的**：B 还是 C。（A 不推荐 —— 它两头都不讨好。）

---

## 二、个人 OA 模块 —— 已定位，是「机制在、没接上」

### 现状

工厂超管（`factory_super_admin`，非餐饮）走 `BossNavigator`，那里有一个**无条件的「审批」tab**
（`BossNavigator.tsx:55`，挂 `OATodoStackNavigator`）。

而后端 `MyTodoAggregatorServiceImpl:149` 的 `ROLE_TYPES` **只有两个 key**：

```java
finance_manager → 7 类待办
cashier         → 1 类 (PAYMENT_DISBURSE)
// 其他角色 → getOrDefault(..., emptySet()) → 空列表
```

`factory_super_admin` 不在其中。它在权限矩阵里是全模块 read_write，
**过得了** Controller 上的 `@RequirePermission({"finance:read", ...})` ——
所以**不是 403，是 HTTP 200 + 空列表**。

⇒ **工厂超管点开「审批」tab，永远显示「暂无待办」。**

### 为什么这条最值得修

LIUSHANMEN 的采购审批链**只有一个节点** `admin_approval`，审批人角色正是
`factory_super_admin`（见 `2026-08-15-sheet-findings-product-decisions.md` 一节）。
**真正有采购单等着他批的那个人，手机上看到的是「暂无待办」。**

它不报错、不留痕，长得像「你没有待办」而不是「这里查不到你的待办」——
这正是本仓反复记的那种坏法：**读数完全正常，但量的不是你以为的那个东西。**

### 建议

| 层 | 改法 |
|---|---|
| 后端 | `ROLE_TYPES` 增 `factory_super_admin` → 至少 `PURCHASE_ORDER_APPROVAL`（业务审批，非财审）。需要新增一个 TodoType + 一个 fetcher，读 `approval_instances` 里当前节点指向该角色的实例 |
| 前端 | 不用改，tab 已经在那儿了 |
| 兜底 | 在没有任何 TodoType 映射的角色上，把空态文案从「暂无待办」改成「当前角色暂不支持待办聚合」——**别让「查不到」长得像「没有」** |

⚠️ **最后那条兜底建议单独拿出来做也值** —— 它成本极低，且立刻消除「假的 0」。
（对应规则里那条：兜底的默认值会把「我不知道」翻译成「是 0」。）

⇒ **需要 owner 拍的**：OA 待办中心是「财务专用」还是「全角色个人待办」。
若维持财务专用，那 `BossNavigator` 那个「审批」tab 就该**摘掉**，不能留一个永远空的入口。

### 顺带已修（不属决策）

`MyTodoController:31` 与 `MyTodoAggregatorServiceImpl:48` 的 javadoc 都写着
`finance_manager → 4 类`，而代码里是 **7 类**。我差一点照这段注释下「有 3 类待办不可达」的结论 ——
是读了 `ROLE_TYPES` 本身才没错。已把两处注释同步成 7 类。
（测试已覆盖全部 7 类，所以只有注释在漂。）

---

## 三、采购「财务已审核」的后半步

### #2660 停在哪儿

已做（审计痕迹说实话）：只有财务节点真的执行过才写 `financeReviewedBy/At`，否则留 null；
界面显示「无需财务审核（未设置审批节点）」。

**状态故意没动**：三条路仍然把单据置成 `FINANCE_APPROVED`，因为收货门禁认的就是它
（`PurchaseServiceImpl:1804`，`status != FINANCE_APPROVED && status != PARTIAL_RECEIVED` → 409）。
动它会把 LIUSHANMEN 的采购收货整条堵死。

### 关键事实：真财审的机制**整套都在**，只是被自动路径绕过

| 件 | 位置 | 状态 |
|---|---|---|
| `PENDING_FINANCE_REVIEW` 状态 | `PurchaseOrderStatus:23` | ✅ 有 |
| `submitForFinanceReview`（APPROVED → PENDING） | `PurchaseServiceImpl:1281` | ✅ 有 |
| `financeApproveOrder`（PENDING → FINANCE_APPROVED） | `:1294` | ✅ 有 |
| `financeRejectOrder` | `:1313` | ✅ 有 |
| web-admin 财审模块（列表+详情） | `views/procurement/finance-review/` | ✅ 有 |

⇒ 这不是「要新建一条财审流程」，是**已建好的那条从来没有入口** ——
`case APPROVED -> FINANCE_APPROVED` 这一跳把 `PENDING_FINANCE_REVIEW` 整个跳过去了。

### 建议：按工厂开关，默认维持现状

⛔ **不要全局强制**。LIUSHANMEN 没有财务节点、也没有财务岗，强制 = 采购收货多一步且无人能完成。

| | 方案 | 影响 |
|---|---|---|
| A | 维持现状 | 财审模块永远没有输入（功能建了没接） |
| B | 全局强制财审 | 堵死 LIUSHANMEN ⛔ |
| **C（推荐）** | **工厂级开关 `purchase.require-finance-review`，默认 `false`** | 默认零回归；开了的工厂才走 `APPROVED → PENDING_FINANCE_REVIEW` |
| D | 按金额阈值 | 可在 C 之上叠（F006 已有 ¥30000 阈值的先例） |

**C 的护栏（重要）**：允许打开这个开关之前，必须先校验该工厂
**至少有一个用户能做财审**（有 `finance:read_write` 的在职用户）。
否则开关一开，单据全部堆在 `PENDING_FINANCE_REVIEW` 没人能推 ——
那就是本仓记过的「给错误补的出口，出口自己是锁着的」。

⇒ **需要 owner 拍的**：接受 C 吗？以及默认值是否维持 `false`（我建议是）。

---

## 待 owner 拍板清单

| # | 决策 | 我的建议 |
|---|---|---|
| 1 | 采购 409：加内容维度 / 整道移除 | **加内容维度**（与 6 个兄弟实现一致）；移除也可接受 |
| 1b | Sheet 那条 409 的原始文案 | **需要 owner 提供** —— 五个候选文案，修法不同 |
| 2 | OA 待办：财务专用 / 全角色 | 全角色（至少补工厂超管）；若维持财务专用则**摘掉 Boss 那个空 tab** |
| 2b | 空态文案改「不支持」而非「暂无」 | **建议直接做**，成本极低 |
| 3 | 财务审核：工厂级开关 + 默认 false | **接受**，并加「该厂有财审岗」的前置校验 |
