# SP6 · 采购到付款 — 实施计划

> **子项**: SP6 Purchase-to-Payment  
> **Flyway 号段**: V20260910_50 ~ V20260910_59（_01~_03 已被占用，从 _50 起）  
> **实施策略**: TDD，Sonnet in-harness 执行 Java/migration；Composer 执行 web-admin UI；Opus 终审 🔒 红线  
> **worktree 规则**: 每 Phase 独立 `git worktree add -b feat/sp6-phaseX ../cretas-sp6-phaseX origin/main`

---

## 0. Flyway 号段查重纪律

**每次 merge 前必执行**（防 Flyway 撞车，per `feedback_flyway_cross_session_dup_collision.md`）：

```bash
git ls-tree origin/main src/main/resources/db/flyway \
  | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
```

已被占用的号（截止 2026-06-09 origin/main）：
- V20260910_01 ~ V20260910_03（餐饮 intent + 报工字段）
- SP6 分配：V20260910_50 ~ V20260910_59（冲突风险为零）

---

## 1. Phase A — 数据模型 + 后端 CRUD（优先级最高）

**目标**: Flyway 全量 apply，新实体 CRUD + 状态机核心，单元测试 ≥ 40 个

### Phase A 任务分解

#### A1 — Flyway migrations + 基础实体（Sonnet in-harness）

**worktree**: `git worktree add -b feat/sp6-a1-migration ../cretas-sp6-a1 origin/main`

**允许改**:
- `src/main/resources/db/flyway/V20260910_50~59__*.sql`（新建 10 个 migration 文件）
- `src/main/java/com/cretas/aims/entity/inventory/PurchaseException.java`（新建）
- `src/main/java/com/cretas/aims/entity/finance/PaymentRequest.java`（新建）
- `src/main/java/com/cretas/aims/entity/finance/PurchaseInvoice.java`（新建）
- `src/main/java/com/cretas/aims/entity/enums/SettlementType.java`（新建）
- `src/main/java/com/cretas/aims/entity/enums/PaymentRequestStatus.java`（新建）
- `src/main/java/com/cretas/aims/entity/enums/ExceptionDecision.java`（新建）
- `src/main/java/com/cretas/aims/entity/enums/ReceiveExceptionType.java`（新建）
- `src/main/java/com/cretas/aims/entity/enums/ReceiveDecisionStatus.java`（新建）
- `src/main/java/com/cretas/aims/entity/inventory/PurchaseOrder.java`（增量字段）
- `src/main/java/com/cretas/aims/entity/inventory/PurchaseReceiveRecord.java`（增量字段）
- `src/main/java/com/cretas/aims/entity/inventory/ReturnOrder.java`（增量字段）
- `src/main/java/com/cretas/aims/repository/inventory/PurchaseExceptionRepository.java`（新建）
- `src/main/java/com/cretas/aims/repository/finance/PaymentRequestRepository.java`（新建）
- `src/main/java/com/cretas/aims/repository/finance/PurchaseInvoiceRepository.java`（新建）

**禁改**: 所有 service/controller 文件（Phase A 只做 entity + migration + repository）

**验收**:
```bash
mvn clean package -DskipTests
# 应 BUILD SUCCESS，Flyway 10 个新 migration apply 无报错
# 日志确认: "Successfully applied 10 migrations to schema"
```

**TDD 目标**: entity 字段级别 unit tests（验证 @Column/关联/枚举）

---

#### A2 — PurchaseException Service + Controller（Sonnet in-harness）

**worktree**: `git worktree add -b feat/sp6-a2-exception ../cretas-sp6-a2 origin/main`（off origin/main after A1 merged）

**允许改**:
- `service/inventory/PurchaseExceptionService.java`（新建 interface）
- `service/inventory/impl/PurchaseExceptionServiceImpl.java`（新建）
- `controller/inventory/PurchaseExceptionController.java`（新建）
- `dto/inventory/CreateExceptionDecisionRequest.java`（新建）

**核心逻辑**:
1. `generateExceptionsForReceive(receiveRecordId)` — 在 `PurchaseServiceImpl.confirmReceive()` 末尾调用；遍历 items diff，生成 PurchaseException
2. `decideException(id, decision, notes)` — 状态机：
   - `ACCEPT_OVER` → 更新 PO item receivedQuantity，关闭异常
   - `RETURN_OVER` → 调 `ReturnOrderService.createFromException()` （REQUIRES_NEW）
   - `ACCEPT_SHORT` → 关闭异常，AP_INVOICE 记实收金额
   - `REQUEST_RESUPPLY` → 关闭异常（导航提示前端）
3. `listExceptions(factoryId, status, page)` — 分页查询

**单元测试文件**: `test/.../PurchaseExceptionServiceTest.java`（≥ 12 个 test cases）

**验收**:
```bash
mvn test -Dtest=PurchaseExceptionServiceTest
# 应 12+ PASS，0 FAIL
```

---

#### A3 — PaymentRequest Service + Controller（Sonnet in-harness）

**worktree**: `git worktree add -b feat/sp6-a3-payment ../cretas-sp6-a3 origin/main`

**允许改**:
- `service/finance/PaymentRequestService.java`（新建）
- `service/finance/impl/PaymentRequestServiceImpl.java`（新建）
- `controller/finance/PaymentRequestController.java`（新建）
- `dto/finance/CreatePaymentRequestRequest.java`（新建）
- `dto/finance/ApprovePaymentRequestRequest.java`（新建）

**核心逻辑**:
1. `create(factoryId, poId, amount)` — 幂等检查（同 PO PENDING/FINANCE_REVIEW/APPROVED 已存在 → 409）；查 `purchase_accounting_subjects` 取 accountingSubject；创建 PENDING 状态
2. `submit` → FINANCE_REVIEW
3. `financeApprove` → APPROVED（@RequireRole("finance_manager")）
4. `reject` → REJECTED（通知采购员）
5. `markPaid(id, voucherUrl)` — 🔒 **同一事务**：PAID + ArApTransaction(AP_PAYMENT) + Supplier.currentBalance 扣减（per 红线 §9.3）

**Role 校验**: 通过 `RequestContextHolder` 读 "role" attribute（per C1 孪生坑教训，非 SecurityContext）

**单元测试**: `PaymentRequestServiceTest.java`（≥ 15 test cases，含幂等 409、事务回滚、状态机转换）

**验收**:
```bash
mvn test -Dtest=PaymentRequestServiceTest
# 15+ PASS
curl -X POST http://localhost:10011/api/mobile/F006/payment-requests \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"purchaseOrderId":"...","amount":18000}'
# → 201 Created，request_number 自动生成
```

---

#### A4 — PurchaseInvoice Service + Controller（Sonnet in-harness）

**worktree**: `git worktree add -b feat/sp6-a4-invoice ../cretas-sp6-a4 origin/main`

**允许改**:
- `service/finance/PurchaseInvoiceService.java`（新建）
- `service/finance/impl/PurchaseInvoiceServiceImpl.java`（新建）
- `controller/finance/PurchaseInvoiceController.java`（新建）
- `dto/finance/CreatePurchaseInvoiceRequest.java`（新建）

**核心逻辑**:
1. `upload(poId, file)` — 调 `DashScopeVisionClient.parseInvoicePdf`（已有复用），回填 ocr_ 字段，status = RECEIVED
2. `reconcile(id, confirmedAmount)` — 比对 confirmedAmount vs PO totalAmount（含税）；差异 > 1% 提示（不卡死）；status = RECONCILED
3. `listPendingReminder(factoryId)` — 查 PO created_at + invoiceReminderDays 超时且无 PurchaseInvoice 的 PO 列表

**单元测试**: `PurchaseInvoiceServiceTest.java`（≥ 10 test cases）

---

## 2. Phase B — web-admin UI（Composer）

**前提**: Phase A 全部 PR merge 到 origin/main，API 已可调

### B1 — 异常单列表 + 决策页（Composer）

**worktree**: `git worktree add -b feat/sp6-b1-exception-ui ../cretas-sp6-b1 origin/main`

**允许改**:
- `web-admin/src/views/procurement/exceptions/list.vue`（新建）
- `web-admin/src/views/procurement/exceptions/detail.vue`（新建）
- `web-admin/src/views/procurement/receives/list.vue`（增量：加异常 badge）
- `web-admin/src/views/procurement/orders/detail.vue`（增量：加异常单 tab）

**UX 重点**（per fool-proof Rule 3）:
- 决策选择用 el-select（4 选项），不是 textarea
- el-select 选 RETURN_OVER → 自动弹出确认 dialog `"将创建采购退货单 → 您确认？"`
- 备注改为选完主决策才显示的可选 textarea

**验收**: `npm run build && npm run type-check`（0 错误）

---

### B2 — 付款申请列表 + 详情（Composer）

**worktree**: `git worktree add -b feat/sp6-b2-payment-ui ../cretas-sp6-b2 origin/main`

**允许改**:
- `web-admin/src/views/finance/payment-requests/list.vue`（新建）
- `web-admin/src/views/finance/payment-requests/detail.vue`（新建）
- `web-admin/src/views/procurement/orders/detail.vue`（增量：加付款申请 tab）

**UX 重点**:
- 列表按角色显示不同 columns（出纳不显示 unitPrice）
- 创建付款申请 dialog：预显 PO 总金额 + 已付金额 + 可申请余额（Rule 1）
- 金额 input `:max="canPayAmount"` + 实时校验

---

### B3 — 采购发票列表 + 核销（Composer）

**worktree**: `git worktree add -b feat/sp6-b3-invoice-ui ../cretas-sp6-b3 origin/main`

**允许改**:
- `web-admin/src/views/procurement/invoices/list.vue`（新建）
- `web-admin/src/views/procurement/invoices/detail.vue`（新建）

**UX 重点**:
- 未到票提醒 tab：显示超期天数 + 红色 badge
- 上传发票后 OCR 结果弹出比对卡（Rule 2 + Rule 1）：「OCR 识别：¥{ocrAmount}，本单合计：¥{poTotal}，差异：¥{diff}」
- 差异 > 1% → 橙色警告，强制勾选「我已确认差异，继续核销」才 enable 确认按钮

---

## 3. Phase C — React Native（Sonnet in-harness）

### C1 — PurchaseReceiveScreen 异常弹窗（Sonnet in-harness）

**worktree**: `git worktree add -b feat/sp6-c1-receive-rn ../cretas-sp6-c1 origin/main`

**允许改**:
- `frontend/CretasFoodTrace/src/screens/warehouse/PurchaseReceiveScreen.tsx`（增量）

**UX Flow 规则**（per ux-flow skill + fool-proof）:
- 提交后若有异常 → 橙色全屏确认弹窗（非 Alert.alert，用 AppDialog）
- 弹窗文案纯中文：「实收 110kg 超出应收 100kg，系统将生成异常单，由采购员处理」
- 仓管点「好的」即完成，无需做任何决策
- 触摸目标 ≥ 44×44pt

---

### C2 — CashierTerminalScreen（Sonnet in-harness）

**worktree**: `git worktree add -b feat/sp6-c2-cashier-rn ../cretas-sp6-c2 origin/main`

**允许改**:
- `frontend/CretasFoodTrace/src/screens/finance/CashierTerminalScreen.tsx`（新建）

**UX 设计**（per UX Flow Analysis 出纳旅程）:
- 首屏：大字「待付款 N 笔」，每笔卡片显示「供应商名 + ¥金额 + 到期日」
- 不显示 PO 明细（@PriceSensitive，出纳无需知道单价）
- 付款 dialog：必须上传凭证 → enable 确认按钮（Rule 4 防呆）
- 付款成功后卡片消失，sticky green toast「付款成功，凭证已保存」

---

### C3 — PurchaseExceptionScreen（Sonnet in-harness）

**worktree**: `git worktree add -b feat/sp6-c3-exception-rn ../cretas-sp6-c3 origin/main`

**允许改**:
- `frontend/CretasFoodTrace/src/screens/procurement/PurchaseExceptionScreen.tsx`（新建）

**UX**: 
- 展示异常单摘要（品名 + 异常数量 + 类型中文）
- 决策按钮 4 个（大按钮，非 select），选后显示确认 dialog

---

## 4. 分发总览

### 🚦 分发总览

| # | 任务 | 推荐模型 | 可否并行 | worktree 分支 | 🔒红线 |
|---|------|---------|---------|--------------|--------|
| A1 | Migration + 实体 + Repo | Sonnet in-harness | ✅ 独立 | feat/sp6-a1-migration | |
| A2 | PurchaseException Service | Sonnet in-harness | ❌ 依赖A1 | feat/sp6-a2-exception | |
| A3 | PaymentRequest Service | Sonnet in-harness | ❌ 依赖A1 | feat/sp6-a3-payment | 🔒（markPaid事务/角色鉴权）|
| A4 | PurchaseInvoice Service | Sonnet in-harness | ✅ 与A2/A3并行（A1后）| feat/sp6-a4-invoice | |
| B1-B3 | web-admin UI（3页）| Composer 2.5 | ✅ 互相独立 | feat/sp6-b1/b2/b3 | |
| C1-C3 | RN 屏幕（3个）| Sonnet in-harness | ✅ 互相独立 | feat/sp6-c1/c2/c3 | |
| Opus终审 | 🔒 红线终审 + prod 部署 | Opus | ❌ 串行最后 | main | 🔒 |

### Scope 锁地图

| 文件 / 目录 | 锁定 task | 预计解锁 |
|------------|----------|---------|
| `db/flyway/V20260910_50~59__*.sql` | A1 | A1 PR merge 后 |
| `entity/finance/PaymentRequest.java` | A1→A3 | A3 PR merge 后 |
| `entity/inventory/PurchaseException.java` | A1→A2 | A2 PR merge 后 |
| `entity/finance/PurchaseInvoice.java` | A1→A4 | A4 PR merge 后 |
| `PurchaseServiceImpl.java`（增量添加 generateExceptions 调用）| A2 | A2 PR |
| `screens/warehouse/PurchaseReceiveScreen.tsx` | C1 | C1 PR |
| `views/procurement/orders/detail.vue` | B1（exception tab）+ B2（payment tab）| B1 先，B2 后（不同 tab 不冲突）|

---

## 5. 每任务 Brief 卡

### 卡A1 → 贴给 Sonnet in-harness

**目标**: 创建 SP6 所有 Flyway migrations（V20260910_50~59）+ 新实体类 + Repository 接口  
**worktree**: `git worktree add -b feat/sp6-a1-migration ../cretas-sp6-a1 origin/main`  
**允许改**: `src/main/resources/db/flyway/V20260910_5*.sql`（新建10个）+ `entity/inventory/PurchaseException.java`（新建）+ `entity/finance/PaymentRequest.java`（新建）+ `entity/finance/PurchaseInvoice.java`（新建）+ 对应 enum 类（5个）+ 对应 Repository 接口（3个）+ PurchaseOrder/PurchaseReceiveRecord/ReturnOrder（增量字段）  
**禁改**: 所有 service/controller 文件，所有现有 migration 文件  
**规则摘要（in-harness 自动可见 .claude/rules/）**:
- database-entity-sync.md: 继承 BaseEntity 的实体必有 created_at/updated_at/deleted_at；PostgreSQL CAST(:param AS string) IS NULL 防止参数 null 报错
- field-naming-convention.md: entity camelCase，@Column snake_case，JSON camelCase  
- Flyway 号段 V20260910_50~59，当前 _01~_03 已被占，_50 起绝对不冲突  
**验收**: `mvn clean package -DskipTests` BUILD SUCCESS；Flyway apply 10 个新 migration 无报错  
**并行**: ✅ 独立，无依赖  
**交接**: PR off origin/main → `git diff origin/main...HEAD --stat` 确认 scope 仅新增文件  

---

### 卡A3 → 贴给 Sonnet in-harness（🔒 red line）

**目标**: PaymentRequest Service + Controller，包含 P0 状态机 + markPaid 三写事务  
**worktree**: `git worktree add -b feat/sp6-a3-payment ../cretas-sp6-a3 origin/main`（A1 merge 后开）  
**允许改**: `service/finance/PaymentRequest*.java`（新建）+ `controller/finance/PaymentRequestController.java`（新建）+ `dto/finance/Pay*.java`（新建）  
**禁改**: `ArApTransaction.java`/`ArApService.java`（只调用，不修改）；`Supplier.java` entity（只读 currentBalance）  
**核心规则（必须遵守）**:
- `markPaid()` 必须同一事务完成：PAID + AP_PAYMENT ArApTransaction + Supplier.currentBalance 扣减（任何失败全回滚）
- **禁止 fail-soft try/catch 吞异常后父事务已 rollback-only**（同 `feedback_failsoft_catch_cannot_save_doomed_tx.md`）
- 角色校验通过 `RequestContextHolder.getRequestAttributes()` 读 "role" request attribute，**不要用 SecurityContext**（SecurityContext 永空，per C1 孪生坑）
- 幂等：同 PO 下 PENDING/FINANCE_REVIEW/APPROVED 状态已存在 → 409 + message 含现有 requestNumber  
**🔒 收尾约束**: 只做到「实现 + 单元测试 + PR off origin/main」，不许自部署 prod，回 main 由 Opus 终审 + 部署  
**验收**: `mvn test -Dtest=PaymentRequestServiceTest`（≥ 15 PASS）  

---

### 卡B2 → 贴给 Composer 2.5

**目标**: 付款申请 web-admin 列表 + 详情页（`views/finance/payment-requests/`）  
**worktree**: `git worktree add -b feat/sp6-b2-payment-ui ../cretas-sp6-b2 origin/main`  
**允许改**: `web-admin/src/views/finance/payment-requests/list.vue`（新建）+ `detail.vue`（新建）+ `web-admin/src/views/procurement/orders/detail.vue`（增量：加付款申请 tab）  
**禁改**: 其他 vue 文件，后端文件  
**UX 规则（Composer 无 .claude/rules，以下自包含）**:
- 创建付款申请 dialog 预显「可申请余额 = PO 总额 - 已付金额」（fool-proof Rule 1）
- 金额 input 加 `:max="canPay"` + 超限 disable 提交按钮
- 审批 dialog 标题必须带「供应商名 + PO 号 + ¥金额」（Rule 2）
- error toast 用 `ElMessage({ duration: 0, showClose: true })`，非默认 3s 自动消失  
**验收**: `cd web-admin && npm run build && npm run type-check`（0 错误）  
**并行**: ✅ 与 B1/B3 独立  

---

## 6. 验收门控

### P0 基础通过标准

- [ ] `mvn test` 全绿（包含 A2/A3/A4 新 test class ≥ 37 个 test case）
- [ ] Flyway 10 个 migration 在 test DB 无报错 apply
- [ ] `PaymentRequest.markPaid` 事务回滚单测：模拟 ArApTransaction INSERT 失败 → PAID 状态不写入
- [ ] 幂等单测：同 PO 二次创建付款申请 → 抛 409 + 含 existing requestNumber
- [ ] `cd web-admin && npm run build`（0 error）
- [ ] `cd web-admin && npm run type-check`（0 error）

### 🔒 Opus 终审要点

1. `markPaid` 事务边界：确认 `@Transactional` propagation 正确，三写原子
2. 角色隔离：cashier 不能调 financeApprove/approve；purchaser 不能调 markPaid
3. `@PriceSensitive` 确认 cashier 角色 PaymentRequest response 中不暴露 PO unitPrice/taxRate
4. Flyway 重复号最终确认：`git ls-tree origin/main src/main/resources/db/flyway | grep V20260910 | sort`
5. 会计科目映射不可硬编码验证：`purchase_accounting_subjects` 表有 `__default__` 兜底行

---

## 7. 交接协议

```text
Steve 分发 Brief 卡 → Sonnet/Composer 独立 worktree 实现
   ↓
各自 PR off origin/main
   ↓
git diff origin/main...HEAD --stat 确认 scope 干净（无 sister 文件夹带）
   ↓
Opus 终审（🔒 A3 markPaid 事务 / 角色隔离 / @PriceSensitive）
   ↓
merge main → Opus 从 main 部署 test 验证 → prod
```

---

## 附：新建实体完整清单

| 实体 | 表名 | Flyway |
|------|------|--------|
| `PurchaseException` | `purchase_exceptions` | V20260910_53 |
| `PaymentRequest` | `payment_requests` | V20260910_54 |
| `PurchaseInvoice` | `purchase_invoices` | V20260910_55 |

新建枚举：`SettlementType`、`PaymentRequestStatus`、`ReceiveExceptionType`、`ReceiveDecisionStatus`、`ExceptionDecision`

修改现有实体（增量字段）：`PurchaseOrder`（+3）、`PurchaseReceiveRecord`（+3）、`ReturnOrder`（+3）、`Supplier`（+2）
