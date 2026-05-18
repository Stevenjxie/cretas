/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 — Playwright E2E config for Customer 360° detail view.
 *
 * Pre-flight (before running):
 *   1. Backend running: http://localhost:10010/api/mobile/health (or prod equivalent)
 *   2. Web-admin dev server: npm run dev (port 5173) OR built dist served
 *   3. Test customer seeded in F006 (or use any existing customer ID)
 *
 * Run:
 *   cd tests/e2e-customer-360 && npx playwright test
 *   PLAYWRIGHT_BASE_URL=https://admin.cretaceousfuture.com npx playwright test
 */
import { defineConfig, devices } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';

export default defineConfig({
  testDir: '.',
  timeout: 180 * 1000,
  expect: { timeout: 15 * 1000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'test-results/html' }]],
  use: {
    baseURL: BASE_URL,
    headless: true,
    screenshot: 'on',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
    viewport: { width: 1920, height: 1080 },
    ignoreHTTPSErrors: true,
  },
  projects: [
    {
      name: 'customer-360',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  outputDir: 'test-results/',
});
