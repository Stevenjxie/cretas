# Workflow → Clerk 过程单 (2B) Headed E2E — Runbook

Proves: `product process Workflow` (editor → publish → activate) → a `SAFETY_STOCK` plan's
逐道录入 (clerk 过程单 / ProcessSheet) → real inventory movement (`MaterialBatch.usedQuantity`,
`SemiFinishedInventory`, `FinishedGoodsBatch`), end to end in the browser.

Design doc: `docs/superpowers/plans/2026-07-11-product-process-workflow-runtime-2b-clerk-implementation.md`
(§Testing has the acceptance criteria this script implements).

Branch: `codex/product-process-workflow` (this worktree). **This was written without a live
run** — see "What to watch for" below before trusting a first FAIL as a real regression.

---

## Files

| File | What it does |
|---|---|
| `workflow-clerk-setup.mjs` | API-only (no browser). Builds the WorkProcess catalog rows, raw material + batch, 3 ProductTypes, the workflow definition (published + activated), a SAFETY_STOCK plan, and 转批次's it. Writes a JSON summary. |
| `headed-workflow-clerk.mjs` | Headed browser (Playwright, `headless:false`). Reads the setup summary, opens 过程单, fills + saves the 3 workflow-driven rows, triggers 小结, asserts real inventory moved. |
| `WORKFLOW-CLERK-E2E-README.md` | This file. |

---

## 1. Bring up a stack to test against

Either **local** (recommended first pass — fastest iteration, uses your local Postgres):

```bash
# Terminal 1 — backend (local, against local Postgres per .claude/rules/CREDENTIAL-MANAGEMENT.md)
cd backend/java/cretas-api
mvn spring-boot:run          # NOT `java -jar` — see server-operations.md note 11

# Terminal 2 — web-admin
cd web-admin
npm install --prefer-offline --legacy-peer-deps   # if not already installed
npm run dev                  # vite — actual default port is 5173, NOT 3010
```

`web-admin/vite.config.ts` proxies `/api` → `http://localhost:10010` (the local backend) by
default, so `E2E_ADMIN_URL=http://localhost:5173` is the right value for this setup — **not**
the `http://localhost:3010` default baked into both scripts (that default mirrors the RN Expo
port from `CLAUDE.md`'s port table by mistake / by convention carried over from other scripts
in this dir; always override it here).

Or **any already-deployed env** where 2B is live (e.g. a test/staging web-admin + backend pair)
— just point `E2E_ADMIN_URL` at that web-admin origin (it must proxy/serve `/api/mobile/*` to
the same backend).

You need an **admin-capable account** for `E2E_USERNAME`/`E2E_PASSWORD` — Workflow
draft/publish/activation endpoints are role-gated to `factory_super_admin` /
`workshop_supervisor` / `department_admin`. Do not put real credentials in this repo; export
them in your shell.

---

## 2. Run

```bash
cd tests/e2e-yield-mixed-sku

# Step A — API-only setup (no browser). Prints + writes a JSON summary.
E2E_ADMIN_URL=http://localhost:5173 \
E2E_FACTORY_ID=F006 \
E2E_USERNAME=<your admin account> \
E2E_PASSWORD=<your password> \
node workflow-clerk-setup.mjs

# Step B — headed browser E2E. Auto-discovers the newest setup summary under
# .playwright-mcp/ (or pass WF_SETUP_FILE=<path> explicitly).
E2E_ADMIN_URL=http://localhost:5173 \
E2E_FACTORY_ID=F006 \
E2E_USERNAME=<your admin account> \
E2E_PASSWORD=<your password> \
PLAYWRIGHT_PORT=9222 \
node headed-workflow-clerk.mjs
```

Both scripts are parameterized purely by env vars — no URLs or credentials are hardcoded.
`workflow-clerk-setup.mjs` is safe to re-run: it always creates a fresh, clearly test-marked
(`WF-E2E-` prefixed name) product/workflow/plan each time rather than mutating anything
existing, so re-running never touches prior runs' data or any real product/plan in the factory.

Results:
- `workflow-clerk-setup.mjs` → `.playwright-mcp/workflow-clerk-setup-<ts>.json` (full trace of
  every id created + the workflow-config sanity check).
- `headed-workflow-clerk.mjs` → `.playwright-mcp/workflow-clerk-<timestamp>/` — screenshots,
  video, `headed-workflow-clerk-result.json` (PASS/PARTIAL/FAIL + every assertion).

---

## 3. What each step asserts (mapped to the design doc's acceptance criteria)

| Design doc step | Script | What's asserted |
|---|---|---|
| 1. Configure a linear single-output-per-process workflow, publish, activate | `workflow-clerk-setup.mjs` | Each API call (draft/publish/activation) must return 2xx `success:true`, or the script aborts with the exact endpoint + response body. |
| 2. Create a SAFETY_STOCK plan, 转批次 (spawns workflow tasks) | `workflow-clerk-setup.mjs` | Same — plan create + create-batch must succeed; a final sanity `GET .../process-sheet/workflow-config` must return exactly 3 processes. |
| 3. Open 过程单 → workflow processes appear with planned outputs/units | `headed-workflow-clerk.mjs` | Each of the 3 process tabs is visible by its real `processName`; `.sp-workflow-banner` is visible with "计划产出" text; the 成品/半成品 tag matches `output.finished`. |
| 4. Enter a row per process, save each | `headed-workflow-clerk.mjs` | Each save returns a success toast AND the toast text does **not** contain `WORKFLOW_ROW_OUTPUT_KIND_MISMATCH` / `WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH` / `409` — this is the exact bug the design doc's F1/F2 adversarial-review fixes resolved (FE deriving `finished`/`unit` from the archetype heuristic instead of the workflow port). A 409 here is a **regression**, not a flaky test. |
| (F3 task-stamp, not a numbered doc step but explicitly called out) | `headed-workflow-clerk.mjs` | `GET .../production-batches/{batchId}/workflow-runtime` → all 3 `WorkProcessTask`s have `status === 'COMPLETED'`. |
| 5. 小结 → real inventory moved | `headed-workflow-clerk.mjs` | Raw `MaterialBatch.usedQuantity` > 0 after settle; `SemiFinishedInventory` has rows with `availableQuantity` for both intermediate products; `FinishedGoodsBatch`/inventory has a row with `availableQuantity` > 0 for the finished product. |
| `MaterialConsumption`/`BatchLineageEdge` lineage | *not directly asserted* | No read endpoint exposing these was found while writing this script (see code comment at the bottom of `headed-workflow-clerk.mjs`). The inventory-quantity deltas + all-tasks-COMPLETED above are the closest observable proxies. If you have DB access, a stronger check is: `SELECT * FROM material_consumptions WHERE production_batch_id = <batchId>` and the corresponding `batch_lineage_edges` rows. |
| 6. Multi-output guard rejects activation | *not covered* | Backend-unit-tested per the design doc (`ProductProcessWorkflowActivationServiceImpl` + `WorkflowSingleOutputGuard`); out of scope for this script, which proves the single-output happy path. |

---

## 4. Manual fallback click-through

If a selector in `headed-workflow-clerk.mjs` is stale, here's the same flow done by hand in
web-admin (after running `workflow-clerk-setup.mjs` to get the ids, or building your own test
product+workflow the same way via the Workflow editor UI):

1. **系统设置 → 产品管理 → Workflow 配置** (or wherever the Workflow graph editor is mounted
   for the finished product) — build a linear chain: 原料 Cell → PROCESS Cell (前处理, 1
   INPUT/RAW + 1 OUTPUT/SEMI_FINISHED port) → 半成品1 Cell → PROCESS Cell (卤制, 1 INPUT/SEMI +
   1 OUTPUT/SEMI port) → 半成品2 Cell → PROCESS Cell (包装, 1 INPUT/SEMI + 1 OUTPUT/FINISHED_GOOD
   port) → 成品 Cell. Each PROCESS Cell's 工序 dropdown must point at a real 工序目录 (WorkProcess)
   row whose "产出类型" (defaultOutputMaterialKind) matches that Cell's primary output kind —
   this is enforced by `ProductProcessWorkflowCatalogValidator` at publish time and is easy to
   miss if you build the workflow by hand (see `workflow-clerk-setup.mjs`'s comments for why).
2. 保存草稿 → 发布 → 启用 (activate).
3. **生产计划 → 新建计划**: 来源类型 = 存货生产 (SAFETY_STOCK), 产品 = your finished product,
   计划量可留空.
4. On the new plan's row, click **转批次** (labeled "APP报工" in the UI) — this spawns the
   workflow's `WorkProcessTask`s. *Do not* click 开始生产 (start) first — that transitions the
   plan out of `PENDING`, and 转批次 requires `PENDING`.
5. Click **逐道录入** on the same row → the 过程单 drawer opens. If it opens in **卡片
   (card)** view, switch the toggle at top-right to **电子表格 (grid)** — the layout differs
   but the same fields exist either way.
6. Confirm each of the 3 tabs shows a blue info banner at the top reading "计划产出：<name>（<
   unit>）" with a 半成品/成品 tag, and (for processes with declared raw inputs) "需要原料：<
   name>".
7. **Tab 1 (前处理)**: + 新增行 → 选原料批次 (a weight-unit AVAILABLE batch) → fill 出库重量(kg)
   and 产出数量(kg) → 保存. Expect a green success toast, no error.
8. **Tab 2 (卤制)**: + 新增行 → the 上游批次 dropdown should show the batch you just produced in
   tab 1 (under "本计划在制半成品") → pick it, fill some 投入(kg) ≤ its 余量 and 产出(kg) → 保存.
   Leave some of the upstream batch's quantity unconsumed (feed less than 100%) so it still
   shows up as available inventory after 小结.
9. **Tab 3 (包装)**: + 新增行 → pick tab 2's output batch as upstream → fill 入库(盒) (any
   positive number) and 使用重量(kg) (some kg ≤ tab 2's leftover) → 保存. This tab looks
   different (入库/留样/剩余/领用/成品重/料头/使用重量/单盒克重/工时单价) because a finished-goods
   output always renders the 气调-style form regardless of the process's real name.
10. Close the drawer. On the plan's row, click **小结** → confirm in the dialog. Expect a
    success toast ("已小结…").
11. Verify real inventory: **仓库 → 原料库存** (the raw batch's 已用/剩余 should reflect the
    consumption), **仓库 → 半成品库存** (filter by the two intermediate products — should show
    remaining quantity), **仓库 → 成品库存** (the finished product should show a new batch with
    available quantity).

---

## 5. Places to watch (written without a live run)

Ordered roughly by how likely they are to need a tweak:

1. **`el-input-number` column indices** (`nums.nth(N)`) for the 卤制/包装 rows. These were
   derived by reading `PROCESS_SHEET_CONFIG.ts`'s column arrays for the `chaoshui`/`qidiao`
   archetypes and cross-checked against the exact same index usage in
   `headed-config-to-production.mjs` / `headed-matrix-fullchain.mjs` — but if a column gets
   reordered or a customer-config custom field gets inserted ahead of these, the indices shift.
   If a fill lands in the wrong box, print `await row.locator('.el-input-number').count()` and
   walk the column headers (`thead th`) to re-map.
2. **`selectByText` matching the produced WIP batch number**. This depends on `wipLabel()`
   including the raw `item.batchNumber` substring in the option text (confirmed by reading the
   source), and on the dropdown actually containing an option (i.e. the prior row's save
   produced a WIP batch the endpoint can see). If the select comes up empty, check
   `GET /{f}/production-plans/{planId}/process-sheet/inventory?process=<code>&processOrder=<n>`
   directly.
3. **"小结" button text/selector on the plan list row** — matched via `hasText: /^小结$/`
   against `list.vue`'s `>小结</el-button>`. If web-admin renders differently (e.g. wrapped in
   extra whitespace/icon), loosen the regex.
4. **Grid vs card view toggle**. The script force-sets `localStorage['sp-f-process-sheet-view']
   = 'grid'` right after login, before navigating to the plans page. If `ProcessSheet.vue`'s
   view-mode key or default ever changes, the grid-mode `table.sp-grid` selectors this script
   (and its siblings) depend on will need the toggle clicked in the UI instead.
5. **workProcessId / productCategory constraints** (setup script only). If `workflow-clerk-setup.mjs`
   fails at the `publish` step with `PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH`, re-read that
   error's `message`/`hint` — it names the exact Cell and reason (this validator is strict by
   design: WorkProcess.defaultOutputMaterialKind must match the Cell kind, and
   ProductType.productCategory must be `SEMI_FINISHED`/`FINISHED_PRODUCT` for the respective
   Cell kinds).
6. **Auth**: the login endpoint used by `workflow-clerk-setup.mjs` is
   `POST /api/mobile/auth/unified-login` (mirroring `web-admin/src/api/auth.ts`), not the more
   generic `/api/mobile/auth/login` path that appears in some other docs — this was verified
   against the actual controller mapping (`MobileController`), not assumed.
7. **`GET .../process-sheet/workflow-config` endpoint path**. Verified against
   `ProcessSheetController.java` + `web-admin/src/api/processSheet.ts`'s
   `getWorkflowSheetConfig()` — this is `.../process-sheet/workflow-config`, which differs from
   the path speculated in the design doc's prose (`.../workflow-sheet-config`). If the doc's
   Task B2 gets re-implemented under a different final path, update both scripts' `api(...)`
   calls to that endpoint accordingly.

---

## Headed Mode Verification (per `.claude/rules/playwright-headed-mode.md`)

`headed-workflow-clerk.mjs` inherits its browser config entirely from
`_headed-helpers.mjs`'s `startHeaded()` (already used and verified by every sibling script in
this directory):

- `headless: false` ✓
- `viewport: 1920×1080` ✓
- `--lang=zh-CN` ✓
- Screenshots via `shot()` at every key step ✓
- `PLAYWRIGHT_PORT` respected for multi-chat isolation ✓
- Video recording: ⚠️ **not actually configured** — `_headed-helpers.mjs`'s `startHeaded()`
  calls `chromium.launchPersistentContext()` without a `recordVideo` option, so no `.webm` is
  produced. This is a pre-existing gap shared by every sibling script in this directory (not
  something introduced here). If you need video, add
  `recordVideo: { dir: outDir, size: { width: 1920, height: 1080 } }` to that
  `launchPersistentContext()` call in `_headed-helpers.mjs` — screenshots via `shot()` are the
  only artifact this script currently produces per step.

No other changes needed — just run it per §2 above.
