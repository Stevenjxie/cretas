---
paths:
  - "tests/**"
  - "**/*.spec.ts"
  - "**/playwright.config.*"
---

# Playwright Headed Mode + 多 chat 共存

**最后更新**: 2026-05-28
**触发**: Steve patch — Playwright 必须 headed (非 headless), Chinese font / CSS / 客户演示价值 / 多 chat 同时跑互不撞

---

## ⛔ 核心规则: Headless 禁用

任何 Cretas web-admin 或 RN Expo Web Playwright spec 跑 prod / staging UI E2E 时, **MUST** `headless: false`. 理由:

| 维度 | Headless | Headed | 选 |
|---|---|---|---|
| 速度 | 100% | 80-90% (~10-20% 慢) | trade-off |
| 中文字体 | fallback 风险 (□ 方块) | 真 OS 字体 | **headed** |
| CSS hover/focus/@media | 部分不触发 | 全触发 | **headed** |
| 截图 | virtual buffer | 真屏 render | **headed** |
| 客户演示价值 | 低 | 高 ⭐ | **headed** |
| Steve 屏占用 | 0 | 1 window/chat | trade-off |

8-15h 工时多 1-3h 换真截图, 值.

---

## 配置范例 (`playwright.config.ts use:`)

```ts
const PORT = Number(process.env.PLAYWRIGHT_PORT) || 9222;
const CHAT_ID = process.env.PLAYWRIGHT_CHAT_ID || 'default';
const POSITIONS: Record<number, string> = {
  9222: '0,0',      // chat A 左
  9223: '500,0',    // chat B 中
  9224: '1000,0',   // chat C 右
};

use: {
  headless: false,                    // ⭐ 强制 headed
  viewport: { width: 1920, height: 1080 },  // 桌面默认 (移动 case page.setViewportSize)
  launchOptions: {
    args: [
      `--remote-debugging-port=${PORT}`,
      `--user-data-dir=./.pw-cache-${CHAT_ID}/`,
      '--lang=zh-CN',                 // ⭐ 中文 locale (Cretas 客户)
      '--font-render-hinting=none',   // 字体渲染稳定 (跨 OS)
      '--disable-blink-features=AutomationControlled',
      `--window-position=${POSITIONS[PORT] || '0,0'}`,
      '--window-size=1920,1080',
    ],
    slowMo: 100,
  },
  screenshot: { mode: 'on', fullPage: true },
  video: { mode: 'on', size: { width: 1920, height: 1080 } },
}
```

每 chat 跑 spec 前 export:
```bash
# AI 工厂 chat
PLAYWRIGHT_PORT=9222 PLAYWRIGHT_CHAT_ID=ai-factory npx playwright test ...

# BI chat
PLAYWRIGHT_PORT=9223 PLAYWRIGHT_CHAT_ID=bi npx playwright test ...

# 餐饮 chat
PLAYWRIGHT_PORT=9224 PLAYWRIGHT_CHAT_ID=mealclaw npx playwright test ...
```

---

## 多 chat 共存纪律 (chat brief 必带)

3 chat 同时跑 headed 时:
1. ❌ 不点 chromium 弹窗里的元素 (跟 Playwright command 撞)
2. ❌ 不 alt-tab 让 chromium window 失焦太频繁 (截图可能黑屏)
3. ✅ 副屏 / 最小化 OK, ❌ 不关 window
4. ❌ 不准 minimize 到 system tray (某些 OS 会 suspend render)
5. ✅ Spec 跑完 chromium 自动 close, 不用手动管
6. ❌ 期间不重启电脑 / 不让电脑 sleep (break Playwright session)

---

## 反 pattern (绝对禁止)

❌ `headless: true` (除非 spec 显式标 "infra test 不要截图")
❌ headless 与 headed 混跑 (同 spec 2 mode → 截图视觉不一致)
❌ chromium `--no-sandbox` (不安全)
❌ 不设 viewport (默认 1280×720 不是客户分辨率)
❌ 不设 `--lang=zh-CN` (中文字体 fallback 风险)
❌ 多 chat 用同 PORT / 同 user-data-dir (window stack + cookie 串)

---

## Audit doc 必含 verification block

跑完 spec audit doc 末必 paste:

```markdown
## Headed Mode Verification

- headless: false ✓
- viewport: 1920×1080 ✓
- locale: zh-CN ✓
- chromium window 真弹 ✓ (Steve 屏幕看到)
- 截图字体: 中文真显示 (无方块 □) ✓
- screenshot mode: fullPage ✓
- video: .webm 真录 ✓
- PLAYWRIGHT_PORT: {9222|9223|9224} (per chat)
- PLAYWRIGHT_CHAT_ID: {chat 标识}
```

如 spec 跑没满足任一条 → 不 ship, 修 config 再跑.

---

## 例外 — infra-only spec 可 headless

仅以下 spec 类型可 `headless: true`:
- 健康检查 / 端口 ping (无 UI)
- 后台 cron 触发 / metrics scrape
- TestBeforeMergeCI smoke (CI 跑, 无 Steve 看)

**所有 customer-visible UI E2E (mealclaw / workdesk / SmartBI dashboard / RN App) 一律 headed.**

---

## 历史 incident 触发

2026-05-28 Steve MCP 浏览审 cache JSON dump bug 时 patch 加这条 rule. 之前 mealclaw-customer.spec / mealclaw-customer-r4-explicit-month.spec 用 default headless 跑出 5 rounds, 但 Steve 用 MCP headed 一看就发现 raw JSON dump (headless 跑没暴露). headed 抓 UX bug 比 headless 多.

---

## 触发场景

| 场景 | 必应用 |
|---|---|
| 任何 UI E2E spec 新写 | 强制 headed config |
| Cretas Workdesk / AIChat / Dashboard 截图 | 强制 headed |
| 客户演示前 verify | 强制 headed |
| MCP playwright browser 手动 audit | 默认 headed (MCP 已是) |
| CI 跑 (Steve 不看) | 可 headless (例外) |

PR review 时如发现 UI E2E spec headless: true → 阻 merge.
