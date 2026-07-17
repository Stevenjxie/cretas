#!/usr/bin/env node

import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { describeHarness, runSuiteWithPage } = require('./core/run-suite.js');

function parseArgs(argv) {
  const options = { scenarios: [] };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--headed') options.headed = true;
    else if (arg === '--production-readonly') options.productionReadonly = true;
    else if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--scenario') options.scenarios.push(...String(argv[++index] || '').split(',').filter(Boolean));
    else if (arg === '--base-url') options.baseUrl = argv[++index];
    else if (arg === '--evidence-dir') options.evidenceDir = argv[++index];
    else throw new Error(`Unknown argument: ${arg}`);
  }
  return options;
}

function isProductionLike(baseUrl) {
  const url = new URL(baseUrl);
  return !['localhost', '127.0.0.1', '::1'].includes(url.hostname);
}

async function loadChromium() {
  for (const name of ['playwright', '@playwright/test']) {
    try {
      const module = await import(name);
      if (module.chromium) return module.chromium;
    } catch {}
  }
  throw new Error('Project Playwright runtime is unavailable. Run npm ci at the repository root; do not install a separate harness dependency tree.');
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const baseUrl = args.baseUrl || process.env.E2E_ADMIN_URL || 'http://localhost:5173';
  const productionReadonly = args.productionReadonly || isProductionLike(baseUrl);
  if (args.dryRun) {
    console.log(JSON.stringify({ ...describeHarness(), baseUrl, productionReadonly, selectedScenarios: args.scenarios }, null, 2));
    return;
  }

  const username = process.env.E2E_USERNAME;
  const password = process.env.E2E_PASSWORD;
  if (!username || !password) throw new Error('E2E_USERNAME and E2E_PASSWORD are required; credentials must come from a gitignored environment source');
  const runId = new Date().toISOString().replace(/[:.]/g, '-');
  const evidenceDir = path.resolve(args.evidenceDir || path.join('.playwright-mcp', 'production-readonly', runId));
  const chromium = await loadChromium();
  const browser = await chromium.launch({ headless: !args.headed });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  try {
    const report = await runSuiteWithPage(page, {
      baseUrl,
      evidenceDir,
      scenarios: args.scenarios,
      username,
      password,
      expectedUsername: process.env.E2E_EXPECTED_USERNAME || 'f006_admin',
      expectedFactoryId: process.env.E2E_FACTORY_ID || 'F006',
      productionReadonly,
    });
    console.log(JSON.stringify({
      runId: report.runId,
      evidence: report.evidence,
      scenarios: report.scenarios.map(({ scenario, result }) => ({ scenario, result })),
      blockedMutationAttempts: report.blockedMutationAttempts.length,
      actualBusinessWrites: report.actualBusinessWrites,
    }, null, 2));
    if (!report.safetyPassed || report.scenarios.some((scenario) => scenario.result !== 'PASS')) {
      process.exitCode = 1;
    }
  } finally {
    await context.close();
    await browser.close();
  }
}

main().catch((error) => {
  console.error(`[production-readonly] ${error.message}`);
  process.exitCode = 1;
});
