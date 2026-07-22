"""Phase 0: column-mapping review API.

Columns the controlled-vocab mapper could not confidently map are queued
(``smart_bi_mapping_review_queue``). An operator confirms the canonical field;
a confirmation writes a **deterministic pin** (``smart_bi_pin_mappings``) so the
same column on the same upload template maps identically forever (kills the
cross-upload drift the prod audit found). Validation rejects any non-canonical
target, so a confirmation can never re-introduce garbage standard names.

Both tables are FORCE RLS → every query sets ``app.factory_id`` in-transaction
(``feedback_asyncpg_rls_guc_must_be_in_transaction``). Permission: reuses
``require_analytics_read`` (no write-scope helper exists yet; tighten later).
"""
from __future__ import annotations
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from smartbi.config import get_pg_pool
from smartbi.services.domain_standard_fields import ALL_CANONICAL_FIELDS, DOMAIN_FIELDS
from smartbi_compat._rbac_role import require_analytics_read
from smartbi_compat.auth import AuthContext

router = APIRouter(tags=["Mapping Review"])

_BASE = "/api/mobile/{factory_id}/smart-bi/mapping-review"


class ConfirmItem(BaseModel):
    column_name: str
    confirmed_standard: str
    should_pin: bool = True


class ConfirmRequest(BaseModel):
    items: List[ConfirmItem]


@router.get(_BASE + "/canonical-fields")
async def list_canonical_fields(
    factory_id: str,
    auth: AuthContext = Depends(require_analytics_read),
):
    """Return canonical field list grouped by domain (for dropdown in review UI).

    Pure static read from ``DOMAIN_FIELDS`` — no DB, no RLS needed.
    Each entry: ``{standard_name, description, category}``.
    """
    data: dict[str, list[dict]] = {}
    for domain, fields in DOMAIN_FIELDS.items():
        data[domain] = [
            {
                "standard_name": standard_name,
                "description": meta.get("description", ""),
                "category": meta.get("category", ""),
            }
            for standard_name, meta in fields.items()
        ]
    return {"success": True, "data": data, "message": "ok"}


@router.get(_BASE + "/pending")
async def list_pending(
    factory_id: str,
    limit: int = 100,
    auth: AuthContext = Depends(require_analytics_read),
):
    """Pending review columns for this factory (newest first)."""
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(503, "database unavailable")
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", auth.factory_id
            )
            rows = await conn.fetch(
                """SELECT id, upload_id, template_key, column_name, detected_standard,
                          detected_confidence, detected_method, created_at
                     FROM smart_bi_mapping_review_queue
                    WHERE factory_id = $1 AND review_status = 'PENDING'
                    ORDER BY created_at DESC
                    LIMIT $2""",
                auth.factory_id, limit,
            )
    return {"success": True, "data": [dict(r) for r in rows], "message": "ok"}


@router.post(_BASE + "/{upload_id}/confirm")
async def confirm_mapping(
    factory_id: str,
    upload_id: int,
    body: ConfirmRequest,
    auth: AuthContext = Depends(require_analytics_read),
):
    """Confirm column mappings → pin them (deterministic for future uploads)."""
    # Validate first: every confirmed target must be a controlled canonical field
    # (a confirmation must never re-introduce off-vocab garbage).
    bad = [it.confirmed_standard for it in body.items
           if it.confirmed_standard not in ALL_CANONICAL_FIELDS]
    if bad:
        raise HTTPException(
            400, f"非受控字典字段: {bad} — 请从 canonical 字段中选择")

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(503, "database unavailable")

    confirmed = 0
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", auth.factory_id
            )
            for it in body.items:
                col = it.column_name.strip().lower()
                row = await conn.fetchrow(
                    """UPDATE smart_bi_mapping_review_queue
                          SET review_status = 'CONFIRMED',
                              user_confirmed_standard = $3,
                              reviewed_by = $4,
                              reviewed_at = now()
                        WHERE upload_id = $1 AND column_name = $2 AND factory_id = $5
                    RETURNING template_key""",
                    upload_id, col, it.confirmed_standard, auth.username, auth.factory_id,
                )
                if not row:
                    continue
                if it.should_pin:
                    await conn.execute(
                        """INSERT INTO smart_bi_pin_mappings
                             (factory_id, template_key, column_name, standard_name,
                              confidence, method, pinned_by)
                           VALUES ($1, $2, $3, $4, 1.0, 'user_pinned', $5)
                           ON CONFLICT (factory_id, template_key, column_name)
                           DO UPDATE SET standard_name = EXCLUDED.standard_name,
                                         confidence = 1.0, method = 'user_pinned',
                                         pinned_by = EXCLUDED.pinned_by, updated_at = now()""",
                        auth.factory_id, row["template_key"], col,
                        it.confirmed_standard, auth.username,
                    )
                confirmed += 1
    return {"success": True, "data": {"confirmed": confirmed, "upload_id": upload_id},
            "message": "ok"}
