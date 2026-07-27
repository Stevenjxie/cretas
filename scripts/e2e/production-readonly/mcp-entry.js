async (page) => {
  const __modules = {
    "config/readonly-post-whitelist.js": function(module, exports, require) {
'use strict';

const AUTH_REQUESTS = [
  {
    id: 'ui-login',
    method: 'POST',
    path: /^\/api\/mobile\/auth\/unified-login\/?$/,
  },
];

// Every entry must point to a server contract that is query-only. Do not add
// generic prefixes. AI chat is deliberately absent because some modes may
// execute tools with side effects behind a single HTTP request.
const READONLY_POST_WHITELIST = [
  {
    id: 'purchase-order-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/purchaseOrder\/?$/,
    rationale: 'Query-only list summary for purchase order rows.',
  },
  {
    id: 'sales-order-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/salesOrder\/?$/,
    rationale: 'Query-only list summary for sales order rows.',
  },
  {
    id: 'production-plan-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/productionPlan\/?$/,
    rationale: 'Query-only list summary for production plan rows.',
  },
  {
    id: 'workflow-resolve-by-outputs',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/product-process-workflows\/resolve-by-outputs\/?$/,
    rationale: 'Read-only workflow resolution; backend service is @Transactional(readOnly = true).',
  },
  {
    id: 'attachment-chip-counts',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/attachments\/batch-3chip-counts\/?$/,
    rationale: 'Batch query for attachment counts; no attachment mutation.',
  },
];

function matchesEntry(entry, method, url) {
  const parsed = new URL(url);
  return entry.method === method.toUpperCase() && entry.path.test(parsed.pathname);
}

function findAuthRequest(method, url) {
  return AUTH_REQUESTS.find((entry) => matchesEntry(entry, method, url)) || null;
}

function findReadonlyPost(method, url, entries = READONLY_POST_WHITELIST) {
  return entries.find((entry) => matchesEntry(entry, method, url)) || null;
}

module.exports = {
  AUTH_REQUESTS,
  READONLY_POST_WHITELIST,
  matchesEntry,
  findAuthRequest,
  findReadonlyPost,
};

},
    "config/routes.js": function(module, exports, require) {
'use strict';

const ROUTES = Object.freeze({
  login: '/login',
  dashboard: '/dashboard',
  bom: '/production/bom',
  purchasing: '/procurement/orders',
  finance: '/finance/ar-ap',
  suppliers: '/procurement/suppliers',
  products: '/system/products',
  workflow: '/system/product-processes',
  productionPlans: '/production/plans',
  labelQc: '/quality/label-qc',
});

module.exports = { ROUTES };

},
    "core/clean-session.js": function(module, exports, require) {
'use strict';

async function establishCleanSession(page, baseUrl) {
  const context = page.context();
  await context.clearCookies();
  await context.clearPermissions().catch(() => {});
  for (const worker of context.serviceWorkers()) await worker.close().catch(() => {});
  await page.goto('about:blank');
  const loginUrl = new URL('/login', baseUrl).toString();
  await page.goto(loginUrl, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 45_000 });
  return { loginUrl, cookiesAfterClear: (await context.cookies()).length };
}

module.exports = { establishCleanSession };

},
    "core/console-recorder.js": function(module, exports, require) {
'use strict';

const { redactText } = require('./sanitizer');

const DEFAULT_NOISE_RULES = [
  { id: 'favicon', pattern: /favicon\.ico/i },
  { id: 'resize-observer', pattern: /ResizeObserver loop/i },
  { id: 'browser-extension', pattern: /chrome-extension:\/\//i },
  { id: 'devtools-prompt', pattern: /(?:Vue|React) Devtools/i },
];

function installConsoleRecorder(context, options = {}) {
  const noiseRules = options.noiseRules || DEFAULT_NOISE_RULES;
  const raw = [];
  const accepted = [];
  const listeners = new Map();

  function record(type, text, location = '') {
    const safeText = redactText(text, 500);
    const matched = noiseRules.find((rule) => rule.pattern.test(safeText));
    const item = {
      type,
      text: safeText,
      location: redactText(location, 300),
      at: new Date().toISOString(),
      filtered: Boolean(matched),
      filterRule: matched?.id || null,
    };
    raw.push(item);
    if (!matched) accepted.push(item);
  }

  function attach(page) {
    if (listeners.has(page)) return;
    const onConsole = (message) => {
      if (!['error', 'warning'].includes(message.type())) return;
      record(message.type(), message.text(), message.location()?.url || '');
    };
    const onPageError = (error) => record('pageerror', error?.message || String(error));
    page.on('console', onConsole);
    page.on('pageerror', onPageError);
    listeners.set(page, { onConsole, onPageError });
  }

  // Playwright surfaces most unhandled rejections as pageerror. This bridge
  // gives them a stable label without storing the rejected object.
  context.addInitScript(() => {
    window.addEventListener('unhandledrejection', (event) => {
      const reason = event.reason instanceof Error ? event.reason.message : String(event.reason || 'unknown');
      console.error(`[unhandledrejection] ${reason}`);
    });
  });
  for (const page of context.pages()) attach(page);
  context.on('page', attach);

  return {
    snapshot() {
      return {
        rawCount: raw.length,
        filteredCount: raw.length - accepted.length,
        retainedCount: accepted.length,
        consoleErrors: accepted.filter((item) => item.type === 'error'),
        consoleWarnings: accepted.filter((item) => item.type === 'warning'),
        pageErrors: accepted.filter((item) => item.type === 'pageerror'),
        filteredNoise: raw.filter((item) => item.filtered),
      };
    },
    dispose() {
      context.off('page', attach);
      for (const [page, handlers] of listeners) {
        page.off('console', handlers.onConsole);
        page.off('pageerror', handlers.onPageError);
      }
      listeners.clear();
    },
  };
}

module.exports = { DEFAULT_NOISE_RULES, installConsoleRecorder };

},
    "core/evidence-writer.js": function(module, exports, require) {
'use strict';

const { sanitizeValue } = require('./sanitizer');

const SAFE_EVIDENCE_SEGMENTS = ['.playwright-mcp', 'test-results', 'test-output', 'tmp'];

function normalizePath(value) {
  return String(value || '').replace(/\\/g, '/').replace(/\/+$/, '');
}

function isSafeEvidenceDirectory(directory) {
  const normalized = normalizePath(directory).toLowerCase();
  return SAFE_EVIDENCE_SEGMENTS.some((segment) => normalized.split('/').includes(segment.toLowerCase()));
}

function joinPath(directory, filename) {
  return `${normalizePath(directory)}/${filename}`;
}

function markdownReport(report) {
  const lines = [
    '# Production read-only Playwright report',
    '',
    `- Run: ${report.runId}`,
    `- Base URL: ${report.baseUrl}`,
    `- Scenarios: ${report.scenarios.length}`,
    `- Auth requests: ${report.authRequests.length}`,
    `- Read-only POST requests: ${report.readonlyPostRequests.length}`,
    `- Blocked mutation attempts: ${report.blockedMutationAttempts.length}`,
    `- Actual business writes: ${report.actualBusinessWrites}`,
    `- Console retained/raw: ${report.console.retainedCount}/${report.console.rawCount}`,
    `- HTTP errors: ${report.network.httpErrors.length}`,
    '',
    '| Scenario | Result | Root cause | Writes | Duration |',
    '|---|---|---|---:|---:|',
  ];
  for (const result of report.scenarios) {
    lines.push(`| ${result.scenario} | ${result.result} | ${result.rootCauseClass} | ${result.actualBusinessWrites} | ${result.durationMs} ms |`);
  }
  lines.push('', '## Priorities', '');
  for (const priority of ['P0', 'P1', 'P2']) {
    const items = report.priorities?.[priority] || [];
    lines.push(`### ${priority}`, '', ...(items.length ? items.map((item) => `- ${item}`) : ['- None']), '');
  }
  lines.push('## Screenshots', '');
  const screenshots = report.scenarios.flatMap((result) => result.screenshots || []);
  lines.push(...(screenshots.length ? screenshots.map((item) => `- ${item}`) : ['- None']), '');
  return `${lines.join('\n')}\n`;
}

async function saveTextWithDownload(page, content, target, mimeType) {
  const downloadPromise = page.waitForEvent('download', { timeout: 15_000 });
  await page.evaluate(({ text, filename, type }) => {
    const blob = new Blob([text], { type });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }, { text: content, filename: target.split(/[\\/]/).pop(), type: mimeType });
  const download = await downloadPromise;
  await download.saveAs(target);
}

async function writeEvidence(report, evidenceDir, options = {}) {
  const directory = normalizePath(evidenceDir);
  if (options.productionReadonly !== false && !isSafeEvidenceDirectory(directory)) {
    throw new Error(`Evidence directory must be gitignored (.playwright-mcp/test-results/test-output/tmp): ${directory}`);
  }
  if (!options.page) throw new Error('A Playwright page is required to write browser-safe evidence');
  const safeReport = sanitizeValue(report);
  const jsonPath = joinPath(directory, 'report.json');
  const markdownPath = joinPath(directory, 'report.md');
  await saveTextWithDownload(options.page, `${JSON.stringify(safeReport, null, 2)}\n`, jsonPath, 'application/json');
  await saveTextWithDownload(options.page, markdownReport(safeReport), markdownPath, 'text/markdown');
  return { evidenceDir: directory, jsonPath, markdownPath };
}

module.exports = { SAFE_EVIDENCE_SEGMENTS, isSafeEvidenceDirectory, markdownReport, writeEvidence };

},
    "core/mutation-guard.js": function(module, exports, require) {
'use strict';

const { findAuthRequest, findReadonlyPost, READONLY_POST_WHITELIST } = require('../config/readonly-post-whitelist');
const { sanitizeUrl } = require('./sanitizer');

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function classifyMutation(method, url, whitelist = READONLY_POST_WHITELIST) {
  const upper = String(method || '').toUpperCase();
  if (!MUTATING_METHODS.has(upper)) return { kind: 'safe-method' };
  const auth = findAuthRequest(upper, url);
  if (auth) return { kind: 'auth', entry: auth };
  const readonly = findReadonlyPost(upper, url, whitelist);
  if (readonly) return { kind: 'readonly-post', entry: readonly };
  return { kind: 'business-mutation' };
}

async function installMutationGuard(context, options = {}) {
  const scenarioRef = options.scenarioRef || { value: 'bootstrap' };
  const whitelist = options.readonlyPostWhitelist || READONLY_POST_WHITELIST;
  const authRequests = [];
  const readonlyPostRequests = [];
  const blockedMutationAttempts = [];
  const actualBusinessWriteRequests = [];
  const allowedByRequest = new WeakMap();

  const handler = async (route) => {
    const request = route.request();
    const method = request.method().toUpperCase();
    const classification = classifyMutation(method, request.url(), whitelist);
    if (classification.kind === 'safe-method') {
      await route.continue();
      return;
    }

    const record = {
      method,
      url: sanitizeUrl(request.url()),
      scenario: scenarioRef.value || 'unknown',
      at: new Date().toISOString(),
    };
    if (classification.kind === 'auth') {
      authRequests.push({ ...record, classificationId: classification.entry.id });
      allowedByRequest.set(request, classification.kind);
      await route.continue();
      return;
    }
    if (classification.kind === 'readonly-post') {
      readonlyPostRequests.push({ ...record, classificationId: classification.entry.id });
      allowedByRequest.set(request, classification.kind);
      await route.continue();
      return;
    }

    blockedMutationAttempts.push({ ...record, safetyStatus: 'FAIL', blockedBeforeSend: true });
    await route.abort('blockedbyclient');
  };

  const responseListener = (response) => {
    const request = response.request();
    const method = request.method().toUpperCase();
    if (!MUTATING_METHODS.has(method)) return;
    const classification = classifyMutation(method, request.url(), whitelist);
    if (classification.kind === 'business-mutation' && !allowedByRequest.has(request)) {
      actualBusinessWriteRequests.push({
        method,
        url: sanitizeUrl(request.url()),
        status: response.status(),
        scenario: scenarioRef.value || 'unknown',
        at: new Date().toISOString(),
      });
    }
  };

  await context.route('**/*', handler);
  context.on('response', responseListener);

  return {
    authRequests,
    readonlyPostRequests,
    blockedMutationAttempts,
    actualBusinessWriteRequests,
    get actualBusinessWrites() {
      return actualBusinessWriteRequests.length;
    },
    snapshot() {
      return {
        authRequests: authRequests.slice(),
        readonlyPostRequests: readonlyPostRequests.slice(),
        blockedMutationAttempts: blockedMutationAttempts.slice(),
        actualBusinessWriteRequests: actualBusinessWriteRequests.slice(),
        actualBusinessWrites: actualBusinessWriteRequests.length,
      };
    },
    async dispose() {
      context.off('response', responseListener);
      await context.unroute('**/*', handler);
    },
  };
}

module.exports = { MUTATING_METHODS, classifyMutation, installMutationGuard };

},
    "core/network-recorder.js": function(module, exports, require) {
'use strict';

const { parsePostData, redactText, sanitizeUrl, summarizePayload } = require('./sanitizer');

function normalizeDuplicateKey(method, url) {
  try {
    const parsed = new URL(url);
    for (const key of [...parsed.searchParams.keys()]) parsed.searchParams.set(key, '*');
    return `${method} ${parsed.origin}${parsed.pathname}${parsed.search}`;
  } catch {
    return `${method} ${url}`;
  }
}

function pickResponseSummary(body, extractors, url) {
  const summary = {
    success: typeof body?.success === 'boolean' ? body.success : null,
    message: body?.message == null ? null : redactText(body.message, 160),
    keyFields: {},
  };
  for (const extractor of extractors || []) {
    if (!extractor.match(url)) continue;
    Object.assign(summary.keyFields, extractor.pick(body) || {});
  }
  return summary;
}

function installNetworkRecorder(context, options = {}) {
  const records = [];
  const httpErrors = [];
  const failedRequests = [];
  const started = new WeakMap();
  const pending = new Set();
  const extractors = options.responseExtractors || [];

  const onRequest = (request) => {
    if (!request.url().includes('/api/mobile/')) return;
    started.set(request, {
      at: Date.now(),
      payload: summarizePayload(parsePostData(request)),
    });
  };

  const onResponse = (response) => {
    const request = response.request();
    if (!request.url().includes('/api/mobile/')) return;
    const task = (async () => {
      const start = started.get(request);
      let body = null;
      try {
        const contentType = response.headers()['content-type'] || '';
        if (/json/i.test(contentType)) body = await response.json();
      } catch {
        body = null;
      }
      const record = {
        method: request.method(),
        url: sanitizeUrl(request.url()),
        status: response.status(),
        durationMs: start ? Math.max(0, Date.now() - start.at) : null,
        requestPayload: start?.payload || null,
        response: pickResponseSummary(body, extractors, request.url()),
        at: new Date(start?.at || Date.now()).toISOString(),
      };
      records.push(record);
      if (response.status() >= 400) httpErrors.push(record);
    })();
    pending.add(task);
    task.finally(() => pending.delete(task));
  };

  const onRequestFailed = (request) => {
    if (!request.url().includes('/api/mobile/')) return;
    const errorText = request.failure()?.errorText || 'request failed';
    // Requests aborted by the mutation guard are represented in the guard's
    // blockedMutationAttempts collection, not duplicated as HTTP failures.
    if (/ERR_BLOCKED_BY_CLIENT|blockedbyclient/i.test(errorText)) return;
    failedRequests.push({
      method: request.method(),
      url: sanitizeUrl(request.url()),
      error: redactText(errorText, 160),
      at: new Date().toISOString(),
    });
  };

  context.on('request', onRequest);
  context.on('response', onResponse);
  context.on('requestfailed', onRequestFailed);

  return {
    records,
    httpErrors,
    failedRequests,
    async flush() {
      await Promise.allSettled([...pending]);
    },
    snapshot() {
      const duplicates = new Map();
      for (const record of records) {
        const key = normalizeDuplicateKey(record.method, record.url);
        duplicates.set(key, (duplicates.get(key) || 0) + 1);
      }
      return {
        apiEvidence: records.slice(),
        httpErrors: httpErrors.slice(),
        failedRequests: failedRequests.slice(),
        duplicateRequests: [...duplicates.entries()]
          .filter(([, count]) => count > 1)
          .map(([request, count]) => ({ request, count }))
          .sort((a, b) => b.count - a.count),
      };
    },
    dispose() {
      context.off('request', onRequest);
      context.off('response', onResponse);
      context.off('requestfailed', onRequestFailed);
    },
  };
}

module.exports = { installNetworkRecorder, normalizeDuplicateKey, pickResponseSummary };

},
    "core/result-schema.js": function(module, exports, require) {
'use strict';

const RESULTS = new Set(['PASS', 'CONFIRMED_DEFECT', 'PARTIAL_DEFECT', 'UNVERIFIED', 'TOOL_ERROR']);
const ROOT_CAUSES = new Set(['frontend', 'backend', 'data', 'config', 'tool', 'none']);

function createScenarioResult(scenario, url = '') {
  return {
    scenario,
    result: 'UNVERIFIED',
    url,
    pageEvidence: [],
    apiEvidence: [],
    consoleErrors: [],
    consoleWarnings: [],
    httpErrors: [],
    blockedMutationAttempts: [],
    actualBusinessWrites: 0,
    rootCauseClass: 'none',
    screenshots: [],
    durationMs: 0,
  };
}

function validateScenarioResult(value) {
  const errors = [];
  if (!value || typeof value !== 'object') return ['result must be an object'];
  if (!value.scenario || typeof value.scenario !== 'string') errors.push('scenario must be a non-empty string');
  if (!RESULTS.has(value.result)) errors.push(`result must be one of ${[...RESULTS].join(', ')}`);
  if (typeof value.url !== 'string') errors.push('url must be a string');
  for (const key of ['pageEvidence', 'apiEvidence', 'consoleErrors', 'consoleWarnings', 'httpErrors', 'blockedMutationAttempts', 'screenshots']) {
    if (!Array.isArray(value[key])) errors.push(`${key} must be an array`);
  }
  if (!Number.isInteger(value.actualBusinessWrites) || value.actualBusinessWrites < 0) {
    errors.push('actualBusinessWrites must be a non-negative integer');
  }
  if (!ROOT_CAUSES.has(value.rootCauseClass)) errors.push(`rootCauseClass must be one of ${[...ROOT_CAUSES].join(', ')}`);
  if (!Number.isFinite(value.durationMs) || value.durationMs < 0) errors.push('durationMs must be a non-negative number');
  return errors;
}

function assertScenarioResult(value) {
  const errors = validateScenarioResult(value);
  if (errors.length) throw new Error(`Invalid scenario result: ${errors.join('; ')}`);
  return value;
}

module.exports = { RESULTS, ROOT_CAUSES, createScenarioResult, validateScenarioResult, assertScenarioResult };

},
    "core/run-suite.js": function(module, exports, require) {
'use strict';

const { establishCleanSession } = require('./clean-session');
const { performUiLogin } = require('./ui-login');
const { installMutationGuard } = require('./mutation-guard');
const { installNetworkRecorder } = require('./network-recorder');
const { installConsoleRecorder } = require('./console-recorder');
const { createScreenshotWriter } = require('./screenshot');
const { writeEvidence } = require('./evidence-writer');
const { createScenarioResult, assertScenarioResult } = require('./result-schema');
const { sanitizeValue } = require('./sanitizer');

const SCENARIOS = [
  require('../scenarios/tenant-isolation'),
  require('../scenarios/bom-readonly'),
  require('../scenarios/purchasing-readonly'),
  require('../scenarios/finance-readonly'),
  require('../scenarios/supplier-readonly'),
  require('../scenarios/ai-readonly'),
  require('../scenarios/workflow-readonly'),
  require('../scenarios/production-plan-routing-readonly'),
  require('../scenarios/label-qc-readonly'),
  require('../scenarios/ui-stability'),
];

function describeHarness() {
  return {
    name: 'cretas-production-readonly',
    scenarios: SCENARIOS.map((scenario) => scenario.id),
    safety: {
      requestInterception: 'before-send',
      guardedMethods: ['POST', 'PUT', 'PATCH', 'DELETE'],
      defaultLogin: 'UI',
      actualBusinessWritesRequired: 0,
    },
  };
}

function resolveScenarios(requested) {
  if (!requested || requested.length === 0) return SCENARIOS;
  const ids = Array.isArray(requested) ? requested : String(requested).split(',');
  return ids.map((id) => {
    const scenario = SCENARIOS.find((candidate) => candidate.id === id.trim());
    if (!scenario) throw new Error(`Unknown scenario: ${id}. Available: ${SCENARIOS.map((item) => item.id).join(', ')}`);
    return scenario;
  });
}

async function runSuiteWithPage(page, options = {}) {
  const context = page.context();
  const baseUrl = String(options.baseUrl || 'https://admin.cretaceousfuture.com').replace(/\/$/, '');
  const expectedUsername = options.expectedUsername || 'f006_admin';
  const expectedFactoryId = options.expectedFactoryId || 'F006';
  const runId = options.runId || new Date().toISOString().replace(/[:.]/g, '-');
  const evidenceDir = options.evidenceDir || `.playwright-mcp/production-readonly/${runId}`;
  const scenarioRef = { value: 'bootstrap' };

  // The guard is installed before any target-origin navigation or login.
  const mutationGuard = await installMutationGuard(context, { scenarioRef });
  const network = installNetworkRecorder(context, options.network || {});
  const consoleRecorder = installConsoleRecorder(context, options.console || {});
  const screenshot = createScreenshotWriter(page, evidenceDir, { sensitiveTexts: [expectedUsername] });
  const selectedScenarios = resolveScenarios(options.scenarios);
  const startedAt = Date.now();
  const startedAtIso = new Date(startedAt).toISOString();
  let loginEvidence;
  const results = [];

  try {
    try {
      scenarioRef.value = 'clean-session';
      await establishCleanSession(page, baseUrl);
      scenarioRef.value = 'ui-login';
      loginEvidence = await performUiLogin(page, {
        username: options.username,
        password: options.password,
        expectedUsername,
        expectedFactoryId,
      });

      const scenarioContext = {
        page,
        context,
        baseUrl,
        expectedUsername,
        expectedFactoryId,
        loginEvidence,
        scenarioRef,
        mutationGuard,
        network,
        consoleRecorder,
        screenshot,
        evidenceDir,
      };
      for (const scenario of selectedScenarios) results.push(await scenario.run(scenarioContext));
    } catch (error) {
      const failure = createScenarioResult(scenarioRef.value || 'bootstrap', page.url());
      failure.result = 'TOOL_ERROR';
      failure.rootCauseClass = 'tool';
      const visibleText = String(await page.locator('body').innerText().catch(() => ''));
      failure.pageEvidence.push({
        error: String(error?.message || error),
        bodyTextLength: visibleText.length,
        finalPath: (() => { try { return new URL(page.url()).pathname; } catch { return ''; } })(),
      });
      try { failure.screenshots.push(await screenshot(`${failure.scenario}-failure`)); } catch {}
      failure.durationMs = Date.now() - startedAt;
      results.push(assertScenarioResult(failure));
    }
    await network.flush();

    const guardReport = mutationGuard.snapshot();
    const networkReport = network.snapshot();
    const consoleReport = consoleRecorder.snapshot();
    const report = {
      runId,
      baseUrl,
      productionReadonly: true,
      startedAt: startedAtIso,
      durationMs: Date.now() - startedAt,
      tenant: {
        username: loginEvidence?.username || null,
        factoryId: loginEvidence?.factoryId || null,
        factoryName: loginEvidence?.factoryName || null,
      },
      scenarios: results,
      authRequests: guardReport.authRequests,
      readonlyPostRequests: guardReport.readonlyPostRequests,
      blockedMutationAttempts: guardReport.blockedMutationAttempts,
      actualBusinessWriteRequests: guardReport.actualBusinessWriteRequests,
      actualBusinessWrites: guardReport.actualBusinessWrites,
      network: networkReport,
      console: consoleReport,
      priorities: options.priorities || { P0: [], P1: [], P2: [] },
      safetyPassed: guardReport.actualBusinessWrites === 0 && guardReport.blockedMutationAttempts.length === 0,
    };
    const safeReport = sanitizeValue(report);
    const files = await writeEvidence(safeReport, evidenceDir, { productionReadonly: true, page });
    return { ...safeReport, evidence: files };
  } finally {
    scenarioRef.value = 'teardown';
    network.dispose();
    consoleRecorder.dispose();
    await mutationGuard.dispose();
  }
}

module.exports = { SCENARIOS, describeHarness, resolveScenarios, runSuiteWithPage };

},
    "core/sanitizer.js": function(module, exports, require) {
'use strict';

const SENSITIVE_KEY_RE = /authorization|cookie|password|passwd|secret|token|credential|session|jwt|set-cookie|(?:^|[_-])username(?:$|[_-])/i;
const SAFE_STRING_KEYS = new Set(['factoryId', 'mode', 'moduleCode', 'action', 'status', 'code', 'category', 'type']);

function redactText(value, maxLength = 240) {
  let text = String(value ?? '');
  text = text
    .replace(/Bearer\s+[A-Za-z0-9._~+\/-]+=*/gi, 'Bearer [REDACTED]')
    .replace(/([?&](?:token|access_token|refresh_token|password|secret|authorization)=)[^&#\s]*/gi, '$1[REDACTED]')
    .replace(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g, '[REDACTED_EMAIL]')
    .replace(/(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)/g, '[REDACTED_PHONE]')
    .replace(/\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]{8,})?\b/g, '[REDACTED_JWT]');
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

function sanitizeUrl(input) {
  try {
    const url = new URL(String(input));
    if (url.username) url.username = '[REDACTED]';
    if (url.password) url.password = '[REDACTED]';
    for (const key of [...url.searchParams.keys()]) {
      if (SENSITIVE_KEY_RE.test(key)) url.searchParams.set(key, '[REDACTED]');
    }
    return redactText(url.toString(), 500);
  } catch {
    return redactText(input, 500);
  }
}

function summarizeScalar(key, value) {
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (SENSITIVE_KEY_RE.test(key)) return '[REDACTED]';
  if (typeof value === 'string') {
    if (SAFE_STRING_KEYS.has(key)) return redactText(value, 80);
    return `<string:${value.length}>`;
  }
  return `<${typeof value}>`;
}

function summarizePayload(value, depth = 0) {
  if (value == null) return null;
  if (depth > 3) return '[DEPTH_LIMIT]';
  if (Array.isArray(value)) {
    return {
      type: 'array',
      length: value.length,
      sample: value.slice(0, 3).map((item) => summarizePayload(item, depth + 1)),
    };
  }
  if (typeof value !== 'object') return summarizeScalar('', value);
  const keys = Object.keys(value).slice(0, 40);
  const fields = {};
  for (const key of keys) {
    const child = value[key];
    if (SENSITIVE_KEY_RE.test(key)) fields[key] = '[REDACTED]';
    else if (child && typeof child === 'object') fields[key] = summarizePayload(child, depth + 1);
    else fields[key] = summarizeScalar(key, child);
  }
  return { type: 'object', keys, fields };
}

function sanitizeValue(value, key = '', depth = 0) {
  if (SENSITIVE_KEY_RE.test(key)) return '[REDACTED]';
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (typeof value === 'string') return redactText(value);
  if (depth > 5) return '[DEPTH_LIMIT]';
  if (Array.isArray(value)) return value.slice(0, 200).map((item) => sanitizeValue(item, '', depth + 1));
  if (typeof value === 'object') {
    const result = {};
    for (const [childKey, childValue] of Object.entries(value)) {
      result[childKey] = sanitizeValue(childValue, childKey, depth + 1);
    }
    return result;
  }
  return redactText(value);
}

function parsePostData(request) {
  try {
    return request.postDataJSON();
  } catch {
    const raw = request.postData();
    return raw ? { rawLength: raw.length } : null;
  }
}

module.exports = {
  SENSITIVE_KEY_RE,
  redactText,
  sanitizeUrl,
  sanitizeValue,
  summarizePayload,
  parsePostData,
};

},
    "core/screenshot.js": function(module, exports, require) {
'use strict';

function slug(value) {
  return String(value || 'evidence').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80) || 'evidence';
}

function createScreenshotWriter(page, evidenceDir, config = {}) {
  const root = String(evidenceDir || '').replace(/\\/g, '/').replace(/\/+$/, '');
  if (!root) throw new Error('Evidence directory is required for screenshots');
  let counter = 0;
  return async function screenshot(name, options = {}) {
    const filename = `${String(++counter).padStart(2, '0')}-${slug(name)}.png`;
    const target = `${root}/${filename}`;
    const mask = [
      page.locator('input[type="password"], input[placeholder*="用户名"]'),
      ...(config.sensitiveTexts || []).map((text) => page.getByText(text, { exact: true })),
    ];
    await page.screenshot({
      path: target,
      fullPage: Boolean(options.fullPage),
      mask,
      maskColor: '#1f2937',
    });
    return target;
  };
}

module.exports = { createScreenshotWriter, slug };

},
    "core/ui-login.js": function(module, exports, require) {
'use strict';

const { redactText, sanitizeUrl } = require('./sanitizer');

function compactLoginData(body) {
  const data = body?.data || {};
  const factoryUser = data.factoryUser || {};
  return {
    success: typeof body?.success === 'boolean' ? body.success : null,
    message: redactText(body?.message || '', 120),
    username: data.username || factoryUser.username || null,
    factoryId: data.factoryId || factoryUser.factoryId || null,
    factoryName: data.factoryName || factoryUser.factoryName || null,
    role: data.role || factoryUser.role || null,
  };
}

function waitForLoginOutcome(page, matchesLogin, timeoutMs = 20_000) {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer);
      page.off('response', onResponse);
      page.off('requestfailed', onFailed);
    };
    const onResponse = (response) => {
      if (!matchesLogin(response.request())) return;
      cleanup();
      resolve({ type: 'response', response });
    };
    const onFailed = (request) => {
      if (!matchesLogin(request)) return;
      cleanup();
      resolve({ type: 'failure', request });
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`UI login request was not observed within ${timeoutMs}ms`));
    }, timeoutMs);
    page.on('response', onResponse);
    page.on('requestfailed', onFailed);
  });
}

async function performUiLogin(page, options) {
  const username = options.username;
  const password = options.password;
  const expectedUsername = options.expectedUsername || 'f006_admin';
  const expectedFactoryId = options.expectedFactoryId || 'F006';
  if (!username || !password) throw new Error('E2E_USERNAME and E2E_PASSWORD are required for UI login');

  const usernameInput = page.locator('input[placeholder*="用户名"], input[type="text"]').first();
  const passwordInput = page.locator('input[placeholder*="密码"], input[type="password"]').first();
  await usernameInput.waitFor({ state: 'visible', timeout: 15_000 });
  await usernameInput.fill(username);
  await passwordInput.fill(password);

  const matchesLogin = (request) => request.url().includes('/api/mobile/auth/unified-login')
    && request.method() === 'POST';
  const outcomePromise = waitForLoginOutcome(page, matchesLogin);
  const submit = page.locator('button.login-button, button[type="submit"]').first();
  await submit.click();
  const outcome = await outcomePromise;
  if (outcome.type === 'failure') {
    throw new Error(`UI login request failed before response: ${outcome.request.failure()?.errorText || 'unknown network error'}`);
  }
  const response = outcome.response;
  let body = null;
  try { body = await response.json(); } catch { body = null; }
  const login = compactLoginData(body);
  if (response.status() >= 400 || login.success === false) {
    throw new Error(`UI login failed: HTTP ${response.status()} ${login.message || 'success=false'}`);
  }
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });

  const stored = await page.evaluate(() => {
    try {
      const value = JSON.parse(localStorage.getItem('cretas_user') || '{}');
      const factoryUser = value.factoryUser || {};
      return {
        username: value.username || factoryUser.username || null,
        factoryId: value.factoryId || factoryUser.factoryId || null,
        factoryName: value.factoryName || factoryUser.factoryName || null,
        role: value.role || factoryUser.role || null,
      };
    } catch {
      return {};
    }
  });
  const pageText = await page.locator('body').innerText().catch(() => '');
  const actualUsername = stored.username || login.username;
  const actualFactoryId = stored.factoryId || login.factoryId;
  const actualFactoryName = stored.factoryName || login.factoryName;
  const businessSuccess = login.success ?? Boolean(actualUsername && actualFactoryId);
  const staleTenantDetected = /liushanmen_admin/i.test(pageText);
  if (actualUsername !== expectedUsername) throw new Error(`Tenant login username mismatch: expected ${expectedUsername}, got ${actualUsername}`);
  if (actualFactoryId !== expectedFactoryId) throw new Error(`Tenant factory mismatch: expected ${expectedFactoryId}, got ${actualFactoryId}`);
  if (staleTenantDetected) throw new Error('Stale tenant marker liushanmen_admin detected after clean UI login');
  if (actualFactoryName && !pageText.includes(actualFactoryName)) {
    throw new Error('Factory display name does not match the authenticated factory name');
  }

  return {
    method: 'POST',
    url: sanitizeUrl(response.url()),
    status: response.status(),
    success: businessSuccess,
    responseBodyParsed: body !== null,
    username: actualUsername,
    factoryId: actualFactoryId,
    factoryName: actualFactoryName,
    role: stored.role || login.role,
    staleTenantDetected,
  };
}

module.exports = { compactLoginData, performUiLogin, waitForLoginOutcome };

},
    "scenarios/_shared.js": function(module, exports, require) {
'use strict';

const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');

function arrayDelta(after, beforeLength) {
  return (after || []).slice(beforeLength);
}

async function runReadOnlyPageScenario(ctx, definition) {
  const startedAt = Date.now();
  const result = createScenarioResult(definition.id);
  ctx.scenarioRef.value = definition.id;
  const beforeNetwork = ctx.network.snapshot();
  const beforeConsole = ctx.consoleRecorder.snapshot();
  const beforeGuard = ctx.mutationGuard.snapshot();

  try {
    await ctx.page.goto(new URL(definition.path, ctx.baseUrl).toString(), {
      waitUntil: 'domcontentloaded',
      timeout: definition.timeoutMs || 45_000,
    });
    await ctx.page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
    result.url = ctx.page.url();
    const body = await ctx.page.locator('body').innerText().catch(() => '');
    const matched = (definition.landmarks || []).filter((landmark) => body.includes(landmark));
    result.pageEvidence.push({
      expectedLandmarks: definition.landmarks || [],
      matchedLandmarks: matched,
      bodyTextLength: body.trim().length,
      finalPath: new URL(result.url).pathname,
    });
    let assessment = null;
    if (definition.inspect) {
      const inspected = await definition.inspect(ctx.page, body, ctx);
      if (inspected) {
        assessment = inspected.assessment || null;
        const { assessment: _assessment, screenshots = [], ...evidence } = inspected;
        result.pageEvidence.push(evidence);
        result.screenshots.push(...screenshots);
      }
    }
    if (definition.screenshot) result.screenshots.push(await ctx.screenshot(definition.id));
    result.result = matched.length === (definition.landmarks || []).length && body.trim().length > 40
      ? 'PASS'
      : 'UNVERIFIED';
    result.rootCauseClass = result.result === 'PASS' ? 'none' : 'tool';
    if (result.result === 'PASS' && assessment) {
      result.result = assessment.result;
      result.rootCauseClass = assessment.rootCauseClass;
    }
  } catch (error) {
    result.result = 'TOOL_ERROR';
    result.rootCauseClass = 'tool';
    result.pageEvidence.push({ error: String(error?.message || error) });
  }

  await ctx.network.flush();
  const afterNetwork = ctx.network.snapshot();
  const afterConsole = ctx.consoleRecorder.snapshot();
  const afterGuard = ctx.mutationGuard.snapshot();
  result.apiEvidence = arrayDelta(afterNetwork.apiEvidence, beforeNetwork.apiEvidence.length);
  result.httpErrors = arrayDelta(afterNetwork.httpErrors, beforeNetwork.httpErrors.length);
  result.consoleErrors = arrayDelta(afterConsole.consoleErrors, beforeConsole.consoleErrors.length)
    .concat(arrayDelta(afterConsole.pageErrors, beforeConsole.pageErrors.length));
  result.consoleWarnings = arrayDelta(afterConsole.consoleWarnings, beforeConsole.consoleWarnings.length);
  result.blockedMutationAttempts = arrayDelta(afterGuard.blockedMutationAttempts, beforeGuard.blockedMutationAttempts.length);
  result.actualBusinessWrites = afterGuard.actualBusinessWrites - beforeGuard.actualBusinessWrites;
  if (result.blockedMutationAttempts.length || result.actualBusinessWrites > 0) {
    result.result = 'TOOL_ERROR';
    result.rootCauseClass = 'tool';
    result.pageEvidence.push({ safetyStatus: 'FAIL', reason: 'Unexpected mutation attempted during read-only scenario' });
  } else if (result.httpErrors.length || result.consoleErrors.length) {
    result.result = result.result === 'PASS' ? 'PARTIAL_DEFECT' : result.result;
    result.rootCauseClass = result.rootCauseClass === 'none' ? 'frontend' : result.rootCauseClass;
  }
  result.durationMs = Date.now() - startedAt;
  return assertScenarioResult(result);
}

module.exports = { runReadOnlyPageScenario };

},
    "scenarios/ai-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'ai-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'ai-readonly',
    path: ROUTES.products,
    landmarks: ['SKU'],
    inspect: async (page, body) => ({
      aiEntryVisible: /AI/.test(body),
      note: 'AI chat is not sent in production; /ai/chat is intentionally not whitelisted.',
      buttonCount: await page.getByRole('button').count(),
    }),
  }),
};

},
    "scenarios/bom-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

async function selectTargetProduct(page) {
  if (await page.getByText('BOM 配方版本', { exact: true }).isVisible().catch(() => false)) {
    return { selected: true, selectedLabel: null, failure: null };
  }

  const productSelect = page.locator('.bom-hero-card .el-select, .header-card .el-select').first();
  const productInput = productSelect.locator('input').first();
  if (!(await productSelect.isVisible().catch(() => false))
      || !(await productInput.isVisible().catch(() => false))) {
    return { selected: false, selectedLabel: null, failure: 'product selector missing' };
  }

  await productSelect.click();
  await productInput.fill('干式熟成鸡 400g');
  const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)');
  let targetOption = options.filter({ hasText: '干式熟成鸡 400g' }).first();
  await targetOption.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});

  if (!(await targetOption.isVisible().catch(() => false))) {
    await productInput.fill('干式熟成鸡');
    targetOption = options.filter({ hasText: /干式熟成鸡.*400g/ }).first();
    await targetOption.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});
  }
  if (!(await targetOption.isVisible().catch(() => false))) {
    await page.keyboard.press('Escape');
    if (await page.getByText('BOM 配方版本', { exact: true }).isVisible().catch(() => false)) {
      const selectedLabel = (await productInput.inputValue().catch(() => '')).trim() || null;
      return { selected: true, selectedLabel, selectionMode: 'auto-selected-fallback', failure: null };
    }
    return { selected: false, selectedLabel: null, selectionMode: null, failure: 'target product option missing' };
  }

  const selectedLabel = (await targetOption.innerText()).trim();
  await targetOption.click();
  await page.getByText('BOM 配方版本', { exact: true })
    .waitFor({ state: 'visible', timeout: 15_000 })
    .catch(() => {});
  return {
    selected: await page.getByText('BOM 配方版本', { exact: true }).isVisible().catch(() => false),
    selectedLabel,
    selectionMode: 'target-search',
    failure: null,
  };
}

module.exports = {
  id: 'bom-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-readonly',
    path: ROUTES.bom,
    landmarks: ['BOM'],
    screenshot: true,
    inspect: async (page, body, ctx) => {
      const productSelection = await selectTargetProduct(page);
      const versionHistoryButton = page.getByRole('button', { name: '版本历史', exact: true }).first();
      if (await versionHistoryButton.isVisible().catch(() => false)) {
        await versionHistoryButton.click();
        await page.getByText('BOM 配方版本', { exact: true })
          .waitFor({ state: 'visible', timeout: 10_000 })
          .catch(() => {});
      }
      const activeRow = page.locator('.recipe-status-card .el-table__row')
        .filter({ hasText: '已生效' })
        .first();
      const lifecycle = page.locator('[data-testid="bom-version-lifecycle"]');
      let activeViewOpened = await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
        .isVisible()
        .catch(() => false);
      if (!activeViewOpened && await activeRow.isVisible().catch(() => false)) {
        const viewActive = activeRow.getByRole('button', { name: '查看', exact: true }).first();
        if (await viewActive.isVisible().catch(() => false)) {
          await viewActive.click();
          await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
            .waitFor({ state: 'visible', timeout: 10_000 })
            .catch(() => {});
          activeViewOpened = await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
            .isVisible()
            .catch(() => false);
        }
      }
      const currentBody = await page.locator('body').innerText();
      const obsoleteControls = ['对话微调', 'Excel 导入', '一键重算出成率', 'kg/份']
        .filter((label) => currentBody.includes(label));
      const contract = {
        productSelection,
        hasVersionTable: currentBody.includes('BOM 配方版本'),
        hasHistoricalYield: currentBody.includes('系统历史出成率'),
        obsoleteControls,
        hasPricingUnit: /元\/(?:kg|g|袋|盒|箱)/.test(currentBody),
        hasSkuCostBasis: /元\/(?:袋|盒|箱|件|只|份)/.test(currentBody),
        tableCount: await page.locator('.el-table').count(),
        activeViewOpened,
        activeVersionVisible: /已生效/.test(currentBody),
        readOnlyGuidanceVisible: /当前生效.*内容已锁定|历史版本.*仅供查看/.test(currentBody),
        cloneActionVisible: false,
        addRawButtonVisible: false,
      };

      const rawTab = page.locator('.el-tabs__item').filter({ hasText: /^原料/ }).first();
      if (await rawTab.isVisible().catch(() => false)) await rawTab.click();
      const addRaw = page.getByRole('button', { name: /添加原料/ }).first();
      contract.addRawButtonVisible = await addRaw.isVisible().catch(() => false);
      contract.cloneActionVisible = await page.getByRole('button', {
        name: /克隆为新版本.*修改|前往 v\d+ 草稿.*修改|继续修改 v\d+ 草稿|新建版本/,
      }).first().isVisible().catch(() => false);
      const screenshots = [await ctx.screenshot('bom-active-readonly')];

      const failures = [];
      if (!productSelection.selected) failures.push(productSelection.failure || 'product selection failed');
      if (!contract.hasVersionTable) failures.push('BOM version table missing');
      if (!contract.hasHistoricalYield) failures.push('system historical yield missing');
      if (contract.obsoleteControls.length) failures.push('obsolete BOM controls visible');
      if (!contract.activeVersionVisible) failures.push('active BOM version missing');
      if (!contract.activeViewOpened) failures.push('active BOM view did not open');
      if (!contract.readOnlyGuidanceVisible) failures.push('active BOM read-only guidance missing');
      if (!contract.cloneActionVisible) failures.push('clone-to-new-version action missing');
      if (contract.addRawButtonVisible) failures.push('active BOM exposes raw material mutation action');
      return {
        ...contract,
        contractFailures: failures,
        screenshots,
        assessment: failures.length
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' }
          : { result: 'PASS', rootCauseClass: 'none' },
      };
    },
  }),
};

},
    "scenarios/finance-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'finance-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'finance-readonly',
    path: ROUTES.finance,
    landmarks: ['财务'],
    inspect: async (page, body) => ({ hasPaymentMethodColumn: /支付方式|付款方式/.test(body), tableCount: await page.locator('.el-table').count() }),
  }),
};

},
    "scenarios/label-qc-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'label-qc-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'label-qc-readonly',
    path: ROUTES.labelQc,
    landmarks: ['包装标签拍检', '待人工审核', '已审核整理', '归档记录'],
    screenshot: true,
    inspect: async (page) => ({
      reviewButtonCount: await page.getByRole('button', { name: '人工审核' }).count(),
      visibleTableCount: await page.locator('.el-table:visible').count(),
      note: 'Read-only page load only. Review, retry, archive, restore, backup, export, and training actions are intentionally not invoked.',
    }),
  }),
};

},
    "scenarios/production-plan-routing-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

const TOPOLOGY_KEYS = ['1_TO_1', '1_TO_MANY', 'MANY_TO_1', 'MANY_TO_MANY'];

function topologyKey(candidate) {
  const inputCount = new Set(candidate?.rootInputProductTypeIds || []).size;
  const outputCount = new Set((candidate?.terminalOutputs || []).map((item) => item.productTypeId).filter(Boolean)).size;
  if (candidate?.workflowType === 'RAW_MATERIAL_SPLIT') return '1_TO_MANY';
  if (candidate?.workflowType === 'JOINT_PRODUCTION') return 'MANY_TO_MANY';
  if (outputCount === 1 && inputCount <= 1) return '1_TO_1';
  // The resolver DTO exposes physical raw roots but not the EXACTLY_ONE logical
  // grouping needed to distinguish alternatives from simultaneous many-to-one.
  if (outputCount === 1 && inputCount > 1) return 'MULTI_RAW_TO_1_UNQUALIFIED';
  return 'UNQUALIFIED';
}

function chooseRepresentatives(inventory) {
  const byTopology = {};
  let ambiguous = null;
  let superset = null;
  for (const item of inventory) {
    if (!ambiguous && item.candidates.length > 1) ambiguous = item;
    for (const candidate of item.candidates) {
      if (!byTopology[candidate.topology]) byTopology[candidate.topology] = item;
      if (!superset && candidate.exactMatch === false) superset = item;
    }
  }
  return { byTopology, ambiguous, superset };
}

async function inventoryWorkflowRoutes(page, expectedFactoryId) {
  return page.evaluate(async ({ factoryId, topologyKeys }) => {
    const apiRoot = `/api/mobile/${encodeURIComponent(factoryId)}`;
    const readJson = async (url, init) => {
      const response = await fetch(url, { credentials: 'same-origin', ...init });
      const payload = await response.json().catch(() => null);
      return { ok: response.ok, status: response.status, payload };
    };
    const productResponse = await readJson(`${apiRoot}/product-types/active`);
    const rawRows = productResponse.payload?.data?.content || productResponse.payload?.data || [];
    const excluded = new Set(['RAW_MATERIAL', 'PACKAGING', 'SEASONING']);
    const products = (Array.isArray(rawRows) ? rawRows : [])
      .filter((row) => row?.id && !excluded.has(String(row.productCategory || '')))
      .map((row) => ({ id: String(row.id), name: String(row.name || row.id) }));
    const priority = /E2E|替代|熟成|联产|鸡|DEMO|测试/i;
    products.sort((left, right) => Number(priority.test(right.name)) - Number(priority.test(left.name)));

    const records = [];
    const coverage = new Set();
    let ambiguousFound = false;
    let supersetFound = false;
    let resolverCalls = 0;
    for (const product of products.slice(0, 200)) {
      const resolution = await readJson(`${apiRoot}/product-process-workflows/resolve-by-outputs`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ productTypeIds: [product.id] }),
      });
      resolverCalls += 1;
      const candidates = resolution.payload?.data?.candidates || [];
      if (!resolution.ok || !Array.isArray(candidates) || candidates.length === 0) continue;
      const normalized = candidates.map((candidate) => {
        const roots = [...new Set(candidate.rootInputProductTypeIds || [])];
        const outputs = (candidate.terminalOutputs || []).map((output) => ({
          id: String(output.productTypeId || ''),
          name: String(output.productName || output.productTypeId || ''),
        })).filter((output) => output.id);
        const workflowType = String(candidate.workflowType || '');
        const topology = workflowType === 'RAW_MATERIAL_SPLIT'
          ? '1_TO_MANY'
          : workflowType === 'JOINT_PRODUCTION'
            ? 'MANY_TO_MANY'
            : outputs.length === 1 && roots.length <= 1
              ? '1_TO_1'
              : outputs.length === 1 && roots.length > 1
                ? 'MULTI_RAW_TO_1_UNQUALIFIED'
                : 'UNQUALIFIED';
        coverage.add(topology);
        if (candidate.exactMatch === false) supersetFound = true;
        return {
          workflowId: candidate.workflowId,
          definitionVersion: candidate.definitionVersion,
          workflowType,
          topology,
          exactMatch: candidate.exactMatch === true,
          rootInputCount: roots.length,
          terminalOutputs: outputs,
          processSteps: (candidate.processSteps || []).map(String),
          previewNodeCount: (candidate.previewNodes || []).length,
          previewEdgeCount: (candidate.previewEdges || []).length,
        };
      });
      ambiguousFound ||= normalized.length > 1;
      records.push({ selection: [product], candidates: normalized });
      if (topologyKeys.every((key) => coverage.has(key)) && ambiguousFound && supersetFound) break;
    }
    return {
      activeFinishedGoodCount: products.length,
      resolverCalls,
      records,
      coverage: [...coverage],
      ambiguousFound,
      supersetFound,
    };
  }, { factoryId: expectedFactoryId, topologyKeys: TOPOLOGY_KEYS });
}

async function selectProducts(page, planDialog, names) {
  const productField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
  const wrapper = productField.locator('.el-select__wrapper');
  const input = productField.locator('input.el-select__input');
  for (let index = 0; index < names.length; index += 1) {
    await wrapper.click();
    await input.waitFor({ state: 'attached', timeout: 8_000 });
    await input.fill(names[index]);
    const option = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: names[index] })
      .first();
    await option.waitFor({ state: 'visible', timeout: 8_000 });
    const finalResponse = index === names.length - 1
      ? page.waitForResponse((response) => response.request().method() === 'POST'
          && response.url().includes('/product-process-workflows/resolve-by-outputs'), { timeout: 15_000 })
      : null;
    await option.click();
    if (finalResponse) await finalResponse;
  }
  await page.keyboard.press('Escape').catch(() => {});
  await page.keyboard.press('Tab').catch(() => {});
  const dateLabel = planDialog.locator('.el-form-item__label').filter({ hasText: '计划生产日' }).first();
  if (await dateLabel.isVisible().catch(() => false)) await dateLabel.click();
  await page.locator('.el-select-dropdown:visible').waitFor({ state: 'hidden', timeout: 5_000 }).catch(() => {});
  await page.getByText('正在解析工序图…').waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});
}

async function clearSelectedProducts(planDialog) {
  const productField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
  for (let attempts = 0; attempts < 12; attempts += 1) {
    const closer = productField.locator('.el-tag__close').first();
    if (!await closer.isVisible().catch(() => false)) break;
    await closer.click();
  }
}

async function inspectUiCase(ctx, planDialog, item, caseId) {
  const names = item.selection.map((product) => product.name);
  await selectProducts(ctx.page, planDialog, names);
  const routeDialog = ctx.page.getByRole('dialog', { name: '选择本计划使用的生产工序路线' });
  const decisionVisible = await routeDialog.isVisible().catch(() => false);
  const selectedRoute = planDialog.locator('.selected-workflow-route');
  const autoResolved = await selectedRoute.isVisible().catch(() => false);
  const trigger = decisionVisible
    ? routeDialog.locator('.workflow-preview-trigger').first()
    : selectedRoute.getByRole('button', { name: '悬浮查看 Cell 图' }).first();
  let preview = { visible: false, nodeCount: 0, edgeCount: 0, labels: [] };
  if (await trigger.isVisible().catch(() => false)) {
    await trigger.scrollIntoViewIfNeeded();
    await trigger.hover();
    const previewRoot = ctx.page.locator('[data-testid="workflow-route-preview"]:visible').first();
    await previewRoot.waitFor({ state: 'visible', timeout: 8_000 }).catch(() => {});
    preview = {
      visible: await previewRoot.isVisible().catch(() => false),
      nodeCount: await previewRoot.locator('.preview-cell').count(),
      edgeCount: await previewRoot.locator('.preview-edge').count(),
      labels: (await previewRoot.locator('.preview-cell').allInnerTexts()).slice(0, 12),
    };
  }
  const screenshot = await ctx.screenshot(`production-plan-routing-${caseId}`);
  const evidence = {
    caseId,
    selectedProducts: names,
    expectedTopologies: [...new Set(item.candidates.map((candidate) => candidate.topology))],
    candidateCountFromResolver: item.candidates.length,
    decisionDialogVisible: decisionVisible,
    candidateCardCount: decisionVisible ? await routeDialog.locator('.workflow-candidate-card').count() : 0,
    autoResolved,
    preview,
    screenshot,
  };
  if (decisionVisible) await routeDialog.getByRole('button', { name: '暂不选择' }).click();
  await clearSelectedProducts(planDialog);
  return evidence;
}

module.exports = {
  id: 'production-plan-routing-readonly',
  topologyKey,
  chooseRepresentatives,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'production-plan-routing-readonly',
    path: ROUTES.productionPlans,
    landmarks: ['生产计划管理'],
    timeoutMs: 60_000,
    inspect: async (page) => {
      await page.getByRole('button', { name: '新建计划', exact: true }).click();
      const planDialog = page.getByRole('dialog', { name: '新建生产计划' });
      await planDialog.waitFor({ state: 'visible', timeout: 10_000 });
      const manualSource = planDialog.getByRole('radio', { name: '手动', exact: true });
      await manualSource.check({ force: true });
      if (!await manualSource.isChecked()) throw new Error('Manual production source radio did not become checked');
      const productionField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
      await productionField.waitFor({ state: 'visible', timeout: 10_000 });
      await productionField.locator('.el-select__wrapper').waitFor({ state: 'visible', timeout: 10_000 });
      const inventory = await inventoryWorkflowRoutes(page, ctx.expectedFactoryId);
      const representatives = chooseRepresentatives(inventory.records);

      const uiCases = [];
      const usedSelections = new Set();
      for (const topology of TOPOLOGY_KEYS) {
        const item = representatives.byTopology[topology];
        if (!item) continue;
        const key = item.selection.map((product) => product.id).sort().join(',');
        if (usedSelections.has(key)) continue;
        usedSelections.add(key);
        uiCases.push(await inspectUiCase(ctx, planDialog, item, topology.toLowerCase()));
      }
      if (representatives.ambiguous) {
        const key = representatives.ambiguous.selection.map((product) => product.id).sort().join(',');
        if (!usedSelections.has(key)) {
          usedSelections.add(key);
          uiCases.push(await inspectUiCase(ctx, planDialog, representatives.ambiguous, 'ambiguous'));
        }
      }
      if (representatives.superset) {
        const key = representatives.superset.selection.map((product) => product.id).sort().join(',');
        if (!usedSelections.has(key)) {
          usedSelections.add(key);
          uiCases.push(await inspectUiCase(ctx, planDialog, representatives.superset, 'superset'));
        }
      }
      await planDialog.getByRole('button', { name: '取消', exact: true }).click();

      const topologyCoverage = Object.fromEntries(TOPOLOGY_KEYS.map((key) => [key, inventory.coverage.includes(key)]));
      const previewCases = uiCases.filter((item) => item.preview.visible && item.preview.nodeCount > 0).length;
      const expectedCaseCount = Object.values(topologyCoverage).filter(Boolean).length;
      const complete = Object.values(topologyCoverage).every(Boolean)
        && uiCases.length >= expectedCaseCount
        && previewCases === uiCases.length;
      return {
        activeFinishedGoodCount: inventory.activeFinishedGoodCount,
        resolverCalls: inventory.resolverCalls,
        topologyCoverage,
        unqualifiedMultiRawSingleOutputFound: inventory.coverage.includes('MULTI_RAW_TO_1_UNQUALIFIED'),
        ambiguousFound: inventory.ambiguousFound,
        supersetFound: inventory.supersetFound,
        uiCaseSummaries: uiCases.map((item) => ({
          caseId: item.caseId,
          selectedProducts: item.selectedProducts,
          expectedTopologies: item.expectedTopologies,
          candidateCountFromResolver: item.candidateCountFromResolver,
          decisionDialogVisible: item.decisionDialogVisible,
          candidateCardCount: item.candidateCardCount,
          autoResolved: item.autoResolved,
          previewVisible: item.preview.visible,
          previewNodeCount: item.preview.nodeCount,
          previewEdgeCount: item.preview.edgeCount,
          previewLabels: item.preview.labels,
        })),
        note: 'The create dialog was cancelled; no production plan was submitted.',
        screenshots: uiCases.map((item) => item.screenshot),
        assessment: complete
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'UNVERIFIED', rootCauseClass: 'data' },
      };
    },
  }),
};

},
    "scenarios/purchasing-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'purchasing-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'purchasing-readonly',
    path: ROUTES.purchasing,
    landmarks: ['采购'],
    inspect: async (page) => ({ tableCount: await page.locator('.el-table').count() }),
  }),
};

},
    "scenarios/supplier-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'supplier-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'supplier-readonly',
    path: ROUTES.suppliers,
    landmarks: ['供应商'],
    inspect: async (page) => ({ tableCount: await page.locator('.el-table').count() }),
  }),
};

},
    "scenarios/tenant-isolation.js": function(module, exports, require) {
'use strict';

const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');
const { ROUTES } = require('../config/routes');

module.exports = {
  id: 'tenant-isolation',
  async run(ctx) {
    const startedAt = Date.now();
    const result = createScenarioResult(this.id, ctx.page.url());
    ctx.scenarioRef.value = this.id;
    const body = await ctx.page.locator('body').innerText().catch(() => '');
    const login = ctx.loginEvidence;
    const staleTenantDetected = /liushanmen_admin/i.test(body);
    result.pageEvidence.push({
      username: login.username,
      factoryId: login.factoryId,
      factoryName: login.factoryName,
      displayedUsername: body.includes(login.username),
      displayedFactoryName: login.factoryName ? body.includes(login.factoryName) : null,
      staleTenantDetected,
    });
    result.apiEvidence.push(login);
    result.result = login.username === ctx.expectedUsername
      && login.factoryId === ctx.expectedFactoryId
      && !staleTenantDetected ? 'PASS' : 'CONFIRMED_DEFECT';
    result.rootCauseClass = result.result === 'PASS' ? 'none' : 'config';
    result.screenshots.push(await ctx.screenshot(this.id));
    result.durationMs = Date.now() - startedAt;
    return assertScenarioResult(result);
  },
  path: ROUTES.dashboard,
};

},
    "scenarios/ui-stability.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'ui-stability',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'ui-stability',
    path: ROUTES.dashboard,
    landmarks: ['工作台'],
    inspect: async (page, body) => ({
      visibleToastCount: await page.locator('.el-message:visible, .el-notification:visible').count(),
      rawEnglishEnums: (body.match(/\b[A-Z][A-Z_]{3,}\b/g) || []).slice(0, 20),
      horizontalOverflowCount: await page.locator('*').evaluateAll((elements) => elements.filter((element) => {
        const style = getComputedStyle(element);
        return element.scrollWidth > element.clientWidth + 5 && ['auto', 'scroll'].includes(style.overflowX);
      }).length),
    }),
  }),
};

},
    "scenarios/workflow-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'workflow-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'workflow-readonly',
    path: ROUTES.workflow,
    landmarks: ['Workflow'],
    screenshot: true,
    inspect: async (page, body) => ({
      rawCellEntryVisible: /原料 Cell|原料 SKU/.test(body),
      publishControlVisible: /发布/.test(body),
      quantityRelationVisible: body.includes('投入产出数量关系'),
      inputUnitChips: await page.getByTestId('input-unit-chip').allInnerTexts(),
      note: 'Publish/save/apply controls are never clicked by this scenario.',
      selectCount: await page.locator('.el-select').count(),
    }),
  }),
};

}
  };
  const __cache = Object.create(null);
  const __resolve = (fromId, request) => {
    const parts = fromId.split('/');
    parts.pop();
    for (const part of request.split('/')) {
      if (!part || part === '.') continue;
      if (part === '..') parts.pop();
      else parts.push(part);
    }
    let id = parts.join('/');
    if (!/\.[A-Za-z0-9]+$/.test(id)) id += '.js';
    return id;
  };
  const __load = (id) => {
    if (__cache[id]) return __cache[id].exports;
    const factory = __modules[id];
    if (!factory) throw new Error('Missing bundled module: ' + id);
    const module = { exports: {} };
    __cache[id] = module;
    factory(module, module.exports, (request) => __load(__resolve(id, request)));
    return module.exports;
  };
  const options = page.__cretasReadonlyOptions || {};
  delete page.__cretasReadonlyOptions;
  const harness = __load("core/run-suite.js");
  if (options.dryRun) return harness.describeHarness();
  return harness.runSuiteWithPage(page, { ...options, productionReadonly: true });
}
