#!/usr/bin/env node
/**
 * RN Expo Web smoke: chef recent-fill (prod) + procurement photo UI (test).
 *
 * Usage:
 *   node scripts/e2e/restaurant-procure-rn-expo-smoke.mjs
 *
 * Requires: frontend deps installed; Playwright from scripts/e2e.
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '../..');
const RN_DIR = path.join(ROOT, 'frontend/CretasFoodTrace');
const EVIDENCE_DIR = process.env.CRETAS_E2E_EVIDENCE_DIR || 'scripts/e2e/evidence/prod';
const APP_PORT = Number(process.env.CRETAS_EXPO_PORT || 3012);
const APP_BASE = `http://127.0.0.1:${APP_PORT}`;

let evidenceStamp = new Date().toISOString().replace(/[:.]/g, '-');
let shotDir = '';

const report = {
  startedAt: new Date().toISOString(),
  steps: [],
  pass: false,
};

function step(name, ok, extra = {}) {
  report.steps.push({ name, ok, ...extra });
  const tag = ok ? 'PASS' : ok === false ? 'FAIL' : 'SKIP';
  console.log(`[${tag}] ${name}`, extra.message || extra.error || '');
}

function ensureShotDir() {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  shotDir = path.join(EVIDENCE_DIR, `restaurant-procure-rn-expo-${evidenceStamp}`);
  fs.mkdirSync(shotDir, { recursive: true });
  return shotDir;
}

function writeEvidence() {
  const jsonPath = path.join(EVIDENCE_DIR, `restaurant-procure-rn-expo-${evidenceStamp}.json`);
  report.finishedAt = new Date().toISOString();
  report.pass = report.steps.every((s) => s.ok !== false);
  fs.writeFileSync(jsonPath, JSON.stringify(report, null, 2), 'utf8');
  console.log('EVIDENCE_JSON', jsonPath);
  if (shotDir) console.log('EVIDENCE_SHOTS', shotDir);
  return jsonPath;
}

async function apiLogin(apiBase, username, password) {
  const res = await fetch(`${apiBase}/api/mobile/auth/unified-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const json = await res.json();
  if (!json.success) throw new Error(`API login failed: ${JSON.stringify(json).slice(0, 200)}`);
  return json.data;
}

async function seedChefRequisition(apiBase) {
  try {
    const auth = await apiLogin(apiBase, 'qhj_prod', '123456');
    const { accessToken: token, factoryId, userId } = auth;
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
    const matsRes = await fetch(`${apiBase}/api/mobile/${factoryId}/raw-material-types/active`, { headers });
    const matsJson = await matsRes.json();
    const material = (matsJson.data || matsJson || [])[0];
    if (!material?.id) return false;
    const createRes = await fetch(`${apiBase}/api/mobile/${factoryId}/purchase-requisitions`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        requesterDeptId: 'HOT_DISH',
        expectedDate: new Date().toISOString().slice(0, 10),
        reason: 'E2E smoke seed',
        requestedItems: [{ materialTypeId: material.id, materialName: material.name, quantity: 2, unit: material.unit || 'kg' }],
        remark: `seed:${userId}`,
      }),
    });
    const created = await createRes.json();
    return createRes.ok && created.success;
  } catch {
    return false;
  }
}

async function waitForHttp(url, timeoutMs = 180_000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
      if (res.ok || res.status === 200) return true;
    } catch {
      // retry
    }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error(`Timeout waiting for ${url}`);
}

let expoProc = null;

function startExpo(envFile, port = APP_PORT, clearCache = false) {
  if (expoProc) {
    expoProc.kill('SIGTERM');
    expoProc = null;
  }
  const isWin = process.platform === 'win32';
  const cmd = isWin ? 'npx.cmd' : 'npx';
  const args = ['expo', 'start', '--web', '--port', String(port)];
  if (clearCache) args.push('--clear');
  expoProc = spawn(cmd, args, {
    cwd: RN_DIR,
    stdio: 'pipe',
    shell: isWin,
    env: {
      ...process.env,
      CI: '1',
      EXPO_NO_TELEMETRY: '1',
      ENVFILE: envFile,
    },
  });
  expoProc.stdout?.on('data', (d) => process.stdout.write(`[expo] ${d}`));
  expoProc.stderr?.on('data', (d) => process.stderr.write(`[expo] ${d}`));
  return `http://127.0.0.1:${port}`;
}

async function loginExpo(page, baseUrl, username, password) {
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(2000);

  const landing = page.getByTestId('landing-login-btn');
  if (await landing.isVisible().catch(() => false)) {
    await landing.click();
    await page.waitForTimeout(800);
  }

  await page.getByTestId('login-username-input').fill(username);
  await page.getByTestId('login-password-input').fill(password);

  const [loginResp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/mobile/auth/unified-login'), { timeout: 30_000 }),
    page.getByTestId('login-submit-btn').click(),
  ]);
  const body = await loginResp.json().catch(() => ({}));
  if (!body.success) {
    throw new Error(`登录失败 ${username}: ${JSON.stringify(body).slice(0, 200)}`);
  }
  await page.waitForTimeout(4000);
}

async function scrollHome(page) {
  for (let i = 0; i < 6; i += 1) {
    await page.mouse.wheel(0, 500);
    await page.waitForTimeout(400);
  }
}

async function openChefRequisitionList(page) {
  await page.getByText('管理', { exact: true }).click({ timeout: 10_000 });
  await page.waitForTimeout(1500);
  const mgmtBtn = page.getByTestId('fa-chef-requisition-btn');
  if (await mgmtBtn.isVisible({ timeout: 8_000 }).catch(() => false)) {
    await mgmtBtn.click();
    return true;
  }
  await scrollHome(page);
  const quick = page.getByText(/档口报货|Stall requisition|报货追踪/i).first();
  if (await quick.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await quick.click();
    return true;
  }
  return false;
}

async function chefRecentFillSmoke(page) {
  const opened = await openChefRequisitionList(page);
  if (!opened) {
    step('chef-nav', false, { message: '未找到报货追踪入口' });
    return;
  }
  step('chef-nav', true, { message: '已进入报货追踪' });
  await page.waitForTimeout(1500);
  await page.getByTestId('chef-requisition-create-btn').click({ timeout: 12_000 });
  await page.waitForTimeout(2500);

  const hasCreateHeader = await page.getByText('档口报货').first().isVisible({ timeout: 10_000 }).catch(() => false);
  step('chef-create-screen', hasCreateHeader, { message: hasCreateHeader ? '档口报货页已打开' : '未进入档口报货页' });
  await page.screenshot({ path: path.join(shotDir, 'chef-01-create.png'), fullPage: true });

  const recentTitle = await page.getByText('最近报货').first().isVisible({ timeout: 8_000 }).catch(() => false);
  step('chef-recent-section', recentTitle ? true : null, {
    message: recentTitle ? '最近报货区块可见' : '无历史报货数据（按设计隐藏，需先有报货记录）',
    skipped: !recentTitle,
  });

  if (recentTitle) {
    const recentCard = page.locator('[class*="Card"]').filter({ hasText: /PR-|REQ-|报货/ }).first();
    const cardVisible = await recentCard.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!cardVisible) {
      const anyCard = page.locator('div').filter({ hasText: /行$/ }).first();
      if (await anyCard.isVisible().catch(() => false)) {
        page.once('dialog', (d) => d.accept());
        await anyCard.click();
        await page.waitForTimeout(1500);
        step('chef-recent-apply', true, { message: '已点击最近报货卡片' });
      } else {
        step('chef-recent-apply', null, { message: '有区块但无卡片可点', skipped: true });
      }
    } else {
      page.once('dialog', (d) => d.accept());
      await recentCard.click();
      await page.waitForTimeout(1500);
      step('chef-recent-apply', true, { message: '已套用最近报货' });
    }
    await page.screenshot({ path: path.join(shotDir, 'chef-02-after-apply.png'), fullPage: true });
  }
}

async function warehouseRestaurantInboundSmoke(page) {
  const inboundTab = page.getByText('入库', { exact: true }).first();
  if (!(await inboundTab.isVisible({ timeout: 12_000 }).catch(() => false))) {
    step('warehouse-inbound-tab', false, { message: '未找到入库 Tab' });
    return;
  }
  await inboundTab.click();
  await page.waitForTimeout(2000);
  step('warehouse-inbound-tab', true, { message: '已进入入库模块' });

  const restaurantBtn = page.getByText('餐饮待验收', { exact: true }).first();
  const hasRestaurantEntry = await restaurantBtn.isVisible({ timeout: 12_000 }).catch(() => false);
  step('warehouse-restaurant-entry', hasRestaurantEntry, {
    message: hasRestaurantEntry ? '餐饮待验收入口可见' : '未找到餐饮待验收入口',
  });
  await page.screenshot({ path: path.join(shotDir, 'wh-01-inbound-list.png'), fullPage: true });
  if (!hasRestaurantEntry) return;

  await restaurantBtn.click();
  await page.waitForTimeout(2500);
  const hasDeliveryList = await page.getByText('待验收入库').first().isVisible({ timeout: 12_000 }).catch(() => false);
  step('warehouse-supplier-delivery-list', hasDeliveryList, {
    message: hasDeliveryList ? '待验收入库列表已打开' : '未进入送货单列表',
  });
  await page.screenshot({ path: path.join(shotDir, 'wh-02-delivery-list.png'), fullPage: true });
}

async function procurementPhotoSmoke(page) {
  await page.getByText('采购', { exact: true }).first().click({ timeout: 15_000 });
  await page.waitForTimeout(2500);
  await page.getByTestId('pm-procurement-confirm-btn').click({ timeout: 12_000 });
  await page.waitForTimeout(2000);

  const hasProcHeader = await page.getByText('采购确认送货').first().isVisible({ timeout: 12_000 }).catch(() => false);
  step('procurement-confirm-screen', hasProcHeader, {
    message: hasProcHeader ? '采购确认送货页已打开' : '未进入采购确认页',
  });
  await page.screenshot({ path: path.join(shotDir, 'proc-01-screen.png'), fullPage: true });
  if (!hasProcHeader) return;

  const hasPhotoSection = await page.getByText('供应商报价照片').first().isVisible().catch(() => false);
  const hasAlbumBtn = await page.getByText('相册').first().isVisible().catch(() => false);
  step('procurement-photo-ui', hasPhotoSection && hasAlbumBtn, {
    message: `照片区=${hasPhotoSection} 相册按钮=${hasAlbumBtn}`,
  });

  const fixturePath = path.join(__dirname, 'fixtures', 'quote-smoke.jpg');
  if (hasAlbumBtn && fs.existsSync(fixturePath)) {
    const fileChooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 });
    await page.getByText('相册').first().click();
    const fileChooser = await fileChooserPromise;
    await fileChooser.setFiles(fixturePath);
    await page.waitForTimeout(5000);
    const uploaded = await page.locator('img').count();
    step('procurement-photo-upload', uploaded > 0, {
      message: `缩略图数量=${uploaded}`,
    });
    await page.screenshot({ path: path.join(shotDir, 'proc-02-after-upload.png'), fullPage: true });
  } else {
    step('procurement-photo-upload', null, { message: '跳过实际上传（无 fixture 或按钮不可见）', skipped: true });
  }

  const voiceBtn = page.getByText(/录音|停止录音/).first();
  const hasVoice = await voiceBtn.isVisible().catch(() => false);
  step('procurement-voice-ui', hasVoice, { message: hasVoice ? '语音录入按钮可见' : '语音按钮未找到（Web 可能降级）' });
}

(async () => {
  ensureShotDir();

  const fixtureDir = path.join(__dirname, 'fixtures');
  fs.mkdirSync(fixtureDir, { recursive: true });
  const fixtureJpg = path.join(fixtureDir, 'quote-smoke.jpg');
  if (!fs.existsSync(fixtureJpg)) {
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
      'base64',
    );
    fs.writeFileSync(path.join(fixtureDir, 'quote-smoke.png'), png);
    fs.copyFileSync(path.join(fixtureDir, 'quote-smoke.png'), fixtureJpg);
  }

  try {
    const prodPort = APP_PORT;
    const testPort = APP_PORT + 1;
    console.log(`>> Starting Expo Web (prod API) on :${prodPort}...`);
    const prodBase = startExpo('.env.production', prodPort, true);
    await waitForHttp(prodBase);
    step('expo-prod-up', true, { message: prodBase });

    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });

    const seeded = await seedChefRequisition('http://139.196.165.140:8086');
    step('chef-seed-requisition', seeded ? true : null, {
      message: seeded ? '已预置一条报货草稿供最近套用' : '预置报货跳过（不影响页面 smoke）',
      skipped: !seeded,
    });

    await loginExpo(page, prodBase, 'qhj_prod', '123456');
    step('login-prod-qhj_prod', true);
    await page.screenshot({ path: path.join(shotDir, '00-home-prod.png'), fullPage: true });

    await chefRecentFillSmoke(page);

    await browser.close();

    const browserWh = await chromium.launch({ headless: true });
    const pageWh = await browserWh.newPage({ viewport: { width: 390, height: 844 } });
    await loginExpo(pageWh, prodBase, 'qhj_warehouse_mgr', '123456');
    step('login-prod-qhj_warehouse_mgr', true);
    await warehouseRestaurantInboundSmoke(pageWh);
    await browserWh.close();
    if (expoProc) {
      expoProc.kill('SIGTERM');
      expoProc = null;
      await new Promise((r) => setTimeout(r, 3000));
    }

    console.log(`>> Starting Expo Web (test API) on :${testPort}...`);
    const testBase = startExpo('.env.test', testPort, true);
    await waitForHttp(testBase, 300_000);
    step('expo-test-up', true, { message: testBase });

    const browser2 = await chromium.launch({ headless: true });
    const page2 = await browser2.newPage({ viewport: { width: 390, height: 844 } });

    await loginExpo(page2, testBase, 'e2e_purchase_mgr', '123456');
    step('login-test-e2e_purchase_mgr', true);
    await procurementPhotoSmoke(page2);
    await browser2.close();

    writeEvidence();
    if (!report.pass) process.exit(1);
    console.log('SMOKE_PASS');
  } catch (err) {
    step('fatal', false, { error: String(err?.message || err) });
    writeEvidence();
    process.exit(1);
  } finally {
    if (expoProc) expoProc.kill('SIGTERM');
  }
})();
