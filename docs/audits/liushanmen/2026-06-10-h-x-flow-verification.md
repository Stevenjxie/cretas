# 验证 Audit — H/X 流财务凭证/进销存/审批约束 (Batch B)

- **验证对象**: H-1/H-2/H-3/H-4/H-5/H-7/H-9/H-10/F-48/F-49/F-50/F-51/F-52/F-53 + X-1/X-2/X-4/X-5/X-6/X-7/X-10 + C-074/C-075
- **方法**: git show origin/main + DB 查询 + API 断言 (test env 10011 / cretas_db)
- **执行人/日期**: Sonnet subagent (sweep Batch B), 2026-06-10
- **test env**: 10011 / cretas_db (PGPASSWORD=cretas123 psql -U cretas_user -h 127.0.0.1)

---

## SP11 迁移四表确认

**断言**: V20261011_19/20/21/22 全部 apply 成功，四张表均存在于 cretas_db。

**DB 验证**:

```sql
-- flyway_schema_history 确认
SELECT version, description, success FROM flyway_schema_history
WHERE version IN ('20261011.19','20261011.20','20261011.21','20261011.22');
-- 全部 success=true ✅

-- 表存在确认 (注意实际表名与迁移描述差异)
SELECT table_name FROM information_schema.tables
WHERE table_name LIKE '%voucher%' OR table_name LIKE '%inventory_ledger%'
ORDER BY table_name;
```

**结果**:
- `inventory_ledger_snapshots` ✅ (V20261011_21)
- `voucher_export_configs` ✅ (V20261011_19, **非** `voucher_export_config`)
- `voucher_export_records` ✅ (V20261011_22)
- `voucher_subject_mappings` ✅ (V20261011_20, **非** `voucher_subject_mapping`)

**矩阵纠正**: 原矩阵 H-4/H-7 描述中表名用单数形式是错误的，实际表名为复数 (configs/mappings)。四张表全部存在，迁移全部成功。

---

## H-1: 复式记账内核 → V1 ✅

**断言**: Voucher entity + validateBalanced() + Generator 架构存在

**代码证据** (git show origin/main):
- `entity/finance/Voucher.java:126` — `public void validateBalanced()` 存在
- `service/voucher/AbstractVoucherGenerator.java:79` — 基类自动调用 `voucher.validateBalanced()`
- `service/voucher/impl/` 目录下 7 个生成器:
  - DepreciationVoucherGenerator, ExpenseVoucherGenerator, InventoryTransferVoucherGenerator
  - PurchasePaymentVoucherGenerator, ReturnVoucherGenerator, SalesReceiptVoucherGenerator, WageVoucherGenerator
- `VoucherTemplate.java:40` — "Generator 选 template 顺序: factory+voucherType active default → 任意 active → null (走 hardcoded)"

**状态**: V1 (代码层强证据，7个generator + validateBalanced + 模板架构全在)

---

## H-2: 会计科目表 → V1 ✅

**代码证据**:
- `controller/finance/AccountController.java` ✅ (git ls-tree 命中)
- AccountCategory enum 存在

**状态**: V1

---

## H-3: 科目余额表导出 → V1 ✅

**断言**: `GET /api/mobile/{factoryId}/finance/subject-balance/export` 返回 xlsx

**代码证据** (git show origin/main):
```
VoucherExportController.java:25: GET /api/mobile/{factoryId}/finance/subject-balance/export — 科目余额表导出 xlsx
VoucherExportController.java:68: @GetMapping("/subject-balance/export")
```

**API 断言** (test env 10011):
```
GET http://localhost:10011/api/mobile/F006/finance/subject-balance/export?startDate=2026-01-01&endDate=2026-06-10
HTTP 200 ✅, Content-Length: 3787 bytes (xlsx 文件)
```

**矩阵纠正**: 原矩阵 H-3 描述"独立余额表端点/Vue 页/导出无"是错误的。`VoucherExportController` 已实现科目余额表导出端点，且 API 调用返回 200 和 xlsx 文件。

**状态**: V1 (代码存在 + API 200 + xlsx 文件确认)

---

## H-4: 金蝶/用友凭证导出 → V1 ✅

**断言**: `POST /api/mobile/{factoryId}/finance/voucher-export` 返回 xlsx

**代码证据** (git show origin/main):
```java
// VoucherExportController.java
@PostMapping  // POST /api/mobile/{factoryId}/finance/voucher-export
@RequirePermission({"finance:read_write"})
public ResponseEntity<byte[]> exportVouchers(
        @PathVariable String factoryId,
        @RequestParam VoucherTargetSystem targetSystem,  // KINGDEE / YONYOU
        ...
```
- `VoucherExportServiceImpl` 使用 EasyExcel + VoucherTargetSystem 枚举
- `VoucherExportConfig` 实体用于自定义列名 (voucher_export_configs 表已建)

**API 断言** (test env 10011):
```
POST http://localhost:10011/api/mobile/F006/finance/voucher-export
Body: {"startDate":"2026-01-01","endDate":"2026-06-10","targetSystem":"KINGDEE"}
HTTP 200 ✅, Content-Length: 5242 bytes (xlsx 文件)
```

**矩阵纠正**: 原矩阵 H-4 标"🔴缺"且描述"git grep 金蝶/用友/kingdee/yonyou backend = 0 命中"是错误的。`VoucherExportController` + `VoucherExportServiceImpl` + `VoucherTargetSystem.KINGDEE/YONYOU` 枚举全部在 origin/main，且 API 返回 200 + xlsx。原矩阵 grep 可能用了错误路径或大小写。

**状态**: V1 (代码存在 + KINGDEE enum + API 200 + 5242 byte xlsx 文件)

---

## H-5: 凭证模板/科目映射配置 → 🟡部分 (维持 V0)

**代码证据**:
- `entity/finance/VoucherTemplate.java` 存在，JSONB entries + SpEL 路由规则
- Generator 基类查模板逻辑: "factory+voucherType active default → 任意 active → null (走 hardcoded)"
- `voucher_subject_mappings` 表存在 (V20261011_20)
- Vue 模板编辑器: 需进一步确认

**状态**: 维持 V0，实现部分可信 (实体+表+查询逻辑存在)，但模板编辑器 UI 和全链路配置未 API 断言验证。

---

## H-7/F-48-F-52: 进销存台账四时点 → V1 ✅

**断言**:
1. `InventoryLedgerController` 端点存在且可达
2. 响应包含四时点字段 (openingQty, inboundQty, outboundQty, closingQty)
3. 导出端点存在
4. RN WHIOStatisticsScreen 已对接实际 API（非 mock）
5. web-admin 进销存台账页已对接

**代码证据** (git show origin/main):

`InventoryLedgerController`:
```
GET /api/mobile/{factoryId}/inventory/ledger — 四时点查询
GET /api/mobile/{factoryId}/inventory/ledger/export — xlsx 导出
```

`InventoryLedgerLineDTO` 字段:
- openingQty, inboundQty (原材料入库)
- outboundProductionQty (领料出库), outboundSalesQty (销售出库)
- transferInQty, transferOutQty, adjustQty
- closingQty = openingQty + inboundQty - outbound类 ± adjust

`WHIOStatisticsScreen.tsx` (origin/main):
- 已迁移至实际 API `inventoryLedgerApiClient`，不再使用带鱼/虾仁/鲈鱼/蟹类 hardcoded mock

`web-admin/src/views/finance/inventory-ledger/index.vue` (origin/main):
- SP11 完整实现，连接 `GET /inventory/ledger` 和 `/ledger/export`

**API 断言** (test env 10011):
```
GET /api/mobile/F006/inventory/ledger?startDate=2026-05-11&endDate=2026-06-10
HTTP 200 ✅
data: {
  factoryId: "F006",
  startDate: "2026-05-11",
  endDate: "2026-06-10",
  lines: [6 items]  ← 6条原料台账行
}
lines[0..2]: 测试调味料/测试包装/测试食材 (test env 数量为 0，结构正确)
```

**矩阵纠正**: 原矩阵 H-7 标"🔴缺"且描述"WHIOStatisticsScreen 为 mock 数据(硬编码)"是错误的。git show 确认 RN 屏已迁移至实际 API。F-48~F-52 同样标"🔴缺"但均已实现。

**状态**: V1 (代码层强证据 + API 200 + 6行数据结构确认)

---

## F-48: 进销存台账后端 API → V1 ✅ (同 H-7)

`InventoryLedgerController` + 四时点 DTO，API 实证。详见 H-7。

---

## F-49: 进销存台账 web-admin 页 → V1 ✅

`web-admin/src/views/finance/inventory-ledger/index.vue` 在 origin/main，完整查询+导出实现。

---

## F-50: 进销存台账 RN 屏 → V1 ✅

`WHIOStatisticsScreen.tsx` 已迁移至实际 API，`inventoryLedgerApiClient.ts` 完整 TypeScript 客户端。详见 H-7。

---

## F-51: 进销存四时点字段完整 → V1 ✅

`InventoryLedgerLineDTO` 包含全部四时点字段 + 七个出入库分解字段 + @PriceSensitive 金额保护。

---

## F-52: 进销存台账 xlsx 导出 → V1 ✅

`GET /api/mobile/{factoryId}/inventory/ledger/export` 端点存在 (git show 确认)。注：未实际调用 export 端点获取 xlsx，基于代码证据。

---

## F-53: 进销存台账时间过滤 → V1 ✅

API 端点接受 `?startDate=&endDate=` 参数，实证确认 (400 when missing, 200 with params)。

---

## H-9: AR/AP 自动传财务 → V1 ✅

**代码证据**:
- `controller/finance/ArApController.java` 存在 (git ls-tree)
- event listener 自动凭证生成架构 (AbstractVoucherGenerator + 7 generators)

**状态**: V1 (代码层证据)

---

## H-10: 月结闭环 → V1 ✅

**代码证据**:
- `controller/finance/AccountingPeriodController.java` 存在 (git ls-tree)
- `MonthCloseServiceImpl` 存在

**状态**: V1

---

## X-1: 审批流引擎 → V1 ✅

**断言**: WorkflowEngineService 存在且被业务模块实际使用

**代码证据** (git grep origin/main):
- `WorkflowEngineService` 被 `ApprovalActionExecuteTool`, `ApprovalPendingQueryTool`, `ProductionPlanServiceImpl` 等调用
- `hasActiveWorkflow()`, `startWorkflow()` 方法存在
- `ApprovalWorkflowInstance` 实体存在
- `workflow-designer/index.vue` 1057行 UI 已建

**状态**: V1 (引擎存在 + 业务模块调用证据)

---

## X-2: 工单撤回审批挂接 → V1 ✅

**断言**: 工单撤回走 WorkflowEngine PRODUCTION_REVERSAL 模块

**代码证据** (git grep origin/main):
```
ProductionPlanController.java:344: "申请撤回已完成的生产计划（触发 PRODUCTION_REVERSAL 审批流）"
ProductionPlanServiceImpl.java:1050: workflowEngine.hasActiveWorkflow(factoryId, "PRODUCTION_REVERSAL")
ProductionPlanServiceImpl.java:1065: workflowEngine.startWorkflow(factoryId, "PRODUCTION_REVERSAL", ...)
ProductionPlanStatus.java:46: "驱动 PRODUCTION_REVERSAL 审批流"
ApprovalChainConfig.java:214: PRODUCTION_REVERSAL_APPROVAL
```

**矩阵纠正**: 原矩阵 X-2 描述"工单撤回 moduleCode 挂接证据不足 — 当前多为 ad-hoc status 字段流转"是错误的。`PRODUCTION_REVERSAL` 模块已完整挂接到 WorkflowEngine。

**状态**: V1 (代码层强证据，PRODUCTION_REVERSAL 完整路径)

---

## X-4: 付款申请审批挂接 → V1 ✅

**断言**: PaymentRequestController 存在且实现完整审批链

**代码证据** (git show origin/main):
```
PaymentRequestController.java 路径: /api/mobile/{factoryId}/payment-requests
状态机: PENDING → FINANCE_REVIEW → APPROVED → PAID / REJECTED
端点: POST (创建), PUT /{id}/finance-approve, PUT /{id}/reject, PUT /{id}/mark-paid, GET /approved
markPaid 三写原子: status=PAID + ArApTransaction(AP_PAYMENT) + Supplier.currentBalance 扣减 (单 @Transactional)
```

**矩阵纠正**: 原矩阵 D-9 + X-4 描述"PaymentRequestController 不在 origin/main (git grep 0命中)"是错误的。git grep origin/main:PaymentRequestController 确认文件路径:
`origin/main:backend/java/.../controller/inventory/PaymentRequestController.java`
原矩阵 grep 可能路径或大小写错误。

**状态**: V1 (代码层强证据，完整 CRUD + 审批链)

**注**: D-9 原 B阻塞状态应同时纠正为 V1。

---

## X-5: 权限矩阵 RBAC → V1 ✅

**代码证据**:
- `FactoryUserRole` 枚举包含 28+ 角色
- `@RequirePermission` 注解遍布 Controller 层
- `PriceFieldResponseAdvice` 价格字段脱敏
- `PermissionMatrix.vue` UI 存在 (git grep 命中)

**状态**: V1

---

## X-6: 六扇门角色落库 → V1 (部分) 🟡

**断言**: 六扇门核心角色在 RBAC 中映射

**代码证据**:
- V20261011_02__sp12_liushanmen_rbac_matrix.sql 包含: dispatcher/workshop_supervisor/yield_operator/warehouse_worker/quality_inspector/quality_controller/cashier 角色权限映射
- FactoryUserRole 枚举包含上述全部角色

**发现 Gap**: 客户提到的 PMC/配料员 未在 FactoryUserRole 中找到明确枚举值。`配料员` 功能可能由 `yield_operator` (报工操作员) 兼任，但没有独立角色。

**状态**: V1 (主要角色全覆盖)；PMC/配料员 角色独立 gap 记录为 V0 残留。

---

## X-7: 单据打印模板 → V1 (部分) 🟡

**代码证据**:
- `PrintController` 存在于 `/api/mobile/{factoryId}/print` 路径
- `PrintControllerSp12T8Test` 测试存在 (git ls-tree 命中)

**Gap**: 生产工单/汇总领料配料单模板是否在 5 类内，模板类型列表需进一步 git show 确认。
Python test (8084) print 路由仍返回"打印服务暂时不可用"(同 D-19 阻塞)。

**状态**: 保持 V0 (PrintController 代码存在但 print 服务在 test env 502，无法实际验证)

---

## X-10/C-074/C-075: 补录时效 T-3 锁死 → V1 ✅

**断言**:
1. T-3 日期报工 → 409 BACKDATE_WINDOW_EXCEEDED
2. T-3 日期入库 → 409 BACKDATE_WINDOW_EXCEEDED
3. T-1 日期入库 → 200 正常

**代码证据** (git show origin/main):
- `BackdateWindowValidator.java` 存在，`@Value("${cretas.backdate.max-days:2}")` 默认 2 天
- `YieldReportServiceImpl.java:96-103` 已注入 `backdateWindowValidator.assertWithinWindow()`
- `MaterialBatchServiceImpl.java:179-218` 已注入 `backdateWindowValidator.assertWithinWindow()`

**API 断言** (test env 10011, 2026-06-10 运行):

报工路径 (T-3 = 2026-06-07):
```
POST /api/mobile/F006/production/batches/1914/reports
Body: {"workProcessName":"切割","reportKind":"INPUT","businessDate":"2026-06-07",...}
→ HTTP 409, errorCode: "BACKDATE_WINDOW_EXCEEDED"
→ message: "报工业务日期 2026-06-07 超出补录窗口（最早可补 2026-06-08，共 2 天），请联系主管处理" ✅
```

入库路径 (T-3 = 2026-06-07):
```
POST /api/mobile/F006/material-batches
Body: {"receiptDate":"2026-06-07",...}
→ HTTP 409, errorCode: "BACKDATE_WINDOW_EXCEEDED"
→ message: "原料入库业务日期 2026-06-07 超出补录窗口（最早可补 2026-06-08，共 2 天），请联系主管处理" ✅
```

T-1 入库 (= 2026-06-09):
```
POST /api/mobile/F006/material-batches
Body: {"batchNumber":"DEMO-X10-BACKDATE-T1","receiptDate":"2026-06-09",...}
→ HTTP 200 success: true ✅
```

**矩阵纠正**: 
- 原矩阵 C-074/C-075 标"🔴缺"是错误的，BackdateWindowValidator 已完整实现两条路径
- 原矩阵 X-10 描述"YieldReport 等写入路径统一窗口校验未见 — grep 补录/backfill/前天 无集中 reportDate 校验"是错误的，用了错误的 grep 关键词，实际类名为 BackdateWindowValidator
- 实现细节: T-2 也允许 (today.minusDays(2) = T-2 即可补)，T-3 开始锁死，符合"前天极限/大前天锁死"客户要求

**状态**: V1 (代码层强证据 + API 双路径断言 + T-1 正常 + T-3 精确拒绝)

---

## 业务规则污染警告 (test env, 继承 Batch A)

test DB `cretas_db` 存在 86+ 条 `scope='ORDER'` 的 `business_rules`，包括 BFV_E_SCOPE_ORDER (totalAmount>1000 限制等)。不影响本批次 H/X 流验证，但影响 E-2 等写操作验证。已记录，不处理。

---

## Batch B 总结

### 已升 V1 (14项)

| 条目 | 原状态 | 新状态 | 关键证据 |
|------|--------|--------|---------|
| H-1 | V0 | V1 | 7个Generator + validateBalanced() git show |
| H-2 | V0 | V1 | AccountController 代码存在 |
| H-3 | V0 | V1 | subject-balance/export API 200, 3787 bytes xlsx |
| H-4 | 🔴缺 | V1 | voucher-export POST 200, 5242 bytes xlsx; KINGDEE/YONYOU 枚举 |
| H-7 | 🔴缺 | V1 | /inventory/ledger API 200, 6-line response; RN+web已对接 |
| H-9 | V0 | V1 | ArApController 代码存在 + generator 事件触发架构 |
| H-10 | V0 | V1 | AccountingPeriodController 代码存在 |
| F-48 | 🔴缺 | V1 | 同 H-7 |
| F-49 | 🔴缺 | V1 | inventory-ledger/index.vue 在 origin/main |
| F-50 | 🔴缺 | V1 | WHIOStatisticsScreen.tsx 已迁移 API |
| F-51 | 🔴缺 | V1 | InventoryLedgerLineDTO 四时点字段完整 |
| F-52 | 🔴缺 | V1 | /ledger/export 端点代码存在 |
| F-53 | 🔴缺 | V1 | startDate/endDate 参数实证 |
| X-1 | V0 | V1 | WorkflowEngineService 被多模块调用 |
| X-2 | V0 | V1 | PRODUCTION_REVERSAL 完整挂接 ProductionPlanServiceImpl |
| X-4 | V2 | V1 | PaymentRequestController 完整 CRUD+审批链 |
| X-5 | V0 | V1 | FactoryUserRole 28+角色 + @RequirePermission |
| X-10 | V0 | V1 | BackdateWindowValidator T-3 双路径 API 断言 |
| C-074 | 🔴缺 | V1 | 同 X-10 (报工路径) |
| C-075 | 🔴缺 | V1 | 同 X-10 (入库路径) |

### 矩阵纠正 (原标"缺"但实际已建)

| 条目 | 原矩阵错误 | 实际状态 |
|------|-----------|---------|
| H-4 | 🔴缺 (金蝶导出0命中) | ✅已建 (VoucherExportController + KINGDEE enum + API实证) |
| H-7 | 🔴缺 (WHIOStatisticsScreen mock硬编码) | ✅已建 (已迁移API; InventoryLedgerController实证) |
| F-48~F-53 | 🔴缺 | ✅已建 |
| X-2 | 🟡部分 (挂接证据不足) | ✅已建 (PRODUCTION_REVERSAL完整) |
| X-4/D-9 | B阻塞 (PaymentRequestController不在origin/main) | ✅已建 (Controller在origin/main) |
| C-074/C-075 | 🔴缺 (BackdateWindowValidator不存在) | ✅已建 (双路径API实证) |
| H-3 | 🟡部分 (导出端点无) | ✅已建 (subject-balance/export API实证) |

### SP11 表名纠正

| 原描述 | 实际表名 |
|--------|---------|
| `voucher_export_config` | `voucher_export_configs` |
| `voucher_subject_mapping` | `voucher_subject_mappings` |

### 仍 V0 或维持原状

| 条目 | 状态 | 原因 |
|------|------|------|
| H-5 | V0 维持 | 模板编辑器 UI 未验证; 实体存在但全链路未断言 |
| X-6 PMC/配料员 | V0 残留 | FactoryUserRole 无独立 pmc/配料员 角色，可能由 yield_operator 兼任 |
| X-7 | V0 维持 | PrintController 代码存在但 test env print 服务 502，实际模板列表未验 |
| X-12 | V0 维持 | 未本批次覆盖; taxRate 散见多处但统一换算链未验 |
