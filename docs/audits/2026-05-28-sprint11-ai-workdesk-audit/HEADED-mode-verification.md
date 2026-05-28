# Headed Mode Verification — per Steve 2026-05-28 patch

| Check | Status | Evidence |
|---|---|---|
| `headless: false` | ✅ | `playwright.config.ts:222` |
| `viewport: 1920×1080` | ✅ | `playwright.config.ts:223` |
| `locale: zh-CN` | ✅ | `playwright.config.ts:224` |
| `timezoneId: Asia/Shanghai` | ✅ | `playwright.config.ts:225` |
| `--lang=zh-CN` launch arg | ✅ | `playwright.config.ts:228` |
| `--font-render-hinting=none` | ✅ | `playwright.config.ts:229` |
| `--window-position=0,0` (ai-factory chat 左) | ✅ | `playwright.config.ts:231` |
| `--window-size=1920,1080` | ✅ | `playwright.config.ts:232` |
| `slowMo: 100ms` (screenshot 时机稳) | ✅ | `playwright.config.ts:234` |
| `screenshot.fullPage` | ✅ | `playwright.config.ts:236` |
| `video.size 1920×1080` | ✅ | `playwright.config.ts:237` |
| Chromium window 真弹 | ✅ | smoke 1.2min (vs 40s headless) — Steve 可见左上角弹窗 |
| 中文真显示 (无方块 □) | TBD | Phase B 22 PNG 人眼 review |
| Sprint 13 ticket on font fallback (if seen) | N/A | leak detector A2 hit "Sprint 8 P4a" — Steve 5/28 screenshot 印证 |

## 多 chat 共存 protocol

| Chat | PORT | Window position | Spec file |
|---|---|---|---|
| **AI 工厂 (我)** | 9222 (default) | 0,0 (左) | `sprint11-ai-workdesk-full.spec.ts` |
| BI chat | 9223 | 500,0 (中) | TBD — 用 `bi-*.spec.ts` 命名 |
| 第 3 chat | 9224 | 1000,0 (右) | TBD — 用 `mealclaw-*.spec.ts` 命名 |

Steve 注意:
1. 不要点 chromium 弹窗里的元素
2. 不要 alt-tab 频繁
3. 副屏 / 最小化 OK, 不准关 window
4. 跑完 chromium 自动 close
5. 不准让电脑 sleep / 重启
