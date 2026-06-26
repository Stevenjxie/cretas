import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'fs/promises';
import path from 'path';
import process from 'process';

const OUT_DIR = path.resolve(process.env.E2E_OUT || '.playwright-mcp/codex-20260626-web-admin-yield-detail');
const PROFILE_DIR = path.join(OUT_DIR, `pw-profile-${Date.now().toString(36)}`);
const WEB_URL = process.env.E2E_WEB_URL || 'http://127.0.0.1:5173';
const USERNAME = process.env.E2E_USERNAME;
const PASSWORD = process.env.E2E_PASSWORD;
const BATCH_ID = process.env.E2E_BATCH_ID || '9370';
const PLAYWRIGHT_PORT = process.env.PLAYWRIGHT_PORT || '9241';
const CHAT_ID = process.env.PLAYWRIGHT_CHAT_ID || 'codex-f006-web-admin-yield-detail';

const records = [];
const screenshots = [];
const consoleMessages = [];
const pageErrors = [];
const apiResponses = [];
let activeContext = null;
let activePage = null;

function record(type, status, detail = {}) {
  const entry = { type, status, ts: new Date().toISOString(), ...detail };
  records.push(entry);
  const suffix = detail.message ? `: ${detail.message}` : '';
  console.log(`[${status}] ${type}${suffix}`);
}

async function screenshot(page, name) {
  const file = path.join(OUT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  screenshots.push(file);
}

function captureInterestingResponse(response) {
  const url = response.url();
  if (url.includes('/auth/') || url.includes('/production/batches/')) {
    apiResponses.push({ status: response.status(), url });
  }
}

async function writeResult(status, extra = {}) {
  const result = {
    status,
    webUrl: WEB_URL,
    batchId: BATCH_ID,
    playwright: {
      headed: true,
      viewport: '1920x1080',
      port: PLAYWRIGHT_PORT,
      chatId: CHAT_ID,
      profileDir: PROFILE_DIR,
    },
    records,
    screenshots,
    apiResponses,
    consoleMessages: consoleMessages.slice(-80),
    pageErrors,
    ...extra,
  };
  await writeFile(path.join(OUT_DIR, 'web-admin-batch-detail-yield-card-result.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
}

async function run() {
  if (!USERNAME || !PASSWORD) {
    throw new Error('E2E_USERNAME and E2E_PASSWORD are required');
  }

  await mkdir(OUT_DIR, { recursive: true });
  activeContext = await chromium.launchPersistentContext(PROFILE_DIR, {
    headless: false,
    viewport: { width: 1920, height: 1080 },
    locale: 'zh-CN',
    args: [`--remote-debugging-port=${PLAYWRIGHT_PORT}`, '--lang=zh-CN'],
  });

  activePage = activeContext.pages()[0] || await activeContext.newPage();
  activePage.on('console', (msg) => consoleMessages.push({ type: msg.type(), text: msg.text() }));
  activePage.on('pageerror', (error) => pageErrors.push(error.message));
  activePage.on('response', captureInterestingResponse);
  activePage.on('dialog', async (dialog) => {
    record('dialog', 'INFO', { message: dialog.message() });
    await dialog.accept();
  });

  const targetPath = `/production/batches/${BATCH_ID}`;
  await activePage.goto(`${WEB_URL}/login?redirect=${encodeURIComponent(targetPath)}`, { waitUntil: 'domcontentloaded' });
  await activePage.locator('.login-form input').first().waitFor({ state: 'visible', timeout: 30000 });
  await screenshot(activePage, '01-login');

  const inputs = activePage.locator('.login-form input');
  await inputs.nth(0).fill(USERNAME);
  await inputs.nth(1).fill(PASSWORD);
  await activePage.locator('.login-button').click();
  record('ui-login-submit', 'PASS');

  await activePage.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
  await screenshot(activePage, '02-after-login');
  record('post-login-state', 'INFO', {
    message: activePage.url(),
    title: await activePage.title().catch(() => ''),
  });

  await activePage.locator('[data-testid="web-batch-detail-yield-card"]').waitFor({ state: 'visible', timeout: 60000 });
  const rateText = (await activePage.locator('[data-testid="web-batch-detail-yield-rate"]').textContent())?.trim() || '';
  const formulaText = (await activePage.locator('[data-testid="web-batch-detail-yield-formula"]').textContent())?.trim() || '';
  const hintText = (await activePage.locator('[data-testid="web-batch-detail-yield-open-hint"]').textContent())?.trim() || '';
  await screenshot(activePage, '03-batch-detail-yield-card');

  if (!/%$/.test(rateText) || rateText === '—') {
    throw new Error(`Yield rate was not displayed as a percentage: "${rateText}"`);
  }
  if (!formulaText.includes('末道产出') || !formulaText.includes('首道投入')) {
    throw new Error(`Formula is missing expected terms: "${formulaText}"`);
  }
  if (!hintText.includes('未关单') || !hintText.includes('当前出成率')) {
    throw new Error(`Rolling hint is missing expected wording: "${hintText}"`);
  }

  record('web-yield-card', 'PASS', { batchId: BATCH_ID, rateText, formulaText, hintText });
  await activeContext.close();
  activeContext = null;

  await writeResult('PASS');
}

run().catch(async (error) => {
  record('run', 'FAIL', { message: error.message, stack: error.stack });
  if (activePage) {
    await screenshot(activePage, '99-failure-state').catch(() => {});
  }
  if (activeContext) {
    await activeContext.close().catch(() => {});
    activeContext = null;
  }
  await mkdir(OUT_DIR, { recursive: true });
  await writeResult('FAIL', { error: error.message, stack: error.stack });
  console.error(error);
  process.exitCode = 1;
});
