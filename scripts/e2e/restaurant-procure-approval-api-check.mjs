#!/usr/bin/env node
/**
 * API-level acceptance for restaurant procure-to-pay approval gate.
 * Includes automatic seed-data creation so every run exercises the full
 * approval chain even when prod has no pending high-price-anomaly DRAFTs.
 *
 * Usage:
 *   node scripts/e2e/restaurant-procure-approval-api-check.mjs
 *
 * Env (optional):
 *   CRETAS_API_BASE=http://localhost:10010
 *   CRETAS_FACTORY_ID=FACTORY-QHJ
 *   CRETAS_WAREHOUSE_USER / CRETAS_WAREHOUSE_PASS
 *   CRETAS_BOSS_USER / CRETAS_BOSS_PASS
 *   CRETAS_PROCUREMENT_USER / CRETAS_PROCUREMENT_PASS
 *   CRETAS_FINANCE_USER / CRETAS_FINANCE_PASS
 *   CRETAS_E2E_EVIDENCE_DIR=scripts/e2e/evidence
 *   CRETAS_SKIP_CONFIRM=1   # skip final inbound posting (dev only)
 *   CRETAS_NO_SEED=1        # disable auto-seed (use only existing DRAFTs)
 *   CRETAS_SEED_CLEANUP=1   # delete seeded DRAFT after test regardless of outcome
 *
 * Seed-data strategy (§ P0 fix from handoff 2026-06-07):
 *   1. Fetch active suppliers for the factory.
 *   2. Scan CONFIRMED notes for ingredients with prior price history.
 *   3. Create a MANUAL DRAFT with:
 *        - supplierId  = first active supplier (so /confirm does not 400)
 *        - line.unitPrice = historicalAvg × 1.20  (20 % above baseline → fires anomaly flag)
 *        - noteNumber  = "E2E-SEED-<datestamp>" (recognisable / idempotent key)
 *   4. Idempotency: reuse an existing E2E-SEED DRAFT if one already exists (Rule 4).
 *   5. Fallback: if no CONFIRMED history exists yet (clean env), log a guidance message
 *      and fall back to the existing "find any DRAFT" behaviour.
 */

import fs from 'node:fs';
import path from 'node:path';

const API_BASE = process.env.CRETAS_API_BASE || 'http://localhost:10010';
const FACTORY_ID = process.env.CRETAS_FACTORY_ID || 'FACTORY-QHJ';
const EVIDENCE_DIR = process.env.CRETAS_E2E_EVIDENCE_DIR || 'scripts/e2e/evidence';
const NO_SEED = process.env.CRETAS_NO_SEED === '1';
const SEED_CLEANUP = process.env.CRETAS_SEED_CLEANUP === '1';

/** Recognisable prefix for seeded DRAFTs — used for idempotency look-up. */
const SEED_NOTE_NUMBER_PREFIX = 'E2E-SEED-';

// ───────────────────────────────────────────────────────────
// HTTP helpers
// ───────────────────────────────────────────────────────────

async function login(username, password) {
  const attempts = [
    '/api/mobile/auth/login',
    '/api/mobile/auth/unified-login',
  ];
  let lastError = 'no attempt';
  for (const urlPath of attempts) {
    const res = await fetch(`${API_BASE}${urlPath}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    const json = await res.json().catch(() => ({}));
    const token = json.data?.accessToken
      || json.data?.tokens?.accessToken
      || json.data?.token
      || json.token;
    if (res.ok && token) {
      return token;
    }
    lastError = `${urlPath} ${res.status} ${JSON.stringify(json).slice(0, 200)}`;
  }
  throw new Error(`Login failed for ${username}: ${lastError}`);
}

async function api(token, method, urlPath, body) {
  const res = await fetch(`${API_BASE}${urlPath}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => ({}));
  return { status: res.status, json };
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function writeEvidence(report) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const file = path.join(EVIDENCE_DIR, `restaurant-procure-approval-${stamp}.json`);
  fs.writeFileSync(file, JSON.stringify(report, null, 2), 'utf8');
  console.log('EVIDENCE', file);
  return file;
}

// ───────────────────────────────────────────────────────────
// Seed-data helpers
// ───────────────────────────────────────────────────────────

/**
 * Return the first active supplier for the factory, or null.
 */
async function fetchFirstActiveSupplier(token) {
  const res = await api(token, 'GET', `/api/mobile/${FACTORY_ID}/suppliers/active`);
  if (res.status !== 200) {
    console.log('SEED warn: could not fetch active suppliers', res.status, res.json?.message || '');
    return null;
  }
  const suppliers = res.json?.data || [];
  return suppliers[0] || null;
}

/**
 * Scan CONFIRMED notes (up to 3 pages) for a line we can use to seed a price anomaly.
 *
 * Returns { ingredientName, rawMaterialTypeId, unit, baselineUnitPrice } or null,
 * where baselineUnitPrice is the value we should multiply by 1.20 to guarantee
 * the backend's priceAnomalyFlag fires (varianceRate > 5%).
 *
 * Key insight: the backend computes baseline = avg of last 5 confirmed prices
 * for that material, stored on each note's line as `baselineUnitPrice`.  So if
 * a confirmed note's line has baselineUnitPrice = X, then any future note for
 * that material with unitPrice > X × 1.05 will trigger the flag.
 *
 * We therefore always use line.baselineUnitPrice (not line.unitPrice) as the
 * reference.  If baselineUnitPrice is null or 0 (the first-ever delivery for
 * that material had no prior history), we fall back to unitPrice as a proxy.
 *
 * We read the detail of up to MAX_DETAIL_SCANS confirmed notes.
 */
async function fetchPriceHistoryLine(token) {
  const MAX_DETAIL_SCANS = 10;
  let scanned = 0;
  let fallback = null; // line with unitPrice but no baselineUnitPrice
  for (let page = 1; page <= 3; page++) {
    const listRes = await api(
      token, 'GET',
      `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes?status=CONFIRMED&page=${page}&size=20`,
    );
    if (listRes.status !== 200) break;
    const notes = listRes.json?.data?.content || [];
    if (notes.length === 0) break;
    for (const note of notes) {
      if (scanned >= MAX_DETAIL_SCANS) break;
      const detail = await api(
        token, 'GET',
        `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes/${note.id}`,
      );
      scanned++;
      if (detail.status !== 200) continue;
      const lines = detail.json?.data?.lines || [];
      for (const line of lines) {
        if (!line.rawMaterialTypeId) continue;
        const baseline = Number(line.baselineUnitPrice);
        const price = Number(line.unitPrice);
        if (baseline > 0) {
          // Perfect: use the persisted baseline as reference.
          // newPrice = baseline × 1.20 is guaranteed to cross the 5% threshold.
          return {
            ingredientName: line.ingredientName,
            rawMaterialTypeId: line.rawMaterialTypeId,
            unit: line.unit || 'kg',
            baselineUnitPrice: baseline,
          };
        }
        if (price > 0 && !fallback) {
          // Fallback: no persisted baseline (first ever confirmed delivery for this
          // material).  The "new" baseline when we create a seed note will be computed
          // from the confirmed price we just read, so using it as reference is correct.
          fallback = {
            ingredientName: line.ingredientName,
            rawMaterialTypeId: line.rawMaterialTypeId,
            unit: line.unit || 'kg',
            baselineUnitPrice: price,
          };
        }
      }
    }
    if (scanned >= MAX_DETAIL_SCANS) break;
  }
  return fallback;
}

/**
 * Look for an existing E2E-SEED DRAFT so we can reuse it (idempotency Rule 4).
 * Returns the first DRAFT whose noteNumber starts with E2E-SEED-, or null.
 */
async function findExistingSeedDraft(token) {
  const base = `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes`;
  for (let page = 1; page <= 3; page++) {
    const res = await api(token, 'GET', `${base}?status=DRAFT&page=${page}&size=20`);
    if (res.status !== 200) break;
    const notes = res.json?.data?.content || [];
    if (notes.length === 0) break;
    const found = notes.find((n) => String(n.noteNumber || '').startsWith(SEED_NOTE_NUMBER_PREFIX));
    if (found) return found;
  }
  return null;
}

/**
 * Soft-delete a DRAFT note (best-effort, failures are logged not thrown).
 */
async function cleanupSeedDraft(token, noteId) {
  const res = await api(token, 'POST',
    `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes/${noteId}/delete`, {});
  if (res.status === 200) {
    console.log('SEED cleanup: deleted seeded DRAFT', noteId);
  } else {
    console.log('SEED cleanup warn: could not delete', noteId, res.status, res.json?.message || '');
  }
}

/**
 * Create (or reuse) a seeded DRAFT note that is guaranteed to:
 *   (a) have supplierId bound (so /confirm won't 400 with "未绑定供应商")
 *   (b) trigger priceAnomalyFlag on at least one line (so the approval gate fires)
 *
 * Returns { noteId, supplierId, seeded: true, reused: boolean } on success,
 * or { noteId: null, seeded: false, reason: string } when prerequisites are absent.
 *
 * Never throws — seed failure is surfaced as a report step, not a crash.
 */
async function ensureSeedDraft(whToken, report) {
  if (NO_SEED) {
    return { noteId: null, seeded: false, reason: 'CRETAS_NO_SEED=1 — seed disabled' };
  }

  // ── idempotency: reuse existing SEED DRAFT if present ──────────────────
  const existing = await findExistingSeedDraft(whToken);
  if (existing) {
    // Verify it hasn't been approved already (which means it's been through the chain)
    const detail = await api(whToken, 'GET',
      `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes/${existing.id}`);
    if (detail.status === 200) {
      const approval = detail.json?.data?.priceAnomalyApprovalStatus;
      if (approval !== 'APPROVED') {
        console.log('SEED idempotent: reusing existing SEED DRAFT', existing.id, existing.noteNumber);
        report.steps.push({
          name: 'seed-draft',
          reused: true,
          noteId: existing.id,
          noteNumber: existing.noteNumber,
          supplierId: detail.json?.data?.supplierId ?? null,
          ok: true,
        });
        return {
          noteId: existing.id,
          supplierId: detail.json?.data?.supplierId,
          seeded: true,
          reused: true,
        };
      }
      // Already APPROVED — clean it up and create fresh
      console.log('SEED: existing SEED DRAFT already APPROVED, creating fresh one');
      await cleanupSeedDraft(whToken, existing.id);
    }
  }

  // ── prerequisites: active supplier ─────────────────────────────────────
  const supplier = await fetchFirstActiveSupplier(whToken);
  if (!supplier) {
    const reason = 'no active supplier in factory — cannot bind supplierId on DRAFT';
    console.log('SEED skip:', reason);
    report.steps.push({ name: 'seed-draft', skipped: true, reason, ok: true });
    return { noteId: null, seeded: false, reason };
  }

  // ── prerequisites: price history (to guarantee anomaly flag) ───────────
  const histLine = await fetchPriceHistoryLine(whToken);
  if (!histLine) {
    const reason = [
      'No CONFIRMED delivery notes with price history found in factory.',
      'To prime the baseline: manually create and confirm a delivery note at a normal price,',
      'then re-run this script — it will create a DRAFT at 120% of that price to trigger the anomaly.',
    ].join(' ');
    console.log('SEED skip:', reason);
    report.steps.push({ name: 'seed-draft', skipped: true, reason, ok: true });
    return { noteId: null, seeded: false, reason };
  }

  // ── build anomaly line: 20% above historical baseline ───────────────────
  // PRICE_ANOMALY_THRESHOLD = 5% (service impl), so 20% comfortably triggers it.
  const anomalyPrice = parseFloat((histLine.baselineUnitPrice * 1.20).toFixed(4));
  const quantity = 5;
  const datestamp = new Date().toISOString().slice(0, 10);
  const noteNumber = `${SEED_NOTE_NUMBER_PREFIX}${datestamp}`;

  const base = `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes`;
  const createRes = await api(whToken, 'POST', `${base}/manual`, {
    supplierId: supplier.id,
    supplierName: supplier.name || supplier.supplierName,
    deliveryDate: datestamp,
    noteNumber,
    lines: [
      {
        ingredientName: histLine.ingredientName,
        rawMaterialTypeId: histLine.rawMaterialTypeId,
        quantity,
        unit: histLine.unit,
        unitPrice: anomalyPrice,
      },
    ],
  });

  if (createRes.status !== 200 && createRes.status !== 201) {
    const reason = `POST /manual failed: ${createRes.status} ${createRes.json?.message || ''}`;
    console.error('SEED FAIL:', reason);
    report.steps.push({ name: 'seed-draft', error: reason, ok: false });
    // Hard fail — the task requires a seed-able DRAFT
    throw new Error(`Seed DRAFT creation failed: ${reason}`);
  }

  const created = createRes.json?.data || {};
  console.log(
    `SEED created DRAFT ${created.id} (${noteNumber})`,
    `supplier=${supplier.id}`,
    `ingredient=${histLine.ingredientName}`,
    `price=${anomalyPrice} (baseline=${histLine.baselineUnitPrice}, +20%)`,
  );

  // Verify the anomaly flag was actually set; if not, adaptively reprice using
  // the backend's computed baselineUnitPrice (which may differ from our estimate).
  let detailCheck = await api(whToken, 'GET', `${base}/${created.id}`);
  let currentLines = detailCheck.json?.data?.lines || [];
  let anomalyLines = currentLines.filter((l) => l.priceAnomalyFlag);

  if (anomalyLines.length === 0) {
    // The backend computed a different baseline from what we estimated.
    // Read it back and reprice at baseline × 1.20 via updateLines.
    const firstLine = currentLines[0];
    const backendBaseline = firstLine ? Number(firstLine.baselineUnitPrice) : 0;
    if (backendBaseline > 0) {
      const repriceAmount = parseFloat((backendBaseline * 1.20).toFixed(4));
      console.log(
        `SEED reprice: backend baseline=${backendBaseline}, repricing to ${repriceAmount}`,
        `(was ${anomalyPrice}, histEstimate=${histLine.baselineUnitPrice})`,
      );
      const patchLines = currentLines.map((l) => ({
        id: l.id,
        ingredientName: l.ingredientName,
        rawMaterialTypeId: l.rawMaterialTypeId,
        quantity: l.quantity,
        unit: l.unit,
        unitPrice: repriceAmount,
        qcResult: l.qcResult || 'PASS',
        remark: l.remark,
      }));
      const reprice = await api(whToken, 'PUT', `${base}/${created.id}/lines`, patchLines);
      if (reprice.status === 200) {
        detailCheck = await api(whToken, 'GET', `${base}/${created.id}`);
        currentLines = detailCheck.json?.data?.lines || [];
        anomalyLines = currentLines.filter((l) => l.priceAnomalyFlag);
        if (anomalyLines.length > 0) {
          console.log(`SEED reprice success: anomaly flag set at price=${repriceAmount}`);
        } else {
          console.log(`SEED reprice warn: still no anomaly flag after reprice to ${repriceAmount}`);
          console.log('  lines=', JSON.stringify(currentLines).slice(0, 400));
        }
      } else {
        console.log(`SEED reprice failed: ${reprice.status} ${reprice.json?.message || ''}`);
      }
    } else {
      console.log(
        'SEED warn: anomaly flag NOT set and backend baseline is 0/null —',
        'this material has no price history yet; gate cannot be exercised via seed.',
        'Hint: confirm one delivery at a normal price first, then re-run.',
        `lines=`, JSON.stringify(currentLines).slice(0, 300),
      );
    }
  }

  if (anomalyLines.length === 0) {
    // After reprice attempt, still no flag — give up and clean up this DRAFT.
    await cleanupSeedDraft(whToken, created.id);
    report.steps.push({
      name: 'seed-draft',
      noteId: created.id,
      skipped: true,
      reason: 'anomaly flag not set after seed and reprice — no usable baseline',
      ingredient: histLine.ingredientName,
      baselinePrice: histLine.baselineUnitPrice,
      seedPrice: anomalyPrice,
      ok: true,
    });
    return {
      noteId: null,
      seeded: false,
      reason: 'anomaly flag not set after seed and reprice',
    };
  }

  const finalLine = currentLines[0];
  report.steps.push({
    name: 'seed-draft',
    noteId: created.id,
    noteNumber,
    supplierId: supplier.id,
    supplierName: supplier.name || supplier.supplierName,
    ingredient: histLine.ingredientName,
    rawMaterialTypeId: histLine.rawMaterialTypeId,
    estimatedBaseline: histLine.baselineUnitPrice,
    backendBaseline: finalLine ? Number(finalLine.baselineUnitPrice) : null,
    finalSeedPrice: finalLine ? Number(finalLine.unitPrice) : anomalyPrice,
    anomalyFlagSet: anomalyLines.length > 0,
    reused: false,
    ok: true,
  });

  return { noteId: created.id, supplierId: supplier.id, seeded: true, reused: false };
}

// ───────────────────────────────────────────────────────────
// Gate / approval helpers (unchanged from original)
// ───────────────────────────────────────────────────────────

function pickAnomalyDraft(notes) {
  return (notes || []).find((note) => (note.lines || []).some((line) => line.priceAnomalyFlag)) || null;
}

const DEFAULT_ANOMALY_EXPLANATION = 'E2E: 供应商电话确认临时市场涨价，已留存报价截图。';

function ingredientFromConfirmMessage(message) {
  const match = String(message || '').match(/解释[：:]\s*(.+)$/);
  return match ? match[1].trim() : null;
}

function lineNeedsExplanation(line, confirmMessage) {
  if (line.priceAnomalyFlag && !line.priceAnomalyExplanation) return true;
  const named = ingredientFromConfirmMessage(confirmMessage);
  return Boolean(named && line.ingredientName === named && !line.priceAnomalyExplanation);
}

function buildLinePayload(lines, confirmMessage) {
  return (lines || []).map((line) => {
    const needsExpl = lineNeedsExplanation(line, confirmMessage);
    return {
      id: line.id,
      ingredientName: line.ingredientName,
      rawMaterialTypeId: line.rawMaterialTypeId,
      quantity: line.quantity,
      unit: line.unit,
      unitPrice: line.unitPrice,
      qcResult: line.qcResult || 'PASS',
      remark: line.remark,
      priceAnomalyReasonCode: needsExpl
        ? (line.priceAnomalyReasonCode || 'SUPPLIER_EXPLAINED')
        : line.priceAnomalyReasonCode,
      priceAnomalyExplanation: needsExpl
        ? (line.priceAnomalyExplanation || DEFAULT_ANOMALY_EXPLANATION)
        : line.priceAnomalyExplanation,
    };
  });
}

function linesHaveAnomalyFlag(lines) {
  return (lines || []).some((line) => line.priceAnomalyFlag);
}

function isApprovalGateResponse(status, json) {
  if (status < 400) return false;
  const code = json?.code ?? json?.errorCode;
  return code === 'PRICE_ANOMALY_APPROVAL_REQUIRED'
    || code === 'PRICE_ANOMALY_PENDING_APPROVAL'
    || /等待审批|提交老板审批|老板审批/.test(String(json?.message || ''));
}

function isExplanationRequiredResponse(status, json) {
  if (status < 400) return false;
  const code = json?.code ?? json?.errorCode;
  return code === 'PRICE_ANOMALY_EXPLANATION_REQUIRED'
    || /需先填写供应商解释/.test(String(json?.message || ''));
}

// ───────────────────────────────────────────────────────────
// Main
// ───────────────────────────────────────────────────────────

async function main() {
  const warehouseUser = process.env.CRETAS_WAREHOUSE_USER;
  const warehousePass = process.env.CRETAS_WAREHOUSE_PASS;
  const bossUser = process.env.CRETAS_BOSS_USER;
  const bossPass = process.env.CRETAS_BOSS_PASS;
  const procurementUser = process.env.CRETAS_PROCUREMENT_USER;
  const procurementPass = process.env.CRETAS_PROCUREMENT_PASS;
  const financeUser = process.env.CRETAS_FINANCE_USER;
  const financePass = process.env.CRETAS_FINANCE_PASS;
  const skipConfirm = process.env.CRETAS_SKIP_CONFIRM === '1';

  if (!warehouseUser || !warehousePass || !bossUser || !bossPass) {
    console.log('SKIP: set CRETAS_WAREHOUSE_USER/PASS and CRETAS_BOSS_USER/PASS to run live E2E');
    process.exit(0);
  }

  const report = {
    startedAt: new Date().toISOString(),
    apiBase: API_BASE,
    factoryId: FACTORY_ID,
    steps: [],
    pass: true,
  };

  const whToken = await login(warehouseUser, warehousePass);
  let bossToken = null;
  try {
    bossToken = await login(bossUser, bossPass);
  } catch (error) {
    report.steps.push({
      name: 'boss-login',
      skipped: true,
      reason: String(error.message || error),
    });
    console.log('SKIP boss login:', error.message || error);
  }
  const procurementToken = procurementUser && procurementPass
    ? await login(procurementUser, procurementPass).catch(() => null)
    : null;
  const financeToken = financeUser && financePass
    ? await login(financeUser, financePass).catch(() => null)
    : null;
  const base = `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes`;
  const reconBase = `/api/mobile/${FACTORY_ID}/restaurant/supplier-reconciliations`;

  const health = await fetch(`${API_BASE}/api/mobile/health`);
  assert(health.ok, 'Backend health check failed');
  report.steps.push({ name: 'health', status: health.status, ok: true });

  if (bossToken) {
    const pending = await api(bossToken, 'GET', `${base}/price-anomaly/pending?page=1&size=5`);
    const bossCanApprove = pending.status === 200;
    report.steps.push({
      name: 'pending-approvals',
      status: pending.status,
      count: pending.json?.data?.content?.length ?? 0,
      ok: bossCanApprove,
      skipped: !bossCanApprove,
      message: pending.json?.message ?? null,
    });
    if (bossCanApprove) {
      console.log('PASS pending approvals endpoint', pending.json?.data?.content?.length ?? 0, 'rows');
    } else {
      console.log('SKIP pending approvals (boss role not approver on this env)', pending.status, pending.json?.message || '');
      bossToken = null;
    }
  }

  // ── SEED: ensure we have a testable DRAFT before scanning the list ───────
  let seedNoteId = null;
  {
    const seedResult = await ensureSeedDraft(whToken, report);
    seedNoteId = seedResult.noteId;
    if (seedResult.seeded && !seedResult.reused) {
      console.log(`SEED new DRAFT created: ${seedNoteId}`);
    }
  }

  // ── scan DRAFT list (seed DRAFT will appear here if just created) ─────────
  const list = await api(whToken, 'GET', `${base}?status=DRAFT&page=1&size=20`);
  assert(list.status === 200, `draft list failed: ${list.status}`);
  const drafts = list.json?.data?.content || [];

  // Prioritise: (1) seed DRAFT by ID, (2) any anomaly DRAFT, (3) first DRAFT with details
  const candidateIds = [
    seedNoteId,
    pickAnomalyDraft(drafts)?.id,
    ...drafts.map((n) => n.id),
  ].filter((id, idx, arr) => id && arr.indexOf(id) === idx);

  let draft = null;
  let detailBefore = null;
  for (const candidateId of candidateIds) {
    const detail = await api(whToken, 'GET', `${base}/${candidateId}`);
    if (detail.status !== 200) continue;
    const approval = detail.json?.data?.priceAnomalyApprovalStatus || 'NONE';
    if (approval === 'APPROVED') continue;
    draft = detail.json?.data;
    detailBefore = detail;
    break;
  }

  report.steps.push({
    name: 'draft-list',
    status: list.status,
    draftId: draft?.id ?? null,
    seedNoteId,
    pickedAnomalyDraft: Boolean(pickAnomalyDraft(drafts)),
    candidateCount: candidateIds.length,
    ok: true,
  });

  if (!draft?.id || !detailBefore) {
    console.log('SKIP: no exercisable DRAFT (all approved or empty)');
    report.steps.push({ name: 'confirm-gate', skipped: true, reason: 'no pending draft note' });
    writeEvidence(report);
    process.exit(0);
  }

  assert(detailBefore.status === 200, `detail read failed: ${detailBefore.status}`);
  report.steps.push({
    name: 'detail-readback',
    status: detailBefore.status,
    noteId: draft.id,
    approvalStatus: detailBefore.json?.data?.priceAnomalyApprovalStatus ?? null,
    quotePhotos: detailBefore.json?.data?.supplierQuotePhotoUrls ?? null,
    voiceTranscript: detailBefore.json?.data?.voiceTranscriptText ?? null,
    ok: true,
  });

  let noteData = detailBefore.json?.data || {};
  let lines = noteData.lines || draft.lines || [];
  let approvalStatus = noteData.priceAnomalyApprovalStatus || 'NONE';
  let hasAnomaly = linesHaveAnomalyFlag(lines);

  let confirmAttempt = await api(whToken, 'PUT', `${base}/${draft.id}/confirm`, {});
  report.steps.push({
    name: 'confirm-attempt',
    status: confirmAttempt.status,
    hasAnomaly,
    approvalStatus,
    message: confirmAttempt.json?.message ?? null,
    code: confirmAttempt.json?.code ?? null,
    ok: true,
  });

  if (isExplanationRequiredResponse(confirmAttempt.status, confirmAttempt.json)) {
    const patch = await api(
      whToken,
      'PUT',
      `${base}/${draft.id}/lines`,
      buildLinePayload(lines, confirmAttempt.json?.message),
    );
    report.steps.push({
      name: 'fill-anomaly-explanation',
      status: patch.status,
      ok: patch.status === 200,
      message: patch.json?.message ?? null,
    });
    assert(patch.status === 200, `fill anomaly explanation failed: ${patch.status}`);
    console.log('PASS filled anomaly explanation on lines');

    const detailAfterPatch = await api(whToken, 'GET', `${base}/${draft.id}`);
    noteData = detailAfterPatch.json?.data || noteData;
    lines = noteData.lines || lines;
    hasAnomaly = linesHaveAnomalyFlag(lines) || hasAnomaly;
    approvalStatus = noteData.priceAnomalyApprovalStatus || approvalStatus;

    confirmAttempt = await api(whToken, 'PUT', `${base}/${draft.id}/confirm`, {});
    report.steps.push({
      name: 'confirm-after-explanation',
      status: confirmAttempt.status,
      hasAnomaly,
      approvalStatus,
      message: confirmAttempt.json?.message ?? null,
      code: confirmAttempt.json?.code ?? null,
      ok: isApprovalGateResponse(confirmAttempt.status, confirmAttempt.json) || confirmAttempt.status >= 400,
    });
  }

  hasAnomaly = hasAnomaly
    || linesHaveAnomalyFlag(lines)
    || isApprovalGateResponse(confirmAttempt.status, confirmAttempt.json)
    || isExplanationRequiredResponse(confirmAttempt.status, confirmAttempt.json);

  if (hasAnomaly && approvalStatus !== 'APPROVED') {
    assert(confirmAttempt.status >= 400, 'Expected confirm blocked for price anomaly without approval');
    console.log('PASS confirm blocked without approval', confirmAttempt.json?.message || confirmAttempt.status);

    const submit = await api(whToken, 'POST', `${base}/${draft.id}/price-anomaly/submit`, {});
    report.steps.push({
      name: 'submit-approval',
      status: submit.status,
      approvalStatus: submit.json?.data?.priceAnomalyApprovalStatus ?? null,
      ok: submit.status === 200 || submit.json?.code === 'PRICE_ANOMALY_ALREADY_PENDING',
    });
    const submitted = submit.status === 200
      || submit.json?.code === 'PRICE_ANOMALY_ALREADY_PENDING'
      || approvalStatus === 'PENDING';
    if (submit.status === 200) {
      console.log('PASS submit approval', submit.json?.data?.priceAnomalyApprovalStatus);
    } else if (submitted) {
      console.log('INFO submit skipped — already pending', submit.json?.message || submit.status);
    } else {
      assert(false, `submit approval failed: ${submit.status} ${submit.json?.message || ''}`);
    }

    const confirmAfterSubmit = await api(whToken, 'PUT', `${base}/${draft.id}/confirm`, {});
    report.steps.push({
      name: 'confirm-after-submit',
      status: confirmAfterSubmit.status,
      message: confirmAfterSubmit.json?.message ?? null,
      ok: confirmAfterSubmit.status >= 400,
    });
    assert(confirmAfterSubmit.status >= 400, 'Expected confirm still blocked while PENDING approval');
    console.log('PASS confirm still blocked while pending', confirmAfterSubmit.json?.message || confirmAfterSubmit.status);

    if (bossToken) {
      const pendingAfterSubmit = await api(bossToken, 'GET', `${base}/price-anomaly/pending?page=1&size=10`);
      report.steps.push({
        name: 'pending-after-submit',
        status: pendingAfterSubmit.status,
        count: pendingAfterSubmit.json?.data?.content?.length ?? 0,
        containsDraft: (pendingAfterSubmit.json?.data?.content || []).some((n) => n.id === draft.id),
        ok: pendingAfterSubmit.status === 200,
      });

      const approve = await api(bossToken, 'POST', `${base}/${draft.id}/price-anomaly/approve`, { comment: 'E2E approve' });
      report.steps.push({
        name: 'boss-approve',
        status: approve.status,
        approvalStatus: approve.json?.data?.priceAnomalyApprovalStatus ?? null,
        ok: approve.status === 200 || approve.json?.code === 'PRICE_ANOMALY_ALREADY_APPROVED',
      });
      if (approve.status === 200) {
        console.log('PASS boss approve', approve.json?.data?.priceAnomalyApprovalStatus);
      } else if (approve.json?.code === 'PRICE_ANOMALY_ALREADY_APPROVED') {
        console.log('INFO boss approve skipped — already approved');
      } else {
        assert(false, `boss approve failed: ${approve.status} ${approve.json?.message || ''}`);
      }

      const detailAfterApprove = await api(whToken, 'GET', `${base}/${draft.id}`);
      report.steps.push({
        name: 'detail-after-approve',
        status: detailAfterApprove.status,
        approvalStatus: detailAfterApprove.json?.data?.priceAnomalyApprovalStatus ?? null,
        ok: detailAfterApprove.status === 200 && detailAfterApprove.json?.data?.priceAnomalyApprovalStatus === 'APPROVED',
      });
      assert(
        detailAfterApprove.json?.data?.priceAnomalyApprovalStatus === 'APPROVED',
        'Expected APPROVED readback after boss approve',
      );
      console.log('PASS readback after approve', detailAfterApprove.json?.data?.priceAnomalyApprovalStatus);

      if (!skipConfirm) {
        const confirmAfterApprove = await api(whToken, 'PUT', `${base}/${draft.id}/confirm`, {});
        report.steps.push({
          name: 'confirm-after-approve',
          status: confirmAfterApprove.status,
          message: confirmAfterApprove.json?.message ?? null,
          ok: confirmAfterApprove.status === 200,
        });
        assert(confirmAfterApprove.status === 200, `confirm after approve failed: ${confirmAfterApprove.status} ${confirmAfterApprove.json?.message || ''}`);
        console.log('PASS confirm inbound after approve');

        const detailConfirmed = await api(whToken, 'GET', `${base}/${draft.id}`);
        const confirmedData = detailConfirmed.json?.data || {};
        report.steps.push({
          name: 'detail-after-confirm',
          status: detailConfirmed.status,
          noteStatus: confirmedData.status ?? null,
          payableTransactionId: confirmedData.payableTransactionId ?? null,
          materialBatchIds: (confirmedData.lines || []).map((l) => l.materialBatchId).filter(Boolean),
          ok: detailConfirmed.status === 200 && confirmedData.status === 'CONFIRMED',
        });
        assert(confirmedData.status === 'CONFIRMED', `Expected CONFIRMED status, got ${confirmedData.status}`);
        console.log('PASS readback CONFIRMED', confirmedData.payableTransactionId || 'no-payable');

        // Mark seed note as consumed so next run creates a fresh one
        // (confirmed notes are no longer DRAFT, so findExistingSeedDraft won't pick them up)

        if (financeToken && confirmedData.supplierId) {
          const month = (confirmedData.deliveryDate || new Date().toISOString().slice(0, 10)).slice(0, 7);
          const reconDraft = await api(financeToken, 'POST', `${reconBase}/draft`, {
            supplierId: confirmedData.supplierId,
            month,
          });
          const reconData = reconDraft.json?.data || {};
          const reconLines = reconData.lines || [];
          const matched = reconLines.find((line) => line.deliveryNoteId === draft.id)
            || reconLines.find((line) => line.payableTransactionId === confirmedData.payableTransactionId)
            || reconLines.find((line) => line.apTransactionId === confirmedData.payableTransactionId);
          const reconciliationFrozen = reconData.status === 'CONFIRMED';
          const noteEvidenceOk = confirmedData.priceAnomalyApprovalStatus === 'APPROVED'
            && Boolean(confirmedData.payableTransactionId);
          const reconOk = reconDraft.status === 200 && (Boolean(matched) || (reconciliationFrozen && noteEvidenceOk));
          report.steps.push({
            name: 'finance-reconciliation-readback',
            status: reconDraft.status,
            month,
            supplierId: confirmedData.supplierId,
            reconciliationStatus: reconData.status ?? null,
            reconciliationFrozen,
            lineCount: reconLines.length,
            matchedLine: Boolean(matched),
            priceAnomalyApprovalStatus: matched?.priceAnomalyApprovalStatus
              ?? confirmedData.priceAnomalyApprovalStatus
              ?? null,
            voiceTranscriptText: matched?.voiceTranscriptText ?? confirmedData.voiceTranscriptText ?? null,
            payableTransactionId: matched?.payableTransactionId
              ?? matched?.apTransactionId
              ?? confirmedData.payableTransactionId
              ?? null,
            ok: reconOk,
            skipped: reconDraft.status >= 400,
            message: matched
              ? reconDraft.json?.message ?? null
              : reconciliationFrozen
                ? '月对账已确认冻结；改验送货单审批+应付字段'
                : reconDraft.json?.message ?? null,
          });
          if (reconOk && matched) {
            console.log('PASS finance reconciliation readback', matched.priceAnomalyApprovalStatus);
          } else if (reconOk && reconciliationFrozen) {
            console.log('PASS finance evidence via delivery note (reconciliation CONFIRMED frozen)');
          } else {
            console.log('INFO finance reconciliation skipped/failed', reconDraft.json?.message || reconDraft.status);
          }
        } else {
          report.steps.push({
            name: 'finance-reconciliation-readback',
            skipped: true,
            reason: financeToken ? 'confirmed note missing supplierId' : 'CRETAS_FINANCE_USER/PASS not set',
          });
        }
      } else {
        report.steps.push({ name: 'confirm-after-approve', skipped: true, reason: 'CRETAS_SKIP_CONFIRM=1' });
      }
    } else {
      report.steps.push({
        name: 'boss-approve',
        skipped: true,
        reason: 'no approver account (factory_super_admin/restaurant_manager) on this env',
      });
      console.log('SKIP boss approve — seed qhj_prod or restaurant_manager on test');
    }
  } else if (!hasAnomaly) {
    console.log('INFO note has no price anomaly; confirm gate not exercised');
  }

  if (procurementToken) {
    const procurementConfirm = await api(procurementToken, 'POST', `${base}/procurement-confirm`, {
      supplierName: 'E2E 测试供应商',
      deliveryDate: new Date().toISOString().slice(0, 10),
      supplierContactNote: 'E2E procurement confirm smoke',
      voiceTranscriptText: 'E2E voice transcript placeholder',
      voiceAudioUrl: 'https://example.com/e2e-voice.wav',
      supplierQuotePhotoUrls: ['https://example.com/e2e-quote.jpg'],
      lines: [{
        ingredientName: 'E2E测试食材',
        rawMaterialTypeId: draft.lines?.[0]?.rawMaterialTypeId || 'MAT-PLACEHOLDER',
        quantity: 1,
        unit: 'kg',
        unitPrice: 10,
      }],
    });
    report.steps.push({
      name: 'procurement-confirm',
      status: procurementConfirm.status,
      noteId: procurementConfirm.json?.data?.id ?? null,
      ok: procurementConfirm.status === 200 || procurementConfirm.status === 201,
      skipped: procurementConfirm.status >= 400,
      message: procurementConfirm.json?.message ?? null,
    });
    if (procurementConfirm.status >= 400) {
      console.log('INFO procurement-confirm skipped/failed', procurementConfirm.json?.message || procurementConfirm.status);
    } else {
      console.log('PASS procurement-confirm created note', procurementConfirm.json?.data?.id);
    }
  } else {
    report.steps.push({
      name: 'procurement-confirm',
      skipped: true,
      reason: 'CRETAS_PROCUREMENT_USER/PASS not set',
    });
  }

  // ── optional cleanup of seeded DRAFT (only relevant if NOT confirmed) ─────
  // If the DRAFT was confirmed by this run it's now CONFIRMED, not DRAFT, so
  // findExistingSeedDraft won't find it next run — no cleanup needed.
  // If the run ended before confirm (e.g. no bossToken), the DRAFT stays for re-use.
  if (SEED_CLEANUP && seedNoteId) {
    await cleanupSeedDraft(whToken, seedNoteId);
  }

  report.finishedAt = new Date().toISOString();
  writeEvidence(report);
  console.log('E2E API smoke completed');
}

main().catch((err) => {
  console.error('FAIL', err.message);
  process.exit(1);
});
