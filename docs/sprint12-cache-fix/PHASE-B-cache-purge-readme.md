# Phase B — Cache Purge Endpoint + Event Listener (Sprint 12)

**Branch**: `feat/sprint12-cache-fix-2026-05-29`
**Files added**:
- `service/cache/CachePurgeScope.java` — enum (ROUTING / INDICATOR / ALL)
- `service/cache/CachePurgeEvent.java` — Spring application event record
- `service/cache/CachePurgeService.java` — interface
- `service/cache/impl/CachePurgeServiceImpl.java` — implementation
- `service/cache/CachePurgeEventListener.java` — `@TransactionalEventListener(AFTER_COMMIT)` bridge
- `controller/CachePurgeController.java` — admin REST `POST /api/admin/cache/purge`
- `test/.../service/cache/CachePurgeServiceImplTest.java` — 11 unit tests

---

## For sister chats

### sprint12-bi-backend chat — wiring indicator-service-rewrite

When you save / update / delete an `Indicator` (or `IndicatorThreshold` / `IndicatorComputation`), publish a `CachePurgeEvent.indicator(...)` from within the @Transactional method. The listener fires after-commit and invalidates `semantic_cache` for the factory (semantic cache stores execution results that may reference indicator values).

```java
@Autowired ApplicationEventPublisher events;

@Transactional
public Indicator saveIndicator(Indicator ind) {
    Indicator saved = indicatorRepository.save(ind);
    events.publishEvent(CachePurgeEvent.indicator(saved.getFactoryId(), saved.getCode(),
            "indicator-save"));
    return saved;
}
```

If you need synchronous purge (e.g. bulk ETL backfill before serving requests), call `CachePurgeService.purgeIndicator(factoryId, code, reason)` directly.

### sprint12-mealclaw-backend chat — wiring ETL ¥20M backfill

After Phase E ETL backfill completes, call:

```bash
curl -X POST 'http://47.100.235.168:10010/api/admin/cache/purge?scope=INDICATOR&factoryId=RES_3101_009&reason=etl-backfill-2026-05-XX' \
  -H 'Authorization: Bearer <super_admin_token>'
```

Or programmatically:
```java
cachePurgeService.purgeIndicator("RES_3101_009", null, "etl-backfill-2026-05-XX");
```

### Sprint 12 routing fix (AI 工厂 chat) — already-deployed scenario

If a routing fix is deployed and customers may have stale cache, on-call calls:

```bash
curl -X POST 'http://47.100.235.168:10010/api/admin/cache/purge?scope=ROUTING&factoryId=F001&intentCode=RESTAURANT_ECONOMICS_ANALYSIS&reason=post-pr-NNN-deploy'
```

Or scope=ROUTING with no intentCode to purge all routing for the factory.

---

## API contract

`POST /api/admin/cache/purge`

| Query param | Required | Description |
|---|---|---|
| `scope` | yes | `ROUTING` / `INDICATOR` / `ALL` |
| `factoryId` | yes | target factory (no global-purge support via REST; use admin tool) |
| `targetCode` | no | intent_code (ROUTING) or indicator_code (INDICATOR); blank = all of scope |
| `reason` | no | audit string, default `"manual-admin-purge"` |

Response (200):
```json
{
  "success": true,
  "code": 200,
  "message": "缓存清理完成",
  "data": {
    "scope": "ROUTING",
    "factoryId": "F001",
    "targetCode": "RESTAURANT_ECONOMICS_ANALYSIS",
    "reason": "post-pr-279-deploy",
    "rowsDeleted": 7,
    "executedAt": "2026-05-29T10:15:30"
  }
}
```

Authorization: requires role `platform_admin` / `super_admin` / `developer`.

---

## Scope semantics

| Scope | semantic_cache action | tool_call_cache action |
|---|---|---|
| `ROUTING` (with `targetCode`) | DELETE WHERE factory_id=? AND intent_code=? | (skip — TTL self-cleans) |
| `ROUTING` (no `targetCode`) | DELETE WHERE factory_id=? | (skip) |
| `INDICATOR` | DELETE WHERE factory_id=? (factory-wide; no indicator col) | (skip) |
| `ALL` | DELETE WHERE factory_id=? | DELETE WHERE expires_at < NOW() |

`tool_call_cache` has no `factory_id` column (keyed by `session_id + tool_name + parameters_hash`). For scope=ALL we trigger expired-rows cleanup as a best-effort sweep that doesn't disrupt active sessions. For truly global flush, call `ToolCallCacheRepository.deleteAll()` from an admin tool.

---

## Phase A evidence — why this matters

See `docs/sprint12-cache-fix/PHASE-A-evidence.md`. TL;DR:
- `semantic_cache` TTL is 1 hour. After Sprint 12 routing fix deploys, stale rows from the OLD routing live for up to 1 hour before TTL clears them.
- Within that 1 hour, users hitting the same input phrase get the OLD intent_code response (per `feedback_stale_cache_poisoning_survives_backend_fix` HARD rule).
- Manual `POST /api/admin/cache/purge?scope=ROUTING&...` post-deploy closes that window.
- Programmatic `events.publishEvent(CachePurgeEvent.routing(...))` after config save closes it automatically.
