import { chromium } from '@playwright/test';
import { existsSync, readFileSync } from 'fs';
import { mkdir, writeFile } from 'fs/promises';
import path from 'path';
import process from 'process';

const SOURCE_RESULT = path.resolve(
  process.env.E2E_SOURCE_RESULT
    || '.playwright-mcp/codex-20260626-f006-live-chain-prod-100/production-cost-live-chain-result.json',
);
const OUT_DIR = path.resolve(process.env.E2E_OUT || '.playwright-mcp/codex-20260626-f006-web-admin-complex-mix-readback');
const PROFILE_DIR = path.join(OUT_DIR, `pw-profile-${Date.now().toString(36)}`);
const WEB_URL = process.env.E2E_WEB_URL || 'http://127.0.0.1:5179';
const USERNAME = process.env.E2E_USERNAME;
const PASSWORD = process.env.E2E_PASSWORD;
const PLAYWRIGHT_PORT = process.env.PLAYWRIGHT_PORT || '9246';
const CHAT_ID = process.env.PLAYWRIGHT_CHAT_ID || 'codex-f006-complex-mix-readback';

const records = [];
const screenshots = [];
const consoleMessages = [];
const pageErrors = [];
let activeContext = null;
let activePage = null;

function record(type, status, detail = {}) {
  const entry = { type, status, ts: new Date().toISOString(), ...detail };
  records.push(entry);
  const suffix = detail.message ? `: ${detail.message}` : '';
  console.log(`[${status}] ${type}${suffix}`);
}

function loadSource() {
  if (!existsSync(SOURCE_RESULT)) {
    throw new Error(`source result not found: ${SOURCE_RESULT}`);
  }
  const source = JSON.parse(readFileSync(SOURCE_RESULT, 'utf8'));
  const scenarioById = new Map((source.scenarios || []).map((scenario) => [scenario.id, scenario]));
  return (source.liveResults || []).map((result) => {
    const scenario = scenarioById.get(result.scenarioId) || {};
    const steps = result.evidence?.apiSteps || [];
    const batchCreate = steps.find((step) => step.key === 'batchCreate') || {};
    const batchStart = steps.find((step) => step.key === 'batchStart') || {};
    const close = steps.find((step) => step.key === 'settleDayClose') || {};
    const yieldInputs = steps.filter((step) => step.key === 'yieldReportInput');
    const request = batchCreate.request || {};
    return {
      ...scenario,
      scenarioId: result.scenarioId,
      batchId: batchStart.batchId || result.evidence?.cost?.batchId,
      batchNumber: request.batchNumber,
      notes: request.notes || '',
      actualProductTypeCount: Array.isArray(request.productTypeIds) ? request.productTypeIds.length : 0,
      yieldInputCount: yieldInputs.length,
      closeShouldRemainRolling: close.shouldRemainRolling === true,
    };
  }).filter((scenario) => scenario.batchId);
}

function pickCases(sourceCases) {
  const byId = new Map(sourceCases.map((scenario) => [scenario.scenarioId, scenario]));
  const preferredIds = ['PC-096', 'PC-098', 'PC-099', 'PC-100', 'PC-012'];
  const picked = preferredIds.map((id) => byId.get(id)).filter(Boolean);
  if (picked.length >= 4) return picked;

  const fallback = sourceCases.filter((scenario) =>
    scenario.skuCount > 1
    || scenario.rawBatchCount > 1
    || scenario.processCount >= 5
    || scenario.closeShouldRemainRolling,
  );
  return fallback.slice(-5);
}

async function screenshot(page, name) {
  const file = path.join(OUT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  screenshots.push(file);
  return file;
}

async function login(page) {
  const targetPath = '/production/batches';
  await page.goto(`${WEB_URL}/login?redirect=${encodeURIComponent(targetPath)}`, { waitUntil: 'domcontentloaded' });
  await page.locator('.login-form input').first().waitFor({ state: 'visible', timeout: 30000 });
  await screenshot(page, '00-login');

  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const inputs = page.locator('.login-form input');
    await inputs.nth(0).fill(USERNAME);
    await inputs.nth(1).fill(PASSWORD);
    await page.locator('.login-button').click();
    await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
    await page.waitForTimeout(1000);

    const stillOnLogin = await page.locator('.login-form input').first().isVisible().catch(() => false);
    if (!stillOnLogin) {
      record('login', 'PASS', { message: `${page.url()} attempt=${attempt}` });
      return;
    }
    record('login-attempt', 'WARN', { message: `still on login after attempt ${attempt}` });
  }

  throw new Error(`login did not leave the login form: ${page.url()}`);
}

function summarizePageDom(scenario) {
  const text = document.body.innerText || '';
  const processRows = Array.from(document.querySelectorAll('.process-task-row')).map((row) => row.innerText.trim());
  const orderBadges = Array.from(document.querySelectorAll('.process-task-row .order-badge')).map((node) => node.textContent?.trim() || '');
  const yieldRows = Array.from(document.querySelectorAll('.detail-card .el-table__body-wrapper tbody tr')).map((row) => row.innerText.trim());
  const currentYieldCard = document.querySelector('[data-testid="web-batch-detail-yield-card"]')?.innerText?.trim() || '';
  const yieldRate = document.querySelector('[data-testid="web-batch-detail-yield-rate"]')?.textContent?.trim() || '';
  const rawSectionMatch = text.match(/原料消耗记录\s*共\s*(\d+)\s*条/);
  const processSectionMatch = text.match(/工序明细\s*共\s*(\d+)\s*道/);
  const duplicateProcessOrderCount = orderBadges.length - new Set(orderBadges).size;

  return {
    url: window.location.href,
    title: document.title,
    bodyTextSample: text.slice(0, 2000),
    containsBatchNumber: scenario.batchNumber ? text.includes(scenario.batchNumber) : false,
    hasCurrentYieldCard: Boolean(currentYieldCard),
    currentYieldCard,
    yieldRate,
    hasRollingHint: text.includes('未关单也会显示当前出成率') || text.includes('生产进行中'),
    hasYieldStepSection: text.includes('出成率') && text.includes('逐道报工'),
    hasCostSection: text.includes('成本明细'),
    hasRawConsumptionSection: text.includes('原料消耗记录'),
    rawConsumptionRows: rawSectionMatch ? Number(rawSectionMatch[1]) : 0,
    processSectionRows: processSectionMatch ? Number(processSectionMatch[1]) : processRows.length,
    processRows,
    orderBadges,
    duplicateProcessOrderCount,
    yieldRows: yieldRows.slice(0, 20),
  };
}

function evaluateCase(scenario, pageDom) {
  const checks = [];
  checks.push({
    key: 'detail-loaded',
    pass: pageDom.containsBatchNumber,
    detail: `batchNumber=${scenario.batchNumber}`,
  });
  checks.push({
    key: 'current-yield-visible',
    pass: pageDom.hasCurrentYieldCard && /%$/.test(pageDom.yieldRate),
    detail: `yieldRate=${pageDom.yieldRate}`,
  });
  checks.push({
    key: 'cost-visible',
    pass: pageDom.hasCostSection,
    detail: '成本明细 section',
  });
  checks.push({
    key: 'yield-step-visible',
    pass: pageDom.hasYieldStepSection,
    detail: '出成率 · 逐道报工 section',
  });
  checks.push({
    key: 'mixed-process-readback',
    pass: scenario.processCount >= 5 ? pageDom.processSectionRows >= Math.min(5, scenario.yieldInputCount || 5) : pageDom.processSectionRows > 0,
    detail: `expected processCount=${scenario.processCount}, visibleRows=${pageDom.processSectionRows}`,
  });
  if (scenario.skuCount > 1 || scenario.actualProductTypeCount > 1) {
    checks.push({
      key: 'mixed-sku-readback',
      pass: pageDom.processSectionRows >= scenario.actualProductTypeCount
        && (pageDom.duplicateProcessOrderCount > 0 || pageDom.processSectionRows >= scenario.actualProductTypeCount + 2),
      detail: `skuCount=${scenario.skuCount}, actualProductTypes=${scenario.actualProductTypeCount}, duplicateProcessOrders=${pageDom.duplicateProcessOrderCount}`,
    });
  }
  if (scenario.rawBatchCount > 1) {
    checks.push({
      key: 'mixed-raw-batch-readback',
      pass: pageDom.hasRawConsumptionSection && pageDom.rawConsumptionRows >= Math.min(2, scenario.rawBatchCount),
      detail: `rawBatchCount=${scenario.rawBatchCount}, rawRows=${pageDom.rawConsumptionRows}`,
    });
  }
  if (scenario.closeShouldRemainRolling || scenario.outputMode === 'partial-continue') {
    checks.push({
      key: 'rolling-open-readback',
      pass: pageDom.hasRollingHint,
      detail: `closeShouldRemainRolling=${scenario.closeShouldRemainRolling}, outputMode=${scenario.outputMode}`,
    });
  }

  return {
    status: checks.every((check) => check.pass) ? 'PASS' : 'FAIL',
    checks,
  };
}

async function inspectScenario(page, scenario, index) {
  await page.goto(`${WEB_URL}/production/batches/${scenario.batchId}`, { waitUntil: 'domcontentloaded' });
  await page.locator('[data-testid="web-batch-detail-yield-card"]').waitFor({ state: 'visible', timeout: 30000 });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  const pageDom = await page.evaluate(summarizePageDom, scenario);
  const verdict = evaluateCase(scenario, pageDom);
  const shot = await screenshot(page, `${String(index + 1).padStart(2, '0')}-${scenario.scenarioId}-${scenario.batchId}`);
  record('complex-mix-case', verdict.status, {
    message: `${scenario.scenarioId} batch=${scenario.batchId}`,
    scenario,
    checks: verdict.checks,
    screenshot: shot,
  });
  return { scenario, pageDom, verdict, screenshot: shot, depth: verdict.status === 'PASS' ? 'deep' : 'medium' };
}

async function writeResult(status, extra = {}) {
  const result = {
    schema: 'web-admin-complex-mix-readback-v1',
    status,
    generatedAt: new Date().toISOString(),
    sourceResult: SOURCE_RESULT,
    webUrl: WEB_URL,
    playwright: {
      headed: true,
      viewport: '1920x1080',
      port: PLAYWRIGHT_PORT,
      chatId: CHAT_ID,
      profileDir: PROFILE_DIR,
    },
    records,
    screenshots,
    consoleMessages: consoleMessages.slice(-100),
    pageErrors,
    ...extra,
  };
  await writeFile(path.join(OUT_DIR, 'web-admin-complex-mix-readback-result.json'), JSON.stringify(result, null, 2), 'utf8');
  console.log(JSON.stringify({
    status: result.status,
    outDir: OUT_DIR,
    cases: extra.caseResults?.map((item) => ({
      id: item.scenario.scenarioId,
      batchId: item.scenario.batchId,
      status: item.verdict.status,
      failedChecks: item.verdict.checks.filter((check) => !check.pass).map((check) => check.key),
    })),
  }, null, 2));
}

async function main() {
  if (!USERNAME || !PASSWORD) {
    throw new Error('E2E_USERNAME and E2E_PASSWORD are required');
  }
  await mkdir(OUT_DIR, { recursive: true });
  const sourceCases = loadSource();
  const pickedCases = pickCases(sourceCases);
  record('case-selection', pickedCases.length > 0 ? 'PASS' : 'FAIL', {
    message: `selected ${pickedCases.length} cases`,
    pickedCases,
  });
  if (pickedCases.length === 0) throw new Error('no complex cases selected');

  activeContext = await chromium.launchPersistentContext(PROFILE_DIR, {
    headless: false,
    viewport: { width: 1920, height: 1080 },
    locale: 'zh-CN',
    args: [`--remote-debugging-port=${PLAYWRIGHT_PORT}`, '--lang=zh-CN'],
  });
  activePage = activeContext.pages()[0] || await activeContext.newPage();
  activePage.on('console', (msg) => {
    if (['error', 'warning'].includes(msg.type())) {
      consoleMessages.push({ type: msg.type(), text: msg.text() });
    }
  });
  activePage.on('pageerror', (error) => pageErrors.push(error.message));

  await login(activePage);
  const caseResults = [];
  for (const [index, scenario] of pickedCases.entries()) {
    caseResults.push(await inspectScenario(activePage, scenario, index));
  }

  await activeContext.close();
  activeContext = null;

  const passed = caseResults.filter((item) => item.verdict.status === 'PASS').length;
  const failed = caseResults.length - passed;
  const status = failed === 0 && pageErrors.length === 0 ? 'PASS' : 'FAIL';
  await writeResult(status, {
    selectedTotal: pickedCases.length,
    actualExecuted: caseResults.length,
    actualPass: passed,
    actualFail: failed,
    depthBreakdown: {
      smoke: 0,
      medium: failed,
      deep: passed,
    },
    caseResults,
  });
  if (status !== 'PASS') process.exitCode = 1;
}

main().catch(async (error) => {
  record('run', 'FAIL', { message: error.message, stack: error.stack });
  if (activePage) await screenshot(activePage, '99-failure-state').catch(() => {});
  if (activeContext) await activeContext.close().catch(() => {});
  await mkdir(OUT_DIR, { recursive: true });
  await writeResult('FAIL', { error: error.message, stack: error.stack });
  process.exitCode = 1;
});
