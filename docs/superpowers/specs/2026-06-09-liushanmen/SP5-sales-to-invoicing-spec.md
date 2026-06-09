# SP5 · 销售到开票 — 设计 Spec

**子项**: SP5 · 销售到开票 (含未税税率 + 毛利红线 + 开票传票)
**Flyway 号段**: V20260910_40 ~ V20260910_49
**依赖**: SP3 (标准成本引擎) → SP4 (税率字段) → SP5
**Wave**: Wave 3 (与 SP8 ‖ SP9 并行)
**文档版本**: 2026-06-09 · Opus organizer 执笔

> 🔒 本 spec 含红线设计章 (§8)。红线实现由 Opus 终审，执行者只到 PR。

---

## 1. 目标

| 目标 | 说明 |
|---|---|
| 激活毛利红线 | SP3 落地 `ProductType.standardCost` 后，激活已有 `PricingEngine.checkWarnings()` 的毛利红线预警 (现被 `costEstimate(null)` 静默禁用) |
| 含/未税双显 | 订单行 含税 / 未税 金额双列展示；tax_rate 字段已在 `SalesOrderItem` |
| 提成联动 | 订单确认 → 按毛利率区间查 `CommissionRule.tierConfig` 算提成预览 |
| 传票自动化 | 财务审批通过 → 自动触发 `SalesReceiptVoucherGenerator` (现仅 Confirmed 触发；Finance Approve 未接) |
| 多单合并供单 | 销售订单追加 `sourceOrderIds` (N:1 关联生产计划)；**P1，本 SP 只建字段，UI 下一版** |

---

## 2. 范围

### 本 SP 做

- `ProductType.standardCost` + `ProductType.targetGrossMargin` 字段 (SP3 出，SP5 消费)
- `SalesServiceImpl` 激活 `costEstimate`：从 `productType.standardCost` 取值，传入 `PricingEngine`
- 前端 `sales/orders/` 展示含税/未税双列
- 下单实时调 `/api/{fid}/sales/check-margin` → 返回 `{belowRedline, warningMessage}` (后端计算，前端只展示 bool+文案)
- `SalesOrderFinanceApprovedEvent` 监听器 → 触发 `voucherService.createFromBusiness(SALES_ORDER)`
- `CommissionRuleService.previewCommission(soId)` — 订单确认时算提成预览，写 `SalesOrder.commissionPreview`
- `ProductionPlan.sourceOrderIds` (JSONB list) 字段 + Flyway，单个 API 接受 list (UI 占位)

### 本 SP 不做 (Backlog / 其它 SP)

| 不做项 | 归属 |
|---|---|
| SP3 标准成本计算引擎本身 | SP3 (SP5 依赖其交付) |
| SP4 税率配置 UI + CustomerTaxRate | SP4 |
| 盐化 (盐费价格逻辑) | P2 defer |
| 多单合并 UI (加号追加多 SO) | SP5 P1 follow-up |
| 金蝶凭证格式导出 | SP9/流程 H |
| 提成详细历史报表 | SP12 |

---

## 3. 现状复用 (grep 验证)

| 现有代码 | 状态 | SP5 动作 |
|---|---|---|
| `SalesOrder.java` — vflag, defaultTaxRate, defaultInvoiceType, paidAmount, invoiceStatus | ✅ EXISTS | 直接复用；新增 commissionPreview 字段 |
| `SalesOrderItem.java` — taxRate, unitPrice, costUnitPrice, getLineAmount() | ✅ EXISTS | 添加 `getLineAmountExTax()` 派生 getter |
| `PriceFieldResponseAdvice.java` — @PriceSensitive 全链路脱敏 | ✅ EXISTS | 新字段遵循同样约定 |
| `PricingEngineImpl.computeWarnings()` — costEstimate null 短路 | 🟡 PARTIAL | 激活：传 `productType.standardCost` |
| `SalesReceiptVoucherGenerator` — 借1122/贷6001 | ✅ EXISTS | 补监听器：接 FinanceApprovedEvent |
| `SalesOrderVoucherListener` — 监听 SalesOrderConfirmedEvent | ✅ EXISTS | 复制模式，新建 `SalesFinanceApproveVoucherListener` |
| `CommissionRule.tierConfig` — JSONB [{minAmount,maxAmount,rate}] | ✅ EXISTS | 新增 `CommissionRuleService.previewByGrossMargin()` 方法 |
| `SalesOrderFinanceApprovedEvent` — 已发布 (SalesServiceImpl line 643) | ✅ EXISTS | 只需 @TransactionalEventListener 监听 |
| `ProductionPlan.sourceOrderId` — 单 String | 🔴 MISSING (单值) | 新增 `sourceOrderIds` JSONB list；旧字段保留向后兼容 |
| `ProductType` — 无 standardCost / targetGrossMargin | 🔴 MISSING | SP3 负责添加；SP5 依赖其 PR 合并 |

---

## 4. 数据模型增量

### 4.1 ProductType (SP3 出，SP5 消费)

SP3 负责如下 migration (SP5 仅依赖，不写这个 migration)：

```sql
-- SP3 的 migration (V20260910_3x)
ALTER TABLE product_types
  ADD COLUMN standard_cost NUMERIC(15,4),              -- @PriceSensitive
  ADD COLUMN target_gross_margin NUMERIC(5,4);         -- e.g. 0.10 = 10%
```

SP5 `SalesServiceImpl` 激活时直接 `productType.getStandardCost()`。

### 4.2 SalesOrder — commissionPreview (V20260910_40)

```sql
-- V20260910_40__sales_order_commission_preview.sql
ALTER TABLE sales_orders
  ADD COLUMN commission_preview NUMERIC(15,2),         -- 预估提成金额（只读展示）
  ADD COLUMN commission_rate_pct NUMERIC(5,2);         -- 命中的提成率 %（只读展示）
```

实体新增字段 (均 `@PriceSensitive`)：
```java
@PriceSensitive
@Column(name = "commission_preview", precision = 15, scale = 2)
private BigDecimal commissionPreview;

@PriceSensitive
@Column(name = "commission_rate_pct", precision = 5, scale = 2)
private BigDecimal commissionRatePct;
```

### 4.3 ProductionPlan — sourceOrderIds (V20260910_41)

```sql
-- V20260910_41__production_plan_source_order_ids.sql
ALTER TABLE production_plans
  ADD COLUMN source_order_ids JSONB;  -- ["SO-001","SO-002",...] 多单合并用
```

实体新增字段：
```java
@Type(JsonBinaryType.class)
@Column(name = "source_order_ids", columnDefinition = "jsonb")
private List<String> sourceOrderIds;
```

旧 `sourceOrderId` (String) 保留不删，向后兼容。创建计划时若传 list，写入 `sourceOrderIds`；若只传单 id，同时写 `sourceOrderId`（兜底字段不改）。

### 4.4 FactoryGrossMarginConfig — 工厂级红线配置 (V20260910_42)

```sql
-- V20260910_42__factory_gross_margin_config.sql
CREATE TABLE factory_gross_margin_configs (
  id              VARCHAR(36)   PRIMARY KEY,
  factory_id      VARCHAR(191)  NOT NULL,
  product_type_id VARCHAR(191),              -- NULL = 工厂全局兜底
  target_gross_margin NUMERIC(5,4) NOT NULL, -- e.g. 0.10 = 10%
  effective_from  DATE          NOT NULL,
  effective_to    DATE,                      -- NULL = 永久
  created_at      TIMESTAMP     DEFAULT NOW(),
  updated_at      TIMESTAMP     DEFAULT NOW(),
  deleted_at      TIMESTAMP,
  CONSTRAINT uq_fgmc_factory_product UNIQUE (factory_id, product_type_id, effective_from)
);
CREATE INDEX idx_fgmc_factory ON factory_gross_margin_configs(factory_id);
```

用途：`GrossMarginRedlineService.resolveTargetMargin(factoryId, productTypeId)` 查询，product 级优先，null product 为全局兜底。

> 若 SP3 已在 `ProductType.targetGrossMargin` 存放单品级配置，则本表作工厂级兜底，优先级：ProductType.targetGrossMargin > FactoryGrossMarginConfig (product=null)。

---

## 5. 新增服务 / 方法

### 5.1 GrossMarginRedlineService (新建，🔒 红线核心)

```java
// 包: com.cretas.aims.service.pricing
public interface GrossMarginRedlineService {
    /**
     * 校验订单行是否低于毛利红线。
     * 后端唯一计算点；前端只收 belowRedline + warningMessage。
     * @return {belowRedline: bool, warningMessage: String, minPrice: BigDecimal}
     */
    GrossMarginCheckResult checkLine(String factoryId, String productTypeId,
                                      BigDecimal unitPrice, BigDecimal quantity);

    /** 解析目标毛利率: ProductType.targetGrossMargin > FactoryGrossMarginConfig > 默认10% */
    BigDecimal resolveTargetMargin(String factoryId, String productTypeId);
}
```

实现逻辑：
```
minPrice = standardCost × (1 + targetGrossMargin)
belowRedline = unitPrice < minPrice
warningMessage = "当前单价 ¥X 低于毛利红线 ¥Y (成本 ¥Z × (1 + 10%)), 请确认" (文案由后端生成)
```

注意：`standardCost` 和 `minPrice` 的数值**不发往前端**，只发 `belowRedline` bool + `warningMessage` 文案。

### 5.2 CommissionRuleService.previewByGrossMargin (扩展现有服务)

```java
/** 根据订单毛利金额和业务员 ID，查 CommissionRule.tierConfig，返回预估提成 */
CommissionPreviewDTO previewByGrossMargin(String factoryId, Long salespersonId,
                                           BigDecimal grossProfitAmount, LocalDate orderDate);
```

`tierConfig` 已是 `[{minAmount, maxAmount, rate}]` 结构，直接 range-match `grossProfitAmount`，不需新实体。

### 5.3 SalesFinanceApproveVoucherListener (新建，🔒 传票)

```java
// 包: com.cretas.aims.listener.voucher
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
public void onSalesOrderFinanceApproved(SalesOrderFinanceApprovedEvent event) {
    // 复用 SalesOrderVoucherListener 逻辑，监听 FinanceApprovedEvent
    // voucherService.createFromBusiness(factoryId, "SALES_ORDER", salesOrderId)
}
```

注意：现有 `SalesOrderVoucherListener` 只监听 `SalesOrderConfirmedEvent`，导致财务审批后凭证未生成。本监听器补填此空缺。幂等：检查 `so.getVflag() != UNCREATED` 则跳过（同现有模式）。

---

## 6. API 端点增量

| 端点 | Method | 说明 | 端 |
|---|---|---|---|
| `/{fid}/sales/check-margin` | POST | 实时毛利红线检查；body: `{productTypeId, unitPrice, quantity}` → 返回 `{belowRedline, warningMessage}` | BE |
| `/{fid}/sales/orders/{id}/commission-preview` | GET | 查订单预估提成 | BE |
| `/{fid}/production-plans/{id}/source-orders` | PUT | 更新 sourceOrderIds (多单合并) | BE |

现有端点不变；`financeApproveOrder` 端点已存在，只需补 listener。

---

## 7. 前端增量 (web-admin · sales/*.vue)

### 7.1 sales/orders/list.vue (修改)

- 列表新增「毛利预警」列：`belowRedline` badge（红色 chip，仅财务/超管可见，对销售角色隐藏）
- 提成预览列（仅超管/财务）

### 7.2 sales/orders/detail.vue (修改)

- 订单行表格新增含税单价 / 未税单价 双列：
  - 含税单价：`item.unitPrice`（已有）
  - 未税单价：`item.unitPrice / (1 + item.taxRate/100)`（派生，`getLineAmountExTax()` 后端也提供）
- 创建/编辑订单行时，单价输入框下方显示毛利红线实时预警（调 `check-margin` 接口，非阻塞 warning，sticky duration:0 showClose）
- 预警文案来自后端 `warningMessage`，前端不自行计算，不展示任何成本数值

### 7.3 sales/finance-review/detail.vue (修改)

- 财务审批页展示「提成预览」字段（来自 `commissionPreview` / `commissionRatePct`）

### 7.4 sales/orders/components/TaxGroupInvoiceDialog.vue (检查)

- 已有税率分组开票逻辑，本 SP 复核 9%/13% 两档均有 `taxRate` 来源，无需大改

---

## 8. 🔒 红线设计章 (照蓝图 §3 逐字落地)

> **执行者只到 PR，Opus 终审此章所有代码**

### 8.1 红线公式 (蓝图 §3.3 原文照搬)

```
minPrice = standardCostPrice × (1 + targetGrossMargin)
```

- `standardCostPrice` 来源: `ProductType.standardCost`（SP3 出，SP5 消费）
- `targetGrossMargin` 来源 (优先级): `ProductType.targetGrossMargin` > `FactoryGrossMarginConfig`(product=null) > 硬编码兜底 0.10 (10%)
- `unitPrice < minPrice` → `belowRedline = true`

### 8.2 脱敏规则 (🔒 不可绕过)

| 字段 | 发往前端? | 规则 |
|---|---|---|
| `standardCost` | ❌ 绝不发送 | 加 `@PriceSensitive`，`PriceFieldResponseAdvice` 自动剥离 |
| `targetGrossMargin` | ❌ 绝不发送 | 加 `@PriceSensitive`，同上 |
| `minPrice` (计算值) | ❌ 绝不发送 | 仅在 `GrossMarginRedlineService` 内部计算，不序列化到响应 |
| `belowRedline` (bool) | ✅ 发送 | 只有 true/false，不泄露数值 |
| `warningMessage` (文案) | ✅ 发送 | 文案：`"单价低于毛利红线，请向上级确认"` (不含任何数字) |

**实现约束**：
- `GrossMarginCheckResult` DTO 只包含 `{belowRedline: Boolean, warningMessage: String}`，不含 `minPrice/standardCost/costMultiplier` 等任何数字字段
- `check-margin` 端点加 `@RequireRole({"factory_super_admin","sales_manager","finance_manager"})` 门控（操作员/仓管不可调）
- 单元测试：验证 `GrossMarginCheckResult` 序列化后不含 `standardCost` / `minPrice` 字段

### 8.3 前端展示规则 (fool-proof 4 位一体)

1. **预警展示**：`ElMessage({ type: 'warning', message: warningMessage, duration: 0, showClose: true })` — sticky
2. **不阻断提交**：预警为 warning 级别，`submitButton` 不 disable
3. **上下文**：dialog 标题含品名 + 订单号 (fool-proof Rule 2)
4. **next action 提示**：`warningMessage` 末尾加 `"如需继续，请向销售经理确认"` (fool-proof Rule 4位一体 d)

### 8.4 提成联动

毛利金额 = `SalesOrder.totalAmount - SalesOrder.estimatedCost`（财审时 estimatedCost 已有，来自财审录入）

当 `estimatedCost` 缺失时（草稿/待审期），`commissionPreview` 保持 null，前端显示「待财务确认后显示」。

订单确认事件 (`SalesOrderConfirmedEvent`) 触发 `CommissionRuleService.previewByGrossMargin()` → 写回 `SalesOrder.commissionPreview`。

---

## 9. 错误处理 (fool-proof 4 位一体)

所有写操作 (含税率修改、传票生成) 遵循：

| # | 规则 | 实施 |
|---|---|---|
| a | 网络 response.message 精确 | `"订单 SO-XXX 财务审批时凭证生成失败: {具体原因}"` |
| b | UI toast = 后端 message | 前端 `e.response.data.message` 原样展示 |
| c | toast sticky | `duration: 0, showClose: true` (错误) |
| d | next action 提示 | `"请通知财务手动生成凭证或重试"` |

传票生成失败：`vflag` 置 `FAILED`，不回滚 financeApprove 主事务（`@Async + AFTER_COMMIT` 保证不互相污染）。

---

## 10. 测试策略

| 层 | 测试 | 说明 |
|---|---|---|
| 单元 | `GrossMarginRedlineServiceTest` | 校验公式：cost=8,margin=10% → minPrice=8.8；belowRedline(8.5)=true，belowRedline(9.0)=false |
| 单元 | `GrossMarginCheckResultSerializationTest` | 序列化后 JSON 不含 standardCost/minPrice 字段 |
| 单元 | `CommissionRuleServicePreviewTest` | tierConfig range-match 逻辑 |
| 单元 | `SalesFinanceApproveVoucherListenerTest` | mock voucherService；vflag UNCREATED → CREATED；幂等跳过 |
| 集成 | `SalesServiceImplTest.financeApprove_triggersVoucher` | 发布 FinanceApprovedEvent → listener 跑 → vflag=CREATED |
| 集成 | `SalesServiceImplTest.createOrder_belowRedline_warns` | SP3 就绪后：standardCost 设 8.0，price=7.5，warnings 非空 |
| E2E (headed) | 下单填低价格，预警 sticky 出现 + 不阻断提交 | Playwright web-admin headed |

---

## 11. 依赖

| 依赖 | 说明 |
|---|---|
| **SP3** (🔒 强依赖) | 提供 `ProductType.standardCost` + `ProductType.targetGrossMargin` 字段。SP5 的红线激活完全依赖 SP3 PR 合并后才能生效；SP5 可先写代码 (null guard)，但 E2E 验证需 SP3 完成。 |
| **SP4** (弱依赖) | `SalesOrderItem.taxRate` 字段由 SP4 规范化；该字段已存在，SP5 直接读用。SP4 若改字段语义需通知 SP5。 |

---

## 12. ⚠️ 跨子项依赖 / 风险

1. **SP3 未交付则红线激活形同虚设** — `SalesServiceImpl` 的 `costEstimate(null)` 那行是 SP5 的激活点，但激活需要读 `ProductType.standardCost`，此字段由 SP3 Migration 添加。SP5 代码可先写但 `costEstimate` 传 null 直到 SP3 merge。务必测试"SP3 null guard 路径"保持无异常。

2. **SalesOrderVoucherListener vs SalesFinanceApproveVoucherListener 幂等冲突** — `SalesOrderVoucherListener` 在 Confirmed 时已检查 `vflag == UNCREATED`，SP5 新增的 FinanceApprove 监听器同样检查此条件，形成幂等。但若两个事件先后极短时间触发（罕见），存在 TOCTOU race。处理：`salesOrderRepo.save(so)` 有 `@Version` 乐观锁，第二个写入会 409，可 catch 并 log 跳过。

3. **ProductionPlan.sourceOrderIds 与 SP7 生产排程冲突** — SP7 读 `ProductionPlan.sourceOrderId`（单值）用于排程关联。SP5 新增 `sourceOrderIds` 不删旧字段，但 SP7 需确认是否需要同时读 `sourceOrderIds` 列表。建议 SP7 builder 在 SP5 合并后加兼容读（`sourceOrderIds != null ? first : sourceOrderId`）。

4. **PriceFieldResponseAdvice 新字段需同步** — `commissionPreview` / `commissionRatePct` 加了 `@PriceSensitive`，会被 `PriceFieldResponseAdvice` 自动脱敏。需在实体字段上正确标注（别漏）；另外 `FactoryGrossMarginConfig.targetGrossMargin` 不通过 Sales API 下发，无需额外处理，但如果有管理端口暴露该表，必须加权限门控。

5. **SalesOrderFinanceApprovedEvent 已被 SupplyChainOrchestrator 消费** — 该 event 已有 `SupplyChainOrchestrator.onSalesOrderFinanceApproved` 监听。SP5 增加第二个监听器（传票），两者均为 `@Async`，Spring 会分别调用，无干扰。确认 `@EnableAsync` 已在主 config 开启（SalesOrderVoucherListener 已用过 `@Async`，可确认已开）。

---

## 附录：Flyway 号段使用计划

| 号 | 文件 | 内容 |
|---|---|---|
| V20260910_40 | `__sales_order_commission_preview.sql` | SalesOrder 加 commission_preview + commission_rate_pct |
| V20260910_41 | `__production_plan_source_order_ids.sql` | ProductionPlan 加 source_order_ids JSONB |
| V20260910_42 | `__factory_gross_margin_config.sql` | 新建 factory_gross_margin_configs 表 |
| V20260910_43 | 预留 | 后续扩展字段 |

> **Flyway 查重纪律**：每次 PR 前运行：
> ```bash
> git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
> ```
> 若有重复号立即重编，**绝不** 沿用冲突号。
