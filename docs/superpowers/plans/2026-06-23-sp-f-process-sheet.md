# SP-F 逐工序电子表格录入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 SP-B2 一次性抽屉替换成配置驱动的逐工序电子表格 (修油→焯水→熟制 3 道切片),靠真实 batchNumber 串 DAG,增量单行物化进现有成本图,复用 SP-C 核算。

**Architecture:** 抽取 `ClerkProcessEntryServiceImpl` 内循环为共享 `materializeBatch(resolvedEdges, resolvedCtx)` —— 上游解析反转出 helper 外 (recordChain 走内存 map,新 `ProcessSheetService.saveRow` 走持久 batchNumber)。新增 `process_sheet_rows` 行追踪表 (回读 + update-in-place upsert)。前端通用 `<ProcessSheet>` 组件 + `PROCESS_SHEET_CONFIG`。

**Tech Stack:** Java 21 / Spring Boot 3.2 / JPA-Hibernate 6 / PostgreSQL / Flyway;Vue 3 + TS + Element Plus (web-admin);JUnit5 + 真 PG 集成测;Playwright headed E2E。

**Spec:** `docs/superpowers/specs/2026-06-23-sp-f-process-sheet-design.md` (Rev 2)。

**前置铁律 (每个 worker 必读)**:
- 成本边 `totalCost = qty × 上游单价`;调料走 `cost_category='SEASONING'` 报工 (**显式 set,不靠 idx 启发式**),**不写** MaterialConsumption;WIP 批 `batch_type='CLERK_WIP'` + `productionPlanId=null` + `source_doc_type='PRODUCTION_BATCH'`。
- 所有新 query/endpoint **factory-scoped** (跨租户红线)。
- WIP 批 `material_type_id` **NOT NULL FK → raw_material_types** (SP-E bug);per-row 熟制行无 rawInputs → 从上游 WIP 派生 type。
- re-save = **update-in-place 保 id** (不删+重建)。
- worktree `C:\Users\Steve\cretas-sp-f` (off origin/main);scope-locked commit;DEMO_FACTORY only。

---

## File Structure

**后端 (新建)**
- `backend/java/cretas-api/src/main/resources/db/migration/V20261027_10__process_sheet_rows.sql` — 行追踪表
- `entity/processentry/ProcessSheetRow.java` — JPA 实体 (extends BaseEntity)
- `repository/ProcessSheetRowRepository.java`
- `dto/processentry/ProcessSheetRowRequest.java` / `ProcessSheetRowResult.java`
- `service/processentry/ProcessSheetService.java` (接口) + `impl/ProcessSheetServiceImpl.java`
- `controller/ProcessSheetController.java`
- `dto/processentry/MaterializeContext.java` / `ResolvedEdge.java` — materializeBatch 入参

**后端 (改)**
- `service/processentry/impl/ClerkProcessEntryServiceImpl.java` — 抽 `materializeBatch`,内循环改调它
- `repository/MaterialBatchRepository.java` — +`findByFactoryIdAndSourceDocTypeAndSourceDocId`
- `repository/MaterialConsumptionRepository.java` — 确认/加 `findByFactoryIdAndBatchId` + `deleteByFactoryIdAndProductionBatchId`
- `repository/ProductionReportRepository.java` — +`deleteByFactoryIdAndBatchId`

**前端 (新建)**
- `web-admin/src/views/production/components/processSheet/PROCESS_SHEET_CONFIG.ts`
- `web-admin/src/views/production/components/processSheet/ProcessSheet.vue` (+ 子组件 DataTable/WorkHoursTable/InventoryTable)
- `web-admin/src/api/processSheet.ts`

**前端 (改)**
- `web-admin/src/views/production/plans/list.vue` — 替换 drawer 触发为 ProcessSheet 入口

**测试 (新建)**
- `service/processentry/ProcessSheetServiceImplTest.java` + `ProcessSheetIntegrationTest.java` (真 PG)
- `web-admin/.../processSheet/__tests__/*.spec.ts`
- e2e spec (headed)

---

## Phase 1 — F1 Keystone (Opus 自做, 🔒): 表 + materializeBatch 重构 + 写端点

### Task 1.1: `process_sheet_rows` 表 + 实体 + repo

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/migration/V20261027_10__process_sheet_rows.sql`
- Create: `entity/processentry/ProcessSheetRow.java`
- Create: `repository/ProcessSheetRowRepository.java`

- [ ] **Step 1: 预检 Flyway 重号**

Run: `git ls-tree -r origin/main --name-only | grep flyway | grep -oE 'V[0-9_]+' | sort | uniq -d`
Expected: 空 (无重号)。若 `V20261027_10` 已被占用,顺延 `_11`/`V20261028_01` 并同步本计划文件名号。

- [ ] **Step 2: 写迁移 SQL**

```sql
-- V20261027_10__process_sheet_rows.sql
CREATE TABLE process_sheet_rows (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64) NOT NULL,
  process_code VARCHAR(32) NOT NULL,
  client_row_id VARCHAR(64) NOT NULL,
  batch_id BIGINT,
  batch_number VARCHAR(64),
  row_payload JSONB NOT NULL,
  row_status VARCHAR(16) NOT NULL DEFAULT 'SAVED',
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL,
  CONSTRAINT uk_sheet_row UNIQUE (factory_id, plan_id, process_code, client_row_id)
);
CREATE INDEX idx_psr_plan ON process_sheet_rows (factory_id, plan_id, process_code) WHERE deleted_at IS NULL;
```

- [ ] **Step 3: 写实体** (extends `BaseEntity`,JSONB 用 `@JdbcTypeCode(SqlTypes.JSON)` 存 `String`)

```java
@Entity @Table(name = "process_sheet_rows")
@Data @EqualsAndHashCode(callSuper = true)
public class ProcessSheetRow extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="factory_id", nullable=false) private String factoryId;
    @Column(name="plan_id", nullable=false) private String planId;
    @Column(name="process_code", nullable=false) private String processCode;
    @Column(name="client_row_id", nullable=false) private String clientRowId;
    @Column(name="batch_id") private Long batchId;
    @Column(name="batch_number") private String batchNumber;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="row_payload", nullable=false, columnDefinition="jsonb") private String rowPayload;
    @Column(name="row_status", nullable=false) private String rowStatus = "SAVED";
}
```

- [ ] **Step 4: 写 repo**

```java
public interface ProcessSheetRowRepository extends JpaRepository<ProcessSheetRow, Long> {
    Optional<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(String f, String p, String pc, String cr);
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCode(String f, String p, String pc);
    List<ProcessSheetRow> findByFactoryIdAndPlanId(String f, String p);
}
```

- [ ] **Step 5: 编译验证**

Run: `cd backend/java/cretas-api && mvn -q -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(SP-F): process_sheet_rows table + entity + repo" -- \
  backend/java/cretas-api/src/main/resources/db/migration/V20261027_10__process_sheet_rows.sql \
  backend/java/cretas-api/src/main/java/com/cretas/aims/entity/processentry/ProcessSheetRow.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProcessSheetRowRepository.java
```

### Task 1.2: 新增 repo 方法 (factory-scoped finder + delete)

**Files:**
- Modify: `repository/MaterialBatchRepository.java`
- Modify: `repository/MaterialConsumptionRepository.java`
- Modify: `repository/ProductionReportRepository.java`

- [ ] **Step 1: 确认已存在的方法** (避免重复定义)

Run: `grep -nE "findByFactoryIdAndBatchId|findByFactoryIdAndSourceDocType|deleteByFactoryIdAndProductionBatchId|deleteByFactoryIdAndBatchId" backend/java/cretas-api/src/main/java/com/cretas/aims/repository/Material*Repository.java backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionReportRepository.java`
Expected: 列出已有的。`MaterialConsumptionRepository.findByFactoryIdAndBatchId` 审计称已存在 (line ~112) — 若有则跳过加它。

- [ ] **Step 2: 加缺失方法**

```java
// MaterialBatchRepository.java
Optional<MaterialBatch> findByFactoryIdAndSourceDocTypeAndSourceDocId(String factoryId, String sourceDocType, String sourceDocId);

// MaterialConsumptionRepository.java (若不存在)
List<MaterialConsumption> findByFactoryIdAndBatchId(String factoryId, String batchId);
@Modifying @Query("UPDATE MaterialConsumption c SET c.deletedAt = CURRENT_TIMESTAMP WHERE c.factoryId = :f AND c.productionBatchId = :pbId AND c.deletedAt IS NULL")
int softDeleteByFactoryIdAndProductionBatchId(@Param("f") String factoryId, @Param("pbId") Long productionBatchId);

// ProductionReportRepository.java
@Modifying @Query("UPDATE ProductionReport r SET r.deletedAt = CURRENT_TIMESTAMP WHERE r.factoryId = :f AND r.batchId = :bId AND r.deletedAt IS NULL")
int softDeleteByFactoryIdAndBatchId(@Param("f") String factoryId, @Param("bId") Long batchId);
```

> **NOTE**: `MaterialConsumption` / `ProductionReport` 有 `@Where(deleted_at IS NULL)` 软删除 — 用软删 (UPDATE deleted_at) 不是 hard DELETE,保持血缘可审计。确认实体有 `deletedAt` 字段 (BaseEntity)。

- [ ] **Step 3: 编译 + commit**

Run: `mvn -q -o compile` → SUCCESS
```bash
git commit -m "feat(SP-F): factory-scoped finder + soft-delete repo methods" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/MaterialBatchRepository.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/MaterialConsumptionRepository.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionReportRepository.java
```

### Task 1.3: 抽取 `materializeBatch` — 重构 recordChain (行为不变)

**这是 keystone 重构。目标: 把内循环 (现 L139-244) 的「写一个批次」逻辑抽成共享 helper,上游边/ctx 由调用方预解析。recordChain 现有行为与测试必须保持绿。**

**Files:**
- Create: `dto/processentry/ResolvedEdge.java`, `dto/processentry/MaterializeContext.java`
- Modify: `service/processentry/impl/ClerkProcessEntryServiceImpl.java`

- [ ] **Step 1: 跑现有 recordChain 测试,记录基线绿**

Run: `mvn -q -o test -Dtest=ClerkProcessEntryServiceImplTest,ClerkProcessEntryIntegrationTest`
Expected: PASS (这是重构的回归护栏)

- [ ] **Step 2: 定义 helper 入参 DTO**

```java
// ResolvedEdge.java — 一条已解析的上游消耗边
@Data @AllArgsConstructor public class ResolvedEdge {
    private MaterialBatch sourceBatch;   // RAW 原料批 或 上游 WIP 批 (已 factory-scoped 查到)
    private BigDecimal feedQuantityKg;
    private String sourceType;           // "RAW_MATERIAL" | "SEMI_FINISHED"
}
// MaterializeContext.java — 一个批次物化所需的预解析上下文
@Data @AllArgsConstructor public class MaterializeContext {
    private String factoryId;
    private Long planId;                 // null for WIP batch
    private String productTypeId;
    private String batchNumber;          // 可空: 由 createProductionBatch 生成
    private boolean finished;
    private BigDecimal laborRate;
    private String warehouseId;
    private String rawMaterialTypeId;    // 非空 (WIP FK); RAW 取首原料 type, 纯混锅取上游 WIP type
    private Long userId;
}
```

- [ ] **Step 3: 抽 `materializeBatch` 方法** (把内循环的「单批次写」逻辑搬进来,上游不再读 `wipMbIdByKey`,改用入参 `List<ResolvedEdge>`)

签名:
```java
/** 物化一个批次: ProductionBatch + raw/SEMI MaterialConsumption + SEASONING/labor 报工 + WIP MaterialBatch。
 *  上游边与 ctx 由调用方预解析 (whole-chain 从内存 map,per-row 从持久 batchNumber)。
 *  @return MaterializedBatch(productionBatchId, batchNumber, wipMaterialBatchId, rowTotalCost) */
MaterializedBatch materializeBatch(MaterializeContext ctx, StepEntry step, List<ResolvedEdge> edges);
```
逻辑 (搬自现内循环,关键点):
1. `createProductionBatch(ctx)` → batchId (`batchType = ctx.finished?"REGULAR":"CLERK_WIP"`, `productionPlanId = ctx.finished?planId:null`)。
2. 对每个 `ResolvedEdge e`: `writeConsumption(factoryId, batchId, e.sourceBatch.id, e.feedQuantityKg, e.sourceBatch.unitPrice, e.sourceType, userId, ...)` — `totalCost = feedKg × e.sourceBatch.unitPrice`。
3. 若 `step.seasoningStep`: `seasoningCost = computeSeasoningCost(...)`; `writeSeasoningReport(... costCategory="SEASONING" ...)` (**显式 SEASONING**)。
4. `laborCost = computeLaborCost(step.laborSegments, ctx.laborRate)` (Task 1.4 的新 wrapper);累加进 `batchTotalCost = Σedge totalCost + seasoningCost + laborCost`。
5. 若 `!ctx.finished && step.outputQuantity.signum() > 0`: `createWipMaterialBatch(factoryId, batchId, ctx.rawMaterialTypeId, outputQty, unitPrice = batchTotalCost/outputQty, warehouseId)` → wipMbId。
6. 返回 `MaterializedBatch`。

- [ ] **Step 4: 改 recordChain 内循环调 materializeBatch**

在 recordChain 里,对每个 BatchEntry: 先把 `upstreamSources` 经内存 `wipMbIdByKey` 解析成 `List<ResolvedEdge>` (RAW 从 rawMaterialInputs 解析),构造 `MaterializeContext` (rawMaterialTypeId = firstRawMaterialTypeId 现逻辑),调 `materializeBatch`,再把结果 `wipMbIdByKey.put(clientBatchKey, mat.wipMaterialBatchId)`。**保持 topo 预排序**。

- [ ] **Step 5: 跑回归测试 — 必须仍绿**

Run: `mvn -q -o test -Dtest=ClerkProcessEntryServiceImplTest,ClerkProcessEntryIntegrationTest`
Expected: PASS (行为不变。若挂,对比 materializeBatch 与原内循环的逐行差异)

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor(SP-F): extract materializeBatch from recordChain (behavior-preserving, edges/ctx pre-resolved)" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ClerkProcessEntryServiceImpl.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/dto/processentry/ResolvedEdge.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/dto/processentry/MaterializeContext.java
```

### Task 1.4: labor Σ-segments wrapper

**Files:** Modify `ClerkProcessEntryServiceImpl.java`

- [ ] **Step 1: 写失败测试** (`ClerkProcessEntryServiceImplTest`)

```java
@Test void computeLaborCost_sumsMultipleSegments() {
    var segs = List.of(seg("08:00","10:00",2), seg("13:00","14:00",3)); // 2h×2 + 1h×3 = 7 工时
    BigDecimal rate = new BigDecimal("26");
    assertThat(svc.computeLaborCost(segs, rate)).isEqualByComparingTo("182.00"); // 7×26
}
```

- [ ] **Step 2: 跑验证 FAIL** — Run: `mvn -q -o test -Dtest=ClerkProcessEntryServiceImplTest#computeLaborCost_sumsMultipleSegments` → FAIL (方法不存在)

- [ ] **Step 3: 加 wrapper**

```java
BigDecimal computeLaborCost(List<LaborSegment> segs, BigDecimal rate) {
    if (segs == null) return BigDecimal.ZERO;
    BigDecimal hours = segs.stream()
        .map(s -> minutesBetween(s.getStartTime(), s.getEndTime())
            .multiply(BigDecimal.valueOf(s.getWorkerCount() == null ? 0 : s.getWorkerCount()))
            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return hours.multiply(rate).setScale(2, RoundingMode.HALF_UP);
}
```

- [ ] **Step 4: 跑 PASS** + **Step 5: Commit** (`feat(SP-F): labor cost sum over segments`)

### Task 1.5: `ProcessSheetService.saveRow` — 新建路径

**Files:** Create DTOs + `ProcessSheetService` + `impl` + `MaterializedBatch` 返回类型。

- [ ] **Step 1: 写 DTO** (`ProcessSheetRowRequest`/`Result` 按 spec §4.2/§4.3 完整字段;不带 byproducts/sampleRetain)

- [ ] **Step 2: 写失败集成测试** (`ProcessSheetIntegrationTest`, 真 PG profile)

```java
@Test void saveRow_xiuyou_writesRawConsumptionAndWipBatch() {
    var req = xiuyouReq(rawBatchId, outQty("100"), labor("08:00","12:00",4));
    var res = svc.saveRow(FACTORY, PLAN, req, USER);
    assertThat(res.isMaterialized()).isTrue();
    assertThat(res.getBatchNumber()).startsWith("CLK-W-");
    // RAW consumption 写入 + 原料批扣减
    var cons = consumptionRepo.findByFactoryIdAndProductionBatchId(FACTORY, res.getBatchId());
    assertThat(cons).hasSize(1);
    assertThat(cons.get(0).getSourceType()).isEqualTo("RAW_MATERIAL");
    // WIP 批 unitPrice = 原料成本/产出, materialTypeId 来自原料
    var wip = batchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(FACTORY,"PRODUCTION_BATCH",res.getBatchId().toString()).orElseThrow();
    assertThat(wip.getMaterialTypeId()).isEqualTo(rawMaterialTypeId);
    assertThat(wip.getBatchType()).isEqualTo("CLERK_WIP");
}
```

- [ ] **Step 3: 跑 FAIL** — Run: `mvn -q -o test -Dtest=ProcessSheetIntegrationTest#saveRow_xiuyou_writesRawConsumptionAndWipBatch`

- [ ] **Step 4: 实现 saveRow 新建路径** (`@Transactional`)

```java
@Transactional
public ProcessSheetRowResult saveRow(String factoryId, Long planId, ProcessSheetRowRequest req, Long userId) {
    // 1. plan 归属工厂守卫
    planRepo.findByIdAndFactoryId(planId, factoryId).orElseThrow(() -> new BusinessException(403, "无权访问该计划"));
    var existing = rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(factoryId, String.valueOf(planId), req.getProcessCode(), req.getClientRowId());
    if (existing.isPresent()) return resaveRow(factoryId, planId, req, userId, existing.get()); // Task 1.6
    // 2. 解析上游边 (factory-scoped)
    List<ResolvedEdge> edges = resolveEdges(factoryId, req);   // RAW from rawMaterialInputs + SEMI from upstreamSources(batchNumber)
    // 3. ctx: rawMaterialTypeId = 首原料 type, 否则上游 WIP type; 非空断言
    String rawTypeId = resolveRawMaterialTypeId(edges); // throw 400 if null
    var ctx = new MaterializeContext(factoryId, req.isFinished()?planId:null, req.getProductTypeId(), null,
        req.isFinished(), resolveLaborRate(factoryId), resolveWarehouseId(factoryId), rawTypeId, userId);
    // 4. outputQty<=0 → DRAFT 不物化
    ProcessSheetRow row = new ProcessSheetRow(); /* fill keys + payload JSON */
    if (req.getOutputQuantity().signum() <= 0) { row.setRowStatus("DRAFT"); rowRepo.save(row); return draftResult(req); }
    // 5. 物化
    var mat = clerkSvc.materializeBatch(ctx, toStep(req), edges);
    row.setBatchId(mat.getProductionBatchId()); row.setBatchNumber(mat.getBatchNumber()); row.setRowStatus("SAVED");
    try { rowRepo.saveAndFlush(row); }
    catch (DataIntegrityViolationException e) { throw new BusinessException(409, "该行已存在 (并发提交)"); } // Task 1.7
    return result(req, mat, false);
}
```

`resolveEdges`: for each `upstreamSources[i]` → `productionBatchRepo.findByFactoryIdAndBatchNumber(factoryId, bn)` → `batchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(factoryId,"PRODUCTION_BATCH", pb.id)` → assert `srcMb.factoryId==factoryId` → `ResolvedEdge(srcMb, feedKg, "SEMI_FINISHED")`; for each `rawMaterialInputs[i]` → `batchRepo.findByIdAndFactoryId(mbId, factoryId)` → `ResolvedEdge(rawMb, qty, "RAW_MATERIAL")`. 任一找不到 → 409「上游/原料批次 X 不存在」。

`resolveRawMaterialTypeId`: 优先 edges 中 RAW 的 `sourceBatch.materialTypeId`;否则 SEMI edges 的 `sourceBatch.materialTypeId`;全空 → `throw new BusinessException(400,"无法确定原料类型")`。

- [ ] **Step 5: 跑 PASS** (xiuyou test) + 加焯水/熟制混锅 test (SEMI edge + SEASONING 桶)

```java
@Test void saveRow_shuzhi_mixedPots_writesSemiEdgesAndSeasoningBucket() { /* 选 2 焯水批 + seasoningStep=true → 2 SEMI consumption + SEASONING report, materialTypeId 来自上游 WIP */ }
```

- [ ] **Step 6: Commit** (`feat(SP-F): ProcessSheetService.saveRow create path (@Transactional, factory-scoped, FK-from-upstream)`)

### Task 1.6: re-save update-in-place (保 id)

- [ ] **Step 1: 写失败测试**

```java
@Test void resave_noDownstream_updatesInPlacePreservingIds() {
    var r1 = svc.saveRow(F, PLAN, chaoshuiReq("80"), USER);
    var r2 = svc.saveRow(F, PLAN, chaoshuiReqSameRow("90"), USER); // 改产出 80→90, 无下游
    assertThat(r2.isUpdated()).isTrue();
    assertThat(r2.getBatchId()).isEqualTo(r1.getBatchId());           // id 保持
    // 旧边软删, 新边写入 (只 1 条有效 SEMI edge)
    assertThat(consumptionRepo.findByFactoryIdAndProductionBatchId(F, r1.getBatchId())).hasSize(1);
}
@Test void resave_withDownstreamConsumed_throws409() {
    var up = svc.saveRow(F, PLAN, chaoshuiReq("80"), USER);
    svc.saveRow(F, PLAN, shuzhiReqConsuming(up.getBatchNumber(), "50"), USER); // 下游消耗 up
    assertThatThrownBy(() -> svc.saveRow(F, PLAN, chaoshuiReqSameRow("90"), USER))
        .isInstanceOf(BusinessException.class).hasMessageContaining("已被下游");
}
```

- [ ] **Step 2: FAIL** → **Step 3: 实现 `resaveRow`**

```java
private ProcessSheetRowResult resaveRow(String f, Long plan, ProcessSheetRowRequest req, Long user, ProcessSheetRow row) {
    Long pbId = row.getBatchId();
    if (pbId != null) {
        var wip = batchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(f,"PRODUCTION_BATCH",pbId.toString()).orElse(null);
        if (wip != null) {
            var downstream = consumptionRepo.findByFactoryIdAndBatchId(f, wip.getId()); // 谁消耗了这个 WIP
            if (!downstream.isEmpty()) throw new BusinessException(409, "该批已被下游 " + downstream.size() + " 行消耗,请先删除下游行再改");
        }
        // 删旧边/报工 (按 productionBatchId), 保 ProductionBatch + WIP id
        consumptionRepo.softDeleteByFactoryIdAndProductionBatchId(f, pbId);
        reportRepo.softDeleteByFactoryIdAndBatchId(f, pbId);
        // 重写边/报工 + 更新 WIP receiptQty/unitPrice (in place, 不新建)
        rematerializeInPlace(f, pbId, wip, req, user);
    }
    row.setRowPayload(toJson(req)); rowRepo.save(row);
    return result(req, /* updated */ true);
}
```
`rematerializeInPlace`: 解析 edges + ctx (同 saveRow),重写 consumption/report (productionBatchId=pbId),重算 batchTotalCost,`wip.setReceiptQuantity(outputQty); wip.setUnitPrice(total/outputQty); batchRepo.save(wip)`。**不调 createProductionBatch/createWipMaterialBatch** (保 id)。

- [ ] **Step 4: PASS** + **Step 5: Commit** (`feat(SP-F): re-save update-in-place with downstream-consumed guard`)

### Task 1.7: 并发 UK 冲突 → 409 (已在 1.5 catch;补测试)

- [ ] **Step 1: 测试** — 两线程同 clientRowId saveRow,一成一 409,无孤儿 (事务回滚)。
```java
@Test void concurrentSaveRow_sameClientRowId_oneSucceedsOneThrows409_noOrphan() { /* CompletableFuture×2; 断言成功后只 1 ProductionBatch */ }
```
- [ ] **Step 2: FAIL/调整** (确保 materialize+row-save 同事务,loser 回滚) → **Step 3: PASS** → **Step 4: Commit**

### Task 1.8: 删除行端点

- [ ] **Step 1: 测试** — delete 无下游 → 全产物软删 + row 删;有下游 → 409。
- [ ] **Step 2: 实现** `deleteRow(f, plan, clientRowId)` `@Transactional`: 查 row → guard downstream (findByFactoryIdAndBatchId) → 软删 consumption(by pbId)+report(by pbId)+WIP batch+ProductionBatch → 软删 row。
- [ ] **Step 3: PASS** → **Step 4: Commit**

### Task 1.9: Controller 接线

**Files:** Create `ProcessSheetController.java`

- [ ] **Step 1: 写 controller**

```java
@RestController
@RequestMapping("/api/mobile/{factoryId}/production-plans/{planId}/process-sheet")
@RequiredArgsConstructor
public class ProcessSheetController {
    private final ProcessSheetService service;
    @RequirePermission({"production:read_write"}) @PostMapping("/row")
    public ApiResponse<ProcessSheetRowResult> saveRow(@PathVariable String factoryId, @PathVariable Long planId,
        @RequestAttribute("userId") Long userId, @Valid @RequestBody ProcessSheetRowRequest req) {
        return ApiResponse.success("保存成功", service.saveRow(factoryId, planId, req, userId));
    }
    @RequirePermission({"production:read_write"}) @DeleteMapping("/row/{clientRowId}")
    public ApiResponse<Void> deleteRow(@PathVariable String factoryId, @PathVariable Long planId, @PathVariable String clientRowId) {
        service.deleteRow(factoryId, planId, clientRowId); return ApiResponse.success("删除成功", null);
    }
}
```

- [ ] **Step 2: 跨租户测试** (别工厂 planId → 403;混锅引用别工厂 batchNumber → 404/403) → **Step 3: PASS** → **Step 4: Commit**

---

## Phase 2 — F2 (Sonnet in-harness): 读端点

### Task 2.1: 半成品库存端点

**Files:** Modify `ProcessSheetService`/`impl` + `ProcessSheetController`

- [ ] **Step 1: 测试** — 种 修油→焯水 后,GET inventory?process=xiuyou → 修油 WIP `produced/used/remaining` 正确 (used=焯水消耗量),`remaining>0` ACTIVE。
```java
@Test void inventory_derivesProducedUsedRemaining_scopedToPlanViaSheetRows() { ... }
```
- [ ] **Step 2: 实现** `getInventory(f, plan, processCode)`:
```
rows = rowRepo.findByFactoryIdAndPlanIdAndProcessCode(f, plan, processCode); // 经 sheet 表限范围 (WIP planId=null)
for each row.batchId → ProductionBatch → WIP MaterialBatch (findByFactoryIdAndSourceDocTypeAndSourceDocId):
  produced = wip.receiptQuantity
  used = Σ consumptionRepo.findByFactoryIdAndBatchId(f, wip.id).quantity   // factory-scoped 🔒
  remaining = produced - used; status = remaining<=0?DEPLETED:ACTIVE
```
返回 `List<InventoryItemDTO>`。endpoint `@RequirePermission({"production:read"}) GET /inventory`。
- [ ] **Step 3: PASS** → **Step 4: Commit**

### Task 2.2: 行回读端点

- [ ] **Step 1: 测试** — GET /rows?process=xiuyou 返回已存行 payload (原样)。
- [ ] **Step 2: 实现** `getRows(f, plan, processCode)` → 反序列化 row_payload。`GET /rows`。
- [ ] **Step 3: PASS** → **Step 4: Commit**

---

## Phase 3 — F3 (Sonnet/Composer): 前端 `<ProcessSheet>`

> Out-of-harness 若用 Composer,brief 须内联: API 路径 `/api/mobile/{factoryId}/production-plans/{planId}/process-sheet/{row,inventory,rows}`;响应 `{success,data,message}` 解析;成本/剩余**只读取后端**;headed E2E 规则。

### Task 3.1: 配置 + 类型 + API client

**Files:** Create `processSheet/PROCESS_SHEET_CONFIG.ts`, `api/processSheet.ts`

- [ ] **Step 1: 写 `PROCESS_SHEET_CONFIG.ts`** (修油/焯水/熟制,按 spec §6.2,含 `ColType`/`AutoCalc` 可扩展 enum)。
- [ ] **Step 2: 写 `api/processSheet.ts`** (`saveRow`/`deleteRow`/`getInventory`/`getRows`,axios,解析 `response.success`)。
- [ ] **Step 3: 组件单测** — config 渲染列数正确 (修油 8 列 / 焯水 8 列 / 熟制 8 列)。
- [ ] **Step 4: Commit**

### Task 3.2: `<ProcessSheet>` 三联表骨架

- [ ] **Step 1: 测试** — mount `<ProcessSheet process="chaoshui">`,渲染数据录入/工时/库存 3 张表 header。
- [ ] **Step 2: 实现** ProcessSheet.vue + DataTable/WorkHoursTable/InventoryTable 子组件 (config 驱动列;工时多时段 Σ;库存读 getInventory)。
- [ ] **Step 3: PASS** → **Step 4: Commit**

### Task 3.3: 上游下拉 (混锅) + 派生剩余

- [ ] **Step 1: 测试** — 上游 dropdown 只列 `materialized && remaining>0`;熟制多选 `+来源批` → upstreamSources[];剩余列只读派生 (非手填)。
- [ ] **Step 2: 实现** dropdown (读 getInventory),混锅多源行,`remain` 列 readonly 从 inventory 派生。
- [ ] **Step 3: PASS** → **Step 4: Commit**

### Task 3.4: 自动算 + 超量软预警 + 保存

- [ ] **Step 1: 测试** — 出成率=产出/投入;总工时=Σ;feedKg>剩余 → 黄字软预警 (不阻断);保存调 saveRow 乐观更新。
- [ ] **Step 2: 实现**。
- [ ] **Step 3: PASS** → **Step 4: Commit**

### Task 3.5: 替换 drawer 入口

**Files:** Modify `production/plans/list.vue`

- [ ] **Step 1: 找现 drawer 触发** — Run: `grep -n "ProcessChainEntryDrawer\|submitProcessChain" web-admin/src/views/production/plans/list.vue`
- [ ] **Step 2: 替换**「进入生产」/逐道录入触发为打开 `<ProcessSheet>` (移除 drawer import + 用法)。
- [ ] **Step 3: 构建验证** — Run: `cd web-admin && npm run build` → SUCCESS
- [ ] **Step 4: Commit**

---

## Phase 4 — F4 (Sonnet/Composer): 3 UI 修复

### Task 4.1: 多锅 N>1 逐锅 kg (UX + 校验)
- [ ] Step 1 测试: potCount=2 → 预展示 2 个逐锅 kg 输入;缺填 → 提交 disabled + 后端 400。
- [ ] Step 2 实现: 前端 N>1 时 render N 个 potRawKgs 输入 (fool-proof Rule1 预展示);后端 `materializeBatch`/saveRow 守卫 N>1 缺 potRawKgs → 400。
- [ ] Step 3 PASS → Step 4 Commit

### Task 4.2: 提交 :disabled
- [ ] Step 1 测试: overLimit||invalid 时提交按钮 disabled。
- [ ] Step 2 实现: `:disabled="overLimit || invalid"`。
- [ ] Step 3 PASS → Step 4 Commit

### Task 4.3: 配方老汤豁免价格
- [ ] Step 1 测试: `count_in_seasoning=false` 行不强制价格 (validate 通过)。
- [ ] Step 2 实现: validate 跳过老汤行价格必填 (前端 + RecipeCostCalculator 校验路径)。
- [ ] Step 3 PASS → Step 4 Commit

---

## Phase 5 — F5 (Sonnet in-harness): 集成测 + E2E

### Task 5.1: 后端集成测补全 (真 PG)
- [ ] 跑全部 Phase 1-2 集成测 against 真 PG (非 H2): `mvn -o test -Dtest=ProcessSheetIntegrationTest -Dspring.profiles.active=pg-test`。
- [ ] **SP-E FK 专测**: 熟制行 materialTypeId 来自上游 WIP,真 PG FK 非空通过。
- [ ] re-save 边清理后 inventory `used` 不重复计。
- [ ] Commit

### Task 5.2: E2E headed (DEMO_FACTORY)
- [ ] **Step 1**: 写 spec (headed config: `headless:false`, viewport 1920×1080, `--lang=zh-CN`, `PLAYWRIGHT_PORT`/`CHAT_ID` 隔离)。
- [ ] **Step 2**: 流程 df_admin/123456 → 计划 → ProcessSheet 走 修油(选原料+产出)→焯水(选修油批)→熟制(混锅 2 焯水批+调料) → 截图三联表 + computeByBatch 成本核对吻合手算期望。
- [ ] **Step 3**: 跑 + 贴 Headed Mode Verification block。**绝不碰 F006/LIUSHANMEN**。
- [ ] **Step 4**: Commit

---

## Phase 6 — 终审 + 部署 (Opus 出货闸 🔒)
- [ ] `git diff origin/main...HEAD --stat` 确认 scope 干净 (无 sister 文件)。
- [ ] Flyway 重号复检 (§Task 1.1 Step 1 命令)。
- [ ] Opus 终审全 diff (成本图/跨租户/FK/事务)。
- [ ] PR → merge main → 从 main 部署 test → smoke → 部署 prod → 核对运行 jar 含修复。

---

## Self-Review (plan vs spec)

- ✅ §3 materializeBatch 重构 → Task 1.3 (behavior-preserving, edges/ctx 预解析)。
- ✅ §4.4 saveRow @Transactional / outputQty>0 gate / factory-scoped resolve / materialTypeId from upstream → Task 1.5。
- ✅ §4.4 re-save update-in-place + downstream guard → Task 1.6;并发 409 → Task 1.7;delete → Task 1.8。
- ✅ §4.5 labor Σ segments → Task 1.4;成本边 qty×上游单价 → Task 1.3 Step 3。
- ✅ §5 inventory (sheet-join 范围 + factory-scoped used) → Task 2.1。
- ✅ §6 ProcessSheet 配置/三联表/混锅/派生剩余/软预警 → Phase 3;§6.2 修油/焯水/熟制 config → Task 3.1。
- ✅ §7 切片成本核对 computeByBatch → Task 5.2 Step 2 (汇总页 defer,不建)。
- ✅ §8.3 真 PG FK 测 → Task 5.1。
- ✅ §9 三 UI 修复 → Phase 4。
- ✅ §10 表/repo 方法/Flyway 预检 → Task 1.1/1.2。
- ✅ §12/§13 隔离/终审/部署 → Phase 6。
- 类型一致性: `materializeBatch(ctx, step, edges)` / `ResolvedEdge` / `MaterializeContext` / `MaterializedBatch` 跨 Task 1.3/1.5/1.6 一致;`findByFactoryIdAndSourceDocTypeAndSourceDocId`/`findByFactoryIdAndBatchId`/`softDeleteByFactoryIdAndProductionBatchId` 跨 Task 1.2/1.5/1.6/2.1 一致。
- defer 项 (张权成本页/汇总页/操作记录/气调/Q1Q6/byproduct) 无对应 task ✓ (有意 out of scope)。
