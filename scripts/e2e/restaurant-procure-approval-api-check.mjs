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
 */

const API_BASE = process.env.CRETAS_API_BASE || 'http://localhost:10010';
const FACTORY_ID = process.env.CRETAS_FACTORY_ID || 'FACTORY-QHJ';

async function login(username, password) {
  const res = await fetch(`${API_BASE}/api/mobile/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const json = await res.json();
  if (!json.success && !json.data?.token) {
    throw new Error(`Login failed for ${username}: ${JSON.stringify(json)}`);
  }
  return json.data?.token || json.token;
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

async function main() {
  const warehouseUser = process.env.CRETAS_WAREHOUSE_USER;
  const warehousePass = process.env.CRETAS_WAREHOUSE_PASS;
  const bossUser = process.env.CRETAS_BOSS_USER;
  const bossPass = process.env.CRETAS_BOSS_PASS;

  if (!warehouseUser || !warehousePass || !bossUser || !bossPass) {
    console.log('SKIP: set CRETAS_WAREHOUSE_USER/PASS and CRETAS_BOSS_USER/PASS to run live E2E');
    process.exit(0);
  }

  const whToken = await login(warehouseUser, warehousePass);
  const bossToken = await login(bossUser, bossPass);
  const base = `/api/mobile/${FACTORY_ID}/restaurant/supplier-delivery-notes`;

  const health = await fetch(`${API_BASE}/api/mobile/health`);
  assert(health.ok, 'Backend health check failed');

  const pending = await api(bossToken, 'GET', `${base}/price-anomaly/pending?page=0&size=5`);
  assert(pending.status === 200, `pending list failed: ${pending.status}`);
  console.log('PASS pending approvals endpoint', pending.json?.data?.content?.length ?? 0, 'rows');

  const list = await api(whToken, 'GET', `${base}?status=DRAFT&page=0&size=5`);
  assert(list.status === 200, `draft list failed: ${list.status}`);
  const draft = list.json?.data?.content?.[0];
  if (!draft?.id) {
    console.log('SKIP: no DRAFT supplier delivery note to exercise confirm gate');
    process.exit(0);
  }

  const confirmBlocked = await api(whToken, 'PUT', `${base}/${draft.id}/confirm`, {});
  if ((draft.lines || []).some((l) => l.priceAnomalyFlag)) {
    assert(confirmBlocked.status >= 400, 'Expected confirm blocked for price anomaly without approval');
    console.log('PASS confirm blocked without approval', confirmBlocked.json?.message || confirmBlocked.status);
  } else {
    console.log('INFO note has no price anomaly flag; confirm gate not applicable for this draft');
  }

  console.log('E2E API smoke completed');
}

main().catch((err) => {
  console.error('FAIL', err.message);
  process.exit(1);
});
