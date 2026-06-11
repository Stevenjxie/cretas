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
    // Sprint 10 Loop 2 — 入库/收货 AI 闭环 E2E
    {
      name: 'sprint10-loop-2-receive',
      testMatch: 'tests/e2e-closed-loop/loop-2-receive.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    // Sprint 10 Loop 4 — 审批闭环 E2E
    {
      name: 'sprint10-loop-4-approval',
      testMatch: 'sprint10-loop-4-approval.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    // Sprint 10 Loop 3 — 采购下单 AI 闭环
    {
      name: 'loop-3-procurement',
      testMatch: 'tests/e2e-closed-loop/loop-3-procurement.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    // Sprint 10 Loop 5 — 生产任务创建 AI 闭环
    {
      name: 'loop-5-production',
      testMatch: 'tests/e2e-closed-loop/loop-5-production.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    // Sprint 11 Loop 6 — Restaurant Economics Analysis AI 闭环 (MealClaw Response)
    {
      name: 'loop-6-restaurant-ai',
      testMatch: 'tests/e2e-closed-loop/loop-6-restaurant-ai.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    // Sprint 11 MealClaw Audit Q7/Q8 — UI-level customer journey screenshot + video
    // Per .claude/rules/playwright-headed-mode.md: 强制 headed + zh-CN locale +
    // 1920x1080 viewport so 中文 font renders真 + 客户演示截图价值. Multi-chat
    // coexistence: set PLAYWRIGHT_PORT (9222/9223/9224) + PLAYWRIGHT_CHAT_ID per chat.
    // Output: docs/audits/sprint-11-mealclaw-screenshots/*.png + *.webm
    {
      name: 'mealclaw-customer-ui',
      testMatch: 'tests/e2e-customer-journey/mealclaw-customer.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        headless: false,
        viewport: { width: 1920, height: 1080 },
        launchOptions: {
          args: [
            `--remote-debugging-port=${Number(process.env.PLAYWRIGHT_PORT) || 9224}`,
            `--user-data-dir=./.pw-cache-${process.env.PLAYWRIGHT_CHAT_ID || 'mealclaw'}/`,
            '--lang=zh-CN',
            '--font-render-hinting=none',
            '--disable-blink-features=AutomationControlled',
            '--window-position=1000,0',
            '--window-size=1920,1080',
          ],
          slowMo: 100,
        },
        video: 'on',
        trace: 'on',
        screenshot: 'on',
      },
    },
    // Sprint 11 Round 4 — B.5 explicit-month customer evidence (headed per rule)
    {
      name: 'mealclaw-customer-r4',
      testMatch: 'tests/e2e-customer-journey/mealclaw-customer-r4-explicit-month.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        headless: false,
        viewport: { width: 1920, height: 1080 },
        launchOptions: {
          args: [
            `--remote-debugging-port=${Number(process.env.PLAYWRIGHT_PORT) || 9224}`,
            `--user-data-dir=./.pw-cache-${process.env.PLAYWRIGHT_CHAT_ID || 'mealclaw'}/`,
            '--lang=zh-CN',
            '--font-render-hinting=none',
            '--disable-blink-features=AutomationControlled',
            '--window-position=1000,0',
            '--window-size=1920,1080',
          ],
          slowMo: 100,
        },
        video: 'on',
        trace: 'on',
        screenshot: 'on',
      },
    },
    // Sprint 11 D7 — SalesOwner Workdesk 4 IndicatorCards depth test
    {
      name: 'sprint-11-d7-salesowner',
      testMatch: 'tests/e2e-closed-loop/sprint-11-d7-salesowner.spec.ts',
      fullyParallel: false,
      workers: 1,
    },
    // Sprint 11 全流程 UX 审计 (2026-05-23) — 12 cases (4 phrase × 3 accounts)
    // Real UI: login + nav SalesOwnerWorkdesk + type phrase + screenshot result.
    // Output: 12 PNG + 1 video .webm + ui-text-12.json
    {
      name: 'full-customer-flow-2026-05-23',
      testMatch: 'tests/e2e-customer-journey/full-customer-flow-2026-05-23.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        headless: true,
        video: 'on',
        viewport: { width: 1440, height: 900 },
      },
    },
    // Sprint 11 AI Workdesk Full E2E + UX Audit (2026-05-28) — 22 cases
    // Core 12 happy (SalesOwner × 4 phrase × 3 accounts) + Breadth 6 (其他 Workdesks) + Error 4
    // Per qa-prompt v2.4: MutationObserver + 四位一体 + Rule 11 roundtrip + Rule 9 抽检
    // Per Steve 5/28 expanded brief: HEADED mode (中文字体/CSS/客户演示真实) + 15 类 anti-pattern leak sweep
    // Output: 22 PNG + 1 video .webm + captures.json + leak-*.png
    // CHAT_ID: ai-factory (PORT 9222, window-position 0,0)
    {
      name: 'sprint11-ai-workdesk-full',
      testMatch: 'tests/e2e-customer-journey/sprint11-ai-workdesk-full.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        // ⭐ HEADED — 真弹 chromium window, 客户演示价值
        headless: false,
        viewport: { width: 1920, height: 1080 },
        locale: 'zh-CN',
        timezoneId: 'Asia/Shanghai',
        launchOptions: {
          args: [
            '--lang=zh-CN',
            '--font-render-hinting=none',
            '--disable-blink-features=AutomationControlled',
            '--window-position=0,0',           // AI 工厂 chat 左
            '--window-size=1920,1080',
          ],
          slowMo: 100,
        },
        screenshot: { mode: 'on', fullPage: true },
        video: { mode: 'on', size: { width: 1920, height: 1080 } },
      },
    },
    // Sidebar IA-redesign — 双业态 E2E (2026-06-01). 验证「经营报表」+「智能分析」
    // 合并为单一「数据与分析」组 + 业态门控 + redirect。HEADED per
    // .claude/rules/playwright-headed-mode.md (中文字体/CSS/客户演示真实)。
    // Run via: npx playwright test --project ia-redesign
    // Self-injects auth (factory_admin1 + qhj_prod) via e2e-auth-helper; no vue-auth dep.
    {
      name: 'ia-redesign',
      testMatch: 'tests/e2e-ia-redesign.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        // ⭐ HEADED — 真弹 chromium window, 客户演示价值 (per playwright-headed-mode rule)
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
          slowMo: 100,
        },
        screenshot: { mode: 'on', fullPage: true },
        video: { mode: 'on', size: { width: 1920, height: 1080 } },
      },
    },
    // 餐饮运营组 IA v2 — headed E2E (2026-06-02). 验证「餐饮运营」组重组 3 层
    // (深度分析/日常录入/数据与系统) + 旧路由 redirect (menu/gross-margin/dianping/analytics)
    // + 平台口碑页禁假数据 banner。HEADED per .claude/rules/playwright-headed-mode.md
    // (中文字体/CSS/客户演示真实)。多 chat 共存靠 --window-position (右) + Playwright
    // 内置 per-worker context 隔离 —— 不用 --user-data-dir/--remote-debugging-port
    // (本版 Playwright 的 launch() 拒绝 --user-data-dir; 沿用已验证的 ia-redesign 配置)。
    // Run via:
    //   E2E_BASE_URL=http://139.196.165.140:8097 \
    //   npx playwright test --project restaurant-ia
    // Self-injects auth (qhj_prod) via e2e-auth-helper; no vue-auth dep.
    // fix/ai-workprocess-draft-render verification (headed, zh-CN)
    {
      name: 'ai-workprocess-draft-render',
      testMatch: 'ai-workprocess-draft-render.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        headless: false,
        viewport: { width: 1920, height: 1080 },
        locale: 'zh-CN',
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
        screenshot: { mode: 'on', fullPage: true },
      },
    },
    {
      name: 'restaurant-ia',
      testMatch: 'tests/restaurant-ia.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        // ⭐ HEADED — 真弹 chromium window, 客户演示价值 (per playwright-headed-mode rule)
        headless: false,
        viewport: { width: 1920, height: 1080 },
        locale: 'zh-CN',
        timezoneId: 'Asia/Shanghai',
        launchOptions: {
          args: [
            '--lang=zh-CN',
            '--font-render-hinting=none',
            '--disable-blink-features=AutomationControlled',
            '--window-position=1000,0',
            '--window-size=1920,1080',
          ],
          slowMo: 100,
        },
        screenshot: { mode: 'on', fullPage: true },
        video: { mode: 'on', size: { width: 1920, height: 1080 } },
      },
    },
    // SP2 撤回流程 E2E — 4 场景 (P1 #41, 2026-06-11)
    // 覆盖: 提交撤回申请 / 主管审批 / SP12 快速通道本人直撤 / 主管驳回
    // Per .claude/rules/playwright-headed-mode.md: 强制 headed + zh-CN locale + 1920x1080
    // Run via:
    //   PLAYWRIGHT_PORT=9222 PLAYWRIGHT_CHAT_ID=sp2-reversal \
    //   E2E_BASE_URL=http://139.196.165.140:8086 \
    //   E2E_API_URL=http://47.100.235.168:10010/api/mobile \
    //   npx playwright test --project sp2-reversal
    {
      name: 'sp2-reversal',
      testMatch: 'sp2-reversal.spec.ts',
      fullyParallel: false,
      workers: 1,
      use: {
        // ⭐ HEADED — per .claude/rules/playwright-headed-mode.md 铁律 (中文字体/客户演示/CSS hover)
        headless: false,
        viewport: { width: 1920, height: 1080 },
        locale: 'zh-CN',
        timezoneId: 'Asia/Shanghai',
        launchOptions: {
          args: [
            `--remote-debugging-port=${Number(process.env.PLAYWRIGHT_PORT) || 9222}`,
            `--user-data-dir=./.pw-cache-${process.env.PLAYWRIGHT_CHAT_ID || 'sp2-reversal'}/`,
            '--lang=zh-CN',
            '--font-render-hinting=none',
            '--disable-blink-features=AutomationControlled',
            '--window-position=0,0',
            '--window-size=1920,1080',
          ],
          slowMo: 80,
        },
        screenshot: { mode: 'on', fullPage: true },
        video: { mode: 'on', size: { width: 1920, height: 1080 } },
      },
    },
  ],
});