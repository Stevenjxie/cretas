import { chromium } from '@playwright/test';
import { existsSync, readFileSync } from 'fs';
import { mkdir, writeFile } from 'fs/promises';
import path from 'path';
import process from 'process';

function applyEnvLine(line) {
  const trimmed = line.trim();
  if (!trimmed || trimmed.startsWith('#')) return;
  const index = trimmed.indexOf('=');
  if (index <= 0) return;
  const key = trimmed.slice(0, index).trim();
  let value = trimmed.slice(index + 1).trim();
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
    value = value.slice(1, -1);
  }
  if (key && process.env[key] == null) process.env[key] = value;
}

function loadLocalEnv() {
  for (const file of ['.env.e2e.local', '.env.test.local']) {
    const envPath = path.resolve(file);
    if (!existsSync(envPath)) continue;
    readFileSync(envPath, 'utf8').split(/\r?\n/).forEach(applyEnvLine);
  }
  if (!process.env.E2E_USERNAME && process.env.TEST_FACTORY_ADMIN_USER) {
    process.env.E2E_USERNAME = process.env.TEST_FACTORY_ADMIN_USER;
  }
  if (!process.env.E2E_PASSWORD && process.env.TEST_FACTORY_ADMIN_PASS) {
    process.env.E2E_PASSWORD = process.env.TEST_FACTORY_ADMIN_PASS;
  }
  if (!process.env.E2E_FACTORY_ID && process.env.TEST_FACTORY_ADMIN_FACTORY_ID) {
    process.env.E2E_FACTORY_ID = process.env.TEST_FACTORY_ADMIN_FACTORY_ID;
  }
}

loadLocalEnv();

const RUN_DATE = new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10).replaceAll('-', '');
const OUT_DIR = path.resolve(process.env.E2E_OUT || `.playwright-mcp/codex-${RUN_DATE}-headed-production-cost-flow`);
const PROFILE_ROOT = path.join(OUT_DIR, `pw-profiles-${Date.now().toString(36)}`);
const APP_URL = process.env.E2E_APP_URL || process.env.E2E_ADMIN_URL || 'http://127.0.0.1:3021';
const API_BASE_URL = process.env.E2E_API_BASE || process.env.API_BASE_URL || process.env.E2E_ADMIN_URL || 'http://127.0.0.1:10010';
const HEADLESS = false;
const STRICT = process.env.E2E_STRICT === '1';
const ALLOW_MUTATION = process.env.E2E_ALLOW_MUTATION === '1';
const SCENARIO_TARGET = Math.max(100, Number.parseInt(process.env.E2E_SCENARIO_COUNT || '100', 10));
const HEADED_LIMIT = Math.min(
  SCENARIO_TARGET,
  Number.parseInt(process.env.E2E_HEADED_SCENARIO_COUNT || String(SCENARIO_TARGET), 10),
);
const FETCH_TIMEOUT_MS = Number.parseInt(process.env.E2E_FETCH_TIMEOUT_MS || '12000', 10);
const PLAYWRIGHT_ARGS = [
  '--lang=zh-CN',
  ...(process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : []),
];

const records = [];
const screenshots = [];
const consoleMessages = [];
const pageErrors = [];

function nowIso() {
  return new Date().toISOString();
}

function record(type, status, detail = {}) {
  const entry = { type, status, ts: nowIso(), ...detail };
  records.push(entry);
  const suffix = detail.message ? `: ${detail.message}` : '';
  console.log(`[${status}] ${type}${suffix}`);
  return entry;
}

function scenarioProfileDir(scenario, index) {
  return path.join(PROFILE_ROOT, `${String(index + 1).padStart(3, '0')}-${scenario.id}`);
}

const headedScenarioDimensions = {
  skuCount: [1, 2, 3, 5],
  rawBatchCount: [1, 2, 3, 5],
  orderCount: [1, 2, 4, 8],
  processCount: [2, 3, 5, 8],
  routeShape: ['shared-prefix', 'same-step-mixed-order', 'diverge-after-boil', 'rejoin-final', 'rolling-open'],
  flowMode: ['normal-close', 'settle-without-close', 'partial-output-continue', 'other-step-mixed-order'],
};

function buildScenarios(count) {
  const scenarios = [];

  for (let i = 0; scenarios.length < count; i += 1) {
    const skuCount = headedScenarioDimensions.skuCount[i % headedScenarioDimensions.skuCount.length];
    const rawBatchCount = headedScenarioDimensions.rawBatchCount[Math.floor(i / 2) % headedScenarioDimensions.rawBatchCount.length];
    let orderCount = headedScenarioDimensions.orderCount[Math.floor(i / 3) % headedScenarioDimensions.orderCount.length];
    const processCount = headedScenarioDimensions.processCount[Math.floor(i / 5) % headedScenarioDimensions.processCount.length];
    const flowMode = headedScenarioDimensions.flowMode[Math.floor(i / 7) % headedScenarioDimensions.flowMode.length];
    if (flowMode === 'other-step-mixed-order' && orderCount < 2) {
      orderCount = 2;
    }
    const plannedOutputs = Array.from({ length: skuCount }, (_, index) => 80 + i + index * 17);
    const firstInput = plannedOutputs.reduce((sum, value) => sum + value, 0) * (1.08 + (rawBatchCount - 1) * 0.015);
    const finalOutput = plannedOutputs.reduce((sum, value) => sum + value, 0) * (0.91 + (i % 5) * 0.01);
    scenarios.push({
      id: `HPF-${String(scenarios.length + 1).padStart(3, '0')}`,
      skuCount,
      rawBatchCount,
      orderCount,
      processCount,
      routeShape: headedScenarioDimensions.routeShape[Math.floor(i / 11) % headedScenarioDimensions.routeShape.length],
      flowMode,
      rolling: flowMode !== 'normal-close',
      expected: {
        plannedOutputs,
        firstInput: Number(firstInput.toFixed(2)),
        finalOutput: Number(finalOutput.toFixed(2)),
        rollingYieldRate: Number((finalOutput / firstInput).toFixed(4)),
      },
    });
  }

  return scenarios;
}

function countBy(scenarios, key) {
  return scenarios.reduce((acc, scenario) => {
    const value = String(scenario[key]);
    acc[value] = (acc[value] || 0) + 1;
    return acc;
  }, {});
}

function summarizeCoverage(scenarios) {
  return {
    dimensions: Object.fromEntries(Object.keys(headedScenarioDimensions).map((key) => [key, countBy(scenarios, key)])),
    complexCases: {
      multiSku: scenarios.filter((scenario) => scenario.skuCount > 1).length,
      maxSku: scenarios.filter((scenario) => scenario.skuCount >= 5).length,
      multiOrder: scenarios.filter((scenario) => scenario.orderCount > 1).length,
      maxOrder: scenarios.filter((scenario) => scenario.orderCount >= 8).length,
      multiRawBatch: scenarios.filter((scenario) => scenario.rawBatchCount > 1).length,
      maxRawBatch: scenarios.filter((scenario) => scenario.rawBatchCount >= 5).length,
      longRoute: scenarios.filter((scenario) => scenario.processCount >= 8).length,
      rolling: scenarios.filter((scenario) => scenario.rolling).length,
      otherStepMixedOrder: scenarios.filter((scenario) => scenario.flowMode === 'other-step-mixed-order').length,
      partialOutputContinue: scenarios.filter((scenario) => scenario.flowMode === 'partial-output-continue').length,
      mixedStress: scenarios.filter((scenario) =>
        scenario.skuCount >= 3
        && scenario.orderCount >= 2
        && scenario.rawBatchCount >= 2
        && scenario.processCount >= 5).length,
    },
  };
}

function validateCoverage(coverage) {
  const issues = [];
  for (const [key, expectedValues] of Object.entries(headedScenarioDimensions)) {
    for (const expected of expectedValues) {
      if (!coverage.dimensions[key]?.[String(expected)]) {
        issues.push(`missing ${key}=${expected}`);
      }
    }
  }
  const requiredComplexCases = {
    multiSku: 50,
    maxSku: 10,
    multiOrder: 50,
    maxOrder: 10,
    multiRawBatch: 50,
    maxRawBatch: 10,
    longRoute: 10,
    rolling: 50,
    otherStepMixedOrder: 10,
    partialOutputContinue: 10,
    mixedStress: 10,
  };
  for (const [key, minimum] of Object.entries(requiredComplexCases)) {
    if ((coverage.complexCases[key] || 0) < minimum) {
      issues.push(`${key} coverage ${coverage.complexCases[key] || 0} < ${minimum}`);
    }
  }
  return { status: issues.length === 0 ? 'PASS' : 'FAIL', issues };
}

function validateScenario(scenario) {
  const issues = [];
  if (scenario.skuCount < 1) issues.push('skuCount must be positive');
  if (scenario.rawBatchCount < 1) issues.push('rawBatchCount must be positive');
  if (scenario.processCount < 2) issues.push('processCount must be at least 2');
  if (!(scenario.expected.rollingYieldRate > 0 && scenario.expected.rollingYieldRate < 2)) {
    issues.push('yield rate is outside plausible bounds');
  }
  if (scenario.flowMode === 'other-step-mixed-order' && scenario.orderCount < 2) {
    issues.push('other-step mixed-order scenario requires multiple orders');
  }
  return { status: issues.length === 0 ? 'PASS' : 'FAIL', issues };
}

async function fetchJson(pathOrUrl) {
  const url = pathOrUrl.startsWith('http') ? pathOrUrl : `${API_BASE_URL}${pathOrUrl}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const response = await fetch(url, { signal: controller.signal, headers: { Accept: 'application/json' } });
    const text = await response.text();
    return { ok: response.ok, status: response.status, body: text.slice(0, 500), url };
  } catch (error) {
    return { ok: false, error: error.message, url };
  } finally {
    clearTimeout(timer);
  }
}

async function apiPreflight() {
  const checks = [];
  for (const apiPath of ['/api/mobile/health', '/actuator/health']) {
    const result = await fetchJson(apiPath);
    checks.push(result);
    if (result.ok) return { reachable: true, checks };
  }
  return { reachable: false, checks };
}

async function safeVisible(locator, timeout = 1000) {
  try {
    await locator.first().waitFor({ state: 'visible', timeout });
    return true;
  } catch {
    return false;
  }
}

async function clickFirstVisible(page, candidates, timeout = 2500) {
  for (const candidate of candidates) {
    const locator = typeof candidate === 'string' ? page.getByTestId(candidate) : candidate;
    if (await safeVisible(locator, timeout)) {
      await locator.first().click();
      return typeof candidate === 'string' ? candidate : 'locator';
    }
  }
  return null;
}

async function waitForAnyTestId(page, ids, timeout = 12000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    for (const id of ids) {
      if (await safeVisible(page.getByTestId(id), 350)) return id;
    }
  }
  return null;
}

async function screenshot(page, name, fullPage = true) {
  const file = path.join(OUT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage });
  screenshots.push(file);
  return file;
}

async function auditPage(page, label) {
  const audit = await page.evaluate(() => {
    const viewportWidth = window.innerWidth;
    const bodyWidth = Math.max(document.body.scrollWidth, document.documentElement.scrollWidth);
    const bodyHeight = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
    const viewportHeight = window.innerHeight;
    const isVisible = (element) => {
      const rect = element.getBoundingClientRect();
      const style = window.getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
    };
    const scrollContainers = Array.from(document.querySelectorAll('*')).filter((element) => {
      if (!isVisible(element)) return false;
      const style = window.getComputedStyle(element);
      const y = /(auto|scroll)/.test(style.overflowY) && element.scrollHeight > element.clientHeight + 2;
      const x = /(auto|scroll)/.test(style.overflowX) && element.scrollWidth > element.clientWidth + 2;
      return x || y;
    }).map((element) => {
      const rect = element.getBoundingClientRect();
      return {
        tag: element.tagName,
        testId: element.getAttribute('data-testid') || '',
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      };
    }).slice(0, 20);
    const smallControls = Array.from(document.querySelectorAll('button,[role="button"],a,input,textarea,select')).flatMap((element) => {
      if (!isVisible(element)) return [];
      const rect = element.getBoundingClientRect();
      if (rect.width < 44 || rect.height < 44) {
        const text = (element.innerText || element.getAttribute('aria-label') || element.getAttribute('placeholder') || '').trim();
        return [{ text: text.slice(0, 80), width: Math.round(rect.width), height: Math.round(rect.height) }];
      }
      return [];
    }).slice(0, 20);
    const clippedText = Array.from(document.querySelectorAll('button,[role="button"],span,div,p')).flatMap((element) => {
      if (!isVisible(element)) return [];
      const text = (element.innerText || '').trim();
      if (!text || text.length < 2) return [];
      if (element.scrollWidth > element.clientWidth + 2 || element.scrollHeight > element.clientHeight + 2) {
        const rect = element.getBoundingClientRect();
        return [{ text: text.slice(0, 80), width: Math.round(rect.width), height: Math.round(rect.height) }];
      }
      return [];
    }).slice(0, 20);

    return {
      url: window.location.href,
      title: document.title,
      horizontalOverflow: bodyWidth > viewportWidth + 2,
      bodyScrolls: bodyHeight > viewportHeight + 2,
      scrollContainerCount: scrollContainers.length,
      doubleScrolling: bodyHeight > viewportHeight + 2 && scrollContainers.length > 0,
      scrollContainers,
      smallControls,
      clippedText,
    };
  });
  const file = await screenshot(page, `${label.replace(/[^a-z0-9-]+/gi, '-')}`);
  return { label, audit, screenshot: file };
}

async function loginThroughUi(page) {
  await page.goto(APP_URL, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1000);

  const alreadyInApp = await waitForAnyTestId(page, [
    'fa-home-root',
    'processing-dashboard-screen',
    'main-tab-processing',
    'tab-batches',
    'operator-tab-report',
  ], 2500);
  if (alreadyInApp) return { status: 'PASS', message: `already in app at ${alreadyInApp}` };

  const loginEntry = await clickFirstVisible(page, ['landing-login-btn'], 2500);
  if (!loginEntry && !(await safeVisible(page.getByTestId('login-username-input'), 1000))) {
    return { status: 'BLOCKED', message: 'login entry and username input not visible' };
  }

  await page.getByTestId('login-username-input').fill(process.env.E2E_USERNAME || '');
  await page.getByTestId('login-password-input').fill(process.env.E2E_PASSWORD || '');
  await Promise.allSettled([
    page.waitForLoadState('networkidle', { timeout: 12000 }),
    page.getByTestId('login-submit-btn').click(),
  ]);
  const root = await waitForAnyTestId(page, [
    'fa-home-root',
    'processing-dashboard-screen',
    'main-tab-processing',
    'tab-batches',
    'operator-tab-report',
  ], 20000);
  return root
    ? { status: 'PASS', message: `logged in, root=${root}` }
    : { status: 'BLOCKED', message: 'login did not reach a known app root' };
}

async function openProductionPlan(page) {
  if (await safeVisible(page.getByTestId('production-plan-screen'), 1000)) {
    return { status: 'PASS', via: 'already-open' };
  }
  if (await safeVisible(page.getByTestId('main-tab-processing'), 1000)) {
    await page.getByTestId('main-tab-processing').click();
    if (await safeVisible(page.getByTestId('processing-create-plan-btn'), 6000)) {
      await page.getByTestId('processing-create-plan-btn').click();
      await page.getByTestId('production-plan-screen').waitFor({ state: 'visible', timeout: 12000 });
      return { status: 'PASS', via: 'main-processing' };
    }
  }
  if (await safeVisible(page.getByTestId('fa-home-root'), 1000)) {
    const clicked = await clickFirstVisible(page, ['workflow-workdesk-row-production'], 4000);
    if (clicked) {
      await page.getByTestId('production-plan-screen').waitFor({ state: 'visible', timeout: 12000 });
      return { status: 'PASS', via: 'fa-workdesk' };
    }
  }
  if (await safeVisible(page.getByTestId('fa-tab-home'), 1000)) {
    await page.getByTestId('fa-tab-home').click();
    const clicked = await clickFirstVisible(page, ['workflow-workdesk-row-production'], 4000);
    if (clicked) {
      await page.getByTestId('production-plan-screen').waitFor({ state: 'visible', timeout: 12000 });
      return { status: 'PASS', via: 'fa-home-tab' };
    }
  }
  return { status: 'BLOCKED', message: 'no visible production plan entry for current role' };
}

async function fillCreatePlanForm(page, scenario) {
  if (!ALLOW_MUTATION) {
    return { status: 'BLOCKED', message: 'set E2E_ALLOW_MUTATION=1 to create production plans through headed UI' };
  }
  if (!(await safeVisible(page.getByTestId('production-plan-create-fab'), 3000))) {
    return { status: 'BLOCKED', message: 'create FAB not visible, current role may be read-only' };
  }
  await page.getByTestId('production-plan-create-fab').click();
  await page.getByTestId('production-plan-create-modal').waitFor({ state: 'visible', timeout: 8000 });
  await page.getByText(/Future|未来|未来计划/).first().click({ timeout: 2500 }).catch(() => null);

  await page.getByTestId('product-type-selector-trigger').click();
  await page.getByTestId('product-type-selector-modal').waitFor({ state: 'visible', timeout: 8000 });
  const product = page.locator('[data-testid^="product-type-option-"]').first();
  if (!(await safeVisible(product, 10000))) return { status: 'BLOCKED', message: 'no product type option visible' };
  await product.click();

  await page.getByTestId('production-plan-qty-input').fill(String(scenario.expected.plannedOutputs[0]));

  await page.getByTestId('customer-selector-trigger').click();
  await page.getByTestId('customer-selector-modal').waitFor({ state: 'visible', timeout: 8000 });
  const customer = page.locator('[data-testid^="customer-option-"]').first();
  if (!(await safeVisible(customer, 10000))) return { status: 'BLOCKED', message: 'no customer option visible' };
  await customer.click();

  await page.getByTestId('production-plan-notes-input').fill(
    `headed-ui ${scenario.id} ${scenario.flowMode} ${Date.now()}`,
  );

  page.on('dialog', async (dialog) => {
    await dialog.accept().catch(() => null);
  });
  const responsePromise = page.waitForResponse((response) =>
    response.url().includes('/production-plans') && response.request().method() === 'POST',
  { timeout: 15000 }).catch((error) => ({ error: error.message }));
  await page.getByTestId('production-plan-save-btn').click();
  const response = await responsePromise;
  if ('error' in response) return { status: 'BLOCKED', message: `save response not observed: ${response.error}` };
  if (!response.ok()) return { status: 'BLOCKED', message: `production plan save returned HTTP ${response.status()}` };
  await page.getByTestId('production-plan-screen').waitFor({ state: 'visible', timeout: 10000 }).catch(() => null);
  return { status: 'PASS', message: `created via UI, HTTP ${response.status()}` };
}

async function openYieldReport(page) {
  if (await safeVisible(page.getByTestId('fa-tab-management'), 1000)) {
    await page.getByTestId('fa-tab-management').click();
    if (await safeVisible(page.getByTestId('fa-yield-report-btn'), 5000)) {
      await page.getByTestId('fa-yield-report-btn').click();
    }
  } else if (await safeVisible(page.getByTestId('main-tab-processing'), 1000)) {
    await page.getByTestId('main-tab-processing').click();
    if (await safeVisible(page.getByTestId('processing-yield-report-btn'), 5000)) {
      await page.getByTestId('processing-yield-report-btn').click();
    }
  }

  if (!(await safeVisible(page.getByTestId('yield-batch-select-screen'), 10000))) {
    return { status: 'BLOCKED', message: 'yield batch selection did not open' };
  }
  const batchCard = page.locator('[data-testid^="yield-batch-card-"]').first();
  if (!(await safeVisible(batchCard, 8000))) {
    return { status: 'BLOCKED', message: 'no in-progress batch card visible for yield reporting' };
  }
  await batchCard.click();

  const entered = await waitForAnyTestId(page, [
    'yield-step-report-screen',
    'yield-sku-selection-screen',
    'yield-step-report-done-screen',
    'yield-sentinel-material-screen',
    'yield-sentinel-output-screen',
  ], 15000);
  if (!entered) return { status: 'BLOCKED', message: 'batch click did not enter yield report page' };

  if (entered === 'yield-sku-selection-screen') {
    const sku = page.locator('[data-testid^="yield-sku-option-"]').first();
    if (await safeVisible(sku, 5000)) {
      await sku.click();
      const afterSku = await waitForAnyTestId(page, [
        'yield-step-report-screen',
        'yield-step-report-done-screen',
      ], 10000);
      return afterSku
        ? { status: 'PASS', message: `entered yield flow after SKU selection: ${afterSku}` }
        : { status: 'BLOCKED', message: 'SKU selected but yield operation page did not open' };
    }
  }
  return { status: 'PASS', message: `entered yield flow: ${entered}` };
}

async function openCostAnalysis(page) {
  if (await safeVisible(page.getByTestId('main-tab-processing'), 1000)) {
    await page.getByTestId('main-tab-processing').click();
  }
  if (await safeVisible(page.getByTestId('processing-cost-analysis-dialog-btn'), 4000)) {
    await page.getByTestId('processing-cost-analysis-dialog-btn').click();
    if (await safeVisible(page.getByTestId('processing-cost-by-batch-btn'), 4000)) {
      await page.getByTestId('processing-cost-by-batch-btn').click();
    }
  } else if (await safeVisible(page.getByTestId('processing-batch-list-btn'), 1000)) {
    await page.getByTestId('processing-batch-list-btn').click();
  }

  if (!(await safeVisible(page.getByTestId('batch-list-screen'), 12000))) {
    return { status: 'BLOCKED', message: 'batch list did not open for cost analysis' };
  }
  const batchCard = page.locator('[data-testid^="batch-list-card-"]').first();
  if (!(await safeVisible(batchCard, 8000))) return { status: 'BLOCKED', message: 'no batch card visible' };
  await batchCard.click();
  if (!(await safeVisible(page.getByTestId('batch-detail-cost-analysis-btn'), 12000))) {
    return { status: 'BLOCKED', message: 'batch detail cost analysis button not visible' };
  }
  await page.getByTestId('batch-detail-cost-analysis-btn').click();
  await page.getByTestId('cost-analysis-screen').waitFor({ state: 'visible', timeout: 15000 });
  const valueVisible = await safeVisible(page.getByTestId('cost-total-value'), 10000);
  return valueVisible
    ? { status: 'PASS', message: 'cost analysis opened with total cost visible' }
    : { status: 'BLOCKED', message: 'cost page opened but total cost value not visible' };
}

async function runHeadedFlow(scenario, index = 0) {
  let context;
  const evidence = [];
  const profileDir = scenarioProfileDir(scenario, index);
  try {
    await mkdir(profileDir, { recursive: true });
    context = await chromium.launchPersistentContext(profileDir, {
      headless: HEADLESS,
      viewport: { width: 1920, height: 1080 },
      acceptDownloads: false,
      locale: 'zh-CN',
      args: PLAYWRIGHT_ARGS,
    });
    const page = context.pages()[0] || await context.newPage();
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) {
        consoleMessages.push({ type: message.type(), text: message.text().slice(0, 500) });
      }
    });
    page.on('pageerror', (error) => pageErrors.push(error.message));

    await page.goto(APP_URL, { waitUntil: 'domcontentloaded', timeout: 30000 });
    evidence.push(await auditPage(page, `${scenario.id}-login-shell`));

    if (!process.env.E2E_USERNAME || !process.env.E2E_PASSWORD) {
      return {
        scenarioId: scenario.id,
        status: 'BLOCKED',
        reason: 'missing E2E_USERNAME/E2E_PASSWORD or TEST_FACTORY_ADMIN_USER/TEST_FACTORY_ADMIN_PASS',
        profileDir,
        evidence,
      };
    }

    const login = await loginThroughUi(page);
    record('headed-login', login.status, { message: login.message });
    if (login.status !== 'PASS') return { scenarioId: scenario.id, status: login.status, reason: login.message, profileDir, evidence };
    evidence.push(await auditPage(page, `${scenario.id}-after-login`));

    const planOpen = await openProductionPlan(page);
    record('headed-open-production-plan', planOpen.status, { message: planOpen.message || planOpen.via });
    if (planOpen.status !== 'PASS') {
      return { scenarioId: scenario.id, status: planOpen.status, reason: planOpen.message, profileDir, evidence };
    }
    evidence.push(await auditPage(page, `${scenario.id}-production-plan`));

    const createPlan = await fillCreatePlanForm(page, scenario);
    record('headed-create-production-plan', createPlan.status, { message: createPlan.message });
    evidence.push(await auditPage(page, `${scenario.id}-production-plan-after-create`));
    if (createPlan.status !== 'PASS') {
      return { scenarioId: scenario.id, status: createPlan.status, reason: createPlan.message, profileDir, evidence };
    }

    const yieldOpen = await openYieldReport(page);
    record('headed-open-yield-report', yieldOpen.status, { message: yieldOpen.message });
    evidence.push(await auditPage(page, `${scenario.id}-yield-flow`));
    if (yieldOpen.status !== 'PASS') {
      return { scenarioId: scenario.id, status: yieldOpen.status, reason: yieldOpen.message, profileDir, evidence };
    }

    const costOpen = await openCostAnalysis(page);
    record('headed-open-cost-analysis', costOpen.status, { message: costOpen.message });
    evidence.push(await auditPage(page, `${scenario.id}-cost-analysis`));
    if (costOpen.status !== 'PASS') {
      return { scenarioId: scenario.id, status: costOpen.status, reason: costOpen.message, profileDir, evidence };
    }

    return { scenarioId: scenario.id, status: 'PASS', reason: null, profileDir, evidence };
  } finally {
    if (context) await context.close();
  }
}

async function writeResult(summary) {
  await mkdir(OUT_DIR, { recursive: true });
  const resultPath = path.join(OUT_DIR, 'headed-production-cost-flow-result.json');
  const matrixPath = path.join(OUT_DIR, 'headed-production-cost-flow-scenarios.json');
  await writeFile(resultPath, JSON.stringify({ ...summary, records }, null, 2), 'utf8');
  await writeFile(matrixPath, JSON.stringify(summary.scenarios, null, 2), 'utf8');
  return { resultPath, matrixPath };
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  const scenarios = buildScenarios(SCENARIO_TARGET);
  const coverage = summarizeCoverage(scenarios);
  const coverageCheck = validateCoverage(coverage);
  const validations = scenarios.map((scenario) => ({ scenarioId: scenario.id, ...validateScenario(scenario) }));
  const validationFailures = validations.filter((item) => item.status !== 'PASS');
  record('scenario-matrix', validationFailures.length === 0 ? 'PASS' : 'FAIL', {
    message: `${scenarios.length - validationFailures.length}/${scenarios.length} headed UI scenario models valid`,
    failures: validationFailures.slice(0, 10),
  });
  record('scenario-coverage', coverageCheck.status, {
    message: coverageCheck.status === 'PASS'
      ? 'dimension and stress-case coverage satisfied'
      : 'dimension or stress-case coverage is insufficient',
    coverage,
    issues: coverageCheck.issues,
  });

  const api = await apiPreflight();
  record('api-preflight', api.reachable ? 'PASS' : 'BLOCKED', {
    message: api.reachable ? 'backend reachable' : 'backend API health unavailable',
    checks: api.checks,
  });

  const canRunFullHeaded = api.reachable
    && Boolean(process.env.E2E_USERNAME)
    && Boolean(process.env.E2E_PASSWORD)
    && ALLOW_MUTATION;
  const headedScenarios = canRunFullHeaded ? scenarios.slice(0, HEADED_LIMIT) : scenarios.slice(0, 1);
  const flowResults = [];

  for (const [index, scenario] of headedScenarios.entries()) {
    const flow = await runHeadedFlow(scenario, index);
    flowResults.push(flow);
    record('headed-production-to-cost-flow', flow.status, {
      message: `${scenario.id} ${flow.reason || 'production plan to yield to cost analysis UI flow passed'}`,
    });
    if (flow.status !== 'PASS' && (STRICT || !canRunFullHeaded)) break;
  }

  const passedFlow = flowResults.filter((item) => item.status === 'PASS').length;
  const failedFlow = flowResults.filter((item) => item.status === 'FAIL').length;
  const blockedFlow = flowResults.filter((item) => item.status === 'BLOCKED').length;
  const fullHeadedPassed = canRunFullHeaded && passedFlow === HEADED_LIMIT;
  const missingFullRunConfig = [
    !api.reachable ? 'backend API health unavailable' : null,
    !process.env.E2E_USERNAME ? 'E2E_USERNAME/TEST_FACTORY_ADMIN_USER missing' : null,
    !process.env.E2E_PASSWORD ? 'E2E_PASSWORD/TEST_FACTORY_ADMIN_PASS missing' : null,
    !ALLOW_MUTATION ? 'E2E_ALLOW_MUTATION=1 missing' : null,
  ].filter(Boolean);

  const blockingReason = fullHeadedPassed
    ? null
    : [
        ...missingFullRunConfig,
        canRunFullHeaded && blockedFlow > 0 ? `${blockedFlow} headed scenario(s) blocked` : null,
        canRunFullHeaded && passedFlow < HEADED_LIMIT ? `${HEADED_LIMIT - passedFlow} headed scenario(s) not passed` : null,
        flowResults[0]?.reason || null,
      ].filter(Boolean).join('; ');
  const coverageFailures = coverageCheck.status === 'PASS' ? [] : coverageCheck.issues;
  const deliveryStatus = validationFailures.length > 0 || coverageFailures.length > 0
    ? 'failed'
    : failedFlow > 0
      ? 'failed'
      : fullHeadedPassed
      ? 'test_complete'
      : 'blocked';

  const summary = {
    schema: 'headed-production-cost-flow-v1',
    campaign: 'pure-headed-production-plan-yield-cost-analysis',
    generatedAt: nowIso(),
    appUrl: APP_URL,
    apiBaseUrl: API_BASE_URL,
    outDir: OUT_DIR,
    profileDir: PROFILE_ROOT,
    playwrightIsolation: {
      persistentProfile: PROFILE_ROOT,
      collidesWithOtherPlaywright: false,
      headed: !HEADLESS,
      viewport: { width: 1920, height: 1080 },
      lang: 'zh-CN',
      playwrightPort: process.env.PLAYWRIGHT_PORT || null,
      playwrightChatId: process.env.PLAYWRIGHT_CHAT_ID || null,
    },
    specTotal: scenarios.length,
    effectiveTotal: fullHeadedPassed ? HEADED_LIMIT : 0,
    actualExecuted: flowResults.length,
    actualPass: passedFlow,
    actualFail: validationFailures.length + failedFlow,
    actualBlocked: fullHeadedPassed ? 0 : scenarios.length - passedFlow - failedFlow,
    depthBreakdown: {
      smoke: flowResults.some((item) => item.evidence?.length) ? 1 : 0,
      medium: flowResults.length - passedFlow,
      deep: passedFlow,
    },
    modelInvariantPass: scenarios.length - validationFailures.length,
    modelInvariantFail: validationFailures.length,
    coverage,
    coverageIssues: coverageFailures,
    actualBugsFound: validationFailures.length + coverageFailures.length,
    sameCauseSweep: validationFailures.length > 0 || coverageFailures.length > 0
      ? 'required after inspecting model or coverage failure'
      : 'not_applicable',
    deliveryStatus,
    blockingReason,
    liveConfig: {
      usernameConfigured: Boolean(process.env.E2E_USERNAME),
      passwordConfigured: Boolean(process.env.E2E_PASSWORD),
      allowMutation: ALLOW_MUTATION,
      headedLimit: HEADED_LIMIT,
      canRunFullHeaded,
      missingFullRunConfig,
      strict: STRICT,
    },
    apiPreflight: api.checks,
    flow: flowResults[0] || null,
    flowResults,
    consoleMessages,
    pageErrors,
    screenshots,
    scenarios,
    validations,
  };

  const paths = await writeResult(summary);
  console.log(JSON.stringify({ deliveryStatus, blockingReason, resultPath: paths.resultPath, matrixPath: paths.matrixPath }, null, 2));
  if (STRICT && deliveryStatus !== 'test_complete') process.exitCode = 2;
  if (validationFailures.length > 0 || coverageFailures.length > 0) process.exitCode = 1;
}

main().catch(async (error) => {
  record('runner', 'FAIL', { message: error.stack || error.message });
  await writeResult({
    schema: 'headed-production-cost-flow-v1',
    campaign: 'pure-headed-production-plan-yield-cost-analysis',
    generatedAt: nowIso(),
    appUrl: APP_URL,
    apiBaseUrl: API_BASE_URL,
    outDir: OUT_DIR,
    profileDir: PROFILE_ROOT,
    deliveryStatus: 'failed',
    blockingReason: error.message,
    screenshots,
    consoleMessages,
    pageErrors,
    scenarios: [],
  });
  process.exitCode = 1;
});
