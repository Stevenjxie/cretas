# Product Process Workflow Runtime 2B (Clerk Process-Sheet) Implementation Plan

**Status:** Framed 2026-07-11. Supersedes the yield-stack assumption in
`2026-07-10-product-process-workflow-runtime-design.md#7` for the reporting surface:
per Steve's 2026-07-11 decision, 2B connects the workflow to the **web-admin clerk
过程单 (process-sheet)** path, not the RN yield stack. RN reporting (2C) stays deferred.

**Two locked decisions (Steve, 2026-07-11):**
1. **Thin projection, reuse clerk machinery.** Project the immutable workflow snapshot into the
   structure the clerk sheet already consumes; reuse `saveRow → materializeBatch → interim-settle`
   for all inventory/cost/settle. No new reporting engine. Lineage reuses existing
   `MaterialConsumption` + `BatchLineageEdge` (no new `production_inventory_lineage` table).
2. **MVP = single OUTPUT port per reportable process.** Multi-output nodes are rejected at
   **activation** (and defensively at **materialize**) with a clear, config-pointing message.
   True multi-output fan-out (one node → N rows) is deferred to **2B.2**.

---

## What already works (verified 2026-07-11)

- `createBatchFromPlan` (`ProductionPlanServiceImpl`:3999) creates a `ProductionBatch` with
  `productionPlanId = plan.id`; a PostgreSQL insert trigger sets `workflow_selection_mode`,
  `selected_workflow_id`, `selected_workflow_version` from the active activation; then
  `spawnTasks → materializeIfActive` spawns one `WorkProcessTask` per reportable PROCESS node
  plus its `WorkflowTaskPort` rows, and persists the immutable `ProductionWorkflowInstance` snapshot.
- So for a workflow-activated product, a plan → 转批次 already yields workflow tasks/ports bound to a
  batch that is **findable from the plan** (`findByFactoryIdAndProductionPlanId`), unlike clerk-WIP
  batches (which set `productionPlanId=null`).
- `WorkflowTaskPort` carries `direction (INPUT/OUTPUT)`, `materialKind (RAW_MATERIAL/SEMI_FINISHED/
  FINISHED_GOOD)`, `skuId`, `unit`, `ordinal`, `conversionMode/Expression`.
- `skuId` resolution is clean, no new table:
  - RAW_MATERIAL port `skuId` = `RawMaterialType.id` (editor binds `/raw-material-types/active`).
  - SEMI_FINISHED / FINISHED_GOOD port `skuId` = `ProductType.id` (editor binds `/product-types`,
    `productCategory` classifies semi vs finished).

## The single real gap 2B closes

The clerk sheet enumerates its processes from **`ProductWorkProcess`** (legacy config) via
`getProductWorkProcesses` → FE `resolveProcesses`. Workflow tasks carry `productWorkProcessId=null`,
so workflow processes do not surface. 2B projects the workflow snapshot into the descriptor shape the
clerk sheet consumes, drives/validates the row against the ports, and reuses all inventory machinery.

---

## Global Constraints

- Additive only. The legacy (non-workflow) clerk path stays byte-for-byte unchanged; every workflow
  branch is gated by "this plan has a workflow batch".
- No new inventory write path. All raw drawdown / SFI / finished-goods / cost / interim-settle stays in
  `ClerkProcessEntryService.materializeBatch` + `InterimSettleServiceImpl`, unchanged.
- Reject (never silently degrade) a workflow that violates the MVP single-output invariant, per
  `禁止降级处理`. Error responses carry `message/code/actionHint` per fool-proof 四位一体.
- Factory-scoped everywhere; cross-factory plan/batch/workflow/SKU references → 404/409.
- Fool-proof: the sheet shows the workflow-planned output (name/SKU/unit) read-only and the required
  input material types; the clerk fills only actual batches + quantities.

---

## Backend

### Task B1 — MVP single-output guard (activation + materialize)

**Files:** `service/workflow/impl/ProductProcessWorkflowActivationServiceImpl.java`,
`service/workflow/ProductProcessWorkflowRuntimeCompiler.java` (or its caller in
`ProductProcessWorkflowRuntimeServiceImpl`), + tests.

- At `activate(...)`: after loading the exact published workflow, compile/inspect its reportable
  PROCESS nodes; if any has `> 1` OUTPUT port, throw 409 `WORKFLOW_MULTI_OUTPUT_UNSUPPORTED` with hint
  "当前版本每道工序仅支持一个产出，请在 Workflow 配置中拆分或删除多余产出后再启用". Never activate.
- Defensive mirror at `materializeIfActive`: same guard, so a pre-existing activation cannot spawn a
  multi-output batch. Fail closed (roll back batch creation) with the same code.
- Tests: activation rejects a 2-output node; single-output activates; materialize guard rejects.

### Task B2 — Clerk-sheet projection service + endpoint

**Files:** new `service/workflow/WorkflowClerkSheetService(+Impl)`,
new `dto/workflow/WorkflowClerkSheetConfigDTO`,
`controller/ProcessSheetController.java` (add GET), + tests.

- `WorkflowClerkSheetConfigDTO getWorkflowSheetConfig(String factoryId, String planId)`:
  1. Find the workflow batch: `productionBatchRepo.findByFactoryIdAndProductionPlanId(factoryId, planId)`,
     pick the one with `workflowSelectionMode == WORKFLOW` (and an existing instance). None → return
     `null` (legacy plan; FE keeps current behavior).
  2. Load `ProductionWorkflowInstance`, its `WorkProcessTask`s (ordered by `processOrder`), and
     `WorkflowTaskPort`s (grouped by `taskId`).
  3. For each task, emit a descriptor:
     `{ workflowNodeId, workProcessId, processName, defaultCostCategory, processOrder, plannedUnit,
        allowMultipleUpstreamSources, allowFinishedGoodsSource, customFieldSchema,
        inputs: [{ materialKind, skuId, materialName, unit }],
        output: { materialKind, skuId, materialName, unit, finished } }`.
     - `finished = (output.materialKind == FINISHED_GOOD)`.
     - `allowMultipleUpstreamSources = inputs has >1 upstream (SEMI/FINISHED) or node flag`.
     - `allowFinishedGoodsSource = any input materialKind == FINISHED_GOOD`.
     - Resolve `materialName`/`unit` by `skuId`: RawMaterialType (raw inputs), ProductType (semi/finished).
       Missing/deleted SKU → keep id + a flag so the FE shows a clear "SKU 已失效, 请回 Workflow 配置" hint
       (fool-proof Rule 5), do not crash.
  4. `defaultCostCategory`/`processName`/`customFieldSchema` come from the `WorkProcess`
     (`workProcessId`) exactly like legacy, so the FE archetype mapping in `resolveProcesses` is reused
     unchanged (generic fallback for unmapped).
- Endpoint: `GET /api/mobile/{factoryId}/production-plans/{planId}/workflow-sheet-config`
  → `ApiResponse<WorkflowClerkSheetConfigDTO>` (data `null` for legacy plans). Read-only, factory-scoped.
- Tests: workflow batch found → descriptors ordered + resolved; legacy plan → null; cross-factory → 404;
  deleted SKU → flagged not crash.

### Task B3 — saveRow workflow validation + task association (🔒 keystone)

**Files:** `service/processentry/impl/ProcessSheetServiceImpl.java` (`saveRow` pre/post hooks only),
optionally `WorkProcessTaskRepository`, + tests. **Additive, gated by "workflow batch".**

- When the plan has a workflow batch and the row's `processOrder` maps to a workflow task:
  - Validate the row's **output** against the port: `finished` flag matches the port
    materialKind (FINISHED_GOOD ⇒ finished=true; SEMI_FINISHED ⇒ finished=false); output `unit` matches
    the port `unit`; the finished/semi productType matches the port `skuId`. Mismatch → 409 with a
    specific, sticky, action-hint message (fool-proof 四位一体).
  - Validate **raw inputs**: each `rawMaterialInputs[].materialBatchId` resolves to a `MaterialBatch`
    whose `materialTypeId` ∈ the task's declared RAW input `skuId`s (soft warn if extra, hard block if a
    required raw type is absent — mirror `required` port). Keep this additive and specific.
  - Associate: stamp the produced `WorkProcessTask` (`actualQuantity`, mark `COMPLETED` on the finishing
    output like the legacy double-write at `YieldReportServiceImpl` does) so `getRuntime`/runtime view
    reflects progress, and so lineage (existing `MaterialConsumption` + `BatchLineageEdge`) ties the row's
    inputs/outputs to the workflow node. No new lineage table.
  - Do **not** alter the legacy branch, materialization, or interim-settle. This is a thin guard + stamp
    around the existing `saveRow`.
- Tests: workflow row validates & stamps task; wrong output kind/unit → 409; legacy row unaffected;
  interim-settle still drives real drawdown for a workflow-materialized row (real-JPA/mock as available).

---

## Frontend (web-admin)

### Task F1 — ProcessSheet.vue workflow-awareness

**Files:** `views/production/components/processSheet/ProcessSheet.vue`, `api/processSheet.ts` (+ type), test.

- In `resolveProcesses()`: first call `getWorkflowSheetConfig(factoryId, planId)`. If it returns a config,
  build the process tabs from its descriptors (map each to an archetype `code` via the existing
  `defaultCostCategory`/name logic, generic fallback otherwise), passing `allowInjection`,
  `allowMultipleUpstreamSources`, `allowFinishedGoodsSource`, `customFieldSchema`, **plus the workflow
  port info** (planned output name/SKU/unit, input material types). If it returns `null`, keep the current
  `getProductWorkProcesses` path unchanged.

### Task F2 — ProcessDataTable.vue prefill/display (fool-proof)

**Files:** `views/production/components/processSheet/ProcessDataTable.vue`, test.

- When workflow port context is present: show the workflow-planned **output** (name/SKU/unit) read-only
  (fool-proof Rule 2/3 — tell the clerk what to produce, no free type choice), and the required **input**
  material types as hints on the raw/upstream pickers. Reuse existing raw-batch / upstream / SFI / FG
  pickers unchanged. Save via existing `saveRow`.

---

## Testing

### Backend
- B1/B2/B3 unit tests (above). Focused suite green + `mvn test-compile` + existing workflow/clerk
  regression (`ProductProcessWorkflow*Test`, `ProcessSheet*Test`, `InterimSettle*Test`,
  `WorkProcessTaskServiceImplTest`) unchanged-green.

### Headed web-admin E2E (F006) — the acceptance
Per `playwright-headed-mode.md` (headless:false, zh-CN, 1920×1080, video). F006 only, no real tenant.
1. Configure a **linear single-output-per-process** workflow for an F006 test product
   (原料 → 前处理→半成品 → 卤制→半成品 → 包装→成品), publish, **activate**.
2. Create a **SAFETY_STOCK** plan for that product, 转批次 (spawns workflow tasks).
3. Open 过程单 → the workflow processes appear with planned outputs/units (workflow uiux 正常).
4. Enter a row per process (raw batch + qty → semi; feed upstream semi → next; final → finished), save each.
5. 小结 (interim-settle) → assert **real** raw `MaterialBatch.usedQuantity`↑ (USED_UP when depleted),
   `SemiFinishedInventory`/`FinishedGoodsBatch` created, `MaterialConsumption`/`BatchLineageEdge` lineage.
6. Multi-output guard: attempt to activate a 2-output workflow → blocked with the config-pointing message.

Acceptance = the clerk 过程单, driven by the workflow structure, moves real inventory end-to-end (报工联通),
and the workflow config UI (editor → publish → activate → plan → 过程单) is usable in the browser.

---

## Adversarial review (fable diff-hunt) — findings + resolution (2026-07-11)

A read-only fable diff-hunt of the 2B increment found the increment did not survive its own
acceptance script. Resolution:

| # | Finding | Sev | Resolution |
|---|---|---|---|
| F1 | FE `buildRequest` derived `finished` from the name-keyword archetype (`processCode==='qidiao'`), not the workflow port → the finishing row 409'd on B3's kind check → clerk dead-ends, no `FinishedGoodsBatch`. | BLOCKING | **Fixed (FE):** `buildRequest` sources `finished` from `workflowContext.output.finished`; `mapWorkflowProcesses` forces the finished-goods archetype when the port output is finished. |
| F2 | FE hardcoded output `unit` (`'kg'`/`'盒'`) → any non-kg semi (or non-盒 finished) port 409'd on B3's unit check. | BLOCKING | **Fixed (FE):** `buildRequest` sources the output `unit` from `workflowContext.output.unit`. |
| F3 | B3 dropped the planned task-stamp → workflow `WorkProcessTask`s stay PENDING forever (runtime view never reflects clerk progress). | HIGH | **Fixed (B3):** fail-soft `stampWorkflowTaskIfApplicable` marks the task COMPLETED + writes actualQuantity after a workflow row produces output; never blocks the save. |
| F4 | B3 fail-open `catch` could hit Spring's rollback-only trap if the new `ProductWorkProcess` finder threw `IncorrectResultSizeDataAccessException` on duplicates. | HIGH | **No fix needed (verified):** `product_work_processes` has a `@UniqueConstraint`/DB unique index on `(factory_id, product_type_id, work_process_id)` → duplicates are impossible → the `Optional` finder never throws that. Fable's "no unique constraint" premise was incorrect. |
| F5 | A multi-output instance materialized *before* the guard would 409 at sheet-read (→ silent legacy fallback) but also 409 every save. | MED | **Accepted (theoretical):** nothing is deployed yet and the activation+materialize guards block all new multi-output instances; documented deploy-window edge. |
| F6 | A first process whose input is SEMI (non-keyword name) maps to the raw-intake archetype → only the raw picker shows. | MED | **Deferred to 2B.2:** F006 real flows start from RAW (natural case); documented. Full archetype-by-port-structure is 2B.2. |
| F7 | Branching (split) workflows render as a fake linear clerk chain. | MED | **Deferred to 2B.2:** adding a linear guard risks regressing 2A's supported branch/merge behavior; the F006 E2E uses a linear single-output workflow; documented as a clerk-MVP limitation. |

Confirmed sound by the review: B1 guard correctness, projection plumbing, legacy fallback (byte-identical),
skuId resolution + factory-scoping + deleted-SKU degradation, and guard completeness (only
`materializeIfActive` writes instances/ports; guard fires before persist).

## Ship-gate boundary

- 🔒 areas: activation guard, saveRow inventory-adjacent validation, projection. Opus keystone on B3;
  Sonnet in-harness for B1/B2 scaffolding + tests + F1/F2 under adversarial review; Opus final diff +
  from-main prod deploy (never from this feature branch).
- Deferred: 2B.2 multi-output fan-out; 2C RN screens + RN F006 E2E.
