# SP6 · 采购到付款 — 设计 Spec

> **子项**: SP6 Purchase-to-Payment  
> **Flyway 号段**: V20260910_50 ~ V20260910_59  
> **波次**: Wave 2（依赖 SP4 MaterialBatch factory_number/origin_place 完成）  
> **编写**: 2026-06-09 Sonnet in-harness  
> **红线终审**: Opus (🔒 见 §8)

---

## 1. 目标与范围

### 1.1 目标

用数字流程替代微信钉钉付款申请，实现：
- 采购入库时真实数量与 PO 差异的**异常单分流**（超收/少收 → 采购员决策退/接）
- 采购退货走**财务审批**后仓库执行退货出库（现有 ReturnOrder 补全 withGoods=TRUE）
- **付款申请**触发 P0 轻量状态机，双端审批后出纳只读执行付款
- **6 类结算属性** 映射会计科目，为 SP11 凭证导出提供结构化数据
- **采购发票**（区别于现有 sales-side InvoiceRecord）管理：未到票提醒 + 上传自动核销

### 1.2 范围边界

**In scope**:
- PurchaseOrder 增量字段：settlementType + contractNumber
- PurchaseReceiveRecord 增量字段：exceptionType + exceptionQty + decisionStatus
- PurchaseException 新实体：异常单（超收/少收差值，采购员决策）
- ReturnOrder 补全：withGoods=TRUE 时仓库出库执行（MaterialBatch 扣减/状态更新）
- PaymentRequest 新实体：触发点 + P0 状态机（PENDING→FINANCE_REVIEW→APPROVED→PAID）
- PurchaseInvoice 新实体（purchase-side，区别于 sales-side InvoiceRecord）
- Supplier.paymentTerms 从 free text 升级为枚举
- 出纳角色（cashier）：只读查看已审批付款申请，记录实际付款

**Out of scope**:
- SP12 全功能审批引擎（P0 轻量状态机；SP12 上线后 adapter 迁移）
- 总账/金蝶 API 对接（决策 4：仅导出表头格式，H-flow P2）
- 采购计划驱动（SP5 scope）
- 月结自动结算（SP11 scope）

---

## 2. 现状复用分析

| 现有资产 | 状态 | SP6 复用方式 |
|---------|------|-------------|
| `PurchaseOrder` entity | exists | 增量加 3 字段（settlementType/contractNumber/invoiceReminderDays）|
| `PurchaseOrderItem` entity | exists | 不变 |
| `PurchaseReceiveRecord` entity | exists | 增量加 exceptionType/exceptionQty/decisionStatus 3 字段 |
| `PurchaseReceiveItem` entity | exists | 不变 |
| `ReturnOrder` entity | partial | withGoods=TRUE 补 仓库出库执行链（Phase C 标注项）|
| `ReturnOrderItem` entity | exists | 不变 |
| `ArApTransaction` | exists | PaymentRequest 审批通过 → 触发写 AP_PAYMENT 条目 |
| `InvoiceRecord` | exists (sales-only) | 结构参考；PurchaseInvoice 是独立新实体，FK 指向 PO |
| `PurchaseOrderApprovalRule` | exists | P0 状态机复用审批规则引擎 |
| `PurchaseServiceImpl.validateOverReceiveCap` | exists | 复用 overReceiveRate 超收上限校验 |
| `Supplier.paymentTerms` | free text → enum | ALTER + 枚举 migration |
| web `views/procurement/` | exists | 增量加 exception/payment/invoice tab |
| RN `PurchaseOrderCreateScreen` + `DetailScreen` | exists | 增量加 settlement 字段展示 |

**不复用（独立新建）**:
- `PaymentRequest` entity（付款申请工作流，非 ArApController 的账务记录）
- `PurchaseInvoice` entity（采购发票，FK→PO，区别于 sales-side InvoiceRecord）
- `PurchaseException` entity（异常单，独立于 ReceiveRecord 的决策文档）

---

## 3. 数据模型增量（Flyway V20260910_50 ~ _59）

### 3.1 Flyway 号段分配

| 号 | 文件名 | 内容 |
|----|--------|------|
| V20260910_50 | `__sp6_purchase_order_settlement.sql` | PO 增量字段 |
| V20260910_51 | `__sp6_supplier_payment_terms_enum.sql` | Supplier paymentTerms 结构化 |
| V20260910_52 | `__sp6_purchase_receive_exception_fields.sql` | ReceiveRecord 异常字段 |
| V20260910_53 | `__sp6_purchase_exception_table.sql` | PurchaseException 新表 |
| V20260910_54 | `__sp6_payment_request_table.sql` | PaymentRequest 新表 |
| V20260910_55 | `__sp6_purchase_invoice_table.sql` | PurchaseInvoice 新表 |
| V20260910_56 | `__sp6_return_order_purchase_fields.sql` | ReturnOrder 补 withGoods 出库字段 |
| V20260910_57 | `__sp6_purchase_accounting_subjects.sql` | SettlementType → 会计科目映射表 |
| V20260910_58 | `__sp6_cashier_role_intent.sql` | cashier 角色 intent 绑定（ai_intent_config）|
| V20260910_59 | `__sp6_indexes.sql` | 补充索引 |

### 3.2 PurchaseOrder 增量字段（V20260910_50）

```sql
ALTER TABLE purchase_orders
  ADD COLUMN settlement_type       VARCHAR(32),    -- 见枚举 SettlementType
  ADD COLUMN contract_number       VARCHAR(100),   -- 合同号（可选）
  ADD COLUMN invoice_reminder_days INTEGER DEFAULT 30;  -- 未到票提醒天数，0=不提醒
```

**SettlementType 枚举**（新建 `entity/enums/SettlementType.java`）:

```java
public enum SettlementType {
    PREPAID,          // 预付
    CREDIT_FIRST,     // 赊销先入库
    NO_INVOICE,       // 未到票
    MONTHLY,          // 月结
    CREDIT_PERIOD,    // 账期（需填 creditDays）
    IMMEDIATE         // 现结
}
```

### 3.3 Supplier paymentTerms 结构化（V20260910_51）

```sql
ALTER TABLE suppliers
  ADD COLUMN payment_terms_type    VARCHAR(32),    -- SettlementType 枚举值
  ADD COLUMN credit_days           INTEGER;        -- 账期天数，仅 CREDIT_PERIOD 有效
-- 保留 payment_terms TEXT 列（历史数据），新字段优先
```

### 3.4 PurchaseReceiveRecord 异常字段（V20260910_52）

```sql
ALTER TABLE purchase_receive_records
  ADD COLUMN exception_type        VARCHAR(32),    -- OVER_RECEIVE / UNDER_RECEIVE / null
  ADD COLUMN exception_qty         DECIMAL(15,4),  -- 异常数量（绝对值）
  ADD COLUMN decision_status       VARCHAR(32);    -- PENDING_DECISION / ACCEPTED / RETURNED
```

**ExceptionType 枚举**:
```java
public enum ReceiveExceptionType { OVER_RECEIVE, UNDER_RECEIVE }
public enum ReceiveDecisionStatus { PENDING_DECISION, ACCEPTED, RETURNED }
```

### 3.5 PurchaseException 新表（V20260910_53）

异常单：入库完成后由系统生成（差量 ≠ 0 时），采购员在单上做决策。

```sql
CREATE TABLE purchase_exceptions (
  id                  VARCHAR(191) PRIMARY KEY,
  factory_id          VARCHAR(191) NOT NULL,
  exception_number    VARCHAR(50) NOT NULL,
  receive_record_id   VARCHAR(191) NOT NULL,
  purchase_order_id   VARCHAR(191) NOT NULL,
  supplier_id         VARCHAR(191),
  material_type_id    VARCHAR(191),
  material_name       VARCHAR(200),
  exception_type      VARCHAR(32) NOT NULL,   -- OVER_RECEIVE / UNDER_RECEIVE
  po_quantity         DECIMAL(15,4) NOT NULL,
  received_quantity   DECIMAL(15,4) NOT NULL,
  exception_qty       DECIMAL(15,4) NOT NULL,
  unit                VARCHAR(20),
  decision            VARCHAR(32),            -- ACCEPT_OVER / RETURN_OVER / ACCEPT_SHORT / REQUEST_RESUPPLY
  decision_by         BIGINT,
  decision_at         TIMESTAMP,
  decision_notes      TEXT,
  status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- PENDING / RESOLVED
  created_by          BIGINT NOT NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at          TIMESTAMP,
  UNIQUE(factory_id, exception_number)
);
CREATE INDEX idx_pe_factory ON purchase_exceptions(factory_id);
CREATE INDEX idx_pe_receive ON purchase_exceptions(receive_record_id);
CREATE INDEX idx_pe_status  ON purchase_exceptions(status);
```

**ExceptionDecision 枚举**:
```java
public enum ExceptionDecision {
    ACCEPT_OVER,      // 超收：接受（补 PO 追加），更新 PO item received_quantity
    RETURN_OVER,      // 超收：退回多余数量 → 触发 PURCHASE_RETURN
    ACCEPT_SHORT,     // 少收：接受现有数量（本次关闭）
    REQUEST_RESUPPLY  // 少收：要求补货（发起补采购单）
}
```

### 3.6 PaymentRequest 新表（V20260910_54）

```sql
CREATE TABLE payment_requests (
  id                  VARCHAR(191) PRIMARY KEY,
  factory_id          VARCHAR(191) NOT NULL,
  request_number      VARCHAR(50) NOT NULL,
  purchase_order_id   VARCHAR(191) NOT NULL,
  supplier_id         VARCHAR(191) NOT NULL,
  supplier_name       VARCHAR(200),
  settlement_type     VARCHAR(32) NOT NULL,
  amount              DECIMAL(15,2) NOT NULL,       -- 申请付款金额
  currency            VARCHAR(10) DEFAULT 'CNY',
  bank_name           VARCHAR(100),                 -- 收款银行（冗余供出纳查看）
  bank_account        VARCHAR(50),                  -- 收款账号
  accounting_subject  VARCHAR(100),                 -- 会计科目（来自 settlement_type 映射）
  due_date            DATE,                         -- 付款截止日（账期计算）
  status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  -- PENDING → FINANCE_REVIEW → APPROVED → PAID / REJECTED
  requested_by        BIGINT NOT NULL,
  requested_at        TIMESTAMP,
  finance_reviewed_by BIGINT,
  finance_reviewed_at TIMESTAMP,
  finance_notes       TEXT,
  approved_by         BIGINT,
  approved_at         TIMESTAMP,
  paid_by             BIGINT,                       -- 出纳
  paid_at             TIMESTAMP,
  payment_voucher_url VARCHAR(500),                 -- 付款凭证 OSS URL
  remark              TEXT,
  version             BIGINT NOT NULL DEFAULT 0,
  created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at          TIMESTAMP,
  UNIQUE(factory_id, request_number)
);
CREATE INDEX idx_pr_factory ON payment_requests(factory_id);
CREATE INDEX idx_pr_po      ON payment_requests(purchase_order_id);
CREATE INDEX idx_pr_status  ON payment_requests(status);
CREATE INDEX idx_pr_due     ON payment_requests(due_date);
```

**PaymentRequestStatus 枚举**:
```java
public enum PaymentRequestStatus {
    PENDING,          // 待财务初审
    FINANCE_REVIEW,   // 财务审核中
    APPROVED,         // 已批准（待付款）
    PAID,             // 已付款
    REJECTED          // 已拒绝
}
```

### 3.7 PurchaseInvoice 新表（V20260910_55）

采购侧发票（进货发票），区别于销售侧 `invoice_records`。

```sql
CREATE TABLE purchase_invoices (
  id                  VARCHAR(191) PRIMARY KEY,
  factory_id          VARCHAR(191) NOT NULL,
  invoice_number      VARCHAR(50) NOT NULL,          -- PI-YYYYMMDD-XXXX
  purchase_order_id   VARCHAR(191) NOT NULL,
  supplier_id         VARCHAR(191) NOT NULL,
  supplier_name       VARCHAR(200),
  amount              DECIMAL(15,2) NOT NULL,         -- 不含税
  tax_amount          DECIMAL(15,2),
  total_amount        DECIMAL(15,2) NOT NULL,
  invoice_date        DATE,
  received_date       DATE,                           -- 票据实际收到日
  due_date            DATE,                           -- 账期到期日（settlement_type=CREDIT_PERIOD）
  status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  -- PENDING（未收票）/ RECEIVED（已收票未核销）/ RECONCILED（已核销）/ OVERDUE
  invoice_pdf_url     VARCHAR(500),                   -- OSS URL
  invoice_file_name   VARCHAR(255),
  ocr_invoice_number  VARCHAR(50),                    -- OCR 识别发票号
  ocr_amount          DECIMAL(15,2),
  ocr_confidence      DECIMAL(4,3),
  ocr_parsed_at       TIMESTAMP,
  reconciled_at       TIMESTAMP,
  reconciled_by       BIGINT,
  remark              TEXT,
  created_by          BIGINT NOT NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at          TIMESTAMP,
  UNIQUE(factory_id, invoice_number)
);
CREATE INDEX idx_pi_factory ON purchase_invoices(factory_id);
CREATE INDEX idx_pi_po      ON purchase_invoices(purchase_order_id);
CREATE INDEX idx_pi_status  ON purchase_invoices(status);
CREATE INDEX idx_pi_due     ON purchase_invoices(due_date);
```

### 3.8 ReturnOrder 采购退货出库补全（V20260910_56）

```sql
-- 补 withGoods=TRUE 时仓库执行出库的字段
ALTER TABLE return_orders
  ADD COLUMN warehouse_executed_at  TIMESTAMP,   -- 仓库出库完成时间
  ADD COLUMN warehouse_executed_by  BIGINT,      -- 执行人
  ADD COLUMN batch_ids              JSONB;        -- 出库扣减的 MaterialBatch id 列表
```

### 3.9 会计科目映射表（V20260910_57）

```sql
CREATE TABLE purchase_accounting_subjects (
  id                  SERIAL PRIMARY KEY,
  factory_id          VARCHAR(191) NOT NULL,
  settlement_type     VARCHAR(32) NOT NULL,
  accounting_subject  VARCHAR(100) NOT NULL,   -- 如 "2202 应付账款"
  description         VARCHAR(200),
  created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(factory_id, settlement_type)
);
-- 默认数据（可工厂级覆盖）
INSERT INTO purchase_accounting_subjects(factory_id, settlement_type, accounting_subject, description) VALUES
  ('__default__', 'PREPAID',      '1123 预付账款', '预付款供应商'),
  ('__default__', 'CREDIT_FIRST', '2202 应付账款', '赊销先入库'),
  ('__default__', 'NO_INVOICE',   '2202 应付账款-未到票', '未到票暂估'),
  ('__default__', 'MONTHLY',      '2202 应付账款', '月结'),
  ('__default__', 'CREDIT_PERIOD','2202 应付账款', '账期'),
  ('__default__', 'IMMEDIATE',    '1001 库存现金', '现结');
```

---

## 4. 业务流程与组件数据流

### 4.1 主流程图

```
采购员建 PO（settlementType + contractNumber）
       ↓
  采购审批（已有 PurchaseOrderApprovalRule）
       ↓
仓管收货（PurchaseReceiveRecord）
  ├── 数量吻合 → 正常入库 → MaterialBatch（已有链路）
  └── 数量差异 → 生成 PurchaseException（系统自动）
           ↓
     采购员查看异常单，做 ACCEPT/RETURN 决策
     ├── ACCEPT_OVER  → 更新 PO receivedQty，写 ArApTransaction(AP_INVOICE)
     ├── RETURN_OVER  → 自动创建 ReturnOrder(PURCHASE_RETURN, withGoods=TRUE)
     ├── ACCEPT_SHORT → 关闭异常，写 AP_INVOICE（实收金额）
     └── REQUEST_RESUPPLY → 提示采购员新建补采 PO（导航，不自动建）
           ↓
     （正常入库后）
       付款申请触发器：PO 所有行 received_quantity >= quantity → PaymentRequest 可创建
           ↓
  采购员提交 PaymentRequest（PENDING）
       ↓
  财务初审（FINANCE_REVIEW → APPROVED）
       ↓
  出纳只读终端：查看 APPROVED 列表 → 记录付款（PAID）→ 写 ArApTransaction(AP_PAYMENT)
       ↓
  发票管理（异步）：
   - 未到票提醒：PO 创建后 invoiceReminderDays 天无 PurchaseInvoice → 系统提醒
   - 上传发票 → OCR 自动填充 → 核销比对 → RECONCILED
```

### 4.2 异常单（PurchaseException）数据流

```
PurchaseReceiveRecord.confirm()  [Service层]
  │
  ├── 计算每行 diff = receivedQty - poQty
  ├── diff > 0 且 <= overReceiveRate×poQty → 正常超收（已有逻辑）
  ├── diff > overReceiveRate×poQty → 严重超收 → 生成 PurchaseException(OVER_RECEIVE)
  ├── diff < 0 → 少收 → 生成 PurchaseException(UNDER_RECEIVE)
  └── diff = 0 → 无异常，正常写 AP_INVOICE
```

### 4.3 PaymentRequest P0 状态机

```
PENDING
  ├── 财务 approve → FINANCE_REVIEW
  │     ├── 主管 approve → APPROVED
  │     │     └── 出纳 markPaid → PAID → 写 AP_PAYMENT ArApTransaction
  │     └── reject → REJECTED（通知采购员）
  └── 撤回（requestedBy 本人，status=PENDING）→ CANCELLED
```

### 4.4 PurchaseInvoice 核销流

```
系统定时器（每日）：
  SELECT po.id FROM purchase_orders po
  LEFT JOIN purchase_invoices pi ON pi.purchase_order_id = po.id AND pi.deleted_at IS NULL
  WHERE po.status = 'RECEIVED' AND pi.id IS NULL
    AND po.created_at < NOW() - INTERVAL '1 day' * po.invoice_reminder_days
  → 发通知给采购员

上传发票：
  POST /api/mobile/{factoryId}/purchase-invoices (multipart)
  → 调 DashScopeVisionClient.parseInvoicePdf（已有，复用 InvoiceRecord 的 OCR 链路）
  → 回填 ocr_invoice_number/ocr_amount/ocr_confidence
  → 前端展示 OCR 结果请用户确认 → 确认后 status=RECEIVED

核销：
  采购员点"核销" → 比对 total_amount vs PO totalAmount（含税）
  差异 > 1% → 提示但允许强制确认
  → status = RECONCILED, reconciled_at, reconciled_by
```

---

## 5. 端点归属

### 5.1 Java 后端端点（新增）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mobile/{fid}/purchase-orders/{id}/exceptions` | 获取 PO 下所有异常单 |
| POST | `/api/mobile/{fid}/purchase-exceptions/{id}/decide` | 采购员提交异常决策 |
| GET | `/api/mobile/{fid}/purchase-exceptions` | 异常单列表（带 status 筛选）|
| POST | `/api/mobile/{fid}/payment-requests` | 创建付款申请 |
| GET | `/api/mobile/{fid}/payment-requests` | 付款申请列表 |
| GET | `/api/mobile/{fid}/payment-requests/{id}` | 付款申请详情 |
| PUT | `/api/mobile/{fid}/payment-requests/{id}/submit` | 提交（PENDING → FINANCE_REVIEW）|
| PUT | `/api/mobile/{fid}/payment-requests/{id}/finance-approve` | 财务审批 |
| PUT | `/api/mobile/{fid}/payment-requests/{id}/approve` | 主管审批（APPROVED）|
| PUT | `/api/mobile/{fid}/payment-requests/{id}/reject` | 拒绝 |
| PUT | `/api/mobile/{fid}/payment-requests/{id}/mark-paid` | 出纳记录付款（PAID）|
| POST | `/api/mobile/{fid}/purchase-invoices` | 上传采购发票 |
| GET | `/api/mobile/{fid}/purchase-invoices` | 发票列表 |
| GET | `/api/mobile/{fid}/purchase-invoices/{id}` | 发票详情 |
| PUT | `/api/mobile/{fid}/purchase-invoices/{id}/reconcile` | 核销发票 |
| GET | `/api/mobile/{fid}/purchase-invoices/pending-reminder` | 未到票提醒列表 |
| PUT | `/api/mobile/{fid}/purchase-orders/{id}/settlement` | 更新 PO 结算类型 |

### 5.2 web-admin 新增页面（Vue）

| 路由 | 页面 | 说明 |
|------|------|------|
| `/procurement/exceptions` | `views/procurement/exceptions/list.vue` | 异常单列表 |
| `/procurement/exceptions/:id` | `views/procurement/exceptions/detail.vue` | 异常单详情 + 决策 |
| `/finance/payment-requests` | `views/finance/payment-requests/list.vue` | 付款申请列表 |
| `/finance/payment-requests/:id` | `views/finance/payment-requests/detail.vue` | 付款申请详情 |
| `/procurement/invoices` | `views/procurement/invoices/list.vue` | 采购发票列表 |
| `/procurement/invoices/:id` | `views/procurement/invoices/detail.vue` | 发票详情 + 核销 |

在已有页面上增量：
- `views/procurement/orders/detail.vue`：加「结算属性」tab + 「付款申请」tab
- `views/procurement/receives/list.vue`：加异常标记 badge + 「查看异常单」链接

### 5.3 React Native 屏幕

| 文件 | 说明 |
|------|------|
| `screens/warehouse/PurchaseReceiveScreen.tsx` | 扫码收货（已有，增量加异常弹窗确认）|
| `screens/procurement/PurchaseExceptionScreen.tsx` | 新：异常单详情 + 决策 |
| `screens/finance/PaymentRequestScreen.tsx` | 新：付款申请（采购员侧）|
| `screens/finance/CashierTerminalScreen.tsx` | 新：出纳只读终端（低技术素养 UX）|

---

## 6. 错误处理（Fool-Proof 4-in-1）

所有写操作失败必须满足以下 4 项（per `.claude/rules/fool-proof-design.md` 铁律）：

| # | 检查 | 实施 |
|---|------|------|
| a | 后端 response.message 具体 | `"付款申请 PR-20260610-0001 已审批，请勿重复提交"` 非 `"操作失败"` |
| b | 前端 toast = 后端 message | 前端原样 display `e.response.data.message` |
| c | error toast sticky | `ElMessage({ duration: 0, showClose: true })` |
| d | toast 含 next action 提示 | message 含 `"请联系财务审批 → 跳转 /finance/payment-requests/xxx"` |

**Rule 1（预先显示边界）**: 付款申请创建时，dialog 打开即显示 PO 总金额 + 已付金额 + 可申请金额；input 加 `:max`；超限 disable 提交。

**Rule 2（上下文必带身份）**: 所有审批 dialog 标题 `"审批付款申请 — {供应商名} {PO 号} ¥{金额}"`。

**Rule 3（dropdown 代替自由文本）**: 异常决策用 el-select（ACCEPT_OVER/RETURN_OVER 等 4 选项）+ 备注 textarea（可选）。结算类型用 el-select（6 选项）。

**Rule 4（幂等防重复）**: `PaymentRequest` 创建前检查同 PO 下是否已有 PENDING/FINANCE_REVIEW/APPROVED 状态的申请；存在则 409 `"PO-xxx 已有待审批的付款申请 PR-yyy，是否查看？"` + action link。

**Rule 5（dead-end → 导航）**: 出纳角色看到付款申请详情时若状态还是 PENDING，提示 `"此申请待财务初审，如需催审请联系财务"`+ 一键 @财务负责人（如系统配置有）。

---

## 7. 测试策略

### 7.1 单元测试（TDD 先写）

每个 Service 方法对应一个 test 类：

| 测试类 | 覆盖点 |
|--------|--------|
| `PurchaseExceptionServiceTest` | 超收生成异常单、少收生成异常单、决策 ACCEPT_OVER 更新 receivedQty、决策 RETURN_OVER 创建 ReturnOrder |
| `PaymentRequestServiceTest` | 创建、PENDING→FINANCE_REVIEW→APPROVED→PAID 状态机、幂等 409、PAID 写 AP_PAYMENT ArApTransaction |
| `PurchaseInvoiceServiceTest` | 创建、OCR 回填、核销（差异校验 1%）、未到票提醒列表 |
| `ReturnOrderPurchaseTest` | withGoods=TRUE 时仓库出库扣减 MaterialBatch（补全 Phase C）|

目标：每个 Service 类 ≥ 10 个单元测试，覆盖正常路径 + 至少 3 个边界/异常路径。

### 7.2 集成测试

- 完整流程：创建 PO → 收货有差异 → 异常单生成 → 决策 ACCEPT_OVER → 付款申请 → 财务审批 → 出纳 markPaid → AP_PAYMENT 落账
- 幂等：同 PO 二次创建付款申请 → 409
- ReturnOrder withGoods=TRUE → 仓库出库 → MaterialBatch 状态更新

### 7.3 前端校验

- web-admin：付款申请创建表单 max 约束（金额不超 PO 余额）
- RN：CashierTerminalScreen 只显示 APPROVED 状态，不显示 PENDING/PAID

---

## 8. 依赖（SP4）

SP6 depends on SP4 completing the following before SP6 goes to prod:

| 依赖字段 | SP4 负责 | SP6 用途 |
|---------|---------|---------|
| `MaterialBatch.factory_number` | SP4 | 入库时带入批次溯源号 |
| `MaterialBatch.origin_place` | SP4 | 入库时带入产地 |

如 SP4 尚未完成，SP6 可先做不涉及 MaterialBatch 的部分（付款申请 + 发票管理），入库链路 Phase B 再开。

---

## 9. 🔒 红线设计章（Opus 终审）

以下设计涉及财务权限、会计科目映射、跨模块事务，执行者仅实现到 PR，Opus 负责终审 + prod 部署。

### 9.1 权限隔离

- **PaymentRequest.create/submit**：角色 `purchaser` 或 `factory_admin`
- **PaymentRequest.financeApprove**：角色 `finance_manager`
- **PaymentRequest.approve（主管）**：角色 `factory_admin`
- **PaymentRequest.markPaid（出纳）**：角色 `cashier`（新角色）
- **PurchaseInvoice 操作**：`purchaser` + `finance_manager`
- **PurchaseException 决策**：`purchaser` 或 `factory_admin`

`cashier` 角色：只读 PaymentRequest（APPROVED 状态）+ 执行 markPaid；不得查看 PO 金额明细（`@PriceSensitive` 保护）。

### 9.2 会计科目映射不可硬编码

`purchase_accounting_subjects` 表提供工厂级可覆盖配置（factory_id + settlement_type 联合唯一）。`__default__` 行是兜底。PaymentRequest.accountingSubject 在创建时从该表查取，而非运行时推断。

### 9.3 AP_PAYMENT 双写事务

`PaymentRequestService.markPaid()` 必须在同一事务内完成：
1. `payment_requests.status = PAID`
2. `ArApTransaction` INSERT（AP_PAYMENT, counterpartyType=SUPPLIER）
3. `Supplier.currentBalance` 扣减

任何一步失败 → 整体回滚（per `fail-soft catch 救不回 doomed 事务` HARD 规则：避免 fail-soft try/catch 吞异常后父事务已 rollback-only）。

### 9.4 PurchaseException → ReturnOrder 事务边界

`decideException(RETURN_OVER)` 调用 `ReturnOrderService.createFromException()`，使用 `@Transactional(propagation = REQUIRES_NEW)` 隔离，防止异常单决策事务被 ReturnOrder 创建失败拖垮（per `feedback_failsoft_catch_cannot_save_doomed_tx.md`）。

### 9.5 Flyway 重复号检查

部署前必须执行：
```bash
git ls-tree origin/main src/main/resources/db/flyway \
  | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
```
确认无重复才允许 merge。当前 V20260910_01~03 已被占用，SP6 从 _50 起始，绝无冲突。

---

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像

**仓管员（warehouse_worker）** — 年龄 40-55，文化程度初中为主，主要用手机 App 完成收货扫码操作。看到英文报错或多余字段会困惑，更倾向"告诉我收多少就行"（客户张权原话）。  
**出纳（cashier）** — 财务兼职，不需要理解 ERP 流程，只需"看到 APPROVED 的单子，点付款，上传凭证"。

### 用户旅程（仓管员收货异常）

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 收货界面：PO 号 + 品名 + **「应收 100kg」** 大字 | 扫码/输入实收数量（如 110kg）| 数字大字号 ≥ 24px，numeric 键盘 |
| 2 | 提交后弹出橙色确认框：**「实收 110kg 超出应收 100kg（+10%）」** + 两个大按钮「确认入库」「取消」 | 点「确认入库」| 超收在系统允许范围内，一键确认 |
| 3 | 若超出超收上限，弹出红色说明框：**「超收已超上限，系统将生成异常单，由采购员处理」** + 「好的」| 点「好的」| 仓管无需做任何决策，告知即可 |
| 4 | 正常收货完成 → 绿色 Toast「收货成功，已入库 100kg」| 完成 | 无额外步骤 |

### 用户旅程（出纳付款终端）

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 出纳终端首屏：**「待付款 3 笔」** 列表，每笔显示「供应商名 + ¥金额 + 到期日」| 点击某笔 | 不显示 PO 明细价格（@PriceSensitive 保护）|
| 2 | 详情页：「应付金额 ¥18,000 — 工商银行 0123456789」+ 大号「确认付款」按钮 | 点「确认付款」| 弹 dialog |
| 3 | Dialog：**「确认向 [昆山XX供应商] 付款 ¥18,000？」** + 上传付款凭证按钮 | 上传截图/照片 → 点确认 | 付款记录为 PAID，凭证上传 OSS |
| 4 | Toast：**「付款成功，凭证已保存」**，列表刷新 | 完成 | 无后续步骤 |

### 摩擦点清单

| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 收货屏若只有空输入框无任何提示，仓管员不知道"应该收多少" | HIGH | fool-proof Rule 1（预先显示边界）|
| F2 | 异常弹出时出现"OVER_RECEIVE"等技术术语，仓管看不懂 | HIGH | 内联 UX 规则（错误文案双句式）|
| F3 | 出纳终端看到财务明细被限制后空字段，困惑"为什么是空" | MED | fool-proof Rule 2（上下文身份）|
| F4 | 超收允许上限已到，仓管被卡住不知道下一步是什么 | MED | fool-proof Rule 5（dead-end → 导航）|
| F5 | 出纳误触「付款」后没有二次确认，直接记录 | HIGH | fool-proof Rule 4（幂等防重复）+ Rule 2 |

### 每个摩擦点的设计回应

- F1 → 收货屏顶部固定显示「本单应收：{品名} {应收数量} {单位}」大字，作为收货时的参照锚点
- F2 → 异常弹窗文案改为「实收超出应收 10kg，系统已通知采购员处理，您可继续其他入库」（纯中文，不显示状态码）
- F3 → 出纳角色的 PaymentRequest 详情隐藏价格相关字段（unitPrice/taxRate），只显示「总付款金额」；说明文字「金额明细需财务权限查看」
- F4 → 超收超限时，弹窗底部加「了解更多」链接，说明「超出上限由采购员决定退货或补单，您已完成此次入库操作」
- F5 → 出纳「确认付款」dialog 必须展示供应商名 + 金额 + 银行账号三要素，且需要主动上传凭证才能 enable「确认」按钮（无凭证 = disabled）
