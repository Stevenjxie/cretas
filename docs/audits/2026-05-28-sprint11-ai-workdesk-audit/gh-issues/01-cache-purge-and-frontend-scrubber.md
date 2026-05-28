# [Sprint 13 P0] Purge `tool_call_cache` legacy rows + centralize cache-prefix scrubber for 6 Workdesks

**Severity**: P0 (customer-visible JSON dump in production)
**Source**: AI 工厂 Sprint 11 AI Workdesk audit `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` §A1 leak + reviewer Ticket #1

## Problem

Steve's 5/28 screenshot showed customer (qhj_warehouse_mgr) seeing literal JSON dump `"(缓存结果) {data:{...storeName:..., pctOfRevenue:0.2798...}}"` in AI Workdesk response card.

Audit reproduced on `core_warehouse_mgr1_F001__phrase3` — 5 leak patterns hit simultaneously: A1 (cache state) + A2 (sprint version) + A5 (mock marker) + B1 (JSON dump) + C1 (camelCase field × 8 incl `totalPOs`, `includeOverdue`).

## Root cause (per reviewer agent independent finding)

1. Backend `ToolDispatchService.java:239` previously emitted `"(缓存结果) " + cachedResult.get()` raw — **fixed in commit `d76e3c63a` (2026-05-23) to use `parseToolResultToResponse(...)`**.
2. **BUT**: legacy rows in `cretas_prod_db.tool_call_cache.cached_result` still contain pre-fix serialized payloads with the prefix baked in. New cache hits replay these.
3. **Frontend scrubber** added to `WarehouseKeeperWorkdesk.vue` in commit `067b8281b` (2026-05-28) — NOT propagated to 6 sibling Workdesks.

## Scope (6/7 Workdesks vulnerable per Phase D sweep)

Files needing scrubber:
- `web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue:647` (sendQuery)
- `web-admin/src/views/workdesk/FinanceManagerWorkdesk.vue` (sendQuery — verify)
- `web-admin/src/views/workdesk/QualityChiefWorkdesk.vue`
- `web-admin/src/views/workdesk/QualityManagerWorkdesk.vue`
- `web-admin/src/views/workdesk/PurchaserWorkdesk.vue`
- `web-admin/src/views/workdesk/ProductionManagerWorkdesk.vue`

**Better fix (recommended)**: centralize in `web-admin/src/api/smartbi/intent-chat.ts:22` post-response interceptor. One-touch fix for all 7 Workdesks + future ones.

## DB cleanup

```sql
-- On cretas_prod_db
DELETE FROM tool_call_cache
WHERE cached_result LIKE '%(缓存结果)%'
  OR cached_result LIKE '%(缓存命中)%';
-- Backup first: pg_dump tool_call_cache > /tmp/cache_pre_purge_$(date +%s).sql
```

Verify after: re-run AI Workdesk audit spec → 0 A1 hits expected.

## Test design

1. **SQL pre-test**: `SELECT COUNT(*) FROM tool_call_cache WHERE cached_result LIKE '%缓存结果%';` — assert 0 after migration
2. **Playwright**: trigger `/ai-intents/execute` twice with same input (force cache hit on 2nd), assert `.formatted-output` text does NOT match `/^\(缓存结果\)|^\{/`
3. **Unit test**: feed `cleanCachedFormattedText('(缓存结果) {"data":{"message":"hi"}}')` → expect `'hi'`

## Owner suggestion

- AI 工厂 chat (owns Sprint 12 ToolDispatchService fix context) for SQL migration + intent-chat.ts centralized scrubber
- Frontend chat for verification across 6 Workdesks (or AI 工厂 chat does single-touch fix in intent-chat.ts)

## Effort

3-5h (1h migration + 1h centralized scrubber in intent-chat.ts + 1h unit + 1h e2e re-run + verify)

## Cross-references

- Audit: `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md`
- Reproducer screenshot: `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/screenshots/LEAK-core_warehouse_mgr1_F001__phrase3.png`
- Sprint 12 backend fix: commit `d76e3c63a`
- Sibling commit: `067b8281b` (WarehouseKeeperWorkdesk-only scrubber)
- Backend file: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/ToolDispatchService.java:244-258`
