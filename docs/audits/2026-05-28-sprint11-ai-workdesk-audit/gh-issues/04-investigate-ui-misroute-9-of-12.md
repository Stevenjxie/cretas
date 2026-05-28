# [Sprint 13 P0] CRITICAL — 9/12 SalesOwner cases STILL misroute despite Sprint 12 PR #246 fix

**Severity**: P0 — customer demo blocker. STOP signal PR #224 stays valid.
**Source**: AI 工厂 Sprint 11 AI Workdesk audit `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` §Phase D + Reviewer #4 + this audit captures

## Problem

Sprint 12 PR #246 NL routing fix (`d76e3c63a`) verified via direct API curl: 4/4 restaurant phrases route to `RESTAURANT_ECONOMICS_ANALYSIS`. UI re-test 5/23 showed 10/12 PASS.

**5/28 re-test (this audit) shows REGRESSION: only 3/12 reach the correct intent.**

| Case | Expected | Actual | Verdict |
|---|---|---|---|
| `core_qhj_warehouse_mgr__phrase1` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |
| `core_qhj_warehouse_mgr__phrase2` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |
| `core_qhj_warehouse_mgr__phrase3` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |
| `core_qhj_warehouse_mgr__phrase4` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |
| `core_f006_admin__phrase1` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |
| `core_f006_admin__phrase2` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |
| `core_f006_admin__phrase3` | RESTAURANT_ECONOMICS_ANALYSIS | **RESTAURANT_ECONOMICS_ANALYSIS** | ✅ OK |
| `core_f006_admin__phrase4` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |
| `core_warehouse_mgr1_F001__phrase1` | RESTAURANT_ECONOMICS_ANALYSIS | **RESTAURANT_ECONOMICS_ANALYSIS** | ✅ OK |
| `core_warehouse_mgr1_F001__phrase2` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |
| `core_warehouse_mgr1_F001__phrase3` | RESTAURANT_ECONOMICS_ANALYSIS | **RESTAURANT_ECONOMICS_ANALYSIS** | ✅ OK |
| `core_warehouse_mgr1_F001__phrase4` | RESTAURANT_ECONOMICS_ANALYSIS | DAILY_CUSTOMER_FOLLOWUP | ❌ MISROUTE |

3/12 OK = 25% — WORSE than Sprint 12 audit 5/23 (10/12 = 83%).

## Hypothesis (3 candidates to investigate)

### H1: stale cache pollution

`tool_call_cache` rows from before Sprint 12 deploy (5/23) still serve old DAILY_CUSTOMER_FOLLOWUP responses for the same input hashes. Over 5 days more cache rows accumulated.

**Probe**: 
```sql
SELECT cache_key, intent_code_at_cache_time, created_at
FROM tool_call_cache 
WHERE LOWER(cache_key) LIKE '%上月损溢%' OR LOWER(cache_key) LIKE '%损益分析%'
ORDER BY created_at;
```

If pre-5/23 rows exist → purge + re-test.

### H2: SalesOwner auto-mount session pollution

SalesOwnerWorkdesk.vue mounts triggers `triggerFollowupQuery()` (line 588-591) → POST with `intentCode='DAILY_CUSTOMER_FOLLOWUP'`. Subsequent user-click POSTs may inherit:
- sessionId from cookie/store → orchestrator `handleConversationContinuation` (line 179-185) returns cached previous response
- semantic cache key collision

**Probe**: re-test WITHOUT auto-mount — navigate directly to a hypothetical no-auto-trigger Workdesk OR comment out triggerFollowupQuery temporarily in dev build.

### H3: Playwright capture race — spec captures auto-mount request, not user click request

This audit's spec uses `page.on('request', ...)` which captures EVERY POST. Final `executeReqBody` saved is LAST one only.

Smoke evidence: phrase1 captured `executeReqBody: {"userInput":"今日 SO 待发","intentCode":"SPRINT10_SHIPMENT_PENDING_TODAY"}` — that's NOT my injected phrase. It's a 3rd auto-mount POST from SalesOwner's `triggerShipmentPendingQuery` or similar.

**Probe**: filter `page.on('request')` to ONLY capture POST that fires AFTER my click + filter by request body containing my phrase.

## Test design

1. **H1 probe** (5 min): SQL count + sample timestamps on `tool_call_cache`. If pre-fix rows → execute purge + re-run spec.
2. **H2 probe** (1h): inject test flag to skip auto-mount queries on test runs. Re-test.
3. **H3 probe** (30min): rewrite spec request capture to filter by phrase content + post-click timestamp.

## Owner suggestion

**AI 工厂 chat** (own Sprint 11/12 routing context + spec author).

## Effort

3-5h investigation + fix (per hypothesis confirmed)

## Why P0

Per Steve 5/28 brief — customer-visible UX is the audit point. If brief's exact phrases ("帮我看上月损溢异常" etc.) still misroute 75% of the time on UI, **客户演示 100% 失望率仍未消除**. STOP signal PR #224 `be06b9613` continues to block 微信 brief send-out.

Reproduce in browser:
1. Open `http://139.196.165.140:8086/login`
2. Login `qhj_warehouse_mgr` / `123456` / `RES_3101_009`
3. Navigate `/workdesk/sales-owner`
4. Wait for auto-mount to settle (~5s)
5. Type "帮我看上月损溢异常" in chat input
6. Click 发送
7. Observe: response shows "今日客户跟进概览 + 暂无 X5" instead of P&L analysis

## Cross-references

- Audit: `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` §Phase D Ticket #4
- Reviewer ¶4: same audit §Phase C
- Sprint 12 fix that didn't propagate: PR #246 + commit `d76e3c63a`
- Sprint 12 verdict: `docs/audits/sprint-12-routing-fix/verdict.md`
- Sprint 11 baseline: `docs/audits/sprint-11-ux-audit/verdict-2026-05-23.md`
- STOP signal: `docs/audits/2026-05-23-mealclaw-stop-customer-demo.md`
