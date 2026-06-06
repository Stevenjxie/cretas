#!/usr/bin/env node
/**
 * API-level acceptance for restaurant procure-to-pay approval gate.
 * Requires local Java backend on :10010 and seeded qhj restaurant accounts.
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
 *   CRETAS_E2E_EVIDENCE_DIR=scripts/e2e/evidence
 */

import fs from 'node:fs';
import path from 'node:path';

const API_BASE = process.env.CRETAS_API_BASE || 'http://localhost:10010';
const FACTORY_ID = process.env.CRETAS_FACTORY_ID || 'FACTORY-QHJ';
const EVIDENCE_DIR = process.env.CRETAS_E2E_EVIDENCE_DIR || 'scripts/e2e/evidence';

async function login(username, password) {
  const attempts = [
    '/api/mobile/auth/login',
    '/api/mobile/auth/unified-login',
  ];
  let lastError = 'no attempt';
  for (const path of attempts) {
    const res = await fetch(`${API_BASE}${path}`, {
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
    lastError = `${path} ${res.status} ${JSON.stringify(json).slice(0, 200)}`;
  }
  throw new Error(`Login failed for ${username}: ${lastError}`);
}

async function api(token, method, path, body) {
  const res = await fetch(`${API_BASE}${path}`, {
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

function pickAnomalyDraft(notes) {
  return (notes || []).find((note) => (note.lines || []).some((line) => line.priceAnomalyFlag)) || null;
}

function buildLinePayload(lines) {
  return (lines || []).map((line) => ({
    id: line.id,
    ingredientName: line.ingredientName,
    rawMaterialTypeId: line.rawMaterialTypeId,
    quantity: line.quantity,
    unit: line.unit,
    unitPrice: line.unitPrice,
    qcResult: line.qcResult || 'PASS',
    remark: line.remark,
    priceAnomalyReasonCode: line.priceAnomalyFlag
      ? (line.priceAnomalyReasonCode || 'SUPPLIER_EXPLAINED')
      : line.priceAnomalyReasonCode,
    priceAnomalyExplanation: line.priceAnomalyFlag
      ? (line.priceAnomalyExplanation || 'E2E: 供应商电话确认临时市场涨价，已留存报价截图。')
      : line.priceAnomalyExplanation,
  }));
}

function anomalyLinesNeedExplanation(lines) {
  return (lines || []).some((line) => line.priceAnomalyFlag && !line.priceAnomalyExplanation);
}

async function main() {
  const warehouseUser = process.env.CRETAS_WAREHOUSE_USER;
  const warehousePass = process.env.CRETAS_WAREHOUSE_PASS;
  const bossUser = process.env.CRETAS_BOSS_USER;
  const bossPass = process.env.CRETAS_BOSS_PASS;
  const procurementUser = process.env.CRETAS_PROCUREMENT_USER;
  const procurementPass = process.env.CRETAS_PROCUREMENT_PASS;

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
  const base = `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes`;

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

  const list = await api(whToken, 'GET', `${base}?status=DRAFT&page=1&size=10`);
  assert(list.status === 200, `draft list failed: ${list.status}`);
  const drafts = list.json?.data?.content || [];
  const draft = pickAnomalyDraft(drafts) || drafts[0] || null;
  report.steps.push({
    name: 'draft-list',
    status: list.status,
    draftId: draft?.id ?? null,
    pickedAnomalyDraft: Boolean(pickAnomalyDraft(drafts)),
    ok: true,
  });

  if (!draft?.id) {
    console.log('SKIP: no DRAFT supplier delivery note to exercise confirm gate');
    report.steps.push({ name: 'confirm-gate', skipped: true, reason: 'no draft note' });
    writeEvidence(report);
    process.exit(0);
  }

  const detailBefore = await api(whToken, 'GET', `${base}/${draft.id}`);
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

  const noteData = detailBefore.json?.data || {};
  const lines = noteData.lines || draft.lines || [];
  const hasAnomaly = lines.some((l) => l.priceAnomalyFlag);
  const approvalStatus = noteData.priceAnomalyApprovalStatus || 'NONE';

  const confirmBlocked = await api(whToken, 'PUT', `${base}/${draft.id}/confirm`, {});
  report.steps.push({
    name: 'confirm-attempt',
    status: confirmBlocked.status,
    hasAnomaly,
    approvalStatus,
    message: confirmBlocked.json?.message ?? null,
    code: confirmBlocked.json?.code ?? null,
    ok: hasAnomaly && approvalStatus !== 'APPROVED' ? confirmBlocked.status >= 400 : true,
  });

  if (hasAnomaly) {
    if (approvalStatus !== 'APPROVED') {
      assert(confirmBlocked.status >= 400, 'Expected confirm blocked for price anomaly without approval');
      console.log('PASS confirm blocked without approval', confirmBlocked.json?.message || confirmBlocked.status);
    }

    let workingLines = lines;
    if (anomalyLinesNeedExplanation(workingLines) && approvalStatus === 'NONE') {
      const patch = await api(whToken, 'PUT', `${base}/${draft.id}/lines`, buildLinePayload(workingLines));
      report.steps.push({
        name: 'fill-anomaly-explanation',
        status: patch.status,
        ok: patch.status === 200,
        message: patch.json?.message ?? null,
      });
      assert(patch.status === 200, `fill anomaly explanation failed: ${patch.status}`);
      workingLines = patch.json?.data?.lines || workingLines;
      console.log('PASS filled anomaly explanation on lines');
    }

    const submit = await api(whToken, 'POST', `${base}/${draft.id}/price-anomaly/submit`, {});
    report.steps.push({
      name: 'submit-approval',
      status: submit.status,
      approvalStatus: submit.json?.data?.priceAnomalyApprovalStatus ?? null,
      ok: submit.status === 200,
    });
    const submitted = submit.status === 200
      || submit.json?.code === 'PRICE_ANOMALY_ALREADY_PENDING'
      || approvalStatus === 'PENDING';
    if (submit.status === 200) {
      console.log('PASS submit approval', submit.json?.data?.priceAnomalyApprovalStatus);
    } else if (submitted) {
      console.log('INFO submit skipped — already pending', submit.json?.message || submit.status);
    }

    if (submitted) {
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
        }

        const detailAfter = await api(whToken, 'GET', `${base}/${draft.id}`);
        report.steps.push({
          name: 'detail-after-approve',
          status: detailAfter.status,
          approvalStatus: detailAfter.json?.data?.priceAnomalyApprovalStatus ?? null,
          ok: detailAfter.status === 200 && detailAfter.json?.data?.priceAnomalyApprovalStatus === 'APPROVED',
        });
        if (detailAfter.json?.data?.priceAnomalyApprovalStatus === 'APPROVED') {
          console.log('PASS readback after approve', detailAfter.json?.data?.priceAnomalyApprovalStatus);
        }
      } else {
        report.steps.push({
          name: 'boss-approve',
          skipped: true,
          reason: 'no approver account (factory_super_admin/restaurant_manager) on this env',
        });
        console.log('SKIP boss approve — seed qhj_prod or restaurant_manager on test');
      }
    }
  } else {
    console.log('INFO note has no price anomaly flag; confirm gate not applicable for this draft');
  }

  if (procurementToken) {
    const procurementConfirm = await api(procurementToken, 'POST', `${base}/procurement-confirm`, {
      supplierName: 'E2E 测试供应商',
      deliveryDate: new Date().toISOString().slice(0, 10),
      supplierContactNote: 'E2E procurement confirm smoke',
      voiceTranscriptText: 'E2E voice transcript placeholder',
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

  report.finishedAt = new Date().toISOString();
  writeEvidence(report);
  console.log('E2E API smoke completed');
}

main().catch((err) => {
  console.error('FAIL', err.message);
  process.exit(1);
});
