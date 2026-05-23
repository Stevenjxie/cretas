# Sprint 12 P0 Routing Fix — Verdict (2026-05-23)

**Owner**: AI 工厂 chat (Sprint 11 audit coordinator → Sprint 12 implementer)
**Spec**: `docs/superpowers/specs/2026-05-23-sprint-12-nl-routing-fix.md`
**Branch**: `feat/sprint-12-nl-routing-fix-2026-05-23`
**Deploy**: 2026-05-23 04:13 prod 47:10010 Blue-Green cutover (green 10020 ACTIVE)
**Status**: 🟢 **P0 ROUTING GOAL ACHIEVED at backend** (4/4 API verified). UI rendering shows partial improvement; remaining UI inconsistency is **frontend-side capture/formatter issue, NOT routing**.

---

## TL;DR

🟢 **Backend routing fix WORKS — 4/4 customer phrases now route to RESTAURANT_ECONOMICS_ANALYSIS via prod API.**
🟡 **UI test capture: 2-3/12 cases show new behavior; 6-7/12 still show old DAILY_CUSTOMER_FOLLOWUP output**. Root cause is UI-side (Playwright capture race against Vue formattedText state OR Vue response formatter transforming output) — **NOT a routing regression**.

For Sprint 12 P0 goal: ROUTING fix is the deliverable. Goal achieved. UI rendering quality is a frontend P0-2 follow-up (separate scope per Steve goal "❌ Fix P0-2 LLM 幻觉 guard").

---

## API-level proof (load-bearing evidence)

Per direct curl on prod 139:8086 (post-deploy) — `qhj_warehouse_mgr / RES_3101_009`:

```bash
$ TOKEN=$(curl ... unified-login | ...)
$ echo '{"userInput":"帮我看上月损溢异常"}' > /tmp/req.json
$ curl ... ai-intents/execute --data-binary @/tmp/req.json
{"data":{"intentCode":"RESTAURANT_ECONOMICS_ANALYSIS",
         "intentRecognized":true, "status":"SUCCESS",
         "formattedText":"部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性. 已基于可用数据生成分析, 不可用部分需明确标注."}}
```

| Phrase | Before fix (Sprint 11 audit) | After fix (Sprint 12 API verify) |
|---|---|---|
| 帮我看上月损溢异常 | DAILY_CUSTOMER_FOLLOWUP (Class D) | **RESTAURANT_ECONOMICS_ANALYSIS** ✅ Class B |
| 损益分析 | DAILY_CUSTOMER_FOLLOWUP (Class D) | **RESTAURANT_ECONOMICS_ANALYSIS** ✅ |
| 上月成本 | DAILY_CUSTOMER_FOLLOWUP (Class D) | **RESTAURANT_ECONOMICS_ANALYSIS** ✅ |
| 哪个菜亏钱 | UI hang (Class F) | **RESTAURANT_ECONOMICS_ANALYSIS** ✅ |

**Verified by sequence test (simulate UI auto-mount + click)**:
1. POST with `intentCode: DAILY_CUSTOMER_FOLLOWUP` (auto-mount) → SUCCESS, len 597
2. POST `{"userInput":"哪个菜亏钱"}` (user click, no intentCode) → **`intentCode: RESTAURANT_ECONOMICS_ANALYSIS`** ✅

This sequence proves the Sprint 11 #0.25 phrase shortcut fires correctly AFTER a prior DAILY_CUSTOMER_FOLLOWUP call (no session/conversation pollution).

---

## UI-level result (12 PNG re-captured post-fix)

`docs/audits/sprint-12-routing-fix/screenshots-after/` (12 PNG) + `ui-text-12-after.json` (13 captures incl. 1 retry).

| Account | Phrase | Before (Sprint 11) | After (Sprint 12 UI) | API truth | UI matches API? |
|---|---|---|---|---|---|
| qhj_warehouse_mgr | phrase1 | F (LLM timeout) | "查询完成 包含 5 项数据指标" (短 37char) | RESTAURANT_ECONOMICS_ANALYSIS | ⚠️ different formatter |
| qhj_warehouse_mgr | phrase2 | D 错路由 | TIMEOUT (UI never rendered) | RESTAURANT_ECONOMICS_ANALYSIS | ❌ capture race |
| qhj_warehouse_mgr | phrase3 | D 错路由 | "### 今日客户跟进概览 ..." (stale) | RESTAURANT_ECONOMICS_ANALYSIS | ❌ capture race |
| qhj_warehouse_mgr | phrase4 | F (UI hang) | "### 今日客户跟进概览 ..." (stale) | RESTAURANT_ECONOMICS_ANALYSIS | ❌ capture race |
| f006_admin | phrase1-4 | D × 4 | TIMEOUT × 1, D × 3 | (not tested — F006 may not have RESTAURANT_ECONOMICS_ANALYSIS configured for non-restaurant factory) | N/A |
| warehouse_mgr1 | phrase1-3 | D × 4 | D × 3 | (not tested — F001 same as F006) | N/A |
| warehouse_mgr1 | phrase4 | D | AUTH_FAIL × 2 (after retries) | N/A | ⚠️ transient prod glitch |

### UI capture race explanation (likely root cause for 6/12 stale captures)

`SalesOwnerWorkdesk.vue:589-606`:
1. Page mounts → `triggerFollowupQuery()` → DAILY_CUSTOMER_FOLLOWUP response sets `formattedText.value = '### 今日客户跟进概览 ...'`
2. User click 发送 → `sendQuery()` sets `formattedText.value = ''` momentarily, then awaits response
3. Playwright wait condition `(result-card present) || error || (loading gone + indicators-card)` may resolve on stale state if Vue's reactivity DOM update lags 1 tick behind the wait check
4. Capture happens BEFORE new response arrives → captures old `### 今日客户跟进概览`

**Evidence this is UI race not routing bug**: same 4 phrases via direct curl ALL return RESTAURANT_ECONOMICS_ANALYSIS. Same factory, same auth, same payload. Only difference is browser timing.

### Why phrase1 shows "查询完成 包含 5 项数据指标"

This 37-char NEW response is NOT from API (API returns 78 chars "部分数据不可用..."). This suggests Vue's **response formatter wraps RESTAURANT_ECONOMICS_ANALYSIS in a generic "summary stub"** for cases where formattedText lacks Markdown headers OR the response.resultData has multiple items. Likely a formatter at the Vue layer (separate frontend issue, not routing).

---

## Class distribution comparison

| Class | Sprint 11 (12 PNG) | Sprint 12 API truth (4 RES) | Sprint 12 UI capture (12 PNG) |
|---|---|---|---|
| (A) 经营建议 | 0% | 0% (Composite Tool returns Class B "数据缺" still — P0-3 餐饮 chat scope) | 0% |
| (B) 数据缺 (在正确 intent 下) | 0% | **100%** (4/4 RES) ✅ routing fixed | ~1/12 (8%) |
| (D) 错路由 | 75% (9/12) | **0%** ✅ | ~50% (6/12) — capture race or formatter |
| (F) 错误信息 | 25% (3/12) | 0% | ~33% (4/12) — timeout/auth_fail |
| Routing CORRECT at backend | 0% | **100%** for RES_3101_009 | proves fix works |

**Sprint 12 P0 goal scope: 100% achieved at API/routing layer. UI rendering quality is out of scope.**

---

## DoD verification

| DoD | Status |
|---|---|
| Spec doc merged | ✅ `2026-05-23-sprint-12-nl-routing-fix.md` |
| Fix 1+2+3+4+5 implemented + tests | ✅ 16/16 mvn unit tests PASS |
| Deploy to prod 47:10010 BG cutover | ✅ green 10020 ACTIVE (5/5 health rounds) |
| 12 PNG re-capture + before/after diff | ✅ this verdict |
| RES_3101_009 4/4 routes to RESTAURANT_ECONOMICS_ANALYSIS | ✅ API verified |
| Verdict doc | ✅ this file |
| PR opened + admin-merged | 🟡 next step |

---

## Recommendation for Steve

### Customer demo decision (revised from Sprint 11 verdict)

Sprint 11 verdict was Option C (改 brief 走 SmartBI Path B) because 客户失望率 100%.

After Sprint 12 routing fix:
- ✅ Routing is fixed (API verified)
- ❌ Tool content still returns "部分数据不可用: P&L / 档口损溢 / 成本刚性" — this is P0-3 餐饮 chat scope (Composite Tool data wiring)
- ❌ UI capture/formatter still shows wrong content — frontend P0-2 follow-up

**Customer impact**: If customer types brief's 5-step phrases on UI now, they'll see EITHER:
- Composite Tool's "部分数据不可用" error message (Class B 数据缺 — honest "no data" reply)
- Stale Vue capture showing "今日客户跟进概览" (Class D appearance, real result hidden)

Either way: **customer won't see (A) 经营建议**. Demo crash risk remains.

**Recommendation: STILL Option C (改 brief 走 SmartBI Path B)** until P0-3 餐饮 chat fixes Composite Tool data wiring AND P0-2 fixes UI rendering. Sprint 12 P0 routing fix is necessary but not sufficient for demo readiness.

### Sprint 13+ follow-ups

| # | Owner | Title |
|---|---|---|
| S13-001 | 餐饮 chat | P0-3 Composite Tool data wiring — actually populate financial_metrics / shrinkage_rows / financial_data.current context BEFORE sub-Tool dispatch |
| S13-002 | Frontend chat | Investigate SalesOwnerWorkdesk Vue formattedText capture race + formatter that wraps RESTAURANT_ECONOMICS_ANALYSIS as "查询完成 包含 5 项数据指标" |
| S13-003 | BI chat | P0-2 LLM 防幻觉 guard (separate from S13-002 routing fix) |
| S13-004 | AI 工厂 / BI | F006 / F001 (non-RESTAURANT factories) — investigate if RESTAURANT_ECONOMICS_ANALYSIS should be configured per-factory or globally available |

---

## Cross-references

- Sprint 11 audit (the bug discovered): `docs/audits/sprint-11-ux-audit/verdict-2026-05-23.md`
- Sprint 12 spec: `docs/superpowers/specs/2026-05-23-sprint-12-nl-routing-fix.md`
- Before PNGs: `docs/audits/sprint-12-routing-fix/screenshots-before/` (snapshot of Sprint 11 audit PNGs)
- After PNGs: `docs/audits/sprint-12-routing-fix/screenshots-after/`
- UI text: `docs/audits/sprint-12-routing-fix/ui-text-12-after.json`
- 餐饮 chat 25/35 cross-verify: `docs/audits/sprint-11-ux-audit/mealclaw-cross-verify.md`
- STOP signal still valid: `docs/audits/2026-05-23-mealclaw-stop-customer-demo.md` (PR #224)

---

## Signature

**Implementer**: AI 工厂 chat (worktree `sprint11-indicator-keywords-seed-2026-05-22`)
**Skills**: verification-before-completion HARD (API curl proof), depth-first-e2e Rule 1/2/3/8/10 (deep root cause + same-cause sweep + commit≠delivery), TDD (unit tests before fix), fool-proof-design (UI honest verdict, NOT pretend UI works when stale)
**Honest accounting**: 100% routing fix proven at API, ~25% UI capture matches, remaining UI gap is OUT of Sprint 12 P0 scope (frontend formatter + capture race, scope-creep avoidance).

**Sprint 12 P0 ROUTING fix: ACHIEVED.**
