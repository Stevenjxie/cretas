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
const OUT_DIR = path.resolve(process.env.E2E_OUT || `.playwright-mcp/codex-${RUN_DATE}-production-cost-live-chain`);
const PROFILE_DIR = path.join(OUT_DIR, `pw-profile-${Date.now().toString(36)}`);
const APP_URL = process.env.E2E_APP_URL || process.env.E2E_ADMIN_URL || 'http://127.0.0.1:3021';
const API_BASE_URL = process.env.E2E_API_BASE || process.env.API_BASE_URL || process.env.E2E_ADMIN_URL || 'http://127.0.0.1:10010';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F001';
const HEADLESS = false;
const STRICT = process.env.E2E_STRICT === '1';
const ALLOW_MUTATION = process.env.E2E_ALLOW_MUTATION === '1';
const RUN_LIVE = process.env.E2E_RUN_LIVE === '1';
const DISCOVER_FIXTURES = process.env.E2E_DISCOVER_FIXTURES !== '0';
const SCENARIO_TARGET = Math.max(100, Number.parseInt(process.env.E2E_SCENARIO_COUNT || '100', 10));
const LIVE_LIMIT = Math.min(SCENARIO_TARGET, Number.parseInt(process.env.E2E_LIVE_SCENARIO_COUNT || '1', 10));
const FETCH_TIMEOUT_MS = Number.parseInt(process.env.E2E_FETCH_TIMEOUT_MS || '15000', 10);
const PLAYWRIGHT_ARGS = [
  '--lang=zh-CN',
  ...(process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : []),
];

const records = [];
const consoleMessages = [];
const pageErrors = [];
const screenshots = [];

const apiContract = [
  { key: 'health', method: 'GET', path: '/api/mobile/health', depthGate: 'preflight' },
  { key: 'login', method: 'POST', path: '/api/mobile/auth/unified-login', depthGate: 'auth' },
  { key: 'planCreate', method: 'POST', path: '/api/mobile/{factoryId}/production-plans', depthGate: 'deep' },
  { key: 'planReadback', method: 'GET', path: '/api/mobile/{factoryId}/production-plans/{planId}', depthGate: 'deep' },
  { key: 'planStart', method: 'POST', path: '/api/mobile/{factoryId}/production-plans/{planId}/start', depthGate: 'deep' },
  { key: 'batchCreate', method: 'POST', path: '/api/mobile/{factoryId}/processing/batches', depthGate: 'deep' },
  { key: 'batchStart', method: 'POST', path: '/api/mobile/{factoryId}/processing/batches/{batchId}/start', depthGate: 'deep' },
  { key: 'spawnTasks', method: 'POST', path: '/api/mobile/{factoryId}/production/batches/{batchId}/spawn-tasks', depthGate: 'deep' },
  { key: 'taskList', method: 'GET', path: '/api/mobile/{factoryId}/production/batches/{batchId}/work-process-tasks', depthGate: 'deep' },
  { key: 'yieldLimit', method: 'GET', path: '/api/mobile/{factoryId}/production/batches/{batchId}/yield/limits', depthGate: 'medium' },
  { key: 'yieldReport', method: 'POST', path: '/api/mobile/{factoryId}/production/batches/{batchId}/reports', depthGate: 'deep' },
  { key: 'yieldReadback', method: 'GET', path: '/api/mobile/{factoryId}/production/batches/{batchId}/yield', depthGate: 'deep' },
  { key: 'settleDayRolling', method: 'POST', path: '/api/mobile/{factoryId}/production/batches/{batchId}/settle-day?triggerComplete=false', depthGate: 'deep' },
  { key: 'settleDayClose', method: 'POST', path: '/api/mobile/{factoryId}/production/batches/{batchId}/settle-day?triggerComplete=true', depthGate: 'deep' },
  { key: 'costSimple', method: 'GET', path: '/api/mobile/{factoryId}/processing/batches/{batchId}/cost-analysis', depthGate: 'deep' },
  { key: 'costEnhanced', method: 'GET', path: '/api/mobile/{factoryId}/processing/batches/{batchId}/cost-analysis/enhanced', depthGate: 'deep' },
  { key: 'financeReport', method: 'GET', path: '/api/mobile/{factoryId}/reports/finance', depthGate: 'downstream' },
];

const routeAuditTargets = [
  { route: 'ProductionPlanManagement', label: 'production plan list/create entry' },
  { route: 'YieldBatchSelect', label: 'yield batch selection' },
  { route: 'YieldStepReport', label: 'mixed SKU yield step report' },
  { route: 'CostAnalysisDashboard', label: 'batch cost analysis dashboard' },
];

function nowIso() {
  return new Date().toISOString();
}

function record(type, status, detail = {}) {
  const entry = { type, status, ts: nowIso(), ...detail };
  records.push(entry);
  const message = detail.message ? `: ${detail.message}` : '';
  console.log(`[${status}] ${type}${message}`);
  return entry;
}

function splitEnv(name) {
  return (process.env[name] || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function todayLocal(offset = 0) {
  const date = new Date(Date.now() + 8 * 60 * 60 * 1000 + offset * 24 * 60 * 60 * 1000);
  return date.toISOString().slice(0, 10);
}

function cents(value) {
  return Math.round(Number(value) * 100);
}

function money(centsValue) {
  return centsValue / 100;
}

function allocateCents(totalCents, weights) {
  const totalWeight = weights.reduce((sum, value) => sum + value, 0);
  const floors = weights.map((weight, index) => {
    const exact = (totalCents * weight) / totalWeight;
    return { index, cents: Math.floor(exact), fraction: exact - Math.floor(exact) };
  });
  let remainder = totalCents - floors.reduce((sum, item) => sum + item.cents, 0);
  floors
    .sort((a, b) => b.fraction - a.fraction || a.index - b.index)
    .forEach((item) => {
      if (remainder > 0) {
        item.cents += 1;
        remainder -= 1;
      }
    });
  return floors.sort((a, b) => a.index - b.index).map((item) => item.cents);
}

function integerLike(value) {
  return typeof value === 'number' || /^\d+$/.test(String(value || ''));
}

const apiScenarioDimensions = {
  skuCount: [1, 2, 3, 5],
  orderCount: [1, 2, 4],
  rawBatchCount: [1, 2, 3, 5],
  processCount: [2, 3, 5, 8],
  routeShape: ['shared-prefix', 'shared-middle', 'diverged-after-first', 'fully-diverged', 'rejoin-final'],
  costMode: ['raw-only', 'raw-labor', 'packaging-shared', 'aux-pot-shared'],
  outputMode: ['exact', 'partial-continue', 'over-yield-warning', 'under-yield-warning'],
};

function buildScenarios(count) {
  const scenarios = [];

  for (let i = 0; scenarios.length < count; i += 1) {
    const skuCount = apiScenarioDimensions.skuCount[i % apiScenarioDimensions.skuCount.length];
    const orderCount = apiScenarioDimensions.orderCount[Math.floor(i / 2) % apiScenarioDimensions.orderCount.length];
    const rawBatchCount = apiScenarioDimensions.rawBatchCount[Math.floor(i / 3) % apiScenarioDimensions.rawBatchCount.length];
    const processCount = apiScenarioDimensions.processCount[Math.floor(i / 5) % apiScenarioDimensions.processCount.length];
    const outputMode = apiScenarioDimensions.outputMode[Math.floor(i / 7) % apiScenarioDimensions.outputMode.length];
    const weights = Array.from({ length: skuCount }, (_, skuIndex) => 100 + skuIndex * 17 + orderCount * 9);
    const totalCost = 10000 + skuCount * 317 + rawBatchCount * 941 + processCount * 223;
    const allocation = allocateCents(totalCost, weights);

    scenarios.push({
      id: `PC-${String(scenarios.length + 1).padStart(3, '0')}`,
      depth: 'deep',
      skuCount,
      orderCount,
      rawBatchCount,
      processCount,
      routeShape: apiScenarioDimensions.routeShape[Math.floor(i / 11) % apiScenarioDimensions.routeShape.length],
      outputMode,
      costMode: apiScenarioDimensions.costMode[Math.floor(i / 13) % apiScenarioDimensions.costMode.length],
      expected: {
        totalCostCents: totalCost,
        outputWeights: weights,
        allocatedCostCents: allocation,
        costAllocationExact: allocation.reduce((sum, value) => sum + value, 0) === totalCost,
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
    dimensions: Object.fromEntries(Object.keys(apiScenarioDimensions).map((key) => [key, countBy(scenarios, key)])),
    complexCases: {
      multiSku: scenarios.filter((scenario) => scenario.skuCount > 1).length,
      maxSku: scenarios.filter((scenario) => scenario.skuCount >= 5).length,
      multiOrder: scenarios.filter((scenario) => scenario.orderCount > 1).length,
      maxOrder: scenarios.filter((scenario) => scenario.orderCount >= 4).length,
      multiRawBatch: scenarios.filter((scenario) => scenario.rawBatchCount > 1).length,
      maxRawBatch: scenarios.filter((scenario) => scenario.rawBatchCount >= 5).length,
      longRoute: scenarios.filter((scenario) => scenario.processCount >= 8).length,
      rollingPartial: scenarios.filter((scenario) => scenario.outputMode === 'partial-continue').length,
      yieldWarnings: scenarios.filter((scenario) =>
        scenario.outputMode === 'over-yield-warning'
        || scenario.outputMode === 'under-yield-warning').length,
      sharedCostModes: scenarios.filter((scenario) =>
        scenario.costMode === 'packaging-shared'
        || scenario.costMode === 'aux-pot-shared').length,
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
  for (const [key, expectedValues] of Object.entries(apiScenarioDimensions)) {
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
    rollingPartial: 10,
    yieldWarnings: 20,
    sharedCostModes: 20,
    mixedStress: 10,
  };
  for (const [key, minimum] of Object.entries(requiredComplexCases)) {
    if ((coverage.complexCases[key] || 0) < minimum) {
      issues.push(`${key} coverage ${coverage.complexCases[key] || 0} < ${minimum}`);
    }
  }
  return { status: issues.length === 0 ? 'PASS' : 'FAIL', issues };
}

function validateScenarioMath(scenario) {
  const issues = [];
  if (scenario.expected.allocatedCostCents.reduce((sum, value) => sum + value, 0) !== scenario.expected.totalCostCents) {
    issues.push('allocated cost does not sum back to total cents');
  }
  if (scenario.expected.allocatedCostCents.some((value) => value < 0)) {
    issues.push('allocated cost contains negative cents');
  }
  if (scenario.outputMode === 'partial-continue' && scenario.processCount < 2) {
    issues.push('partial continue requires at least two process steps');
  }
  return { status: issues.length === 0 ? 'PASS' : 'FAIL', issues };
}

function requiredLiveConfig() {
  const productTypeIds = splitEnv('E2E_PRODUCT_TYPE_IDS');
  const customerIds = splitEnv('E2E_CUSTOMER_IDS');
  return {
    username: process.env.E2E_USERNAME || '',
    password: process.env.E2E_PASSWORD || '',
    productTypeIds,
    customerIds,
    supervisorId: process.env.E2E_SUPERVISOR_ID || '',
    missing: [
      !process.env.E2E_USERNAME ? 'E2E_USERNAME' : null,
      !process.env.E2E_PASSWORD ? 'E2E_PASSWORD' : null,
      !ALLOW_MUTATION ? 'E2E_ALLOW_MUTATION=1' : null,
      !RUN_LIVE ? 'E2E_RUN_LIVE=1' : null,
    ].filter(Boolean),
  };
}

function normalizeDataArray(body) {
  if (Array.isArray(body)) return body;
  if (Array.isArray(body?.data)) return body.data;
  if (Array.isArray(body?.data?.content)) return body.data.content;
  if (Array.isArray(body?.content)) return body.content;
  return [];
}

async function fetchJson(pathOrUrl, options = {}, token = null) {
  const url = pathOrUrl.startsWith('http') ? pathOrUrl : `${API_BASE_URL}${pathOrUrl}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
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
    return { ok: response.ok, status: response.status, body, url };
  } catch (error) {
    return { ok: false, error: error.message, url };
  } finally {
    clearTimeout(timer);
  }
}

async function apiPreflight() {
  const checks = [];
  for (const pathName of ['/api/mobile/health', '/actuator/health']) {
    const result = await fetchJson(pathName);
    checks.push(result);
    if (result.ok) {
      return { reachable: true, checks };
    }
  }
  return { reachable: false, checks };
}

async function login(config) {
  const result = await fetchJson('/api/mobile/auth/unified-login', {
    method: 'POST',
    body: JSON.stringify({ username: config.username, password: config.password, factoryId: FACTORY_ID }),
  });
  const token = result.body?.data?.token || result.body?.token || null;
  const user = result.body?.data?.user || result.body?.data?.factoryUser || result.body?.user || {};
  const userId = result.body?.data?.userId || user.id || user.userId || null;
  return { ok: result.ok && Boolean(token), status: result.status, token, userId, reason: token ? null : 'login did not return token', raw: result };
}

async function discoverLiveFixtures(config, token, auth) {
  const discovered = {
    enabled: DISCOVER_FIXTURES,
    productTypes: null,
    configuredProductTypes: null,
    customers: null,
    supervisor: null,
  };
  const resolved = {
    ...config,
    productTypeIds: [...config.productTypeIds],
    customerIds: [...config.customerIds],
    supervisorId: config.supervisorId,
  };

  if (!DISCOVER_FIXTURES || !token) {
    return { config: resolved, discovered };
  }

  if (resolved.productTypeIds.length < 5) {
    const result = await fetchJson(`/api/mobile/${FACTORY_ID}/product-types`, {}, token);
    const candidates = normalizeDataArray(result.body)
      .filter((item) => item && item.id != null && item.enabled !== false && item.active !== false)
      .map((item) => ({ id: String(item.id), name: item.name || item.productName || '' }));
    const configured = [];
    for (const candidate of candidates) {
      const processResult = await fetchJson(
        `/api/mobile/${FACTORY_ID}/product-work-processes?productTypeId=${encodeURIComponent(candidate.id)}`,
        {},
        token,
      );
      const processes = normalizeDataArray(processResult.body);
      if (processResult.ok && processes.length > 0) {
        configured.push({ ...candidate, processCount: processes.length });
      }
    }
    const preferredIds = (configured.length > 0 ? configured : candidates).map((item) => item.id);
    resolved.productTypeIds = [...new Set([...resolved.productTypeIds, ...preferredIds])].slice(
      0,
      Math.max(5, resolved.productTypeIds.length),
    );
    discovered.productTypes = {
      ok: result.ok,
      status: result.status,
      found: candidates.length,
      selected: resolved.productTypeIds.length,
    };
    discovered.configuredProductTypes = {
      found: configured.length,
      selected: resolved.productTypeIds.filter((id) => configured.some((item) => item.id === id)).length,
      sample: configured.slice(0, 5),
    };
  }

  if (resolved.customerIds.length < 1) {
    const result = await fetchJson(`/api/mobile/${FACTORY_ID}/customers/active`, {}, token);
    const ids = normalizeDataArray(result.body)
      .filter((item) => item && item.id != null && item.enabled !== false && item.active !== false)
      .map((item) => String(item.id));
    resolved.customerIds = [...new Set([...resolved.customerIds, ...ids])].slice(0, Math.max(1, resolved.customerIds.length));
    discovered.customers = {
      ok: result.ok,
      status: result.status,
      found: ids.length,
      selected: resolved.customerIds.length,
    };
  }

  if (!resolved.supervisorId && auth?.userId != null) {
    resolved.supervisorId = String(auth.userId);
    discovered.supervisor = {
      source: 'login-user-id',
      configured: true,
    };
  } else {
    discovered.supervisor = {
      source: resolved.supervisorId ? 'env' : 'missing',
      configured: Boolean(resolved.supervisorId),
    };
  }

  return { config: resolved, discovered };
}

function liveFixtureBlockers(config) {
  return [
    config.productTypeIds.length < 1 ? 'E2E_PRODUCT_TYPE_IDS or discovered product types' : null,
    config.customerIds.length < 1 ? 'E2E_CUSTOMER_IDS or discovered active customers' : null,
    !config.supervisorId ? 'E2E_SUPERVISOR_ID or login userId' : null,
  ].filter(Boolean);
}

function endpoint(pathTemplate, params = {}) {
  return pathTemplate
    .replaceAll('{factoryId}', FACTORY_ID)
    .replaceAll('{planId}', params.planId)
    .replaceAll('{batchId}', params.batchId);
}

function scenarioProductTypeIds(scenario, config, scenarioIndex) {
  const available = config.productTypeIds;
  const count = Math.max(1, Math.min(scenario.skuCount, available.length));
  const ids = [];
  for (let i = 0; i < count; i += 1) {
    ids.push(available[(scenarioIndex + i) % available.length]);
  }
  return [...new Set(ids)];
}

function scenarioPlanPayload(scenario, config, scenarioIndex) {
  const customerId = config.customerIds[scenarioIndex % Math.max(config.customerIds.length, 1)];
  const productTypeIds = scenarioProductTypeIds(scenario, config, scenarioIndex);
  const payload = {
    planType: 'FUTURE',
    productTypeId: productTypeIds[0],
    plannedQuantity: scenario.expected.outputWeights[0],
    plannedDate: todayLocal(1),
    expectedCompletionDate: todayLocal(2),
    skipProcessReporting: false,
    customerOrderNumber: `E2E-${scenario.id}`,
    sourceCustomerName: 'F006 E2E 客户',
    notes: `E2E ${scenario.id} mixed-sku live chain ${Date.now()}`,
  };
  if (integerLike(customerId)) {
    payload.customerId = Number(customerId);
  }
  return payload;
}

function scenarioBatchPayload(scenario, config, scenarioIndex, planId) {
  const productTypeIds = scenarioProductTypeIds(scenario, config, scenarioIndex);
  const productTypeId = productTypeIds[0];
  const targetQuantity = scenario.expected.outputWeights.reduce((sum, value) => sum + value, 0);
  const materialCost = money(Math.round(scenario.expected.totalCostCents * 0.72));
  const laborCost = money(Math.round(scenario.expected.totalCostCents * 0.18));
  const equipmentCost = money(Math.round(scenario.expected.totalCostCents * 0.06));
  const otherCost = money(
    scenario.expected.totalCostCents
      - cents(materialCost)
      - cents(laborCost)
      - cents(equipmentCost),
  );
  return {
    batchNumber: `E2E-${scenario.id}-${Date.now().toString(36)}`,
    productionPlanId: planId,
    productTypeId,
    productTypeIds,
    plannedQuantity: targetQuantity,
    quantity: targetQuantity,
    unit: 'kg',
    supervisorId: Number(config.supervisorId),
    workerCount: 2,
    materialCost,
    laborCost,
    equipmentCost,
    otherCost,
    notes: `E2E ${scenario.id} batch route=${scenario.routeShape} sku=${scenario.skuCount}`,
  };
}

function reportQuantitiesForTask(task, scenario, stepIndex) {
  const planned = Number(task.plannedQuantity || task.targetQuantity || scenario.expected.outputWeights[0] || 100);
  const under = scenario.outputMode === 'under-yield-warning' ? 0.91 : 1;
  const over = scenario.outputMode === 'over-yield-warning' ? 1.08 : 1;
  const partial = scenario.outputMode === 'partial-continue' && stepIndex === 0 ? 0.55 : 1;
  const output = Math.max(1, Math.round(planned * under * over * partial * 100) / 100);
  return { planned, output };
}

function inputReportPayloadForTask(task, scenario, stepIndex) {
  const { planned } = reportQuantitiesForTask(task, scenario, stepIndex);
  return {
    workProcessTaskId: task.id,
    inputQuantity: planned,
    inputUnit: task.plannedUnit || task.outputUnit || 'kg',
    reportKind: 'INPUT',
    workMinutes: 10 + stepIndex,
    workerCount: 2,
    reporterName: 'codex-e2e',
    costCategory: stepIndex === 0 ? 'RAW_MATERIAL' : 'OTHER',
  };
}

function outputReportPayloadForTask(task, scenario, stepIndex) {
  const { output } = reportQuantitiesForTask(task, scenario, stepIndex);
  return {
    workProcessTaskId: task.id,
    outputQuantity: output,
    outputUnit: task.outputUnit || task.plannedUnit || 'kg',
    reportKind: 'OUTPUT',
    markComplete: scenario.outputMode === 'partial-continue' && stepIndex === 0 ? false : true,
    workMinutes: 20 + stepIndex * 3,
    workerCount: 2,
    forceSubmit: scenario.outputMode === 'over-yield-warning',
    reporterName: 'codex-e2e',
    costCategory: stepIndex === 0 ? 'RAW_MATERIAL' : 'OTHER',
  };
}

async function runApiDeepScenario(scenario, config, token, scenarioIndex) {
  const evidence = { scenarioId: scenario.id, apiSteps: [] };
  const addStep = (key, result, extra = {}) => {
    evidence.apiSteps.push({
      key,
      status: result.status || null,
      ok: Boolean(result.ok),
      url: result.url,
      error: result.error || null,
      responseBody: result.ok ? undefined : result.body,
      ...extra,
    });
  };

  const planPayload = scenarioPlanPayload(scenario, config, scenarioIndex);
  let result = await fetchJson(endpoint('/api/mobile/{factoryId}/production-plans'), {
    method: 'POST',
    body: JSON.stringify(planPayload),
  }, token);
  addStep('planCreate', result, { request: { ...planPayload, notes: '[redacted unique e2e note]' } });
  if (!result.ok || !result.body?.data?.id) return { status: 'BLOCKED', reason: 'planCreate failed', evidence };
  const planId = result.body.data.id;

  result = await fetchJson(endpoint('/api/mobile/{factoryId}/production-plans/{planId}', { planId }), {}, token);
  addStep('planReadback', result, { planId });
  if (!result.ok || result.body?.data?.id !== planId) return { status: 'FAIL', reason: 'plan readback mismatch', evidence };

  result = await fetchJson(endpoint('/api/mobile/{factoryId}/production-plans/{planId}/start', { planId }), { method: 'POST' }, token);
  addStep('planStart', result, { planId });
  if (!result.ok) return { status: 'BLOCKED', reason: 'planStart failed', evidence };

  const batchPayload = scenarioBatchPayload(scenario, config, scenarioIndex, planId);
  result = await fetchJson(endpoint('/api/mobile/{factoryId}/processing/batches'), {
    method: 'POST',
    body: JSON.stringify(batchPayload),
  }, token);
  addStep('batchCreate', result, { request: batchPayload });
  if (!result.ok || !result.body?.data?.id) return { status: 'BLOCKED', reason: 'batchCreate failed', evidence };
  const batchId = result.body.data.id;

  result = await fetchJson(`${endpoint('/api/mobile/{factoryId}/processing/batches/{batchId}/start', { batchId })}?supervisorId=${encodeURIComponent(config.supervisorId)}`, { method: 'POST' }, token);
  addStep('batchStart', result, { batchId });
  if (!result.ok) return { status: 'BLOCKED', reason: 'batchStart failed', evidence };

  const spawnPayload = {
    productTypeId: batchPayload.productTypeId,
    productTypeIds: batchPayload.productTypeIds,
  };
  result = await fetchJson(endpoint('/api/mobile/{factoryId}/production/batches/{batchId}/spawn-tasks', { batchId }), {
    method: 'POST',
    body: JSON.stringify(spawnPayload),
  }, token);
  addStep('spawnTasks', result, { batchId, productTypeId: batchPayload.productTypeId, productTypeIds: batchPayload.productTypeIds });
  if (!result.ok) return { status: 'BLOCKED', reason: 'spawnTasks failed', evidence };

  result = await fetchJson(endpoint('/api/mobile/{factoryId}/production/batches/{batchId}/work-process-tasks', { batchId }), {}, token);
  addStep('taskList', result, { batchId });
  const tasks = Array.isArray(result.body?.data) ? result.body.data : [];
  if (!result.ok || tasks.length === 0) return { status: 'BLOCKED', reason: 'no work process tasks available for batch', evidence };

  const taskSkuIds = new Set(tasks.map((task) => task.productTypeId).filter(Boolean));
  if (scenario.skuCount > 1 && taskSkuIds.size < Math.min(2, scenario.skuCount)) {
    return { status: 'FAIL', reason: 'multi-SKU scenario did not create multi-SKU task routes', evidence };
  }

  const reportsToSubmit = scenario.outputMode === 'partial-continue'
    ? tasks.slice(0, 1)
    : tasks;
  for (const [stepIndex, task] of reportsToSubmit.entries()) {
    let reportPayload = inputReportPayloadForTask(task, scenario, stepIndex);
    result = await fetchJson(endpoint('/api/mobile/{factoryId}/production/batches/{batchId}/reports', { batchId }), {
      method: 'POST',
      body: JSON.stringify(reportPayload),
    }, token);
    addStep('yieldReportInput', result, { batchId, taskId: task.id, processOrder: task.processOrder, productTypeId: task.productTypeId });
    if (!result.ok) return { status: 'BLOCKED', reason: 'yieldReport input failed', evidence };

    reportPayload = outputReportPayloadForTask(task, scenario, stepIndex);
    result = await fetchJson(endpoint('/api/mobile/{factoryId}/production/batches/{batchId}/reports', { batchId }), {
      method: 'POST',
      body: JSON.stringify(reportPayload),
    }, token);
    addStep('yieldReportOutput', result, { batchId, taskId: task.id, processOrder: task.processOrder, productTypeId: task.productTypeId });
    if (!result.ok) return { status: 'BLOCKED', reason: 'yieldReport output failed', evidence };
  }

  result = await fetchJson(endpoint('/api/mobile/{factoryId}/production/batches/{batchId}/yield', { batchId }), {}, token);
  addStep('yieldReadback', result, { batchId });
  if (!result.ok || !Array.isArray(result.body?.data?.steps)) return { status: 'FAIL', reason: 'yield readback missing steps', evidence };

  const date = todayLocal(0);
  const shouldRemainRolling = scenario.outputMode === 'partial-continue';
  result = await fetchJson(`${endpoint('/api/mobile/{factoryId}/production/batches/{batchId}/settle-day', { batchId })}?date=${date}&triggerComplete=false`, { method: 'POST' }, token);
  addStep('settleDayRolling', result, { batchId, date });
  if (!result.ok) return { status: 'BLOCKED', reason: 'rolling settleDay failed', evidence };
  if (result.body?.data?.completed === true) {
    return { status: 'FAIL', reason: 'rolling settle-day unexpectedly closed the batch', evidence };
  }

  result = await fetchJson(`${endpoint('/api/mobile/{factoryId}/production/batches/{batchId}/settle-day', { batchId })}?date=${date}&triggerComplete=true`, { method: 'POST' }, token);
  addStep('settleDayClose', result, { batchId, date, shouldRemainRolling });
  if (!result.ok) return { status: 'BLOCKED', reason: 'close settleDay failed', evidence };
  const closeCompleted = result.body?.data?.completed;
  if (shouldRemainRolling && closeCompleted !== false) {
    return { status: 'FAIL', reason: 'rolling or partial scenario was allowed to close', evidence };
  }
  if (!shouldRemainRolling && closeCompleted !== true) {
    return { status: 'FAIL', reason: 'complete scenario did not close after every task report', evidence };
  }

  result = await fetchJson(endpoint('/api/mobile/{factoryId}/processing/batches/{batchId}/cost-analysis', { batchId }), {}, token);
  addStep('costSimple', result, { batchId });
  if (!result.ok || result.body?.data?.totalCost == null) return { status: 'FAIL', reason: 'simple cost analysis missing totalCost', evidence };
  const simpleCostData = result.body.data;

  result = await fetchJson(endpoint('/api/mobile/{factoryId}/processing/batches/{batchId}/cost-analysis/enhanced', { batchId }), {}, token);
  addStep('costEnhanced', result, { batchId });
  if (!result.ok || result.body?.data?.costBreakdown?.totalCost == null) return { status: 'FAIL', reason: 'enhanced cost analysis missing total cost', evidence };
  const enhancedCostData = result.body.data;

  const simpleTotalCents = cents(simpleCostData.totalCost);
  const enhancedTotalCents = cents(enhancedCostData.costBreakdown.totalCost);
  if (Math.abs(simpleTotalCents - enhancedTotalCents) > 1) {
    return { status: 'FAIL', reason: 'simple and enhanced cost totals differ by more than one cent', evidence };
  }
  evidence.cost = {
    batchId,
    totalCostSimple: simpleCostData.totalCost,
    totalCostEnhanced: enhancedCostData.costBreakdown.totalCost,
    totalCostSimpleCents: simpleTotalCents,
    totalCostEnhancedCents: enhancedTotalCents,
  };
  return { status: 'PASS', reason: null, evidence };
}

async function auditPage(page) {
  return page.evaluate(() => {
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;
    const horizontalOverflow = Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) > viewportWidth + 2;
    const verticalOverflow = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight) > viewportHeight + 2;
    const scrollContainers = Array.from(document.querySelectorAll('*')).filter((element) => {
      const style = window.getComputedStyle(element);
      const y = /(auto|scroll)/.test(style.overflowY) && element.scrollHeight > element.clientHeight + 2;
      const x = /(auto|scroll)/.test(style.overflowX) && element.scrollWidth > element.clientWidth + 2;
      return x || y;
    });
    const smallControls = Array.from(document.querySelectorAll('button,[role="button"],a,input,select,textarea')).flatMap((element) => {
      const rect = element.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) return [];
      if (rect.width < 44 || rect.height < 44) {
        const text = (element.innerText || element.getAttribute('aria-label') || element.getAttribute('placeholder') || '').trim();
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

async function runHeadedUiAudit() {
  let context;
  const evidence = [];
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
    page.on('pageerror', (error) => pageErrors.push(error.message));

    for (const viewport of [
      { name: 'desktop', width: 1920, height: 1080 },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      try {
        await page.goto(APP_URL, { waitUntil: 'domcontentloaded', timeout: 30000 });
        await page.waitForTimeout(1500);
        const screenshot = path.join(OUT_DIR, `${viewport.name}-${viewport.width}x${viewport.height}.png`);
        await page.screenshot({ path: screenshot, fullPage: true });
        screenshots.push(screenshot);
        evidence.push({ viewport, status: 'PASS', audit: await auditPage(page), screenshot });
      } catch (error) {
        evidence.push({ viewport, status: 'BLOCKED', error: error.message });
      }
    }

    return { reachable: evidence.some((item) => item.status === 'PASS'), evidence };
  } finally {
    if (context) await context.close();
  }
}

async function writeResult(summary) {
  await mkdir(OUT_DIR, { recursive: true });
  const resultPath = path.join(OUT_DIR, 'production-cost-live-chain-result.json');
  const matrixPath = path.join(OUT_DIR, 'scenario-matrix.json');
  await writeFile(resultPath, JSON.stringify({ ...summary, records }, null, 2), 'utf8');
  await writeFile(matrixPath, JSON.stringify(summary.scenarios, null, 2), 'utf8');
  return { resultPath, matrixPath };
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  const scenarios = buildScenarios(SCENARIO_TARGET);
  const coverage = summarizeCoverage(scenarios);
  const coverageCheck = validateCoverage(coverage);
  const mathResults = scenarios.map((scenario) => ({ scenarioId: scenario.id, ...validateScenarioMath(scenario) }));
  const mathFailures = mathResults.filter((item) => item.status !== 'PASS');
  record('scenario-matrix', mathFailures.length === 0 ? 'PASS' : 'FAIL', {
    message: `${scenarios.length - mathFailures.length}/${scenarios.length} cost/quantity models passed`,
    failures: mathFailures.slice(0, 10),
  });
  record('scenario-coverage', coverageCheck.status, {
    message: coverageCheck.status === 'PASS'
      ? 'dimension and stress-case coverage satisfied'
      : 'dimension or stress-case coverage is insufficient',
    coverage,
    issues: coverageCheck.issues,
  });

  record('api-contract', 'PASS', {
    message: `${apiContract.length} endpoints mapped for production-plan to cost-analysis chain`,
    endpoints: apiContract,
  });

  const config = requiredLiveConfig();
  const api = await apiPreflight();
  record('api-preflight', api.reachable ? 'PASS' : 'BLOCKED', {
    message: api.reachable ? 'backend reachable' : 'backend API health unavailable',
    checks: api.checks,
  });

  let auth = { ok: false, token: null, reason: 'not attempted' };
  if (api.reachable && config.username && config.password) {
    auth = await login(config);
    record('api-login', auth.ok ? 'PASS' : 'BLOCKED', {
      message: auth.ok ? 'token captured' : auth.reason,
      statusCode: auth.status,
    });
  } else {
    record('api-login', 'BLOCKED', {
      message: api.reachable ? 'credentials missing' : 'backend unavailable',
    });
  }

  const fixtureResolution =
    auth.ok ? await discoverLiveFixtures(config, auth.token, auth) : { config, discovered: { enabled: DISCOVER_FIXTURES } };
  const liveConfig = fixtureResolution.config;
  record('api-fixture-discovery', auth.ok && liveFixtureBlockers(liveConfig).length === 0 ? 'PASS' : 'BLOCKED', {
    message: auth.ok ? 'live fixture resolution completed' : 'not attempted without auth token',
    discovered: fixtureResolution.discovered,
    productTypeIdCount: liveConfig.productTypeIds.length,
    customerIdCount: liveConfig.customerIds.length,
    supervisorConfigured: Boolean(liveConfig.supervisorId),
  });

  const liveBlockers = [
    ...liveConfig.missing,
    ...liveFixtureBlockers(liveConfig),
    !api.reachable ? 'backend API health unavailable' : null,
    !auth.ok ? 'authenticated API token unavailable' : null,
  ].filter(Boolean);

  const liveResults = [];
  if (liveBlockers.length === 0) {
    for (const [index, scenario] of scenarios.slice(0, LIVE_LIMIT).entries()) {
      const result = await runApiDeepScenario(scenario, liveConfig, auth.token, index);
      liveResults.push({ scenarioId: scenario.id, ...result });
      record('api-deep-scenario', result.status, { message: `${scenario.id} ${result.reason || 'deep chain passed'}`, evidence: result.evidence });
      if (result.status !== 'PASS' && STRICT) break;
    }
  } else {
    record('api-deep-scenarios', 'BLOCKED', {
      message: 'live mutation chain not attempted',
      blockers: liveBlockers,
    });
  }

  const ui = await runHeadedUiAudit();
  record('headed-ui-audit', ui.reachable ? 'PASS' : 'BLOCKED', {
    message: ui.reachable ? 'isolated headed browser loaded app shell' : 'frontend app unavailable',
    appUrl: APP_URL,
    profileDir: PROFILE_DIR,
    routeAuditTargets,
    evidence: ui.evidence,
  });

  const passedLive = liveResults.filter((item) => item.status === 'PASS').length;
  const failedLive = liveResults.filter((item) => item.status === 'FAIL').length;
  const blockedLive = liveResults.filter((item) => item.status === 'BLOCKED').length;
  const liveReady = liveBlockers.length === 0 && passedLive === LIVE_LIMIT;
  const coverageFailures = coverageCheck.status === 'PASS' ? [] : coverageCheck.issues;
  const deliveryStatus = mathFailures.length > 0 || coverageFailures.length > 0 || failedLive > 0
    ? 'failed'
    : liveReady ? 'test_complete' : 'blocked';
  const blockingReason = liveReady
    ? null
    : [
        ...liveBlockers,
        !ui.reachable ? 'frontend app URL unavailable' : null,
        blockedLive > 0 ? `${blockedLive} live scenario(s) blocked during execution` : null,
      ].filter(Boolean).join('; ');

  const summary = {
    schema: 'depth-first-e2e-live-chain-v1',
    campaign: 'production-plan-to-cost-analysis-mixed-sku-live-chain',
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
    apiContract,
    routeAuditTargets,
    specTotal: scenarios.length,
    effectiveTotal: liveReady ? LIVE_LIMIT : 0,
    actualExecuted: liveResults.length,
    actualPass: passedLive,
    actualFail: failedLive + mathFailures.length,
    actualBlocked: liveReady ? 0 : scenarios.length - passedLive - failedLive,
    depthBreakdown: {
      smoke: ui.reachable ? 1 : 0,
      medium: 0,
      deep: passedLive,
    },
    modelInvariantPass: scenarios.length - mathFailures.length,
    modelInvariantFail: mathFailures.length,
    coverage,
    coverageIssues: coverageFailures,
    actualBugsFound: failedLive + mathFailures.length + coverageFailures.length,
    sameCauseSweep: failedLive > 0 || coverageFailures.length > 0
      ? 'required after inspecting failure root cause'
      : 'not_applicable',
    deliveryStatus,
    blockingReason,
    liveConfig: {
      allowMutation: ALLOW_MUTATION,
      runLive: RUN_LIVE,
      discoverFixtures: DISCOVER_FIXTURES,
      liveLimit: LIVE_LIMIT,
      missing: liveBlockers,
      productTypeIdCount: liveConfig.productTypeIds.length,
      customerIdCount: liveConfig.customerIds.length,
      supervisorConfigured: Boolean(liveConfig.supervisorId),
      usernameConfigured: Boolean(liveConfig.username),
      passwordConfigured: Boolean(liveConfig.password),
      fixtureDiscovery: fixtureResolution.discovered,
    },
    apiPreflight: api.checks,
    auth: {
      attempted: api.reachable && Boolean(config.username && config.password),
      ok: auth.ok,
      tokenCaptured: Boolean(auth.token),
      reason: auth.reason || null,
    },
    liveResults,
    uiEvidence: ui.evidence,
    consoleMessages,
    pageErrors,
    screenshots,
    scenarios,
    mathResults,
  };

  const paths = await writeResult(summary);
  console.log(JSON.stringify({ deliveryStatus, blockingReason, resultPath: paths.resultPath, matrixPath: paths.matrixPath }, null, 2));
  if (STRICT && deliveryStatus !== 'test_complete') process.exitCode = 2;
  if (summary.actualFail > 0) process.exitCode = 1;
}

main().catch(async (error) => {
  record('runner', 'FAIL', { message: error.stack || error.message });
  await writeResult({
    schema: 'depth-first-e2e-live-chain-v1',
    campaign: 'production-plan-to-cost-analysis-mixed-sku-live-chain',
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
  });
  process.exitCode = 1;
});
