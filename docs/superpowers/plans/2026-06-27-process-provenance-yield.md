# Process Provenance Yield Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make process-sheet yield and cost cards calculate and explain partial upstream WIP consumption correctly for mixed SKU, material, batch, process, and cross-day production.

**Architecture:** Keep the existing process-sheet row and upstream-source model. Add a small provenance calculation inside `ProcessSheetServiceImpl` that computes source consumption ratio and inherited raw equivalent for each saved row, then expose those values through `ProcessSheetInventoryItem` and render them in the web-admin yield card. Cost allocation must stay aligned with the existing proportional tracing model in `OrderCostBreakdownService`.

**Tech Stack:** Java 21, Spring Boot 3.2, JUnit 5, Mockito, Vue 3, TypeScript, Playwright headed prod verification.

## Global Constraints

- Use TDD: write and run failing tests before production changes.
- Do not touch gitignored real credential files or copy secrets into tracked files.
- Work only in the isolated worktree `.worktrees/process-provenance-yield`.
- Do not show fake zero costs or fake zero rates when required source data is missing.
- Browser E2E must use headed Playwright with a unique port/chat id if run.

---

### Task 1: Backend Partial-Upstream Provenance Calculation

**Files:**
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ProcessSheetYieldCardTest.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/processentry/ProcessSheetInventoryItem.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java`

**Interfaces:**
- Consumes: `ProcessSheetRowRequest.upstreamSources[].sourceBatchNumber`, `feedQuantityKg`, saved upstream row output/input, and material-batch unit cost.
- Produces: `ProcessSheetInventoryItem.sourceBatchNumber`, `feedQuantity`, `sourceProducedQuantity`, `sourceConsumedRatio`, `inheritedRawEquivalentQuantity`, `inputQuantity`, and corrected `cumulativeYieldRate`.

- [x] **Step 1: Write the failing test**

Add a test where rolling produces `1571.19kg` from raw-equivalent `1440kg`, blanching consumes `765.19kg` from that rolling batch and outputs `604.5kg`. Assert inherited raw equivalent is `701.2988kg`, consumed ratio is `48.7013%`, step yield is `79.0000%`, and cumulative yield is `86.1972%`.

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ProcessSheetYieldCardTest#processSheetRows_partialUpstreamConsumption_usesInheritedRawEquivalent test`

Expected: FAIL because DTO getters or inherited raw/cumulative calculation do not exist.

- [x] **Step 3: Write minimal implementation**

Add DTO fields and calculate upstream source provenance from saved row payloads. For each row with `upstreamSources`, find the referenced upstream saved row by `batchNumber`, compute `sourceConsumedRatio = feedQuantity / sourceProduced`, compute `inheritedRawEquivalent = upstreamInheritedRawEquivalent * sourceConsumedRatio`, and compute `cumulativeYieldRate = produced / inheritedRawEquivalent * 100`.

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ProcessSheetYieldCardTest test`

Expected: PASS.

### Task 2: Frontend Yield Card Explanation UI

**Files:**
- Modify: `web-admin/src/views/production/components/processSheet/YieldCardTable.vue`
- Modify as needed: `web-admin/src/views/production/components/processSheet/types.ts` or API type file if present.

**Interfaces:**
- Consumes: new `ProcessSheetInventoryItem` JSON fields from backend.
- Produces: visible table columns for source batch, feed quantity, consumed ratio, inherited raw equivalent, input quantity, and a non-fake missing-data state.

- [x] **Step 1: Write or update frontend test/type check target**

Run existing process-sheet tests or TypeScript build target to capture current type/UI failure after backend DTO field names are added.

- [x] **Step 2: Implement compact table columns**

Add columns: source batch, feed quantity, source ratio, inherited raw, and input quantity. Keep existing produced/used/remaining/cost/rate columns.

- [x] **Step 3: Verify frontend build/test**

Run the smallest available web-admin type/test command and fix only issues caused by this change.

### Task 3: Headed E2E Verification

**Files:**
- Create under ignored test artifact directory only if needed: `.playwright-mcp/codex-20260627-process-provenance-yield/`

**Interfaces:**
- Consumes: prod web-admin `http://139.196.165.140:8086`, F006 account from gitignored credentials.
- Produces: headed browser evidence that process-sheet modal shows provenance/yield columns and does not show fake zero data for missing source provenance.

- [x] **Step 1: Run headed browser smoke on prod**

Use unique browser context/port/chat id. Log in to F006 through local dev web-admin (`127.0.0.1:5187`) proxied to the prod gateway, open production plan, open process-sheet modal, and capture screenshot.

- [x] **Step 2: Numeric verification**

For a controlled mocked yield-card response (backend not deployed yet), verify rendered inherited raw and cumulative yield match backend math.

- [x] **Step 3: Record result**

Save screenshot/result JSON under `.playwright-mcp/codex-20260627-process-provenance-yield/` and report exact values checked.
