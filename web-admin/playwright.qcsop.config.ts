import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: 'qcsop-closeout.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: {
    timeout: 15_000,
  },
  use: {
    baseURL: 'http://127.0.0.1:5180',
    headless: true,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'python -m http.server 5180 --directory ../docs/manual',
    url: 'http://127.0.0.1:5180/qc-label-inspection-sop.html',
    reuseExistingServer: false,
    timeout: 120_000,
  },
  reporter: [['list']],
  outputDir: 'test-results/qcsop-closeout',
});
