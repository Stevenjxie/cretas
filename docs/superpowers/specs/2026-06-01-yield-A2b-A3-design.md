# 报工体系统一 — A2b 余料 carryover 自动结清 + A3 跨批料归因验证设计

**日期**: 2026-06-01
**状态**: 设计确认 — 待实施
**前置**: Phase A (PR #350/#354/#358) 已 merged main + prod LIVE。本文覆盖 A2b 和 A3 两个尚未实施的子需求。
**产品决策 (Steve 2026-06-01)**: A2b 采用**方案 B — materialBatchId 真实关联**。方案 C (报工侧自检代理) 降级为 fallback 注记，不作主路径。
**产品决策 (Steve 2026-06-01, Q6 补充)**: 一条 material-input 可关联 **1 个或多个** MaterialBatch (取决于产品配置)。设计采用 **`material_batch_refs` jsonb 数组**，镜像现有 `source_batch_refs` 模式 (非单 BIGINT 列)。1 个批次以长度为 1 的数组表示，N 个批次以 N 元素数组表示，天然向后兼容。

---

## §1 背景与现状

Phase A spec (§3.4) 定义每日结清两条触发路径:

| 路径 | 实现状态 |
|---|---|
| 人工标记: `POST /production/batches/{id}/settle-day` | **已实现** (`settleDay` in `YieldReportServiceImpl:206-230`) |
| 原料用完自动: "领料量全部消耗 → 自动结清" | **尚未实现** — 字段 `settled`/`settled_at` 已有，触发逻辑缺失 |

`carryover_quantity` 字段也已落地: 每次 `submitReport` 时系统计算 `上道总产出 − 本道投入` 写进当条 report (`computeCarryover` 方法)。这是 Phase A 定义的接缝 — 记录值但不进 WIP 库存 (Phase B 才升级为库存实体)。

---

## §2 A2b — 原料用完自动结清

### 2.1 "原料用完"信号: MaterialBatch.USED_UP

**代码验证结果 (READ-ONLY audit)**: `MaterialBatchServiceImpl` 在 **4 处**将 status 置为 `USED_UP`:

| 位置 | 方法 | 触发条件 |
|---|---|---|
| line ~599 | `adjustBatchQuantity` | 手工调整后 `newQuantity == 0` |
| line ~636 | `markBatchAsUsedUp` | 显式标记 (仓管员手动) |
| line ~718 | `consumeBatchQuantity` | 消耗后 `currentQuantity == 0` (自动消耗路径) |
| line ~1012 | (另一消耗方法) | `remainingQuantity == 0` |

> **注意**: draft 描述"3 处"，audit 实际数为 4 处 (两个消耗方法路径各自含 USED_UP 判断)。自动结清 hook 需要在**所有 4 处**之后触发，或在公共 `save` 后通过 repository 事件统一触发。

### 2.2 当前字段缺口 (代码验证确认)

**`MaterialInputRequest.java`** (4 字段，无 materialBatchId):
```java
private Long workProcessTaskId;
private BigDecimal warehouseOutQuantity;
private BigDecimal feedInQuantity;
private String inputUnit;
```

**`ProductionReport` entity** (无 `material_batch_id` 列，确认于 entity 全字段审计)。

**`recordMaterialInput` 方法** (YieldReportServiceImpl:144-168): 不记录 `materialBatchId`，是当前的核心缺口。

### 2.3 主方案: 方案 B — materialBatchId 真实关联

Steve 决策: **追踪 `materialBatchId` 到 material-input 路径**，使自动结清触发于真实 MaterialBatch 的 USED_UP 生命周期事件，不依赖报工侧代理比较。

#### 2.3.1 数据层改动

**新增字段**:

| 位置 | 改动 |
|---|---|
| `MaterialInputRequest.java` | 加 `private List<MaterialBatchRef> materialBatchRefs;` (可选, 仓管员领料时选择批次列表；1 个批次 = 1 元素列表) |
| `ProductionReport` entity | 加 jsonb 字段，镜像现有 `source_batch_refs` 模式 (见下) |
| 新 Flyway migration | `V20260901_04__add_material_batch_refs_to_production_reports.sql` (参考现有 V20260901 序列) |

**`MaterialBatchRef` (内嵌 DTO / 序列化到 jsonb)**:
```java
// dto/yield/MaterialBatchRef.java (新建, 也可用 Map<String, Object> 行内)
public class MaterialBatchRef {
    private Long materialBatchId;    // 关联 MaterialBatch.id
    private BigDecimal quantity;     // 本次从该批次领用量
    private String unit;             // 可选, 与 inputUnit 一致
}
```

**`ProductionReport` entity 新增字段** (镜像 `source_batch_refs` 的 `@Type(JsonType.class)` jsonb 模式):
```java
/** A2b: 领料关联原料批次列表 (1 或 N, 取决于产品配置)
 *  格式: [{"materialBatchId": Long, "quantity": Number, "unit": String|null}]
 *  镜像 source_batch_refs: 同用 @Type(JsonType.class) + columnDefinition="jsonb"
 */
@Type(JsonType.class)
@Column(name = "material_batch_refs", columnDefinition = "jsonb")
private List<Map<String, Object>> materialBatchRefs;
```

**migration 内容**:
```sql
-- V20260901_04__add_material_batch_refs_to_production_reports.sql
ALTER TABLE production_reports
    ADD COLUMN material_batch_refs jsonb;

-- GIN 索引支持 @> 包含查询 (查找包含特定 materialBatchId 的报工记录)
CREATE INDEX idx_pr_material_batch_refs ON production_reports
    USING gin (material_batch_refs jsonb_path_ops)
    WHERE material_batch_refs IS NOT NULL;

COMMENT ON COLUMN production_reports.material_batch_refs
    IS 'A2b: 领料关联原料批次列表 (jsonb); 格式 [{"materialBatchId":Long,"quantity":Number,"unit":String}]; 支持 1 或 N 批次';
```

> **为何用 jsonb 数组而非单 BIGINT 列**: Steve Q6 决策 — 1 个批次只是 N=1 的特例。jsonb 数组天然支持两种情况，无需加 join 表，且与现有 `source_batch_refs` 模式完全一致 (同用 `@Type(JsonType.class)`)。索引用 `jsonb_path_ops` GIN 支持 `@>` 包含查询，效率与 B-tree 索引相当 (适合本场景的 "找含某 materialBatchId 的报工" 查询)。

#### 2.3.2 recordMaterialInput 改动

`YieldReportServiceImpl.recordMaterialInput` (line 144) 在 builder 中加入:
```java
.materialBatchRefs(toMaterialBatchRefMaps(req.getMaterialBatchRefs()))   // A2b: 链接领料批次列表
```

辅助方法将 `List<MaterialBatchRef>` 转为 `List<Map<String, Object>>` (与 jsonb 序列化层一致):
```java
private List<Map<String, Object>> toMaterialBatchRefMaps(List<MaterialBatchRef> refs) {
    if (refs == null || refs.isEmpty()) return null;
    return refs.stream().map(r -> {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("materialBatchId", r.getMaterialBatchId());
        m.put("quantity", r.getQuantity());
        if (r.getUnit() != null) m.put("unit", r.getUnit());
        return m;
    }).collect(java.util.stream.Collectors.toList());
}
```

只有 `req.getMaterialBatchRefs() != null && !isEmpty()` 时才有关联。仓管员不填时 (`null`) 退化为只有人工结清路径 — 功能向后兼容。

**1 个批次的向后兼容写法** (调用方便利): 若调用方只传单个 `materialBatchId`，前端/RN 端包装为 1 元素列表即可:
```json
{ "materialBatchRefs": [{"materialBatchId": 123, "quantity": 520.0, "unit": "kg"}] }
```

#### 2.3.3 自动结清 hook: 触发时机与实现

**触发时机选择 (两种方案):**

**方案 B-1 (推荐)**: 在 `YieldReportServiceImpl.recordMaterialInput` 末尾，对 `materialBatchRefs` 列表中**每一个**已 USED_UP 的 materialBatchId 调 `checkAndAutoSettle`:
```java
// recordMaterialInput 完成保存后 — 遍历每个关联批次，各自独立检查
if (req.getMaterialBatchRefs() != null) {
    for (MaterialBatchRef ref : req.getMaterialBatchRefs()) {
        if (ref.getMaterialBatchId() != null) {
            checkAndAutoSettle(factoryId, batchId, ref.getMaterialBatchId());
        }
    }
}
```
`checkAndAutoSettle` 查询对应 MaterialBatch 的当前状态，若已是 USED_UP 则触发结清。

**N 批次部分耗尽语义 (关键边界)**:
- 每个 materialBatchId 独立检查 USED_UP，互不干扰。
- 若 `materialBatchRefs` 有 3 个批次，其中批次 A 已 USED_UP，批次 B/C 未用完 → 仅批次 A 的路径触发 `checkAndAutoSettle`。但此时生产报工记录是否结清，取决于 **B/C 也耗尽**或**人工调用 settle**。
- **设计选择**: `checkAndAutoSettle` 只在该 materialBatchId 的引用报工全部找到 + 所有引用该批次的 refs 对应的批次均 USED_UP 时才打 `settled=true`。实际实现: 查出该报工记录的 `material_batch_refs`，取出全部 `materialBatchId`，逐个查状态，**全部 USED_UP** 才结清。否则跳过 (等待剩余批次也 USED_UP 时再次触发)。
- 这确保了: 1 个批次 = 该批次 USED_UP 即结清；N 个批次 = **全部 USED_UP** 才结清 (any-vs-all 语义保守选 all，防止原料未耗尽的生产批次被误结清)。

**方案 B-2 (备选)**: 在 MaterialBatchServiceImpl 的 4 处 USED_UP 赋值后，注入 `YieldReportService` 调 autoSettle。需 `@Lazy` 注入防循环依赖 (per `.claude/rules/ai-intent-tool-skill-architecture.md`)。

**推荐方案 B-1 理由**: 触发入口单一，在已知 `batchId` 上下文的 `recordMaterialInput` 里发起，无循环依赖风险；缺点是仅在 recordMaterialInput 时检查，若 MaterialBatch 在 recordMaterialInput 之后才被标 USED_UP，需要一个补充 API (详见 §2.4)。

#### 2.3.4 checkAndAutoSettle 逻辑

新私有方法 (或独立 service 方法供测试):

```java
private void checkAndAutoSettle(String factoryId, Long batchId, Long materialBatchId) {
    // 1. 查触发批次状态
    Optional<MaterialBatch> batchOpt = materialBatchRepository.findById(String.valueOf(materialBatchId));
    if (batchOpt.isEmpty()) return;
    MaterialBatch mb = batchOpt.get();
    if (mb.getStatus() != MaterialBatchStatus.USED_UP
            && mb.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
        return;  // 触发批次未用完，不触发
    }
    // 2. 找到 material_batch_refs 包含此 materialBatchId 的所有未结清 YIELD 报工
    List<ProductionReport> candidates = reportRepo.findUnsettledYieldContainingMaterialBatch(
            factoryId, batchId, materialBatchId);
    if (candidates.isEmpty()) return;
    // 3. 对每条候选报工: 检查其 material_batch_refs 中所有 materialBatchId 是否全部 USED_UP
    //    全部 USED_UP → 结清; 任一未 USED_UP → 跳过 (等待该批次也用完时再次触发)
    LocalDateTime now = LocalDateTime.now();
    List<ProductionReport> toSettle = new ArrayList<>();
    for (ProductionReport r : candidates) {
        if (allRefsUsedUp(r.getMaterialBatchRefs())) {
            r.setSettled(true);
            r.setSettledAt(now);
            toSettle.add(r);
        }
    }
    if (!toSettle.isEmpty()) {
        reportRepo.saveAll(toSettle);
        log.info("A2b 自动结清: factoryId={}, batchId={}, triggerMaterialBatchId={}, settledCount={}",
                factoryId, batchId, materialBatchId, toSettle.size());
    }
}

/** 检查 materialBatchRefs 列表中所有 materialBatchId 是否全部 USED_UP */
private boolean allRefsUsedUp(List<Map<String, Object>> refs) {
    if (refs == null || refs.isEmpty()) return false;
    for (Map<String, Object> ref : refs) {
        Object mbIdObj = ref.get("materialBatchId");
        if (mbIdObj == null) continue;
        Long mbId = Long.valueOf(mbIdObj.toString());
        Optional<MaterialBatch> mb = materialBatchRepository.findById(String.valueOf(mbId));
        if (mb.isEmpty()) continue;  // 找不到批次视为忽略
        if (mb.get().getStatus() != MaterialBatchStatus.USED_UP
                && mb.get().getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
            return false;  // 至少一个未用完 → 不结清
        }
    }
    return true;
}
```

新增 repository 方法 (使用 native SQL 支持 jsonb `@>` 包含查询):
```java
// ProductionReportRepository
@Query(value = "SELECT * FROM production_reports r " +
               "WHERE r.factory_id = :factoryId " +
               "AND r.batch_id = :batchId " +
               "AND r.report_type = 'YIELD' " +
               "AND (r.settled IS NULL OR r.settled = false) " +
               "AND r.deleted_at IS NULL " +
               "AND r.material_batch_refs @> CAST(:refJson AS jsonb)",
       nativeQuery = true)
List<ProductionReport> findUnsettledYieldContainingMaterialBatch(
    @Param("factoryId") String factoryId,
    @Param("batchId") Long batchId,
    @Param("refJson") String refJson);  // 调用方传 "[{\"materialBatchId\":" + materialBatchId + "}]"
```

调用方包装 (Service 层):
```java
String refJson = "[{\"materialBatchId\":" + materialBatchId + "}]";
List<ProductionReport> candidates = reportRepo.findUnsettledYieldContainingMaterialBatch(
        factoryId, batchId, refJson);
```

> **GIN 索引**: migration 中已建 `idx_pr_material_batch_refs` (jsonb_path_ops GIN)，`@>` 查询会命中该索引，不需全表扫描。

#### 2.3.5 补充触发 API (materialBatch 先 USED_UP 的情况)

若仓库侧先把 MaterialBatch 标 USED_UP (通过 `markBatchAsUsedUp`)，但 recordMaterialInput 稍后才发生，B-1 的检查时机会错过。增加一个**主动触发端点**:

```
POST /api/mobile/{factoryId}/production/batches/{batchId}/auto-settle-by-material-batch
Body: { "materialBatchId": 123 }
```

Controller 调 `checkAndAutoSettle(factoryId, batchId, materialBatchId)`。此端点通过检查 `material_batch_refs @> [{"materialBatchId": 123}]` 找到关联报工，并评估该报工的**全部** materialBatchRefs 是否都已 USED_UP，才打结清标。供仓管员在标记批次用完后主动触发。这样两个时机都覆盖:
- 先 recordMaterialInput 后 USED_UP: recordMaterialInput 末尾 check 触发
- 先 USED_UP 后 recordMaterialInput: recordMaterialInput 末尾 check 触发 (此时已 USED_UP)
- 先 USED_UP，recordMaterialInput 并不一定发生: 主动端点触发

**N 批次场景下的主动触发**: 若 materialBatchRefs=[A,B,C]，仓管员需在 B 耗尽时手动触发一次 `auto-settle-by-material-batch` body `{materialBatchId: B的id}`。系统会检查 A/B/C 是否全部 USED_UP，全部满足才结清。

### 2.4 方案 C 降级注记 (fallback, 不作主路径)

若 `materialBatchId` 未提供 (仓管员未选批次)，可在 `settleDay` 调用时附带一个代理检查: 首道累计 `inputQuantity ≥ feedInQuantity` → 触发自动结清。此逻辑仅作 fallback，**不作主路径**，原因:
- 只检查首道投入 vs 领料量，不检查中道余料是否已消化完
- 无法关联具体物料批次，无法实现跨批场景的细粒度追踪

### 2.5 文件改动清单 (A2b)

| 文件 | 改动 | 说明 |
|---|---|---|
| `dto/yield/MaterialInputRequest.java` | 加 `List<MaterialBatchRef> materialBatchRefs` 字段 | 领料时传入批次列表 (1 或 N) |
| `dto/yield/MaterialBatchRef.java` (新建) | DTO: `{materialBatchId, quantity, unit}` | jsonb 数组元素类型 |
| `entity/ProductionReport.java` | 加 `@Type(JsonType.class) List<Map<String,Object>> materialBatchRefs` jsonb 字段 | 镜像 source_batch_refs 模式 |
| `V20260901_04__add_material_batch_refs_to_production_reports.sql` (新建) | `ADD COLUMN material_batch_refs jsonb` + GIN 索引 | Phase A migration 序列末位 |
| `YieldReportServiceImpl.java` | `recordMaterialInput` 加 jsonb 写入 + 新增 `checkAndAutoSettle` + `allRefsUsedUp` | |
| `ProductionReportRepository.java` | 新增 `findUnsettledYieldContainingMaterialBatch` (native SQL `@>` 查询) | GIN 索引命中 |
| `YieldReportController.java` | 新增 `auto-settle-by-material-batch` 端点 | 补充触发路径 |

---

## §3 A3 — 跨批料归因: 验证测试

### 3.1 现状 (数据层已实现，代码验证确认)

**代码验证结果**:
- `ProductionReport.source_batch_refs` (jsonb) 字段: 已在 entity line 183-186 确认
- `YieldCalculationServiceImpl.calculateSteps` (line 41-46): A3 跨批累加已实现:
  ```java
  // A3: 跨批带入计入当前道 input
  if (r.getSourceBatchRefs() != null) {
      for (Map<String, Object> ref : r.getSourceBatchRefs()) {
          Object q = ref.get("quantity_from_source");
          if (q != null) totalInput = totalInput.add(new BigDecimal(q.toString()));
      }
  }
  ```
- A3 路径从未用真实跨批数据测试，无专项测试用例

### 3.2 猪舌 WIP 金标准与 A3 测试场景说明

来自 `2026-05-31-报工体系统一-design.md §7`，**完整过程边界** (draft 未清晰区分导致数字跳跃):

| 阶段 | 数量 | 备注 |
|---|---|---|
| 领料出库 | 998 kg | `warehouseOutQuantity` — 从仓库取走 |
| 投料 (首道投入) | 998 kg (或 935.5 kg 扣损耗) | `feedInQuantity`/`inputQuantity` 道1 |
| 道1 焯水产出 | ~1126 kg | `output_quantity` 道1 (保水 > 投入) |
| 道2 投入 (次道领用量) | 520 kg | `input_quantity` 道2 — **只取了部分焯水料** |
| 道2 余料结转 | 606 kg | `carryover_quantity` 道2 = 1126 - 520 |
| 整批末道产出 | 382.08 kg | |
| 整批累计出成率 | **0.3828** | = 382.08 / 998 (金标准，基于首道出库量) |

**为什么道2 inputQty=520 而非 998**: 道2 当班只投入了焯水料的一部分 (520 kg)，剩余 606 kg 作为下一批次 (B批次) 或下一天道2 的引用原料。**A3 的跨批场景就是这个 606 kg 被 B 批次的道1 `sourceBatchRefs` 引用**。

A3 测试用例必须以 520 (非 998) 作为道2 investmentQty，否则无余料可供跨批引用，无法真实测试 A3 路径。

### 3.3 A3 集成测试方案

新建 `YieldCalculationCrossBatchTest.java`:
位置: `src/test/java/com/cretas/aims/service/yield/YieldCalculationCrossBatchTest.java`

**核心场景**: 批次 A 道1 焯水产出 1126 kg，道2 只用 520 kg，606 kg 余料结转给批次 B 引用。

```java
// Step 1: 批次 A 道1 (焯水) — 投入 998 kg, 保水产出 1126 kg
ProductionReport reportA1 = buildReport(batchA, taskA1, processOrder=1,
    inputQty=998, outputQty=1126, unit="kg");
// 期望 A 道1: carryover=null (首道无上道), yieldRate=1126/998=1.1283 (保水正常)

// Step 2: 批次 A 道2 — 从焯水料只取 520 kg
ProductionReport reportA2 = buildReport(batchA, taskA2, processOrder=2,
    inputQty=520, outputQty=382, unit="kg");
// 期望 A 道2: carryover=1126-520=606 (记录余料), yieldRate=382/520=0.7346

// Step 3: 批次 B 道1 — 自有 100 kg + 引用 A 批次余料 606 kg
ProductionReport reportB1 = buildReport(batchB, taskB1, processOrder=1,
    inputQty=100, outputQty=300, unit="kg",
    sourceBatchRefs=[{source_batch_id: batchA.id,
                      source_work_process_task_id: taskA2.id,
                      quantity_from_source: 606,
                      source_unit: "kg"}]);
// 期望 B 道1: totalInput = 100 + 606 = 706
// 期望 B 道1: yieldRate = 300 / 706 = 0.4249

// Step 4: 验证 getYield(batchB)
BatchYieldDTO dto = calcSvc.calculateBatchYield(List.of(reportB1), null);
StepYieldDTO step1 = dto.getSteps().get(0);
assert step1.getTotalInput().compareTo(new BigDecimal("706")) == 0;
assert step1.getYieldRate().compareTo(new BigDecimal("0.4249")) == 0;

// Step 5: 验证批次 A 不受批次 B 的 sourceBatchRefs 干扰 (回归)
BatchYieldDTO dtoA = calcSvc.calculateBatchYield(List.of(reportA1, reportA2), null);
// A 的出成率计算路径应不包含 B 的 sourceBatchRefs
assert dtoA.getCumulativeYieldRate() != null;
// 道2 carryover = 606 (上道1126 - 本道520)
assert dtoA.getSteps().get(1).getCarryover().compareTo(new BigDecimal("606")) == 0;
```

### 3.4 A3 测试用例清单

| 用例 | 输入 | 期望 | 验证点 |
|---|---|---|---|
| 跨批带入量计入 totalInput | B道1: inputQty=100, sourceBatchRefs=[{qty:606}] | totalInput=706 | A3 核心路径 |
| 跨批出成率正确 | B道1: output=300, totalInput=706 | yieldRate=0.4249 | 跨批计算 |
| 无跨批时不受影响 | sourceBatchRefs=null | totalInput=自有 input，路径不变 | 回归 |
| 金标准回归 (猪舌无跨批) | 998→道2 520→382.08 | cumulativeYieldRate=0.3828 | Phase A 回归 |
| A 批次余料 carryover 正确 | 道1输出 1126, 道2投入 520 | carryover=606 on 道2 report | 余料记录 |
| 多条 sourceBatchRef | 2 个来源批次 ref | totalInput=B.inputQty+ref1.qty+ref2.qty | 多来源累加 |

### 3.5 A3 UI 范围

当前 Phase E (web-admin 可视化) spec 已确认: "A3 跨批料归因的专门可视化 (数据层已支持，不做独立 UI)" — 维持不变。`getYield` response 的每个 step 的 `totalInput` 已隐含反映了跨批带入量。如未来审计需要明细展示，可在 `StepYieldDTO` 加 `sourceBatchContributions` 字段，当前 YAGNI。

---

## §4 Phase B/E 接缝 (carryover 跨批/跨日)

**Phase A 范围边界** (明确，不扩展):

| 当前 A2b/A3 | Phase B (WIP 库存) | Phase E (SmartBI 看板) |
|---|---|---|
| `carryover_quantity` 记值，不进库存 | `SemiFinishedInventory` 实体接管余料，升级为库存事务 | 出成率/余料趋势看板 |
| `source_batch_refs` jsonb 数据有 | — | 跨批追溯可视化 (若产品确认需要) |
| `material_batch_id` 关联字段 | 正式关联库存模型 | — |
| 自动结清打标 `settled=true` | 结清事件触发 WIP 入/出库 | 每日结清快照按天聚合 |

**跨日/跨批 carryover 进库存是 Phase B，不在本 spec**。该边界需要 Steve 明确产品确认 (Phase A 的接缝价值 = 记录值，Phase B 才让这些值驱动 WIP 库存扣减/转入)。

---

## §5 测试计划汇总

### 5.1 A2b 自动结清测试

| 测试 | 条件 | 期望 |
|---|---|---|
| 人工结清仍可用 | 调 `settle-day` | settled=true, settledAt 设置 (Phase A 已有，回归) |
| materialBatchRefs 写入 (单批次) | recordMaterialInput + materialBatchRefs=[{id:123, qty:520}] | ProductionReport.material_batch_refs=[{"materialBatchId":123,...}] 落库 |
| materialBatchRefs 写入 (多批次) | recordMaterialInput + materialBatchRefs=[{id:123,qty:300},{id:456,qty:220}] | 两个 ref 均在 jsonb 数组中 |
| 自动结清触发 (单批次 USED_UP) | materialBatchRefs=[{id:123}], 批次 123 USED_UP | settled=true (仅有 1 个 ref 且已 USED_UP) |
| N 批次全部 USED_UP 才结清 | materialBatchRefs=[{id:123},{id:456}], 123 先 USED_UP，456 未用完 | 触发 id=123 的 checkAndAutoSettle → allRefsUsedUp() 返 false → 不结清；456 USED_UP 时再次触发 → allRefsUsedUp() 返 true → 结清 |
| 已 USED_UP 时 recordMaterialInput | 批次已 USED_UP，此时 recordMaterialInput | recordMaterialInput 末尾 check 触发结清 |
| 未关联批次时不触发 | materialBatchRefs=null 或 empty | 不触发自动结清，人工 settle 仍可用 |
| 余料 carryover 正确算 | 道1输出 1126kg，道2投入 520kg | carryover=606 on 道2 report (已有，回归) |
| 自动结清不重复 | 已 settled 的 report 再次 check | settled 状态不变，settledAt 不覆盖 |
| 主动触发端点 | POST auto-settle-by-material-batch | 同自动触发路径结果 |

### 5.2 A3 跨批归因测试

测试文件: 新建 `YieldCalculationCrossBatchTest.java`

详见 §3.4 测试用例清单 (6 用例)。

---

## §6 文件清单汇总

**新建/改动**:

| 文件 | 类型 | 改动说明 | 关联子需求 |
|---|---|---|---|
| `dto/yield/MaterialInputRequest.java` | 改动 | 加 `List<MaterialBatchRef> materialBatchRefs` 字段 | A2b |
| `dto/yield/MaterialBatchRef.java` | 新建 | DTO: `{materialBatchId, quantity, unit}` | A2b |
| `entity/ProductionReport.java` | 改动 | 加 `@Type(JsonType.class) material_batch_refs jsonb` 字段 (镜像 source_batch_refs) | A2b |
| `migrations/V20260901_04__add_material_batch_refs_to_production_reports.sql` | 新建 | `ADD COLUMN material_batch_refs jsonb` + GIN 索引 | A2b |
| `service/yield/impl/YieldReportServiceImpl.java` | 改动 | `recordMaterialInput` 写 materialBatchRefs jsonb + `checkAndAutoSettle` + `allRefsUsedUp` 新方法 | A2b |
| `repository/ProductionReportRepository.java` | 改动 | 新增 `findUnsettledYieldContainingMaterialBatch` (native SQL `@>` 包含查询) | A2b |
| `controller/yield/YieldReportController.java` | 改动 | 新增 `auto-settle-by-material-batch` 端点 | A2b |
| `test/.../YieldCalculationCrossBatchTest.java` | 新建 | A3 跨批归因集成测试 6 用例 | A3 |

---

## §7 Open Questions (Steve 确认)

以下问题在产品决策确认后更新此 spec，决策前不动实现:

| # | 问题 | 背景 | 决策状态 |
|---|---|---|---|
| **Q3** | A3 主用途: 出成率归因 (数据层已做) / 成本分摊 (A5 范围) / 追溯审计 (需 UI)? 目前实施到测试层即够？ | 数据层已支持，UI 暂无 | 待确认 |
| **Q4** | 张权有没有明确要在界面上看到"B批次道1的300kg有606kg来自A批次"的跨批溯源细节？还是数据对了就行？ | Phase E spec 已排除 UI，若有需求升为子项 | 待确认 |
| **Q5** | A2b "余料 carryover 给下一天/下一批次" 的跨日/跨批场景 — Phase A 只记录值，Phase B 才做 WIP 库存实体。张权接受这个范围边界吗？ | Phase B 接缝，见 §4 | 待确认 |
| **Q6** | materialBatchId 关联的基数: 一条 materialInput 只关联一个 MaterialBatch (1:1)，还是可能一次领料跨多批次？ | ~~当前设计 1:1，若多批次需改为 jsonb 数组~~ | **✅ 已决策 (Steve 2026-06-01): 1 和 N 都支持，取决于产品配置。设计改为 `material_batch_refs` jsonb 数组 (1 个批次 = 1 元素数组, N 个批次 = N 元素数组)，镜像 `source_batch_refs` 模式。** |
| **Q7** | 跨批追踪颗粒度: 以 `entry-level` (每条 recordMaterialInput 都记 materialBatchRefs) 还是 `batch-level` (一个生产批次关联一个原料批次)? | 当前设计是 entry-level (每条 ProductionReport 记一份 materialBatchRefs) | **✅ 已决策 (Q6 推导): entry-level。每条 recordMaterialInput 记自己的 materialBatchRefs (1 或 N 元素)，颗粒度细，支持同一生产批次不同道次领不同原料批次的场景。** |
| **Q8** | 报废/呆废 + 中间库存数据结构 — 余料 606 kg 如何处理: 报废、留存、转下批? Phase A 只记录，Phase B 才有实体。这个 Phase A/B 边界张权接受吗? | Phase B 接缝 | 待确认，HARD RULE: **Phase B 前不静默扩展到此** |

---

## §8 OPEN QUESTION 驳回记录 (已由产品决策关闭)

| 草稿问题 | 关闭原因 |
|---|---|
| Q1: 方案 A/B/C 哪种? | **Steve 决策: 方案 B (materialBatchId 真实关联)**，Q1 关闭 |
| Q2: 自动结清方向 (原料批次用完→报工结清，还是反向)? | **Steve 决策: 方向 = MaterialBatch USED_UP → 触发报工记录 settled=true**，Q2 关闭 |

---

## §9 审计修订记录 (2026-06-01 READ-ONLY 代码 audit + 产品决策整合)

### Audit findings 与修订

| Finding | 级别 | 问题 | 修订位置 |
|---|---|---|---|
| CLAIM-1 (P0 gap) | P0 | 自动结清触发逻辑**完全未实现** — draft 描述正确，此处确认。这是 A2b 要 BUILD 的核心。 | §2.3.3 给出具体实现设计 (checkAndAutoSettle 方法 + hook 位置) |
| DESIGN-2 (P1) | P1 | 草稿方案 C "哪个批次触发" 的多批次歧义；Q6 追加: 1:1 还是 1:N | **由 Steve 产品决策消解 (两次)**: (1) 主路径为方案 B materialBatchId entry-level 追踪；(2) Q6 决策支持 1 或 N 批次 → 设计升级为 `material_batch_refs` jsonb 数组 (非单 BIGINT)，镜像 source_batch_refs 模式，auto-settle 采用"所有 refs 全部 USED_UP 才结清"语义，见 §2.3.1-2.3.5 |
| CLAIM-3 (确认) | 确认 | `recordMaterialInput` 不记录 materialBatchId — 草稿正确，此处代码 audit 确认 | §2.3.2 明确改动 |
| CLAIM-4 (确认) | 确认 | MaterialBatch USED_UP 存在且在 4 处触发 (草稿称 3 处，实际 4 处) | §2.1 修正为 4 处，hook 需覆盖全部 |
| CLAIM-2 (确认) | 确认 | A3 数据层已完整实现 (source_batch_refs + calculateSteps:42-46 + tests) | §3.1 确认；A3 只需测试 |
| DESIGN-4 (P2) | P2 | 草稿 A3 测试场景数字跳跃: 表格 998→1126→520→606 但测试代码 inputQty=520 无法从 998 直接理解 | §3.2 增加完整过程边界说明，区分"出库量 998"与"道2 投入量 520" |
| DESIGN-3 (P2) | P2 | carryover 跨日/跨批库存 = Phase B，边界不清晰 | §4 Phase B/E 接缝表，明确"Phase A 只记值不入库"，需 Steve 产品确认 |
| DESIGN-5 (P1) | P1 | 草稿遗漏 Q6/Q7/Q8 | §7 新增 Q6 (materialBatchId 基数)、Q7 (追踪颗粒度)、Q8 (报废/呆废 Phase 边界) |

### 代码 audit 结果汇总 (READ-ONLY)

| 验证项 | 结果 |
|---|---|
| `MaterialInputRequest.java` 无 materialBatchRefs | **确认** (4 字段，见 §2.2；A2b 需新增 `List<MaterialBatchRef> materialBatchRefs`) |
| `ProductionReport.java` 无 material_batch_refs 列 | **确认** (entity 全字段已读，无此列；A2b 新增 jsonb 字段镜像 source_batch_refs 模式) |
| MaterialBatch USED_UP 触发位置数 | **4 处** (draft 称 3 处，实际 4: adjustBatchQuantity:599 + markBatchAsUsedUp:636 + consumeBatchQuantity:718 + 第二消耗方法:1012) |
| `settleDay` 实现确认 | **已实现** (YieldReportServiceImpl:206-230，含 triggerComplete 标志) |
| A3 数据层实现确认 | **已实现** (source_batch_refs jsonb entity:183-186 + calculateSteps:42-46 累加 sourceBatchRefs) |
| A3 测试覆盖 | **缺失** — 无专项测试用例，需新建 `YieldCalculationCrossBatchTest.java` |
