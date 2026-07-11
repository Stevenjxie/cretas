/**
 * API-only setup for the "product process Workflow → clerk 过程单 (process-sheet) → real
 * inventory" (2B) headed E2E — tests/e2e-yield-mixed-sku/headed-workflow-clerk.mjs.
 *
 * Design doc: docs/superpowers/plans/2026-07-11-product-process-workflow-runtime-2b-clerk-implementation.md
 * (read §Testing "Headed web-admin E2E (F006) — the acceptance").
 *
 * This script does NOT open a browser. It builds, via plain HTTP calls against the Java
 * backend (through whatever `E2E_ADMIN_URL` proxies `/api/mobile/*` to — vite dev proxy on
 * localhost, or a deployed gateway), everything the headed script needs:
 *
 *   1. Log in (POST /api/mobile/auth/unified-login) — same endpoint web-admin's login form uses
 *      (web-admin/src/api/auth.ts `login()`), NOT the generic `/auth/login` path.
 *   2. Create 3 WorkProcess catalog rows (front intake / cook / pack), each with an explicit
 *      `defaultOutputMaterialKind` — REQUIRED for ProductProcessWorkflowCatalogValidator to
 *      accept the workflow at publish time (see comments below on why this matters).
 *   3. Ensure a weight-unit RawMaterialType with an AVAILABLE MaterialBatch (reuse an existing
 *      one if F006 already has one; otherwise create a fresh material type + supplier-backed
 *      batch).
 *   4. Create 3 ProductType rows: 2 SEMI_FINISHED (intermediate) + 1 FINISHED_PRODUCT (final).
 *   5. Build a LINEAR single-output-per-process workflow definition JSON
 *      (原料 → 前处理 → 半成品1 → 卤制 → 半成品2 → 包装 → 成品) and:
 *        PUT   /{f}/product-process-workflows/{finishedProductTypeId}/draft
 *        POST  /{f}/product-process-workflows/{finishedProductTypeId}/publish
 *        PUT   /{f}/product-process-workflows/{workflowId}/activation
 *   6. Create a SAFETY_STOCK production plan for the finished product, then 转批次:
 *        POST  /{f}/production-plans
 *        POST  /{f}/production-plans/{planId}/create-batch
 *   7. Sanity-check the clerk-sheet projection endpoint the FE actually calls:
 *        GET   /{f}/production-plans/{planId}/process-sheet/workflow-config
 *      (NOT the path speculated in the design doc's prose — see comment at that call site.)
 *   8. Print + persist a JSON summary the headed script reads.
 *
 * 禁止降级处理: any failed step throws with the exact endpoint + HTTP status + response body.
 * Nothing here silently continues past a failure or fabricates a "looks ok" result.
 *
 * Env:
 *   E2E_ADMIN_URL   default http://localhost:3010  (⚠️ see README — actual vite dev default is
 *                    5173; override to whatever env you're pointing at)
 *   E2E_FACTORY_ID  default F006
 *   E2E_USERNAME    required — must be an admin-capable account (factory_super_admin /
 *                    workshop_supervisor / department_admin) since Workflow draft/publish/
 *                    activation endpoints are role-gated.
 *   E2E_PASSWORD    required
 *
 * Output: .playwright-mcp/workflow-clerk-setup-<ts>.json (path also printed to stdout — feed
 * it to headed-workflow-clerk.mjs via WF_SETUP_FILE, or let that script auto-discover the
 * newest one).
 */
import { writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';

const ADMIN_URL = process.env.E2E_ADMIN_URL || 'http://localhost:3010';
const FACTORY = process.env.E2E_FACTORY_ID || 'F006';
const USERNAME = process.env.E2E_USERNAME;
const PASSWORD = process.env.E2E_PASSWORD;
const PREFIX = 'WF-E2E';
const OUT_DIR = path.resolve('.playwright-mcp');

if (!USERNAME || !PASSWORD) {
  console.error('ERROR: E2E_USERNAME / E2E_PASSWORD are required (admin-capable account — Workflow draft/publish/activation are role-gated).');
  process.exit(1);
}

// -----------------------------------------------------------------------
// Small self-contained helpers (deliberately NOT importing from
// _headed-helpers.mjs — that file pulls in @playwright/test at module scope,
// and this script must stay browser-free per the task).
// -----------------------------------------------------------------------
const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null; };
const arr = (d) => (Array.isArray(d) ? d : (d && (d.content || d.records || d.items || d.processes)) || []);
const today = (offsetDays = 0) => new Date(Date.now() + 8 * 3600e3 + offsetDays * 86400e3).toISOString().slice(0, 10);
const WEIGHT_UNIT_RE = /kg|g|千克|公斤|克|斤/i;
const COUNT_UNIT_RE = /件|个|只|pcs|pc$/i;

let TOKEN = null;

async function api(method, p, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (TOKEN) headers.Authorization = `Bearer ${TOKEN}`;
  const url = `${ADMIN_URL}/api/mobile${p}`;
  const res = await fetch(url, { method, headers, body: body == null ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
  if (!res.ok || json?.success === false) {
    const err = new Error(`${method} ${p} -> HTTP ${res.status}`);
    err.endpoint = `${method} ${p}`;
    err.status = res.status;
    err.body = json ?? text;
    throw err;
  }
  return json && typeof json === 'object' && 'data' in json ? json.data : json;
}

async function login() {
  const url = `${ADMIN_URL}/api/mobile/auth/unified-login`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USERNAME, password: PASSWORD }),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
  if (!res.ok || json?.success === false) {
    const err = new Error(`POST /auth/unified-login -> HTTP ${res.status}`);
    err.endpoint = 'POST /auth/unified-login';
    err.status = res.status;
    err.body = json ?? text;
    throw err;
  }
  const data = (json && typeof json === 'object' && 'data' in json ? json.data : json) || {};
  const token = data.accessToken || data.token;
  if (!token) {
    throw new Error(`login succeeded (HTTP ${res.status}) but response had no accessToken/token field: ${JSON.stringify(data).slice(0, 300)}`);
  }
  TOKEN = token;
  return data;
}

// -----------------------------------------------------------------------
// Step: WorkProcess catalog (real catalog rows the workflow PROCESS nodes
// reference via data.workProcessId). MUST carry an explicit
// defaultOutputMaterialKind — ProductProcessWorkflowCatalogValidator#validateForPublish
// rejects (400 PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH) any process node whose primary
// output Cell kind doesn't equal workProcess.defaultOutputMaterialKind (null included —
// most legacy WorkProcess catalog rows have this unset, so reusing an existing row would
// fail publish). We create fresh, clearly test-marked rows instead of guessing at an
// existing row's configuration.
// -----------------------------------------------------------------------
async function ensureWorkProcess(ts, suffix, unit, defaultOutputMaterialKind) {
  const processName = `${PREFIX}-${suffix}-${ts}`;
  return api('POST', `/${FACTORY}/work-processes`, {
    processName,
    unit,
    defaultOutputMaterialKind, // 'SEMI_FINISHED' | 'FINISHED_GOOD'
    isActive: true,
    processCategory: 'WF_E2E_TEST',
  });
}

// -----------------------------------------------------------------------
// Step: ProductType rows. Node kind (RAW_MATERIAL/SEMI_FINISHED/FINISHED_GOOD) is
// independent of ProductType.productCategory in general — EXCEPT
// ProductProcessWorkflowCatalogValidator#validateOutputSkus, which requires:
//   - a SEMI_FINISHED material Cell's skuId -> ProductType.productCategory == "SEMI_FINISHED"
//   - a FINISHED_GOOD material Cell's skuId -> ProductType.productCategory in
//     {FINISHED_PRODUCT, CONTRACT_MANUFACTURING, CUSTOMER_MATERIAL, DISH, COMBO}
// (see com.cretas.aims.entity.enums.ProductCategory for the exact string constants).
// -----------------------------------------------------------------------
async function createProductType(ts, suffix, unit, productCategory) {
  const name = `${PREFIX}-${suffix}-${ts}`;
  return api('POST', `/${FACTORY}/product-types`, { name, unit, productCategory });
}

// -----------------------------------------------------------------------
// Step: raw material + AVAILABLE batch. Prefer reusing an existing weight-unit
// AVAILABLE batch already sitting in a consumable warehouse (RAW/LOGISTICS/WORKSHOP) —
// F006 almost always has some from prior E2E runs / real data. Only create a fresh
// material type + supplier-backed batch if none exist.
// -----------------------------------------------------------------------
async function ensureRawMaterialAndBatch(ts) {
  const warehouses = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = warehouses.find((w) => w.code === 'WH-LOG')
    || warehouses.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)))
    || warehouses.find((w) => w.code === 'WH-RAW')
    || warehouses[0];
  if (!rawWh) {
    throw new Error('ensureRawMaterialAndBatch: factory has no warehouses at all (GET /factory/warehouses returned empty) — cannot pick a consumable warehouse for a raw batch.');
  }

  const rawTypes = arr(await api('GET', `/${FACTORY}/raw-material-types/active`));
  const existingBatches = arr(await api(
    'GET',
    `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`,
  ));

  for (const b of existingBatches) {
    const unit = String(b.quantityUnit || b.unit || '');
    const bn = String(b.batchNumber || '');
    if (
      num(b.currentQuantity ?? b.quantity) > 5
      && WEIGHT_UNIT_RE.test(unit)
      && !COUNT_UNIT_RE.test(unit)
      && !/^WIP-|^CLK-/.test(bn)
    ) {
      const mt = rawTypes.find((m) => String(m.id) === String(b.materialTypeId));
      if (mt) {
        return { rawMaterialType: mt, batch: b, warehouseId: rawWh.id, created: false };
      }
    }
  }

  // None found — create a fresh, clearly test-marked raw material type + batch.
  const rawMaterialType = await api('POST', `/${FACTORY}/raw-material-types`, {
    name: `${PREFIX}-RawMaterial-${ts}`,
    unit: 'kg',
    category: 'RAW',
  });

  const suppliers = arr(await api('GET', `/${FACTORY}/suppliers/active`));
  const supplierId = suppliers[0]?.id;
  if (!supplierId) {
    throw new Error(
      'ensureRawMaterialAndBatch: no active supplier found (GET /suppliers/active empty) — '
      + 'cannot auto-create a test raw batch (CreateMaterialBatchRequest.supplierId is required). '
      + 'Either add a supplier to this factory, or add an existing weight-unit AVAILABLE raw '
      + 'batch so this script can reuse it instead.',
    );
  }

  const batch = await api('POST', `/${FACTORY}/material-batches`, {
    materialTypeId: rawMaterialType.id,
    supplierId,
    receiptDate: today(0),
    receiptQuantity: 50,
    quantityUnit: 'kg',
    totalWeight: 50,
    totalValue: 500,
    unitPrice: 10,
    warehouseId: rawWh.id,
    sourceDocType: 'MANUAL_ADJUST',
    notes: `${PREFIX} test batch (tests/e2e-yield-mixed-sku/workflow-clerk-setup.mjs)`,
  });

  return { rawMaterialType, batch, warehouseId: rawWh.id, created: true };
}

// -----------------------------------------------------------------------
// Workflow definition builder.
//
// Shape verified against:
//   - backend/.../dto/ProductProcessWorkflowDTO.java (Node/Edge/Port JSON shape)
//   - backend/.../service/validation/ProductProcessWorkflowValidator.java (structural rules:
//     material Cell -> non-blank skuId; PROCESS Cell -> ports[] with >=1 INPUT + hasOutput;
//     each port {id,direction,materialNodeId,unit,ordinal}; edge handles: material-node side
//     literal "input"/"output", process-node side = the port id; every port exactly one edge)
//   - backend/.../service/workflow/ProductProcessWorkflowRuntimeCompiler.java +
//     ProductProcessWorkflowRuntimeCompilerTest.java (process node data needs workProcessId +
//     ports[] with materialKind/unit/ordinal; linearWorkflow() fixture is the direct template
//     this mirrors, extended from 2 to 3 processes)
//   - backend/.../service/validation/ProductProcessWorkflowCatalogValidator.java (catalog
//     cross-checks: workProcessId must resolve to a real WorkProcess row in this factory whose
//     defaultOutputMaterialKind matches the primary OUTPUT port's material kind; output SKU's
//     ProductType.productCategory must match SEMI_FINISHED/FINISHED_* per node kind)
// -----------------------------------------------------------------------
function buildWorkflowDefinition({
  rawMaterialTypeId, semi1Id, semi2Id, finishedId, wp1Id, wp2Id, wp3Id,
}) {
  const node = (id, kind, x, y, data) => ({ id, kind, position: { x, y }, data });
  const port = (id, direction, materialNodeId, materialKind, unit, ordinal) => (
    { id, direction, materialNodeId, materialKind, unit, ordinal }
  );
  const edge = (id, source, sourceHandle, target, targetHandle) => (
    { id, source, sourceHandle, target, targetHandle }
  );

  return {
    schemaVersion: 1,
    nodes: [
      node('raw', 'RAW_MATERIAL', 40, 40, { name: '原料', skuId: rawMaterialTypeId }),
      node('proc1', 'PROCESS', 240, 40, {
        workProcessId: wp1Id,
        processName: `${PREFIX}-前处理`,
        outputUnit: 'kg',
        standardTime: 15,
        reportingRequired: true,
        conversionRule: { mode: 'ACTUAL_WEIGHT' },
        ports: [
          port('proc1-in-raw', 'INPUT', 'raw', 'RAW_MATERIAL', 'kg', 0),
          port('proc1-out-semi1', 'OUTPUT', 'semi1', 'SEMI_FINISHED', 'kg', 0),
        ],
      }),
      node('semi1', 'SEMI_FINISHED', 440, 40, { name: '半成品一', skuId: semi1Id }),
      node('proc2', 'PROCESS', 640, 40, {
        workProcessId: wp2Id,
        processName: `${PREFIX}-卤制`,
        outputUnit: 'kg',
        standardTime: 20,
        reportingRequired: true,
        conversionRule: { mode: 'ACTUAL_WEIGHT' },
        ports: [
          port('proc2-in-semi1', 'INPUT', 'semi1', 'SEMI_FINISHED', 'kg', 0),
          port('proc2-out-semi2', 'OUTPUT', 'semi2', 'SEMI_FINISHED', 'kg', 0),
        ],
      }),
      node('semi2', 'SEMI_FINISHED', 840, 40, { name: '半成品二', skuId: semi2Id }),
      node('proc3', 'PROCESS', 1040, 40, {
        workProcessId: wp3Id,
        processName: `${PREFIX}-包装`,
        outputUnit: '盒',
        standardTime: 10,
        reportingRequired: true,
        conversionRule: { mode: 'ACTUAL_WEIGHT' },
        ports: [
          port('proc3-in-semi2', 'INPUT', 'semi2', 'SEMI_FINISHED', 'kg', 0),
          port('proc3-out-finished', 'OUTPUT', 'finished', 'FINISHED_GOOD', '盒', 0),
        ],
      }),
      node('finished', 'FINISHED_GOOD', 1240, 40, { name: '成品', skuId: finishedId }),
    ],
    edges: [
      edge('e-raw-proc1', 'raw', 'output', 'proc1', 'proc1-in-raw'),
      edge('e-proc1-semi1', 'proc1', 'proc1-out-semi1', 'semi1', 'input'),
      edge('e-semi1-proc2', 'semi1', 'output', 'proc2', 'proc2-in-semi1'),
      edge('e-proc2-semi2', 'proc2', 'proc2-out-semi2', 'semi2', 'input'),
      edge('e-semi2-proc3', 'semi2', 'output', 'proc3', 'proc3-in-semi2'),
      edge('e-proc3-finished', 'proc3', 'proc3-out-finished', 'finished', 'input'),
    ],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

async function main() {
  const ts = Date.now().toString(36);
  console.log(`=== workflow-clerk-setup.mjs — ${PREFIX}-${ts} ===`);
  console.log(`ADMIN_URL=${ADMIN_URL}  FACTORY=${FACTORY}\n`);

  await login();
  console.log('OK  login');

  const wp1 = await ensureWorkProcess(ts, '前处理', 'kg', 'SEMI_FINISHED');
  const wp2 = await ensureWorkProcess(ts, '卤制', 'kg', 'SEMI_FINISHED');
  const wp3 = await ensureWorkProcess(ts, '包装', '盒', 'FINISHED_GOOD');
  console.log(`OK  3 WorkProcess catalog rows: ${wp1.id} / ${wp2.id} / ${wp3.id}`);

  const { rawMaterialType, batch: rawBatch, warehouseId, created: rawCreated } = await ensureRawMaterialAndBatch(ts);
  console.log(
    `OK  raw material "${rawMaterialType.name}" (${rawMaterialType.id}), batch ${rawBatch.batchNumber} `
    + `qty=${rawBatch.currentQuantity ?? rawBatch.quantity} ${rawCreated ? '(created fresh)' : '(reused existing AVAILABLE batch)'}`,
  );

  const semi1 = await createProductType(ts, 'Semi1', 'kg', 'SEMI_FINISHED');
  const semi2 = await createProductType(ts, 'Semi2', 'kg', 'SEMI_FINISHED');
  const finished = await createProductType(ts, 'Final', '盒', 'FINISHED_PRODUCT');
  console.log(`OK  3 ProductType rows: semi1=${semi1.id} semi2=${semi2.id} finished=${finished.id}`);

  const definition = buildWorkflowDefinition({
    rawMaterialTypeId: rawMaterialType.id,
    semi1Id: semi1.id,
    semi2Id: semi2.id,
    finishedId: finished.id,
    wp1Id: wp1.id,
    wp2Id: wp2.id,
    wp3Id: wp3.id,
  });

  const draft = await api('PUT', `/${FACTORY}/product-process-workflows/${finished.id}/draft`, definition);
  console.log(`OK  draft saved — workflow row id=${draft.id} lockVersion=${draft.lockVersion}`);

  const published = await api(
    'POST',
    `/${FACTORY}/product-process-workflows/${finished.id}/publish`,
    { lockVersion: draft.lockVersion },
  );
  console.log(`OK  published — workflowId=${published.id} status=${published.status} lockVersion=${published.lockVersion}`);

  const activation = await api('PUT', `/${FACTORY}/product-process-workflows/${published.id}/activation`);
  console.log(`OK  activated — ${JSON.stringify(activation).slice(0, 200)}`);

  const plan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId: finished.id,
    plannedDate: today(0),
    expectedCompletionDate: today(2),
    sourceType: 'SAFETY_STOCK',
    skipProcessReporting: false, // MUST NOT be true — WorkProcessTaskServiceImpl.spawnTasks
    // short-circuits to the batch-level sentinel-task path BEFORE checking workflow
    // activation when skipProcessReporting===true, bypassing the workflow entirely.
    customerOrderNumber: `${PREFIX}-${ts}`,
    notes: `${PREFIX} workflow-clerk headed E2E setup (${ts})`,
  });
  const planId = String(plan.id || plan.planId);
  const planNumber = String(plan.planNumber || planId);
  console.log(`OK  SAFETY_STOCK plan created — ${planNumber} (${planId}), status=${plan.status}`);

  // Deliberately do NOT call POST /production-plans/{id}/start here: createBatchFromPlan
  // requires plan.status === PENDING (409 otherwise), and /start transitions the plan to
  // IN_PROGRESS. The 2B design doc's flow is plan create -> 转批次 directly.
  const batch = await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/create-batch`);
  const batchId = batch.id;
  const batchNumber = batch.batchNumber;
  console.log(
    `OK  转批次 (create-batch) — batchId=${batchId} batchNumber=${batchNumber} `
    + `workflowSelectionMode=${batch.workflowSelectionMode ?? '(not present on this DTO shape)'}`,
  );

  // Sanity check: the exact endpoint web-admin's ProcessSheet.vue calls
  // (web-admin/src/api/processSheet.ts getWorkflowSheetConfig -> GET .../process-sheet/workflow-config,
  // NOT ".../workflow-sheet-config" as the design doc's prose speculated before implementation).
  const sheetConfig = await api(
    'GET',
    `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/workflow-config`,
  );
  const processes = arr(sheetConfig);
  if (!sheetConfig || processes.length !== 3) {
    throw new Error(
      `workflow-config sanity check FAILED: expected 3 processes, got: ${JSON.stringify(sheetConfig).slice(0, 800)}`,
    );
  }
  const ordered = [...processes].sort((a, b) => (a.processOrder ?? 0) - (b.processOrder ?? 0));
  console.log(`OK  workflow-config sanity — 3 processes: ${ordered.map((p) => p.processName).join(' -> ')}`);

  const summary = {
    ts,
    factoryId: FACTORY,
    adminUrl: ADMIN_URL,
    workProcesses: { proc1: wp1, proc2: wp2, proc3: wp3 },
    rawMaterial: { type: rawMaterialType, batch: rawBatch, warehouseId },
    productTypes: { semi1, semi2, finished },
    workflow: {
      productTypeId: finished.id,
      draftId: draft.id,
      workflowId: published.id,
      lockVersionAfterPublish: published.lockVersion,
      activation,
    },
    plan: { planId, planNumber, status: plan.status },
    batch: { batchId, batchNumber },
    processes: ordered.map((p) => ({
      processOrder: p.processOrder,
      workflowNodeId: p.workflowNodeId,
      workProcessId: p.workProcessId,
      processName: p.processName,
      plannedUnit: p.plannedUnit,
      allowMultipleUpstreamSources: p.allowMultipleUpstreamSources,
      allowFinishedGoodsSource: p.allowFinishedGoodsSource,
      inputs: p.inputs,
      output: p.output,
    })),
  };

  await mkdir(OUT_DIR, { recursive: true });
  const outFile = path.join(OUT_DIR, `workflow-clerk-setup-${ts}.json`);
  await writeFile(outFile, JSON.stringify(summary, null, 2), 'utf8');

  console.log('\n=== SETUP COMPLETE ===');
  console.log(JSON.stringify(summary, null, 2));
  console.log(`\nSummary written to: ${outFile}`);
  console.log(`\nNext:\n  PLAYWRIGHT_PORT=9222 WF_SETUP_FILE=${outFile} node tests/e2e-yield-mixed-sku/headed-workflow-clerk.mjs`);
}

main().catch((e) => {
  console.error('\n=== SETUP FAILED ===');
  if (e.endpoint) console.error(`Endpoint: ${e.endpoint}  (HTTP ${e.status})`);
  console.error(e.message);
  if (e.body) console.error('Response body:', JSON.stringify(e.body, null, 2).slice(0, 3000));
  else if (e.stack) console.error(e.stack);
  process.exitCode = 1;
});
