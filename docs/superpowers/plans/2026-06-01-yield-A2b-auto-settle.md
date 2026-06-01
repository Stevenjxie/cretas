# 报工体系统一 A2b — 余料关联料批 + 自动结清 + A3 跨批测试 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 领料(recordMaterialInput)关联原料批次(`material_batch_refs` jsonb, 1 或 N) → 当所有关联 MaterialBatch USED_UP 时自动结清该报工(settled=true); 补主动触发端点; A3 跨批归因加集成测试(数据层已实现)。

**Architecture:** ProductionReport 加 `material_batch_refs` jsonb(镜像 source_batch_refs)+ migration; recordMaterialInput 写入 + 末尾 checkAndAutoSettle(all-refs-USED_UP 才结清); native `@>` 查询 + GIN 索引; 补 POST auto-settle-by-material-batch 端点; A3 新建 cross-batch 测试。

**Tech Stack:** Java 21 + Spring Boot 3 + JPA(hypersistence JsonType jsonb)+ PostgreSQL + Flyway。

**Worktree:** `C:/Users/Steve/cretas-yield-a2b` (branch `feat/yield-a2b-auto-settle` off origin/main)。`mvn` at `/c/tools/apache-maven-3.9.6/bin`, 用 `MAVEN_OPTS="-Xmx2g"`。
**Spec:** `docs/superpowers/specs/2026-06-01-yield-A2b-A3-design.md`(§2 A2b, §3 A3, §5 测试, §6 文件清单, §9 审计修订)。

> ⚠️ **本功能有 DB migration**(V20260901_04 加 jsonb 列)。部署 prod 后**必须 `systemctl restart cretas-backend`** 让 flyway 真跑迁移(blue-green 起新实例会跑 flyway, 但务必核对 flyway_schema_history 有 V20260901_04 + pg_indexes 有 idx_pr_material_batch_refs)。per memory `project_2026_06_01_yield_reporting_phase_a` 的迁移教训。

---

## 实施顺序
Task 1 (migration + entity 字段 + DTO) → Task 2 (recordMaterialInput 写入 + repo @> 查询) → Task 3 (checkAndAutoSettle + allRefsUsedUp + 触发) → Task 4 (auto-settle-by-material-batch 端点) → Task 5 (A3 跨批集成测试) → Task 6 (部署 + E2E 验证, 与 A4 合并 E2E)。

---

## Task 1: migration + ProductionReport jsonb 字段 + MaterialBatchRef DTO + MaterialInputRequest

**Files:**
- Create: `backend/.../resources/db/flyway/V20260901_04__add_material_batch_refs_to_production_reports.sql` (确认目录: 与现有 V20260901_01/02/03 同目录, 先 `ls`)
- Create: `backend/.../dto/yield/MaterialBatchRef.java`
- Modify: `backend/.../dto/yield/MaterialInputRequest.java` (加 List<MaterialBatchRef>)
- Modify: `backend/.../entity/ProductionReport.java` (加 material_batch_refs jsonb, 镜像 source_batch_refs)

- [ ] **Step 1**: `ls` 现有 flyway 目录确认 V20260901_03 路径 + 读 V20260901_01 看格式。建 V20260901_04(spec §2.3.1 SQL: ADD COLUMN material_batch_refs jsonb + GIN idx_pr_material_batch_refs jsonb_path_ops + COMMENT)。**版本号必须 > prod flyway_schema_history 当前 max**(纯增量列, out-of-order=false → 新版本号要最大; V20260901_04 > _03 OK)。
- [ ] **Step 2**: 读 `ProductionReport.java` 的 `source_batch_refs` 字段(`@Type(JsonType.class)` + `@Column(columnDefinition="jsonb")` + `List<Map<String,Object>>`)精确模仿, 加 `materialBatchRefs`:
```java
@Type(JsonType.class)
@Column(name = "material_batch_refs", columnDefinition = "jsonb")
private List<Map<String, Object>> materialBatchRefs;
```
- [ ] **Step 3**: 建 `MaterialBatchRef.java`(@Data: `Long materialBatchId; BigDecimal quantity; String unit;`)。`MaterialInputRequest.java` 加 `private List<MaterialBatchRef> materialBatchRefs;`。
- [ ] **Step 4**: 编译 `MAVEN_OPTS="-Xmx2g" mvn -q -DskipTests compile` 通过。
- [ ] **Step 5**: Commit (safe-commit) `feat(yield-a2b): material_batch_refs jsonb 字段 + migration V20260901_04 + MaterialBatchRef DTO`。

---

## Task 2: recordMaterialInput 写入 material_batch_refs + repo @> 查询

**Files:**
- Modify: `backend/.../service/yield/impl/YieldReportServiceImpl.java` (recordMaterialInput + toMaterialBatchRefMaps helper)
- Modify: `backend/.../repository/ProductionReportRepository.java` (findUnsettledYieldContainingMaterialBatch native @>)
- Test: `backend/.../service/yield/YieldReportServiceImplTest.java`

- [ ] **Step 1: 写失败测试**: `recordMaterialInput_writesMaterialBatchRefs_single` (materialBatchRefs=[{id:123,qty:520}] → 保存的 ProductionReport.materialBatchRefs 含该 ref); `recordMaterialInput_writesMaterialBatchRefs_multi` (2 refs → 2 元素)。(mock reportRepo.save 捕获 ArgumentCaptor 验 materialBatchRefs。读现有 recordMaterialInput 测试风格。)
- [ ] **Step 2**: 跑 FAIL。
- [ ] **Step 3**: 实现 `toMaterialBatchRefMaps`(spec §2.3.2: List<MaterialBatchRef>→List<Map>, null/empty→null) + recordMaterialInput builder 加 `.materialBatchRefs(toMaterialBatchRefMaps(req.getMaterialBatchRefs()))`。加 repo `findUnsettledYieldContainingMaterialBatch`(spec §2.3.4 native SQL `material_batch_refs @> CAST(:refJson AS jsonb)`, settled false, report_type YIELD, deleted_at null)。
- [ ] **Step 4**: 跑 PASS。
- [ ] **Step 5**: Commit `feat(yield-a2b): recordMaterialInput 写 material_batch_refs + @> 查询`。

---

## Task 3: checkAndAutoSettle + allRefsUsedUp + 触发

**Files:**
- Modify: `backend/.../service/yield/impl/YieldReportServiceImpl.java`
- Test: `YieldReportServiceImplTest.java`

> 需要 `MaterialBatchRepository`(grep 确认类名 + findById(String) — MaterialBatch.id 是 varchar) + `MaterialBatchStatus.USED_UP` 枚举。注入 MaterialBatchRepository(final field, 更新测试构造)。

- [ ] **Step 1: 写失败测试** (spec §5.1):
  - `autoSettle_singleBatchUsedUp_settles`: report materialBatchRefs=[{id:123}], mock MaterialBatch 123 USED_UP → recordMaterialInput 后该 report settled=true。
  - `autoSettle_multiBatch_partialUsedUp_notSettled`: refs=[{123},{456}], 123 USED_UP 456 有余量 → allRefsUsedUp=false → 不结清。
  - `autoSettle_multiBatch_allUsedUp_settles`: 123+456 都 USED_UP → 结清。
  - `autoSettle_noRefs_skips`: materialBatchRefs=null → 不触发。
  (mock materialBatchRepository.findById → MaterialBatch with status/remainingQuantity; mock reportRepo.findUnsettledYieldContainingMaterialBatch → candidate reports。)
- [ ] **Step 2**: 跑 FAIL。
- [ ] **Step 3**: 实现 `checkAndAutoSettle(factoryId, batchId, materialBatchId)` + `allRefsUsedUp(refs)`(spec §2.3.4 精确代码)。在 recordMaterialInput 末尾(保存后)遍历 req.materialBatchRefs 每个 id 调 checkAndAutoSettle(spec §2.3.3 B-1)。
- [ ] **Step 4**: 跑 PASS(全 Yield* 测试不回归)。
- [ ] **Step 5**: Commit `feat(yield-a2b): MaterialBatch USED_UP 自动结清 (all-refs-USED_UP 语义)`。

---

## Task 4: auto-settle-by-material-batch 主动触发端点

**Files:**
- Modify: `backend/.../service/yield/YieldReportService.java` (+ 接口方法暴露 checkAndAutoSettle 或一个 public autoSettleByMaterialBatch)
- Modify: `backend/.../controller/YieldReportController.java`
- Test: `YieldReportServiceImplTest.java`

- [ ] **Step 1**: 接口加 `Map<String,Object> autoSettleByMaterialBatch(factoryId, batchId, materialBatchId)`(返 settledCount), impl 调 checkAndAutoSettle 并返结清数。
- [ ] **Step 2**: controller 加 `POST /yield/auto-settle-by-material-batch` body `{materialBatchId}`(mirror controller 现有注解, @RequirePermission production:read_write)。
- [ ] **Step 3**: 测试 `autoSettleByMaterialBatch_endpoint` (service 层)。跑 PASS + 全后端编译。
- [ ] **Step 4**: Commit `feat(yield-a2b): auto-settle-by-material-batch 主动触发端点`。

---

## Task 5: A3 跨批归因集成测试 (数据层已实现, 只加测试)

**Files:**
- Create: `backend/.../test/java/com/cretas/aims/service/yield/YieldCalculationCrossBatchTest.java`

- [ ] **Step 1**: 建测试(spec §3.3 + §3.4 6 用例): 跨批带入计 totalInput(B道1 input100 + sourceBatchRefs[{qty:606}] → 706); 跨批 yieldRate(300/706=0.4249); 无跨批不受影响; 金标准回归(998→520→382.08 cumulative 0.3828); A 批次 carryover=606; 多 sourceBatchRef 累加。用真实 ProductionReport(含 sourceBatchRefs)+ calcSvc.calculateBatchYield。
- [ ] **Step 2**: 跑 PASS `MAVEN_OPTS="-Xmx2g" mvn -Dtest=YieldCalculationCrossBatchTest test`。
- [ ] **Step 3**: Commit `test(yield-a3): 跨批料归因集成测试 (6 用例)`。

---

## Task 6: 部署 + 完整 E2E 验证 (A4 + A2b, RN app → web, 六扇门颗粒度)

> Steve 指示: A4 + A2b 必须真实完整 E2E(RN app 报工 → web 端查看, 全流程, 验证逻辑 + 每环节颗粒度符合六扇门)。

- [ ] **部署**: PR scope 干净 → PR → CI 绿 merge → deploy worktree `git checkout origin/main` → `deploy-backend.sh --env prod`(blue-green)。**核对 flyway**: `ssh ... "psql ... 'SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3'"` 含 V20260901_04 + `pg_indexes` 有 idx_pr_material_batch_refs。若 blue-green 新实例没跑迁移 → `systemctl restart cretas-backend`。
- [ ] **RN app → web E2E**(headed, per playwright-headed-mode.md; RN Expo via SSH tunnel 连 prod 10010, web-admin 139:8086):
  - **A4 超收 E2E**: RN 逐道报工, 报产出超 `投入×standardYieldMax×1.3` → **确认框弹出**(actionHint 准确数字)→ 点确认超收提交 → 保存。查 DB report 落库 + web-admin 批次详情看到该道产出。
  - **A2b 领料关联 + 自动结清 E2E**: RN 领料关联料批(materialBatchRefs)→ 查 DB material_batch_refs jsonb 落库 → 标记料批 USED_UP(或调 auto-settle 端点)→ 查 DB report settled=true + settled_at。
  - **A3 跨批**: (数据层, API/test 验证即可, 无 UI)。
  - **web 端查看**: 批次详情出成率卡 + ProcessIO 工序聚合 + (若有结清状态展示)。
  - **六扇门颗粒度核对**: 每道投入/产出双量、超收边界预显(防呆 Rule 1)、自动结清(A2b 张权"原料用完自动")、跨批归因(A3)逐项确认颗粒度合理。
- [ ] **验证文档**: 截图 + 每步 DB 证据 + headed verification block + 六扇门逐项 PASS/FAIL。

---

## Self-Review
- Spec 覆盖: Task1(migration+jsonb 字段+DTO §2.3.1)、Task2(recordMaterialInput+@>查询 §2.3.2/2.3.4)、Task3(checkAndAutoSettle+allRefsUsedUp §2.3.3/2.3.4)、Task4(主动端点 §2.3.5)、Task5(A3 测试 §3)、Task6(部署+E2E §5)。§9 审计修订(jsonb 数组 1+N / all-refs-USED_UP / 4 处 USED_UP / A3 数字边界)全落地。
- 类型一致: material_batch_refs jsonb 格式 [{materialBatchId,quantity,unit}] 在 entity/recordMaterialInput 写入/repo @> 查询/checkAndAutoSettle 读取/RN(若改) 五处一致。MaterialBatch.id 是 varchar(findById(String))。
- migration 版本号 V20260901_04 > prod max(部署前核对 flyway_schema_history)。
- 无 placeholder; 每 task 有 grep/测试用例/迁移 SQL。
- ⚠️ 部署后必须核对 flyway 真跑了 V20260901_04(per Phase A 迁移教训: deploy 传 jar 不一定重启活跃实例跑 flyway)。
