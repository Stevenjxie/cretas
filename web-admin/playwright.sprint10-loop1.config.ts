/**
 * Sprint 10 Loop 1 — 发货闭环 Playwright config (standalone).
 *
 * Run:
 *   E2E_BASE_URL=http://139.196.165.140:8086 \
 *   E2E_API_BASE=http://47.100.235.168:10010/api/mobile \
 *   npx playwright test --config=playwright.sprint10-loop1.config.ts
 *
 * Serial 1 worker (test data depends on order).
 * Self-injects auth via e2e-auth-helper.ts; doesn't depend on auth.setup.ts.
 */
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: 'sprint10-loop-1-shipment.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 300000,
  expect: {
    timeout: 15000,
  },
  use: {
    headless: true,
    viewport: { width: 1440, height: 900 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 20000,
    navigationTimeout: 60000,
  },
  reporter: [['list']],
  outputDir: 'test-results/sprint10-loop1',
});
