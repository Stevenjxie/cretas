# BOM Process Seasoning Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace free-text seasoning configuration with auxiliary-material selection, add compact process navigation and explicit pot sequencing, and simplify reporting to total input plus pot count.

**Architecture:** Keep `bom_seasoning_items` as the single dynamic seasoning source and add a material-master foreign-key reference plus server-owned moving-average price snapshot. Treat a non-null per-process subsequent-pot ratio as the explicit pot-sequencing flag. At reporting time load those flags by `workProcessId` and derive equal per-pot weights from the final process input quantity.

**Tech Stack:** Java 21, Spring Boot 3.2, JPA/PostgreSQL/Flyway, Vue 3, TypeScript, Element Plus, Vitest.

## Global Constraints

- Do not deploy.
- Merge and push the verified result to `main`.
- Preserve historical seasoning rows without `materialTypeId`; require remapping only when saving a new draft.
- First pot is 100%; every later pot uses the same fixed percentage of the first pot.
- Reporting accepts total input and pot count only; every pot has equal input weight.
- Do not expose editable `priceSource1` or `priceSource2` fields.

---

### Task 1: Material-linked seasoning contract

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/migration/V20260714_01__seasoning_material_link.sql`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/bom/BomSeasoningItem.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/BomSeasoningSaveRequest.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/bom/BomRecipeSeasoningServiceTest.java`

**Interfaces:**
- Consumes: `RawMaterialTypeRepository.findByIdAndFactoryId(...)`, `RawMaterialType.movingAvgPrice`.
- Produces: `BomSeasoningItem.materialTypeId` and `SeasoningItemDTO.materialTypeId`; persisted canonical name and moving-average snapshot in `priceSource1`.

- [ ] **Step 1: Write failing backend tests** for canonical material name/price, missing material, and missing moving-average price.
- [ ] **Step 2: Run** `mvn -q -Dtest=BomRecipeSeasoningServiceTest test` and confirm the new tests fail because `materialTypeId` is absent and no authoritative price lookup occurs.
- [ ] **Step 3: Add migration and Java fields**, then validate each incoming seasoning row against the same factory, copy `name` and `movingAvgPrice`, clear `priceSource2`, and copy `materialTypeId` during clone.
- [ ] **Step 4: Run** `mvn -q -Dtest=BomRecipeSeasoningServiceTest test` and confirm all tests pass.

### Task 2: Seasoning form helpers and API types

**Files:**
- Create: `web-admin/src/views/production/seasoning/seasoningForm.ts`
- Create: `web-admin/src/views/production/seasoning/__tests__/seasoningForm.spec.ts`
- Modify: `web-admin/src/api/bom.ts`

**Interfaces:**
- Produces: `SeasoningMaterialOption`, `filterSeasoningMaterials`, `applySeasoningMaterial`, `buildEqualPotWeights`, and `isPotSequencingEnabled`.
- `BomSeasoningItem.materialTypeId` is nullable on reads for history and required in new save rows.

- [ ] **Step 1: Write failing Vitest cases** proving auxiliary filtering, material auto-fill, fixed pot enablement, and equal weight generation (`300/3 -> [100,100,100]`).
- [ ] **Step 2: Run** `npm test -- src/views/production/seasoning/__tests__/seasoningForm.spec.ts` and confirm module-not-found failure.
- [ ] **Step 3: Implement the pure helpers and update API types** without component behavior.
- [ ] **Step 4: Re-run the focused Vitest file** and confirm all cases pass.

### Task 3: Process navigation and material selection UI

**Files:**
- Modify: `web-admin/src/views/production/ProductRecipeView.vue`

**Interfaces:**
- Consumes Task 2 helpers and `GET /{factoryId}/raw-material-types/active`.
- Saves rows carrying `materialTypeId`, canonical display data, dosage, `workProcessId`, and one server-snapshotted price.

- [ ] **Step 1: Add a failing component/source contract test** asserting the editor renders a process navigation region, uses a material select, and contains no editable 单价1/单价2 labels.
- [ ] **Step 2: Run the focused test** and verify it fails against the current repeated-card/free-text UI.
- [ ] **Step 3: Implement the two-column editor**: process status navigation on the left and current-process rows on the right; load/filter auxiliary materials; add inline searchable material selects; show auto price; add explicit pot switch and percent input; validate required material, dosage, and ratio before save.
- [ ] **Step 4: Re-run the focused test and Task 2 tests**, then run `npm run build:check`.

### Task 4: Config-driven pot reporting with equal split

**Files:**
- Modify: `web-admin/src/views/production/components/processSheet/ProcessSheet.vue`
- Modify: `web-admin/src/views/production/components/processSheet/ProcessDataTable.vue`
- Test: `web-admin/src/views/production/components/processSheet/__tests__/ProcessDataTable.workflowContext.spec.ts`
- Test: `web-admin/src/views/production/seasoning/__tests__/seasoningForm.spec.ts`

**Interfaces:**
- `ProcessSheet` maps active seasoning `processParams` to `ProcEntry.seasoningPotEnabled` by `workProcessId`.
- `ProcessDataTable` accepts `seasoningPotEnabled?: boolean`, displays pot count only when true, and serializes equal `potRawKgs` from final `inputQuantity`.

- [ ] **Step 1: Add failing tests** for a non-熟制 configured process showing pot count and equal split serialization, and for an unconfigured 熟制 process not being config-enabled.
- [ ] **Step 2: Run the focused tests** and confirm failure under the category/name-driven behavior.
- [ ] **Step 3: Load seasoning configuration in `ProcessSheet`**, retain `workProcessId` in each process entry, pass the explicit flag, remove per-pot inputs/validation, and create equal weights after `inputQuantity` is finalized.
- [ ] **Step 4: Run focused tests and `npm run build:check`**.

### Task 5: Verification and integration

**Files:** All files above plus the confirmed spec/plan.

- [ ] **Step 1: Run backend verification:** `mvn -q -Dtest=BomRecipeSeasoningServiceTest,RecipeCostCalculatorTest test`.
- [ ] **Step 2: Run frontend verification:** focused Vitest files followed by `npm run build:check`.
- [ ] **Step 3: Review `git diff --check`, `git status --short`, and requirement coverage**, then commit only the scoped files.
- [ ] **Step 4: Fetch `origin/main`, rebase/merge in a fresh clean release worktree if it moved, merge the feature commit, rerun focused verification, and push `main`.
- [ ] **Step 5: Prove remote inclusion** with `git merge-base --is-ancestor <feature-commit> origin/main`, then remove the owned feature/release worktrees. Do not deploy.

## Self-Review

- Spec coverage: material master, automatic price snapshot, process navigation, explicit pot toggle, fixed later-pot ratio, equal split reporting, compatibility, tests, merge/no-deploy are each assigned above.
- Placeholder scan: no implementation step is deferred; exact contracts and verification commands are named.
- Type consistency: `materialTypeId`, `seasoningPotEnabled`, `subsequentPotRatio`, and `buildEqualPotWeights` are used consistently across tasks.
