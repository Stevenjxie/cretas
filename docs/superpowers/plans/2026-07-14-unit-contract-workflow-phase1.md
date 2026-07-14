# Unit Contract + Workflow Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立统一单位契约、产品专属换算关系和 Workflow 单位发布防线，使新 Workflow/新批次只能使用可解释、可审计的单位，不再出现 SKU/物料是 `g`、运行时端口却悄悄成为“件”的情况。

**Architecture:** 后端 `UnitContractService` 负责别名归一、量纲判断、系统固有换算和产品专属换算；`product_unit_conversions` 保存 `1 pcs = 200 g` 等 SKU 级关系。Workflow 编辑器通过纯函数对齐物料节点、端口和 process 提示单位，后端发布校验与 runtime compiler 再次 fail-closed；运行时端口快照保存规范单位和换算版本。现有 `73a0c271c` 的“运行时报工只读 Workflow 端口”作为消费端基线保留。

**Tech Stack:** Java 21, Spring Boot 3.2, JPA/Hibernate 6, PostgreSQL/Flyway, Vue 3, TypeScript, Vitest, JUnit 5, Mockito, PostgreSQL integration tests.

## Global Constraints

- 本计划只实现设计文档 Phase 1。生产计划双数量、库存聚合/扣减、销售订单单位候选、BOM 成本归一分别另写 Phase 2/3 计划。
- 不修改已报工批次、库存流水、财务凭证或历史 Workflow runtime snapshot。
- `LIUSHANMEN` 不测试、不迁移；生产数据验证只允许 F006，且先 dry-run。
- 数据库和 API 写规范代码；UI 可显示中文。别名仅表示同一单位，`件` 与 `盒` 绝不按 1:1 归一。
- 跨量纲和包装层级换算必须有 `productTypeId` 和有效换算记录；缺失、冲突、闭环不一致全部 fail-closed。
- 每个任务先写失败测试，再写最小实现，再运行聚焦测试并提交。

---

## Task 1: 建立规范单位值对象与系统固有换算内核

**Files:**

- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/UnitDimension.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/CanonicalUnit.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/UnitConversionStatus.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/UnitConversionContext.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/UnitConversionResult.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/UnitContractService.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/impl/UnitContractServiceImpl.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/unit/UnitContractServiceTest.java`

- [ ] **Step 1: 写失败测试固定别名、量纲和失败语义**

```java
@ParameterizedTest
@CsvSource({
    "克,g", "g,g", "公斤,kg", "千克,kg", "KG,kg",
    "毫升,ml", "mL,ml", "升,l", "件,pcs", "个,pcs", "只,pcs",
    "份,portion", "盒,box", "箱,case", "袋,bag", "瓶,bottle"
})
void normalizeKnownAliases(String raw, String expected) {
    assertThat(service.normalize(raw).code()).isEqualTo(expected);
}

@Test
void boxAndPcsAreNotEquivalent() {
    assertThat(service.areEquivalent("盒", "件")).isFalse();
}

@Test
void refusesCountToMassWithoutProductContext() {
    assertThat(service.convert(context(null, "pcs", "g")).status())
        .isEqualTo(UnitConversionStatus.PRODUCT_CONVERSION_MISSING);
}
```

- [ ] **Step 2: 确认红灯**

Run: `cd backend/java/cretas-api; mvn -q -Dtest=UnitContractServiceTest test`

Expected: compile failure because unit contract types do not exist.

- [ ] **Step 3: 实现最小类型与服务**

```java
public record CanonicalUnit(
    String code, UnitDimension dimension, String baseCode,
    BigDecimal factorToBase, String displayName, int displayScale) {}

public record UnitConversionResult(
    UnitConversionStatus status, BigDecimal quantity,
    String fromUnit, String toUnit, List<String> path,
    String conversionRefId, Long conversionVersion, String message) {
    public boolean succeeded() {
        return status == UnitConversionStatus.IDENTITY
            || status == UnitConversionStatus.CONVERTED;
    }
}
```

系统固有换算仅允许 MASS 内 `mg/g/kg/t`、VOLUME 内 `ml/l`；COUNT/PACKAGE 无产品上下文时只能同规范代码透传。核心计算不按展示精度舍入。

- [ ] **Step 4: 运行测试并提交**

Run: `mvn -q -Dtest=UnitContractServiceTest test`

Expected: PASS.

Commit: `feat(unit): 建立规范单位契约内核`

---

## Task 2: 新增产品专属换算模型与 PostgreSQL 约束

**Files:**

- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261028_62__product_unit_conversions.sql`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/unit/ProductUnitConversion.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/unit/ProductUnitConversionRepository.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/entity/ProductUnitConversionSchemaContractTest.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/integration/ProductUnitConversionPostgresIntegrationTest.java`

- [ ] **Step 1: 写失败 schema contract 测试**

断言 factor > 0、from != to、source type 枚举、有效期合法，并且唯一约束只覆盖未软删且仍有效的同向关系。

- [ ] **Step 2: 创建表和实体**

```sql
CREATE TABLE product_unit_conversions (
  id VARCHAR(36) PRIMARY KEY,
  factory_id VARCHAR(50) NOT NULL,
  product_type_id VARCHAR(100) NOT NULL,
  from_unit_code VARCHAR(20) NOT NULL,
  to_unit_code VARCHAR(20) NOT NULL,
  factor NUMERIC(20,8) NOT NULL CHECK (factor > 0),
  source_type VARCHAR(20) NOT NULL
    CHECK (source_type IN ('NET_CONTENT','PACKAGING','MANUAL')),
  is_primary_sales_conversion BOOLEAN NOT NULL DEFAULT FALSE,
  effective_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_to TIMESTAMP,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  CHECK (from_unit_code <> to_unit_code),
  CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX uq_puc_active_direction
  ON product_unit_conversions(factory_id, product_type_id, from_unit_code, to_unit_code)
  WHERE deleted_at IS NULL AND effective_to IS NULL;
```

Repository 提供按 factory/product/at 查询有效关系；实体继承 `BaseEntity`，使用 `@Version`。

- [ ] **Step 3: PostgreSQL 集成测试**

Run: `mvn -q -Dtest=ProductUnitConversionSchemaContractTest,ProductUnitConversionPostgresIntegrationTest test`

Expected: active duplicate fails; soft-deleted predecessor does not block replacement.

- [ ] **Step 4: 提交**

Commit: `feat(unit): 新增产品专属单位换算模型`

---

## Task 3: 支持产品换算路径、冲突与闭环检测

**Files:**

- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/UnitContractService.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/impl/UnitContractServiceImpl.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/unit/UnitContractServiceTest.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/unit/ProductUnitConversionGraphTest.java`

- [ ] **Step 1: 写失败测试固定 `1 pcs = 200 g` 双向换算**

```java
@Test
void convertsProductSpecificCountToMassBothWays() {
    given(repo.findEffective("F006", "SKU-1", at)).willReturn(List.of(
        conversion("pcs", "g", "200", 7L)));

    assertThat(service.convert(context("SKU-1", "pcs", "g", "10")).quantity())
        .isEqualByComparingTo("2000");
    assertThat(service.convert(context("SKU-1", "g", "pcs", "2000")).quantity())
        .isEqualByComparingTo("10");
}
```

- [ ] **Step 2: 写失败测试覆盖歧义和错误复用**

覆盖两条不同乘积路径、闭环乘积不为 1、`g -> kg` 不得误走 pcs 克重、`case -> pcs -> g` 记录完整 path/version。

- [ ] **Step 3: 实现有界 BFS**

顺序：规范化 -> 同单位 IDENTITY -> 同量纲系统换算 -> 指定产品有效图。最短路径出现不同乘积返回 `AMBIGUOUS_CONVERSION`，缺关系返回 `PRODUCT_CONVERSION_MISSING`。

- [ ] **Step 4: 运行并提交**

Run: `mvn -q -Dtest=UnitContractServiceTest,ProductUnitConversionGraphTest test`

Expected: PASS.

Commit: `feat(unit): 支持 SKU 显式单位换算图`

---

## Task 4: 提供单位目录、换算与产品关系 CRUD API

**Files:**

- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/unit/UnitCatalogItemDTO.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/unit/UnitConversionRequest.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/unit/ProductUnitConversionDTO.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/UnitContractController.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/ProductUnitConversionService.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/impl/ProductUnitConversionServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductTypeServiceImpl.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/UnitContractControllerTest.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/unit/ProductUnitConversionServiceTest.java`

- [ ] **Step 1: 写 API 失败测试**

固定接口：

```text
GET    /api/mobile/{factoryId}/units/catalog
POST   /api/mobile/{factoryId}/units/convert
GET    /api/mobile/{factoryId}/product-types/{productTypeId}/unit-conversions
POST   /api/mobile/{factoryId}/product-types/{productTypeId}/unit-conversions
PUT    /api/mobile/{factoryId}/product-types/{productTypeId}/unit-conversions/{id}
DELETE /api/mobile/{factoryId}/product-types/{productTypeId}/unit-conversions/{id}
```

- [ ] **Step 2: 实现 CRUD 防线**

校验 factory/product 归属、规范化 from/to、factor > 0、转换图一致、乐观锁和软删除。响应同时返回 code/label/dimension，不返回伪造数值。

- [ ] **Step 3: 兼容 gramsPerUnit**

仅当产品主单位明确是 `pcs/portion/box` 时，把 `gramsPerUnit` 映射为该单位 -> g 的 NET_CONTENT 关系。产品本身是 g/kg 时不得猜计数单位。新 API 写 NET_CONTENT 且可无损表达时回写旧字段。

- [ ] **Step 4: 修复显式清空旧字段**

测试并实现 ProductType 更新时可显式清空 `gramsPerUnit` / `boxConversionCoefficient`；如果 DTO 无法区分未提供和 null，增加 patch presence 语义，不使用魔法值。

- [ ] **Step 5: 运行并提交**

Run: `mvn -q -Dtest=UnitContractControllerTest,ProductUnitConversionServiceTest test`

Expected: PASS.

Commit: `feat(unit): 提供单位目录与 SKU 换算 API`

---

## Task 5: 建立 Workflow 单位 reconciliation 纯函数

**Files:**

- Create: `web-admin/src/views/system/product-processes/workflow/workflowUnits.ts`
- Create: `web-admin/src/views/system/product-processes/workflow/__tests__/workflowUnits.spec.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/types.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/workflowModel.ts`

- [ ] **Step 1: 写 SHH0713 冲突失败测试**

```ts
it('aligns bound material, port and primary output hint to canonical g', () => {
  const result = reconcileWorkflowUnits(definitionWith({
    processOutputUnit: 'g',
    materialBaseUnit: '件',
    portUnit: '件',
    skuUnit: '克',
  }), catalog({ skuUnit: 'g' }));

  expect(result.material('material:finished').baseUnit).toBe('g');
  expect(result.port('output:final').unit).toBe('g');
  expect(result.process('process:cold').outputUnit).toBe('g');
  expect(result.errors).toEqual([]);
});
```

另覆盖：无 conversionRefId 的跨单位报错；有效关系保留报工单位；多产出分别对齐；未绑定 SKU 只 warning；`克/g` 只做别名归一。

- [ ] **Step 2: 扩展端口类型**

```ts
conversionRefId?: string | null;
conversionVersion?: number | null;
```

- [ ] **Step 3: 实现无副作用函数**

```ts
export function reconcileWorkflowUnits(
  definition: ProductProcessWorkflowDefinition,
  context: WorkflowUnitContext,
): WorkflowUnitReconciliationResult;
```

主 output 是 ordinal 最小的 OUTPUT；`ProcessNodeData.outputUnit` 从它派生。函数不得访问 Vue ref、网络或 mutate 输入。

- [ ] **Step 4: 删除创建路径的伪造 kg**

`createProcessBranch` 和 AI 节点只继承已连接物料/工序明确单位；未知保留为空并由草稿 warning，不能静默写 kg。

- [ ] **Step 5: 运行并提交**

Run: `cd web-admin; npx vitest run src/views/system/product-processes/workflow/__tests__/workflowUnits.spec.ts src/views/system/product-processes/workflow/__tests__/workflowModel.spec.ts`

Expected: PASS.

Commit: `feat(workflow): 增加单位对齐纯函数`

---

## Task 6: 编辑器接入统一目录、产品换算和 reconciliation

**Files:**

- Create: `web-admin/src/api/unitContract.ts`
- Create: `web-admin/src/api/__tests__/unitContract.spec.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`
- Modify: `web-admin/src/views/system/product-processes/workflow/WorkflowProcessNode.vue`
- Modify: `web-admin/src/views/system/product-processes/workflow/WorkflowMaterialNode.vue`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.spec.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.loadIsolation.spec.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.activation.spec.ts`
- Create: `web-admin/src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.unitReconciliation.spec.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowProcessNode.spec.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowMaterialNode.spec.ts`

- [ ] **Step 1: 写组件失败测试**

覆盖单位下拉来自 catalog、提交 canonical code、`bindOutputSku()` 同步三处、加载/SKU 加载/保存/发布都 reconciliation、error 阻断、跨单位选择 conversionRefId、UI 同时展示库存单位/报工单位/换算关系。专门覆盖当前 `loadCatalogs()` 与 `loadDefinition()` 并发竞态的两种完成顺序，以及旧 factory 的迟到响应不得污染新图。

- [ ] **Step 2: 实现 API 类型**

```ts
export interface UnitCatalogItem {
  code: string;
  label: string;
  dimension: 'MASS' | 'VOLUME' | 'COUNT' | 'PACKAGE';
  displayScale: number;
}
```

- [ ] **Step 3: 替换局部单位同步**

`refreshPortMaterialMetadata()` 只刷新名称/code/kind；单位交给 reconciliation。`bindOutputSku()` 在一次 mutation 内绑定并对齐。删除本地 `unitOptions` 硬编码。历史 PUBLISHED 预览只报告冲突，不原地 hydrate 改写。

- [ ] **Step 4: 保存/发布 fail-closed**

warning 可保存草稿，error 阻断。服务端 `WORKFLOW_PORT_UNIT_STALE` 显示 node/port 和修复入口，不回退 default kg。即使 `dirty=false`，发布前也重跑 reconciliation；若主数据变化导致定义修正，必须先保存新草稿再发布/启用。

- [ ] **Step 5: 运行并提交**

Run:

```powershell
cd web-admin
npx vitest run src/views/system/product-processes/workflow/__tests__
npm run build
```

Expected: workflow tests PASS; build exits 0.

Commit: `feat(workflow): 编辑器接入统一单位契约`

---

## Task 7: 后端发布时阻断陈旧或无依据单位

**Files:**

- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/validation/ProductProcessWorkflowValidator.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidator.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImpl.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/validation/ProductProcessWorkflowValidatorTest.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidatorTest.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImplTest.java`

- [ ] **Step 1: 写失败测试**

固定：`process.outputUnit=g / primaryPort=pcs` 失败；`port=pcs / material=g` 无 ref 失败；material 与 bound SKU 不一致无 ref 失败；ref 归属/方向/version 错误失败；`克/g` 通过；草稿可不完整但发布阻断。

- [ ] **Step 2: 分层校验**

纯图结构留在 `ProductProcessWorkflowValidator`；依赖 SKU/转换库的校验放 `ProductProcessWorkflowCatalogValidator`。错误码使用 `WORKFLOW_PORT_UNIT_STALE` 并带 nodeId/portId/current/expected。

- [ ] **Step 3: 固定发布顺序**

结构校验 -> catalog/SKU 校验 -> conversion graph 校验 -> 保存 PUBLISHED。任一步失败不改变状态。

- [ ] **Step 4: 运行并提交**

Run: `mvn -q -Dtest=ProductProcessWorkflowValidatorTest,ProductProcessWorkflowCatalogValidatorTest,ProductProcessWorkflowServiceImplTest test`

Expected: PASS.

Commit: `fix(workflow): 发布时阻断陈旧和无依据单位`

---

## Task 8: 编译与运行时快照保存规范单位和换算版本

**Files:**

- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261028_63__workflow_port_unit_conversion_snapshot.sql`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/workflow/WorkflowTaskPort.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/CompiledProductProcessWorkflow.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompiler.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/impl/ProductProcessWorkflowRuntimeServiceImpl.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompilerTest.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeServiceTest.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/integration/ProductProcessWorkflowRuntimePostgresIntegrationTest.java`

- [ ] **Step 1: 写 runtime 防线失败测试**

compiler 再拒绝 process/primary port 冲突；全部输出 canonical code；跨单位必须带 ref/version；materialize 保存快照；后续改 ProductType 或 conversion 不改变已物化快照。

- [ ] **Step 2: 扩展 schema**

```sql
ALTER TABLE workflow_task_ports
  ADD COLUMN unit_code VARCHAR(20),
  ADD COLUMN conversion_ref_id VARCHAR(36),
  ADD COLUMN conversion_version BIGINT,
  ADD COLUMN conversion_factor_snapshot NUMERIC(20,8);

UPDATE workflow_task_ports SET unit_code = unit WHERE unit_code IS NULL;
ALTER TABLE workflow_task_ports ALTER COLUMN unit_code SET NOT NULL;
```

保留旧 `unit` 字段作为兼容镜像，写入时与 `unit_code` 相同。

- [ ] **Step 3: compiler 只从主 output port 派生 plannedUnit**

`data.outputUnit` 仅做一致性校验，不再是 fallback。无主 output、无单位、未知别名全部阻断。

- [ ] **Step 4: runtime 复制完整快照**

物化时保存 conversion id/version/factor snapshot，不再查询当前 SKU 单位替换端口定义。

- [ ] **Step 5: 运行并提交**

Run: `mvn -q -Dtest=ProductProcessWorkflowRuntimeCompilerTest,ProductProcessWorkflowRuntimeServiceTest,ProductProcessWorkflowRuntimePostgresIntegrationTest test`

Expected: PASS.

Commit: `feat(workflow): 快照单位与换算版本`

---

## Task 9: 增加只读冲突扫描并准备 F006 验收

**Files:**

- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/unit/UnitGovernanceConflictDTO.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/UnitGovernanceAuditService.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/impl/UnitGovernanceAuditServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/UnitContractController.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/unit/UnitGovernanceAuditServiceTest.java`
- Create: `docs/dispatch/2026-07-14-f006-unit-governance-phase1-runbook.md`

- [ ] **Step 1: 写扫描器失败测试**

`GET /api/mobile/{factoryId}/unit-governance/conflicts` 识别未知别名、material/SKU 不一致、process/primary port 不一致、port/material 无 ref、ref 失效和 gramsPerUnit 语义不明。

- [ ] **Step 2: 实现只读扫描**

不得修改 Workflow/SKU/批次/换算关系。响应包含 factory/product/workflowVersion/node/port/current/expected/errorCode。

- [ ] **Step 3: 写 F006 runbook**

顺序：扫描 Workflow 48/v3 -> 确认 SHH0713 库存基准 g -> 创建 `pcs -> g factor=200` -> 克隆并发布 v4（不改 v3）-> 新计划/新批次验证 runtime g -> 逐道录入 UI/payload g -> 证明历史 snapshot 未改。

- [ ] **Step 4: 运行并提交**

Run: `mvn -q -Dtest=UnitGovernanceAuditServiceTest test`

Expected: PASS.

Commit: `feat(unit): 增加 Workflow 单位冲突扫描`

---

## Task 10: Phase 1 回归、审查与发布边界

- [ ] **Step 1: 后端聚焦回归**

```powershell
cd backend/java/cretas-api
mvn -q -Dtest=UnitContractServiceTest,ProductUnitConversionGraphTest,ProductUnitConversionServiceTest,UnitContractControllerTest,ProductProcessWorkflowValidatorTest,ProductProcessWorkflowCatalogValidatorTest,ProductProcessWorkflowServiceImplTest,ProductProcessWorkflowRuntimeCompilerTest,ProductProcessWorkflowRuntimeServiceTest,ProcessSheetWorkflowUnitNormalizationTest,WorkflowClerkSheetServiceTest test
```

Expected: all selected tests PASS.

- [ ] **Step 2: PostgreSQL 集成回归**

Run: `mvn -q -Dtest=ProductUnitConversionPostgresIntegrationTest,ProductProcessWorkflowPostgresIntegrationTest,ProductProcessWorkflowRuntimePostgresIntegrationTest test`

Expected: Flyway 62/63 clean; constraints and snapshot tests PASS.

- [ ] **Step 3: 前端回归与构建**

```powershell
cd web-admin
npx vitest run src/views/system/product-processes/workflow/__tests__ src/views/production/components/processSheet
npx vue-tsc -b
npm run build
```

Expected: related tests PASS; no new type errors; build exits 0.

- [ ] **Step 4: 静态防回退检查**

```powershell
git grep -n "|| 'kg'\||| \"kg\"" -- web-admin/src/views/system/product-processes/workflow backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow
git grep -n "convertOrSame" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service
```

Expected: Workflow 创建/发布/compiler/runtime 无静默 kg fallback；本 Phase 不新增金额路径的 `convertOrSame`。

- [ ] **Step 5: 请求独立代码审查并修复 findings**

审查重点：钱和库存边界未被 Phase 1 意外接管；规范化不把包装单位当别名；历史 snapshot 不被更新；migration 可重复部署；多产出逐端口校验。

- [ ] **Step 6: 明确完成口径**

已解决：新 Workflow 无法发布冲突单位；新 runtime/reporting 使用规范端口单位；`1件=200g` 有显式产品关系；历史冲突可扫描。

尚未解决：销售候选单位、销售转计划双数量、库存/FEFO/退货统一换算、BOM 成本统一。这些必须进入独立 Phase 2/3，不能宣称“全局单位治理全部完成”。

部署必须从合并后的 `main` 执行：后端 Flyway/健康检查 -> Web Admin -> F006 验证 -> 再扩大范围。
