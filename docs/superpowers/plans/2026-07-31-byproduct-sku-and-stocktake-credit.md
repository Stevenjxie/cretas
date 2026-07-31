# 副产 SKU 化与盘点抵扣 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有的「报工时自由文本副产」升级成「原料字典 SKU + 落生产仓 + 盘点确认单价」，并把副产单价收敛到一条权威链。

**Architecture:** 不新建库存体系也不新建成本算法 —— 副产落进既有的 `material_batches`（`warehouseId`=生产仓，与 WIP 半成品同一条路，prod 已有 249 条先例），单价从「报工时录」挪到「盘点时确认」，成本抵扣复用既有的 `OrderCostBreakdownService` 副产回收链。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JPA/Hibernate 6 + PostgreSQL；web-admin 用 Vue 3 + TypeScript + Element Plus + Vitest。

## Global Constraints

- 响应格式统一 `{ success, data, message }`（CLAUDE.md 核心原则 3）
- **禁止降级处理**：不返回假数据、不臆造默认值。单价未确认 → 抵扣额 `null` + 状态「未抵扣」，**绝不写 0**
- 禁止 `as any`；TypeScript 用明确类型
- 错误 toast 必须 sticky（`duration:0 + showClose`）且原样展示后端 `message`
- 计数/包装单位**不得以英文码示人**：前端一律经 `displayUnit` / `displayProcessUnit`（见 `utils/__tests__/unitDisplayContract.spec.ts`，会扫全部 `.vue`）
- 单位相等判定用 `UnitContractServiceImpl.crossLanguageCode`（跨语言折叠），**不要**用 `canonicalCodeOrRaw`（会把 只/个/件 并成 pcs，违反 #1976）
- DB 变更必须走 migration runner，禁手动 psql DDL（`server-operations` skill）
- 每个任务结束前 `mvn clean test`（不是增量 `mvn test` —— 本仓踩过增量编译假绿）

---

## File Structure

**后端**
- `backend/java/cretas-api/src/main/resources/db/flyway/V20261029_36__byproduct_sku_and_credit.sql` — 新建。加 `raw_material_types` 副产标记、`material_batches` 副产来源、副产批次单价确认列
- `.../entity/MaterialBatch.java` — 修改。加副产单价确认字段
- `.../entity/RawMaterialType.java` — 不改（用既有 `category` 字段承载「副产」大类）
- `.../service/processentry/impl/ProcessSheetServiceImpl.java` — 修改。报工副产行落 `material_batches`
- `.../service/inventory/ByproductCreditService.java` — 新建。副产单价确认 + 抵扣额计算的唯一入口
- `.../controller/ByproductCreditController.java` — 新建。盘点侧确认单价的只读/写接口

**前端**
- `web-admin/src/api/byproduct.ts` — 新建。副产确认接口
- `web-admin/src/views/production/bom/index.vue` — 修改。配方内容第四类「副产」+「添加副产」按钮
- `web-admin/src/views/warehouse/stocktakes/byproductCredit.ts` — 新建。抵扣额计算的纯函数（便于单测）
- `web-admin/src/views/warehouse/stocktakes/index.vue` — 修改。副产价值确认区

**边界**：`ByproductCreditService` 是副产单价与抵扣额的**唯一计算入口**。前端 `byproductCredit.ts` 只做展示格式化，不重复算钱。

---

### Task 1: 副产大类与「不可采购」隔离

副产 SKU 放原料字典（与 WIP 半成品同一条路），但必须与「买来的原料」区分，否则会出现在采购下拉里。

**Files:**
- Modify: `web-admin/src/utils/materialCategory.ts`
- Test: `web-admin/src/utils/__tests__/materialCategory.spec.ts`

**Interfaces:**
- Consumes: 无
- Produces: `BYPRODUCT_CATEGORY: '副产'` 常量；`isByproductCategory(category: string | null | undefined): boolean`；`BigCategory` 类型新增 `'副产'` 成员

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { BYPRODUCT_CATEGORY, isByproductCategory, BIG_CATEGORY_OPTIONS } from '../materialCategory';

describe('副产大类', () => {
  it('副产是独立大类, 不混进原料桶', () => {
    expect(BYPRODUCT_CATEGORY).toBe('副产');
    expect(BIG_CATEGORY_OPTIONS.map((o) => o.value)).toContain('副产');
  });

  it('isByproductCategory 只认副产, 认不出的一律 false (不猜)', () => {
    expect(isByproductCategory('副产')).toBe(true);
    expect(isByproductCategory(' 副产 ')).toBe(true);
    expect(isByproductCategory('原料')).toBe(false);
    expect(isByproductCategory('辅料')).toBe(false);
    expect(isByproductCategory(null)).toBe(false);
    expect(isByproductCategory(undefined)).toBe(false);
    expect(isByproductCategory('')).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/utils/__tests__/materialCategory.spec.ts`
Expected: FAIL —— `BYPRODUCT_CATEGORY` 未导出

- [ ] **Step 3: 最小实现**

在 `web-admin/src/utils/materialCategory.ts`：把 `BigCategory` 类型加上 `'副产'`，并追加：

```ts
/**
 * 副产大类 —— 副产 SKU 放在原料字典里(与 WIP 半成品同一条路, prod 已有 249 条先例),
 * 但它**没有采购来源**: unitPrice / taxIncludedUnitPrice / movingAvgPrice / minStock
 * 这些采购属性对它全是空的。用大类把它与「买来的原料」隔开, 避免出现在采购下拉与补货建议。
 */
export const BYPRODUCT_CATEGORY = '副产' as const;

export function isByproductCategory(category: string | null | undefined): boolean {
  return typeof category === 'string' && category.trim() === BYPRODUCT_CATEGORY;
}
```

并在 `BIG_CATEGORY_OPTIONS` 里追加 `{ label: '副产', value: '副产' }`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/utils/__tests__/materialCategory.spec.ts`
Expected: PASS

- [ ] **Step 5: 变异检验**

把 `isByproductCategory` 的 `=== BYPRODUCT_CATEGORY` 改成 `.includes('副')`，重跑：`isByproductCategory('副食')` 会误判为 true 但当前用例抓不到 —— 补一条 `expect(isByproductCategory('副食')).toBe(false)` 再还原实现，确认该条变红。

- [ ] **Step 6: 提交**

```bash
git add web-admin/src/utils/materialCategory.ts web-admin/src/utils/__tests__/materialCategory.spec.ts
git commit -m "feat(byproduct): 原料字典加「副产」大类, 与采购属性隔离" -- web-admin/src/utils/materialCategory.ts web-admin/src/utils/__tests__/materialCategory.spec.ts
```

---

### Task 2: DB migration —— 副产批次与单价确认列

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261029_36__byproduct_sku_and_credit.sql`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/migration/ByproductCreditMigrationContractTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `material_batches` 新列 `byproduct_source_report_id BIGINT`、`byproduct_unit_price NUMERIC(15,4)`、`byproduct_price_confirmed_at TIMESTAMP`、`byproduct_price_confirmed_by BIGINT`；`source_doc_type` 新增取值 `'BYPRODUCT'`

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ByproductCreditMigrationContractTest {

    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261029_36__byproduct_sku_and_credit.sql");

    @Test
    void migrationAddsByproductColumnsAndIsIdempotent() throws Exception {
        assertThat(SQL).exists();
        String sql = Files.readString(SQL);

        for (String column : new String[]{
                "byproduct_source_report_id", "byproduct_unit_price",
                "byproduct_price_confirmed_at", "byproduct_price_confirmed_by"}) {
            assertThat(sql).as("缺列 %s", column).contains(column);
        }
        // 幂等: 本仓 migration 必须可重复执行
        assertThat(sql.toUpperCase()).contains("IF NOT EXISTS");
        // 单价必须允许 NULL —— 未确认就是 null, 禁降级不许默认 0
        assertThat(sql).doesNotContain("byproduct_unit_price NUMERIC(15,4) NOT NULL");
        assertThat(sql).doesNotContain("DEFAULT 0");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/java/cretas-api && mvn clean test -Dtest=ByproductCreditMigrationContractTest`
Expected: FAIL —— 文件不存在

- [ ] **Step 3: 写 migration**

```sql
-- 副产 SKU 化与盘点抵扣 (2026-07-31)
-- 副产落 material_batches 与 WIP 半成品同一条路 (prod 已有 249 条 PRODUCTION_BATCH 先例)。
-- 单价刻意允许 NULL: 未在盘点确认前不臆造 0 —— 0 会被当成"这批副产不值钱"。
ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS byproduct_source_report_id   BIGINT,
    ADD COLUMN IF NOT EXISTS byproduct_unit_price         NUMERIC(15,4),
    ADD COLUMN IF NOT EXISTS byproduct_price_confirmed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS byproduct_price_confirmed_by BIGINT;

COMMENT ON COLUMN material_batches.byproduct_unit_price IS
    '副产单价(元/单位), 盘点时确认; NULL = 未确认, 不参与抵扣且展示为「未抵扣」';

CREATE INDEX IF NOT EXISTS idx_material_batches_byproduct_pending
    ON material_batches (factory_id, byproduct_price_confirmed_at)
    WHERE byproduct_source_report_id IS NOT NULL;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn clean test -Dtest=ByproductCreditMigrationContractTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/java/cretas-api/src/main/resources/db/flyway/V20261029_36__byproduct_sku_and_credit.sql backend/java/cretas-api/src/test/java/com/cretas/aims/migration/ByproductCreditMigrationContractTest.java
git commit -m "feat(byproduct): migration 加副产批次来源与单价确认列" -- backend/java/cretas-api/src/main/resources/db/flyway/V20261029_36__byproduct_sku_and_credit.sql backend/java/cretas-api/src/test/java/com/cretas/aims/migration/ByproductCreditMigrationContractTest.java
```

---

### Task 3: 抵扣额计算 —— 唯一入口

抵扣额只在这一处算。前端不重复实现（本仓刚因「同一件事多套实现」栽过五次）。

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/ByproductCreditService.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/ByproductCreditServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的 `material_batches` 新列
- Produces: `ByproductCreditService.creditOf(BigDecimal stocktakeQuantity, BigDecimal unitPrice): BigDecimal`（返回 `null` 表示未确认，**不返回 0**）

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.service.inventory;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ByproductCreditServiceTest {

    @Test
    void creditIsQuantityTimesUnitPrice() {
        assertThat(ByproductCreditService.creditOf(new BigDecimal("3.0"), new BigDecimal("4.00")))
                .isEqualByComparingTo("12.00");
    }

    /** 🔴 禁降级: 未确认单价返回 null, 不是 0 —— 0 会被当成「这批副产不值钱」。 */
    @Test
    void missingUnitPriceYieldsNullNotZero() {
        assertThat(ByproductCreditService.creditOf(new BigDecimal("3.0"), null)).isNull();
    }

    /** 单价确认为 0 与「没填」必须分得开: 0 是一个真实的确认结果。 */
    @Test
    void explicitZeroUnitPriceIsAConfirmedCreditOfZero() {
        assertThat(ByproductCreditService.creditOf(new BigDecimal("3.0"), BigDecimal.ZERO))
                .isEqualByComparingTo("0");
    }

    @Test
    void missingQuantityYieldsNull() {
        assertThat(ByproductCreditService.creditOf(null, new BigDecimal("4.00"))).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/java/cretas-api && mvn clean test -Dtest=ByproductCreditServiceTest`
Expected: FAIL —— `ByproductCreditService` 不存在

- [ ] **Step 3: 最小实现**

```java
package com.cretas.aims.service.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 副产抵扣额的<b>唯一</b>计算入口。
 *
 * <p>🔴 本仓 2026-07-31 连续修过五处「同一件事多套实现」(单位别名表)。副产抵扣只在这里算,
 * 前端只负责展示后端返回的值, 不得自行 quantity × unitPrice。</p>
 *
 * <p>🔴 禁降级: 单价未确认返回 {@code null}, <b>不是 0</b>。0 会被读成「这批副产不值钱」,
 * 而 null 如实表达「还没人确认过」。单价确认为 0 是另一回事 —— 那是个真实的确认结果。</p>
 */
public final class ByproductCreditService {

    private ByproductCreditService() {}

    public static BigDecimal creditOf(BigDecimal stocktakeQuantity, BigDecimal unitPrice) {
        if (stocktakeQuantity == null || unitPrice == null) {
            return null;
        }
        return stocktakeQuantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn clean test -Dtest=ByproductCreditServiceTest`
Expected: PASS，4/4

- [ ] **Step 5: 变异检验**

把 `return null` 改成 `return BigDecimal.ZERO`，重跑 —— `missingUnitPriceYieldsNullNotZero` 必须变红。还原后再确认绿。

- [ ] **Step 6: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/ByproductCreditService.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/ByproductCreditServiceTest.java
git commit -m "feat(byproduct): 抵扣额唯一计算入口, 未确认单价返 null 不返 0" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/ByproductCreditService.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/ByproductCreditServiceTest.java
```

---

### Task 4: 报工副产落生产仓

现状：报工副产只写进 `production_reports.byproducts`（自由文本），不进库存。本任务让它同时落一条 `material_batches`。

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/MaterialBatch.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ProcessSheetByproductMaterializationTest.java`

**Interfaces:**
- Consumes: Task 2 的列；`WarehouseResolver.resolveWorkshopId(factoryId)`（既有）
- Produces: `MaterialBatch` 上的 getter/setter：`getByproductSourceReportId()` / `getByproductUnitPrice()` / `getByproductPriceConfirmedAt()` / `getByproductPriceConfirmedBy()`；`source_doc_type` 常量 `"BYPRODUCT"`

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessSheetByproductMaterializationTest {

    /**
     * 副产落 material_batches 与 WIP 半成品同一条路, 但去向是**生产仓**。
     * 单价此时**不写** —— 它在盘点时才确认 (Steve 2026-07-31)。
     */
    @Test
    void byproductBatchLandsInWorkshopWithoutUnitPrice() {
        MaterialBatch batch = ProcessSheetByproductFixtures.materialize(
                "F006", "RAW-0031", new BigDecimal("2.8"), "kg", "WH-WKS-1", 4001L);

        assertThat(batch.getWarehouseId()).isEqualTo("WH-WKS-1");
        assertThat(batch.getSourceDocType()).isEqualTo("BYPRODUCT");
        assertThat(batch.getMaterialTypeId()).isEqualTo("RAW-0031");
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo("2.8");
        assertThat(batch.getByproductSourceReportId()).isEqualTo(4001L);
        assertThat(batch.getByproductUnitPrice())
                .as("单价在盘点时才确认, 报工不写")
                .isNull();
        assertThat(batch.getByproductPriceConfirmedAt()).isNull();
    }
}
```

同时新建 fixture（与被测代码同包，供测试构造）：

```java
package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import java.math.BigDecimal;

/** 把 ProcessSheetServiceImpl 里的副产物化逻辑暴露给测试的薄封装。 */
final class ProcessSheetByproductFixtures {
    private ProcessSheetByproductFixtures() {}

    static MaterialBatch materialize(String factoryId, String materialTypeId,
                                     BigDecimal quantity, String unit,
                                     String workshopId, Long reportId) {
        return com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl
                .buildByproductBatch(factoryId, materialTypeId, quantity, unit, workshopId, reportId);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/java/cretas-api && mvn clean test -Dtest=ProcessSheetByproductMaterializationTest`
Expected: FAIL —— `buildByproductBatch` 不存在

- [ ] **Step 3: 加实体字段**

在 `MaterialBatch.java` 追加（放在既有 `sourceDocType` 附近）：

```java
    /** 副产来源报工 ID; 非 null 即表示这是一条副产批次。 */
    @Column(name = "byproduct_source_report_id")
    private Long byproductSourceReportId;

    /** 副产单价(元/单位), <b>盘点时</b>确认; null = 未确认, 不参与抵扣。 */
    @Column(name = "byproduct_unit_price", precision = 15, scale = 4)
    private BigDecimal byproductUnitPrice;

    @Column(name = "byproduct_price_confirmed_at")
    private java.time.LocalDateTime byproductPriceConfirmedAt;

    @Column(name = "byproduct_price_confirmed_by")
    private Long byproductPriceConfirmedBy;
```

- [ ] **Step 4: 加物化方法**

在 `ProcessSheetServiceImpl` 追加（`public static` 便于契约测试直调，且它是纯函数不读实例状态）：

```java
    /** 副产来源标记 —— 与 WIP 的 PRODUCTION_BATCH 并列。 */
    public static final String SOURCE_DOC_TYPE_BYPRODUCT = "BYPRODUCT";

    /**
     * 把一条报工副产物化成生产仓里的原料批次。
     *
     * <p>去向是<b>生产仓</b>不是原料仓 —— 它是生产出来的, 不是采购入库的
     * (Steve 2026-07-31)。落库后就是普通原料批次, 能被别的 Workflow 正常投入。</p>
     *
     * <p>🔴 <b>不写单价</b>: 单价在盘点时确认。报工时没人知道这批副产值多少,
     * 此处写任何值都是臆造。</p>
     */
    public static MaterialBatch buildByproductBatch(
            String factoryId, String materialTypeId, BigDecimal quantity,
            String unit, String workshopId, Long sourceReportId) {
        MaterialBatch batch = new MaterialBatch();
        batch.setFactoryId(factoryId);
        batch.setMaterialTypeId(materialTypeId);
        batch.setWarehouseId(workshopId);
        batch.setSourceDocType(SOURCE_DOC_TYPE_BYPRODUCT);
        batch.setCurrentQuantity(quantity);
        batch.setQuantityUnit(unit);
        batch.setByproductSourceReportId(sourceReportId);
        return batch;
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn clean test -Dtest=ProcessSheetByproductMaterializationTest`
Expected: PASS

- [ ] **Step 6: 回归 + 取基线**

Run: `cd backend/java/cretas-api && mvn clean test -Dtest='ProcessSheet*Test'`
把失败清单与 `git stash` 后的同一命令结果**逐个名字比对**，确认零新增（本仓基线有既存红测，只看名字差异）。

- [ ] **Step 7: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/entity/MaterialBatch.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ProcessSheetByproductMaterializationTest.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ProcessSheetByproductFixtures.java
git commit -m "feat(byproduct): 报工副产落生产仓, 单价留到盘点确认" -- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/MaterialBatch.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry
```

---

### Task 5: BOM 配方内容第四类「副产」

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue`
- Test: `web-admin/src/views/production/bom/__tests__/byproductTab.source.spec.ts`

**Interfaces:**
- Consumes: Task 1 的 `BYPRODUCT_CATEGORY` / `isByproductCategory`
- Produces: `activeCategoryTab` 类型扩为 `'RAW' | 'AUXILIARY' | 'PACKAGING' | 'BYPRODUCT'`

- [ ] **Step 1: 写失败测试**

```ts
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'index.vue'), 'utf8');

describe('BOM 配方内容: 副产第四类', () => {
  it('页签类型含 BYPRODUCT', () => {
    expect(source).toMatch(/activeCategoryTab\s*=\s*ref<'RAW' \| 'AUXILIARY' \| 'PACKAGING' \| 'BYPRODUCT'>/);
  });

  it('按钮文案随页签切换, 副产页签显示「添加副产」', () => {
    // 沿用既有规则: 现在就是 PACKAGING ? '添加包材' : '添加原料'
    expect(source).toContain("'BYPRODUCT' ? '添加副产'");
  });

  it('副产不计入生效条件 —— 它是可选的', () => {
    expect(source).toContain('副产 可选');
  });

  it('单位一律经展示映射, 不得裸露英文码', () => {
    // 与 utils/__tests__/unitDisplayContract.spec.ts 同一条规矩
    const raw = source.match(/\{\{[^}]*byproduct[^}]*\.unit[^}]*\}\}/gi) ?? [];
    for (const chunk of raw) {
      expect(chunk, `裸露单位插值: ${chunk}`).toMatch(/displayUnit|displayProcessUnit/);
    }
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/views/production/bom/__tests__/byproductTab.source.spec.ts`
Expected: FAIL —— 4 条全红

- [ ] **Step 3: 实现**

在 `index.vue`：

1. `const activeCategoryTab = ref<'RAW' | 'AUXILIARY' | 'PACKAGING' | 'BYPRODUCT'>('RAW');`
2. 按钮文案改为：
```ts
activeCategoryTab === 'BYPRODUCT' ? '添加副产'
  : activeCategoryTab === 'PACKAGING' ? '添加包材' : '添加原料'
```
3. 页签列表加 `副产 ({{ byproductItems.length }}) · 可选`
4. 生效条件面板加一行「副产 可选，不作为生效前提」
5. 副产行的物料选择器按 `isByproductCategory` 过滤原料字典
6. 所有单位展示裹 `displayProcessUnit(...)`

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/views/production/bom/__tests__/byproductTab.source.spec.ts`
Expected: PASS，4/4

- [ ] **Step 5: 全量前端验证**

Run（三样缺一不可，本仓 web-admin 的 PR 上 CI 不跑前端）：
```bash
cd web-admin && npx vue-tsc -b --force && npx vitest run && npm run build
```
`vitest` 的失败清单与基线**逐个名字比对**，确认零新增。

- [ ] **Step 6: 提交**

```bash
git add web-admin/src/views/production/bom/index.vue web-admin/src/views/production/bom/__tests__/byproductTab.source.spec.ts
git commit -m "feat(byproduct): BOM 配方内容加副产第四类与「添加副产」按钮" -- web-admin/src/views/production/bom/index.vue web-admin/src/views/production/bom/__tests__/byproductTab.source.spec.ts
```

---

### Task 6: 盘点侧单价确认与抵扣展示

**Files:**
- Create: `web-admin/src/views/warehouse/stocktakes/byproductCredit.ts`
- Create: `web-admin/src/views/warehouse/stocktakes/__tests__/byproductCredit.spec.ts`
- Modify: `web-admin/src/views/warehouse/stocktakes/index.vue`

**Interfaces:**
- Consumes: Task 3 的后端返回值（前端**不重算**金额）
- Produces: `formatCredit(credit: number | null): string`；`creditStatus(unitPrice: number | null, confirmedAt: string | null): 'CONFIRMED' | 'PENDING'`

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { formatCredit, creditStatus } from '../byproductCredit';

describe('副产抵扣展示', () => {
  it('未抵扣显示「未抵扣」, 不显示 0', () => {
    expect(formatCredit(null)).toBe('未抵扣');
  });

  it('已确认为 0 显示 0.00, 与「未抵扣」区分', () => {
    expect(formatCredit(0)).toBe('0.00');
  });

  it('正常金额两位小数', () => {
    expect(formatCredit(12)).toBe('12.00');
  });

  it('状态: 确认过才算 CONFIRMED', () => {
    expect(creditStatus(4, '2026-07-31T10:00:00')).toBe('CONFIRMED');
    expect(creditStatus(null, null)).toBe('PENDING');
    // 有价但没确认时间 —— 那是 BOM 带过来的参考价, 不算确认
    expect(creditStatus(4, null)).toBe('PENDING');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/views/warehouse/stocktakes/__tests__/byproductCredit.spec.ts`
Expected: FAIL —— 模块不存在

- [ ] **Step 3: 实现**

```ts
/**
 * 副产抵扣的**展示**格式化。
 *
 * 🔴 只做格式化, 不算钱 —— 金额由后端 ByproductCreditService 算好返回。
 * 本仓 2026-07-31 连续修过五处「同一件事多套实现」, 不再开第六处。
 */
export function formatCredit(credit: number | null): string {
  // 禁降级: null 是「还没人确认过」, 与确认为 0 是两回事
  if (credit == null) return '未抵扣';
  return credit.toFixed(2);
}

export function creditStatus(
  unitPrice: number | null,
  confirmedAt: string | null,
): 'CONFIRMED' | 'PENDING' {
  // 有价但没确认时间 = BOM 带过来的参考价, 还没人拍板
  return unitPrice != null && confirmedAt != null ? 'CONFIRMED' : 'PENDING';
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/views/warehouse/stocktakes/__tests__/byproductCredit.spec.ts`
Expected: PASS，4/4

- [ ] **Step 5: 接进盘点页**

在 `stocktakes/index.vue` 加「副产价值确认」区：列出 `byproduct_source_report_id IS NOT NULL` 的批次，显示 **报工重量 / 盘点重量（差异）/ 单价 / 抵扣额 / 状态**，抵扣按**盘点重量**算（Steve 2026-07-31 定）。单位一律经 `displayUnit`。

- [ ] **Step 6: 全量前端验证 + 提交**

```bash
cd web-admin && npx vue-tsc -b --force && npx vitest run && npm run build
git add web-admin/src/views/warehouse/stocktakes/
git commit -m "feat(byproduct): 盘点侧副产单价确认与抵扣展示" -- web-admin/src/views/warehouse/stocktakes/
```

---

### Task 7: 单价来源收敛的存量对比（只出报告，不改数）

Spec §1.2 定的「并成一套」唯一一处会改既有计算输入。**先看存量差多少再决定迁移方式**。

**Files:**
- Create: `docs/dispatch/2026-07-31-byproduct-price-source-audit.md`

**Interfaces:**
- Consumes: Task 1–6 的成果
- Produces: 一份对比报告（无代码产物）

- [ ] **Step 1: 查存量**

对 prod 只读执行（库名 `cretas_prod_db`，不是 `cretas_db`）：

```sql
SELECT r.id AS recipe_id, p.name AS sku, r.byproduct_nrv_unit_price AS bom侧单价,
       r.status, r.version
  FROM bom_recipes r JOIN product_types p ON p.id = r.product_type_id
 WHERE r.byproduct_nrv_unit_price IS NOT NULL
 ORDER BY r.status, p.name;

SELECT jsonb_array_elements(byproducts::jsonb) AS 报工副产
  FROM production_reports
 WHERE byproducts IS NOT NULL AND byproducts::text NOT IN ('null','[]','');
```

- [ ] **Step 2: 写对比报告**

报告必须回答三件：① 两侧单价**是否有同一副产取值不同**的情况 ②迁移到 SKU 参考价后，
哪些 BOM 的**标准成本会变**、变多少 ③是否需要保留每配方的覆盖位。

**阳性对照**：若查询返回 0 行，先用不带过滤的同一 join 确认能查出行 —— 本仓 2026-07-31
栽过「grep 不存在的文件永远返回 0」那类假阴性。

- [ ] **Step 3: 提交报告**

```bash
git add docs/dispatch/2026-07-31-byproduct-price-source-audit.md
git commit -m "docs(byproduct): 单价来源收敛的存量对比报告" -- docs/dispatch/2026-07-31-byproduct-price-source-audit.md
```

- [ ] **Step 4: 停下来找 Steve 定迁移方式**

🔴 **本任务到此为止，不要自行迁移**。改 `byproduct_nrv_unit_price` 的用法会动
**BOM 标准成本**，属成本口径（红线）。带着报告问，定了再做。

---

## Self-Review

**Spec 覆盖**：§3.1 副产 SKU 放原料字典 → Task 1；§3.2 与采购隔离 → Task 1；
§4.2 BOM 第四类 →Task 5；§4.3 报工落生产仓 → Task 4；§5 盘点确认单价 → Task 3 + 6；
§8.2/8.3 成本报表单列与可展开 → Task 6；§8.4 按盘点重量 → Task 6；
§8.5 采购下拉排除 → Task 1；§1.2 单价来源收敛 → Task 7。

**未覆盖（有意）**：§4.1 Workflow 产出 Cell 勾「这是副产」—— 线上已有
`work_processes.expected_byproducts`（4 个工序在用），需先摸清它与本设计的关系，
不在本轮草率改动。**Task 7 的报告应一并覆盖这一项。**

**占位符**：无 TBD/TODO；每个代码步骤都给了可直接粘贴的实现。

**类型一致**：`creditOf` / `formatCredit` / `creditStatus` / `isByproductCategory` /
`buildByproductBatch` 的签名在定义处与引用处一致；`BYPRODUCT` 作为 `activeCategoryTab`
取值与 `SOURCE_DOC_TYPE_BYPRODUCT`（`material_batches.source_doc_type` 的值）是**两个不同的东西**，
命名上已区分。
