# SP4 · 一物一码补缺 — 设计规范 (Design Spec)

**子项**: SP4 · 一物一码补缺 (厂号/产地 + 批次条码 + 税率/未税换算 + BOM按份数/组合装)
**蓝图波次**: Wave 1 (与 SP1 并行, 无依赖)
**Flyway 号段**: `V20260910_30 ~ V20260910_39`
**Scope-lock**: `MaterialBatch`, `RawMaterialType`, `BomRecipeItem` (SP4独占; ProductType 加 tax_rate enum 共享写权)
**生成**: 2026-06-09 Sonnet in-harness architect

---

## 1. 目标

六扇门食品工厂 P0 编码体系补缺。现状四个硬缺口：

1. **A4 · 厂号/产地** — 原料领用时仓管只知"猪肉"不知哪个厂的，用错原料风险高。`MaterialBatch` 无 `factory_number`/`origin_place` 字段。
2. **A5 · 批次条码标签** — 已有 `Label` 实体但无面向原料防错的扫码查询端点；仓管扫码拿不到厂号/重量关键防错信息。
3. **A8 · 税率枚举 + 未税价换算** — `ProductType`/`RawMaterialType` 无 9%/13% 枚举字段；成本引擎要用未税价但现在只存含税价，换算在业务层缺失。
4. **B5/B6 · BOM 辅料按份/组合装** — 辅料（调料）应按成品份数固定拉料，BOM 无 `per_portion` 标志；组合装(A半成品+B半成品)BOM 无半成品引用列。
5. **A3 补充 · 编码前缀对齐** — 现有字母前缀(YL/RL/BC/WL)与客户认知的数字前缀(001/002/003)不对齐，小补不重建。

**不做的事**（范围外）：
- 16位分段编码体系 → P1/SP8
- 供应商匹配厂号校验（客户明确不需要）
- 超支报警逻辑 → SP5
- 半成品双产出 → SP1
- 财务对接 → P2 后

---

## 2. 范围

### 2.1 本子项做什么

| 编号 | 功能 | 层 | 工作量 |
|------|------|----|--------|
| SP4-A4 | MaterialBatch 增 factory_number + origin_place | Backend+Web+RN | M |
| SP4-A5 | 批次条码标签生成 + 扫码端点 | Backend+RN | S |
| SP4-A8 | ProductType+RawMaterialType 增 tax_rate enum (9/13); 未税←→含税自动换算 | Backend+Web | M |
| SP4-B5 | BomRecipeItem 增 per_portion boolean; 份数联动计算 | Backend+Web | S |
| SP4-B6 | BomRecipeItem 增 semi_finished_ref_code; 组合装 BOM | Backend+Web | S |
| SP4-A3 | 编码前缀数字对齐 (001/002/003 并存字母) | Backend | XS |

### 2.2 不做什么

- 税率影响的财务凭证导出 (SP财务模块)
- 条码打印硬件集成 (客户用现有条码打印机自行打印PDF)
- BOM 成本引擎调整 (SP5 用未税价重算成本)
- 超市场景条码格式 (暂不做 EAN-13)

---

## 3. 现状复用 (grep 验证)

| 可复用 | 文件 | 复用方式 |
|--------|------|---------|
| `Label` 实体 + `batchType=MATERIAL` | `entity/Label.java` | 直接复用；Label 已支持 MATERIAL batch type |
| `LabelController` | `controller/LabelController.java` | 扩展扫码查询端点 |
| `generateNextCode()` | `service/impl/RawMaterialTypeServiceImpl.java` | 小改增数字前缀分支 |
| `getMaterialCategoryPrefix()` | 同上 | 增 `"001"/"002"/"003"` 映射 |
| `MaterialBatch.customFields` (jsonb) | `entity/MaterialBatch.java` | **不复用 jsonb** — 需显式列才能索引/过滤 |
| `BomRecipeItem.taxRate` (BigDecimal) | `entity/bom/BomRecipeItem.java` | 已存在但无枚举约束，仅加校验 |
| `@PriceSensitive` 注解 | `security/PriceSensitive.java` | 沿用；新 `taxIncludedUnitPrice` 字段必标注 |
| `CreateMaterialBatchRequest` | `dto/material/CreateMaterialBatchRequest.java` | 扩展增 factoryNumber/originPlace |
| `MaterialBatchDTO` | `dto/material/MaterialBatchDTO.java` | 扩展增输出字段 |
| `WHBatchDetailScreen.tsx` | `warehouse/inventory/` | 复用批次详情展示模式 |
| `MaterialBatchManagementScreen.tsx` | `processing/` | 复用批次列表+编辑 Dialog 模式 |

---

## 4. 数据模型增量

### 4.1 MaterialBatch — SP4-A4

**Flyway**: `V20260910_30__add_factory_number_origin_place_to_material_batches.sql`

```sql
-- V20260910_30
ALTER TABLE material_batches
  ADD COLUMN factory_number VARCHAR(100),
  ADD COLUMN origin_place   VARCHAR(200);

COMMENT ON COLUMN material_batches.factory_number IS '厂号 (非供应商匹配, 纯记录; 同批次内唯一)';
COMMENT ON COLUMN material_batches.origin_place IS '产地 (如: 内蒙古通辽 / 山东 / 进口-巴西)';
```

**Entity 增量** (`MaterialBatch.java`):
```java
/** 厂号 — 原料批次追溯; 非供应商匹配字段 */
@Column(name = "factory_number", length = 100)
private String factoryNumber;

/** 产地 */
@Column(name = "origin_place", length = 200)
private String originPlace;
```

**DTO 四点原则** (per `feedback_dto_roundtrip_silent_drop`):
1. `CreateMaterialBatchRequest.java` — 增 `factoryNumber`/`originPlace` String 字段
2. Service create: `batch.setFactoryNumber(req.getFactoryNumber())`
3. Service update: null-guard `if (req.getFactoryNumber() != null) batch.setFactoryNumber(req.getFactoryNumber())`
4. `MaterialBatchDTO.java` — 增对应输出字段 + convertToDTO 映射

### 4.2 税率枚举 — SP4-A8

**新 Enum** (`entity/enums/TaxRate.java`):
```java
public enum TaxRate {
    TAX_9(new BigDecimal("0.09")),
    TAX_13(new BigDecimal("0.13"));

    private final BigDecimal rate;
    // 含税 → 未税: preTax = taxIncluded / (1 + rate)
    public BigDecimal preTaxPrice(BigDecimal taxIncludedPrice) {
        return taxIncludedPrice.divide(BigDecimal.ONE.add(rate), 4, RoundingMode.HALF_UP);
    }
    // 未税 → 含税: withTax = preTax * (1 + rate)
    public BigDecimal withTaxPrice(BigDecimal preTaxPrice) {
        return preTaxPrice.multiply(BigDecimal.ONE.add(rate)).setScale(4, RoundingMode.HALF_UP);
    }
}
```

**Flyway V20260910_31** — ProductType 增 tax_rate:
```sql
ALTER TABLE product_types
  ADD COLUMN tax_rate VARCHAR(10);
COMMENT ON COLUMN product_types.tax_rate IS '税率枚举: TAX_9=9%, TAX_13=13%; NULL=未配置';
```

**Flyway V20260910_32** — RawMaterialType 增 tax_rate:
```sql
ALTER TABLE raw_material_types
  ADD COLUMN tax_rate VARCHAR(10);
COMMENT ON COLUMN raw_material_types.tax_rate IS '税率枚举: TAX_9=9%, TAX_13=13%';
```

**Entity 增量**:
- `ProductType.java`: `@Enumerated(EnumType.STRING) @Column(name="tax_rate") private TaxRate taxRate;`
- `RawMaterialType.java`: 同上
- DTO 四点: 双向 taxRate 字段 + 前端入参 + 输出 + convertToDTO

**含税/未税自动换算规则**:
- 前端只填含税单价 (`taxIncludedUnitPrice`) → 后端 Service 保存时自动算未税 (`unitPrice = taxRate.preTaxPrice(taxIncludedUnitPrice)`)
- 前端只填未税单价 → 后端算含税 (`taxIncludedUnitPrice = taxRate.withTaxPrice(unitPrice)`)
- 两者都填时: 以含税为准, 重算未税 (客户明确: 成本用未税)
- `taxRate = null` 时: 两个价格均存原值, 不换算, 不报错 (向后兼容)
- **`@PriceSensitive`** 必须同时标注 `unitPrice` 和 `taxIncludedUnitPrice`

### 4.3 BomRecipeItem 扩展 — SP4-B5/B6

**Flyway V20260910_33**:
```sql
ALTER TABLE bom_recipe_items
  ADD COLUMN per_portion          BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN semi_finished_ref_code VARCHAR(100);

COMMENT ON COLUMN bom_recipe_items.per_portion IS
  'TRUE=按成品份数固定拉料(辅料/调料); quantity = standardQuantity * outputPortions';
COMMENT ON COLUMN bom_recipe_items.semi_finished_ref_code IS
  '组合装半成品引用码 (指向另一批次的 batchNumber 或 product_types.code); NULL=普通原料';
```

**Entity 增量** (`BomRecipeItem.java`):
```java
/** 按份数固定拉料: true=辅料/调料按成品份数; false=按重量比例 (默认) */
@Column(name = "per_portion", nullable = false)
private Boolean perPortion = false;

/** 组合装半成品引用码; NULL=普通原料 BOM 行 */
@Column(name = "semi_finished_ref_code", length = 100)
private String semiFinishedRefCode;
```

**BOM 成本计算语义**:
- `perPortion=false` (默认): `itemCost = standardQuantity × unitPrice / 未税`
- `perPortion=true`: `itemCost = standardQuantity × outputPortions × unitPrice / 未税` (SP5 成本引擎实现完整计算; SP4 只加字段)
- `semiFinishedRefCode != null`: 该行是组合装半成品引用, 不直接消耗原料库存; SP5/SP8 实现引用解析

### 4.4 编码前缀对齐 — SP4-A3

**Flyway V20260910_34** (可选, 纯数据逻辑):
```sql
-- 不改现有编码, 只在 raw_material_types 增辅助列便于前端显示
-- 实际逻辑在 Java Service 层: getMaterialCategoryPrefix() 补数字分支
-- 本 migration 可为空占位或记录当前前缀统计
-- (无表结构变更, 仅 Java 逻辑)
```

Java 逻辑变更 (`RawMaterialTypeServiceImpl.java`):
```java
// 新增数字前缀别名映射 (与字母前缀双轨并行)
private static final Map<String, String> NUMERIC_PREFIX_MAP = Map.of(
    "RAW", "001",      // 原料
    "MEAT", "001",     // 肉类也归原料
    "PACKAGING", "002", // 包材
    "AUXILIARY", "003", // 辅料/调料
    "OTHER", "003"     // 兜底归辅料
);
```

前端搜索支持: 同时匹配字母前缀(YL)和数字前缀(001)的编码搜索。

---

## 5. 组件与数据流

### 5.1 A4 — 厂号/产地

```
web-admin (material-types/list.vue 入库 Dialog)
  ├── 入库表单增 "厂号" TextInput (可选, placeholder: 如 GD-2024-001)
  ├── 入库表单增 "产地" TextInput (可选, placeholder: 如 内蒙古通辽)
  └── POST /api/mobile/{factoryId}/material-batches
        ↓
  MaterialBatchController → MaterialBatchService
        ↓
  MaterialBatch (factory_number + origin_place 持久化)
        ↓
  MaterialBatchDTO 输出含 factoryNumber + originPlace

RN MaterialBatchManagementScreen (查看)
  └── 批次列表卡片展示 factoryNumber / originPlace
```

### 5.2 A5 — 批次条码标签

```
web-admin 批次详情页
  └── "生成标签" Button
        ↓ POST /api/mobile/{factoryId}/labels/material-batch/{batchId}
  LabelController → LabelService (复用 Label 实体, batchType=MATERIAL)
        ↓ 返回 labelCode

RN 扫码 (仓管/领料员)
  └── 扫描 labelCode
        ↓ GET /api/mobile/{factoryId}/labels/scan/{labelCode}
  LabelController → MaterialBatchService
        ↓ 返回: { batchNumber, materialTypeName, factoryNumber, originPlace,
                   receiptQuantity, quantityUnit, unitPrice (若有权限), receiptDate }
```

**扫码响应防呆 (fool-proof Rule 2)**:
- 必含: `materialTypeName` (品名) + `factoryNumber` (厂号) + `receiptQuantity` (剩余量)
- 可选: `unitPrice` (受 `@PriceSensitive` 脱敏, canViewPrice 门控)
- Label 已 VOIDED 时: 明确返回 400 + "该标签已作废" + 关联批次号

### 5.3 A8 — 税率配置

```
web-admin 原料管理 (material-types/list.vue) 编辑 Dialog
  ├── 税率下拉: [未配置, 9% (农产品), 13% (一般)]
  ├── 含税单价 Input (主输入)
  ├── 未税单价 (只读, 自动计算展示)
  └── PATCH /api/mobile/{factoryId}/raw-material-types/{id}

web-admin SKU 管理 (product-types) 同样逻辑

后端换算时机: Service.update() 收到 taxRate + taxIncludedUnitPrice
  → 自动算 unitPrice = taxIncludedUnitPrice / (1 + taxRate)
  → 两字段都入库
```

### 5.4 B5/B6 — BOM 扩展

```
web-admin BOM 配置页 (bom 相关 vue)
  ├── 每行物料增 "按份数" Checkbox (AUXILIARY/SEASONING 默认勾选)
  ├── 勾选时: 显示 "每份用量" (g/ml) 替代 "总量"
  ├── 组合装: 增 "引用半成品编码" Input (条件显示, PACKAGING 类型且需要)
  └── PUT /api/mobile/{factoryId}/bom-recipes/{id}/items

RN YieldStepReportScreen (报工)
  └── per_portion 行: 自动按 outputPortions 计算拉料建议量 (只展示, 不强制)
```

---

## 6. 端点归属

### 新增端点

| Method | Path | 说明 | 鉴权 |
|--------|------|------|------|
| POST | `/api/mobile/{factoryId}/labels/material-batch/{batchId}` | 为原料批次生成标签 | warehouse_manager, factory_admin |
| GET | `/api/mobile/{factoryId}/labels/scan/{labelCode}` | 扫码查原料批次信息 | 仓管/操作员角色 |

### 扩展现有端点

| Method | Path | 变更 |
|--------|------|------|
| POST/PUT | `/api/mobile/{factoryId}/material-batches` | 增 factoryNumber/originPlace |
| GET | `/api/mobile/{factoryId}/material-batches/{id}` | 输出增 factoryNumber/originPlace |
| PUT | `/api/mobile/{factoryId}/raw-material-types/{id}` | 增 taxRate 枚举 + 自动换算 |
| PUT | `/api/mobile/{factoryId}/product-types/{id}` | 增 taxRate 枚举 + 自动换算 |
| PUT | `/api/mobile/{factoryId}/bom-recipes/{id}/items` | 增 perPortion + semiFinishedRefCode |

---

## 7. 错误处理 (Fool-Proof 4位一体)

### 税率换算防呆

| 场景 | 响应 |
|------|------|
| `taxRate=null` 但填了两个不一致的价格 | 400 "请先选择税率，系统将自动换算未税价" (含 actionHint) |
| `taxRate` 收到非 TAX_9/TAX_13 | 400 "税率只支持 9% (农产品) 或 13% (一般货物)" |
| 含税价 ≤ 0 | 400 "含税单价必须大于 0" |

### 批次标签防呆

| 场景 | 响应 |
|------|------|
| 批次不存在 | 404 "原料批次不存在 (batchId: xxx)" |
| 批次已有 ACTIVE 标签 | 409 "已有标签 LBL-xxx, 是否查看?" + actionHint 跳转详情 (Rule 4 幂等) |
| 扫码 labelCode 已 VOIDED | 400 "标签 LBL-xxx 已作废 — 关联批次: YL-20260601-001" |

### BOM per_portion 防呆

| 场景 | 响应 |
|------|------|
| `perPortion=true` 但 `outputPortions=0/null` | 400 "按份数模式需要先配置该生产批次的计划产出份数" + Rule 5 跳转计划配置页 |
| `semiFinishedRefCode` 在库存中不存在 | 警告 (非阻塞): "引用半成品编码 XX-001 当前无库存，BOM 可保存，领料时将提示" |

### 4位一体 Toast

所有 error toast 遵守:
- (a) 网络 `response.message` 具体描述
- (b) 前端原样 display `e.response.data.message`
- (c) `duration: 0, showClose: true` (error sticky)
- (d) 含 next action 提示

---

## 8. 测试策略

### 单元测试 (必须先写, TDD)

| 测试类 | 覆盖点 |
|--------|--------|
| `TaxRateTest.java` | `preTaxPrice()`/`withTaxPrice()` 9%/13% 精度 (HALF_UP, scale-4) |
| `MaterialBatchServiceTest.java` | create/update DTO 四点 (factoryNumber/originPlace 保存+输出) |
| `LabelScanServiceTest.java` | 扫码返回结构 + VOIDED 状态 409/400 |
| `BomRecipeItemServiceTest.java` | `perPortion=true` 份数计算; `semiFinishedRefCode` 保存不报错 |
| `RawMaterialTypeServiceTest.java` | 含税→未税换算; `taxRate=null` 时两价格不覆盖 |

### 集成测试

- `MaterialBatchControllerTest`: POST 带 factoryNumber → GET 返回 factoryNumber (round-trip)
- `LabelControllerTest`: 生成标签 → 重复生成 → 409 含 existingId

### Web-admin E2E (headed Playwright)

- 原料入库 Dialog: 填厂号/产地 → 保存 → 列表显示
- 税率配置: 选 13% + 填含税价 → 未税价自动算 → 保存 → 重新打开验证

---

## 9. 跨 SP 依赖

| SP | 依赖关系 |
|----|---------|
| **SP5 (成本核算)** | SP4 加了 `tax_rate` + `unitPrice`(未税) 字段后, SP5 才能正确用未税价计算 BOM 成本. SP5 需在 SP4 migration apply 后开发. |
| **SP6 (出库/领料)** | SP6 领料 picker 需展示 `factoryNumber`/`originPlace` 让仓管确认. SP4 先加字段, SP6 读取. |
| **SP7 (扫码入库)** | SP7 入库扫码流程与 SP4 `labels/scan/{labelCode}` 端点共用. 端点归 SP4, SP7 复用. |
| **SP8 (BOM 配方)** | SP8 BOM 配方完整实现需要 SP4 的 `per_portion` + `semiFinishedRefCode` 字段已在库. |
| **SP1 (半成品双产出)** | 无直接依赖; SP1 使用的 `SemiFinishedInventory.batchNumber` 即 `semiFinishedRefCode` 值来源. |

---

## 10. UX Flow Analysis

> 适用角色: 仓管员 (warehouse_worker) — 低技术素养用户 (六扇门张权: "做仓管的年纪都比较大文化素质很低")

### UX Flow 触发场景

- **入库填厂号/产地** (screens/warehouse 入库 Dialog)
- **扫码查批次** (screens/warehouse 仓管扫码)

### Phase 1 · 用户画像 + 痛点

**仓管员 (低技术素养)**:
- 年龄偏大, 文化程度一般, 不擅长复杂表单
- 核心诉求: "你告诉我这个东西收多少就行了"
- 扫码比打字快 — 优先扫码操作

**关键痛点 (现状)**:
- 领料时拿错原料 (同品名多批次, 不知哪个是哪个厂的)
- 入库时厂号不知道往哪填 (customFields jsonb 难找)
- 扫了标签不知道能查到什么

### Phase 2 · UX Flow 分析

#### 场景 A: 入库 — 填厂号/产地

**用户目标**: 收到一批猪肉, 登记入库, 记下是哪个厂的

**Flow**:
```
仓管扫/选原料 → 系统自动显示 "这批猪肉要收多少" (Rule 1: 预先显示边界)
  → 填数量 + [可选] 厂号 (输入, placeholder: "如 GD-2024-001")
  → [可选] 产地 (输入, placeholder: "如 内蒙古通辽")
  → 确认入库
```

**防呆设计**:
- 厂号/产地为 **可选** (不填仍能入库, 降低新手门槛)
- Dialog 标题: "入库 — [品名] [规格] ([供应商])" (Rule 2: 身份信息)
- 已有 ACTIVE 批次时: "该原料已有在库批次 YL-xxx (xx kg), 本次新建批次?" 确认 (Rule 4: 幂等)

**RN 屏**: 操作员/仓管扫码入库
- 仓管扫码 → 底部弹 sheet (占屏 60%) 展示: 品名 + 厂号 + 产地 + 剩余量
- 大字体 (fontSize 20+), 重要信息加颜色强调

#### 场景 B: 扫码查批次

**用户目标**: 仓管领料时扫标签确认拿的是对的

**Flow**:
```
扫描批次标签 QR/barcode
  → 返回卡片 (大字): 品名 | 厂号 | 产地 | 剩余量
  → [次要] 入库日期 | 供应商
  → 操作按钮: "领用此批次" / "查看详情"
```

**防呆设计**:
- 扫到的 → 立刻显示, 不要让仓管找入口
- 厂号/产地 用醒目颜色 (橙色) 区分同品名不同批次
- 已作废标签: 全屏红色错误 "此标签已作废 — 请联系管理员" (Rule 5: dead-end 改导航)

### Phase 3 · 关键 UX 决策

1. **厂号为可选字段** (不能因为没填厂号就卡死入库)
2. **扫码结果页以"防错"为第一目标** — 大字品名+厂号, 而非完整详情
3. **RN 入库屏**: 厂号/产地 折叠在"更多信息"下, 减少仓管认知负荷
4. **web-admin 列表**: 厂号/产地 作为可选列 (默认不显示, 点列配置可加)

---

## 附: 实体字段变更汇总

| 实体 | 新增字段 | Flyway |
|------|----------|--------|
| `MaterialBatch` | `factory_number VARCHAR(100)`, `origin_place VARCHAR(200)` | V20260910_30 |
| `ProductType` | `tax_rate VARCHAR(10)` | V20260910_31 |
| `RawMaterialType` | `tax_rate VARCHAR(10)` | V20260910_32 |
| `BomRecipeItem` | `per_portion BOOLEAN DEFAULT FALSE`, `semi_finished_ref_code VARCHAR(100)` | V20260910_33 |
| (无表结构变更) | Java 逻辑补数字前缀 | V20260910_34 (占位) |

**新增类**:
- `entity/enums/TaxRate.java` (enum with preTaxPrice/withTaxPrice 方法)

**无新表** — 全部为现有表的字段追加。
