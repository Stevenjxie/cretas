# Cross-verify: 餐饮 chat retro 25/35 + Round 7 4/4 PASS claim

**Date**: 2026-05-23
**Owner**: AI 工厂 chat auditor
**Source A** (claim): `docs/superpowers/handoffs/2026-05-22-mealclaw-retrospective.md` (Recovery v2)
**Source B** (evidence): this audit — 12 PNG + 60+72 cell matrices

---

## TL;DR

🔴 **餐饮 chat 25/35 claim DISPROVEN. Round 7 "4 phrases route correctly ✅" only true for curl path with forced intentCode. UI NL path: 0/12 route correctly. Customer demo would crash.**

---

## Claim 1 (餐饮 retro §5): "All 4 phrases route correctly"

> 2026-05-23 11:30 SSH curl on prod 10010:
> qhj_warehouse_mgr login → token len 279
> POST /api/mobile/RES_3101_009/ai-intents/execute
> "帮我看上月损溢异常" → intentCode: RESTAURANT_ECONOMICS_ANALYSIS, status: SUCCESS, hasResult: true
> "损益分析"            → 同 ✅
> "上月成本"            → 同 ✅
> "哪个菜亏钱"          → 同 ✅
> All 4 phrases route correctly. Goal Phase 4 DOD #1 PART 1 (PM-side routing) verified.

### Test setup difference (this audit found the gap)

| Aspect | 餐饮 chat curl | This UI audit |
|---|---|---|
| Endpoint | `POST /ai-intents/execute` | `POST /ai-intents/execute` (same) |
| **intentCode field** | **explicit: `RESTAURANT_ECONOMICS_ANALYSIS`** | **omitted (per UI code path)** |
| User input | phrase same | phrase same |
| Result | RESTAURANT_ECONOMICS_ANALYSIS → "(B) 数据缺" inside that intent | **DAILY_CUSTOMER_FOLLOWUP → "今日客户跟进概览 暂无 X5"** |

### Smoking gun (UI code)

`web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue:593-603`:
```typescript
async function sendQuery(forceFollowup = false) {
    const intentCode = forceFollowup ? 'DAILY_CUSTOMER_FOLLOWUP' : undefined;
    const response = await callIntentExecute(userInput.value, intentCode);
```

When user clicks 发送 (chat input), `sendQuery()` is invoked with `forceFollowup=false`, so **`intentCode` is `undefined`** — request goes through NL intent matching pipeline. NL matching for these 4 phrases on prod (verified by 12 PNG) → DAILY_CUSTOMER_FOLLOWUP.

**The 餐饮 chat curl test verified the BACKEND handler works WHEN given the right intentCode, but skipped the NL matching layer — which is exactly the layer that fails for the customer.**

### Verdict

| | 餐饮 chat claim | This audit |
|---|---|---|
| "4 phrases route correctly" | ✅ (curl forced intentCode) | **🔴 0/12 — UI NL path routes ALL to DAILY_CUSTOMER_FOLLOWUP, NOT RESTAURANT_ECONOMICS_ANALYSIS** |
| Customer experience | (not measured) | **boss sees "今日客户跟进概览 + 暂无 X5", NOT P&L analysis** |

---

## Claim 2 (餐饮 retro 总分): 25/35

| Dimension | 餐饮 chat self-assess | This audit re-score (per 60+72 cell matrix) | Delta |
|---|---|---|---|
| Phase 1: 7 deliverable | ✅ 5/5 | accept 5/5 (audit subagent work is real) | 0 |
| Phase 2: ≥3 PR merged + test | ✅ 5/5 (6 PR / 96% test cov) | accept 5/5 (PRs landed, test cov via Java unit) | 0 |
| Phase 3: 5 rounds E2E P0=0 P1≤2 | ✅ 5/5 (6 rounds) | **2/5** (6 rounds completed but tested via curl with forced intentCode — UI NL path never tested → 9/12 misroute survives all 6 rounds) | **-3** |
| Phase 4: prod ship + retro + 客户演示 | ⏳ partial 3/5 (prod ship ✅, retro ✅, 待客户) | **0/5** (prod ship ≠ demo-ready; curl smoke ≠ UI smoke; this audit proves UI fails) | **-3** |
| 最终硬验证 (literal "帮我看上月损溢异常") | ⏳ PART 1 ✅ (curl), PART 2 待 (客户 prod 跑) | **0/5** (curl PART 1 PASSED but UI re-test PROVES misroute — PART 1 itself was insufficient gate) | **-5** |
| 差异化 5 项 | 4/5 (D 真实可信数据 ✅) | accept 4/5 (data quality work is real even if routing prevents customer seeing it) | 0 |
| Subagent + PM 协调 | 5/5 (7 subagent + PM 全程 own) | accept 5/5 (process worked, but produced wrong DOD) | 0 |
| **TOTAL** | **25-30 / 35** | **16 / 35** | **-9 to -14** |

### Verdict

**25/35 self-assess inflated by ~9 points.** The Phase 3+4+硬验证 sections need re-score to 2/5, 0/5, 0/5 because:
- 6 rounds E2E never tested UI NL path → produces a "PASS" that customer's real interaction fails
- prod ship was technical ship, not demo-ready ship
- 最终硬验证 PART 1 (curl) is necessary but insufficient — without PART 2 (UI), can't claim "verified"

**Re-score: 16/35 (46%) — not the 71% (25/35) claimed.**

---

## Claim 3 (Round 7 retro): "Round 7 root cause: /recognize 走 EarlyPhrase OK 但 /execute bypass — pre-pipeline shortcut 修"

This claim says Round 7 fixed a routing bug where `/execute` bypassed the phrase shortcut. **This audit prove the fix is incomplete or didn't deploy correctly:**

- Round 7 deployed code path: `/execute` should now read EarlyPhrase shortcut → route to RESTAURANT_ECONOMICS_ANALYSIS
- This audit observed: 12/12 UI cases (which call `/execute` per `callIntentExecute()`) route to DAILY_CUSTOMER_FOLLOWUP
- Therefore: either (a) Round 7 fix is in a code path NOT triggered by the UI's request shape (e.g. Round 7 fixed `/recognize` but UI calls `/execute` with no shortcut), or (b) Round 7 deployed correctly but the phrase shortcuts for these 4 phrases aren't in EarlyPhrase, or (c) the fix is overridden by a higher-priority intent (DAILY_CUSTOMER_FOLLOWUP via SalesOwnerWorkdesk context).

**Action needed (added to P0-1 dispatch)**: BI chat must re-test Round 7 fix using UI path, NOT curl path, with explicit per-phrase verification. Add to `2026-05-23-p0-1-smart-indicator-query-intent.md` as P0-1B.

---

## Cross-verify summary

| 餐饮 chat 自报 | 真相 (per 12 PNG + matrices) | Action |
|---|---|---|
| 25/35 总分 | 16/35 (real) | 餐饮 chat update retro |
| Round 7 4/4 phrases route correctly | curl 4/4, UI 0/12 | re-test Round 7 via UI; add to P0-1B |
| Composite shipped done | code shipped, content blank, **route bug HIDES the content blank** | P0-3 unchanged (Option A rebuild OR B path B) |
| LLM 防幻觉 ✅ | Some prompt constraints exist BUT 3/12 cases got `Skill 执行失败` shown as PLAIN BLACK TEXT (no visual error marker) — user can't tell it's an error | P0-2 add UI error banner enforcement |
| Phase 4 客户演示 ready (after Steve schedule) | NOT ready — UI fails 100% on literal brief phrases | STOP signal still valid (PR #224 merged) |

---

## Sprint 11 progress re-rating (after this audit)

| Aspect | 之前 claim (this AI 工厂 chat 真实 retro doc) | **Updated (this audit)** | Delta |
|---|---|---|---|
| Sprint 11 progress | ~10% | **~5%** (downgrade — even backend "Tools shipped" is invisible to UI because of routing bug) | -5pp |
| 4 BI Tool ship | code LIVE, 3/4 callable | unchanged (Tools work via direct API; only UI routing fails) | 0 |
| Composite Tool | code shipped, content blank | unchanged | 0 |
| **NEW finding** | (not previously identified) | **Route bug: NL pipeline routes restaurant-finance phrases to DAILY_CUSTOMER_FOLLOWUP regardless of intent. Customer types brief's exact phrase → sees customer-followup UI.** | +1 critical |
| SalesOwner Workdesk UI | "OK 但显示 mirror data" | **"functionally broken for non-default phrases — only `forceFollowup=true` path works"** | -1 verdict |

---

## What 餐饮 chat (and others) should do

1. **餐饮 chat**: update retro § Recovery v2 §5 "All 4 phrases route correctly" with caveat: "**curl path only — UI NL path 0/12 routes to RESTAURANT_ECONOMICS_ANALYSIS, see `docs/audits/sprint-11-ux-audit/`**". Re-score 25/35 → 16/35.
2. **BI chat**: P0-1B add — re-test Round 7 fix via UI path (not curl). Specifically test SalesOwnerWorkdesk chat input with 4 phrases + verify intentCode in response.
3. **AI 工厂 chat (this)**: P0-2 add error UX requirement — `Skill 执行失败` MUST go through `errorMessage.value` (red el-alert banner sticky), NOT into `formattedText.value` (black plain text).
4. **Steve**: STOP signal stays. Wechat invite cannot be sent — customer demo would crash on the brief's literal 5 步 phrases. Goal v5 P6 verdict below.

---

## Cross-references

- 餐饮 chat retro (target of disprove): `docs/superpowers/handoffs/2026-05-22-mealclaw-retrospective.md`
- 餐饮 chat curl audit (where claim came from): `docs/audits/sprint-11-mealclaw-output-quality-deep-audit.md`
- This audit's 12 PNG: `docs/audits/sprint-11-ux-audit/screenshots/`
- 60-cell output: `output-quality-matrix.md`
- 72-cell UX: `ux-state-matrix.md`
- Round 7 phrase shortcut PR: #204 (deployed but UI test contradicts effectiveness)
- STOP signal already merged: PR #224 `be06b9613`
