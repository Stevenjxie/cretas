# #304 Root-Cause — SalesOwnerWorkdesk "auto-mount 遮盖用户输入"

**Date**: 2026-05-29
**Chat**: sprint13-frontend-workdesk
**Method**: superpowers:systematic-debugging (reproduce → isolate → diagnose), real headed browser (Playwright MCP, prod 139:8086)
**Issue**: gh #304 — "后端 routing 12/12 真生效但 UI 0/12 用户看得到"

---

## TL;DR — Verdict: **Fork Y (Playwright test artifact)**, NOT the prod bug #304 describes

The #304 headline — *"auto-mount 遮盖用户输入, 真实用户输入'哪个菜亏钱'看到的是 daily-followup, 不是其答案"* — is **FALSE for a real user**. I logged in through the real UI and manually reproduced: typing an economics phrase and clicking 发送 **does** fire the user POST and **does** replace the result card with the `RESTAURANT_ECONOMICS_ANALYSIS` answer.

The `executeReqBody=None ×12` / `executeReqBodyCount=2` / "12/12 PASS" in the audit is a **spec artifact** (loading-disabled button at click time + weak PASS gate), exactly the failure mode predicted by HARD rules `feedback_ui_false_pass_auto_mount_masks_user_input` and `feedback_playwright_capture_race_for_auto_mount_vue`.

**BUT** the customer still sees no business value — for a **different, backend** reason: the `RESTAURANT_ECONOMICS_ANALYSIS` Composite returns `部分数据不可用: 成本刚性` for RES_3101_009 **even for 2025-12** (the month that should hold ¥20.6M). That is backend/data (S13-001), out of the frontend lane.

---

## Phase 1 — Evidence (no fixes before this)

### 1a. Code data-flow (`web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue`, identical on `main` and current branch, last touched #254)

- `userInput = ref('今天该跟谁?')` (L520) — single ref, two-way bound to the `el-input` textarea (L40-46).
- Send button: `:disabled="!userInput.trim()"` + `:loading="loading"` + `@click="sendQuery()"` (L47-53). `sendQuery()` (no arg) → `forceFollowup=false`.
- `sendQuery(false)` (L625) clears `formattedText`, computes `context.month` for economics phrases via `looksLikeRestaurantEconomicsQuery` (L638) + `parseMonthFromInput` (L588), POSTs `{userInput, context:{month}}` **with no intentCode** (L646/569-581), then `formattedText.value = response.formattedText` (L647).
- Auto-mount: `onMounted` → `triggerFollowupQuery()` + `loadTodayPendingShipments()` (L1179-1182) = the 2 POSTs the spec captured.
- `triggerFollowupQuery()` synchronously sets `userInput.value = '今天该跟谁?'` then `sendQuery(true)` (L620-622). This runs **at mount**, so it does not overwrite a later user keystroke (it only clobbers input on 重新分析 / opportunity-refresh re-calls).

### 1b. Backend boundary verification (exact Workdesk request shape)

`POST /RES_3101_009/ai-intents/execute` with the **exact Workdesk body** `{userInput, context:{month}}` (no intentCode):

| phrase | context.month | intentCode | content |
|---|---|---|---|
| 哪个菜亏钱 | 2026-04 | `RESTAURANT_ECONOMICS_ANALYSIS` ✓ | `本月暂无营业数据` (empty month) |
| 哪个菜亏钱 | 2025-12 | `RESTAURANT_ECONOMICS_ANALYSIS` ✓ | `部分数据不可用: 成本刚性` |
| 2025年12月哪个菜亏钱 | 2025-12 | `RESTAURANT_ECONOMICS_ANALYSIS` ✓ | `部分数据不可用: 成本刚性` |

→ **Routing is genuinely fixed** for the Workdesk's real request shape. Backend is the source of "no value" (cost data missing), not the frontend.

### 1c. captures.json (the audit that produced "12/12 PASS")

Per-case (`core_*`): `executeReqBodyCount: 2`, no `executeReqBody`, `executeReqBodyLast = {"userInput":"今日 SO 待发","intentCode":"SPRINT10_SHIPMENT_PENDING_TODAY"}`, `executeRespBody.intentCode = DAILY_CUSTOMER_FOLLOWUP`, `formattedTextInnerText` = daily-followup, `status: PASS`.
The spec is **already the Sprint-12-fixed version** (`waitForUserClickExecutePost` + phrase filter + `page.on('response')`) — yet still recorded only the 2 auto-mount POSTs. So the user-click POST genuinely never fired **in that run**.

---

## Phase A — Real headed-browser reproduction (the decisive experiment)

Logged in via the **real login form** (qhj_warehouse_mgr / 123456 → role warehouse_manager → RES_3101_009), navigated to `/workdesk/sales-owner`, waited for auto-mount to fully settle (send button enabled), then acted as a real user:

| step | action | observed |
|---|---|---|
| settle | wait auto-mount | `sendBtnDisabled:false`, result card = daily-followup, **execCalls=2** |
| type | `哪个菜亏钱` (fill replaces) | `textareaValue:"哪个菜亏钱"`, button enabled |
| click | 发送 | **execCalls 2→3**, result body → `部分数据不可用: 成本刚性` (RESTAURANT_ECONOMICS) |
| type+click | `2025年12月哪个菜亏钱` | **execCalls 3→4**, body → `餐饮经营分析 — 2025年12月 …部分数据不可用` |

**Conclusion: the user POST fires and the result card updates. The component is NOT masking user input.** → **Fork Y**.

### Why the spec saw `count=2` / `executeReqBody=None` / false PASS (artifact root cause)

`runWorkdeskCase` Step 3:
```ts
await page.waitForFunction(() => {
  const btn = document.querySelector('.chat-input button.el-button--primary');
  return btn && !btn.classList.contains('is-loading');
}, { timeout: 90_000 }).catch(() => { /* WARN: initial query never settled */ });
```
The `.catch(()=>{})` **silently swallows** the 90s timeout. The auto-mount query is a 5-tool LLM aggregation; on the rate-limited 22-case run (Aliyun ~30 req/min, concurrent sister chats — see `feedback_e2e_runner_aliyun_rate_limit_cushion`) it frequently exceeded 90s. When it did, the spec proceeded to `fill()`+`click()` on a **still-`:loading`, disabled** send button → click is a no-op → user POST never fires → `count=2` (only the 2 auto-mount POSTs), `executeReqBody` never set.
The PASS gate (`resultCardPresent` true for the auto-mount daily-followup) then reports **PASS** on stale content. This is precisely `feedback_ui_false_pass_auto_mount_masks_user_input`.

---

## Real frontend defects found (in-scope, smaller than #304 claims)

- **D1 (UX, confirmed)** Result-card header hardcoded `今日跟进清单` (L84). During my repro the header read `今日跟进清单` while the body was the economics answer → mislabel.
- **D2 (UX)** Single `formattedText` ref shared by auto-mount default and user query → the daily-followup card is replaced by the user answer (issue suggestion #1: separate the two areas).
- **D3 (latent)** `triggerFollowupQuery` resets `userInput.value` (L621); on 重新分析 / opportunity-refresh it can clobber a user's typed phrase.

## The actual customer blocker (OUT of frontend lane → escalate)

**Backend `RESTAURANT_ECONOMICS_ANALYSIS` Composite returns `部分数据不可用: 成本刚性` for RES_3101_009 for all months incl. 2025-12** — cost data not reaching the Composite (smartbi→cretas COPY drift per project memory). The DOD criterion "user sees ¥20.6M" is blocked here, not in the frontend. Tracks to **S13-001**.

---

## Recommended remediation

1. **Spec (Fork-Y core fix)** — `sprint11-ai-workdesk-full.spec.ts`: do NOT swallow the settle timeout; require auto-mount fully settled before typing; **strong-assert** `intentCode === RESTAURANT_ECONOMICS_ANALYSIS` + `executeReqBody` contains the phrase (FAIL otherwise). Converts the false 12/12 into an honest result.
2. **Component UX** — dynamic result-card header (D1); separate auto-mount default vs user-answer area (D2); stop clobbering `userInput` (D3).
3. **Sweep** — Phase C across the 6 Workdesks for the same auto-mount + single-ref + hardcoded-header pattern.
4. **Escalate** — backend Composite data gap (S13-001) to backend/餐饮 chat; this is the real reason the customer sees no value.

---

## Headed Mode Verification (per `.claude/rules/playwright-headed-mode.md`)

- Tooling: Playwright MCP (`plugin_playwright_playwright`) — headed by default ✓
- Real OS Chromium window, zh-CN, prod 139:8086 ✓ (Chinese rendered, no □)
- Auth: real UI login (not localStorage seed — seed fails because `auth.ts:46 isAuthenticated=!!user.value` is in-memory and races the guard on hard nav) ✓
- Evidence: live `execCalls` counters (2→3→4), live `formattedText`/header reads, API cross-verify via in-page fetch ✓
