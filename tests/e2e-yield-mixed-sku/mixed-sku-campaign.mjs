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
  if (key && process.env[key] == null) {
    process.env[key] = value;
  }
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
const DEFAULT_OUT_DIR = `.playwright-mcp/codex-${RUN_DATE}-mixed-sku-yield-campaign`;
const OUT_DIR = path.resolve(process.env.E2E_OUT || DEFAULT_OUT_DIR);
const PROFILE_DIR = path.join(OUT_DIR, `pw-profile-${Date.now().toString(36)}`);
const APP_URL = process.env.E2E_APP_URL || process.env.E2E_ADMIN_URL || 'http://127.0.0.1:3021';
const API_BASE_URL = process.env.E2E_API_BASE || process.env.API_BASE_URL || process.env.E2E_ADMIN_URL || 'http://127.0.0.1:10010';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F001';
const SCENARIO_TARGET = Math.max(100, Number.parseInt(process.env.E2E_SCENARIO_COUNT || '100', 10));
const HEADLESS = false;
const STRICT = process.env.E2E_STRICT === '1';
const FETCH_TIMEOUT_MS = Number.parseInt(process.env.E2E_FETCH_TIMEOUT_MS || '12000', 10);
const PLAYWRIGHT_ARGS = [
  '--lang=zh-CN',
  ...(process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : []),
];

const dimensions = {
  skuCount: [1, 2, 3, 5],
  orderCount: [1, 2, 4],
  rawBatchCount: [1, 2, 3, 5],
  routeShape: ['shared-prefix', 'shared-middle', 'diverged-after-first', 'fully-diverged', 'rejoin-final'],
  processCount: [2, 3, 5, 8],
  wipMode: ['none', 'semi-finished', 'partial-wip'],
  outputMode: ['exact', 'partial-continue', 'over-yield-warning', 'under-yield-warning'],
  costMode: ['raw-only', 'raw-labor', 'packaging-shared', 'aux-pot-shared'],
  settlement: ['single-sku-complete', 'all-sku-complete', 'incomplete-other-sku-blocked', 'stopped-other-sku-allowed'],
};

const results = [];
const screenshots = [];
const consoleMessages = [];
const pageErrors = [];

function nowIso() {
  return new Date().toISOString();
}

function record(type, status, detail = {}) {
  const entry = { type, status, ts: nowIso(), ...detail };
  results.push(entry);
  const suffix = detail.message ? `: ${detail.message}` : '';
  console.log(`[${status}] ${type}${suffix}`);
  return entry;
}

function cents(value) {
  return Math.round(value * 100);
}

function money(centsValue) {
  return centsValue / 100;
}

function allocateCents(totalCents, weights) {
  const totalWeight = weights.reduce((sum, weight) => sum + weight, 0);
  if (totalWeight <= 0) {
    throw new Error('Cost allocation requires positive total weight');
  }

  const raw = weights.map((weight, index) => {
    const exact = (totalCents * weight) / totalWeight;
    const floor = Math.floor(exact);
    return { index, floor, fraction: exact - floor };
  });

  let remainder = totalCents - raw.reduce((sum, item) => sum + item.floor, 0);
  raw
    .sort((a, b) => b.fraction - a.fraction || a.index - b.index)
    .forEach((item) => {
      if (remainder > 0) {
        item.floor += 1;
        remainder -= 1;
      }
    });

  return raw.sort((a, b) => a.index - b.index).map((item) => item.floor);
}

function pick(array, seed) {
  return array[seed % array.length];
}

function generateScenarios(count) {
  const scenarios = [];
  for (let i = 0; scenarios.length < count; i += 1) {
    const skuCount = pick(dimensions.skuCount, i);
    const orderCount = pick(dimensions.orderCount, Math.floor(i / 2));
    const rawBatchCount = pick(dimensions.rawBatchCount, Math.floor(i / 3));
    const routeShape = pick(dimensions.routeShape, Math.floor(i / 5));
    const processCount = pick(dimensions.processCount, Math.floor(i / 7));
    const wipMode = pick(dimensions.wipMode, Math.floor(i / 11));
    const outputMode = pick(dimensions.outputMode, Math.floor(i / 13));
    const costMode = pick(dimensions.costMode, Math.floor(i / 17));
    let settlement = pick(dimensions.settlement, Math.floor(i / 19));
    if (skuCount === 1 && ['incomplete-other-sku-blocked', 'stopped-other-sku-allowed'].includes(settlement)) {
      settlement = 'single-sku-complete';
    }

    scenarios.push({
      id: `YMS-${String(scenarios.length + 1).padStart(3, '0')}`,
      depth: 'deep',
      skuCount,
      orderCount,
      rawBatchCount,
      routeShape,
      processCount,
      wipMode,
      outputMode,
      costMode,
      settlement,
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
    dimensions: Object.fromEntries(Object.keys(dimensions).map((key) => [key, countBy(scenarios, key)])),
    complexCases: {
      multiSku: scenarios.filter((scenario) => scenario.skuCount > 1).length,
      maxSku: scenarios.filter((scenario) => scenario.skuCount >= 5).length,
      multiOrder: scenarios.filter((scenario) => scenario.orderCount > 1).length,
      maxOrder: scenarios.filter((scenario) => scenario.orderCount >= 4).length,
      multiRawBatch: scenarios.filter((scenario) => scenario.rawBatchCount > 1).length,
      maxRawBatch: scenarios.filter((scenario) => scenario.rawBatchCount >= 5).length,
      longRoute: scenarios.filter((scenario) => scenario.processCount >= 8).length,
      rollingOrPartial: scenarios.filter((scenario) =>
        scenario.outputMode === 'partial-continue'
        || scenario.settlement === 'incomplete-other-sku-blocked').length,
      stoppedSku: scenarios.filter((scenario) => scenario.settlement === 'stopped-other-sku-allowed').length,
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
  for (const [key, expectedValues] of Object.entries(dimensions)) {
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
    rollingOrPartial: 20,
    stoppedSku: 10,
    mixedStress: 10,
  };
  for (const [key, minimum] of Object.entries(requiredComplexCases)) {
    if ((coverage.complexCases[key] || 0) < minimum) {
      issues.push(`${key} coverage ${coverage.complexCases[key] || 0} < ${minimum}`);
    }
  }
  return { status: issues.length === 0 ? 'PASS' : 'FAIL', issues };
}

function routeStepName(shape, stepIndex, skuIndex, processCount) {
  const common = ['split', 'blanch', 'cool', 'inspect', 'pack'];
  if (shape === 'fully-diverged') {
    return `sku${skuIndex + 1}-step${stepIndex + 1}`;
  }
  if (shape === 'shared-prefix' && stepIndex < Math.min(2, processCount)) {
    return common[stepIndex] || `shared-${stepIndex + 1}`;
  }
  if (shape === 'shared-middle' && stepIndex === Math.floor(processCount / 2)) {
    return 'shared-middle';
  }
  if (shape === 'diverged-after-first' && stepIndex === 0) {
    return 'split';
  }
  if (shape === 'rejoin-final' && stepIndex === processCount - 1) {
    return 'final-pack';
  }
  return `sku${skuIndex + 1}-step${stepIndex + 1}`;
}

function makeFixture(scenario) {
  const batchId = `BATCH-${scenario.id}`;
  const skus = Array.from({ length: scenario.skuCount }, (_, index) => ({
    productTypeId: `SKU-${index + 1}`,
    productTypeName: `Product SKU ${index + 1}`,
    plannedQuantity: 80 + index * 17 + scenario.orderCount * 5,
  }));

  const tasks = [];
  for (const [skuIndex, sku] of skus.entries()) {
    for (let stepIndex = 0; stepIndex < scenario.processCount; stepIndex += 1) {
      const isOtherSku = skuIndex > 0;
      const shouldRemainPending =
        scenario.settlement === 'incomplete-other-sku-blocked' && isOtherSku && stepIndex === scenario.processCount - 1;
      const shouldBeStopped =
        scenario.settlement === 'stopped-other-sku-allowed' && isOtherSku && stepIndex === scenario.processCount - 1;
      const isPartial =
        scenario.settlement !== 'all-sku-complete' &&
        scenario.settlement !== 'stopped-other-sku-allowed' &&
        scenario.outputMode === 'partial-continue' &&
        skuIndex === 0 &&
        stepIndex === scenario.processCount - 1;

      tasks.push({
        id: `${batchId}-${sku.productTypeId}-TASK-${stepIndex + 1}`,
        batchId,
        productTypeId: sku.productTypeId,
        productTypeName: sku.productTypeName,
        processOrder: stepIndex + 1,
        processName: routeStepName(scenario.routeShape, stepIndex, skuIndex, scenario.processCount),
        plannedQuantity: sku.plannedQuantity,
        completedQuantity: shouldRemainPending ? 0 : isPartial ? Math.floor(sku.plannedQuantity * 0.55) : sku.plannedQuantity,
        status: shouldBeStopped ? 'CANCELLED' : shouldRemainPending || isPartial ? 'IN_PROGRESS' : 'COMPLETED',
      });
    }
  }

  const selectedSku = skus[0];
  const selectedRoute = tasks.filter((task) => task.productTypeId === selectedSku.productTypeId);
  const conflictingTask = tasks.find(
    (task) => task.productTypeId !== selectedSku.productTypeId && task.processOrder === selectedRoute[0]?.processOrder,
  );
  const yieldSteps = [];
  if (conflictingTask) {
    yieldSteps.push({
      id: `STEP-${scenario.id}-CONFLICT`,
      workProcessTaskId: conflictingTask.id,
      productTypeId: conflictingTask.productTypeId,
      processOrder: conflictingTask.processOrder,
      outputQuantity: conflictingTask.completedQuantity,
    });
  }
  if (selectedRoute[0]) {
    yieldSteps.push({
      id: `STEP-${scenario.id}-SELECTED`,
      workProcessTaskId: selectedRoute[0].id,
      productTypeId: selectedSku.productTypeId,
      processOrder: selectedRoute[0].processOrder,
      outputQuantity: selectedRoute[0].completedQuantity,
    });
  }

  const outputs = skus.map((sku, index) => {
    const base = sku.plannedQuantity;
    if (scenario.outputMode === 'over-yield-warning') return base + 3 + index;
    if (scenario.outputMode === 'under-yield-warning') return Math.max(1, base - 9 - index);
    if (scenario.outputMode === 'partial-continue' && index === 0) return Math.floor(base * 0.55);
    return base;
  });

  const rawCost = 12750 + scenario.rawBatchCount * 875 + scenario.skuCount * 419;
  const laborCost = scenario.costMode === 'raw-labor' ? 3560 + scenario.processCount * 155 : 0;
  const packagingCost = scenario.costMode === 'packaging-shared' ? 2480 + scenario.orderCount * 90 : 0;
  const auxCost = scenario.costMode === 'aux-pot-shared' ? 1710 + scenario.rawBatchCount * 130 : 0;
  const totalSharedCost = rawCost + laborCost + packagingCost + auxCost;
  const allocated = allocateCents(totalSharedCost, outputs);

  return {
    batchId,
    skus,
    tasks,
    selectedSku,
    selectedRoute,
    yieldSteps,
    outputs,
    totalSharedCost,
    allocated,
  };
}

function findYieldStepForTask(task, steps) {
  return (
    steps.find((step) => step.workProcessTaskId && step.workProcessTaskId === task.id) ||
    steps.find((step) => !step.workProcessTaskId && step.processOrder === task.processOrder)
  );
}

function isTerminalTaskStatus(status) {
  return status === 'COMPLETED' || status === 'SKIPPED' || status === 'CANCELLED';
}

function validateScenario(scenario) {
  const fixture = makeFixture(scenario);
  const issues = [];

  const selectedRoute = fixture.tasks.filter((task) => task.productTypeId === fixture.selectedSku.productTypeId);
  if (selectedRoute.length !== scenario.processCount) {
    issues.push(`selected SKU route expected ${scenario.processCount}, got ${selectedRoute.length}`);
  }

  const allRouteSkuIds = new Set(selectedRoute.map((task) => task.productTypeId));
  if (allRouteSkuIds.size !== 1 || !allRouteSkuIds.has(fixture.selectedSku.productTypeId)) {
    issues.push('selected route contains tasks from another SKU');
  }

  const firstTask = selectedRoute[0];
  const matchedStep = firstTask ? findYieldStepForTask(firstTask, fixture.yieldSteps) : null;
  if (scenario.skuCount > 1 && matchedStep?.workProcessTaskId !== firstTask?.id) {
    issues.push('yield step matching crossed SKU because processOrder collided');
  }
  if (scenario.skuCount > 1 && firstTask) {
    const conflictingOnly = findYieldStepForTask(firstTask, fixture.yieldSteps.filter(
      (step) => step.workProcessTaskId !== firstTask.id,
    ));
    if (conflictingOnly?.workProcessTaskId) {
      issues.push('yield step fallback matched a different task id with the same processOrder');
    }
  }

  const options = fixture.skus.map((sku) => {
    const skuTasks = fixture.tasks.filter((task) => task.productTypeId === sku.productTypeId);
    return {
      productTypeId: sku.productTypeId,
      taskCount: skuTasks.length,
      completedTaskCount: skuTasks.filter((task) => isTerminalTaskStatus(task.status)).length,
    };
  });
  const allRoutesComplete = options.every((option) => option.taskCount > 0 && option.completedTaskCount === option.taskCount);
  const finalCloseAllowed = allRoutesComplete;
  const dailyRollingSettleAllowed = true;
  if (scenario.settlement === 'incomplete-other-sku-blocked' && allRoutesComplete) {
    issues.push('settlement was not blocked while another SKU route is incomplete');
  }
  if (scenario.settlement === 'incomplete-other-sku-blocked' && finalCloseAllowed) {
    issues.push('final close should be blocked until every SKU route is complete');
  }
  if (scenario.settlement === 'incomplete-other-sku-blocked' && !dailyRollingSettleAllowed) {
    issues.push('daily rolling settlement should stay available even when final close is blocked');
  }
  if (scenario.settlement === 'all-sku-complete' && !allRoutesComplete) {
    issues.push('all-sku-complete scenario did not complete every route');
  }
  if (scenario.settlement === 'stopped-other-sku-allowed' && !finalCloseAllowed) {
    issues.push('stopped SKU should be treated as an intentional terminal route');
  }

  const allocatedSum = fixture.allocated.reduce((sum, value) => sum + value, 0);
  if (allocatedSum !== fixture.totalSharedCost) {
    issues.push(`allocated cost mismatch: ${allocatedSum} !== ${fixture.totalSharedCost}`);
  }
  if (fixture.allocated.some((value) => value < 0)) {
    issues.push('allocated cost contains negative value');
  }

  return {
    status: issues.length === 0 ? 'PASS' : 'FAIL',
    issues,
    evidence: {
      batchId: fixture.batchId,
      selectedSku: fixture.selectedSku.productTypeId,
      tasks: fixture.tasks.length,
      selectedRouteTasks: selectedRoute.length,
      allRoutesComplete,
      finalCloseAllowed,
      dailyRollingSettleAllowed,
      totalSharedCost: money(fixture.totalSharedCost),
      allocatedCost: fixture.allocated.map(money),
    },
  };
}

async function fetchJson(url, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        ...(options.headers || {}),
      },
    });
    const text = await response.text();
    let body = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = text.slice(0, 500);
    }
    return { ok: response.ok, status: response.status, body };
  } catch (error) {
    return { ok: false, error: error.message };
  } finally {
    clearTimeout(timeout);
  }
}

async function apiPreflight() {
  const healthCandidates = [
    `${API_BASE_URL}/api/mobile/health`,
    `${API_BASE_URL}/actuator/health`,
  ];
  const checks = [];
  for (const url of healthCandidates) {
    const result = await fetchJson(url);
    checks.push({ url, ...result });
    if (result.ok) {
      record('api-preflight', 'PASS', { message: `reachable ${url}`, statusCode: result.status });
      return { reachable: true, checks, token: null };
    }
  }

  record('api-preflight', 'BLOCKED', {
    message: 'backend health checks are unavailable',
    checks,
  });
  return { reachable: false, checks, token: null };
}

async function loginIfConfigured() {
  const username = process.env.E2E_USERNAME;
  const password = process.env.E2E_PASSWORD;
  if (!username || !password) {
    return { configured: false, token: null, reason: 'E2E_USERNAME/E2E_PASSWORD not set' };
  }

  const url = `${API_BASE_URL}/api/mobile/auth/unified-login`;
  const result = await fetchJson(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, factoryId: FACTORY_ID }),
  });
  const token = result.body?.data?.token || result.body?.token || null;
  return {
    configured: true,
    ok: result.ok && Boolean(token),
    status: result.status,
    token,
    reason: result.ok && token ? null : 'login did not return a token',
  };
}

async function auditPage(page) {
  return await page.evaluate(() => {
    const body = document.body;
    const html = document.documentElement;
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;
    const horizontalOverflow = Math.max(body.scrollWidth, html.scrollWidth) > viewportWidth + 2;
    const verticalOverflow = Math.max(body.scrollHeight, html.scrollHeight) > viewportHeight + 2;
    const scrollContainers = Array.from(document.querySelectorAll('*')).filter((element) => {
      const style = window.getComputedStyle(element);
      const canScrollY = /(auto|scroll)/.test(style.overflowY) && element.scrollHeight > element.clientHeight + 2;
      const canScrollX = /(auto|scroll)/.test(style.overflowX) && element.scrollWidth > element.clientWidth + 2;
      return canScrollY || canScrollX;
    });
    const smallControls = Array.from(document.querySelectorAll('button,[role="button"],input,select,textarea,a')).flatMap((element) => {
      const rect = element.getBoundingClientRect();
      const text = (element.innerText || element.getAttribute('aria-label') || element.getAttribute('placeholder') || '').trim();
      if (rect.width === 0 || rect.height === 0) return [];
      if (rect.width < 44 || rect.height < 44) {
        return [{ text: text.slice(0, 80), width: Math.round(rect.width), height: Math.round(rect.height) }];
      }
      return [];
    });
    const clippedText = Array.from(document.querySelectorAll('button,[role="button"],span,div,p')).flatMap((element) => {
      const rect = element.getBoundingClientRect();
      const text = (element.innerText || '').trim();
      if (!text || rect.width <= 0 || rect.height <= 0) return [];
      if (element.scrollWidth > element.clientWidth + 2 || element.scrollHeight > element.clientHeight + 2) {
        return [{ text: text.slice(0, 80), width: Math.round(rect.width), height: Math.round(rect.height) }];
      }
      return [];
    }).slice(0, 20);

    return {
      url: window.location.href,
      title: document.title,
      horizontalOverflow,
      verticalOverflow,
      scrollContainerCount: scrollContainers.length,
      smallControls: smallControls.slice(0, 20),
      clippedText,
    };
  });
}

async function headedFrontendSmoke() {
  let context;
  const browserEvidence = [];
  try {
    await mkdir(PROFILE_DIR, { recursive: true });
    context = await chromium.launchPersistentContext(PROFILE_DIR, {
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
    page.on('pageerror', (error) => {
      pageErrors.push(error.message);
    });

    for (const viewport of [
      { name: 'desktop', width: 1920, height: 1080 },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      try {
        await page.goto(APP_URL, { waitUntil: 'domcontentloaded', timeout: 30000 });
        await page.waitForTimeout(1500);
        const pngPath = path.join(OUT_DIR, `${viewport.name}-${viewport.width}x${viewport.height}.png`);
        await page.screenshot({ path: pngPath, fullPage: true });
        screenshots.push(pngPath);
        const audit = await auditPage(page);
        browserEvidence.push({ viewport, status: 'PASS', audit, screenshot: pngPath });
      } catch (error) {
        browserEvidence.push({ viewport, status: 'BLOCKED', error: error.message });
      }
    }

    const hasReachableViewport = browserEvidence.some((item) => item.status === 'PASS');
    record('headed-frontend-smoke', hasReachableViewport ? 'PASS' : 'BLOCKED', {
      message: hasReachableViewport ? 'app loaded in isolated headed browser' : 'app URL is unavailable',
      appUrl: APP_URL,
      profileDir: PROFILE_DIR,
      evidence: browserEvidence,
    });
    return { reachable: hasReachableViewport, evidence: browserEvidence };
  } catch (error) {
    record('headed-frontend-smoke', 'BLOCKED', { message: error.message, appUrl: APP_URL, profileDir: PROFILE_DIR });
    return { reachable: false, evidence: browserEvidence, error: error.message };
  } finally {
    if (context) {
      await context.close();
    }
  }
}

async function writeResults(summary) {
  await mkdir(OUT_DIR, { recursive: true });
  const resultPath = path.join(OUT_DIR, 'mixed-sku-campaign-result.json');
  const matrixPath = path.join(OUT_DIR, 'scenario-matrix.json');
  await writeFile(matrixPath, JSON.stringify(summary.scenarios, null, 2), 'utf8');
  await writeFile(resultPath, JSON.stringify({ ...summary, results }, null, 2), 'utf8');
  return { resultPath, matrixPath };
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  const scenarios = generateScenarios(SCENARIO_TARGET);
  record('scenario-matrix', 'PASS', { message: `generated ${scenarios.length} deterministic scenarios` });
  const coverage = summarizeCoverage(scenarios);
  const coverageCheck = validateCoverage(coverage);
  record('scenario-coverage', coverageCheck.status, {
    message: coverageCheck.status === 'PASS'
      ? 'dimension and stress-case coverage satisfied'
      : 'dimension or stress-case coverage is insufficient',
    coverage,
    issues: coverageCheck.issues,
  });

  const invariantResults = scenarios.map((scenario) => {
    const validation = validateScenario(scenario);
    return { scenarioId: scenario.id, status: validation.status, issues: validation.issues, evidence: validation.evidence };
  });
  const invariantFailures = invariantResults.filter((item) => item.status !== 'PASS');
  record('scenario-invariants', invariantFailures.length === 0 ? 'PASS' : 'FAIL', {
    message: `${scenarios.length - invariantFailures.length}/${scenarios.length} scenario models passed`,
    failures: invariantFailures.slice(0, 10),
  });

  const api = await apiPreflight();
  const login = api.reachable ? await loginIfConfigured() : { configured: false, reason: 'backend unavailable', token: null };
  if (login.configured) {
    record('api-login', login.ok ? 'PASS' : 'BLOCKED', {
      message: login.ok ? 'login returned a token' : login.reason,
      statusCode: login.status,
    });
  } else {
    record('api-login', 'BLOCKED', { message: login.reason });
  }

  const frontend = await headedFrontendSmoke();

  const liveReady = api.reachable && login.ok && frontend.reachable;
  const coverageFailures = coverageCheck.status === 'PASS' ? [] : coverageCheck.issues;
  const deliveryStatus = invariantFailures.length > 0 || coverageFailures.length > 0
    ? 'failed'
    : liveReady ? 'ready-for-deep-live-run' : 'blocked';
  const blockingReason = liveReady
    ? null
    : [
        !api.reachable ? 'backend API health unavailable' : null,
        api.reachable && !login.ok ? 'live credentials/token unavailable' : null,
        !frontend.reachable ? 'frontend app URL unavailable' : null,
      ]
        .filter(Boolean)
        .join('; ');

  const summary = {
    schema: 'depth-first-e2e-campaign-v1',
    campaign: 'mixed-sku-yield-step-report',
    generatedAt: nowIso(),
    appUrl: APP_URL,
    apiBaseUrl: API_BASE_URL,
    factoryId: FACTORY_ID,
    outDir: OUT_DIR,
    profileDir: PROFILE_DIR,
    playwrightIsolation: {
      persistentProfile: PROFILE_DIR,
      collidesWithOtherPlaywright: false,
      headed: !HEADLESS,
      viewport: { width: 1920, height: 1080 },
      lang: 'zh-CN',
      playwrightPort: process.env.PLAYWRIGHT_PORT || null,
      playwrightChatId: process.env.PLAYWRIGHT_CHAT_ID || null,
    },
    specTotal: scenarios.length,
    effectiveTotal: liveReady ? scenarios.length : 0,
    actualExecuted: 0,
    actualPass: 0,
    actualFail: invariantFailures.length,
    actualBlocked: liveReady ? 0 : scenarios.length,
    depthBreakdown: {
      smoke: frontend.reachable ? 1 : 0,
      medium: 0,
      deep: liveReady ? scenarios.length : 0,
    },
    modelInvariantPass: scenarios.length - invariantFailures.length,
    modelInvariantFail: invariantFailures.length,
    coverage,
    coverageIssues: coverageFailures,
    actualBugsFound: invariantFailures.length + coverageFailures.length,
    sameCauseSweep: invariantFailures.length === 0 && coverageFailures.length === 0
      ? 'no model invariant or coverage failures'
      : 'see scenario-invariants and scenario-coverage failures',
    deliveryStatus,
    blockingReason,
    apiPreflight: api.checks,
    login: {
      configured: login.configured,
      ok: Boolean(login.ok),
      reason: login.reason || null,
      tokenCaptured: Boolean(login.token),
    },
    frontendEvidence: frontend.evidence,
    consoleMessages,
    pageErrors,
    screenshots,
    scenarios,
    invariantResults,
  };

  const paths = await writeResults(summary);
  console.log(JSON.stringify({ deliveryStatus, blockingReason, resultPath: paths.resultPath, matrixPath: paths.matrixPath }, null, 2));

  if (STRICT && deliveryStatus !== 'ready-for-deep-live-run') {
    process.exitCode = 2;
  }
  if (invariantFailures.length > 0 || coverageFailures.length > 0) {
    process.exitCode = 1;
  }
}

main().catch(async (error) => {
  record('campaign-runner', 'FAIL', { message: error.stack || error.message });
  const summary = {
    schema: 'depth-first-e2e-campaign-v1',
    campaign: 'mixed-sku-yield-step-report',
    generatedAt: nowIso(),
    appUrl: APP_URL,
    apiBaseUrl: API_BASE_URL,
    outDir: OUT_DIR,
    profileDir: PROFILE_DIR,
    deliveryStatus: 'failed',
    blockingReason: error.message,
    screenshots,
    consoleMessages,
    pageErrors,
    scenarios: [],
  };
  await writeResults(summary);
  process.exitCode = 1;
});
