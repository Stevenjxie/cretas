"""Admin API — POS dish-name resolution backfill (餐饮 #61 Phase 1).

Router prefix: /api/smartbi/restaurant/name-resolution

  GET  /unresolved   — pending queue rows, sorted revenue_at_risk DESC
  POST /confirm      — bind pos_name → product_type (writes cretas alias + smartbi audit,
                       fires fail-soft incremental finance ETL re-run + cache purge)
  POST /reject       — mark queue row rejected
  POST /skip         — mark queue row skipped
  POST /run-backfill — run resolve_factory_pos_names, return real counts
  GET  /stats        — matched X / total Y / coverage Z%

Auth: all endpoints require_admin (manager/admin tier). __internal__ bypass for
service-account/cron callers (same as restaurant_etl_admin).

Confirm graduation mirrors PR #389: admin gold-standard decision is persisted to BOTH
the resolution data (cretas dim_product_alias, conf=1.0) AND the audit log (smartbi
entity_resolution_history, entity_type='pos_dish'). Machine guesses no longer overwrite
the human decision; same pos_name won't re-run the resolver chain.

Spec: docs/superpowers/specs/2026-06-04-restaurant-pos-name-resolution-design.md
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from smartbi.canonical.provenance._admin_auth import require_admin
from smartbi.gold.restaurant import pos_name_resolver as resolver
from smartbi.gold.restaurant.pos_name_resolver import _pos_dish_surrogate_bigint

logger = logging.getLogger(__name__)

router = APIRouter()

_ACTION = "餐饮 POS 菜品名称解析"


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------

class ConfirmRequest(BaseModel):
    posName: str
    productTypeId: str


class RejectRequest(BaseModel):
    posName: str


class SkipRequest(BaseModel):
    posName: str


# ---------------------------------------------------------------------------
# Pool helpers (lazy, avoid import-time circular)
# ---------------------------------------------------------------------------

async def _pools():
    from smartbi.config import get_pg_pool, get_cretas_pool
    smartbi_pool = await get_pg_pool()
    if smartbi_pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db 连接池不可用 (检查 POSTGRES_URL)")
    cretas_pool = await get_cretas_pool()
    if cretas_pool is None:
        raise HTTPException(status_code=503, detail="cretas_db 连接池不可用 (检查 FOOD_KB_DB_URL)")
    return cretas_pool, smartbi_pool


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.get("/unresolved")
async def list_unresolved(request: Request) -> Dict[str, Any]:
    """List pending unresolved POS dish names, sorted by revenue_at_risk DESC."""
    factory_id = require_admin(request, action_name=f"{_ACTION}（查看未解析队列）")
    if not factory_id:
        raise HTTPException(status_code=400, detail="缺少工厂上下文")
    _cretas, smartbi = await _pools()
    async with smartbi.acquire() as conn:
        await resolver._set_tenant(conn, factory_id)
        rows = await conn.fetch(
            """
            SELECT pos_name, display_name, occurrence_count, revenue_at_risk,
                   best_candidate_id, best_candidate_name, best_confidence,
                   status, created_at, updated_at
              FROM restaurant_pos_unresolved_queue
             WHERE factory_id = $1 AND status = 'pending'
             ORDER BY revenue_at_risk DESC
            """,
            factory_id,
        )
    return {
        "success": True,
        "data": {
            "items": [
                {
                    "posName": r["pos_name"],
                    "displayName": r["display_name"],
                    "occurrenceCount": r["occurrence_count"],
                    "revenueAtRisk": float(r["revenue_at_risk"]) if r["revenue_at_risk"] is not None else 0.0,
                    "bestCandidateId": r["best_candidate_id"],
                    "bestCandidateName": r["best_candidate_name"],
                    "bestConfidence": float(r["best_confidence"]) if r["best_confidence"] is not None else None,
                    "status": r["status"],
                }
                for r in rows
            ],
            "total": len(rows),
        },
    }


@router.post("/confirm")
async def confirm_binding(request: Request, body: ConfirmRequest) -> Dict[str, Any]:
    """Bind pos_name → product_type. Writes cretas alias + smartbi audit; fires ETL re-run."""
    factory_id = require_admin(request, action_name=f"{_ACTION}（确认绑定）")
    if not factory_id:
        raise HTTPException(status_code=400, detail="缺少工厂上下文")
    # Preserve posName verbatim — it must stay byte-identical to dim_product.normalized_name
    # (the ETL alias lookup key); do NOT strip/normalize the persisted key.
    pos_name = body.posName or ""
    product_type_id = (body.productTypeId or "").strip()
    if not pos_name.strip() or not product_type_id:
        raise HTTPException(status_code=400, detail="posName 和 productTypeId 必填")

    cretas, smartbi = await _pools()
    await resolver.ensure_alias_schema(cretas, factory_id)

    # Validate product_type belongs to this tenant + fetch its name for the audit row.
    async with cretas.acquire() as conn:
        pt_name = await conn.fetchval(
            "SELECT name FROM product_types "
            "WHERE id = $1 AND factory_id = $2 AND deleted_at IS NULL",
            product_type_id, factory_id,
        )
        if pt_name is None:
            raise HTTPException(
                status_code=400,
                detail=f"product_type_id {product_type_id!r} 不存在或不属于当前租户",
            )
        # Write resolution data (conf=1.0, source=admin_confirmed). Idempotent.
        await resolver._upsert_alias(
            conn, factory_id, pos_name, product_type_id,
            1.0, "admin_confirmed", "admin",
        )
        # Stamp admin attribution (resolver._upsert_alias is shared with the auto path).
        await conn.execute(
            """
            UPDATE dim_product_alias
               SET admin_user = $3, admin_at = NOW()
             WHERE factory_id = $1 AND pos_name = $2
            """,
            factory_id, pos_name, _admin_user(request),
        )

    # Audit row in smartbi entity_resolution_history (entity_type='pos_dish').
    surrogate = _pos_dish_surrogate_bigint(factory_id, pos_name, product_type_id)
    async with smartbi.acquire() as conn:
        await resolver._set_tenant(conn, factory_id)
        await conn.execute(
            """
            INSERT INTO entity_resolution_history
              (factory_id, entity_type, a_name, b_name, b_entity_id,
               confidence, decided_by_agent, reasoning)
            VALUES ($1, 'pos_dish', $2, $3, $4, 1.00, 'admin', $5)
            ON CONFLICT (factory_id, entity_type, a_name, b_entity_id) DO UPDATE SET
              confidence = GREATEST(entity_resolution_history.confidence, EXCLUDED.confidence),
              decided_by_agent = EXCLUDED.decided_by_agent,
              reasoning = EXCLUDED.reasoning
            """,
            factory_id, pos_name, pt_name, surrogate,
            f"admin confirmed POS '{pos_name}' → product_type {product_type_id} ({pt_name})",
        )
        # Mark the queue row confirmed.
        await conn.execute(
            """
            UPDATE restaurant_pos_unresolved_queue
               SET status = 'confirmed', admin_user = $3, admin_at = NOW(), updated_at = NOW()
             WHERE factory_id = $1 AND pos_name = $2
            """,
            factory_id, pos_name, _admin_user(request),
        )

    # Fail-soft incremental finance ETL re-run + cache purge (must not doom the confirm).
    asyncio.create_task(_rerun_finance_etl(factory_id, reason=f"pos-name confirm {pos_name}"))

    return {
        "success": True,
        "data": {
            "posName": pos_name,
            "productTypeId": product_type_id,
            "productName": pt_name,
            "etlRerun": "scheduled",
        },
        "message": "已绑定。后台正在重跑财务 ETL (约 30 秒) 更新成本卡片。",
    }


@router.post("/reject")
async def reject_binding(request: Request, body: RejectRequest) -> Dict[str, Any]:
    factory_id = require_admin(request, action_name=f"{_ACTION}（拒绝）")
    if not factory_id:
        raise HTTPException(status_code=400, detail="缺少工厂上下文")
    return await _set_queue_status(factory_id, request, body.posName, "rejected")


@router.post("/skip")
async def skip_binding(request: Request, body: SkipRequest) -> Dict[str, Any]:
    factory_id = require_admin(request, action_name=f"{_ACTION}（跳过）")
    if not factory_id:
        raise HTTPException(status_code=400, detail="缺少工厂上下文")
    return await _set_queue_status(factory_id, request, body.posName, "skipped")


@router.post("/run-backfill")
async def run_backfill(request: Request) -> Dict[str, Any]:
    """Run the 5-layer resolver for this factory. Returns real counts."""
    factory_id = require_admin(request, action_name=f"{_ACTION}（运行回填）")
    if not factory_id:
        raise HTTPException(status_code=400, detail="缺少工厂上下文")
    cretas, smartbi = await _pools()
    counts = await resolver.resolve_factory_pos_names(cretas, smartbi, factory_id)
    return {"success": True, "data": counts}


@router.get("/stats")
async def coverage_stats(request: Request) -> Dict[str, Any]:
    """Coverage = (POS names matched by product_types exact OR alias) / total distinct POS names."""
    factory_id = require_admin(request, action_name=f"{_ACTION}（覆盖率统计）")
    if not factory_id:
        raise HTTPException(status_code=400, detail="缺少工厂上下文")
    cretas, smartbi = await _pools()

    async with smartbi.acquire() as conn:
        await resolver._set_tenant(conn, factory_id)
        pos_rows = await conn.fetch(
            """
            SELECT DISTINCT p.normalized_name
              FROM fact_pos_item i
              JOIN dim_product p ON p.product_id = i.product_id
             WHERE i.factory_id = $1 AND p.factory_id = $1 AND i.product_id IS NOT NULL
            """,
            factory_id,
        )
    # Coverage mirrors the ETL: match RAW normalized_name against RAW product_types.name /
    # RAW alias pos_name (NOT _normalize_name — that would under-count Latin/whitespace names).
    distinct = {r["normalized_name"] for r in pos_rows if r["normalized_name"]}
    distinct.discard("")
    total = len(distinct)

    matched = 0
    if total:
        async with cretas.acquire() as conn:
            pt_rows = await conn.fetch(
                "SELECT name FROM product_types WHERE factory_id = $1 AND deleted_at IS NULL",
                factory_id,
            )
            try:
                alias_rows = await conn.fetch(
                    "SELECT pos_name FROM dim_product_alias WHERE factory_id = $1",
                    factory_id,
                )
            except Exception:
                alias_rows = []
        resolved_keys = {r["name"] for r in pt_rows} | {r["pos_name"] for r in alias_rows}
        matched = sum(1 for raw in distinct if raw in resolved_keys)

    coverage = round(matched / total * 100, 1) if total else 0.0
    return {
        "success": True,
        "data": {"matched": matched, "total": total, "coveragePct": coverage},
    }


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _admin_user(request: Request) -> Optional[str]:
    return getattr(request.state, "username", None) or getattr(request.state, "user_id", None)


async def _set_queue_status(
    factory_id: str, request: Request, pos_name: str, status: str,
) -> Dict[str, Any]:
    pos_name = (pos_name or "").strip()
    if not pos_name:
        raise HTTPException(status_code=400, detail="posName 必填")
    _cretas, smartbi = await _pools()
    async with smartbi.acquire() as conn:
        await resolver._set_tenant(conn, factory_id)
        result = await conn.execute(
            """
            UPDATE restaurant_pos_unresolved_queue
               SET status = $3, admin_user = $4, admin_at = NOW(), updated_at = NOW()
             WHERE factory_id = $1 AND pos_name = $2 AND status = 'pending'
            """,
            factory_id, pos_name, status, _admin_user(request),
        )
    updated = result.endswith(" 1") if isinstance(result, str) else False
    return {"success": True, "data": {"posName": pos_name, "status": status, "updated": updated}}


async def _rerun_finance_etl(factory_id: str, reason: str) -> None:
    """Fail-soft incremental finance ETL re-run + cache purge after a confirm.

    MUST NOT raise — runs detached via asyncio.create_task. Any failure is logged
    but never doomed back to the confirm response (which already committed the alias).
    """
    try:
        from smartbi.config import get_pg_pool, get_cretas_pool
        from smartbi.gold.restaurant.restaurant_finance_etl import run_full_finance_etl_with_retry

        smartbi_pool = await get_pg_pool()
        cretas_pool = await get_cretas_pool()
        if smartbi_pool is None or cretas_pool is None:
            logger.warning("[pos-name-confirm] pools unavailable, skip ETL re-run for %s", factory_id)
            return
        stats = await run_full_finance_etl_with_retry(cretas_pool, smartbi_pool, factory_id)
        logger.info(
            "[pos-name-confirm] finance ETL re-run done factory=%s reason=%s stats=%s",
            factory_id, reason, stats,
        )
    except Exception as exc:  # noqa: BLE001 — fail-soft by design
        logger.warning(
            "[pos-name-confirm] finance ETL re-run failed (non-fatal) factory=%s reason=%s err=%s",
            factory_id, reason, exc,
        )
        return

    # Cache purge — best-effort, separate try so an ETL success isn't masked.
    try:
        from smartbi.api.restaurant_etl_admin import _purge_indicator_cache_for_factory
        await _purge_indicator_cache_for_factory(factory_id, reason)
    except Exception as exc:  # noqa: BLE001
        logger.warning("[pos-name-confirm] cache purge failed (non-fatal) factory=%s: %s", factory_id, exc)
