/**
 * Playwright config — Sprint 11 BI Indicator Full E2E
 * Per Steve 2026-05-28 directive:
 *  - HEADED mode (not headless) — real font rendering, real CSS, screenshot truth
 *  - Independent user-data-dir + window-position 防 3-chat 撞
 *  - viewport 1920×1080 default + lang=zh-CN + slowMo 100ms 截图稳
 */
import { defineConfig } from '@playwright/test';

const CHAT_ID = 'bi-chat';
const PORT = 9223; // BI=9223, AI 工厂=9222, 第三 chat=9224

export default defineConfig({
  testDir: 'tests/e2e-customer-journey',
  testMatch: 'sprint11-bi-indicator-full.spec.ts',
  workers: 1,
  timeout: 240_000,
  expect: { timeout: 15_000 },
  use: {
    headless: false, // Steve directive: HEADED for screenshot truth
    viewport: { width: 1920, height: 1080 },
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
    locale: 'zh-CN',
    launchOptions: {
      args: [
        '--lang=zh-CN',
        '--font-render-hinting=none',
        '--disable-blink-features=AutomationControlled',
        '--window-position=500,0',
        '--window-size=1920,1080',
      ],
      slowMo: 100,
    },
    screenshot: { mode: 'on', fullPage: true },
    video: { mode: 'on', size: { width: 1280, height: 720 } },
  },
  reporter: [['list']],
  outputDir: 'test-results',
});
