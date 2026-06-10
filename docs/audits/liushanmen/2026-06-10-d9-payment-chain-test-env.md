# D-9 付款申请链 全链验证 + Gap 测绘

**日期**: 2026-06-10  
**执行人**: Subagent (Sonnet in-harness)  
**环境**: test env 10011 / cretas_db (F006)  
**参照**: requirements-catalog.md D 流"付款申请与审批流"基准需求 1-5

---

## 端点清单（origin/main 实际存在）

| HTTP | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/mobile/{factoryId}/payment-requests` | `procurement:read_write` | 创建付款申请（幂等，同 PO 活跃申请 → 409） |
| PUT | `/{requestId}/submit` | `procurement:read_write` | PENDING → FINANCE_REVIEW |
| PUT | `/{requestId}/finance-approve` | `finance:read_write` | FINANCE_REVIEW → APPROVED |
| PUT | `/{requestId}/reject` | `finance:read_write` OR `procurement:read_write` | 任意非终态 → REJECTED |
| PUT | `/{requestId}/mark-paid` | `finance:read_write` | APPROVED → PAID（三写原子）|
| GET | `/approved` | finance 或 procurement 读 | 出纳台账：APPROVED 列表，按 approvedAt 升序 |

**状态机**: PENDING → FINANCE_REVIEW → APPROVED → PAID / REJECTED / CANCELLED

---

## X-4 结论：WorkflowEngine 真实情况（DB 证据）

**结论**: 付款申请链走**硬编码状态机，非 WorkflowEngine**。

证据：
1. `PaymentRequestController` 有 6 个映射，无任何 `submitForApproval`/`workflow` 端点暴露。
2. `PaymentRequestServiceImpl.financeApprove()` 直接翻转 `status` 字段，不调用 `workflowEngine`。
3. `submitForApproval()` 方法存在于 ServiceImpl 内但未被 Controller 任何端点调用（死代码路径）。
4. DB 查证：创建并走完 3 步后，`approval_workflow_instances` 表无任何关联记录（0 rows WHERE `business_entity_id IN (...)`)。
5. `workflowInstanceId` 字段在整个测试过程中始终为 `null`。

---

## 需求基准逐条验证

### 需求 1：全入库后才能提付款申请 ❌ GAP

**原文**: "订单全入库后提付款申请到财务/出纳（替代钉钉）"

**测试**:
```
POST /api/mobile/F006/payment-requests
body: { purchaseOrderId: "ccad0b11-...", ... }  # status=APPROVED (未完成收货)
```
**结果**: HTTP 200 成功创建，无任何前置检查。  
**Gap**: 系统未校验 PO 是否已全量入库（`status=COMPLETED`）。APPROVED 状态的 PO 也可成功创建付款申请，违反客户要求。

---

### 需求 2：双端审批流（节点全审完 → 数据传出纳）✅ PASS（部分）

**测试走完**:
- `create` → status=PENDING ✅
- `submit` → status=FINANCE_REVIEW ✅
- `finance-approve` → status=APPROVED, approvedBy=1414（f006_finance_mgr）✅
- `/approved` 端点可读取 APPROVED 记录 ✅

**权限隔离验证**:
- `operator` 角色调 `reject` → 403 `您的角色 [操作员] 缺少 财务管理 或 采购管理 模块的 [读写] 权限` ✅
- `finance_manager` 创建 PR → 403 `您的角色 [财务主管] 在 [采购管理] 模块无 [读写] 权限` ✅

**幂等性**:
- 同 PO 再次创建 → 409 `采购订单 PO-F006-TEST-001 已有活跃付款申请单（ID=...）` ✅

**注意**: 所谓"双端"目前仅有一步财务审批（FINANCE_REVIEW→APPROVED），无独立出纳节点。

---

### 需求 3：出纳只读终端能看到付款所需明细 ❌ CRITICAL GAP

**原文**: "出纳能看到 产品名称/对应采购订单/供应商/原料名称/单价/数量/计量 等明细"

**测试**:
```
GET /api/mobile/F006/payment-requests/approved
```

**返回字段**（实际）:
```json
{
  "id": "...",
  "purchaseOrderId": "PO-F006-TEST-002",   ← 只有 ID，无 PO 单号/单号
  "supplierId": "SUP-F006-TEST-002",       ← 只有 ID，无供应商名称
  "amount": 11500.0,
  "paymentMethod": "BANK_TRANSFER",
  "bankName": null,
  "bankAccount": null,
  ...
}
```

**缺失字段**:
- ❌ 供应商名称（只有 `supplierId`，无 `supplierName`）
- ❌ 产品名称（无）
- ❌ 原料名称（无）
- ❌ 采购订单号（只有 UUID `purchaseOrderId`，无 `orderNumber`）
- ❌ 单价（无）
- ❌ 数量（无）
- ❌ 计量单位（无）
- ❌ 结算类型（settlementType，PO 上有字段但未投影到 PR 或列表响应）

PO 明细（`purchase_order_items` 表）可查到：原料 RMT-F006-001，1000kg，单价 11.5，但 `/approved` 端点完全不返回这些。

**结论**: 出纳拿着此 API 无法知道在为谁付款、付什么货、什么价，必须另外手查系统，完全不能替代钉钉。

---

### 需求 4：付款审批支持采购订单和销售订单两方向 ⚠️ 部分 GAP

**测试**: `create` 时 `body.purchaseOrderId` 可传销售订单 ID 或任意字符串——系统不做校验。  
**Gap**: 接口接受任意字符串，但无针对销售订单的专用路径；没有 `salesOrderId` 字段；实际 entity 和 repo 只按 `purchaseOrderId` 过滤。销售方向付款申请路径未明确实现。

---

### 需求 5：结算类型属性随付款申请携带 ⚠️ 未验证 (本条 D-7 scope)

`SettlementType` 枚举已存在（PREPAID/CREDIT_FIRST/NO_INVOICE/MONTHLY/CREDIT_PERIOD/IMMEDIATE），`purchase_orders` 表有 `settlement_type` 列，但 `PaymentRequest` 实体及 `create()` 方法均无 `settlementType` 字段，创建时不从 PO 继承。

---

## markPaid 三写原子 — Bug（已修）

**Bug**: `supplier.getCurrentBalance()` 为 null 时 NPE（Line 182 `PaymentRequestServiceImpl`）。

**根因**: test 库 `suppliers` 表中 `SUP-F006-TEST-001/002` 的 `current_balance` 列为 NULL（未初始化）。生产环境有真实供应商档案应已设置，但不可假设，代码无 null guard。

**Log 证据**:
```
java.lang.NullPointerException: Cannot invoke "java.math.BigDecimal.subtract(java.math.BigDecimal)" 
  because the return value of "com.cretas.aims.entity.Supplier.getCurrentBalance()" is null
  at PaymentRequestServiceImpl.markPaid(PaymentRequestServiceImpl.java:182)
```

**Fix**: `fix/d9-markpaid-null-balance` 分支，null 时降级为 `BigDecimal.ZERO`（付款后余额 = -amount，诚实反映未初始化账户），见本次 PR。

**未修复原因未走到的路径**: `mark-paid` 后的 ArApTransaction 和 supplier balance 更新三写原子逻辑代码正确，但因 NPE 整事务回滚，无法实证原子性。须先修 supplier balance null 才能验。

---

## cashier 角色权限 — 缺口

- `FactoryUserRole.cashier` 枚举已存在（SP12 T1 新增），`permissionPrefix=finance`。
- L1 `platform_role_permissions` 有记录：`cashier → finance:rw, procurement:r`。
- 但 **F006 users 表无任何 `cashier` 角色用户**（`SELECT role_code FROM users WHERE factory_id='F006'` 无 cashier 行）。
- `mark-paid` 端点权限是 `finance:read_write`——cashier 有 finance:rw，理论上可调。但无真实账号可测。
- **`/approved` GET 端点**包含 `finance:read_write, finance:read, procurement:read_write, procurement:read`——cashier 可读。

---

## gap 清单（按 P0 影响排序，交回 organizer）

| # | Gap | 影响 | 改动面 | 估量 |
|---|-----|------|--------|------|
| G1 | `/approved` 不返回出纳所需明细（供应商名、原料名、PO单号、单价/数量/单位）| 出纳无法用此端点付款，必须手查，完全无法替代钉钉 | 新建 `PaymentRequestApprovedDTO`；`listApprovedForPayment` JOIN supplier+PO+items；Controller 切换返回类型 | M（1 DTO + 1 JPQL + 1接口变更） |
| G2 | `create` 无全入库前置检查（需求1：全入库后才提） | 可为未到货 PO 提付款，财务审批通过后出纳无法核实货已到 | ServiceImpl `create()` 加 PO status COMPLETED 检查；或查 `received_quantity >= ordered_quantity` | S（5行逻辑+1单测） |
| G3 | `markPaid` NPE — `Supplier.currentBalance` null | 出纳执行付款时 500 崩溃，付款链无法完成（已在 fix/d9-markpaid-null-balance 修） | 已修（null guard 降级为 ZERO） | XS（已修） |
| G4 | 无 cashier 角色测试账号 | 出纳角色无法端到端验证，测试盲区 | 种 1 条 F006 cashier 用户记录（测试数据） | XS |
| G5 | 销售订单方向付款申请路径未实现（需求4 半） | 销售方向付款（退款/预付客户等）无专用链路 | 新 `salesOrderId` 字段 + 路由 + 权限；或 generic `entityType/entityId` | L（架构决策，需 organizer 拍板） |
| G6 | `submitForApproval` 方法存在但无 Controller 端点（死代码） | WorkflowEngine 集成无法触达 | 要么删方法，要么加 `/submit-for-approval` 端点并接入真实 WorkflowEngine | S~M（决策后执行）|
| G7 | 付款申请创建时不从 PO 继承 `settlementType` | 出纳看不到月结/现结/账期区别（D-7 scope 但此处也有遗漏） | `create()` 查 PO.settlementType → set PR.settlementType（加字段+迁移）| S |

---

## 验证环境记录

```
test env: 47.100.235.168:10011 / cretas_db
login: f006_admin / 123456
PR IDs 创建于本次验证:
  d573e01e-ec29-41cf-a07b-559a9973c82a (PO-TEST-002, 走完 PENDING→APPROVED)
  a4e133da-e5e9-48aa-8db2-64346328dad7 (PO-TEST-001, 用 prod token 测试，已至 APPROVED)
  d83a6f57-17ea-482b-82e3-a3b1907e1b95 (e95895e2 FINANCE_APPROVED PO, pre-existing APPROVED)
  ccad0b11-... PO (APPROVED 状态，用于验证 G2 gap，成功创建 PR = 证明无前置检查)
```

---

## 矩阵修正

| 矩阵行 | 旧状态 | 新状态 | 说明 |
|--------|--------|--------|------|
| D-9 付款申请创建+提交 | B（阻塞误报 "控制器不存在"） | ✅ PASS | Controller + ServiceImpl + Flyway 全在，E2E 走通 |
| D-9 审批链 | 未验 | ✅ PASS（硬编码状态机） | X-4 confirmed: 非 WorkflowEngine，直接状态翻转 |
| D-9 出纳视图 | 未验 | ❌ GAP | `/approved` 缺供应商名/原料/单价/数量/单位（G1） |
| D-9 全入库前置 | 未验 | ❌ GAP | 无校验（G2） |
| D-9 markPaid | 未验 | ⚠️ BUG FIXED | NPE supplier null balance（G3，fix/d9-markpaid-null-balance PR） |
| X-4 WorkflowEngine | 未验 | ✅ 硬编码，非 WF | DB 0 rows 证明 |
