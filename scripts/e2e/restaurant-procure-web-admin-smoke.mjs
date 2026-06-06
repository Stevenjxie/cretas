#!/usr/bin/env node
/**
 * Web-admin smoke: price anomaly approval list + supplier delivery detail.
 *
 * Usage:
 *   node scripts/e2e/restaurant-procure-web-admin-smoke.mjs
 *
 * Env:
 *   CRETAS_WEB_BASE=http://139.196.165.140:8086
 *   CRETAS_WEB_USER=qhj_prod
 *   CRETAS_WEB_PASS=123456
 *   CRETAS_DELIVERY_NOTE_ID=4eb1df20-c6f7-4329-af69-ead24eafb725
 *   CRETAS_E2E_EVIDENCE_DIR=scripts/e2e/evidence/prod
 */

import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const WEB_BASE = (process.env.CRETAS_WEB_BASE || 'http://139.196.165.140:8086').replace(/\/$/, '');
const USER = process.env.CRETAS_WEB_USER || 'qhj_prod';
const PASS = process.env.CRETAS_WEB_PASS || '123456';
const NOTE_ID = process.env.CRETAS_DELIVERY_NOTE_ID || '4eb1df20-c6f7-4329-af69-ead24eafb725';
const EVIDENCE_DIR = process.env.CRETAS_E2E_EVIDENCE_DIR || 'scripts/e2e/evidence/prod';

const report = {
  startedAt: new Date().toISOString(),
  webBase: WEB_BASE,
  user: USER,
  noteId: NOTE_ID,
  steps: [],
  pass: false,
};

function step(name, ok, extra = {}) {
  report.steps.push({ name, ok, ...extra });
  const tag = ok ? 'PASS' : 'FAIL';
  console.log(`[${tag}] ${name}`, extra.message || extra.error || '');
}

let evidenceStamp = new Date().toISOString().replace(/[:.]/g, '-');

function ensureShotDir() {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const shotDir = path.join(EVIDENCE_DIR, `restaurant-procure-web-admin-${evidenceStamp}`);
  fs.mkdirSync(shotDir, { recursive: true });
  return shotDir;
}

function writeEvidence(shotDir) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const jsonPath = path.join(EVIDENCE_DIR, `restaurant-procure-web-admin-${evidenceStamp}.json`);
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(jsonPath, JSON.stringify(report, null, 2), 'utf8');
  console.log('EVIDENCE_JSON', jsonPath);
  if (shotDir) console.log('EVIDENCE_SHOTS', shotDir);
  return jsonPath;
}

async function login(page) {
  await page.goto(`${WEB_BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('input[placeholder="请输入用户名"]', { timeout: 15_000 });
  await page.fill('input[placeholder="请输入用户名"]', USER);
  await page.fill('input[placeholder="请输入密码"]', PASS);

  const [loginResp] = await Promise.all([
    page.waitForResponse(
      (resp) => resp.url().includes('/api/mobile/auth/unified-login'),
      { timeout: 20_000 },
    ),
    page.click('button:has-text("登 录"), button:has-text("登录")'),
  ]);

  const body = await loginResp.json().catch(() => ({}));
  if (!body.success) {
    throw new Error(`登录失败: ${JSON.stringify(body).slice(0, 300)}`);
  }
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 });
}

(async () => {
  let shotDir = '';
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });

  try {
    await login(page);
    step('login', true, { message: `redirected to ${page.url()}` });

    shotDir = ensureShotDir();

    await page.screenshot({ path: path.join(shotDir, '01-dashboard.png') });

    await page.goto(`${WEB_BASE}/restaurant/supplier-delivery/price-anomaly/pending`, {
      waitUntil: 'networkidle',
      timeout: 30_000,
    });
    await page.waitForTimeout(1500);

    const pendingTitle = await page.locator('.page-title, h1, .el-card__header').first().textContent().catch(() => '');
    const hasPendingTitle = (pendingTitle || '').includes('价格异常待审批');
    const tableRows = await page.locator('.el-table__body tr').count();
    step('pending-list-page', hasPendingTitle, {
      message: `title="${(pendingTitle || '').trim()}" rows=${tableRows}`,
    });
    await page.screenshot({ path: path.join(shotDir, '02-pending-list.png'), fullPage: true });

    await page.goto(`${WEB_BASE}/restaurant/supplier-delivery/${NOTE_ID}`, {
      waitUntil: 'networkidle',
      timeout: 30_000,
    });
    await page.waitForTimeout(2000);

    const detailText = await page.locator('.page-wrapper, .page-card').first().innerText().catch(() => '');
    const hasDetail = detailText.includes('送货单详情') || detailText.includes('手动录入送货单');
    const hasApprovalBlock =
      detailText.includes('价格异常审批') ||
      detailText.includes('进价异常处理') ||
      detailText.includes('审批意见');
    step('delivery-detail-page', hasDetail, {
      message: hasApprovalBlock ? 'approval UI visible' : 'detail loaded (approval block optional)',
      hasApprovalBlock,
    });
    await page.screenshot({ path: path.join(shotDir, '03-delivery-detail.png'), fullPage: true });

    report.pass = report.steps.every((s) => s.ok);
    writeEvidence(shotDir);
    if (!report.pass) process.exit(1);
    console.log('SMOKE_PASS');
  } catch (err) {
    step('fatal', false, { error: String(err?.message || err) });
    if (!shotDir) shotDir = ensureShotDir();
    await page.screenshot({ path: path.join(shotDir, '99-error.png'), fullPage: true }).catch(() => {});
    report.pass = false;
    writeEvidence(shotDir);
    process.exit(1);
  } finally {
    await browser.close();
  }
})();
