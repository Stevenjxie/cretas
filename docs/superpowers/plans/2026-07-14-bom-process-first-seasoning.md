# BOM Process-First Seasoning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the separate seasoning tab with a process-first auxiliary workspace that supports the same material in multiple processes, binding-level pot rules, and a deduplicated material summary without double-counting ordinary BOM auxiliaries.

**Architecture:** Keep `bom_seasoning_items` as the canonical versioned source for process seasoning. Add binding-level pot ratio, database uniqueness, and a seasoning revision for safe incremental CRUD; expose one workspace read model containing ordered workflow processes, bindings, summaries, and anomalies. Mount focused Vue components inside the existing BOM auxiliary tab while retaining raw-material and packaging behavior.

**Tech Stack:** Java 21, Spring Boot 3.2.12, JPA/Hibernate 6, PostgreSQL/Flyway, Vue 3, TypeScript 5.9, Element Plus 2.13, Vitest 4, Vue Test Utils.

## Global Constraints

- New process seasoning writes only `bom_seasoning_items`; never dual-write the same input to ordinary BOM auxiliary rows.
- Binding identity is `(recipeId, workProcessId, materialTypeId)`; the same material may bind to multiple processes.
- Dosage is always grams per 1 kg of semi-finished input to the selected process (`g/kg`).
- Pot sequencing is binding-level: first pot is fixed at 100%; every later pot uses one fixed ratio and does not compound.
- Material name and normalized `元/kg` price snapshot come from authoritative material master data; no free-text name or editable dual prices.
- `ACTIVE` and `ARCHIVED` recipes are read-only; only `DRAFT` is editable.
- Cross-process `g/kg` values are never summed in the summary view.
- Missing price, invalid unit conversion, invalid process, collision with an ordinary auxiliary, or incomplete legacy data must fail closed with an actionable message.
- Preserve current raw-material and packaging behavior, current process-report payload compatibility, and existing product-recipe fallback reads.
- Verify with F006 isolated data only; do not deploy in this plan.

---

## File Structure

### Backend

- Create `backend/java/cretas-api/src/main/resources/db/flyway/V20261028_66__seasoning_binding_revision_and_pot_rule.sql` — binding uniqueness, binding pot ratio, recipe seasoning revision, and compatibility backfill.
- Modify `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/bom/BomSeasoningItem.java` — add binding-level `subsequentPotRatio`.
- Modify `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/bom/BomRecipe.java` — add `seasoningRevision` distinct from the business BOM version.
- Modify `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/bom/BomSeasoningItemRepository.java` and `BomRecipeRepository.java` — targeted binding lookup and atomic revision claim.
- Create `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/BomSeasoningWorkspaceResponse.java` — ordered processes, bindings, summaries, and anomalies.
- Create `SeasoningBindingCreateRequest.java`, `SeasoningBindingUpdateRequest.java`, and `SeasoningBindingMutationResponse.java` in the same DTO package.
- Create `LegacyAuxiliaryConversionRequest.java` in the same DTO package.
- Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/BomSeasoningWorkspaceService.java` and `impl/BomSeasoningWorkspaceServiceImpl.java` — workspace read and incremental mutations.
- Modify `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/BomRecipeController.java` — workspace and binding endpoints.
- Modify `backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/RecipeCostCalculator.java` and `service/processentry/impl/ClerkProcessEntryServiceImpl.java` — binding-level pot calculation and pot-field activation.
- Modify BOM version snapshot/restore DTO and service files discovered through `BomVersionServiceImpl` — preserve material, process, and binding pot fields.

### Web Admin

- Modify `web-admin/src/api/bom.ts` — workspace DTO and incremental binding API.
- Create `web-admin/src/views/production/bom/seasoning/seasoningModel.ts` — pure grouping, summary, duplicate, collision, and validation helpers.
- Create `BomAuxiliaryWorkspace.vue`, `ProcessSeasoningCard.vue`, and `SeasoningBindingDialog.vue` in the same folder.
- Modify `web-admin/src/views/production/bom/index.vue` — mount the workspace for the auxiliary category and pass explicit selected recipe context.
- Modify `web-admin/src/views/production/bom-unified/index.vue`, `web-admin/src/router/index.ts`, and `web-admin/src/components/layout/menuConfig.ts` — remove the separate tab and redirect legacy deep links.
- Remove `web-admin/src/views/production/ProductRecipeView.vue` and migrate its still-used helpers/tests into the new folder after integration passes.

---

### Task 1: Binding-Level Data Integrity and Pot Semantics

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261028_66__seasoning_binding_revision_and_pot_rule.sql`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/bom/BomSeasoningItem.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/bom/BomRecipe.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/bom/BomSeasoningItemRepository.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/bom/BomRecipeRepository.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/bom/BomSeasoningBindingIntegrityTest.java`

**Interfaces:**
- Produces: `BomSeasoningItem.getSubsequentPotRatio(): BigDecimal`, `BomRecipe.getSeasoningRevision(): Long`, `BomRecipeRepository.claimSeasoningRevision(...): int`.
- Produces: repository lookup by recipe, process, and material for later CRUD.

- [ ] **Step 1: Write failing integrity tests**

```java
assertDoesNotThrow(() -> createBinding(recipeId, "ROLL", "CHILI"));
assertDoesNotThrow(() -> createBinding(recipeId, "FRY", "CHILI"));
assertThrows(DataIntegrityViolationException.class,
    () -> createBinding(recipeId, "ROLL", "CHILI"));
assertEquals(1, recipeRepository.claimSeasoningRevision(recipeId, factoryId, 0L));
assertEquals(0, recipeRepository.claimSeasoningRevision(recipeId, factoryId, 0L));
```

- [ ] **Step 2: Run the focused tests and confirm failure**

Run: `mvn -Dtest=BomSeasoningBindingIntegrityTest test` from `backend/java/cretas-api`.

Expected: compile/test failure because the new column, constraint, and claim method do not exist.

- [ ] **Step 3: Add the migration**

```sql
ALTER TABLE bom_seasoning_items
  ADD COLUMN IF NOT EXISTS subsequent_pot_ratio NUMERIC(8,4);
ALTER TABLE bom_recipes
  ADD COLUMN IF NOT EXISTS seasoning_revision BIGINT NOT NULL DEFAULT 0;

UPDATE bom_seasoning_items i
SET subsequent_pot_ratio = p.subsequent_pot_ratio
FROM bom_process_seasoning p
WHERE p.recipe_id = i.recipe_id
  AND p.work_process_id = i.work_process_id
  AND p.deleted_at IS NULL
  AND i.deleted_at IS NULL
  AND i.subsequent_pot_ratio IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bsi_recipe_wp_material
  ON bom_seasoning_items(recipe_id, work_process_id, material_type_id)
  WHERE deleted_at IS NULL AND work_process_id IS NOT NULL AND material_type_id IS NOT NULL;
```

- [ ] **Step 4: Add entity fields and atomic revision claim**

```java
@Column(name = "subsequent_pot_ratio", precision = 8, scale = 4)
private BigDecimal subsequentPotRatio;

@Column(name = "seasoning_revision", nullable = false)
private Long seasoningRevision = 0L;

@Modifying
@Query("update BomRecipe r set r.seasoningRevision = r.seasoningRevision + 1 " +
       "where r.id = :recipeId and r.factoryId = :factoryId and r.status = 'DRAFT' " +
       "and r.seasoningRevision = :expectedRevision")
int claimSeasoningRevision(String recipeId, String factoryId, Long expectedRevision);
```

- [ ] **Step 5: Run tests and commit**

Run: `mvn -Dtest=BomSeasoningBindingIntegrityTest,BomRecipeSeasoningServiceTest test`.

Expected: all focused tests pass, including cross-process reuse and same-process uniqueness.

Commit: `feat(bom): enforce process seasoning binding integrity`

---

### Task 2: Incremental Binding API and Workspace Read Model

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/BomSeasoningWorkspaceResponse.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/SeasoningBindingCreateRequest.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/SeasoningBindingUpdateRequest.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/SeasoningBindingMutationResponse.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/LegacyAuxiliaryConversionRequest.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/BomSeasoningWorkspaceService.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/impl/BomSeasoningWorkspaceServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/BomRecipeController.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/bom/BomSeasoningWorkspaceServiceTest.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/BomRecipeSeasoningWorkspaceControllerTest.java`

**Interfaces:**
- Consumes: Task 1 revision claim and binding repository lookup.
- Produces:

```java
BomSeasoningWorkspaceResponse getWorkspace(String factoryId, String recipeId);
SeasoningBindingMutationResponse createBinding(String factoryId, String recipeId,
    String workProcessId, SeasoningBindingCreateRequest request);
SeasoningBindingMutationResponse updateBinding(String factoryId, String recipeId,
    Long bindingId, SeasoningBindingUpdateRequest request);
SeasoningBindingMutationResponse deleteBinding(String factoryId, String recipeId,
    Long bindingId, Long expectedRevision);
SeasoningBindingMutationResponse convertLegacyAuxiliary(String factoryId, String recipeId,
    Long recipeItemId, LegacyAuxiliaryConversionRequest request);
```

- [ ] **Step 1: Write failing workspace and mutation tests**

Cover ordered empty processes, one material in two processes, one deduplicated summary with two usages, same-process duplicate 409, ACTIVE rejection, invalid process/material, price/unit normalization failure, stale revision 409 without partial writes, and legacy conversion that removes the versioned ordinary AUX cost row while creating exactly one seasoning binding.

```java
assertEquals(List.of("ROLL", "FRY"), workspace.processes().stream()
    .map(ProcessView::workProcessId).toList());
assertEquals(1, workspace.materialSummaries().size());
assertEquals(2, workspace.materialSummaries().get(0).usages().size());
assertFalse(workspace.materialSummaries().get(0).hasAggregatedDosage());
```

- [ ] **Step 2: Run tests and confirm failure**

Run: `mvn -Dtest=BomSeasoningWorkspaceServiceTest,BomRecipeSeasoningWorkspaceControllerTest test`.

Expected: failure because service, DTOs, and endpoints do not exist.

- [ ] **Step 3: Implement the workspace DTO**

The response must contain `recipeId`, `productTypeId`, `status`, `editable`, `seasoningRevision`, ordered `processes`, deduplicated `materialSummaries`, and `anomalies`. Each process contains its binding rows. Each summary contains usages, not an aggregated dosage.

- [ ] **Step 4: Implement transactional CRUD**

Each mutation must:

1. atomically claim `expectedRevision`;
2. verify the recipe is the requested factory's DRAFT;
3. verify the work process belongs to the recipe product workflow;
4. verify material factory/status/category and normalize the price to `元/kg`;
5. reject a live `(recipe, process, material)` duplicate;
6. mutate only the requested binding;
7. return the new revision and canonical binding.

- [ ] **Step 5: Add controller endpoints**

```text
GET    /{recipeId}/seasoning/workspace
POST   /{recipeId}/seasoning/processes/{workProcessId}/bindings
PUT    /{recipeId}/seasoning/bindings/{bindingId}
DELETE /{recipeId}/seasoning/bindings/{bindingId}?expectedRevision=N
POST   /{recipeId}/seasoning/legacy-auxiliaries/{recipeItemId}/convert
```

The conversion endpoint accepts `expectedRevision`, `workProcessId`, `dosagePerKgG`, `subsequentPotRatio`, and `countInSeasoning`. In one transaction it claims the revision, validates that the recipe item is an AUXILIARY row for the same recipe/material, creates the canonical seasoning binding, soft-deletes the versioned ordinary recipe item, and records an audit entry containing the old fixed-per-output dosage. It does not guess a process and does not delete the legacy product-level `bom_items` compatibility row.

- [ ] **Step 6: Run tests and commit**

Run: `mvn -Dtest=BomSeasoningWorkspaceServiceTest,BomRecipeSeasoningWorkspaceControllerTest,BomRecipeSeasoningServiceTest test`.

Expected: all tests pass; stale revisions return the standard 409 response envelope.

Commit: `feat(bom): add seasoning workspace and binding api`

---

### Task 3: Binding-Level Cost, Reporting, and Version Fidelity

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/RecipeCostCalculator.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ClerkProcessEntryServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/impl/BomVersionServiceImpl.java`
- Modify: the seasoning snapshot DTO/entity files referenced by `BomVersionServiceImpl`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/recipe/RecipeCostCalculatorBindingPotTest.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ClerkProcessEntrySeasoningPerProcessTest.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/bom/BomVersionSeasoningSnapshotTest.java`

**Interfaces:**
- Consumes: binding `subsequentPotRatio` from Task 1.
- Produces: per-binding pot computation and `seasoningPotEnabled = any(binding.ratio != null)` for a process.

- [ ] **Step 1: Write failing calculation and snapshot tests**

```java
// 300 kg / 3 equal pots; chili later pots 50%, salt not sequenced.
assertEquals(new BigDecimal("2000.0000"), chiliUsageGrams);
assertEquals(new BigDecimal("3000.0000"), saltUsageGrams);
assertTrue(sheetConfig.isSeasoningPotEnabled());
assertEquals("ROLL", restoredBinding.getWorkProcessId());
assertEquals("MAT-CHILI", restoredBinding.getMaterialTypeId());
assertEquals(new BigDecimal("0.5000"), restoredBinding.getSubsequentPotRatio());
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `mvn -Dtest=RecipeCostCalculatorBindingPotTest,ClerkProcessEntrySeasoningPerProcessTest,BomVersionSeasoningSnapshotTest test`.

- [ ] **Step 3: Implement per-binding calculation**

For each binding, use `1 + (potCount - 1) × binding.subsequentPotRatio` when the ratio is non-null; otherwise use the total process input once. Sum currency only after each binding quantity is calculated. Keep the old process-level ratio only as a compatibility fallback for rows whose binding ratio is null and were loaded from pre-migration data.

- [ ] **Step 4: Preserve complete seasoning snapshots**

Snapshot and restore `materialTypeId`, `workProcessId`, `subsequentPotRatio`, price snapshot, and remaining process parameters. Add a round-trip assertion rather than only checking serialization.

- [ ] **Step 5: Run regression tests and commit**

Run: `mvn -Dtest=RecipeCostCalculatorBindingPotTest,RecipeCostCalculatorBomParityTest,ClerkProcessEntrySeasoningPerProcessTest,BomVersionSeasoningSnapshotTest test`.

Commit: `fix(bom): calculate and snapshot binding pot rules`

---

### Task 4: Web Admin State Model and Focused Components

**Files:**
- Modify: `web-admin/src/api/bom.ts`
- Create: `web-admin/src/views/production/bom/seasoning/seasoningModel.ts`
- Create: `web-admin/src/views/production/bom/seasoning/BomAuxiliaryWorkspace.vue`
- Create: `web-admin/src/views/production/bom/seasoning/ProcessSeasoningCard.vue`
- Create: `web-admin/src/views/production/bom/seasoning/SeasoningBindingDialog.vue`
- Test: `web-admin/src/views/production/bom/seasoning/__tests__/seasoningModel.spec.ts`
- Test: `web-admin/src/views/production/bom/seasoning/__tests__/BomAuxiliaryWorkspace.spec.ts`
- Test: `web-admin/src/views/production/bom/seasoning/__tests__/SeasoningBindingDialog.spec.ts`

**Interfaces:**
- Consumes: Task 2 workspace and mutation endpoints.
- Produces: `BomAuxiliaryWorkspace` props `{ factoryId, productTypeId, recipeId, recipeStatus, canWrite }` and emit `recipe-cloned` / `changed`.

- [ ] **Step 1: Write failing pure-model tests**

```ts
expect(buildMaterialSummaries([
  binding('CHILI', 'ROLL', 5),
  binding('CHILI', 'FRY', 1.5),
])).toMatchObject([{ materialTypeId: 'CHILI', processCount: 2 }]);
expect(findDuplicateBinding(bindings, 'ROLL', 'CHILI')).toBeTruthy();
expect(buildMaterialSummaries(bindings)[0]).not.toHaveProperty('totalDosagePerKgG');
```

- [ ] **Step 2: Write failing component tests**

Mount with Element Plus and stable `data-testid` values. Verify DRAFT actions, ACTIVE clone prompt, same material in two cards with different values, summary one-row/two-usage expansion, target-process navigation, dialog locked process context, and stale-revision refresh prompt.

- [ ] **Step 3: Run tests and confirm failure**

Run from `web-admin`: `npm test -- src/views/production/bom/seasoning/__tests__`.

- [ ] **Step 4: Implement API types and pure helpers**

```ts
export interface SeasoningWorkspace {
  recipeId: string;
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
  editable: boolean;
  seasoningRevision: number;
  processes: SeasoningProcessView[];
  materialSummaries: SeasoningMaterialSummary[];
  anomalies: SeasoningAnomaly[];
}
```

- [ ] **Step 5: Implement the approved proposal B components**

- Process cards follow workflow order and default to one expanded card.
- Add/edit dialog receives a fixed process and never offers a process selector.
- First pot is a read-only 100%; later-pot percentage appears only when enabled.
- Right summary deduplicates materials and navigates to usages.
- Full summary mode supplies search and `全部 / 多工序使用 / 锅序调料 / 配置异常` filters.
- No direct add action appears in summary mode.

- [ ] **Step 6: Run tests and commit**

Run: `npm test -- src/views/production/bom/seasoning/__tests__`.

Expected: all focused tests pass.

Commit: `feat(web-admin): build process-first seasoning workspace`

---

### Task 5: BOM Integration, Legacy Safety, and Final Verification

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue`
- Modify: `web-admin/src/views/production/bom-unified/index.vue`
- Modify: `web-admin/src/router/index.ts`
- Modify: `web-admin/src/components/layout/menuConfig.ts`
- Remove: `web-admin/src/views/production/ProductRecipeView.vue`
- Move/remove: `web-admin/src/views/production/seasoning/seasoningForm.ts` and its tests after equivalent coverage exists
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/impl/BomSeasoningWorkspaceServiceImpl.java` — expose legacy ordinary AUX collisions and conversion status
- Test: `web-admin/src/views/production/bom/seasoning/__tests__/BomIntegration.source.spec.ts`

**Interfaces:**
- Consumes: Task 4 `BomAuxiliaryWorkspace`.
- Produces: one BOM page with `原辅料配方 / 转换率`, and auxiliary subviews `工序编排 / 辅料汇总`.

- [ ] **Step 1: Write failing integration contracts**

Assert that `bom-unified/index.vue` has no recipe pane, old `?tab=recipe` maps to materials/auxiliary/process view, the generic add dialog cannot create new AUXILIARY rows, and the auxiliary category renders `BomAuxiliaryWorkspace` with an explicit selected recipe ID.

- [ ] **Step 2: Add explicit recipe selection to the BOM host**

Select the current DRAFT when present; otherwise select the current ACTIVE. Pass that recipe ID and status to the workspace. Clone success switches the selected recipe to the returned DRAFT before reloading.

- [ ] **Step 3: Replace the auxiliary table**

Keep the existing raw-material and packaging table/dialog. Render the new workspace for `activeCategoryTab === 'AUXILIARY'`; remove AUXILIARY from the generic create categories. Existing ordinary AUX rows appear as backend-reported “待绑定工序” records. The conversion dialog requires the user to choose a process and enter the new `g/kg` value, shows the old per-output value for comparison, calls the conversion endpoint, and reloads both BOM cost and seasoning workspace. No name-based or category-wide automatic conversion is allowed.

- [ ] **Step 4: Add legacy navigation compatibility**

Redirect `/production/product-recipes` and `?tab=recipe` to the BOM auxiliary process view while preserving `productTypeId`. Update visible guidance strings from “调料配方 Tab” to “原辅料配方 > 辅料 > 工序编排”.

- [ ] **Step 5: Run static and unit verification**

Run from `web-admin`:

```text
npm test -- src/views/production/bom/seasoning/__tests__
npx vue-tsc -b
npm run build
```

Run from `backend/java/cretas-api`:

```text
mvn -Dtest=BomSeasoningBindingIntegrityTest,BomSeasoningWorkspaceServiceTest,BomRecipeSeasoningWorkspaceControllerTest,RecipeCostCalculatorBindingPotTest,ClerkProcessEntrySeasoningPerProcessTest,BomVersionSeasoningSnapshotTest test
mvn -DskipTests package
```

- [ ] **Step 6: Run F006 headed acceptance without deployment**

Verify: open a DRAFT; convert one historical chili AUX row by explicitly choosing rolling and entering `g/kg`; confirm the old versioned fixed-output row no longer contributes to BOM material cost; bind the same chili to frying with a different dosage; configure pot sequencing for only one binding; refresh; inspect one deduplicated summary row with two usages; navigate back to each process; confirm ACTIVE is read-only; confirm missing workflow/price and unresolved legacy collision fail closed; confirm the reporting form shows pot count only for a process with at least one pot-sequenced binding.

- [ ] **Step 7: Commit final integration**

Commit: `feat(bom): integrate seasoning into auxiliary workflow`

---

## Final Review Checklist

- [ ] `git diff --check` is clean.
- [ ] No production-visible copy still directs users to a separate seasoning tab.
- [ ] No new `as any`, free-text seasoning name, editable price source, or cross-process dosage sum exists.
- [ ] Same material/two processes passes; same material/same process fails.
- [ ] Stale mutation revision produces 409 and preserves all bindings.
- [ ] ACTIVE/DRAFT and version snapshot round trip are covered.
- [ ] Ordinary AUX collision is visible and cannot be double-counted silently.
- [ ] Focused backend tests, Vue tests, typecheck, builds, and F006 headed acceptance pass.
