# SP11 · 财务凭证表导出(金蝶/用友表头)+进销存报表 — 设计规格

**子项**: SP11  
**模块所有者**: 后端 + Web Admin  
**优先级**: H（高）  
**波次**: Wave 4（依赖 SP6+SP7+SP12 完成后开始）  
**Flyway 号段**: V20260911_30 ~ V20260911_39  
**生成**: 2026-06-09 Sonnet in-harness（经 Opus organizer 脊梁约束）

---

## 1. 目标

替客户省"手动制表"——把系统里已有的凭证数据、科目余额、库存流水自动导出为金蝶/用友可直接导入的 Excel 格式，消除手工抄录错误。

**核心价值命题**（客户原话 transcript-2b 行710-859）：  
- 金蝶现状只用总账模块、手动录凭证  
- 产品差异化 = 替客户省手动制表  
- 决策 ④ 已拍板：**仅导出表头格式**，不接 API，不自建总账

---

## 2. 范围

### 2.1 本子项做什么

| # | 功能 | 说明 |
|---|---|---|
| F1 | **进销存报表查询** | 按物料/日期筛选，展示期初/入库/出库/盘盈损/期末数量及金额 |
| F2 | **进销存 Excel 导出** | 标准表头，客户可直接用于台账核对 |
| F3 | **凭证序时账导出（金蝶/用友格式）** | 可配置列字段映射，导出为金蝶/用友可识别表头 |
| F4 | **科目余额表导出** | 按会计期间导出科目借贷余额 |
| F5 | **付款属性→会计科目映射配置** | 6 种结算属性各自对应借/贷科目，管理员可配 |
| F6 | **凭证导出配置管理** | per-factory 可配置表头字段名（适配不同版本金蝶/用友） |

### 2.2 本子项不做什么

- ❌ 不接金蝶/用友 API（决策 ④）
- ❌ 不自建完整总账/科目余额表前端（客户用金蝶做这件事）
- ❌ 不做税务申报表、增值税专用发票模块（税务字段 partial，P2 以后）
- ❌ 不新建凭证生成逻辑（8 个 Generator 已存在）
- ❌ 不做 RN 端财务功能（财务是 web-admin 侧操作）

### 2.3 依赖边界

| 依赖子项 | 原因 | 风险若缺失 |
|---|---|---|
| **SP6** | 采购结算属性字段 `settlement_type` 挂在 `PurchaseOrder` 上 | F5 的科目映射无法按结算属性查；SP11 需 SP6 先 merge |
| **SP7** | 库存盘点/报损完整交易流水（Stocktake + WastageReport） | 进销存期初/期末计算缺少盘盈/报损来源；SP11 需 SP7 先 merge |
| **SP12** | 审批流引擎（凭证导出记录审计、导出权限门控） | F6 配置修改若需审批流；SP11 可无 SP12 运行但缺审批 |

---

## 3. 现状复用（已 grep 验证）

### 3.1 完全复用（零新增代码）

| 类/接口 | 包路径 | SP11 用法 |
|---|---|---|
| `Voucher` | `entity/finance/Voucher.java` | 序时账导出数据源 |
| `VoucherEntry` | `entity/finance/VoucherEntry.java` | 借贷分录数据源 |
| `VoucherEntryRepository` | `repository/VoucherEntryRepository.java` | `aggregateBySubject()` 已存在，科目余额表查询直接用 |
| `SubjectAggregateRow` | `dto/finance/SubjectAggregateRow.java` | 科目余额表 DTO，直接用 |
| `Account` | `entity/finance/Account.java` | 科目4层树，导出时补科目名称 |
| `AccountingPeriod` | `entity/finance/AccountingPeriod.java` | 期初/期末时间锚点 |
| `AccountingPeriodServiceImpl` | `service/finance/impl/` | 已有期间查询，直接注入 |
| `VoucherTemplate` | `entity/finance/VoucherTemplate.java` | 科目映射模板基础，扩展用于结算属性映射 |
| `PurchaseOrder` | `entity/inventory/PurchaseOrder.java` | 采购凭证导出关联（SP6 加 settlement_type 后读取） |
| `PurchaseReceiveRecord` | `entity/inventory/PurchaseReceiveRecord.java` | 进销存"入库"来源 |
| `SalesDeliveryRecord` | `entity/inventory/SalesDeliveryRecord.java` | 进销存"出库"来源 |
| `InternalTransfer` / `InternalTransferItem` | `entity/inventory/` | 进销存"调拨"来源 |
| `MaterialBatch` | `entity/MaterialBatch.java` | 移动均价锚（`unit_price` scale-2 存在；原料入库口径） |
| `MaterialBatchAdjustment` | `entity/MaterialBatchAdjustment.java` | 进销存盘盈/损调整来源 |
| `CostRollupUtil` | `service/shared/CostRollupUtil.java` | HALF_UP 精度计算复用 |
| `PriceSensitive` + `PriceSensitiveContext` | `security/` | 导出接口对非财务角色遮蔽成本 |

### 3.2 已有但需补齐（partial）

| 现状 | 缺什么 | SP11 补什么 |
|---|---|---|
| `VoucherEntryRepository.aggregateBySubject()` 已有查询 | 无 HTTP 端点、无 Vue 页面、无导出、无期初余额 | 新建 `VoucherExportService` + Controller 端点 |
| `VoucherTemplate`（JSONB entries + SpEL） | Generator 未连接、无 Vue 编辑器 | 新建 `VoucherExportConfigRepository` 和 `VoucherSubjectMappingRepository` 作为简化配置层，不改模板引擎 |
| `PurchaseOrder` 已有 `vflag`/`PurchaseType`/付款金额字段 | 无 `settlement_type`（SP6 补） | SP11 在 F5 里读 `settlementType`；若 SP6 未 merge 则 F5 配置页灰显 |

### 3.3 完全不存在（SP11 新建）

- `VoucherExportConfig` 实体（金蝶/用友列字段映射配置）
- `VoucherExportRecord` 实体（导出历史审计）
- `InventoryLedgerSnapshot` 实体（期初数量/金额快照）
- `VoucherSubjectMapping` 实体（结算属性→科目映射）
- `VoucherExportService` / `InventoryLedgerService`
- `VoucherExportController` / `InventoryLedgerController`
- web-admin Vue 页面（进销存查询/导出、凭证导出配置）

---

## 4. 数据模型增量

### 4.1 新实体：VoucherExportConfig

**目的**：per-factory 保存金蝶/用友列字段名映射，适配不同版本差异。

```java
// entity/finance/VoucherExportConfig.java
@Entity
@Table(name = "voucher_export_configs")
public class VoucherExportConfig extends BaseEntity {
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_system", nullable = false, length = 32)
    private VoucherTargetSystem targetSystem; // KINGDEE / YONYOU / CUSTOM

    // 列名映射（客户可改成本地金蝶列名）
    @Column(name = "col_voucher_no",  length = 64)  private String colVoucherNo   = "凭证字号";
    @Column(name = "col_date",        length = 64)  private String colDate        = "日期";
    @Column(name = "col_summary",     length = 64)  private String colSummary     = "摘要";
    @Column(name = "col_subject_code",length = 64)  private String colSubjectCode = "科目编码";
    @Column(name = "col_subject_name",length = 64)  private String colSubjectName = "科目名称";
    @Column(name = "col_debit",       length = 64)  private String colDebit       = "借方金额";
    @Column(name = "col_credit",      length = 64)  private String colCredit      = "贷方金额";
    @Column(name = "col_auxiliary",   length = 64)  private String colAuxiliary   = "辅助核算";
    @Column(name = "col_currency",    length = 64)  private String colCurrency    = "币别";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
```

**Flyway**: `V20260911_30__add_voucher_export_config.sql`

```sql
CREATE TABLE voucher_export_configs (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    target_system   VARCHAR(32)  NOT NULL DEFAULT 'KINGDEE',
    col_voucher_no  VARCHAR(64)  NOT NULL DEFAULT '凭证字号',
    col_date        VARCHAR(64)  NOT NULL DEFAULT '日期',
    col_summary     VARCHAR(64)  NOT NULL DEFAULT '摘要',
    col_subject_code VARCHAR(64) NOT NULL DEFAULT '科目编码',
    col_subject_name VARCHAR(64) NOT NULL DEFAULT '科目名称',
    col_debit        VARCHAR(64) NOT NULL DEFAULT '借方金额',
    col_credit       VARCHAR(64) NOT NULL DEFAULT '贷方金额',
    col_auxiliary    VARCHAR(64) NOT NULL DEFAULT '辅助核算',
    col_currency     VARCHAR(64) NOT NULL DEFAULT '币别',
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP,
    UNIQUE (factory_id, target_system)
);
CREATE INDEX idx_vec_factory ON voucher_export_configs(factory_id);
CREATE TRIGGER trg_vec_updated_at BEFORE UPDATE ON voucher_export_configs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
```

### 4.2 新实体：VoucherSubjectMapping

**目的**：采购结算属性（6 种）→ 会计科目借贷映射，替换 `PurchasePaymentVoucherGenerator` 中硬编码的 `借1405/贷2202`。

```java
// entity/finance/VoucherSubjectMapping.java
@Entity
@Table(name = "voucher_subject_mappings")
public class VoucherSubjectMapping extends BaseEntity {
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", nullable = false, length = 32)
    private SettlementType settlementType; // 六类：PREPAY/CREDIT_RECEIVE/PENDING_INVOICE/MONTHLY/CREDIT_TERM/CASH

    @Column(name = "business_type", length = 32)
    private String businessType; // null=通用；"PURCHASE"/"SALES" 区分

    @Column(name = "debit_subject_code", nullable = false, length = 32)
    private String debitSubjectCode;  // e.g. "1405"

    @Column(name = "debit_subject_name", length = 64)
    private String debitSubjectName;  // e.g. "原材料"

    @Column(name = "credit_subject_code", nullable = false, length = 32)
    private String creditSubjectCode; // e.g. "2202"

    @Column(name = "credit_subject_name", length = 64)
    private String creditSubjectName; // e.g. "应付账款"

    @Column(name = "remark", length = 255)
    private String remark;
}
```

**Flyway**: `V20260911_31__add_voucher_subject_mapping.sql`

```sql
CREATE TABLE voucher_subject_mappings (
    id                  VARCHAR(191) PRIMARY KEY,
    factory_id          VARCHAR(191) NOT NULL,
    settlement_type     VARCHAR(32)  NOT NULL,
    business_type       VARCHAR(32),
    debit_subject_code  VARCHAR(32)  NOT NULL,
    debit_subject_name  VARCHAR(64),
    credit_subject_code VARCHAR(32)  NOT NULL,
    credit_subject_name VARCHAR(64),
    remark              VARCHAR(255),
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP,
    UNIQUE (factory_id, settlement_type, business_type)
);
CREATE INDEX idx_vsm_factory ON voucher_subject_mappings(factory_id);
CREATE TRIGGER trg_vsm_updated_at BEFORE UPDATE ON voucher_subject_mappings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- 种子数据：默认科目映射（六种结算属性，factory_id='__default__' 为系统默认模板）
INSERT INTO voucher_subject_mappings (id, factory_id, settlement_type, business_type,
    debit_subject_code, debit_subject_name, credit_subject_code, credit_subject_name)
VALUES
  (gen_random_uuid(), '__default__', 'PREPAY',          'PURCHASE', '1405', '原材料', '1002', '银行存款'),
  (gen_random_uuid(), '__default__', 'CREDIT_RECEIVE',  'PURCHASE', '1405', '原材料', '2202', '应付账款'),
  (gen_random_uuid(), '__default__', 'PENDING_INVOICE', 'PURCHASE', '1405', '原材料', '2241', '暂估应付款'),
  (gen_random_uuid(), '__default__', 'MONTHLY',         'PURCHASE', '1405', '原材料', '2202', '应付账款'),
  (gen_random_uuid(), '__default__', 'CREDIT_TERM',     'PURCHASE', '1405', '原材料', '2202', '应付账款'),
  (gen_random_uuid(), '__default__', 'CASH',            'PURCHASE', '1405', '原材料', '1001', '库存现金');
```

### 4.3 新实体：InventoryLedgerSnapshot

**目的**：在月结时冻结期初数量/金额，支持"按期间查进销存"不受后续变更影响。

```java
// entity/inventory/InventoryLedgerSnapshot.java
@Entity
@Table(name = "inventory_ledger_snapshots")
public class InventoryLedgerSnapshot extends BaseEntity {
    @Column(name = "factory_id",         nullable = false, length = 191) private String factoryId;
    @Column(name = "accounting_period_id",nullable = false, length = 191) private String accountingPeriodId;
    @Column(name = "material_type_id",   nullable = false, length = 191) private String materialTypeId;
    @Column(name = "material_code",      length = 64)                    private String materialCode;
    @Column(name = "material_name",      length = 128)                   private String materialName;
    @Column(name = "unit",               length = 32)                    private String unit;

    // 期末数量（6位精度对齐 CostRollupUtil）
    @Column(name = "closing_qty", precision = 18, scale = 6, nullable = false)
    private BigDecimal closingQty = BigDecimal.ZERO;

    // 期末单价（移动均价，4位精度，HALF_UP）
    @Column(name = "closing_unit_price", precision = 18, scale = 4)
    private BigDecimal closingUnitPrice;

    // 期末金额 = closingQty × closingUnitPrice
    @Column(name = "closing_amount", precision = 18, scale = 2)
    private BigDecimal closingAmount;

    // 期末即下一期期初：snapshot_type = 'PERIOD_CLOSE'
    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_type", nullable = false, length = 32)
    private SnapshotType snapshotType = SnapshotType.PERIOD_CLOSE;
}
```

**Flyway**: `V20260911_32__add_inventory_ledger_snapshot.sql`

```sql
CREATE TABLE inventory_ledger_snapshots (
    id                    VARCHAR(191) PRIMARY KEY,
    factory_id            VARCHAR(191) NOT NULL,
    accounting_period_id  VARCHAR(191) NOT NULL,
    material_type_id      VARCHAR(191) NOT NULL,
    material_code         VARCHAR(64),
    material_name         VARCHAR(128),
    unit                  VARCHAR(32),
    closing_qty           NUMERIC(18,6) NOT NULL DEFAULT 0,
    closing_unit_price    NUMERIC(18,4),
    closing_amount        NUMERIC(18,2),
    snapshot_type         VARCHAR(32)   NOT NULL DEFAULT 'PERIOD_CLOSE',
    created_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP,
    UNIQUE (factory_id, accounting_period_id, material_type_id, snapshot_type)
);
CREATE INDEX idx_ils_factory_period ON inventory_ledger_snapshots(factory_id, accounting_period_id);
CREATE INDEX idx_ils_material ON inventory_ledger_snapshots(material_type_id);
CREATE TRIGGER trg_ils_updated_at BEFORE UPDATE ON inventory_ledger_snapshots
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
```

### 4.4 SettlementType 枚举（新建 or SP6 提供）

若 SP6 已定义 `SettlementType` 则直接 import；否则 SP11 自建：

```java
// entity/enums/SettlementType.java
public enum SettlementType {
    PREPAY,           // 预付款
    CREDIT_RECEIVE,   // 赊销先入库
    PENDING_INVOICE,  // 未到票（暂估）
    MONTHLY,          // 月结
    CREDIT_TERM,      // 账期
    CASH              // 现结
}
```

> ⚠️ 协调 SP6：`settlement_type` 列在 `purchase_orders` 表由 SP6 的 Flyway 迁移加；SP11 只在 `VoucherSubjectMapping` 中引用该枚举值。两个子项 **不得** 在同一 Flyway 文件中修改 `purchase_orders`。

---

## 5. 组件与数据流

### 5.1 进销存报表数据流

```
前端请求: GET /api/mobile/{factoryId}/inventory/ledger
    ?startDate=2026-06-01&endDate=2026-06-30
    ?materialTypeId=xxx (可选)
    ?includeAmount=true (财务角色可见)
                ↓
InventoryLedgerController
                ↓
InventoryLedgerService.getLedger(factoryId, start, end, materialTypeId)
    ├─ 期初：查 InventoryLedgerSnapshot where period 最近 <= startDate
    │         若无快照 → 从 MaterialBatch 和交易流水聚合（兜底）
    ├─ 入库：PurchaseReceiveRecord + PurchaseReceiveItem (receiveDate BETWEEN start..end)
    ├─ 出库（生产领用）：MaterialBatch.consumedQuantity 变化 / 生产报工 consume 事件
    ├─ 出库（销售出货）：SalesDeliveryRecord + SalesDeliveryItem
    ├─ 调拨：InternalTransfer + InternalTransferItem
    ├─ 盘盈/损：MaterialBatchAdjustment（adjustmentType=INVENTORY_GAIN/LOSS）
    │           + SP7 新建的 Stocktake/WastageReport
    └─ 期末：期初 + 入库 - 出库 +/- 调拨 +/- 盘盈损
                ↓
InventoryLedgerDTO (per materialType 一行)
    { materialCode, materialName, unit,
      openingQty, openingAmount,       ← PriceSensitive
      inboundQty, inboundAmount,
      outboundQty, outboundAmount,
      adjustQty, adjustAmount,
      closingQty, closingAmount,
      movingAvgUnitPrice }             ← PriceSensitive
```

**金额精度**: qty scale-6，unitPrice scale-4，amount scale-2，全部 ROUND_HALF_UP（对齐 CostRollupUtil）。

### 5.2 凭证序时账导出数据流

```
前端请求: POST /api/mobile/{factoryId}/finance/voucher-export
    body: { startDate, endDate, targetSystem: "KINGDEE", configId }
                ↓
VoucherExportController
                ↓
VoucherExportService.exportSequentialLedger(factoryId, params)
    ├─ 查 VoucherExportConfig（get column names）
    ├─ 查 Voucher + VoucherEntry（date BETWEEN start..end, factoryId）
    │   JOIN Account（get subject name）
    ├─ 排列 row：voucherNo | date | summary | subjectCode | subjectName
    │            | debitAmount | creditAmount | auxiliary | currency
    └─ Apache POI 生成 .xlsx，列头名用 config 中映射的字段名
                ↓
VoucherExportRecord（写导出日志：操作人、时间范围、行数、file hash）
                ↓
HTTP 响应：Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
           Content-Disposition: attachment; filename="voucher_YYYYMM.xlsx"
```

### 5.3 科目余额表导出数据流

```
GET /api/mobile/{factoryId}/finance/subject-balance/export
    ?periodId=xxx (AccountingPeriod.id)
                ↓
VoucherExportService.exportSubjectBalance(factoryId, periodId)
    ├─ VoucherEntryRepository.aggregateBySubject(factoryId, period.startDate, period.endDate)
    │   → List<SubjectAggregateRow>（已存在）
    ├─ 取期初余额：若 InventoryLedgerSnapshot 有 → 用；否则从上期聚合
    │   （财务科目期初：aggregateBySubject 截至 startDate-1）
    └─ Apache POI .xlsx：科目编码 | 科目名称 | 期初借 | 期初贷 | 发生借 | 发生贷 | 期末借 | 期末贷
```

### 5.4 付款属性→科目映射配置流

```
GET  /api/mobile/{factoryId}/finance/subject-mappings  → List<VoucherSubjectMappingDTO>
POST /api/mobile/{factoryId}/finance/subject-mappings  → 新建（需 FACTORY_ADMIN 角色）
PUT  /api/mobile/{factoryId}/finance/subject-mappings/{id} → 修改
DELETE /api/mobile/{factoryId}/finance/subject-mappings/{id} → 软删除

初始化：若工厂无配置 → 从 __default__ 种子数据 copy per-factory 记录
```

---

## 6. 端归属

| 端 | 组件 | 路由/文件 |
|---|---|---|
| **后端 Java** | `InventoryLedgerController` | `GET /api/mobile/{fid}/inventory/ledger` |
| **后端 Java** | `VoucherExportController` | `POST /api/mobile/{fid}/finance/voucher-export`（序时账） |
| **后端 Java** | `VoucherExportController` | `GET /api/mobile/{fid}/finance/subject-balance/export` |
| **后端 Java** | `VoucherSubjectMappingController` | CRUD `/api/mobile/{fid}/finance/subject-mappings` |
| **后端 Java** | `VoucherExportConfigController` | CRUD `/api/mobile/{fid}/finance/export-config` |
| **Web Admin** | `views/finance/InventoryLedger.vue` | 进销存查询 + 导出按钮 |
| **Web Admin** | `views/finance/VoucherExport.vue` | 凭证序时账 + 科目余额表导出 |
| **Web Admin** | `views/finance/SubjectMapping.vue` | 付款属性→科目映射配置表格 |
| **RN** | 无 | 财务导出为 web-admin 操作，RN 不做 |

---

## 7. API 设计

### 7.1 进销存报表

```
GET /api/mobile/{factoryId}/inventory/ledger
Query: startDate(required), endDate(required), materialTypeId(optional), page(0), size(50)
Auth: FACTORY_SUPER_ADMIN | FACTORY_FINANCE | WAREHOUSE_MANAGER(数量可见，金额 @PriceSensitive 遮蔽)
Response: { success, data: { content: [InventoryLedgerDTO], totalElements } }
```

```
GET /api/mobile/{factoryId}/inventory/ledger/export
Query: startDate, endDate, materialTypeId
Auth: FACTORY_SUPER_ADMIN | FACTORY_FINANCE（仅有金额权限的角色可导含金额版）
Response: .xlsx attachment
```

### 7.2 凭证序时账导出

```
POST /api/mobile/{factoryId}/finance/voucher-export
Body: { startDate, endDate, targetSystem: "KINGDEE"|"YONYOU"|"CUSTOM", configId?: string }
Auth: FACTORY_FINANCE | FACTORY_SUPER_ADMIN
Response: .xlsx attachment
```

### 7.3 科目余额表导出

```
GET /api/mobile/{factoryId}/finance/subject-balance/export?periodId=xxx
Auth: FACTORY_FINANCE | FACTORY_SUPER_ADMIN
Response: .xlsx attachment
```

### 7.4 付款属性→科目映射

```
GET    /api/mobile/{factoryId}/finance/subject-mappings
POST   /api/mobile/{factoryId}/finance/subject-mappings
PUT    /api/mobile/{factoryId}/finance/subject-mappings/{id}
DELETE /api/mobile/{factoryId}/finance/subject-mappings/{id}
Auth: FACTORY_SUPER_ADMIN (修改)；FACTORY_FINANCE（只读）
```

---

## 8. 错误处理（fool-proof 4位一体）

依照 `fool-proof-design.md` 规则：

### 8.1 导出空数据

- **后端**: 若查询结果为空行，返回空 xlsx（含表头），不返回 400  
- **前端**: `ElMessage({ message: '所选时间段内无凭证数据，已生成空报表', type: 'warning', duration: 0, showClose: true })`  
- **next action**: 按钮"查看凭证列表"跳转 `/finance/vouchers`

### 8.2 导出大文件超时（>5000 行）

- **后端**: 若行数 > 5000，改为异步导出，返回 202 + `exportTaskId`  
- **前端**: 轮询 `/finance/export-tasks/{id}/status`，完成后显示下载链接  
- **toast**: `ElMessage({ message: '凭证数据较多，正在后台生成，完成后自动提示', type: 'info', duration: 5000 })`

### 8.3 权限不足（@PriceSensitive）

- 仓管员请求含金额的进销存：金额列自动返回 `null`（Jackson 序列化层已处理）  
- 前端：金额列显示"—"，tooltip "需要财务权限查看金额"

### 8.4 依赖 SP6 未完成（settlement_type 字段不存在）

- `VoucherSubjectMapping` 配置页：若 `purchase_orders` 表无 `settlement_type` 列，结算属性映射配置页展示提示："结算属性功能由 SP6 提供，待采购模块升级后启用"（Rule 5: dead-end 改导航）

### 8.5 凭证配置未初始化（新工厂首次导出）

- 服务层检测：若 `VoucherExportConfig` 无 per-factory 记录 → 自动复制系统默认配置  
- 不报错，静默初始化，**不是 Rule 4 幂等问题**（自动初始化无副作用）

### 8.6 Rule 4 导出幂等

- 同一用户同一时间范围 5 分钟内二次提交导出 → 返回已有 `VoucherExportRecord.downloadUrl`  
- 前端 catch 409 → `ElMessageBox.confirm("已有此时段的导出记录，是否下载？")` + 下载链接

---

## 9. DTO 4位一体（database-entity-sync.md）

每个新实体的 DTO 必须覆盖全 4 处：

| 实体 | DTO 类 | create set | update null-guard | convertToDTO map |
|---|---|---|---|---|
| `VoucherExportConfig` | `VoucherExportConfigDTO` | `VoucherExportConfigService.create()` | `update()` null guard each col field | `VoucherExportConfigMapper.toDTO()` |
| `VoucherSubjectMapping` | `VoucherSubjectMappingDTO` | `create()` | `update()` null guard | `toDTO()` |
| `InventoryLedgerSnapshot` | （查询 DTO，无 create/update API） | `MonthCloseServiceImpl` 月结时写 | N/A | `InventoryLedgerService.buildSnapshotDTO()` |

---

## 10. 测试策略

### 10.1 TDD 原则

先写失败测试 → 再写实现（参见 master-blueprint §5）。

### 10.2 单元测试

| 测试类 | 重点断言 |
|---|---|
| `InventoryLedgerServiceTest` | 期初+入库-出库+盘盈损 = 期末；精度 HALF_UP；无快照时从流水聚合兜底正确 |
| `VoucherExportServiceTest` | 列名从 VoucherExportConfig 取；空数据返回含表头的 xlsx |
| `VoucherSubjectMappingServiceTest` | 6 种结算属性各找到对应借贷科目；工厂无配置 → 返回 `__default__` 种子 |
| `InventoryLedgerSnapshotServiceTest` | 月结触发时快照值与实时聚合值一致 |

### 10.3 集成测试

- 使用 H2 + `@Transactional` 回滚
- 进销存报表：插 3 批次入库 + 1 出库 + 1 调拨 → 断言 closingQty 正确
- 凭证导出：插 2 张凭证 → 导出 xlsx → Apache POI 读取验列名来自 config

### 10.4 验收测试（手工）

1. web-admin 进销存页：时间筛选 + 物料筛选生效；仓管员金额列显示"—"
2. 导出凭证 xlsx：用 Excel 打开，列名与金蝶模板一致
3. 修改导出配置"借方金额"→"借方发生额"→重新导出 → 列名变更生效

---

## 11. 依赖关系图

```
Wave 3:
  SP6 (采购结算属性) ─→ SP11.F5 (结算属性→科目映射)
  SP7 (盘点/报损)   ─→ SP11.F1 (进销存：盘盈/损来源)

Wave 4:
  SP12 (审批流引擎) ─→ SP11.F6 (凭证配置修改审批，可选；SP11 无 SP12 可运行)
  SP10 (研发报价)   ─→ 无直接依赖

SP11 → 无后续 SP 依赖（Wave 4 末端）
```

---

## 12. 🔒 红线设计章（依 master-blueprint §3）

> 执行者只做到 PR + 自测，**不部署 prod**，红线收尾由 Opus 终审 + 从 main 部署。

### 红线 R1：会计口径不可绕过

- 进销存金额必须走 `@PriceSensitive` 过滤；仓管/操作员角色看数量不看金额  
- `InventoryLedgerDTO` 中 `openingAmount / closingAmount / movingAvgUnitPrice` 字段**必须**标 `@PriceSensitive`  
- **验证**: `grep -rn "@PriceSensitive" InventoryLedgerDTO.java` → 必须 ≥ 3 hits

### 红线 R2：导出文件不含敏感字段旁路

- Excel 导出走同一序列化路径，**不** 绕过 `PriceSensitiveContext`  
- 实现方式：`VoucherExportService` 在写 xlsx 前从 `PriceSensitiveContext.isAllowedToView()` 判断角色；不允许时金额列写空字符串  
- **禁止**在 `ScheduledExport` / `AsyncExport` 线程里 skip `PriceSensitiveContext`（线程局部存储需显式传递）

### 红线 R3：Flyway 号段独占 V20260911_30~39

- 不得使用 V20260911_30 以下或 V20260911_40 以上号  
- merge 前必执行：`git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d`  
- 若有重复 → 重编号未 apply 的迁移文件（**不**修改已 apply 的）

### 红线 R4：不修改 SP6/SP7 迁移文件

- `purchase_orders.settlement_type` 由 SP6 的 Flyway 加；SP11 只读该字段，**不向 purchase_orders 表写任何 DDL**  
- Stocktake / WastageReport 表由 SP7 的 Flyway 加；SP11 只读，不改

### 红线 R5：VoucherExportConfig 每工厂唯一活动配置（per target_system）

- `UNIQUE (factory_id, target_system)` 约束已在建表 SQL；service 层不绕过  
- 创建前查重：`findByFactoryIdAndTargetSystem()` → 已存在返回 409（Rule 4 幂等）

---

## ⚠️ 跨子项依赖/风险

1. **SP6 阻塞 F5**：`VoucherSubjectMapping` 的 `settlement_type` 枚举与 SP6 新增的 `purchase_orders.settlement_type` 字段共享定义。若 SP6 延期，F5 配置功能灰显但 F1/F2/F3/F4 不受影响。

2. **SP7 阻塞进销存完整性**：若 SP7 的 Stocktake/WastageReport 未 merge，进销存报表中的盘盈/损行来源缺失，期末余额可能不准（进来出去有，盘点调整没有）。可发布但需在 UI 上标注"不含盘点调整"。

3. **Flyway 跨 session 碰撞高风险**：Wave 4 有 SP10/SP11/SP12 三个子项并行实施，Flyway 号段分配见 master-blueprint §3.6；SP11 独占 V20260911_3x，但 merge 时序交叉仍需执行 dedup 检查命令。

4. **Apache POI 依赖**：`pom.xml` 需确认 `poi-ooxml` 已引入；若未引入，实施者需在 PR 中添加并通知 organizer 检查版本兼容性（存在运行时 ShadedClassLoader 问题）。

5. **SP12 审批流可选**：SP11 本身无需 SP12 即可运行，但若 SP12 上线后需要对"修改凭证导出配置"加审批，需要 SP11 侧加 approval hook。建议 SP11 在 `VoucherExportConfigService.update()` 预留 `@PreAuthorize` 占位，SP12 上线后补 hook。
