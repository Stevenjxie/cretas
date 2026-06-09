# SP4 · 一物一码补缺 — 实施计划 (Implementation Plan)

**子项**: SP4 · 一物一码补缺
**波次**: Wave 1 (与 SP1 并行)
**Flyway 号段**: `V20260910_30 ~ V20260910_39`
**Worktree**: `git worktree add -b feat/sp4-material-coding ../cretas-sp4 origin/main`
**生成**: 2026-06-09 Sonnet in-harness

---

## Flyway 号段查重纪律 (merge 前必查)

```bash
# 每次 merge 前执行 — 检查 origin/main 是否已有 V20260910_3x 占用
git fetch origin
git ls-tree origin/main --name-only -r db/flyway/ 2>/dev/null | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | grep 'V20260910_3'

# 预期: 空 (无冲突). 有输出则重编号
# 已确认 origin/main 现有: V20260910_01/02/03 (0x 段, 不冲突)
# SP4 安全使用: V20260910_30 ~ V20260910_34
```

---

## Fleet 路由说明

| 任务性质 | 路由 | 理由 |
|----------|------|------|
| Java 后端 (DTO四点/枚举/Service) | **Sonnet in-harness** | rule-heavy (DTO roundtrip/PriceSensitive/@PriceSensitive/Flyway编号); `.claude/rules/` 自动可见 |
| Web-admin Vue 表单/UI | **Composer 2.5** | 纯 UI 改动, 边界清楚 |
| RN 屏批次展示 | **Composer 2.5** | 纯 UI 改动 |
| 🔒 Flyway migration apply + prod 部署 | **Opus (本 chat)** | 红线: migration + prod 部署只能 Opus 出货闸 |
| 🔒 税率换算跨 SP5 设计决策 | **Opus (本 chat)** | 红线: 跨模块架构 |

> Fleet 现状: Codex/GPT 暂停, 只 Composer 出池 (out-of-harness). CLI/E2E/构建 → Sonnet in-harness.

---

## 总览表

| # | 任务 | 模型 | effort | 分支 | scope 锁 | 状态 | 阻塞 |
|---|------|------|--------|------|---------|------|------|
| T1 | 厂号/产地 backend (Entity+DTO+Service+Migration) | Sonnet in-harness | high | feat/sp4-material-coding | `MaterialBatch.*`, `CreateMaterialBatchRequest`, `MaterialBatchDTO` | pending | — |
| T2 | 税率枚举 backend (TaxRate enum + 两实体 + 换算 Service) | Sonnet in-harness | high | 同 | `TaxRate.java`, `RawMaterialType.*`, `ProductType.*` | pending | — |
| T3 | BOM 扩展 backend (per_portion + semi_finished_ref_code) | Sonnet in-harness | high | 同 | `BomRecipeItem.*` | pending | — |
| T4 | 编码前缀数字对齐 backend | Sonnet in-harness | high | 同 | `RawMaterialTypeServiceImpl.java` | pending | T1 先 |
| T5 | 批次标签扫码端点 backend | Sonnet in-harness | high | 同 | `LabelController`, `LabelService` | pending | T1 先 |
| T6 | web-admin 厂号/产地 UI (入库 Dialog) | Composer 2.5 | default | feat/sp4-ui-web | `material-types/list.vue` | pending | T1 merged |
| T7 | web-admin 税率配置 UI | Composer 2.5 | default | 同 | material-types/list.vue, product-types/*.vue | pending | T2 merged |
| T8 | web-admin BOM per_portion/组合装 UI | Composer 2.5 | default | 同 | bom/*.vue | pending | T3 merged |
| T9 | RN 批次详情增厂号/产地展示 | Composer 2.5 | default | feat/sp4-ui-rn | `MaterialBatchManagementScreen.tsx`, `WHBatchDetailScreen.tsx` | pending | T1 merged |

> T1-T5 可在同一 Sonnet worktree 串行. T6-T8 共用一个 Composer worktree (scope 不撞). T9 独立 RN worktree.

---

## T1 · 厂号/产地 Backend

### 分发卡 → Sonnet in-harness

**目标**: `MaterialBatch` 增 `factory_number`/`origin_place` 两列; DTO 四点全覆盖; Flyway V20260910_30.

**Worktree**:
```bash
git worktree add -b feat/sp4-material-coding ../cretas-sp4 origin/main
```

**先写测试 (TDD)**:
```
backend/java/cretas-api/src/test/java/com/cretas/aims/
  service/MaterialBatchServiceTest.java  ← 新增 factoryNumber round-trip 测试
  controller/MaterialBatchControllerTest.java ← POST 含 factoryNumber → GET 返回
```

**允许改**:
- `entity/MaterialBatch.java` — 增 `factoryNumber`, `originPlace` 字段
- `dto/material/CreateMaterialBatchRequest.java` — 增两字段
- `dto/material/MaterialBatchDTO.java` — 增两字段
- `service/impl/MaterialBatchServiceImpl.java` — create/update 四点
- `db/flyway/V20260910_30__add_factory_number_origin_place_to_material_batches.sql` (新建)

**禁改**: 其他 DTO、其他 Service、`RawMaterialType`、`BomRecipeItem`

**DTO 四点 (必须全部做完)**:
1. `CreateMaterialBatchRequest` 增 `String factoryNumber` + `String originPlace`
2. `createBatch()` 中 `batch.setFactoryNumber(req.getFactoryNumber())`
3. `updateBatch()` 中 null-guard: `if (req.getFactoryNumber() != null) batch.setFactoryNumber(...)`
4. `convertToDTO()` 中 `dto.setFactoryNumber(batch.getFactoryNumber())`

**Flyway**:
```sql
-- db/flyway/V20260910_30__add_factory_number_origin_place_to_material_batches.sql
ALTER TABLE material_batches
  ADD COLUMN factory_number VARCHAR(100),
  ADD COLUMN origin_place   VARCHAR(200);
COMMENT ON COLUMN material_batches.factory_number IS '厂号';
COMMENT ON COLUMN material_batches.origin_place IS '产地';
```

**验收**:
- `mvn test -pl backend/java/cretas-api -Dtest=MaterialBatchServiceTest,MaterialBatchControllerTest` 全绿
- POST `factoryNumber="GD-001"` → GET 返回 `"factoryNumber":"GD-001"` (round-trip)
- 旧批次 (无厂号) GET 返回 `"factoryNumber":null` 不报错

**🔒红线**: 不自部署 prod. 完成 → PR off origin/main → 回 Opus 终审.

---

## T2 · 税率枚举 Backend

### 分发卡 → Sonnet in-harness

**目标**: 新建 `TaxRate` enum (TAX_9/TAX_13 + 换算方法); `ProductType`/`RawMaterialType` 增 `taxRate` 字段 + 换算 Service; Flyway V20260910_31/32.

**先写测试**:
```
test/java/com/cretas/aims/
  entity/enums/TaxRateTest.java  ← preTaxPrice/withTaxPrice 精度测试
  service/RawMaterialTypeServiceTest.java  ← 含税→未税换算 + taxRate=null 向后兼容
  service/ProductTypeServiceTest.java ← 同上
```

**允许改**:
- `entity/enums/TaxRate.java` (新建)
- `entity/RawMaterialType.java` — 增 `@Enumerated(EnumType.STRING) private TaxRate taxRate`
- `entity/ProductType.java` — 同上
- `dto/material/RawMaterialTypeDTO.java` — 增 taxRate 字段 + DTO 四点
- `dto/material/CreateRawMaterialTypeRequest.java` — 同
- `dto/product/ProductTypeDTO.java` — 增 taxRate 字段
- `service/impl/RawMaterialTypeServiceImpl.java` — create/update 换算逻辑
- `service/impl/ProductTypeServiceImpl.java` — create/update 换算逻辑
- `db/flyway/V20260910_31__add_tax_rate_to_product_types.sql` (新建)
- `db/flyway/V20260910_32__add_tax_rate_to_raw_material_types.sql` (新建)

**禁改**: `MaterialBatch`, `BomRecipeItem`, `TaxRate` 字段精度不能低于 scale-4

**TaxRate enum 规范**:
```java
public enum TaxRate {
    TAX_9(new BigDecimal("0.09")),
    TAX_13(new BigDecimal("0.13"));
    private final BigDecimal rate;
    // preTaxPrice = taxIncludedPrice / (1 + rate), scale=4, HALF_UP
    // withTaxPrice = preTaxPrice * (1 + rate), scale=4, HALF_UP
}
```

**换算规则** (Service 层):
```
收到 taxRate + taxIncludedUnitPrice → 算 unitPrice = preTaxPrice(taxIncludedUnitPrice)
收到 taxRate + unitPrice (未税) → 算 taxIncludedUnitPrice = withTaxPrice(unitPrice)
两个都收到 → 以 taxIncludedUnitPrice 为准, 重算 unitPrice
taxRate = null → 原样保存两个价格, 不换算
```

**PriceSensitive 标注**: `unitPrice` 和 `taxIncludedUnitPrice` 都必须 `@PriceSensitive`

**验收**:
- `TaxRateTest`: TAX_9: 100含税 → 91.7431... 未税 (HALF_UP, scale-4) ✓
- `TaxRateTest`: TAX_13: 113含税 → 100.0000 未税 ✓
- `RawMaterialTypeServiceTest`: 旧数据 taxRate=null → update 不报错, unitPrice 不被清零
- `mvn test -Dtest=TaxRateTest,RawMaterialTypeServiceTest,ProductTypeServiceTest` 全绿

**🔒红线**: 不自部署. PR off origin/main → Opus 终审.

---

## T3 · BOM 扩展 Backend

### 分发卡 → Sonnet in-harness

**目标**: `BomRecipeItem` 增 `per_portion`(boolean) + `semi_finished_ref_code`(varchar); DTO 四点; Flyway V20260910_33.

**先写测试**:
```
test/java/com/cretas/aims/
  service/bom/BomRecipeItemServiceTest.java
    - perPortion=true 字段保存 round-trip
    - semiFinishedRefCode 保存 round-trip
    - perPortion=false (默认) 旧 BOM 不受影响
```

**允许改**:
- `entity/bom/BomRecipeItem.java` — 增两字段
- `dto/bom/BomRecipeItemDTO.java` — 增两字段 + 四点
- `dto/bom/CreateBomRecipeItemRequest.java` (如有) — 增两字段
- `service/impl/bom/BomRecipeServiceImpl.java` 或 `BomRecipeItemServiceImpl.java` — create/update 四点
- `db/flyway/V20260910_33__add_per_portion_semi_finished_ref_code_to_bom_recipe_items.sql` (新建)

**禁改**: 现有 BOM 成本计算逻辑 (SP5 做); `taxRate`(BigDecimal) 字段不改类型

**Flyway**:
```sql
ALTER TABLE bom_recipe_items
  ADD COLUMN per_portion           BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN semi_finished_ref_code VARCHAR(100);
```

**注意**: `per_portion=false` DEFAULT 保证旧 BOM 行向后兼容, 不需 backfill.

**验收**:
- POST BOM item `perPortion=true, semiFinishedRefCode="ZS-001"` → GET 返回相同值
- POST 不带 `perPortion` → GET 返回 `perPortion=false` (默认)
- 旧 BOM (数据库已有行) SELECT 不报错, `per_portion=false`

**🔒红线**: 不自部署. PR → Opus 终审.

---

## T4 · 编码前缀数字对齐 Backend

### 分发卡 → Sonnet in-harness

**目标**: `RawMaterialTypeServiceImpl.getMaterialCategoryPrefix()` 补数字前缀映射; 前端搜索时 001/002/003 能匹配到对应分类.

**先写测试**:
```java
// RawMaterialTypeServiceTest
@Test void numericPrefixMapping() {
    assertEquals("001", svc.getNumericPrefix("RAW"));
    assertEquals("001", svc.getNumericPrefix("MEAT"));
    assertEquals("002", svc.getNumericPrefix("PACKAGING"));
    assertEquals("003", svc.getNumericPrefix("AUXILIARY"));
}
```

**允许改**:
- `service/impl/RawMaterialTypeServiceImpl.java` — 增 `NUMERIC_PREFIX_MAP` + `getNumericPrefix(category)` 方法
- 搜索逻辑: `searchByCodeOrNumericPrefix(query)` (如有搜索端点则扩展)
- `db/flyway/V20260910_34__code_prefix_numeric_alignment.sql` (占位 migration, 仅注释无 DDL)

**占位 Migration**:
```sql
-- V20260910_34__code_prefix_numeric_alignment.sql
-- SP4-A3: 编码前缀数字对齐 (Java 逻辑层, 无 schema 变更)
-- 数字前缀: 001=原料/肉类, 002=包材, 003=辅料/调料
-- 字母前缀: YL=原料, RL=肉类, BC=包材, WL=其他
-- 两套并行, 搜索时互相 alias
SELECT 1; -- no-op
```

**验收**:
- 单元测试全绿
- GET 搜索 `?category=PACKAGING` 和搜索 `?q=002` 返回相同结果集

---

## T5 · 批次标签扫码端点 Backend

### 分发卡 → Sonnet in-harness

**目标**: 新增 `POST /labels/material-batch/{batchId}` (生成标签) + `GET /labels/scan/{labelCode}` (扫码查批次); 复用 `Label` 实体.

**先写测试**:
```java
// LabelControllerTest (或 LabelServiceTest)
- generateMaterialBatchLabel_success → Label 记录创建, batchType=MATERIAL
- generateMaterialBatchLabel_duplicate → 409 返回 existingLabelId (Rule 4 幂等)
- scanLabel_success → 返回 materialTypeName + factoryNumber + receiptQuantity
- scanLabel_voided → 400 "标签已作废" + batchNumber
```

**允许改**:
- `controller/LabelController.java` — 新增两端点
- `service/LabelService.java` (接口) + `service/impl/LabelServiceImpl.java` — 扩展两方法
- `dto/label/MaterialBatchLabelScanResponse.java` (新建 DTO)

**扫码响应结构**:
```java
public class MaterialBatchLabelScanResponse {
    private String batchNumber;
    private String materialTypeName;
    private String factoryNumber;    // 可为 null
    private String originPlace;      // 可为 null
    private BigDecimal receiptQuantity;
    private String quantityUnit;
    @PriceSensitive
    private BigDecimal unitPrice;    // canViewPrice 门控
    private LocalDate receiptDate;
    private String status;           // AVAILABLE/QUARANTINE/DEPLETED
}
```

**幂等检查** (Rule 4):
```java
// 生成标签前查: 同 batchId 是否已有 ACTIVE Label (batchType=MATERIAL)
Optional<Label> existing = labelRepo.findActiveByBatchIdAndBatchType(batchId, "MATERIAL");
if (existing.isPresent()) {
    throw new ConflictException("已有标签 " + existing.get().getLabelCode(),
        "existingLabelId", existing.get().getId());
}
```

**验收**:
- `LabelControllerTest` / `LabelServiceTest` 全绿 (含幂等+VOIDED 分支)
- `curl -X POST .../labels/material-batch/{validBatchId}` → `{ labelCode: "LBL-xxx" }`
- `curl .../labels/scan/LBL-xxx` → 返回 materialTypeName + factoryNumber

**🔒红线**: 不自部署. PR → Opus 终审.

---

## T6 · web-admin 厂号/产地 UI

### 分发卡 → Composer 2.5

**目标**: `material-types/list.vue` 入库 Dialog 增"厂号"/"产地"两个 TextInput; 保存时传后端.

**前置**: T1 PR 已 merged 进 origin/main (后端字段已存在).

**Worktree**:
```bash
git worktree add -b feat/sp4-ui-web ../cretas-sp4-web origin/main
cd ../cretas-sp4-web/web-admin
npm install --prefer-offline --legacy-peer-deps
```

**允许改**:
- `web-admin/src/views/warehouse/material-types/list.vue`
  - 入库/编辑 Dialog 增两个 `<el-form-item>` (厂号+产地, 均为可选)
  - placeholder: 厂号 "如 GD-2024-001", 产地 "如 内蒙古通辽"
  - 列表增"厂号"可选列 (默认隐藏, `v-show` 或列配置)
- `web-admin/src/api/materialBatch.ts` (如有) — 接口类型增 `factoryNumber?` / `originPlace?`

**禁改**: 其他 vue 文件; 不改后端 API 路径

**Dialog 防呆 (Rule 2)**:
- Dialog 标题: "入库 — [品名] [规格]"
- 展示当前在库量: "当前库存: XX kg"

**验收**:
- `npm run build` 无 TypeScript 错误
- 入库 Dialog: 填厂号/产地 → 保存 → 列表可筛查

---

## T7 · web-admin 税率配置 UI

### 分发卡 → Composer 2.5

**目标**: 原料管理编辑 Dialog 增税率下拉 (9%/13%/未配置); 含税价联动算未税价展示.

**前置**: T2 PR 已 merged.

**允许改**:
- `web-admin/src/views/warehouse/material-types/list.vue` — 编辑 Dialog 增 el-select 税率 + 含税价 Input + 未税价 (只读 computed)
- `web-admin/src/views/sales/products/` 相关 vue — SKU 编辑 Dialog 同样逻辑

**含税/未税联动** (前端 computed):
```javascript
const preTaxPrice = computed(() => {
  if (!form.taxRate || !form.taxIncludedUnitPrice) return null;
  const rate = form.taxRate === 'TAX_9' ? 0.09 : 0.13;
  return (form.taxIncludedUnitPrice / (1 + rate)).toFixed(4);
});
```

**验收**:
- 选 TAX_13 + 含税价 113 → 未税价自动显示 100.0000
- 不选税率 → 未税价区域显示 "请先选择税率"

---

## T8 · web-admin BOM per_portion/组合装 UI

### 分发卡 → Composer 2.5

**目标**: BOM 配置页每行物料增"按份数"Checkbox + 条件显示"每份用量"; 增"引用半成品编码"输入项.

**前置**: T3 PR 已 merged.

**允许改**:
- BOM 相关 vue (Glob: `web-admin/src/views/**/bom*.vue`)
- 每 BOM 行: `per_portion` Checkbox; 勾选时 label 改"每份用量(g)"
- 组合装: `semiFinishedRefCode` Input (条件显示: `materialCategory==='PACKAGING'`)

**防呆 (Rule 3)**:
- `perPortion=true` 时, 旁边显示公式: `总用量 = [每份用量] × [计划份数]`
- 当 `outputPortions` 未配置时: 橙色警告 "需先配置计划份数才能计算总用量" + 按钮跳配置

**验收**:
- BOM 行勾选"按份数" → 保存 → 重新加载仍为 true
- 取消勾选 → 恢复重量比例显示

---

## T9 · RN 批次详情增厂号/产地

### 分发卡 → Composer 2.5

**目标**: `MaterialBatchManagementScreen.tsx` + `WHBatchDetailScreen.tsx` 展示 `factoryNumber`/`originPlace`; 防错区域用橙色加粗.

**前置**: T1 PR 已 merged; RN api client 类型更新.

**Worktree**:
```bash
git worktree add -b feat/sp4-ui-rn ../cretas-sp4-rn origin/main
cd ../cretas-sp4-rn/frontend/CretasFoodTrace
npm install --prefer-offline --legacy-peer-deps
```

**允许改**:
- `frontend/CretasFoodTrace/src/screens/processing/MaterialBatchManagementScreen.tsx`
  - 批次卡片增 factoryNumber (橙色, fontSize 14) / originPlace 行
- `frontend/CretasFoodTrace/src/screens/warehouse/inventory/WHBatchDetailScreen.tsx`
  - 详情页增厂号/产地信息块 (大字, 防错高亮)
- `frontend/CretasFoodTrace/src/services/api/materialBatchApiClient.ts`
  - `MaterialBatch` 接口增 `factoryNumber?: string`, `originPlace?: string`

**UX 规范**:
- 厂号/产地: `color: '#F59E0B'` (Amber-500) + `fontWeight: '600'`
- 无厂号时: 显示灰色 "未记录厂号"
- 仓管扫码结果卡: 大字 (fontSize 20) 显示品名+厂号

**验收**:
- `npx tsc --noEmit` 无错误
- 批次详情卡片展示厂号/产地 (有值时橙色, 无值时灰色 placeholder)

---

## 整体验收标准

### Backend (T1-T5 全部 merged)

```bash
# 在 backend/java/cretas-api 目录
mvn test -Dtest="MaterialBatchServiceTest,MaterialBatchControllerTest,TaxRateTest,RawMaterialTypeServiceTest,BomRecipeItemServiceTest,LabelServiceTest"
# 预期: BUILD SUCCESS, 所有测试绿色
```

### Web-admin (T6-T8 merged)

```bash
cd web-admin
npm run type-check && npm run build
# 预期: 无 TypeScript 错误, Build Success
```

### RN (T9)

```bash
cd frontend/CretasFoodTrace
npx tsc --noEmit
# 预期: no errors
```

### Migration 查重 (PR 前必做)

```bash
git fetch origin
git ls-tree origin/main --name-only -r db/flyway/ | grep 'V20260910_3' | sort | uniq -d
# 预期: 无输出 (无重复)
```

### Scope 检查 (PR 前必做)

```bash
git diff origin/main...HEAD --stat
# 预期: 只有 SP4 scope 文件 (MaterialBatch/RawMaterialType/BomRecipeItem/Label/TaxRate 相关)
# 无 sister 文件 (无报工/出库/SmartBI/餐饮等无关文件)
```

---

## 🔒 红线 Checklist (Opus 终审门)

PR 合并前 Opus 必须确认:

- [ ] Flyway V20260910_30-34 无重复版本号
- [ ] `@PriceSensitive` 已标注所有价格字段 (unitPrice + taxIncludedUnitPrice)
- [ ] `TaxRate.preTaxPrice()` scale=4, ROUND_HALF_UP (Rule 4/Rule 10 from python-java-port 参考)
- [ ] 税率换算 Service: `taxRate=null` 时原样保存 (向后兼容, 旧数据不报错)
- [ ] `Label` 幂等: 同批次重复生成标签返回 409 + existingId
- [ ] DTO 四点: T1/T2/T3 每项的 create/update/convertToDTO 全部有对应实现
- [ ] `perPortion` 字段 DEFAULT FALSE (旧 BOM 行不受影响)
- [ ] 无 `as any` / `@SuppressWarnings("unchecked")` 绕过类型安全
- [ ] 测试覆盖: round-trip + edge cases (null/空值/旧数据兼容)

---

## Worktree 清理

```bash
# 全部 PR merged 后
git worktree remove ../cretas-sp4
git worktree remove ../cretas-sp4-web
git worktree remove ../cretas-sp4-rn
```

**⛔ 禁止**: `mklink /J node_modules` (Windows worktree remove 会掏空主 repo node_modules)
