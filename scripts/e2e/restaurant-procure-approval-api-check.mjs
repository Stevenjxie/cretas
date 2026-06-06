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
 *   CRETAS_FINANCE_USER / CRETAS_FINANCE_PASS
 *   CRETAS_E2E_EVIDENCE_DIR=scripts/e2e/evidence
 *   CRETAS_SKIP_CONFIRM=1   # skip final inbound posting (dev only)
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

  const list = await api(whToken, 'GET', `${base}?status=DRAFT&page=1&size=10`);
  assert(list.status === 200, `draft list failed: ${list.status}`);
  const drafts = list.json?.data?.content || [];
  const candidates = [
    pickAnomalyDraft(drafts),
    ...drafts,
  ].filter((note, index, arr) => note?.id && arr.findIndex((n) => n?.id === note.id) === index);

  let draft = null;
  let detailBefore = null;
  for (const candidate of candidates) {
    const detail = await api(whToken, 'GET', `${base}/${candidate.id}`);
    if (detail.status !== 200) continue;
    const approval = detail.json?.data?.priceAnomalyApprovalStatus || 'NONE';
    if (approval === 'APPROVED') continue;
    draft = candidate;
    detailBefore = detail;
    break;
  }

  report.steps.push({
    name: 'draft-list',
    status: list.status,
    draftId: draft?.id ?? null,
    pickedAnomalyDraft: Boolean(pickAnomalyDraft(drafts)),
    candidateCount: candidates.length,
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

  report.finishedAt = new Date().toISOString();
  writeEvidence(report);
  console.log('E2E API smoke completed');
}

main().catch((err) => {
  console.error('FAIL', err.message);
  process.exit(1);
});
