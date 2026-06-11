# 六扇门 含税/不含税口径统一设计 Spec

**审计来源**: P1 #31 (含税口径需独立 spec — 金蝶导入正确性依赖统一口径)
**日期**: 2026-06-11
**类型**: Spec (设计文档, 不含实现)
**横跨**: SP3 成本 + SP4 标签 + SP11 凭证/进销存
**前置**: SP4 #708 已落 `TaxRate` enum + `ProductType` 税换算半边

---

## 0. TL;DR (给 organizer 派活的人)

1. **系统内部口径 = 未税 (净价) 存储 + 税率字段**。含税仅在展示/导出层按 `× (1+rate)` 派生。这是**现状的既定约定**(SalesOrder/PurchaseOrder 的 `total_amount`=未税净额, `tax_amount`=税额单列), 本 spec 把它**统一并补齐缺口**, 不推翻。
2. **核心缺口 (金蝶导入会借贷不平/税额错)**: `SalesReceiptVoucherGenerator` / `PurchasePaymentVoucherGenerator` 把 `order.getTotalAmount()` 当单一金额过账, **完全没有把销项税 (2221.01) / 进项税 (2221.02) 单列** → 价税未分离 → 金蝶凭证科目结构错 (收入虚高 or 应收/应付与价税不符)。这是必修项。
3. **3 个并行 impl 任务**: SP11 (凭证价税分离 — **最高优先, 红线 🔒**) ‖ SP3 (成本含税口径标注 + 委外费税) ‖ SP4 (#708 补齐: SO/PO item 税率回填 + DTO 双值)。文件范围互不重叠, 可并行。

---

## 1. 现状摸底 (origin/main, 2026-06-11)

> 主目录 STALE (落后 origin/main 91 commits)。本 spec 全部基于 `origin/main` (worktree `cretas-tax-spec` @ `89576c300`)。

### 1.1 已有的税基础设施 (SP4 #708 落地的)

| 组件 | 现状 | 口径 |
|------|------|------|
| `entity/enums/TaxRate.java` | enum `TAX_9`(0.09)/`TAX_13`(0.13); `preTaxPrice()` / `withTaxPrice()` 换算, **scale=4, HALF_UP** | ✅ 干净, 单一事实源 |
| `ProductType.unitPrice` | 未税单价 (precision 10,2) `@PriceSensitive` | **未税** (内部存储口径) |
| `ProductType.taxIncludedUnitPrice` | 含税单价 (precision 15,4) `@PriceSensitive` | 含税 (展示用) |
| `ProductType.taxRate` | `TaxRate` enum 字段 | — |
| `ProductTypeServiceImpl` (line 191-213, 286-291) | create/update 双向换算: 有 `taxRate`+`taxIncludedUnitPrice` → `unitPrice = preTaxPrice(含税)`; 有 `taxRate`+`unitPrice` 无含税 → 反算 `taxIncludedUnitPrice = withTaxPrice(未税)` | ✅ **内部存未税, 派生含税** — 这是全系统应统一的范式 |
| `Customer.defaultTaxRate` (%) | 客户级默认税率 (precision 5,2) | prefill 链源头 |

### 1.2 订单层 (SalesOrder / PurchaseOrder)

**关键: 订单头已有 价税分离的两列, 约定 = 未税净额 + 税额单列。**

| 字段 | SalesOrder | PurchaseOrder | 口径 |
|------|-----------|--------------|------|
| `total_amount` (scale 2) | ✅ | ✅ | **未税净额 (含义见 `getTotalWithTax`)** |
| `tax_amount` (scale 2) | ✅ | ✅ | 税额单列 |
| `default_tax_rate` (%, scale 2) | ✅ (从 Customer 继承) | ❌ (PO 无单据级 default) | 下放到 item.taxRate |
| `getTotalWithTax()` | `total - discount + tax` | `calculateTotalAmount()` | 含税总额 = 未税 + 税 |

> **结论**: 订单头的既定口径 = `total_amount` 未税, `tax_amount` 单列, 含税 = 二者相加。本 spec 把这个口径作为**全系统统一标准**。

### 1.3 订单行 (SalesOrderItem / PurchaseOrderItem)

| 字段 | SalesOrderItem | PurchaseOrderItem | 口径/问题 |
|------|---------------|------------------|----------|
| `unit_price` (scale 4) `@PriceSensitive` | ✅ | ✅ | **未税单价** (与订单头一致) |
| `tax_rate` (%, scale 2) `@PriceSensitive` | ✅ (无 default 值) | ✅ (default `ZERO`) | 行级税率 |
| `cost_unit_price` (scale 4) | ✅ 注释写 **"含税"** ⚠️ | ❌ | **口径冲突点 — 见 §1.5** |
| `getLineAmount()` | qty×unitPrice (未税, scale 2) | qty×unitPrice (未税) | ✅ 未税 |
| `getLineAmountWithTax()` | `lineAmount×(1+tax%/100)` (含税) | 同 | ✅ 含税派生, scale-6 中间→scale-2 |

> **行级口径**: `unit_price` 未税, `getLineAmountWithTax()` 派生含税。**与订单头、与 ProductType 范式三方一致**。✅

### 1.4 凭证层 (Voucher — 金蝶导入的源头)

**这是必修缺口。**

| 组件 | 现状 | 问题 |
|------|------|------|
| `SalesReceiptVoucherGenerator` | 借 1122 应收账款 (`totalAmount`) / 贷 6001 主营业务收入 (`totalAmount`) | ❌ **税额完全没分离**。两边都用 `totalAmount` (未税净额)。缺 **销项税额 2221.01**。结果: 应收账款 = 未税净额, 但真实应收应是含税总额 (未税+税)。**应收被低估了一个税额, 且销项税科目根本不存在** → 金蝶导入后该笔业务无销项税, 申报错。 |
| `PurchasePaymentVoucherGenerator` | 借 1405 库存商品 (`totalAmount`) / 贷 2202 应付账款 (`totalAmount`) | ❌ 同理缺 **进项税额 2221.02**。库存成本含了本应抵扣的进项税 (虚高), 应付账款被低估。 |
| `Voucher.validateBalanced()` | persist 前强制 `sum(debit)==sum(credit)`, 不平抛 `UnbalancedVoucherException` | ⚠️ **约束**: 加税行必须**两边同时重构**, 否则不平直接抛异常 — 这正是金蝶导入借贷平衡的硬保证, 但也意味着不能只往一边塞税行。 |
| `VoucherEntry` | `debit`/`credit` (scale 2) + `subjectCode`/`subjectName` + `auxiliaryType`/`auxiliaryEntityId` (客户/供应商辅助核算已支持) | ✅ 结构足够承载税额分录, **无需新列** |
| `VoucherExportServiceImpl` | 按 `VoucherEntry` 逐行导出, 列名从 `VoucherExportConfig` 取 (默认金蝶列名), `debit`/`credit` scale2 | ✅ 导出层只要分录正确即正确, 无需改 |

### 1.5 成本层 (FinanceCostBreakdown / SP3 三价对比)

| 字段 | 现状 | 口径问题 |
|------|------|----------|
| `FinanceCostBreakdown.totalAmount` | 注释 "订单总额(销售方收入)" | ⚠️ 未标含税/未税 — 实际取 `SalesOrder.totalAmount` = **未税净额** |
| `actualCost` = Σ `SalesOrderItem.costUnitPrice × qty` | `cost_unit_price` 注释 **"含税"** | ❌ **口径冲突**: 收入用未税, 成本用含税 → 毛利 = 未税收入 − 含税成本, **口径不一致, 毛利偏低 (成本多算了进项税)** |
| `processingFee` (委外加工费) | 恒 null (数据源未接) | 委外费的含税/未税待定 |
| `CostRollupUtil` | `COST_SCALE=4`, `QTY_SCALE=6`, 全 HALF_UP | ✅ 税额计算应对齐此 scale |

> **SP3 必修**: `cost_unit_price` 的 "含税" 注释与全系统 "未税内部存储" 范式冲突。需决策成本口径并标注清楚 (见 §2.3)。

---

## 2. 口径决策 (统一标准)

### 2.1 核心原则: 内部未税存储 + 税率字段 + 派生含税

```
内部存储口径 (DB / entity 字段)     = 未税 (净价)
税率                                = 独立字段 (TaxRate enum 或 % BigDecimal)
含税值                              = 展示/导出层按 net × (1 + rate) 派生, 不落库为"权威"
金蝶凭证                            = 价税分离, 税额单列科目 (销项 2221.01 / 进项 2221.02)
```

**理由**:
1. **已是现状约定** (订单头 `total_amount` 未税 + `tax_amount` 单列; ProductType `unitPrice` 未税 + `taxIncludedUnitPrice` 派生; item `unit_price` 未税)。统一即把成本层 + 凭证层拉齐到这个既定范式, 改动面最小、不推翻。
2. **会计正确性**: 增值税专用发票的入账本就是价税分离 (进项税可抵扣, 不进成本; 销项税是负债不是收入)。未税存储天然对齐金蝶凭证结构。
3. **毛利口径一致**: 收入未税、成本未税 → 毛利口径自洽 (这正是 SP3 现在的 bug 来源)。

### 2.2 各层口径对照表 (统一后)

| 层 | 字段 | 口径 | 含税派生 |
|----|------|------|----------|
| 产品 | `ProductType.unitPrice` | 未税 | `taxIncludedUnitPrice` (= `taxRate.withTaxPrice(unitPrice)`) |
| 销售行 | `SalesOrderItem.unitPrice` | 未税 | `getLineAmountWithTax()` |
| 采购行 | `PurchaseOrderItem.unitPrice` | 未税 | `getLineAmountWithTax()` |
| 订单头 | `SalesOrder/PurchaseOrder.total_amount` | 未税净额 | `getTotalWithTax()` = total − discount + tax |
| 订单头税额 | `*.tax_amount` | 税额 (Σ 行税额) | — |
| **成本** | `SalesOrderItem.cost_unit_price` | **改为未税** (见 §2.3) | 需要含税时派生 |
| **成本视图** | `FinanceCostBreakdown.actualCost` | **未税** (与未税收入一致) | — |
| **凭证** | `VoucherEntry.debit/credit` | **价税分离** (主科目未税, 税额单列) | — |

### 2.3 成本口径决策 (SP3)

`SalesOrderItem.cost_unit_price` 注释当前写 "含税"。两种修法:

- **方案 A (推荐)**: 把 `cost_unit_price` 语义统一为**未税成本** (与未税收入对齐, 毛利口径自洽)。注释改 "未税成本单价"。如果上游写入的是含税值, 在写入点用 `taxRate.preTaxPrice()` 转未税。**向后兼容: 现有数据若无税率, 视为未税 (税率 0), 不变。**
- 方案 B (不推荐): 保持含税, 但在 `FinanceCostBreakdown` 计算毛利时把收入也转含税。→ 引入"含税毛利"概念, 与所有报表/SmartBI 未税口径冲突, 扩散面大。

**决策: 方案 A。** SP3 impl 卡需:
1. `cost_unit_price` 注释 + 语义改未税。
2. `FinanceCostBreakdown` 各 `@PriceSensitive` 成本字段补注释 "未税"。
3. 委外加工费 `processingFee`: 委外发票通常含税, 但入成本应未税 → 数据源接入时 (未来) 按未税存。本 spec 仅标注口径, 不接数据源 (恒 null 现状不变)。

### 2.4 Decimal 口径 (全统一)

| 用途 | scale | rounding | 对齐 |
|------|-------|----------|------|
| 税率换算 (含税↔未税) | 4 | HALF_UP | `TaxRate.preTaxPrice/withTaxPrice` |
| 行税额 / 中间计算 | 6 (中间) → 2 (落库) | HALF_UP | `getLineAmountWithTax` 现状 |
| 成本 (item cost / total) | 4 | HALF_UP | `CostRollupUtil.COST_SCALE` |
| 凭证分录 debit/credit | 2 | HALF_UP | `VoucherEntry` 列 scale 2 + `VoucherExportServiceImpl.scale2` |

**税额计算公式 (统一)**:
```
税额 = 未税净额 × rate            (rate = TaxRate.getRate(), e.g. 0.13)
含税 = 未税净额 + 税额 = 未税 × (1 + rate)
未税 = 含税 / (1 + rate)          (HALF_UP, scale 4)
```
凭证场景: `税额 = totalAmount(未税) × rate`, scale 2 HALF_UP。**为保证借贷平衡, 税额用减法兜底**: `税额 = 含税总额 − 未税净额` (见 §3.1 平衡要点)。

---

## 3. 金蝶导入正确性要点

### 3.1 凭证价税分离 (SP11 核心)

**销售收款凭证 (改后)**:
```
借: 1122 应收账款        = 含税总额 (未税 + 销项税)   [客户辅助核算]
贷: 6001 主营业务收入    = 未税净额
贷: 2221.01 应交税费-应交增值税-销项税额  = 销项税额
```
其中 `销项税额 = 含税总额 − 未税净额` (**用减法保证 借=贷 精确平衡**, 避免 `未税×rate` 与 `含税/(1+rate)` 的舍入裂缝)。

**采购付款凭证 (改后)**:
```
借: 1405 库存商品        = 未税净额
借: 2221.02 应交税费-应交增值税-进项税额  = 进项税额
贷: 2202 应付账款        = 含税总额 (未税 + 进项税)   [供应商辅助核算]
```
其中 `进项税额 = 含税总额 − 未税净额`。

**平衡校验**:
- 销售: 借 1122 (含税) = 贷 6001 (未税) + 贷 2221.01 (税) ✅
- 采购: 借 1405 (未税) + 借 2221.02 (税) = 贷 2202 (含税) ✅
- 两边都满足 `validateBalanced()`。

### 3.2 税额来源 (数据从哪取)

- **优先**: 用订单头已有的 `total_amount` (未税) + `tax_amount` (税额) → 含税 = 二者相加。**直接读, 无需重算**。
- **兜底**: 若 `tax_amount` 为 0/null 但订单行有 `tax_rate` → `税额 = Σ item.getLineAmountWithTax() − Σ item.getLineAmount()`; 或订单头 `税额 = totalAmount × defaultTaxRate%`。
- **零税率/无税率订单**: `tax_amount=0` → 不生成税额分录, 凭证退化为现状两行 (向后兼容)。

### 3.3 科目映射 (VoucherSubjectMapping)

销项税 `2221.01` / 进项税 `2221.02` 需确认在 `VoucherSubjectMapping` / 金蝶科目表里存在。impl 时:
- 若 mapping 表已有 → 直接用。
- 若缺 → SP11 卡需补默认科目映射 (随迁移 seed 或 config)。**科目代码以客户金蝶账套实际为准, 默认值仅 fallback**。

### 3.4 不变量 (impl 必须保持)

1. **借贷恒平**: 任何加税行的改动后 `validateBalanced()` 必过。
2. **税额减法兜底**: 税额 = 含税 − 未税 (不用 未税×rate 独立算, 防舍入裂缝)。
3. **零税向后兼容**: 无税率/零税订单凭证结构不变 (退化两行)。
4. **辅助核算保留**: 应收/应付分录的客户/供应商 `auxiliaryType` 不丢。

---

## 4. 并行 impl 任务拆分 (3 卡)

> 三卡文件范围互不重叠, 可并行。各卡 off `origin/main` 独立 worktree。

### 卡 SP11 — 凭证价税分离 🔒 (最高优先, 红线)

**为什么 🔒**: 涉及财务凭证 + 金蝶导入 + 可能的迁移 (科目映射 seed)。执行者只做到实现+自测+PR, **回 main 由 Opus 终审 + 部署**。

| 项 | 内容 |
|----|------|
| **目标** | `SalesReceiptVoucherGenerator` / `PurchasePaymentVoucherGenerator` 改为价税分离三行 (主科目未税 + 税额单列 + 含税对方科目), 借贷平衡, 零税向后兼容 |
| **scope 锁 (允许改)** | `service/voucher/impl/SalesReceiptVoucherGenerator.java`, `service/voucher/impl/PurchasePaymentVoucherGenerator.java`, `service/voucher/impl/ReturnVoucherGenerator.java` (退货反向, 同理分离), 对应单测; **若科目缺** → `db/flyway/V*__voucher_tax_subject_mapping.sql` (seed 2221.01/2221.02) |
| **禁改** | `ProductType*`, `SalesOrderItem`/`PurchaseOrderItem` (那是 SP4 卡), `FinanceCostBreakdown` (SP3 卡), `VoucherEntry`/`Voucher` 实体结构 (无需新列) |
| **验收** | 单测: ① 13% 销售订单 → 三行借贷平衡 + 销项税额正确 (减法) ② 9% 采购 → 进项税分离 ③ 零税订单 → 退化两行 ④ `validateBalanced()` 全过。金蝶导出 Excel 含税额行。 |
| **Decimal** | 税额 scale 2 HALF_UP, **减法兜底** (含税 − 未税) |
| **依赖** | 无 (读订单头已有 `total_amount`/`tax_amount`)。与 SP3/SP4 并行无冲突。 |

### 卡 SP3 — 成本含税口径标注

| 项 | 内容 |
|----|------|
| **目标** | 成本口径统一为未税 (§2.3 方案 A): `cost_unit_price` 语义改未税 + `FinanceCostBreakdown` 成本字段补未税注释 + 委外费口径标注 |
| **scope 锁 (允许改)** | `entity/inventory/SalesOrderItem.java` (仅 `cost_unit_price` 注释/写入转换, **不动 unit_price/tax_rate**), `dto/inventory/FinanceCostBreakdown.java` (注释), `service/.../SalesServiceImpl.java` 中 `FinanceCostBreakdown` 组装处 (若 cost 写入点需 `preTaxPrice` 转换), 对应单测 |
| **禁改** | voucher 包 (SP11), `ProductType` 税字段 (SP4), `TaxRate` enum |
| **验收** | 单测: 含税成本写入 → 存未税; 毛利 = 未税收入 − 未税成本 口径一致; 无税率数据视未税不变 (向后兼容)。 |
| **Decimal** | `CostRollupUtil.COST_SCALE=4` HALF_UP; 含税→未税用 `TaxRate.preTaxPrice` |
| **⚠️ 协调点** | 若 SP3 要在 `SalesOrderItem` 加 `cost_unit_price` 转换逻辑, 而 SP4 也碰 `SalesOrderItem` (加 item 税率回填) → **按 §4 末"协调"切分**: SP3 只碰 `cost_unit_price` 相关行, SP4 只碰 `tax_rate` 回填相关行。不同方法/字段, 物理不撞。 |

### 卡 SP4 — #708 补齐 (item 税率回填 + DTO 双值)

| 项 | 内容 |
|----|------|
| **目标** | 补齐 #708 半边: ① SO/PO item 创建时 `tax_rate` 从 `SalesOrder.defaultTaxRate` / `Customer.defaultTaxRate` 继承回填 (现状 `SalesOrderItem.tax_rate` 无 default) ② 订单头 `tax_amount` 由 Σ 行税额自动汇总 ③ API/DTO 同时返回未税 (`lineAmount`) + 含税 (`lineAmountWithTax`) 双值 (PO 已有, 核对 SO DTO 暴露) |
| **scope 锁 (允许改)** | `service/.../SalesServiceImpl.java` (item 税率继承 + tax_amount 汇总, **仅 tax_rate/tax_amount 相关行**), `dto/inventory/CreateSalesOrderRequest.java` / `CreatePurchaseOrderRequest.java` (若需透传税率), SO/PO 的 DTO 响应组装 (双值暴露), 对应单测 |
| **禁改** | voucher 包 (SP11), `cost_unit_price` 相关 (SP3), `TaxRate` enum (已稳定) |
| **验收** | 单测: ① 创建 SO 无显式 item 税率 → 继承 SO default → 继承 Customer default ② `tax_amount` = Σ 行税额 ③ DTO 响应含未税+含税双值。 |
| **Decimal** | 行税额 scale-6 中间 → scale-2 (沿用 `getLineAmountWithTax`); `tax_amount` scale 2 |
| **依赖** | 无硬依赖。SP11 读 `tax_amount`, 若 SP4 先 merge 则 SP11 数据更准, 但 SP11 有 §3.2 兜底, 可并行。 |

### 三卡协调要点

- **唯一物理重叠风险**: SP3 + SP4 都可能碰 `SalesOrderItem.java` 和 `SalesServiceImpl.java`。
  - `SalesOrderItem.java`: SP3 改 `cost_unit_price` (注释/转换), SP4 不碰该字段 (只碰 `tax_rate` 继承在 service 层) → **实际不撞同一行**。建议 SP4 的税率回填全放 `SalesServiceImpl`, 不碰实体 → 完全无重叠。
  - `SalesServiceImpl.java`: SP3 碰 `FinanceCostBreakdown` 组装方法; SP4 碰 SO 创建 + tax_amount 汇总方法 → **不同方法, organizer 用 `git commit -- <file>` 锁 scope + 按方法切分**。如担心, 串行 SP4 → SP3 (SP4 先, 因 tax_amount 是 SP11 兜底数据源)。
- **SP11 完全独立** (voucher 包), 与 SP3/SP4 零重叠, 必并行。

---

## 5. 向后兼容

| 场景 | 处理 |
|------|------|
| 现有 SO/PO 无 `tax_rate` 数据 | 视为 **0 税率** (未税=含税)。凭证退化两行, 成本不转换, 毛利不变。**不批量回填** (无依据)。 |
| 现有 `cost_unit_price` 标注"含税"的历史数据 | 无税率 → 当未税用 (税率 0 时含税=未税, 数值不变)。有税率的极少历史数据如需精确, 单独数据修复脚本 (非本 spec 范围)。 |
| Customer 无 `defaultTaxRate` | item 税率 null → 凭证零税分支 → 退化两行。 |
| 金蝶科目表无 2221.01/2221.02 | SP11 seed 默认映射; 客户实际账套科目以配置覆盖。 |
| 零税率订单 (免税业务) | `tax_amount=0` → 凭证两行, 与现状字节一致。 |

**默认税率策略**: 不强制全工厂统一税率。空税率 = 0 税 (安全默认)。六扇门实际按客户/产品配 (Customer.defaultTaxRate → SO.defaultTaxRate → Item.taxRate 继承链已存在)。

---

## 6. 不在本 spec 范围 (defer)

- 委外加工费数据源接入 (WorkProcess 加 `is_outsourced`/`outsourced_fee` 列) — 仅标口径, 不接数据。
- 进项税抵扣台账 / 增值税申报报表 — 凭证分离是前提, 申报是后续独立功能。
- 历史含税成本数据精确回填脚本 — 按需单独做。
- InventoryLedger 金额含税否 — **现状库存按未税成本计 (与 1405 库存商品未税一致), 已对齐**, 无需改; 仅在 SP3 注释里确认即可。

---

## 7. 验收总览 (organizer 终审 checklist)

- [ ] SP11: 销售/采购/退货凭证价税分离三行, 借贷恒平 (`validateBalanced()` 全过)
- [ ] SP11: 税额用减法兜底 (含税−未税), 无舍入裂缝
- [ ] SP11: 零税订单退化两行 (向后兼容字节一致)
- [ ] SP11: 销项 2221.01 / 进项 2221.02 科目映射存在
- [ ] SP3: `cost_unit_price` 语义未税 + 毛利口径自洽 (未税收入 − 未税成本)
- [ ] SP4: item 税率继承链回填 + `tax_amount` 汇总 + DTO 未税/含税双值
- [ ] 全: Decimal scale 对齐 (换算 4 / 凭证 2 / 成本 4, 全 HALF_UP)
- [ ] 全: 客户辅助核算不丢; 金蝶导出 Excel 含税额行
