# [Sprint 13 P1] Spec hardening — scoped leak regex + intent-routing assertion + cache-state matrix

**Severity**: P1 (test infra — current spec silently passes 9/12 MISROUTE cases)
**Source**: AI 工厂 Sprint 11 AI Workdesk audit `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` Reviewer Ticket #3

## Problem

Current `sprint11-ai-workdesk-full.spec.ts` (this audit's spec) has 3 measured gaps:

### Gap 1: Leak regex runs on `document.body.innerText` — too coarse

Spec line 310 conflates 3 disjoint regions:
- AI output card (`.formatted-output`) — the actual customer pain point
- Header tag (`Sprint 8 P1` always visible)
- B2BRealDataSection banner (`F999_MOCK 镜像示例` text — intentional disclosure per PR #243)

Result: every case reports `A2 × 9 + A5 × 1` regardless of AI output quality. **Real signals drowned out.**

### Gap 2: NO `expectedIntentCode` assertion → silent MISROUTE PASS

Captures from this audit:
- 9/12 core cases ROUTED TO `DAILY_CUSTOMER_FOLLOWUP` (wrong)
- 3/12 reached `RESTAURANT_ECONOMICS_ANALYSIS` (correct)
- Spec called all 12 PASS because `resultCardPresent === true`

**This is the SAME bug Sprint 12 PR #246 was supposed to fix.** The fix works at API curl level but NOT via UI for 9/12 cases. Spec must catch this.

### Gap 3: No cache-state matrix — stale-cache poisoning caught 1/22 by luck

Steve's 5/28 screenshot bug reproduced only on F001 phrase3 (1 case). The other 21 likely have stale cache too but LLM has re-summarized since. Spec needs cold/warm cache matrix to surface all instances.

## Plus 22 missing pattern categories (from reviewer gap list)

| Code | Pattern | Why important |
|---|---|---|
| B4 | null/undefined literal | `v-html` interpolation common bug |
| B5 | "暂无数据" repeated >3× | reads "everything broken" |
| D2 | currency without unit (`¥1225510`) | seen in captures |
| D3 | raw decimal ratio (`pctOfRevenue:0.2798`) | un-humanized data |
| D4 | ratio expressed as decimal vs % | numeric confusion |
| D5 | UUID exposed (`3215df20-abe3-...`) | internal ID leak |
| D6 | Java/Python language artifacts (`🟢`) | unicode bug |
| E1 | markdown not rendered (literal `### `) | renderer broken |
| G3 | LLM "我不知道 / 抱歉" | not in matrix |
| G5 | LLM repetition (same template 4×) | hallucination signal |
| L1-L4 | toast assertions (sticky / count / loading-timeout / input-not-cleared) | UX gaps |
| M1-M2 | horizontal scroll / text overlap | mobile/visual bugs |
| R1 | same-input determinism (different topItems count 2 runs) | cache-encoding bug |

## Fix scope

Rewrite spec line 278-320 (`Step 9.5 ANTI-PATTERN LEAK SWEEP`):

1. **Scoped selectors**:
   ```ts
   const aiOutput = await page.locator('.formatted-output').first().innerText().catch(() => '');
   const resultCard = await page.locator('.result-card').first().innerText().catch(() => '');
   // Run STRICT regex (B/C/D/E/G categories) on aiOutput
   // Run MEDIUM regex (A1/A2/A7) on resultCard but EXCLUDE .big-banner
   const banner = await page.locator('.big-banner').first().innerText().catch(() => '');
   // Subtract banner text from medium pass
   ```

2. **Intent-routing assertion**:
   ```ts
   const EXPECTED_INTENT: Record<string, string> = {
     '帮我看上月损溢异常': 'RESTAURANT_ECONOMICS_ANALYSIS',
     '损益分析':           'RESTAURANT_ECONOMICS_ANALYSIS',
     '上月成本':           'RESTAURANT_ECONOMICS_ANALYSIS',
     '哪个菜亏钱':         'RESTAURANT_ECONOMICS_ANALYSIS',
     '本月财务情况':       'FINANCE_REPORT_QUERY',  // verify per spec
     // ... add 7 breadth + 4 error expectations
   };
   const intentMatch = cap.executeRespBody.match(/"intentCode"\s*:\s*"([^"]+)"/);
   const actualIntent = intentMatch?.[1];
   const expected = EXPECTED_INTENT[phrase];
   if (expected && actualIntent !== expected) {
     cap.intentMisroute = { expected, actual: actualIntent };
   }
   ```

3. **Cache-state matrix**: each case runs twice — cold (after cache purge) + warm (immediate replay):
   ```ts
   // Need backend endpoint OR DB hook: DELETE FROM tool_call_cache WHERE user_id = ?
   await purgeCacheForUser(acct.userId);
   const cold = await runWorkdeskCase(...);
   const warm = await runWorkdeskCase(...);
   const drift = diffFormattedText(cold.formattedTextInnerText, warm.formattedTextInnerText);
   if (drift.size > THRESHOLD) cap.cacheEncodingDrift = drift;
   ```

4. **22 new pattern regex/assertions** (B4/B5/D2-D6/E1/G3/G5 + L1-L4 + M1-M2 + R1) per reviewer gap list above.

## Test design

This ticket IS a test infra task. Self-validating: re-run new spec on prod → expect:
- 0 false-positives from `Sprint 8 P1` (scoped excluded)
- ≥9 MISROUTE failures captured (current 9/12 silent pass)
- 22 patterns recognized

## Owner suggestion

AI 工厂 chat (this spec author has full context).

## Effort

4-6h (rewrite Step 9.5 + 22 new regex/assertions + intent dictionary + cache matrix logic)

## Cross-references

- Audit: `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` §Phase C ¶1 + Reviewer Ticket #3
- Sprint 12 routing fix that should work: `docs/superpowers/specs/2026-05-23-sprint-12-nl-routing-fix.md` + PR #246
- Spec to rewrite: `web-admin/tests/e2e-customer-journey/sprint11-ai-workdesk-full.spec.ts:278-320`
