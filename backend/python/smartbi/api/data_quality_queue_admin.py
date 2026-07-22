"""Shared data quality queue admin API (Phase A A-3 Task 3.2 — list endpoint).

Future tasks will extend this module:
  Task 3.3: resolve/reject endpoints
  Task 3.4: batch-resolve endpoint
  Task 3.6: history endpoint

Per W0.4 binding findings:
  Finding 1: source_upload_id has no FK → LEFT JOIN must handle NULL uploaded_by
              gracefully (item.submitter can be None).
  Finding 2: created_at is nullable despite DEFAULT now() → use .isoformat() if
              not None pattern throughout.
  Finding 3: RLS FORCE on entity_resolution_admin_queue requires
             SELECT set_config('app.factory_id', $1, false) per query.
             Phase A: require factoryId (cross-factory deferred to Phase B —
             would require BYPASSRLS or SECURITY DEFINER function).
  Finding 4: Default to status='PENDING' for partial-index hit
             (idx_eraq_pending_priority).
  Finding 7: VALID_ENTITY_TYPES hardcoded from DB CHECK constraint (8 values).
"""
from __future__ import annotations

import logging
import os
from typing import Any, Dict, List, Optional, Tuple

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel

from smartbi.canonical.dish_confirm_service import (
    build_dish_review_payload,
    confirm as dish_confirm,
    reject as dish_reject,
)
from smartbi.canonical.provenance._admin_auth import require_admin
from smartbi.config import get_pg_pool

logger = logging.getLogger(__name__)
router = APIRouter()

# W0.4 finding 7: entity_type values from DB CHECK constraint.
# Must stay in sync with entity_resolution_admin_queue.entity_type CHECK.
# P4a (V20260602_04): 'dish' added — cross-store canonical dish merge proposals.
VALID_ENTITY_TYPES = frozenset({
    "store",
    "product",
    "staff",
    "ingredient",
    "dish",
    "shape_detection",
    "sheet_merge",
    "period_inference",
    "field_conflict",
})

# Subset of entity_type values that are genuine name-resolution entities with a
# canonical dim table (dim_store / dim_product / dim_staff) AND that the
# entity_resolution_history CHECK constraint accepts (V20260426_01 created it as
# only 'store'/'product'/'staff' and it was never widened, unlike the queue's).
# These mirror EntityType in
# smartbi.canonical.entity_resolution.orchestrator. The other queue-only types
# (ingredient / shape_detection / sheet_merge / period_inference / field_conflict)
# have no dim_* table and would violate the history CHECK, so admin confirms of
# those types are NOT graduated into entity_resolution_history.
# Used as the allow-list that gates the f-string-interpolated dynamic table name
# (dim_{entity_type}) — only values in this frozen set are ever interpolated.
HISTORY_GRADUATABLE_ENTITY_TYPES = frozenset({"store", "product", "staff"})


async def _fetch_queue_items(
    pool,
    factory_id: str,
    entity_type: Optional[str],
    status: str,
    page: int,
    page_size: int,
) -> Tuple[List[Dict[str, Any]], int]:
    """Run paginated query joining uploads for submitter info.

    W0.4 finding 3: GUC set_config is issued inside the same connection
    (same asyncpg connection context) as the SELECT, so RLS FORCE sees the
    correct factory_id and returns rows (rather than silently returning 0).

    W0.4 finding 1: LEFT JOIN so rows without a matching upload (NULL
    source_upload_id or orphaned FK) still appear; submitter becomes None.

    W0.4 finding 2: created_at / admin_at serialised as .isoformat() if
    the value is not None.
    """
    where_clauses: List[str] = ["q.factory_id = $1"]
    params: List[Any] = [factory_id]
    p_idx = 2

    if entity_type:
        if entity_type not in VALID_ENTITY_TYPES:
            raise HTTPException(
                status_code=400,
                detail=f"未知 entity_type: {entity_type!r}，有效值: {sorted(VALID_ENTITY_TYPES)}",
            )
        where_clauses.append(f"q.entity_type = ${p_idx}")
        params.append(entity_type)
        p_idx += 1

    where_clauses.append(f"q.status = ${p_idx}")
    params.append(status)
    p_idx += 1

    where_sql = "WHERE " + " AND ".join(where_clauses)
    offset = (page - 1) * page_size

    async with pool.acquire() as conn:
        # W0.4 finding 3: MUST set GUC inside an EXPLICIT TRANSACTION before
        # the SELECT — set_config(..., is_local=true) is transaction-scoped,
        # so without an explicit txn wrapper asyncpg auto-commits the SELECT
        # set_config call, the GUC is wiped, and the next query sees no
        # app.factory_id → RLS FORCE silently returns 0 rows. Pattern matches
        # smartbi/agent/budget_tracker.py:101-107 and narrative_cache.py:83-88.
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )

            rows = await conn.fetch(
                f"""
                SELECT q.id,
                       q.factory_id,
                       q.entity_type,
                       q.raw_name,
                       q.candidate_entity_id,
                       q.confidence,
                       q.decided_by_agent,
                       q.status,
                       q.priority,
                       q.source_upload_id,
                       q.admin_user,
                       q.admin_at,
                       q.admin_action,
                       q.admin_resolved_to_entity_id,
                       q.reasoning,
                       q.extra,
                       q.created_at,
                       u.uploaded_by AS submitter
                  FROM entity_resolution_admin_queue q
                  LEFT JOIN smart_bi_pg_excel_uploads u
                         ON u.id = q.source_upload_id
                  {where_sql}
                 ORDER BY q.priority DESC, q.created_at DESC
                 LIMIT ${p_idx} OFFSET ${p_idx + 1}
                """,
                *params,
                page_size,
                offset,
            )

            total = await conn.fetchval(
                f"""
                SELECT COUNT(*)
                  FROM entity_resolution_admin_queue q
                  {where_sql}
                """,
                *params,
            )

            # P4a (Task 8): for entity_type='dish', enrich each item with the
            # human-review payload (fool-proof Rule 2 — show member dishes +
            # which stores carry each name + sales sample + confidence so a human
            # can eyeball "same dish?" before confirming). Done INSIDE the same
            # transaction so app.factory_id GUC is still set for the FORCE-RLS
            # JOINs (dim_store / fact_pos_* / agg_product). Generic shape for the
            # other 8 entity types is unchanged.
            dish_reviews: Dict[int, Dict[str, Any]] = {}
            if entity_type == "dish":
                for r in rows:
                    try:
                        dish_reviews[int(r["id"])] = await build_dish_review_payload(
                            conn, factory_id, r["extra"]
                        )
                    except Exception as exc:  # noqa: BLE001 — enrichment is best-effort
                        # Never fail the whole list because one item's sales JOIN
                        # blew up; the item still shows raw_name + confidence.
                        logger.warning(
                            "dish review enrichment failed for queue id=%s "
                            "(factory=%s): %s",
                            r["id"], factory_id, exc,
                        )

    items: List[Dict[str, Any]] = [
        {
            "id": int(r["id"]),
            "factoryId": r["factory_id"],
            "entityType": r["entity_type"],
            "rawName": r["raw_name"],
            "candidateEntityId": (
                int(r["candidate_entity_id"])
                if r["candidate_entity_id"] is not None
                else None
            ),
            "confidence": (
                float(r["confidence"])
                if r["confidence"] is not None
                else None
            ),
            "decidedByAgent": r["decided_by_agent"],
            "status": r["status"],
            "priority": r["priority"],
            "sourceUploadId": (
                int(r["source_upload_id"])
                if r["source_upload_id"] is not None
                else None
            ),
            # W0.4 finding 1: LEFT JOIN — uploaded_by (BIGINT) may be None
            "submitter": (
                str(r["submitter"]) if r["submitter"] is not None else None
            ),
            "adminUser": r["admin_user"],
            # W0.4 finding 2: admin_at is nullable
            "adminAt": (
                r["admin_at"].isoformat() if r["admin_at"] is not None else None
            ),
            "adminAction": r["admin_action"],
            "adminResolvedToEntityId": (
                int(r["admin_resolved_to_entity_id"])
                if r["admin_resolved_to_entity_id"] is not None
                else None
            ),
            "reasoning": r["reasoning"],
            "extra": r["extra"],
            # W0.4 finding 2: created_at is nullable despite DEFAULT now()
            "createdAt": (
                r["created_at"].isoformat()
                if r["created_at"] is not None
                else None
            ),
            # P4a (Task 8): dish-only enrichment (None for other entity types).
            "dishReview": dish_reviews.get(int(r["id"])),
        }
        for r in rows
    ]

    return items, int(total or 0)


@router.get("/list")
async def list_queue(
    request: Request,
    factoryId: Optional[str] = Query(
        None, description="工厂 ID，必填（Phase A 不支持跨工厂查询）"
    ),
    entityType: Optional[str] = Query(
        None, description="过滤 entity_type（store/product/staff/ingredient/…）"
    ),
    status: Optional[str] = Query(
        None, description="过滤 status，默认 PENDING（命中 idx_eraq_pending_priority）"
    ),
    page: int = Query(1, ge=1, description="页码，从 1 开始"),
    pageSize: int = Query(50, ge=1, le=200, description="每页条数（最大 200）"),
) -> Dict[str, Any]:
    """Paginated admin view of the entity resolution admin queue.

    Phase A constraint: factoryId is required.  Cross-factory view (where
    factoryId is omitted) requires BYPASSRLS or SECURITY DEFINER and is
    deferred to Phase B.

    W0.4 binding:
      - RLS GUC set_config issued inside same connection as SELECT (finding 3)
      - Default status='PENDING' for partial-index hit (finding 4)
      - VALID_ENTITY_TYPES validation on entityType (finding 7)
    """
    require_admin(request, action_name="数据质量队列查询")

    if not factoryId or not factoryId.strip():
        raise HTTPException(
            status_code=400,
            detail="factoryId 不能为空 (Phase A 不支持跨工厂查询，Phase B 实现)",
        )
    factoryId = factoryId.strip()

    # Phase B fix: factory_super_admin / permission_admin / factory_admin
    # 只能查自己工厂; 仅 platform_admin 跨工厂. 之前 require_admin 接受任意 admin
    # tier 导致 F002 admin 能查 R_BEJ data (deep test round 2 finding).
    role = getattr(request.state, "role", None)
    jwt_factory_id = getattr(request.state, "factory_id", None)
    if role != "platform_admin" and jwt_factory_id and factoryId != jwt_factory_id:
        raise HTTPException(
            status_code=403,
            detail=f"非 platform_admin 仅可查询自己工厂的队列 (当前工厂 {jwt_factory_id!r})",
        )

    # W0.4 finding 4: default to PENDING to hit the partial index
    effective_status = (status or "PENDING").upper()

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库不可用")

    items, total = await _fetch_queue_items(
        pool,
        factoryId.strip(),
        entityType,
        effective_status,
        page,
        pageSize,
    )

    return {
        "items": items,
        "total": total,
        "page": page,
        "pageSize": pageSize,
    }


# ---------------------------------------------------------------------------
# Task 3.3: resolve / reject endpoints + 4-eye gate
# ---------------------------------------------------------------------------

class ResolveBody(BaseModel):
    action: str  # 'confirm' | 'create_new'
    resolvedToEntityId: Optional[int] = None
    notes: Optional[str] = None
    # P4a (Task 8): for entity_type='dish', the regularised canonical display
    # name the human entered/edited. 'create_new' → new dim_canonical_dish with
    # this name; 'confirm' with resolvedToEntityId → attach members to that
    # existing canonical_dish_id. Ignored for non-dish entity types.
    canonicalName: Optional[str] = None


class RejectBody(BaseModel):
    reason: str


async def _get_queue_item(pool, item_id: int) -> Optional[Dict[str, Any]]:
    """Fetch a single queue row + submitter via LEFT JOIN.

    W0.4 finding 1: LEFT JOIN — submitter may be NULL when source_upload_id
    is NULL or the upload row was deleted. Caller treats None submitter as
    "no conflict possible → allow" (cannot enforce 4-eye without a submitter).

    Security note: this SELECT runs without setting app.factory_id GUC because
    we look up by integer id (bounded), only admins reach this path (require_admin
    is the security boundary), and the subsequent UPDATE sets the GUC inside a
    transaction. Phase A acceptable; Phase B can add BYPASSRLS SECURITY DEFINER.
    """
    async with pool.acquire() as conn:
        async with conn.transaction():
            r = await conn.fetchrow(
                """
                SELECT q.id,
                       q.factory_id,
                       q.status,
                       q.entity_type,
                       u.uploaded_by AS submitter
                  FROM entity_resolution_admin_queue q
                  LEFT JOIN smart_bi_pg_excel_uploads u
                         ON u.id = q.source_upload_id
                 WHERE q.id = $1
                """,
                item_id,
            )
    if not r:
        return None
    return {
        "id": int(r["id"]),
        "factoryId": r["factory_id"],
        "status": r["status"],
        # P4a (Task 8): needed so resolve/reject can branch to the dish path.
        "entityType": r["entity_type"],
        # W0.4 finding 1: uploaded_by (BIGINT) may be None
        "submitter": str(r["submitter"]) if r["submitter"] is not None else None,
    }


async def _get_admin_count_for_factory(factory_id: str) -> int:
    """Call Java GET /api/internal/users/admin-count?factoryId=X.

    2026-04-29 (Phase B): Java endpoint moved from /api/mobile/{factoryId}/users/admin-count
    to /api/internal/users/admin-count, so JwtAuthInterceptor's existing /api/internal/*
    handler validates X-Internal-Key against INTERNAL_API_SECRET. No JWT required.

    Any failure (network, timeout, non-200) falls back to 2 = safer default (4-eye enforced).
    Returns the actual admin count when Java is reachable + auth succeeds.
    """
    import httpx
    java_base = os.environ.get("JAVA_API_BASE", "http://localhost:10010")
    java_url = f"{java_base}/api/internal/users/admin-count"
    internal_key = os.environ.get("INTERNAL_API_SECRET", "")
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.get(
                java_url,
                params={"factoryId": factory_id},
                headers={"X-Internal-Key": internal_key},
            )
            if resp.status_code != 200:
                logger.warning(
                    "admin-count for %s returned %s, defaulting to 2 (safer)",
                    factory_id, resp.status_code,
                )
                return 2
            data = resp.json()
            count = int(data.get("data", {}).get("count", 2))
            return count
    except Exception as exc:
        logger.warning(
            "Failed to fetch admin count for %s: %s, defaulting to 2 (safer)",
            factory_id, exc,
        )
        return 2


async def _update_queue_resolved(
    pool,
    item_id: int,
    factory_id: str,
    action: str,
    resolved_to_entity_id: Optional[int],
    admin_user: str,
    notes: Optional[str],
    single_admin_degraded: bool,
) -> Optional[Dict[str, Any]]:
    """Single transaction: SET GUC + UPDATE queue status to CONFIRMED.

    W0.4 finding 3: set_config MUST be inside conn.transaction() so RLS FORCE
    sees the GUC for the duration of the UPDATE (transaction-scoped local GUC).

    Race condition: UPDATE conditioned on status='PENDING' so concurrent resolves
    return no rows (fetchrow → None → caller maps to 409).

    Returns the updated row {id, raw_name, entity_type} on success (so callers
    can graduate an admin-confirmed match into entity_resolution_history without
    a second fetch), or None if no PENDING row was updated (race / already done).
    """
    async with pool.acquire() as conn:
        async with conn.transaction():
            # W0.4 finding 3: GUC inside transaction
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )

            # Two static SQL templates (no f-string interpolation) for safety —
            # reviewer Task 3.3 concern: f-string + dynamic SQL is a foot-gun even
            # when current values are constant.
            if single_admin_degraded:
                updated = await conn.fetchrow(
                    """
                    UPDATE entity_resolution_admin_queue
                       SET status                    = 'CONFIRMED',
                           admin_action              = $1,
                           admin_user                = $2,
                           admin_at                  = NOW(),
                           admin_resolved_to_entity_id = $3,
                           extra                     = COALESCE(extra, '{}'::jsonb)
                                                       || jsonb_build_object(
                                                            'single_admin_degraded', true,
                                                            'submitter_was_resolver', true
                                                          )
                     WHERE id = $4
                       AND status = 'PENDING'
                    RETURNING id, raw_name, entity_type
                    """,
                    action, admin_user, resolved_to_entity_id, item_id,
                )
            else:
                updated = await conn.fetchrow(
                    """
                    UPDATE entity_resolution_admin_queue
                       SET status                    = 'CONFIRMED',
                           admin_action              = $1,
                           admin_user                = $2,
                           admin_at                  = NOW(),
                           admin_resolved_to_entity_id = $3
                     WHERE id = $4
                       AND status = 'PENDING'
                    RETURNING id, raw_name, entity_type
                    """,
                    action, admin_user, resolved_to_entity_id, item_id,
                )
    if updated is None:
        return None
    return {
        "id": int(updated["id"]),
        "raw_name": updated["raw_name"],
        "entity_type": updated["entity_type"],
    }


async def _record_admin_confirm_history(
    pool,
    factory_id: str,
    entity_type: str,
    raw_name: str,
    resolved_to_entity_id: int,
) -> None:
    """Graduate an admin-confirmed resolution into entity_resolution_history.

    The automated orchestrator (orchestrator.py:_record_history) records every
    machine decision here so the transitive read path resolves the same name at
    0 cost next upload. The admin's manual confirmation previously wrote ONLY the
    queue's admin_resolved_to_entity_id, so the human gold-standard correction was
    discarded and the same name re-ran the whole agent chain + re-queued forever.
    This mirrors the orchestrator's upsert (same columns + ON CONFLICT key) with
    confidence=1.0 (human gold, well above the read path's >0.85 threshold) and
    decided_by_agent='admin'.

    SECURITY: entity_type is interpolated into the dim table name, so it MUST be
    validated against HISTORY_GRADUATABLE_ENTITY_TYPES (store/product/staff)
    BEFORE this call; the caller skips non-graduatable types. We re-check here as
    defence in depth before interpolating.

    RLS: entity_resolution_history has FORCE ROW LEVEL SECURITY, so app.factory_id
    must be set inside the same transaction as the upsert (mirrors the GUC pattern
    in _update_queue_resolved / _fetch_queue_items).

    This is a best-effort learning side-effect: the caller wraps it in try/except
    so a failure here NEVER blocks or rolls back the admin confirm.
    """
    if entity_type not in HISTORY_GRADUATABLE_ENTITY_TYPES:
        # Defence in depth — caller already gates, but never interpolate an
        # unvalidated entity_type into the dynamic dim_* table name.
        logger.warning(
            "Skipping history graduation: entity_type %r not graduatable "
            "(factory=%s raw_name=%r)",
            entity_type, factory_id, raw_name,
        )
        return

    # Safe now: entity_type is one of store/product/staff (known dim_* tables).
    entity_table = f"dim_{entity_type}"
    id_column = f"{entity_type}_id"

    async with pool.acquire() as conn:
        async with conn.transaction():
            # RLS FORCE: GUC inside the transaction so both the dim_* lookup and
            # the history upsert see the correct factory_id.
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )

            # b_name = canonical name of the resolved entity (spec C-NEW-2:
            # lookup from dim, not self-ref). Fallback to raw_name if the dim
            # row is not visible, exactly like the orchestrator.
            row = await conn.fetchrow(
                f"SELECT name FROM {entity_table} "
                f"WHERE factory_id = $1 AND {id_column} = $2",
                factory_id,
                resolved_to_entity_id,
            )
            b_name = row["name"] if row else raw_name

            await conn.execute(
                """
                INSERT INTO entity_resolution_history
                  (factory_id, entity_type, a_name, b_name, b_entity_id,
                   confidence, decided_by_agent, reasoning)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                ON CONFLICT (factory_id, entity_type, a_name, b_entity_id) DO UPDATE SET
                  confidence = GREATEST(
                      entity_resolution_history.confidence, EXCLUDED.confidence
                  ),
                  decided_by_agent = EXCLUDED.decided_by_agent,
                  reasoning = EXCLUDED.reasoning
                """,
                factory_id,
                entity_type,
                raw_name,
                b_name,
                resolved_to_entity_id,
                1.0,  # human gold — well above the read path's >0.85 threshold
                "admin",
                "human-confirmed via admin queue",
            )


# ---------------------------------------------------------------------------
# P4a (Task 8): dish entity_type path — reuse dish_confirm_service (the same
# core the CLI confirm_dish_canonical uses) so the canonical write + history
# graduation live in ONE place.
# ---------------------------------------------------------------------------

async def _resolve_dish(
    pool,
    item_id: int,
    factory_id: str,
    action: str,
    resolved_to_entity_id: Optional[int],
    canonical_name: Optional[str],
    admin_user: str,
) -> int:
    """Confirm a dish merge proposal via dish_confirm_service.confirm.

    The dish service does ALL of: create/attach dim_canonical_dish, set
    dim_product.canonical_dish_id, recount, mark queue CONFIRMED, and the
    fail-open entity_resolution_history graduation (#389). We acquire a conn,
    set the FORCE-RLS GUC, and delegate.

      action='create_new' → new canonical named canonical_name (or the
                            proposal's suggested name when None).
      action='confirm'    → attach members to existing canonical
                            (resolved_to_entity_id = canonical_dish_id).

    Returns the service's status code (0 success; 1 precondition failed). The
    service raises on a fail-loud canonical/link write failure (#390).
    """
    target_canonical_id = (
        resolved_to_entity_id if action == "confirm" else None
    )
    async with pool.acquire() as conn:
        # FORCE RLS: set the tenant GUC for this connection before the service
        # runs its own transaction (mirrors the GUC pattern elsewhere here; the
        # dish service does NOT set tenant itself by design).
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        return await dish_confirm(
            conn,
            factory_id,
            item_id,
            canonical_name=canonical_name,
            canonical_id=target_canonical_id,
            admin_user=admin_user,
        )


async def _reject_dish(
    pool,
    item_id: int,
    factory_id: str,
    reason: str,
    admin_user: str,
) -> int:
    """Reject a dish merge proposal via dish_confirm_service.reject."""
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        return await dish_reject(
            conn, factory_id, item_id, reason, admin_user=admin_user
        )


@router.post("/{id}/resolve")
async def resolve_queue(
    request: Request,
    id: int,
    body: ResolveBody,
) -> Dict[str, Any]:
    """Resolve a PENDING queue item (confirm entity match or create new entity).

    4-eye gate: if the current admin is the same person who submitted the upload
    that generated this queue item AND the factory has more than one admin, a 403
    is returned — a second admin must resolve it.

    Single-admin degradation: if the factory has only 1 admin (admin_count == 1)
    the same-person check is bypassed; the resolution is allowed and tagged in
    extra JSONB with single_admin_degraded=true for audit purposes.

    Race condition safety: UPDATE conditioned on status='PENDING' so two
    concurrent requests cannot both succeed — the second returns 409.
    """
    require_admin(request, action_name="数据质量队列处理")

    if body.action not in ("confirm", "create_new"):
        raise HTTPException(
            status_code=400,
            detail=f"action 必须是 'confirm' 或 'create_new', 收到 {body.action!r}",
        )

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库不可用")

    item = await _get_queue_item(pool, id)
    if not item:
        raise HTTPException(status_code=404, detail="队列项不存在")

    # Phase B cross-factory tightening: non-platform_admin 仅能操作自己工厂.
    role = getattr(request.state, "role", None)
    jwt_factory_id = getattr(request.state, "factory_id", None)
    if role != "platform_admin" and jwt_factory_id and item["factoryId"] != jwt_factory_id:
        raise HTTPException(
            status_code=403,
            detail=f"非 platform_admin 仅可处理自己工厂的队列项 (该项属于 {item['factoryId']!r})",
        )

    if item["status"] != "PENDING":
        raise HTTPException(
            status_code=409,
            detail=f"队列项当前状态为 {item['status']}，无法处理（非 PENDING）",
        )

    # Determine current user: prefer numeric user_id (matches uploaded_by BIGINT),
    # fall back to username string.
    current_user = str(
        getattr(request.state, "user_id", None)
        or getattr(request.state, "username", "")
        or ""
    )
    submitter = item["submitter"]  # None when LEFT JOIN found no upload row

    single_admin_degraded = False
    # W0.4 finding 1: if submitter is None, treat as "no conflict known" → allow.
    if current_user and submitter and current_user == submitter:
        admin_count = await _get_admin_count_for_factory(item["factoryId"])
        if admin_count > 1:
            raise HTTPException(
                status_code=403,
                detail="您是该队列项的提交者，需要另一位管理员审核（4-eye 原则）",
            )
        # admin_count == 1 → single-admin degradation, allow but tag
        single_admin_degraded = True
        logger.info(
            "Single-admin degradation: factory=%s item=%d user=%s",
            item["factoryId"], id, current_user,
        )

    # P4a (Task 8): dish proposals take the canonical-merge path, NOT the generic
    # queue-status update. dish_confirm_service.confirm does the whole thing in
    # one place (create/attach canonical + link dim_product + recount + mark
    # CONFIRMED + fail-open history graduation). It fails LOUD (#390) on a
    # canonical/link write rejection → surfaced to the admin as a 500/error.
    if item.get("entityType") == "dish":
        if body.action == "confirm" and body.resolvedToEntityId is None:
            raise HTTPException(
                status_code=400,
                detail="dish confirm 需指定 resolvedToEntityId (要挂到的 canonical_dish_id); "
                       "若要新建 canonical 请用 action='create_new'",
            )
        rc = await _resolve_dish(
            pool,
            id,
            item["factoryId"],
            body.action,
            body.resolvedToEntityId,
            body.canonicalName,
            current_user or "admin",
        )
        if rc != 0:
            # Precondition failure inside the service (already non-PENDING / no
            # members / missing). We re-checked PENDING above, so this is a race.
            raise HTTPException(
                status_code=409,
                detail="菜品归一提议已被处理或无成员，无法确认（请刷新列表）",
            )
        return {"resolved": True, "singleAdminDegraded": single_admin_degraded}

    updated = await _update_queue_resolved(
        pool,
        id,
        item["factoryId"],
        body.action,
        body.resolvedToEntityId,
        current_user,
        body.notes,
        single_admin_degraded,
    )

    if updated is None:
        raise HTTPException(
            status_code=409,
            detail="队列项已被其他管理员处理（race condition），请刷新列表",
        )

    # The confirm transaction has committed above. Now best-effort graduate the
    # admin's gold-standard confirmation into entity_resolution_history so the
    # transitive read path resolves this name at 0 cost next upload (instead of
    # re-running the whole agent chain + re-queuing forever). This is a SEPARATE
    # transaction wrapped in its own try/except: a failure here must NEVER block
    # or roll back the confirm the admin already succeeded at. Only graduate a
    # 'confirm' with a real resolved entity of a graduatable type (store/product/
    # staff) — create_new / reject / null-entity produce no first-hop history.
    if (
        body.action == "confirm"
        and body.resolvedToEntityId is not None
        and updated["entity_type"] in HISTORY_GRADUATABLE_ENTITY_TYPES
    ):
        try:
            await _record_admin_confirm_history(
                pool,
                item["factoryId"],
                updated["entity_type"],
                updated["raw_name"],
                body.resolvedToEntityId,
            )
        except Exception as exc:  # noqa: BLE001 — fail-open learning side-effect
            logger.warning(
                "History graduation failed (confirm succeeded) "
                "item=%s factory=%s: %s",
                id, item["factoryId"], exc,
            )

    return {
        "resolved": True,
        "singleAdminDegraded": single_admin_degraded,
    }


@router.post("/{id}/reject")
async def reject_queue(
    request: Request,
    id: int,
    body: RejectBody,
) -> Dict[str, Any]:
    """Reject a PENDING queue item with a mandatory reason.

    Same 4-eye gate and single-admin degradation as resolve_queue.
    Reject reason is stored in extra JSONB (no separate column per W0.1 spec).
    Race condition: UPDATE conditioned on status='PENDING'.
    """
    require_admin(request, action_name="数据质量队列拒绝")

    if not body.reason or not body.reason.strip():
        raise HTTPException(status_code=400, detail="reason 不能为空")

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库不可用")

    item = await _get_queue_item(pool, id)
    if not item:
        raise HTTPException(status_code=404, detail="队列项不存在")

    # Phase B cross-factory tightening: non-platform_admin 仅能操作自己工厂.
    role = getattr(request.state, "role", None)
    jwt_factory_id = getattr(request.state, "factory_id", None)
    if role != "platform_admin" and jwt_factory_id and item["factoryId"] != jwt_factory_id:
        raise HTTPException(
            status_code=403,
            detail=f"非 platform_admin 仅可处理自己工厂的队列项 (该项属于 {item['factoryId']!r})",
        )

    if item["status"] != "PENDING":
        raise HTTPException(
            status_code=409,
            detail=f"队列项当前状态为 {item['status']}，无法拒绝（非 PENDING）",
        )

    current_user = str(
        getattr(request.state, "user_id", None)
        or getattr(request.state, "username", "")
        or ""
    )
    submitter = item["submitter"]

    single_admin_degraded = False
    if current_user and submitter and current_user == submitter:
        admin_count = await _get_admin_count_for_factory(item["factoryId"])
        if admin_count > 1:
            raise HTTPException(
                status_code=403,
                detail="您是该队列项的提交者，需要另一位管理员审核（4-eye 原则）",
            )
        single_admin_degraded = True
        logger.info(
            "Single-admin degradation (reject): factory=%s item=%d user=%s",
            item["factoryId"], id, current_user,
        )

    # P4a (Task 8): dish proposals reject via dish_confirm_service.reject (same
    # core as the CLI). Functionally it is the same UPDATE (mark REJECTED + store
    # reason in extra) but routed through the shared module so the dish path
    # stays single-sourced. NEVER touches canonical / dim_product on reject.
    if item.get("entityType") == "dish":
        rc = await _reject_dish(
            pool, id, item["factoryId"], body.reason.strip(),
            current_user or "admin",
        )
        if rc != 0:
            raise HTTPException(
                status_code=409,
                detail="菜品归一提议已被处理，无法拒绝（请刷新列表）",
            )
        return {"rejected": True}

    async with pool.acquire() as conn:
        # W0.4 finding 3: GUC inside transaction
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", item["factoryId"]
            )

            # Two static SQL templates (no f-string interpolation) for safety —
            # reviewer Task 3.3 concern: f-string + dynamic SQL is a foot-gun.
            if single_admin_degraded:
                updated = await conn.fetchval(
                    """
                    UPDATE entity_resolution_admin_queue
                       SET status       = 'REJECTED',
                           admin_action = 'reject',
                           admin_user   = $1,
                           admin_at     = NOW(),
                           extra        = COALESCE(extra, '{}'::jsonb)
                                          || jsonb_build_object('reject_reason', $2::text)
                                          || jsonb_build_object(
                                               'single_admin_degraded', true,
                                               'submitter_was_resolver', true
                                             )
                     WHERE id = $3
                       AND status = 'PENDING'
                    RETURNING id
                    """,
                    current_user, body.reason, id,
                )
            else:
                updated = await conn.fetchval(
                    """
                    UPDATE entity_resolution_admin_queue
                       SET status       = 'REJECTED',
                           admin_action = 'reject',
                           admin_user   = $1,
                           admin_at     = NOW(),
                           extra        = COALESCE(extra, '{}'::jsonb)
                                          || jsonb_build_object('reject_reason', $2::text)
                     WHERE id = $3
                       AND status = 'PENDING'
                    RETURNING id
                    """,
                    current_user, body.reason, id,
                )

    if updated is None:
        raise HTTPException(
            status_code=409,
            detail="队列项已被其他管理员处理（race condition），请刷新列表",
        )

    return {"rejected": True}


# ---------------------------------------------------------------------------
# Task 3.4: batch-resolve with per-id transactions + partial success
# ---------------------------------------------------------------------------

class BatchResolveBody(BaseModel):
    ids: List[int]
    action: str  # 'confirm' | 'create_new'
    resolvedToEntityId: Optional[int] = None


@router.post("/batch-resolve")
async def batch_resolve_queue(
    request: Request, body: BatchResolveBody,
) -> Dict[str, Any]:
    """Per-id transactional batch resolve with 4-eye + partial success.

    Each ID is processed in its own loop iteration with isolated transaction.
    Single-ID failures (not found / status / 4-eye / race) do NOT block the rest.

    Returns:
        {successCount: int, failedItems: [{id, reason}]}
    """
    require_admin(request, action_name="数据质量队列批量处理")

    if not body.ids:
        raise HTTPException(status_code=400, detail="ids 不能为空")
    if body.action not in ("confirm", "create_new"):
        raise HTTPException(
            status_code=400,
            detail=f"action 必须是 'confirm' 或 'create_new', 收到 {body.action!r}",
        )

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库不可用")

    # Determine current user: prefer numeric user_id, fall back to username string.
    current_user = str(
        getattr(request.state, "user_id", None)
        or getattr(request.state, "username", "")
        or ""
    )
    # Phase B: cross-factory tightening for batch (per-id check below)
    role = getattr(request.state, "role", None)
    jwt_factory_id = getattr(request.state, "factory_id", None)
    is_platform_admin = role == "platform_admin"

    success_count = 0
    failed_items: List[Dict[str, Any]] = []

    for item_id in body.ids:
        # --- Per-id isolation: each iteration is its own transaction boundary ---
        item = await _get_queue_item(pool, item_id)
        if not item:
            failed_items.append({"id": item_id, "reason": "队列项不存在"})
            continue

        # Phase B: non-platform_admin 仅能操作自己工厂
        if not is_platform_admin and jwt_factory_id and item["factoryId"] != jwt_factory_id:
            failed_items.append({
                "id": item_id,
                "reason": f"非 platform_admin 仅可处理自己工厂的项 (该项属于 {item['factoryId']!r})",
            })
            continue

        # P4a (Task 8): NEVER batch-confirm dish proposals. A dish confirm must
        # create/attach a canonical (one-at-a-time human judgement per fool-proof
        # Rule 2/3 — eyeball members + stores first). Batch here would only flip
        # queue status WITHOUT writing the canonical → silent membership gap.
        # The dish tab UI does not offer batch; this is defence-in-depth.
        if item.get("entityType") == "dish":
            failed_items.append({
                "id": item_id,
                "reason": "菜品归一需逐条人工确认 (不支持批量), 请在详情逐条确认",
            })
            continue

        if item["status"] != "PENDING":
            failed_items.append({
                "id": item_id,
                "reason": f"状态 {item['status']}, 无法处理",
            })
            continue

        # 4-eye check per item (same logic as resolve_queue)
        submitter = item["submitter"]
        single_admin_degraded = False
        # W0.4 finding 1: if submitter is None, treat as "no conflict known" → allow.
        if current_user and submitter and current_user == submitter:
            admin_count = await _get_admin_count_for_factory(item["factoryId"])
            if admin_count > 1:
                failed_items.append({
                    "id": item_id,
                    "reason": "您是提交者, 需另一管理员审核 (4-eye 原则)",
                })
                continue
            # admin_count == 1 → single-admin degradation, allow but tag
            single_admin_degraded = True
            logger.info(
                "Single-admin degradation (batch): factory=%s item=%d user=%s",
                item["factoryId"], item_id, current_user,
            )

        try:
            updated = await _update_queue_resolved(
                pool,
                item_id,
                item["factoryId"],
                body.action,
                body.resolvedToEntityId,
                current_user,
                None,  # notes not supported in batch mode
                single_admin_degraded,
            )
            if updated is not None:
                success_count += 1
                # Same best-effort history graduation as resolve_queue: the
                # confirm committed, now learn from the human gold correction so
                # the read path hits 0-cost next upload. Separate try/except —
                # never fail the (already-succeeded) confirm for a learning write.
                if (
                    body.action == "confirm"
                    and body.resolvedToEntityId is not None
                    and updated["entity_type"] in HISTORY_GRADUATABLE_ENTITY_TYPES
                ):
                    try:
                        await _record_admin_confirm_history(
                            pool,
                            item["factoryId"],
                            updated["entity_type"],
                            updated["raw_name"],
                            body.resolvedToEntityId,
                        )
                    except Exception as hist_exc:  # noqa: BLE001 — fail-open
                        logger.warning(
                            "History graduation failed (batch confirm succeeded) "
                            "item=%s factory=%s: %s",
                            item_id, item["factoryId"], hist_exc,
                        )
            else:
                failed_items.append({
                    "id": item_id,
                    "reason": "已被其他管理员处理 (race condition)",
                })
        except Exception as e:
            logger.exception("Batch resolve failed for item %d: %s", item_id, e)
            failed_items.append({
                "id": item_id,
                "reason": f"处理失败: {str(e)[:200]}",
            })

    return {
        "successCount": success_count,
        "failedItems": failed_items,
    }


# ---------------------------------------------------------------------------
# Task 3.6: history endpoint
# ---------------------------------------------------------------------------

@router.get("/{id}/history")
async def get_history(
    request: Request, id: int,
) -> Dict[str, Any]:
    """Return all queue rows for the same (factory_id, entity_type, raw_name).

    Ordered by created_at DESC so the most-recent (current) row is first.

    W0.4 finding 3: GUC set_config inside conn.transaction() so RLS FORCE
    sees app.factory_id for the SELECT.  Pattern matches _fetch_queue_items.

    Security boundary: require_admin() is the gate; _get_queue_item fetches
    by integer id without setting the GUC (same as Task 3.3 pattern — Phase A
    acceptable, Phase B can add BYPASSRLS SECURITY DEFINER).
    """
    require_admin(request, action_name="数据质量队列历史")

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库不可用")

    # Step 1: look up the item to obtain factory_id.
    # _get_queue_item returns {id, factoryId, status, submitter} (no entity_type / raw_name).
    item = await _get_queue_item(pool, id)
    if not item:
        raise HTTPException(status_code=404, detail="队列项不存在")

    # Step 2: fetch entity_type + raw_name, then historical rows — all in one
    # transaction so the GUC covers both SELECTs.
    async with pool.acquire() as conn:
        async with conn.transaction():
            # W0.4 finding 3: GUC must be inside same transaction as the SELECT.
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", item["factoryId"]
            )

            # Fetch entity_type + raw_name from the target row.
            current = await conn.fetchrow(
                """
                SELECT entity_type, raw_name
                  FROM entity_resolution_admin_queue
                 WHERE id = $1
                """,
                id,
            )
            if not current:
                raise HTTPException(status_code=404, detail="队列项不存在")

            rows = await conn.fetch(
                """
                SELECT q.id, q.raw_name, q.entity_type, q.status,
                       q.admin_action, q.admin_at, q.admin_user,
                       q.admin_resolved_to_entity_id, q.candidate_entity_id,
                       q.confidence, q.decided_by_agent, q.created_at,
                       q.priority, q.reasoning, q.extra
                  FROM entity_resolution_admin_queue q
                 WHERE q.factory_id = $1
                   AND q.entity_type = $2
                   AND q.raw_name = $3
                 ORDER BY q.created_at DESC
                """,
                item["factoryId"], current["entity_type"], current["raw_name"],
            )

    return {
        "items": [
            {
                "id": int(r["id"]),
                "rawName": r["raw_name"],
                "entityType": r["entity_type"],
                "status": r["status"],
                "adminAction": r["admin_action"],
                # W0.4 finding 2: admin_at is nullable
                "adminAt": r["admin_at"].isoformat() if r["admin_at"] is not None else None,
                "adminUser": r["admin_user"],
                "resolvedToEntityId": (
                    int(r["admin_resolved_to_entity_id"])
                    if r["admin_resolved_to_entity_id"] is not None
                    else None
                ),
                "candidateEntityId": (
                    int(r["candidate_entity_id"])
                    if r["candidate_entity_id"] is not None
                    else None
                ),
                "confidence": (
                    float(r["confidence"]) if r["confidence"] is not None else None
                ),
                "decidedByAgent": r["decided_by_agent"],
                # W0.4 finding 2: created_at nullable despite DEFAULT now()
                "createdAt": (
                    r["created_at"].isoformat() if r["created_at"] is not None else None
                ),
                "priority": r["priority"],
                "reasoning": r["reasoning"],
                "extra": r["extra"],
            }
            for r in rows
        ],
    }
