import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'fs/promises';
import path from 'path';
import process from 'process';

const RUN_DATE = new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10).replaceAll('-', '');
const OUT_DIR = path.resolve(process.env.E2E_OUT || `.playwright-mcp/codex-${RUN_DATE}-batch-detail-yield-card`);
const PROFILE_DIR = path.join(OUT_DIR, `pw-profile-${Date.now().toString(36)}`);
const APP_URL = process.env.E2E_APP_URL || 'http://127.0.0.1:3017';
const API_BASE_URL = process.env.E2E_API_BASE || process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const USERNAME = process.env.E2E_USERNAME;
const PASSWORD = process.env.E2E_PASSWORD;
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const PLAYWRIGHT_PORT = process.env.PLAYWRIGHT_PORT || '9237';
const CHAT_ID = process.env.PLAYWRIGHT_CHAT_ID || 'codex-f006-yield-detail';

const records = [];
const screenshots = [];
const consoleMessages = [];
const pageErrors = [];

function record(type, status, detail = {}) {
  const entry = { type, status, ts: new Date().toISOString(), ...detail };
  records.push(entry);
  const suffix = detail.message ? `: ${detail.message}` : '';
  console.log(`[${status}] ${type}${suffix}`);
  return entry;
}

function byTestId(id) {
  return `[data-testid="${id}"]`;
}

async function fetchJson(pathOrUrl, options = {}) {
  const url = pathOrUrl.startsWith('http') ? pathOrUrl : `${API_BASE_URL}${pathOrUrl}`;
  const response = await fetch(url, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  if (!response.ok) {
    throw new Error(`${options.method || 'GET'} ${url} -> ${response.status}: ${text.slice(0, 300)}`);
  }
  return body;
}

async function apiLogin() {
  if (!USERNAME || !PASSWORD) {
    throw new Error('E2E_USERNAME and E2E_PASSWORD are required');
  }
  const login = await fetchJson('/api/mobile/auth/unified-login', {
    method: 'POST',
    body: JSON.stringify({ username: USERNAME, password: PASSWORD }),
  });
  const token = login?.data?.accessToken || login?.data?.token;
  if (!token) {
    throw new Error('Login response did not include access token');
  }
  return token;
}

async function pickTargetBatch(token) {
  const provided = process.env.E2E_BATCH_ID;
  const headers = { Authorization: `Bearer ${token}` };
  if (provided) {
    const yieldResponse = await fetchJson(`/api/mobile/${FACTORY_ID}/production/batches/${provided}/yield`, { headers });
    return {
      id: String(provided),
      yieldRate: yieldResponse?.data?.asOfYieldRate ?? yieldResponse?.data?.cumulativeYieldRate ?? null,
      inProgress: yieldResponse?.data?.inProgress === true,
      source: 'provided',
    };
  }

  const list = await fetchJson(`/api/mobile/${FACTORY_ID}/processing/batches?page=1&size=50`, { headers });
  const today = new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10);
  const candidates = (list?.data?.content || [])
    .filter((batch) => String(batch.createdAt || '').startsWith(today))
    .filter((batch) => String(batch.status || '').toUpperCase() === 'IN_PROGRESS');

  for (const batch of candidates) {
    const yieldResponse = await fetchJson(`/api/mobile/${FACTORY_ID}/production/batches/${batch.id}/yield`, { headers });
    const yieldRate = yieldResponse?.data?.asOfYieldRate ?? yieldResponse?.data?.cumulativeYieldRate ?? null;
    if (yieldRate != null && yieldResponse?.data?.inProgress === true) {
      return {
        id: String(batch.id),
        batchNumber: batch.batchNumber,
        productType: batch.productType,
        yieldRate,
        inProgress: true,
        source: 'today-in-progress',
      };
    }
  }

  throw new Error('No today IN_PROGRESS batch with rolling yield found in prod');
}

async function screenshot(page, name) {
  const file = path.join(OUT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  screenshots.push(file);
  return file;
}

async function waitAndClick(page, selector, label, timeout = 30000) {
  const locator = page.locator(selector).first();
  await locator.waitFor({ state: 'visible', timeout });
  await locator.scrollIntoViewIfNeeded();
  await locator.click();
  record('click', 'PASS', { label, selector });
}

async function run() {
  await mkdir(OUT_DIR, { recursive: true });

  const token = await apiLogin();
  record('api-login', 'PASS', { factoryId: FACTORY_ID });

  const targetBatch = await pickTargetBatch(token);
  record('target-batch', 'PASS', targetBatch);

  const context = await chromium.launchPersistentContext(PROFILE_DIR, {
    headless: false,
    viewport: { width: 1920, height: 1080 },
    locale: 'zh-CN',
    args: [`--remote-debugging-port=${PLAYWRIGHT_PORT}`, '--lang=zh-CN'],
  });

  const page = context.pages()[0] || await context.newPage();
  page.on('console', (msg) => consoleMessages.push({ type: msg.type(), text: msg.text() }));
  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.on('dialog', async (dialog) => {
    record('dialog', 'INFO', { message: dialog.message() });
    await dialog.accept();
  });

  await page.goto(APP_URL, { waitUntil: 'domcontentloaded' });
  await screenshot(page, '01-loaded');

  const loginButton = page.locator(byTestId('landing-login-btn')).first();
  if (await loginButton.isVisible({ timeout: 15000 }).catch(() => false)) {
    await loginButton.click();
  }
  await page.locator(byTestId('login-username-input')).fill(USERNAME);
  await page.locator(byTestId('login-password-input')).fill(PASSWORD);
  await page.locator(byTestId('login-submit-btn')).click();
  record('ui-login-submit', 'PASS');

  await page.locator(byTestId('fa-home-root')).waitFor({ state: 'visible', timeout: 60000 });
  await screenshot(page, '02-fa-home');
  record('fa-home', 'PASS');

  await waitAndClick(page, byTestId('fa-home-stat-todayBatches'), 'today batches stat');
  await page.locator(byTestId('today-batches-screen')).waitFor({ state: 'visible', timeout: 30000 });
  await screenshot(page, '03-today-batches');

  const targetSelector = byTestId(`today-batches-card-${targetBatch.id}`);
  await waitAndClick(page, targetSelector, `batch ${targetBatch.id}`);

  await page.locator(byTestId('batch-detail-yield-card')).waitFor({ state: 'visible', timeout: 30000 });
  const errorVisible = await page.locator(byTestId('batch-detail-yield-error')).isVisible().catch(() => false);
  if (errorVisible) {
    throw new Error('Yield card rendered error state');
  }

  const rateText = (await page.locator(byTestId('batch-detail-yield-rate')).textContent())?.trim() || '';
  const formulaText = (await page.locator(byTestId('batch-detail-yield-formula')).textContent())?.trim() || '';
  const rollingHintText = (await page.locator(byTestId('batch-detail-yield-open-hint')).textContent())?.trim() || '';
  await screenshot(page, '04-batch-detail-yield-card');

  if (!/%$/.test(rateText) || rateText === '—') {
    throw new Error(`Yield rate was not displayed as a percentage: "${rateText}"`);
  }
  if (!formulaText.includes('末道产出') || !formulaText.includes('首道投入')) {
    throw new Error(`Yield formula text is missing expected terms: "${formulaText}"`);
  }
  if (!rollingHintText.includes('未关单') || !rollingHintText.includes('当前出成率')) {
    throw new Error(`Rolling order hint is missing expected wording: "${rollingHintText}"`);
  }

  record('yield-card', 'PASS', {
    batchId: targetBatch.id,
    expectedYieldRate: targetBatch.yieldRate,
    rateText,
    formulaText,
    rollingHintText,
  });

  await context.close();

  const result = {
    status: 'PASS',
    appUrl: APP_URL,
    apiBaseUrl: API_BASE_URL,
    factoryId: FACTORY_ID,
    playwright: {
      headed: true,
      viewport: '1920x1080',
      port: PLAYWRIGHT_PORT,
      chatId: CHAT_ID,
      profileDir: PROFILE_DIR,
    },
    targetBatch,
    records,
    screenshots,
    consoleMessages: consoleMessages.slice(-50),
    pageErrors,
  };
  await writeFile(path.join(OUT_DIR, 'batch-detail-yield-card-result.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
}

run().catch(async (error) => {
  record('run', 'FAIL', { message: error.message, stack: error.stack });
  const result = {
    status: 'FAIL',
    appUrl: APP_URL,
    apiBaseUrl: API_BASE_URL,
    factoryId: FACTORY_ID,
    playwright: {
      headed: true,
      viewport: '1920x1080',
      port: PLAYWRIGHT_PORT,
      chatId: CHAT_ID,
      profileDir: PROFILE_DIR,
    },
    records,
    screenshots,
    consoleMessages: consoleMessages.slice(-50),
    pageErrors,
    error: error.message,
    stack: error.stack,
  };
  await mkdir(OUT_DIR, { recursive: true });
  await writeFile(path.join(OUT_DIR, 'batch-detail-yield-card-result.json'), JSON.stringify(result, null, 2));
  console.error(error);
  process.exitCode = 1;
});
