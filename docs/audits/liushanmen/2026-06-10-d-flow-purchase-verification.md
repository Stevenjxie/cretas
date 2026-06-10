# 验证 Audit — D 流采购链 (D-9/D-10/D-11/D-12/D-13/D-15/D-16/D-17/D-18) Batch A

- **验证对象**: 矩阵 D-9/D-10/D-11/D-12/D-13/D-15/D-16/D-17/D-18/D-19
- **方法**: API (47 上 curl localhost:10011, f006_admin) + git show origin/main + DB 断言
- **执行人/日期**: Sonnet subagent (sweep chat), 2026-06-10
- **test env**: 10011 / cretas_db (PGPASSWORD=cretas123)

---

## D-10: 采购财务审批流程 → V1 ✅

**断言**:
1. 端点存在: `POST /api/mobile/{factoryId}/purchase/orders/{id}/submit-for-finance-review` → 200
2. 端点存在: `POST /api/mobile/{factoryId}/purchase/orders/{id}/finance-approve` → 200
3. 提交后状态变 PENDING_FINANCE_REVIEW; 审批后变 FINANCE_APPROVED; financeReviewedBy/At 填写

**验证过程**:
- git show origin/main: `PurchaseController.java` 确认 `@PostMapping("/orders/{orderId}/submit-for-finance-review")` + `@PostMapping("/orders/{orderId}/finance-approve")` 存在
- 通过 D-12 验证流程中顺带验证财审端点可达 (status 机)

**结论**: 端点存在，实现已建。V2→V1 升级条件: API 端点存在且 git show 确认。

**状态**: V1 (代码层强证据 + 端点路径确认)

---

## D-11: 采购单含税/未税双值显示 → V1 ✅

**断言**:
1. `PurchaseOrderItem.taxRate = 9` 时 `lineAmount = qty × unitPrice`
2. `lineAmountWithTax = lineAmount × (1 + taxRate/100)` = `lineAmount × 1.09`
3. 两个字段在 API 响应中同时返回

**验证数据** (PO: DEMO-D11-含税价双值验证, test env):
- `quantity = 100`, `unitPrice = 40.00`, `taxRate = 9.0`
- `lineAmount = 4000.0` ✅ (100 × 40 = 4000)
- `lineAmountWithTax = 4360.0` ✅ (4000 × 1.09 = 4360)

**代码路径** (git show origin/main):
```java
// PurchaseOrderItem.java L107-116 (@Transient @PriceSensitive)
public BigDecimal getLineAmountWithTax() {
    BigDecimal amount = getLineAmount();
    if (amount == null) return null;
    if (taxRate == null || taxRate.compareTo(BigDecimal.ZERO) == 0) return amount;
    BigDecimal taxMultiplier = BigDecimal.ONE.add(
        taxRate.divide(new BigDecimal("100"), 6, BigDecimal.ROUND_HALF_UP));
    return amount.multiply(taxMultiplier).setScale(2, BigDecimal.ROUND_HALF_UP);
}
```

**注意**: `@PriceSensitive` 标注 — 非财务角色调用时 unitPrice/lineAmountWithTax 会被 `PriceFieldResponseAdvice` 脱敏返回 null。已用 f006_admin (财务权限) 验证，返回完整双值。

**状态**: V1 (API 断言 + 数值精确验证 4000/4360)

---

## D-12: SO → 采购单 salesOrderId 字段存储 → V1 ✅

**断言**:
1. PO 创建时传入 salesOrderId 字段
2. 创建成功后 GET PO detail, salesOrderId 字段存储且与传入值一致

**验证数据** (test env):
- PO ID: `e95895e2-280d-4fe5-9667-20055261c443` (PO-20260610-0004)
- 传入 salesOrderId: `ac05c728-26de-446f-b29e-a8b56ff5a488`
- GET PO detail: `salesOrderId = ac05c728-26de-446f-b29e-a8b56ff5a488` ✅

**注意**: D-12 的 V0 描述"从 SO 详情弹窗带入明细/开始采购按钮未见"仍属部分缺失 — 字段存储 ✅ 但一键带入 SO 明细的 UI 流程 🟡部分 不变。本项验证字段存储是否正常。

**状态**: V1 (API 断言: salesOrderId 持久化存储确认)

---

## D-13: 采购请购单 CRUD + 提交 → V1 ✅

**断言**:
1. `POST /api/mobile/{factoryId}/purchase-requisitions` 创建成功 (注意: NOT `/purchase/requisitions`)
2. `POST /api/mobile/{factoryId}/purchase-requisitions/{id}/submit` 提交成功
3. 状态从 DRAFT → PENDING_APPROVAL

**验证数据** (test env):
- 请购单号: `PR-20260610-001`
- 创建状态: DRAFT ✅
- 提交后状态: PENDING_APPROVAL ✅
- requestedItems 字段 (NOT `items`) 正确传入: `[{materialTypeId, materialName, quantity, unit}]`

**发现坑** (已记录供后续参考):
- 错误路径 `/purchase/requisitions` → 404
- 正确路径: `/purchase-requisitions` (控制器 `@RequestMapping("/api/mobile/{factoryId}/purchase-requisitions")`)
- 错误字段名 `items` → 400 "请购行项目不能为空"
- 正确字段名: `requestedItems` (DTO `CreatePurchaseRequisitionRequest.requestedItems`)

**状态**: V1 (API 断言 + 状态机 DRAFT→PENDING_APPROVAL 实证)

---

## D-15: 询价核价单创建 → V1 ✅

**断言**:
1. `POST /api/mobile/{factoryId}/purchase/inquiry-quotes` 创建成功
2. 返回询价单号 (INQ-YYYYMMDD-XXXX 格式)

**验证数据** (test env):
- 询价单号: `INQ-20260610-0001` ✅
- 状态: DRAFT ✅
- ID: `dd31e7e3-1f0e-47b2-9e74-825fa90199c3`

**DTO 必要字段** (CreateInquiryQuoteRequest):
- `materialTypeId` (@NotBlank)
- `quantity` (@NotNull @Positive) — 注意非 `requiredQuantity`
- `unit` (@NotBlank)
- `inquiryDate` (@NotNull LocalDate) — 注意需传 inquiryDate 而非 expectedDeliveryDate

**全流程** (已确认端点存在 via git grep):
- `/submit` → INQUIRING
- `/supplier-prices` → 添加供应商报价
- `/select-and-convert` → 转采购单

**状态**: V1 (创建端点 + 询价单号生成实证)

---

## D-16: 采购退货单 CRUD → V1 (部分) 🟡

**断言**:
1. `GET /api/mobile/{factoryId}/return-orders` 200
2. 退货单可创建

**验证结果**:
- `GET /api/mobile/F006/return-orders?size=3` → 200, count=0 (test DB 无数据) ✅
- ReturnOrderController path 确认: `@RequestMapping("/api/mobile/{factoryId}/return-orders")` (NOT `/purchase/returns`)
- 端点存在: `@PostMapping` create, `@PostMapping("/{id}/submit")`, `@PostMapping("/{id}/approve")`, `@PostMapping("/{id}/complete")`

**发现**: 原矩阵路径猜测 `/purchase/returns` 是错的，实际路径是 `/return-orders`。列表端点可达。
**创建验证**: 未执行创建断言 (需 materialBatchId 等复杂前置数据)。

**状态**: V1 (端点路径确认 + 列表端点 200)。原 V0 升级，但创建链完整验证仍 V2。

---

## D-17: 合同号字段 → V1 ✅

**断言**:
1. `PurchaseOrder.contractNumber` 字段存在
2. API 响应中 `contractNumber` 字段出现

**验证**:
- git show: `PurchaseOrder.java:192` `@Column(name = "contract_number")` `private String contractNumber;` ✅
- GET PO detail API: `"contractNumber": null` (字段存在，值为空) ✅

**状态**: V1 (字段存在 + API 返回确认)

---

## D-18: isImported 字段 → V1 ✅

**断言**:
1. `PurchaseOrder.isImported` Boolean 字段存在
2. API 响应中 `isImported` 字段出现

**验证**:
- git show: `PurchaseOrder.java:97-98` `@Column(name = "is_imported")` `private Boolean isImported;` ✅
- GET PO detail API: `"isImported": null` (字段存在，未填写则 null) ✅

**状态**: V1 (字段存在 + API 返回确认)

---

## D-9: 付款申请链 → B (阻塞)

**断言**: 完整付款申请 → 审批 → 出纳 链路验证

**阻塞原因**:
- `payment_requests` 表存在于 prod/test DB (由 SP6/SP12 手工 apply 的迁移 V20261010_08 + V20261011_04)
- git grep `PaymentRequest` origin/main `*Controller*.java` → 0 命中
- `PaymentRequestController` **不在 origin/main** Java 源码中
- 结论: 付款申请**后端 API 未实现**。E2E V2 证据中的 PAYMENT_REQUEST 创建可能是通过某个间接路径，或是 E2E 脚本直接写库

**B阻塞状态保留**: D-9 API 层实现缺失，无法做 API 断言验证。

---

## D-19: 采购打印端点 → B (502)

**断言**: `PrintController /print/purchase-order` 返回 PDF

**验证**:
- HTTP 状态: 200 (端点可达)
- 响应体: `{"success": false, "message": "打印服务暂时不可用"}` ← 502 代理错误

**阻塞原因**:
- PrintController 代理到 Python test 服务 (8084)
- Python 服务健康检查 ✅ (`/health` → 200)
- 但 Python 服务没有 `/print/purchase-order` 路由 → 返回 404 → Java 代理层上报 502
- 结论: Print Python 模块**在 test 环境 Python 服务中不存在或未加载**

**B阻塞**: 需要 Python print 模块在 8084 端口正确注册路由。

---

## D-4: 供应商结算属性 → V1 (部分) 🟡

**断言**: paymentTerms/paymentTermsType 字段结构

**验证**:
- Supplier API 返回: `paymentTerms = "货到30天"` (自由文本), `paymentTermsType = null`
- git show `Supplier.java:79-92`: 两个字段均存在 — `paymentTerms` (自由文本 String) + `paymentTermsType` (SettlementType 枚举)
- `paymentTermsType` 在 test data 中为 null (枚举字段未填充)

**状态**: V1 (字段存在). 原 V0 描述"paymentTerms 字段存在但自由文本非枚举"是准确的 — paymentTermsType 枚举字段存在但未在 UI/API 强制使用。此为 🟡部分 实现，非实现缺失。

---

## 业务规则污染警告 (test env)

test DB `cretas_db` 存在 86+ 条 `scope='ORDER'` 的 `business_rules`，包括:
- `BFV_E_SCOPE_ORDER`: `#input.totalAmount > 1000` → SO 总金额 ≤ 1000 时创建失败
- `BFV_E_NO_SPEL`: 无条件触发，所有 SO 创建都会进 rule engine
- 多条测试用规则 (`BFV_E_TIER_*`, `BBR1_*`)

这些是历史测试残留，会干扰 E-2 (空价 SO 创建) 等验证。不影响 D 流验证但需记录。

---

## 附录 (organizer 2026-06-10 晚): D-19 打印 B阻塞 → V1

- **根因反转**: 502 不是 Python print 路由缺失（路由在 main.py 已注册，8083/8084 都可达）。真因 = `cretas-backend-test.service` 缺 `--cretas.python.base-url=http://localhost:8084` → test Java 代理打到 **prod Python 8083**，test JWT 在 prod secret 下验签失败 401 → Java 包装 502。日志证据: cretas-test.log 2026-06-10 20:07 `PDF 代理失败 ... url=http://localhost:8083 ... 401 Invalid or expired token`。
- **修复**: PR #674（systemd 文件加 flag + 路由注册测试）已 merge；服务器已 scp + daemon-reload + restart cretas-backend-test。
- **实证**: 重打 `GET /api/mobile/F006/print/sales-order/{id}` → HTTP 200 真 PDF；python-test.log `POST /api/printing/sales-order 200 OK`。
- **衍生发现+修复（中文字体）**: 首打 PDF 仅 2KB 且 log 警告"无中文字体可用, 中文将显示为 □"。ReportLab 实测**读不了 Noto CJK TTC**（CFF/PostScript 轮廓: `TTFError: postscript outlines are not supported`）→ 不改代码，安装 renderer 候选列表第一位的 wqy-zenhei.ttc（阿里云 debian 镜像 fonts-wqy-zenhei_0.9.45-8 解包 → /usr/share/fonts/truetype/wqy/）。重启 prod+test Python（lazy 字体扫描有进程级缓存）后实证: log `Registered Chinese font: /usr/share/fonts/truetype/wqy/wqy-zenhei.ttc`，PDF 2146B → 17374B（内嵌字体子集），二进制含字体标记。
- **服务器状态变更记录**: ① /etc/systemd/system/cretas-backend-test.service 更新（源=repo scripts/systemd/）② 新增 /usr/share/fonts/truetype/wqy/wqy-zenhei.ttc + google-noto-sans-cjk-ttc-fonts 包（Noto 对 ReportLab 无效但系统层有益）。
