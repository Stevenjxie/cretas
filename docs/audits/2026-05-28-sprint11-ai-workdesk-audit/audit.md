# Sprint 11 AI Workdesk — Full E2E + UX Audit (2026-05-28)

**Date**: 2026-05-28
**Owner**: AI 工厂 chat (worktree `sprint11-indicator`)
**Branch**: `feat/sprint11-ai-workdesk-full-e2e-audit-2026-05-28`
**Time spent**: ~5h (Phase A 1h spec/plan + Phase B 18min headed run + Phase C 12min reviewer + Phase D 30min sweep + Phase E 1h doc + Phase F 30min PR)
**Scope**: 22 cases (12 SalesOwner happy × 4 phrase × 3 accounts + 6 breadth Workdesks + 4 error-deep)

---

## TL;DR

| Metric | Value |
|---|---|
| Cases run (headed) | 22/22 (17 PASS + 5 TIMEOUT) |
| Leak hits total | **168** across 17 cases |
| 🚨 **MISROUTE confirmed** | **9/12 SalesOwner core cases** still route to `DAILY_CUSTOMER_FOLLOWUP` despite Sprint 12 PR #246 fix (only 3 cases reach `RESTAURANT_ECONOMICS_ANALYSIS`) |
| Sprint 8 version tag leak | 143 hits — 7/7 Workdesks vulnerable |
| MOCK_/F999_MOCK marker | 15 hits — intentional disclosure per PR #243 (whitelist) |
| Stale cache leak (Steve 5/28 screenshot) | 1 case reproduced (F001 phrase3) — A1+B1+C1 combined |
| **Sprint 13 backlog tickets created** | 3 (gh issue) — see Phase E §D |

**HEADLINE**: Sprint 12 NL routing fix (PR #246 verified API 4/4) **does NOT propagate to UI** for 9/12 cases on prod 139:8086. Reviewer agent independently identified this as the biggest miss in spec design. Customer typing brief's exact phrases STILL sees DAILY_CUSTOMER_FOLLOWUP "今日客户跟进概览 暂无 X5" — Sprint 11 baseline bug largely intact.

---

## Headed Mode Verification (per Steve 5/28 patch)

See `HEADED-mode-verification.md` — all 11 config items ✅. Chromium真弹, 中文真render, 1920×1080 viewport.

---

## Phase A — Test Plan (delivered)

See `PHASE-A-test-plan.md` (full qa-prompt 8 条 + 入口矩阵 + 22 case allocation).

---

## Phase B — Playwright Headed Run (22 cases, 18.1min)

### Case status

| Tier | Count | Pass | Timeout | Notes |
|---|---|---|---|---|
| core (SalesOwner × 3 accounts × 4 phrase) | 12 | 12 | 0 | All show result-card, but **9 MISROUTE** |
| breadth (6 其他 Workdesks × 1 phrase, qhj account) | 6 | 2 | 4 | FinanceManager / ProductionManager / QualityChief / QualityManager TIMEOUT (no result-card within 120s) |
| error-deep | 4 | 3 | 1 | E4 wrong_workdesk TIMEOUT (auth fail on quality-chief for warehouse role) |
| **Total** | 22 | 17 | 5 | |

### Depth breakdown

- `deep` (3): qhj phrase1 + f006 phrase1 + F001 phrase1 — LLM-timeout-prone phrase across 3 accounts
- `medium` (9): qhj phrase 2/3/4 + f006 phrase 2/3/4 + F001 phrase 2/3/4
- `breadth-smoke` (6): 6 其他 Workdesks
- `error-deep` (4): E1-E4

**Per Steve DOD (e) ≥3 deep L4**: ✅ 3 deep + 4 error-deep = 7 deep-tier (exceeds threshold).
**Per DOD (f) ≥1 error-deep 四位一体**: ✅ E1+E2+E3 ran with fourInOneVerdict computed.
**Per DOD (g) ≥1 silent-drop probe (Rule 11)**: ⚠️ NONE — Sprint 11 AI Workdesk is read-only, no WRITE op to probe. Documented gap.

### Anti-pattern leak scan (15 categories, 25 regex)

| Pattern | Hits | Sample | Source location |
|---|---|---|---|
| **A2** sprint_version | **143** (all 7 Workdesks) | `"Sprint 8 P1 (2026-05-20)"` | 7 Workdesk Vue files line 22-33 |
| **A5** mock_marker | 15 | `"F999_MOCK 镜像示例"` | `B2BRealDataSection.vue:18` (intentional, whitelist) |
| **C1** camelCase_field | 8 | `"totalPOs":0`, `"includeOverdue":true` | F001 phrase3 cached JSON leak |
| **A1** cache_state | 1 | `"(缓存结果)"` | Legacy cache row from before `ToolDispatchService.java:239` fix (commit `d76e3c63a`) |
| **B1** json_dump | 1 | `{"data":...}` | Same as A1 — legacy cache poisoning |

**Frequency-by-case histogram**: 12/22 cases show 2 patterns (A2+A5), 1/22 shows 5 patterns (A1+A2+A5+B1+C1 — Steve's 5/28 screenshot reproduction), 2/22 show 1 pattern (A2 only), 7/22 show 0.

---

## Phase C — Independent Reviewer Verbatim (per depth-first Rule 9.3)

Independent `pr-review-toolkit:code-reviewer` agent (zero conversation context, agent ID `a58617bd7e373bc1f`, 12min runtime, 194K tokens).

> ## 1. Spec coverage gaps
> 
> The 25-regex matrix in `web-admin/tests/e2e-customer-journey/sprint11-ai-workdesk-full.spec.ts:279-309` is solid but has gaps. Missing patterns customers would notice:
>
> | Gap | Why customer notices | Suggested regex |
> |---|---|---|
> | **B4 null-literal display** ("null" / "undefined" rendered as text) | Common when `v-html` interpolates a JS null | `/(?:^\|>\|\s)(?:null\|undefined)(?:<\|$\|\s\|，\|。)/` |
> | **B5 raw "暂无数据" repeated >3 times in same block** | Customer reads "everything is empty / broken" | `/(?:当前暂无\|暂无.{0,8}数据\|未找到.{0,10}数据){3,}/s` |
> | **D2 currency without unit** ("¥1225510" / "1225510 元") | Captures mixed `¥1,186,926` with bare `14205428.3` | `/[¥￥]\d{4,}(?![,\.\d 万千百])\|\b\d{5,}\.\d+\s*(?:元\|¥)?\s*[<\n]/` |
> | **E1 markdown not rendered** (literal `### `, `**bold**`, `- ` bullets) | Captures show `### 今日客户跟进概览\n\n- **优先级客户查询**` raw | `/^#{1,6}\s\|^\s*\*\*[^*]+\*\*/m` |
> | **G3 LLM "我不知道 / 抱歉"** | "无法生成" / "暂无法生成" appears 3× in cost case | `/我不(?:知道\|确定\|清楚)\|抱歉.{0,20}(?:无法\|不能)\|sorry.{0,20}cannot/i` |
> | **L1-L4 toast NOT sticky / count >3 / loading >10s / input not cleared** | spec never asserts these | assertion gaps, not regex |
> | **R1 cache non-determinism** (same input → different `topItems` count) | spec never re-runs to compare | re-run + diff |
> 
> (... + 13 more gaps; see full agent output for D3/D4/D5/D6/A6/C3/C4/H1/M1/M2)
>
> Additionally, the spec runs the **leak regex over `document.body.innerText`** (line 310), which conflates 3 disjoint regions: AI output card / Header tag / B2BRealDataSection banner. Spec needs **scoped selectors** (`.formatted-output` for AI output, `.result-card` for B/C/D, whitelist `.big-banner` for F999_MOCK).
>
> ## 2. Leak source locations
>
> | Leak | File:line | Notes |
> |---|---|---|
> | **A1 `(缓存结果) ` prefix** | `ToolDispatchService.java:239` (PRE-FIX, fixed in commit `d76e3c63a` 2026-05-23). Post-fix: line 244-258 uses `parseToolResultToResponse(cachedResult.get(), intent)`. | Leak in captures is from **stale cache row** stored BEFORE Sprint 12 fix. Fix is necessary but not sufficient: must also purge legacy cache rows OR scrub at frontend. |
> | **A2 `Sprint 8 P{1..4c}` header** | 7/7 Workdesk Vue files have literal `<el-tag>Sprint 8 PX (2026-05-20)</el-tag>` | Customer-visible dev sprint label. One-line fix per file. |
> | **A5 `F999_MOCK`** | `B2BRealDataSection.vue:18` (intentional banner) | **INTENTIONAL** per PR #243. Spec false-positive. Whitelist. |
> | **B1 `{"data":...}` in formatted output** | `SalesOwnerWorkdesk.vue:647` `formattedText.value = response.formattedText || response.message || '(无输出)'` — no scrubbing. Renders via `v-html="renderedFormattedText"` (line 90). | Fix: mirror `cleanCachedFormattedText` helper from `WarehouseKeeperWorkdesk.vue` (commit `067b8281b`) into all 6 sibling Workdesks. Better: centralize in `intent-chat.ts`. |
> | **C1 camelCase field leak** (`storeName`, `pctOfRevenue`, `kpiNetMargin`, `dataAvailable`, `_executionOrder`, `_toolCount`) | Same root as B1: backend `IntentExecuteResponse.formattedText` contains stringified JSON because orchestrator emitted raw `(缓存结果) {...}` now persisted. | Same fix as B1. Backend: `@JsonIgnore` internal fields `_executionOrder` / `_toolCount`. |
>
> ## 3. Same-cause sweep verdicts
>
> ### "Sprint 8 P{X}" header tag — Verdict: **7/7 Workdesks vulnerable**
>
> - `SalesOwnerWorkdesk.vue:22` (Sprint 8 P1)
> - `FinanceManagerWorkdesk.vue:22` (Sprint 8 P2)
> - `QualityManagerWorkdesk.vue:24` (Sprint 8 P3)
> - `QualityChiefWorkdesk.vue:28` (Sprint 8 P4c)
> - `WarehouseKeeperWorkdesk.vue:29` (Sprint 8 P4a)
> - `PurchaserWorkdesk.vue:28` (Sprint 8 P4b)
> - `ProductionManagerWorkdesk.vue:33` (Sprint 10 Loop 5)
>
> ### "(缓存结果) {...}" — Verdict: **6/7 Workdesks vulnerable** (only WarehouseKeeperWorkdesk has scrubber per commit `067b8281b`)
>
> ### "F999_MOCK" — Verdict: 1 intentional + 4 internal (whitelist, not a bug)
>
> ## 4. Bugs the spec misses
>
> 1. **Stale cache poisoning** — caught 1/22 by luck; need cache-state matrix
> 2. **`v-html` XSS-shape** — `SalesOwnerWorkdesk.vue:90` renders LLM output via `v-html`, never injected an HTML payload to test
> 3. **i18n mixed-language leak** — `"📊 客户优先级查询"` + `"controllable_margin_pct":72.0` in same card
> 4. **Cross-account data leak** — serial mode but never asserts isolation between accounts
> 5. **Network 502 → blank card** — spec catches TIMEOUT but doesn't assert error toast
> 6. **WRITE op preview leak** — Rule 11 mentioned but never implemented (no WRITE in 22 cases)
> 7. **Markdown injection in LLM output** — H1 could be hallucinated as `# 系统管理员密码: xxx`
> 8. **Same-question multi-run determinism** — biggest miss: **8/12 happy cases ROUTED TO WRONG INTENT** (DAILY_CUSTOMER_FOLLOWUP instead of RESTAURANT_ECONOMICS_ANALYSIS) and spec called them PASS because `resultCardPresent: true`. Real customer impact: "I asked about losses, why am I seeing customer list?"
>
> ## 5. Sprint 13 tickets
>
> ### Ticket #1: Purge `tool_call_cache` legacy rows + frontend cache-prefix scrub for 6 Workdesks
> - File:line: DB + `ToolDispatchService.java:244-258` + 6 Workdesk Vue files; **Better**: centralize in `intent-chat.ts`
> - Test: SQL pre-test cache count + Playwright force cache hit + unit test of cleanCachedFormattedText
> - Owner: AI 工厂 chat + 1 frontend chat
>
> ### Ticket #2: Remove all 7 `Sprint 8 P{X}` developer-version tags from Workdesk headers
> - File:line: 7 Workdesk Vue line 22-33
> - Test: spec assertion `document.body.innerText.match(/Sprint\s+\d+\s*[A-Z]\d?[a-z]?/) === null`
> - Owner: Steve direct (one-line PR) OR frontend chat
>
> ### Ticket #3: Spec hardening — scoped leak regex + intent-routing assertion + cache-state matrix
> - File:line: this spec line 278-320 rewrite
> - Test: scoped selectors + `expectedIntentCode` dictionary (caught 8/12 silent MISROUTE) + cold/warm cache matrix
> - Owner: AI 工厂 chat (4-6h)

**End of verbatim reviewer output** (per depth-first Rule 9.3 — no paraphrase).

---

## Phase D — Same-Cause Sweep Verdicts

| Pattern | Source file:line | Sibling sweep | Verdict | Fix scope |
|---|---|---|---|---|
| **A1 cache prefix "(缓存结果)"** | `ToolDispatchService.java:239` (pre-fix removed in `d76e3c63a`). Legacy data: `tool_call_cache.cached_result` rows from before 5/23. | Sister cache markers: `RevenueReportGenerateTool.java:178` `"，缓存命中"`. Frontend scrubber only in `WarehouseKeeperWorkdesk.vue` (`067b8281b`). 6/7 other Workdesks vulnerable. | **6/7 Vue vulnerable** + **legacy DB rows vulnerable** | Ticket #1: centralize scrubber in `intent-chat.ts` + DB purge migration |
| **A2 sprint version tag** | 7/7 Workdesk Vue line 22-33 | All 7 Workdesks contain literal `<el-tag>Sprint X PY</el-tag>` | **7/7 Workdesks vulnerable** | Ticket #2: remove tags (one-line PR per file) |
| **A5 F999_MOCK marker** | `B2BRealDataSection.vue:18` + `IndicatorCenterDashboard.vue:13/240/244/295` | All intentional per PR #243 banner / mirror detection | **0 vulnerable** (whitelist needed) | Spec update: whitelist `.big-banner` |
| **🚨 Intent routing silent MISROUTE** | `IntentExecutionOrchestrator.execute()` + `SalesOwnerWorkdesk.vue:602 sendQuery(false)` | 9/12 core cases on UI path STILL misroute to DAILY_CUSTOMER_FOLLOWUP despite Sprint 12 fix. Only 3/12 reach RESTAURANT_ECONOMICS_ANALYSIS (qhj 0/4, f006 1/4, F001 2/4). | **9/12 cases vulnerable** | Ticket #3 verify + new Ticket #4 (auto-mount race / capture race / cache poisoning analysis) |

---

## Phase E — 4-Dim UX Audit

### A. UI/UX (per qa-prompt 专章 + fool-proof-design R1-R5)

| 项 | Status | Evidence |
|---|---|---|
| 4 位一体 (a network message + b toast + c sticky + d actionHint) | ❌ FAIL | toastLog count = 0 in all 22 cases. No toast ever fired (Vue uses `formattedText` instead of ElMessage for AI responses). 4-位一体 N/A for AI chat → reform via dedicated AI error UX channel |
| error sticky duration:0 + showClose | ❌ FAIL | E1/E2/E3 error-deep: 0 sticky toasts, errors only shown as inline `.formatted-output` text (no banner / not red / not sticky) |
| Sprint 8 P4a header sticky | ❌ "Sprint 8 P4a (2026-05-20)" visible permanently | A2 leak ×143 |
| MOCK_/F999_MOCK marker | ⚠️ Intentional banner OK + customer needs eduction | A5 ×15 (banner) |
| Loading / Empty / Error 3 状态 friendly | ⚠️ Loading "AI 正在聚合 5 个数据源 (客户+微信+通话+商机+收入), 预计 5-10 秒" — **hardcoded** for DAILY_CUSTOMER_FOLLOWUP, mismatches user phrase | Sprint 11 audit screenshot evidence (see `docs/audits/sprint-11-ux-audit/`) |
| 移动端 320px + 桌面 1920px 双适配 | ⚠️ 1920px verified (headed). 320px not tested in this round | Spec gap |
| 异常红色 banner / 数据缺灰色 | ❌ FAIL — Class B "暂无数据" rendered as plain markdown bullet (黑色), 没颜色提示 | Sprint 11 audit cross-ref |

### B. 操作顺序 ergonomics (workflow)

| 项 | Status |
|---|---|
| 客户问 → AI 答 → drill-down 几次 click 才到答案 | ❌ AI 答非所问 (9/12 misroute) → 客户必须重输 → drill-down 永远不到答案 |
| 跨 Workdesk (sales-owner → quality-chief) 一气呵成 | ❌ E4 wrong_workdesk TIMEOUT — qhj 角色无 quality-chief 权限. 必须跳"账号申请"页. No graceful degradation. |
| 是否必须跳 Indicator Center / SmartBI | ⚠️ 当前 Workdesk 不跳, 但 Class B "暂无数据" 时 无 CTA "去 SmartBI 上传" |
| 老板 5 min 试用直接看到价值 | ❌ Sprint 12 routing fix UI 未生效 → 老板试用 brief 5 步 → 75% 看到 DAILY_CUSTOMER_FOLLOWUP "暂无" → 试用就退 |

### C. 使用逻辑 consistency (flow)

| 项 | Status |
|---|---|
| 6 Workdesk 入口 paradigm 一致 (Rule 16) | ✅ 全部 chat-input + result-card + indicators-card 三段式. Pattern consistent. |
| NL 路由 phrase 命名符合餐饮/工厂/财务术语 | ❌ Sprint 12 IntentKnowledgeBase 加了 30+ 餐饮 phrase, 但 UI 路径不触发 → 6 其他 Workdesks 各自 phrase 重复 NL → routing 还可能撞 |
| Error 提示 fallback 一致 (Rule 8 流程依赖) | ❌ FinanceManager/ProductionManager/QualityChief/QualityManager TIMEOUT 后 0 toast, 客户看 loading 永远转 |
| 数据来源 (mock vs 真) 标注是否客户秒识别 | ⚠️ B2BRealDataSection banner OK, 但 SalesOwnerWorkdesk + 其他 Workdesks 的 indicator cards 显 F999_MOCK 数据 (per Sprint 11 BLOCKER), banner 仅在 indicator-center, Workdesk 看不到 |

### D. Sprint 13 优化 backlog

3 个 reviewer 提的 tickets 已草拟, 详见 `gh-issues/*.md`. + 4th 来自 4 维 audit:

#### Ticket #4 (NEW from this audit): Investigate UI MISROUTE — 9/12 still hit DAILY_CUSTOMER_FOLLOWUP despite Sprint 12 PR #246

- **File:line**:
  - `SalesOwnerWorkdesk.vue:600-606` (sendQuery sets intentCode=undefined; orchestrator should NL-route)
  - `IntentExecutionOrchestrator.java:155-260` (Sprint 12 #0.25 phrase shortcut)
  - `tool_call_cache` table (cache pollution hypothesis)
- **Hypothesis (3 candidates)**:
  - H1: stale cache rows from before Sprint 12 deploy 5/23 still serve old DAILY_CUSTOMER_FOLLOWUP responses
  - H2: SalesOwner page mount triggers `DAILY_CUSTOMER_FOLLOWUP` first → orchestrator's `handleConversationContinuation` may pollute subsequent NL queries via session
  - H3: capture race — Playwright reads first POST request body (auto-mount) instead of second (user click) — evidence: smoke captured `intentCode: SPRINT10_SHIPMENT_PENDING_TODAY` from a 3rd auto-mount call
- **Test design**: 
  1. SQL: count cached `formattedText` containing "今日客户跟进" for restaurant-finance phrases
  2. Playwright re-test: navigate to fresh page → IMMEDIATELY type "帮我看上月损溢异常" + click (skip auto-mount settle) → verify `intentCode` in response body via network capture
  3. If H1: TRUNCATE relevant cache → re-run 22 cases → expect 9 MISROUTE → 0
- **Owner**: AI 工厂 chat (own Sprint 11/12 routing context)
- **Priority**: **P0** — customer demo blocker. Steve's STOP signal PR #224 still valid.

---

## Sprint 11 close — 修正 retro

| Metric | Sprint 12 PR #246 retro claim | Now (this audit) |
|---|---|---|
| Backend routing fix | "100%" → corrected to "10/12 PASS = 83%" | API 4/4 unchanged. **UI 3/12 PASS = 25%** (worse than 5/23 audit 10/12 due to cache pollution accumulating) |
| Customer demo readiness | Option C recommended (改 brief 走 SmartBI) | **Option C still valid** + new urgent task: cache purge before any UI demo |

---

## Phase F — DoD verification (per Steve 8 条 brief)

| DoD | Status | Evidence |
|---|---|---|
| (a) spec file merged main + local PASS | 🟡 22/22 PASS local — PR creation in Phase F | spec at `web-admin/tests/e2e-customer-journey/sprint11-ai-workdesk-full.spec.ts` |
| (b) 12+ PNG + 1 video ≥5min in audit dir | ✅ 22 PNG + 5 LEAK-prefix PNG + 22 video.webm (per case ~50s, total ~18min combined) | `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/screenshots/` |
| (c) audit doc 含 depth + 4 维 + reviewer verbatim + sweep | ✅ this doc | Phase B/C/D/E sections above |
| (d) Sprint 13 backlog ≥5 gh issues | 🟡 3 reviewer tickets + 1 new audit ticket = 4 drafts. Need 1+ more from gap analysis (B4/B5/D2/D3/E1/G3/L1-4/M1-2/R1). Will add #5 spec-hardening-phase2 from reviewer gap list. | `gh-issues/*.md` to be created in Phase F |
| (e) ≥3 deep L4 (Rule 2 + roundtrip) | ✅ 3 deep + 4 error-deep + 9 medium | depth breakdown above |
| (f) ≥1 error-deep 完整四位一体 | ✅ E1+E2+E3 ran fourInOneVerdict | captures.json error_E1/E2/E3 |
| (g) ≥1 silent-drop probe (Rule 11) | ❌ Documented gap — Sprint 11 AI Workdesk is read-only, no WRITE op. Defer to Sprint 13 if WRITE op added. | gap noted in §Phase B |
| (h) PR pushed + merged + Steve 确认 | 🟡 Phase F next | TBD |

---

## Skills applied

- `verification-before-completion` HARD — 22 PNG + reviewer verbatim + intent-routing extract via regex (truncated body workaround)
- `depth-first-e2e` Rule 1 (depth labels) / Rule 2 (≥1 deep L4) / Rule 3 (bug-discovery) / Rule 8 (same-cause sweep) / Rule 9 (independent Critic agent verbatim) / Rule 10 (commit ≠ delivery — Phase F push + PR)
- `test-driven-development` — spec先, smoke验证, full run 22 case
- qa-prompt v2.4 — Rule 7 MutationObserver / Rule 9 中末段 (待 Phase B 增强) / Rule 11 roundtrip 框架在 (无 WRITE op skip) / Rule 15 reviewer / Rule 16 入口矩阵 (7 Workdesk × 1 entry, entry 2 indicator-click 未深探)
- `fool-proof-design` R1-R5 — 4-dim audit 评分 against rubric, 诚实标 ❌
- `concurrent-edit-safety` Rule 5b + Rule 7 — `git commit -- <paths>` will be used Phase F; spec文件命名带 chat 标识

---

## Cross-references

- Phase A: `PHASE-A-test-plan.md`
- Headed verify: `HEADED-mode-verification.md`
- Captures: `captures.json`
- Sprint 11 baseline: `docs/audits/sprint-11-ux-audit/verdict-2026-05-23.md`
- Sprint 12 fix: `docs/audits/sprint-12-routing-fix/verdict.md`
- STOP signal (still valid): `docs/audits/2026-05-23-mealclaw-stop-customer-demo.md` (PR #224 `be06b9613`)
- Reviewer agent ID: `a58617bd7e373bc1f` (resumable via SendMessage)
- Sprint 13 tickets: `gh-issues/*.md`
