import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: 'liushanmen-manual-screenshots.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 120000,
  use: {
    headless: false,
    viewport: { width: 1920, height: 1080 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    launchOptions: {
      args: [
        '--lang=zh-CN',
        '--font-render-hinting=none',
        '--disable-blink-features=AutomationControlled',
        '--window-position=0,0',
        '--window-size=1920,1080',
      ],
      slowMo: 80,
    },
    actionTimeout: 15000,
    navigationTimeout: 30000,
  },
  reporter: [['list']],
});
