import { defineConfig, devices } from '@playwright/test';
import * as path from 'path';

/**
 * Dedicated config for Canvas E2E rounds.
 *
 * Differs from the main playwright.config.ts:
 *   - testDir = ./canvas (this directory)
 *   - baseURL points to test env 8097 (or override via env)
 *   - fullyParallel = true to fan out the 340 rounds across workers
 *   - workers = 4 for throughput (test env can absorb)
 *   - shorter per-test timeout (30s) — API rounds finish fast
 */
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  fullyParallel: false,        // serial inside a file (state-dependent rounds)
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  reporter: [
    ['list'],
    ['json', { outputFile: path.join(__dirname, '..', 'test-results', 'canvas-results.json') }],
    ['html', { outputFolder: path.join(__dirname, '..', 'playwright-report-canvas'), open: 'never' }],
  ],
  outputDir: path.join(__dirname, '..', 'test-results', 'canvas'),
  use: {
    baseURL: process.env.CANVAS_E2E_BASE_URL || 'http://139.196.165.140:8097',
    headless: true,
    viewport: { width: 1440, height: 900 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    locale: 'zh-CN',
  },
  projects: [
    {
      name: 'canvas-chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
