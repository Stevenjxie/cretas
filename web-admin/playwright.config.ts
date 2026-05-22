import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  fullyParallel: true,
  workers: 3,
  timeout: 300000,
  expect: {
    timeout: 10000,
  },
  use: {
    headless: true,
    viewport: { width: 1440, height: 900 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 15000,
    navigationTimeout: 30000,
  },
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results',
  projects: [
    // Step 1: 登录一次，保存 storageState
    {
      name: 'vue-auth',
      testMatch: /auth\.setup\.ts/,
    },
    // Step 2: Vue 测试复用 factory_admin1 的 token
    {
      name: 'vue-web-admin',
      testMatch: 'process-mode-e2e.spec.ts',
      dependencies: ['vue-auth'],
      use: { storageState: 'test-results/.auth/factory-admin.json' },
    },
    // Step 3: RN 测试独立（自己登录）
    {
      name: 'rn-expo-web',
      testMatch: 'rn-expo-web-e2e.spec.ts',
    },
    // Step 4: 截图脚本
    {
      name: 'screenshots',
      testMatch: 'capture-guide-screenshots.spec.ts',
    },
    // Step 5: P0+P1+P2 验证
    {
      name: 'p0p1p2-verify',
      testMatch: 'p0-p1-p2-verify.spec.ts',
      dependencies: ['vue-auth'],
      use: { storageState: 'test-results/.auth/factory-admin.json' },
    },
    // Step 6: 新功能截图
    {
      name: 'new-features-screenshots',
      testMatch: 'capture-new-features.spec.ts',
    },
    // Step 7: Phase 2 workflow + governance
    {
      name: 'phase2-verify',
      testMatch: 'workflow-phase2-e2e.spec.ts',
      dependencies: ['vue-auth'],
      use: { storageState: 'test-results/.auth/factory-admin.json' },
    },
    // Step 8: 六扇门一期 E2E (Web Admin)
    {
      name: 'liushanmen-e2e',
      testMatch: 'liushanmen-e2e.spec.ts',
      dependencies: ['vue-auth'],
      use: { storageState: 'test-results/.auth/factory-admin.json' },
    },
    // Step 9: 六扇门一期 E2E (RN Expo Web — 比 Maestro 快)
    {
      name: 'liushanmen-rn-e2e',
      testMatch: 'liushanmen-rn-e2e.spec.ts',
    },
    // Step 10: Web Admin 全模块 E2E (自注入 auth，不依赖 vue-auth)
    {
      name: 'web-admin-e2e',
      testMatch: 'web-admin-e2e.spec.ts',
    },
    // Step 11: Web Admin CRUD Interactions E2E
    {
      name: 'web-admin-crud',
      testMatch: 'web-admin-crud-e2e.spec.ts',
    },
    // Step 12: Web Admin Business Workflows E2E
    {
      name: 'web-admin-workflows',
      testMatch: 'web-admin-workflows-e2e.spec.ts',
    },
    // Step 13: Restaurant Chat E2E (P5 Task 5.7)
    // Skipped by default — requires full stack. Enable: RUN_CHAT_E2E=1
    {
      name: 'restaurant-chat',
      testMatch: 'restaurant-chat.spec.ts',
    },
    // Step 14: 数据织网 C smoke E2E — real-window guards for Day 23-30
    // critical user journeys. Catches "vitest passes but production broken"
    // bugs (Day 26 snake/camel + P0-1 grossMargin 2058% + P0-2 KPI 全 0 +
    // P1-3+P1-4 chart 空骨架). Run via:
    //   E2E_BASE_URL=http://139.196.165.140:8097 \
    //   npx playwright test --project data-fabric-c-smoke
    {
      name: 'data-fabric-c-smoke',
      testMatch: 'data-fabric-c-smoke-e2e.spec.ts',
      dependencies: ['vue-auth'],
      // 串行: test env 单服务器并发受限, 减少 networkidle/timeout flake.
      fullyParallel: false,
      workers: 1,
      use: { storageState: 'test-results/.auth/factory-admin.json' },
    },
    // Step 15: QHJ revenue report (Phase I, 2026-05-13). Self-injects auth via
    // e2e-auth-helper; doesn't depend on vue-auth (uses qhj_admin not factory_admin1).
    // Run via:
    //   E2E_BASE_URL=http://139.196.165.140:8097 \
    //   E2E_API_BASE=http://139.196.165.140:8097/api/mobile \
    //   E2E_USER=qhj_admin E2E_PASS=... E2E_FACTORY_ID=R_QINGHUAJIAO_REAL \
    //   npx playwright test --project revenue-report
    {
      name: 'revenue-report',
      testMatch: 'revenue-report.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    {
      name: 'revenue-report-smoke',
      testMatch: 'revenue-report-smoke.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    {
      name: 'smartbi-bug-repro',
      testMatch: 'smartbi-bug-repro.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    {
      name: 'revenue-report-chat-demo',
      testMatch: 'revenue-report-chat-demo.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        headless: false,
        launchOptions: { slowMo: 400 },
        video: 'on',
      },
    },
    {
      name: 'phase-iia-final-demo',
      testMatch: 'phase-iia-final-demo.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    // Sprint 10 Loop 2 — 入库/收货 AI 闭环 E2E (2026-05-21).
    // Self-injects auth via e2e-auth-helper using f006_admin / 123456.
    // Run via:
    //   E2E_BASE_URL=http://139.196.165.140:8086 \
    //   E2E_API_BASE=http://47.100.235.168:10010/api/mobile \
    //   E2E_USER=f006_admin E2E_PASS=123456 E2E_FACTORY_ID=F006 \
    //   npx playwright test --project sprint10-loop-2-receive
    {
      name: 'sprint10-loop-2-receive',
      testMatch: 'tests/e2e-closed-loop/loop-2-receive.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    // Sprint 10 Loop 4 — 审批闭环 E2E (top-level spec file convention from Loop 1)
    // Run via:
    //   E2E_API_URL=http://47.100.235.168:10010/api/mobile \
    //   E2E_USER=f006_admin E2E_PASS=123456 E2E_FACTORY_ID=F006 \
    //   npx playwright test --project sprint10-loop-4-approval
    {
      name: 'sprint10-loop-4-approval',
      testMatch: 'sprint10-loop-4-approval.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
  ],
});