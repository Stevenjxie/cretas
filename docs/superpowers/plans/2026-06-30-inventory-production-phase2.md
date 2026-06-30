# 库存生产核心 (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **🔒 RED-LINE**: 涉及 prod 库存写 + 扣减 + 多租户 + Flyway。执行者只做到 实现+自测+PR off origin/main; **不自部署 prod**。每个含库存写的 task 终审由 Opus(organizer)做。G3 小结 diff 必经独立 code-reviewer(Rule 8)。

**Goal:** 把"库存生产业态"落地 — 生产计划可选 BY_STOCK 永续模式;逐道录入「小结」分批入库(写 SemiFinishedInventory 半成品账 + FinishedGoodsBatch 成品账)+ 实时扣减原料(会话幂等);「停产」纯状态关闭。

**Architecture:** R2-A 桥接(逐道录入物化时追加写库存账)。新建逐道录入专用器(不复用 task/settlement-coupled 旧器)。库存生产**永不走 settleProduction**,小结独占扣减 → 双扣架构性消除。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JPA(Hibernate 6)+ PostgreSQL + Flyway;web-admin Vue 3 + Element Plus。

**前置阅读(执行者必看)**:
- spec `docs/plans/2026-06-30-inventory-production-line-spec.md`(Phase 2 节 + 顶部 5 个 🔒 陷阱)
- 设计审计结论(2026-06-30,本计划依据):见下方「审计已钉死的事实」
- 规则:`.claude/rules/database-entity-sync.md`(BaseEntity audit 字段 / PG CAST null)、`.claude/rules/server-operations.md`(⛔ Flyway 在 `db/flyway` 不是 `db/migration`)、`.claude/rules/concurrent-edit-safety.md`

---

## 审计已钉死的事实(file:line,执行者据此实现,勿重新假设)

| 事实 | 证据 |
|---|---|
| `ProductionPlan` 无 productionMode 字段(无冲突);enum 映射学 `sourceType` | `ProductionPlan.java:156-158` `@Enumerated(STRING) @Column(name="source_type",length=30)` |
| **Flyway 真路径 = `classpath:db/flyway`** (不是 db/migration) | `application-pg.properties:56`, `application-pg-prod.properties:63` |
| 列-add 迁移范本 + 版本必须 > `V20261027_18`(`out-of-order=false`) | `db/flyway/V20261017_01__production_plan_skip_process_reporting.sql:29-33` |
| 逐道录入 saveRow **无 plan-status 门控**(永续安全) | `ClerkProcessEntryServiceImpl.recordChain:106-120`(0 getStatus) |
| `upsertProducedWip` 私有 + 强依赖 WorkProcessTask → **不能复用** | `WipInventoryServiceImpl:674-680,339-354` |
| 可复用底层(task-free):`applyMovingAverageIn(sfi,inQty,inUnitCost,totalCost,unit,refs)` + `commitEmptySemiRow(placeholder)`(public REQUIRES_NEW)+ `findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull` | `WipInventoryServiceImpl:278-316,375-378`;`SemiFinishedInventoryRepository:41-46` |
| SemiFinishedInventory: `intermediateBatchNo` **NOT NULL + 分区唯一** `(factory_id,intermediate_batch_no) WHERE deleted_at IS NULL`;batchId/taskId/processOrder 可空 | `SemiFinishedInventory.java:38-51,85` |
| `createFinishedGoodsFromReceipt` 只用 settlement 的 factoryId+planNumber+批号派生 → **interim 版无需 settlement** | `ProductionPlanServiceImpl:2777-2832` |
| FinishedGoodsBatch 必填:factoryId / batchNumber(≤64,unique per factory)/ productTypeId / producedQuantity / unit / **warehouseId**(`warehouseResolver.resolveWorkshopId`)/ status;可空 productionPlanId | `FinishedGoodsBatch.java:34-36,80-83`;`ProductionPlanServiceImpl:2798-2819` |
| 逐道录入写 MaterialConsumption **不扣** source usedQuantity | `ClerkProcessEntryServiceImpl.writeConsumption:584-602` |
| 扣减范本(悲观锁):`materialBatchRepository.findByIdAndFactoryIdForUpdate(id,factoryId)` → `setUsedQuantity(used+qty)` | `MaterialBatchRepository:432-435`;`ProductionPlanServiceImpl:2658-2674` |
| **settle 从 `ProductionSettlementConsumption` 请求行扣,永不读 MaterialConsumption** → 库存生产不走 settle 即无双扣 | `ProductionPlanServiceImpl:1534-1537,2645-2673` |
| `completeProduction` 发 `BatchCompletedEvent` → 触发扣料+建成品(停产必须绕开) | `ProductionPlanServiceImpl:1459-1485` |
| MaterialConsumption 字段:productionPlanId/productionBatchId/batchId(source,NOT NULL)/quantity/materialTypeId/sourceType | `MaterialConsumption.java:41-47` |
| 端点位置范本(controller 已有 settle/complete/pause/resume) | `ProductionPlanController:453-468,366,656` |

**两个设计决定(本计划锁定)**:
1. **postClerkOutput 的 intermediateBatchNo**:稳定 per-(plan,productType) 码 `CLK-SEMI-{planId8}-{productTypeId8}`(planId/productTypeId 各取前 8 位,总长 ≤64),作 SemiFinishedInventory **运行余额行**的锚 —— 多次小结对同一半成品**累加**(moving-average in),不是每次新建行。幂等不靠这个码,靠 #2。
2. **会话幂等**:新建 `production_interim_settlement` 表(每次小结一条:plan_id/factory_id/session_seq/posted_at/posted_by/summary jsonb)+ 给 `material_consumptions` 加 `interim_settled_at TIMESTAMP NULL`。小结只处理 `interim_settled_at IS NULL` 的消耗行(扣完打戳),重复点找不到未结行 → 0 扣减,天然幂等。session_seq 供 `FG-{planNumber}-S{seq}` 编号。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `entity/enums/ProductionMode.java` | BY_ORDER/BY_STOCK 枚举 + null-safe fromString | Create |
| `entity/ProductionPlan.java` | +productionMode 字段 | Modify |
| `db/flyway/V20261027_19__production_plan_production_mode.sql` | production_mode 列 | Create |
| `db/flyway/V20261027_20__production_interim_settlement.sql` | 小结表 + material_consumptions.interim_settled_at | Create |
| `entity/production/ProductionInterimSettlement.java` | 小结记录实体(继承 BaseEntity) | Create |
| `repository/ProductionInterimSettlementRepository.java` | factory-scoped 查询 | Create |
| `service/wip/WipInventoryService(Impl).java` | +postClerkOutput(task-free SFI upsert) | Modify |
| `service/yield/InterimSettleService.java` + Impl | 小结编排:扣减+半成品入库+成品入库+记录 | Create |
| `service/ProductionPlanService(Impl).java` | +stopProduction(纯状态) | Modify |
| `controller/ProductionPlanController.java` | +interim-settle +stop-production | Modify |
| `web-admin/src/api/productionPlan.ts` | +productionMode +interimSettle/stopProduction API | Modify |
| `web-admin/src/views/production/plans/list.vue` + 创建表单 + 逐道录入抽屉 | 模式选择 + 小结/停产按钮(BY_STOCK 门控) | Modify |

---

## Task 1 — G2: ProductionMode 字段 + 迁移(最安全,可独立 ship)

**Files:** Create `entity/enums/ProductionMode.java`, `db/flyway/V20261027_19__production_plan_production_mode.sql`; Modify `entity/ProductionPlan.java`; Test `src/test/.../ProductionModeMigrationTest`(或在现有 plan service 测试加用例)。

- [ ] **Step 1: 写枚举**(学 `PlanSourceType.fromString` null-safe `:93-102`)
```java
package com.cretas.aims.entity.enums;
public enum ProductionMode {
    BY_ORDER, BY_STOCK;
    public static ProductionMode fromString(String s) {
        if (s == null || s.isBlank()) return BY_ORDER;
        try { return ProductionMode.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return BY_ORDER; }
    }
}
```

- [ ] **Step 2: 实体加字段**(`ProductionPlan.java`,学 sourceType `:156-158`)
```java
@Enumerated(EnumType.STRING)
@Column(name = "production_mode", length = 30)
private ProductionMode productionMode = ProductionMode.BY_ORDER;
```

- [ ] **Step 3: Flyway 迁移**(`db/flyway/V20261027_19__production_plan_production_mode.sql`,PG idempotent)
```sql
ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS production_mode VARCHAR(30) NOT NULL DEFAULT 'BY_ORDER';
COMMENT ON COLUMN production_plans.production_mode IS '生产业态: BY_ORDER 销售订单生产 / BY_STOCK 库存(永续)生产';
```
⚠️ 版本号必须排在 `V20261027_18` 之后(`out-of-order=false`);确认 `db/flyway/` 下没有更高版本号,有则顺延。

- [ ] **Step 4: create/list 透传**:建计划 DTO/请求加 productionMode(默认 BY_ORDER 向后兼容);plan 详情/列表响应带出 productionMode。grep 建计划入口(`ProductionPlanController` 的 create handler + 对应 request DTO),加字段透传。**写测试**:create 一个 BY_STOCK plan → 读回 productionMode==BY_STOCK;create 不传 → 默认 BY_ORDER。

- [ ] **Step 5: 构建 + 测试** `cd backend/java/cretas-api && mvn -q -o test -Dtest=*ProductionPlan*`(或新测试类)。全绿。

- [ ] **Step 6: Commit**(scoped)`git commit -m "feat(production): add productionMode BY_ORDER/BY_STOCK + flyway migration" -- <files>`

---

## Task 2 — 幂等地基:production_interim_settlement 表 + 实体

**Files:** Create `db/flyway/V20261027_20__production_interim_settlement.sql`, `entity/production/ProductionInterimSettlement.java`, `repository/ProductionInterimSettlementRepository.java`; Test repo 单测。

- [ ] **Step 1: 迁移**(BaseEntity audit 字段必带,见 database-entity-sync 规则)
```sql
CREATE TABLE IF NOT EXISTS production_interim_settlement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factory_id VARCHAR(50) NOT NULL,
    production_plan_id VARCHAR(50) NOT NULL,
    session_seq INT NOT NULL,
    posted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    posted_by BIGINT,
    summary JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_interim_plan_seq UNIQUE (factory_id, production_plan_id, session_seq)
);
CREATE INDEX IF NOT EXISTS idx_interim_plan ON production_interim_settlement(factory_id, production_plan_id) WHERE deleted_at IS NULL;
ALTER TABLE material_consumptions ADD COLUMN IF NOT EXISTS interim_settled_at TIMESTAMP NULL;
```

- [ ] **Step 2: 实体**(继承 BaseEntity;字段 factoryId/productionPlanId/sessionSeq/postedAt/postedBy/summary)。`summary` 用 `@JdbcTypeCode(SqlTypes.JSON)` 或现有 jsonb 映射范本(grep 现有 jsonb 字段如 SemiFinishedInventory.materialBatchRefs `:134-136`)。

- [ ] **Step 3: 仓库**(factory-scoped):`findTopByFactoryIdAndProductionPlanIdOrderBySessionSeqDesc(factoryId, planId)`(取上次 seq → 下次 seq=last+1,首次=1);`@Query` 加 `deleted_at IS NULL`。

- [ ] **Step 4: 单测**(@DataJpaTest):插两条 seq=1,2 → findTop 返 seq=2;跨 factory 隔离。`mvn -q -o test -Dtest=ProductionInterimSettlementRepositoryTest`。

- [ ] **Step 5: Commit** scoped。

---

## Task 3 — 🔒 G3 小结核心:postClerkOutput + createFinishedGoodsForInterim + 会话幂等扣减 + 端点

> 最高风险 task(三账一事务写 + 幂等 + 多租户)。diff 必经独立 code-reviewer(Rule 8)。每个写都 factory-scoped + 锁序(MaterialBatch findByIdAndFactoryIdForUpdate;SFI findForUpdate...;@Version)。

**Files:** Modify `WipInventoryServiceImpl`(+postClerkOutput);Create `InterimSettleService`+Impl;Modify `ProductionPlanController`(+interim-settle);Test `InterimSettleServiceTest`(@SpringBootTest 或 mock + 一个 @DataJpaTest 验扣减幂等)。

- [ ] **Step 1: postClerkOutput(task-free SFI upsert)** in `WipInventoryServiceImpl`
```java
/** 逐道录入半成品入库: 无 WorkProcessTask, 直接 upsert SemiFinishedInventory 运行余额行。 */
@Transactional
public void postClerkOutput(String factoryId, String intermediateBatchNo, String productTypeId,
        BigDecimal inQty, String unit, BigDecimal inUnitCost, List<?> materialBatchRefs) {
    if (inQty == null || inQty.signum() <= 0) return;
    var existing = semiFinishedInventoryRepository
        .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo);
    if (existing.isEmpty()) {
        SemiFinishedInventory placeholder = SemiFinishedInventory.builder()
            .factoryId(factoryId).intermediateBatchNo(intermediateBatchNo)
            .productTypeId(productTypeId).unit(unit)
            .producedQuantity(BigDecimal.ZERO).consumedQuantity(BigDecimal.ZERO).availableQuantity(BigDecimal.ZERO)
            .accumulatedCost(BigDecimal.ZERO).status("AVAILABLE").build();
        commitEmptySemiRow(placeholder);  // public REQUIRES_NEW, 并发安全
    }
    SemiFinishedInventory sfi = semiFinishedInventoryRepository
        .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
        .orElseThrow(() -> new IllegalStateException("SFI row missing after create: " + intermediateBatchNo));
    BigDecimal totalCost = inUnitCost == null ? null : inUnitCost.multiply(inQty);
    applyMovingAverageIn(sfi, inQty, inUnitCost, totalCost, unit, materialBatchRefs);  // :278-316
    semiFinishedInventoryRepository.save(sfi);
}
```
⚠️ 确认 `applyMovingAverageIn` / `commitEmptySemiRow` 的确切签名 + `SemiFinishedInventory.builder()` 字段名(读 `:278-316,375-378` + 实体);确认 intermediate_batch_no 列长 ≥64,`CLK-SEMI-{planId8}-{productTypeId8}` 超长则截断。inUnitCost 可空时按 honest-null(不假成 0)。

- [ ] **Step 2: 单测 postClerkOutput**:首次调 → 建行 producedQuantity=inQty;再调同码 → moving-average 累加(不新建行);跨 factory 同码隔离。

- [ ] **Step 3: createFinishedGoodsForInterim** in `InterimSettleServiceImpl`(不依赖 settlement)
```java
FinishedGoodsBatch fg = FinishedGoodsBatch.builder()
    .factoryId(factoryId)
    .batchNumber(truncate64("FG-" + planNumber + "-S" + sessionSeq))
    .productTypeId(productTypeId).producedQuantity(qty).unit(unit)
    .warehouseId(warehouseResolver.resolveWorkshopId(factoryId))   // WH-WKS, :2815
    .productionPlanId(planId).status("AVAILABLE")
    .shippedQuantity(BigDecimal.ZERO).reservedQuantity(BigDecimal.ZERO)
    .productionDate(LocalDate.now()).build();
finishedGoodsBatchRepository.save(fg);
```
⚠️ 读 `:2798-2819` 抄全必填字段 + `finishedGoodsBatchNumber` 的 64 截断逻辑(`:2822-2832`);保质期 expireDate 若产品有 shelfLife 则算。

- [ ] **Step 4: 会话幂等扣减** in `InterimSettleServiceImpl`
```java
List<MaterialConsumption> unposted = materialConsumptionRepository
    .findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(planId, factoryId);  // 新增仓库方法
for (MaterialConsumption mc : unposted) {
    MaterialBatch src = materialBatchRepository.findByIdAndFactoryIdForUpdate(mc.getBatchId(), factoryId)
        .orElseThrow(...);
    src.setUsedQuantity(zeroIfNull(src.getUsedQuantity()).add(mc.getQuantity()));  // :2658-2674 范本
    src.setLastUsedAt(LocalDateTime.now());
    if (src.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) src.setStatus(MaterialBatchStatus.USED_UP);
    materialBatchRepository.save(src);
    mc.setInterimSettledAt(LocalDateTime.now());
    materialConsumptionRepository.save(mc);
}
```
重复点小结 → unposted 为空 → 0 扣减(幂等)。

- [ ] **Step 5: 编排 interimSettle(planId)**:校验 plan.productionMode==BY_STOCK(否则 400);取 sessionSeq=last+1;扣减(Step4)+ 半成品入库(Step1,对各 WIP 道未结产出)+ 成品入库(Step3,对各成品道未结产出)+ 存 ProductionInterimSettlement(summary 记 deducted/semiIn/finishedIn);**不** setStatus(COMPLETED)。整体 `@Transactional`。
   - ⚠️ "未结产出"如何界定:对齐 Step4 的 interim 标记思路 —— 产出侧(半成品/成品)也只 post 自上次小结后新增的量。最简:小结时按 process_sheet_rows 当前累计产出 − 上次 summary 记录的累计 = delta,post delta。delta≤0 跳过。实现者按此 delta 模型,summary jsonb 存每半成品/成品的累计已 post 量。

- [ ] **Step 6: 端点** `ProductionPlanController`(学 settle handler `:453-468`)
```java
@PostMapping("/{planId}/interim-settle")
@RequirePermission({"production:read_write","scheduling:read_write"})
@RequireModule("production_plan")
public ApiResponse<?> interimSettle(@PathVariable String factoryId, @PathVariable String planId,
        @RequestHeader("Authorization") String auth) {
    Long userId = jwtUtil.extractUserId(auth);  // 抄 settle 取 userId 方式
    return ApiResponse.success(interimSettleService.interimSettle(factoryId, planId, userId));
}
```

- [ ] **Step 7: 测试**:@DataJpaTest 验扣减幂等(两次小结只扣一次);mock/service 测试验 BY_ORDER plan 调 interim-settle → 400;半成品/成品入库 delta 正确。`mvn -q -o test -Dtest=InterimSettleServiceTest`。跑 `*ProcessSheet* *Wip*` 回归不破。

- [ ] **Step 8: Commit** scoped。**标记 DONE_WITH_CONCERNS 若 delta 产出界定有不确定**,交 organizer 终审 + 独立 reviewer。

---

## Task 4 — G3b 停产:纯状态关闭(绕开扣料/建成品)

**Files:** Modify `ProductionPlanServiceImpl`(+stopProduction)、`ProductionPlanController`(+stop-production);Test。

- [ ] **Step 1: stopProduction(纯状态)** —— **不** 调 completeProduction/settleProduction,**不** 发 BatchCompletedEvent(`:1459-1485` 会扣料+建成品 → 双扣)
```java
@Transactional
public void stopProduction(String factoryId, String planId) {
    ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId).orElseThrow(...);
    if (plan.getProductionMode() != ProductionMode.BY_STOCK)
        throw new BusinessException("仅库存生产计划可停产");
    plan.setStatus(ProductionPlanStatus.COMPLETED);
    plan.setEndTime(LocalDateTime.now());
    if (plan.getStartTime() == null) plan.setStartTime(LocalDateTime.now());
    planRepository.save(plan);
}
```
⚠️ 确认 ProductionPlanStatus 枚举值(COMPLETED)+ planRepository.findByIdAndFactoryId 存在。

- [ ] **Step 2: 端点** `POST /{planId}/stop-production`(同 Task3 Step6 注解模式)。

- [ ] **Step 3: 测试**:BY_STOCK plan stop → COMPLETED 且无 BatchCompletedEvent 发布(verify no event)/ 无新扣料;BY_ORDER plan stop → 400。

- [ ] **Step 4: Commit** scoped。

---

## Task 5 — 前端:模式选择 + 小结/停产按钮(BY_STOCK 门控)

**Files:** Modify `web-admin/src/api/productionPlan.ts`、建计划表单、`views/production/plans/list.vue`、逐道录入抽屉组件。

- [ ] **Step 1: API** `productionPlan.ts`:create 请求加 `productionMode`;加 `interimSettle(factoryId, planId)` / `stopProduction(factoryId, planId)`;ProductionPlan 接口加 productionMode 字段。

- [ ] **Step 2: 建计划表单**:加业态选择(销售订单生产 / 库存生产),默认销售订单。库存生产时隐藏/弱化销售订单关联字段。

- [ ] **Step 3: 列表/详情按钮门控**(防呆 fool-proof-design):`productionMode==='BY_STOCK'` 的计划:把"结单"按钮**换成"小结"**(点 → 调 interimSettle → 成功 toast "已小结第 N 次,计划继续挂起" + 刷新)+ 单独"停产"按钮(点 → ElMessageBox.confirm "停产后计划关闭,确认?" → stopProduction)。`BY_ORDER` 保持现有结单不变。
   - 防呆:小结按钮成功/失败 toast sticky(error duration:0);停产二次确认带计划名/编号(Rule 2 context)。小结幂等由后端保证,前端按钮 saving 禁用防双击(Rule 4)。

- [ ] **Step 4: 构建** `cd web-admin && npm install --prefer-offline --legacy-peer-deps && npm run build` 绿。

- [ ] **Step 5: Commit** scoped。

---

## 交付(Phase 2 完成定义,Rule 10)

1. 全 task PR off origin/main;`git diff origin/main...HEAD --stat` 确认 scope 干净。
2. **organizer(Opus)终审**(🔒 库存写/扣减/迁移必经);G3 diff 必经独立 code-reviewer。
3. 部署:`git checkout main && pull` → backend(Flyway 自动跑 V19/V20)+ web-admin,从干净 worktree,prod==main。
4. **headed E2E(干净 BY_STOCK 计划)**:建 BY_STOCK plan → 逐道录入 → 小结 → 验(半成品进 SFI 库 / 成品进 FG / source usedQuantity 扣了 / 计划仍挂起)→ 再点小结(幂等,不重复扣)→ 停产 → COMPLETED。三方:API==DB==DOM。
5. 部署后核对运行 jar 含修复 + smartbi migration tracker(若动 smartbi 无关,跳过)。

## Self-Review(写完本计划已检查)
- 5 个 🔒 陷阱:陷阱2(不走 settle 扣减)✓ Task3-4;陷阱3(新建不复用)✓ Task3;陷阱1(WIP planId=null)本阶段不碰 planId ✓。
- 类型一致:ProductionMode / interimSettle 签名跨 task 一致 ✓。
- 占位符扫描:Step 均带具体代码/命令 ✓(delta 产出界定 Step5 标了需实现者按 delta 模型定,是已知风险点非占位)。
- 最大风险:Task3 Step5 的"未结产出 delta 界定"——交 organizer + 独立 reviewer 重点审。
