# RN OA「我的待办」财务/出纳手机端审批 — 设计 spec

**日期**: 2026-06-12
**类型**: 新功能（演示后 P0）
**状态**: 设计已批准（Steve 2026-06-12: Option A 后端聚合 + ¥5000 阈值 + 混合审批深度 + v1 全 5 类）

---

## 1. 背景 / 需求来源（客户转录，非内部 spec）

- **catalog 行402 / transcript-2b [39:21]**: "系统需设置审批流（电脑端+手机端）—— 电脑端/手机端都给审批流选项，审批完节点结束单子即生效"。
- **catalog 行791 [81:21]**: "复杂/大数据量操作放电脑端，简单必要操作放手机端"。审批是单点简单操作 → 属手机端。
- **Codex RN 第二波实测 (2026-06-12)**: `f006_finance_mgr` / `f006_cashier` 登录 RN 只有「首页/考勤/我的/系统设置」，**无任何审批入口** → 客户要的"审批流手机端"对财务角色缺失。

这是溯源审计确认的**唯一一条真·"客户要了但还缺"**（矩阵其余"缺口"经 gate 实查均已实现）。

## 2. 范围（YAGNI）

**v1 做**（Steve 拍板「全 5 类一次做全」）:
| 待办类型 | 角色 | 现有审批端点（复用，不重写） |
|---|---|---|
| 采购财审 | finance_manager | `POST /purchase/orders/{id}/finance-approve` |
| 销售财审 | finance_manager | `POST /sales/orders/{id}/finance-approve` |
| 价格异常 | finance_manager | `POST /...supplier-delivery/{id}/price-anomaly/approve` |
| 盘点审批 | finance_manager | `POST /stocktakes/{id}/approve` |
| 已审付款（出纳付款） | cashier | `PUT /payment-requests/{id}/mark-paid`（出纳执行） |

**⛔ 明确不做（gold-plating，客户 0 提）**:
- 通用 WorkflowEngine 引擎集成（DisposalRecord 那套是 dead code 负资产）。纯聚合现有审批端点。
- 复杂凭证明细 / 完整报表 / 批量审批 → 留 PC（客户 device-split）。
- 报损审批进 OA（报损 approver 是 production_manager/厂长，非财务；v1 不纳入，二期按需）。

## 3. 架构（Option A — 后端统一待办聚合）

```
RN MyTodoListScreen
  → GET /api/mobile/{factoryId}/my-todos        (按 caller 角色过滤, 一次拿全)
  → GET /api/mobile/{factoryId}/my-todos/count  (徽标)
  ← TodoItemDTO[] (统一形状)
  → 审批动作: 调各域现有 approve 端点 (不经聚合层)
```

**为何 A 不是 RN 客户端聚合**: 契合客户"OA 我的待办"单一清单心智；5 域 pending 查询已存在，聚合只是 fan-out + 角色过滤；RN 一个调用一个列表一个徽标，扩展只改后端。

## 4. 组件

### 4.1 后端（新增）

**`TodoItemDTO`**（统一待办项）:
```
type          : 枚举 PURCHASE_FINANCE_REVIEW / SALES_FINANCE_REVIEW / PRICE_ANOMALY / STOCKTAKE_APPROVAL / PAYMENT_DISBURSE
refId         : 业务单 ID (调 approve 端点用)
refNumber     : 单号 (展示, 如 PO-2026...)
title         : 卡片标题 (如 "采购财审 — 北京飞熊")
amount        : 金额 (BigDecimal, 阈值判定 + 展示)
counterparty  : 对象 (供应商/客户名)
submittedBy   : 申请人名
submittedAt   : 提交时间
needDetail    : boolean (amount > 阈值 → true, RN 据此强制走详情)
detailPath    : RN 详情路由 hint (可选)
```
对 amount 不可得的项（理论上 5 类都有金额），`needDetail` 默认 true（保守，强制看详情）。

**`MyTodoAggregatorService`**:
- `listTodos(factoryId, callerRole)`: 按角色映射 fan-out 到对应域的 pending 查询（已存在：采购 finance-review pending / 销售同 / `price-anomaly/pending` / stocktake PENDING_APPROVAL / `payment-requests/approved`），各自映射成 TodoItemDTO，合并排序（提交时间倒序）。
- `countTodos(factoryId, callerRole)`: 同上只返数量（徽标）。
- 角色映射常量：finance_manager → {采购财审,销售财审,价格异常,盘点审批}；cashier → {已审付款}。default → 空（其他角色无待办）。
- **阈值**: `@Value("${cretas.oa.todo.detail-threshold:5000}")` BigDecimal，`amount.compareTo(threshold) > 0` → needDetail=true。server 端常量，无配置 UI（后续可升级 per-factory）。

**`MyTodoController`**:
- `GET /api/mobile/{factoryId}/my-todos` → `ApiResponse<List<TodoItemDTO>>`，`@RequirePermission` 宽松（任何登录用户看自己角色的待办，聚合层按角色过滤）。
- `GET /api/mobile/{factoryId}/my-todos/count` → `ApiResponse<Integer>`。
- caller 角色从请求属性取（JwtAuthInterceptor set 的 role，非空 SecurityContext —— C1 孪生坑规避，复用现有 `getRole(request)` 范式）。

### 4.2 RN（新增）

- **导航入口**: MainNavigator 对 `finance_manager`/`cashier` 加「我的待办」tab（带 count 徽标，复用 ConfigChangeSet `/pending/count` 徽标范式）。
- **`MyTodoListScreen`**: 单列表，`useMyTodos` hook 调 `/my-todos`。卡片显 **单号 + 金额 + 对象 + 申请人 + 提交时间**（防呆 Rule 2 上下文带身份）。
  - `needDetail=false`（≤阈值）: 卡片底部 **「通过」「驳回」** 两键。驳回 → 弹 **原因 dropdown**（标准原因 5-8 项 + 其他才填字，防呆 Rule 3）→ 调域 reject 端点。通过 → 确认 → 调域 approve 端点。
  - `needDetail=true`（>阈值）: 卡片底部只有 **「查看详情」**（无直接审批键）→ `TodoDetailScreen`。
- **`TodoDetailScreen`**: 按 type 拉对应域详情（复用现有详情 API），显全明细 → 底部「通过」「驳回」（同上 dropdown）。
- **审批后**: 列表刷新 + 徽标减 1 + sticky toast 反馈（防呆 4 位一体）。
- **空状态**: 「暂无待办」诚实显示（防呆 Rule 5，非假数据）。
- **幂等**: 审批端点已幂等（如 #777 报损范式 / 付款已审 409）；RN catch 409 → 提示「已被处理」+ 刷新（防并发双审）。

## 5. 数据流

```
登录(finance_mgr) → MainNavigator 显「我的待办」tab(徽标=count)
  → 进 tab → GET /my-todos → 渲染卡片
  → 小额卡 → 一键通过 → 确认 → POST 域 approve → 刷新+徽标-1
  → 大额卡 → 查看详情 → TodoDetailScreen(域详情) → 通过/驳回 → 刷新
  → 驳回 → 原因 dropdown → POST 域 reject
出纳同理: cashier tab → /my-todos(只付款类) → 标记已付(mark-paid)
```

## 6. 错误处理 / 防呆（4 位一体）

- 卡片带 单号/金额/对象/申请人（Rule 2）。
- 驳回原因 dropdown 非自由文本（Rule 3）。
- 大额强制详情（Steve 拍板混合深度，防误批）。
- 幂等 409 → 「已被处理」+刷新（防并发）。
- error toast：具体文案 + 后端 message 原文 + sticky + 下一步（Rule 4 位一体）。
- 空状态诚实（Rule 5）。

## 7. 测试

- **后端**: `MyTodoAggregatorService` 单测（角色过滤：finance_mgr 出 4 类 / cashier 出付款 / 其他空；fan-out 各域 mock；阈值 needDetail 判定边界 ¥5000）。`MyTodoController` 端点测（角色属性 + 聚合）。
- **RN headed E2E**: finance_mgr 登录 → 待办 tab 有徽标 → 小额卡一键通过(域端点真调) → 大额卡强制详情 → 详情审批 → 驳回原因 dropdown；cashier → 付款待办 → mark-paid。SQL 坐实状态流转。

## 8. 明确边界（OUT OF SCOPE）

- WorkflowEngine 引擎集成（gold-plating）。
- per-factory 阈值配置 UI（v1 常量，后续）。
- 报损审批进 OA（v1 不纳入，approver 非财务）。
- owner 角色待办（v1 = finance_manager + cashier）。
- 复杂凭证/报表/批量 → PC。
- 推送通知（v1 进 tab 拉取 + 徽标；push 后续）。

## 9. 分发预览（spec 批准后出正式分发卡）

| 块 | 推荐 | scope 锁 |
|---|---|---|
| 后端聚合(Controller+Service+DTO+测试) | Sonnet in-harness（rule-aware: 角色属性/Decimal/@RequirePermission） | 新文件 + 0 改现有审批端点 |
| RN 屏+导航+hook | Sonnet in-harness（走 ux-flow 防呆 + RN 报工屏范式复用） | MainNavigator + 新 screens |
| 🔒 终审 | Opus（权限/角色过滤/审批正确性 = 红线） | - |

🔒 红线：角色过滤（finance_mgr 不能看不该看的）、审批正确性、幂等 → Opus 终审从 main 部署。
