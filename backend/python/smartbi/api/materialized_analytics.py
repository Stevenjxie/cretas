"""API routes for materialized analytics cache.

GET /api/smartbi/analytics/cached/{upload_id}
  → Returns cached template results for the upload (factory-scoped).
  → Status 404 if upload not found.
  → Status 403 if factory_id mismatch (cross-tenant attempt).

POST /api/smartbi/analytics/materialize/{upload_id}
  → Triggers fresh materialization + persist. Idempotent (upserts).
  → Used by upload hook (Task 13) and manual refresh.

POST /api/smartbi/analytics/reclassify/{upload_id}  (γ-2)
  → Re-runs unified field_classifier on existing upload's columns,
    updates smart_bi_pg_field_definitions where classifications changed,
    then triggers re-materialization to regenerate cache with the new roles.
  → Used to fix historical uploads affected by buggy legacy classifiers
    (e.g. qhj upload 4169's 账单号/商品结账总数 misclassifications).
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Dict, List, Set

from fastapi import APIRouter, HTTPException, Query, Request

# Module-level set of pending background tasks. asyncio only keeps weak refs
# to tasks, so a fire-and-forget task spawned inside an HTTP handler can get
# GC'd mid-execution when the handler returns. We anchor tasks here and let
# them self-remove on completion. See CPython issue #88831 / docs note on
# asyncio.create_task.
_PENDING_BG_TASKS: Set[asyncio.Task] = set()


def _spawn_bg(coro) -> asyncio.Task:
    """Schedule a detached coroutine that outlives the current request.

    FastAPI/Starlette can cancel the request context, but the task reference
    lives in _PENDING_BG_TASKS so the event loop keeps it alive.
    """
    task = asyncio.create_task(coro)
    _PENDING_BG_TASKS.add(task)
    task.add_done_callback(_PENDING_BG_TASKS.discard)
    return task


from smartbi.config import get_pg_pool  # noqa: E402
from smartbi.services.field_classifier import classify_column, infer_agg_strategy  # noqa: E402
from smartbi.services.materialized_analytics.materializer import build_schema, materialize_upload  # noqa: E402
from smartbi.services.materialized_analytics.persistence import (  # noqa: E402
    load_materialization_results,
    save_materialization_results,
)
from smartbi.services.materialized_analytics.schema import Domain  # noqa: E402

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/analytics", tags=["materialized-analytics"])


async def _get_upload_factory(pool, upload_id: int) -> str:
    """Fetch factory_id for upload. Raises HTTPException 404 if missing."""
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT factory_id FROM smart_bi_pg_excel_uploads WHERE id = $1",
            upload_id
        )
    if row is None:
        raise HTTPException(status_code=404, detail=f"upload {upload_id} not found")
    return row['factory_id']


def _extract_factory_id(request: Request) -> str:
    """Get factory_id from auth middleware. 401 if missing."""
    factory_id = getattr(request.state, 'factory_id', None)
    if not factory_id:
        raise HTTPException(status_code=401, detail="factory_id not in auth context")
    return factory_id


@router.get("/cached/{upload_id}")
async def get_cached_analytics(upload_id: int, request: Request) -> Dict[str, Any]:
    """Load pre-computed template results for an upload.

    Returns {results: [...], upload_id, factory_id, count, cached: bool}.
    If nothing cached yet, cached=False, results=[]; caller should POST
    /analytics/materialize/{upload_id} to trigger a fresh run.
    """
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="postgres pool not available")

    user_factory = _extract_factory_id(request)
    upload_factory = await _get_upload_factory(pool, upload_id)
    if upload_factory != user_factory:
        logger.warning(
            f"[analytics] factory mismatch: user={user_factory}, upload={upload_factory}"
        )
        raise HTTPException(status_code=403, detail="cross-tenant access denied")

    results = await load_materialization_results(pool, upload_id, factory_id=user_factory)
    return {
        "success": True,
        "cached": len(results) > 0,
        "upload_id": upload_id,
        "factory_id": user_factory,
        "count": len(results),
        "results": results,
    }


@router.get("/list-factory-templates")
async def list_factory_templates(
    request: Request,
    factory_id: str = Query(..., description="Factory whose materialized templates to list"),
) -> Dict[str, Any]:
    """措施③ recommendation engine: list the analysis templates that already
    have materialized (pre-computed) results for a factory, so the FE can show
    a "猜你想问" guidance area.

    Why this is the core 杠杆: clicking a recommended chip pins the user's
    free-form natural-language input to a known template phrase, which then
    hits the materialized cache in the query router → 0 LLM token + ~230ms,
    instead of running the full open-NL pipeline (slow + expensive).

    Logic: DISTINCT template_code over this factory's materialized results,
    counted by how many uploads produced each template (popularity proxy),
    DESC by count. The top 3 get is_recommended=true. Titles come from the
    template registry's canonical Chinese title (single source of truth shared
    with the FE useTemplateMap dictionary).

    Returns {success, factory_id, count, templates: [{code, title,
    materialization_count, is_recommended}]}.

    Auth: same JWT / X-Internal-Secret + X-Factory-Id model as the rest of
    this router. The path factory_id MUST equal the authenticated factory
    (no cross-tenant listing).
    """
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="postgres pool not available")

    user_factory = _extract_factory_id(request)
    if factory_id != user_factory:
        logger.warning(
            f"[list-factory-templates] factory mismatch: user={user_factory}, "
            f"requested={factory_id}"
        )
        raise HTTPException(status_code=403, detail="cross-tenant access denied")

    # DISTINCT template_code with per-template materialization count. We count
    # DISTINCT uploads (a single upload can store one row per template, but join
    # on upload guards against any future duplicate rows inflating the count).
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """SELECT r.template_code AS template_code,
                      COUNT(DISTINCT r.upload_id) AS materialization_count
               FROM smart_bi_pg_analysis_results r
               JOIN smart_bi_pg_excel_uploads u ON u.id = r.upload_id
               WHERE u.factory_id = $1 AND r.template_code IS NOT NULL
               GROUP BY r.template_code
               ORDER BY materialization_count DESC, r.template_code ASC""",
            user_factory,
        )

    # Resolve canonical titles from the template registry (single source of
    # truth). get_registry() here lazy-imports + ensures load_all_templates()
    # ran (idempotent), so the registry is populated even if no materialize has
    # happened in this process yet. Fall back to the raw code on any failure.
    try:
        from smartbi.capability.template_status import get_registry as _get_registry
        registry = _get_registry()
    except Exception as e:  # pragma: no cover - registry import is stable
        logger.warning(f"[list-factory-templates] registry unavailable: {e}")
        registry = None

    def _title_for(code: str) -> str:
        if registry is not None:
            try:
                return registry.by_code(code).title
            except KeyError:
                pass
        return code

    templates: List[Dict[str, Any]] = []
    for idx, row in enumerate(rows):
        code = row["template_code"]
        templates.append({
            "code": code,
            "title": _title_for(code),
            "materialization_count": int(row["materialization_count"]),
            "is_recommended": idx < 3,  # top 3 by popularity
        })

    return {
        "success": True,
        "factory_id": user_factory,
        "count": len(templates),
        "templates": templates,
    }


@router.post("/precompute-cache/{upload_id}")
async def precompute_cache_endpoint(upload_id: int, request: Request) -> Dict[str, Any]:
    """γ-2c (Apr 25 2026 / Task C / PROD-1 fix): pre-materialize KPI-only
    enrichment_cache for a given upload.

    Lives on the materialized_analytics router (JWT / X-Internal-Secret +
    X-Factory-Id protected) instead of analysis_cache.router because the
    latter is in PUBLIC_PREFIXES and can't see request.state.factory_id.

    Called by Java γ-2c afterCommit hook on every successful upload, so the
    FE cache-first branch (analysis.ts cache-first, ~line 1400) hits an
    instant cache on first visit and renders KPI cards in <1s instead of
    running the full 30-60s LLM pipeline (which times out on 200K-row POS
    uploads).

    Idempotent. Pure compute (no LLM). Won't clobber a full LLM cache —
    only refreshes kpiSummary in-place when richer cache exists.
    """
    from smartbi.api.analysis_cache import precompute_enrichment_cache_for_upload

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="postgres pool not available")

    user_factory = _extract_factory_id(request)
    upload_factory = await _get_upload_factory(pool, upload_id)
    if upload_factory != user_factory:
        logger.warning(
            f"[precompute-cache] factory mismatch: user={user_factory}, "
            f"upload={upload_factory}"
        )
        raise HTTPException(status_code=403, detail="cross-tenant access denied")

    try:
        return await precompute_enrichment_cache_for_upload(upload_id, user_factory)
    except Exception as e:
        logger.error(f"[precompute-cache] upload {upload_id} failed: {e}", exc_info=True)
        return {"success": False, "message": "处理失败，请稍后重试"}


@router.post("/materialize/{upload_id}")
async def trigger_materialization(upload_id: int, request: Request) -> Dict[str, Any]:
    """Trigger fresh materialization + persist. Idempotent (ON CONFLICT updates)."""
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="postgres pool not available")

    user_factory = _extract_factory_id(request)
    upload_factory = await _get_upload_factory(pool, upload_id)
    if upload_factory != user_factory:
        raise HTTPException(status_code=403, detail="cross-tenant access denied")

    t_start = time.time()
    results = await materialize_upload(pool, upload_id)
    # Derive domain from schema (all templates share it). If no results, empty domain.
    # We use the first applicable result's stored domain via re-reading schema, but
    # materialize_upload doesn't return the schema directly. Cheapest: re-peek domain
    # from any stored location; fallback to UNKNOWN.
    # For W1 simplicity: always pass "unknown" if results empty, else read domain from
    # the upload's factory context (we only have restaurant templates anyway in W1).
    # Better: extend materialize_upload to return (schema, results). W1 TODO.
    # For now, inspect the first applicable result's kpis/data doesn't reveal domain.
    # Simplest: run build_schema ourselves to get domain.
    async with pool.acquire() as conn:
        ur = await conn.fetchrow(
            "SELECT factory_id, row_count FROM smart_bi_pg_excel_uploads WHERE id = $1",
            upload_id
        )
        field_rows = await conn.fetch(
            """SELECT original_name, is_measure, is_dimension, is_time
               FROM smart_bi_pg_field_definitions WHERE upload_id = $1
               ORDER BY display_order""",
            upload_id
        )
    field_meta = [dict(r) for r in field_rows]
    if field_meta:
        schema = build_schema(upload_id, ur['factory_id'], field_meta, ur['row_count'] or 0)
        domain_value = schema.domain.value
    else:
        domain_value = Domain.UNKNOWN.value

    saved = await save_materialization_results(
        pool, upload_id, user_factory, domain_value, results
    )

    applied = sum(1 for r in results if r.applies)
    skipped = sum(1 for r in results if not r.applies and not r.error)
    errored = sum(1 for r in results if r.error)

    # === Accuracy fix (Apr 25 2026): invalidate Agent narrative cache on new upload ===
    # Without this, Week 5 Agent战略 insights would serve stale (up to 24h old)
    # analysis based on the PREVIOUS upload's data. Since the customer just
    # uploaded fresh data, any LLM-generated analytical narrative must be
    # regenerated to reflect the new numbers.
    narrative_invalidated = 0
    try:
        from smartbi.agent.narrative_cache import NarrativeCacheService
        narrative_invalidated = await NarrativeCacheService(pool).invalidate_on_upload(user_factory)
    except Exception as e:
        # Don't fail the materialize if narrative_cache table isn't created yet
        logger.warning(f"[materialize] narrative cache invalidation skipped: {e}")

    # === Speed optimization: background warmup of Agent Insights ===
    # Fire-and-forget async task to pre-populate the narrative_cache with
    # analyses for the standard question set. Customer hitting Dashboard 30s
    # later sees insights already warm (6ms cache hit) instead of 10-30s LLM call.
    try:
        import asyncio
        from smartbi.services.analytics_warmup import warmup_factory_insights
        asyncio.create_task(warmup_factory_insights(pool, user_factory, upload_id))
    except ImportError:
        # Module not yet present — accept 30s cold on first Dashboard access.
        pass
    except Exception as e:
        logger.warning(f"[materialize] warmup task skip: {e}")

    return {
        "success": True,
        "upload_id": upload_id,
        "factory_id": user_factory,
        "domain": domain_value,
        "total_templates": len(results),
        "applied": applied,
        "skipped": skipped,
        "errored": errored,
        "saved": saved,
        "narrative_invalidated": narrative_invalidated,
        "wall_ms": int((time.time() - t_start) * 1000),
    }


@router.post("/reclassify/{upload_id}")
async def reclassify_upload(
    upload_id: int,
    request: Request,
    rematerialize: bool = Query(True, description="Re-run materialize after reclassify"),
) -> Dict[str, Any]:
    """γ-2: Re-run unified field_classifier on existing upload columns.

    Fixes historical misclassifications (账单号 as measure, 商品结账总数 as dim, etc.)
    without re-uploading the file. After updating field_definitions, optionally
    triggers materialize_upload to regenerate cached templates (since template
    applies() logic depends on field roles).

    Returns a diff of what changed so user can audit.
    """
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="postgres pool not available")

    user_factory = _extract_factory_id(request)
    upload_factory = await _get_upload_factory(pool, upload_id)
    if upload_factory != user_factory:
        logger.warning(
            f"[reclassify] factory mismatch: user={user_factory}, upload={upload_factory}"
        )
        raise HTTPException(status_code=403, detail="cross-tenant access denied")

    t_start = time.time()

    # Load existing field_definitions
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """SELECT original_name, standard_name, field_type, semantic_type,
                      is_measure, is_dimension, is_time, display_order,
                      statistics, agg_strategy
               FROM smart_bi_pg_field_definitions
               WHERE upload_id = $1
               ORDER BY display_order""",
            upload_id,
        )
    if not rows:
        raise HTTPException(status_code=404, detail=f"no field_definitions for upload {upload_id}")

    # Run new classifier on each column
    changes: List[Dict[str, Any]] = []
    updates: List[Dict[str, Any]] = []
    for row in rows:
        name = row["original_name"]
        field_type = (row["field_type"] or "").upper() or None
        new = classify_column(original_name=name, inferred_dtype=field_type)

        # Compute agg_strategy from new classification + persisted statistics.
        # asyncpg's default JSONB codec returns a dict (or None) — but if a
        # legacy row stored a list/scalar JSONB shape (uncommon, possible from
        # older codepaths), the isinstance(dict) check normalises to None and
        # the helper falls back to the conservative 'sum' default.
        stats = row["statistics"] if isinstance(row["statistics"], dict) else None
        new_agg = infer_agg_strategy(
            name=name,
            semantic_type=new["semantic_type"],  # type: ignore[arg-type]
            is_measure=bool(new["is_measure"]),
            statistics=stats,
        )

        old_roles = {
            "is_measure": row["is_measure"],
            "is_dimension": row["is_dimension"],
            "is_time": row["is_time"],
        }
        new_roles = {
            "is_measure": new["is_measure"],
            "is_dimension": new["is_dimension"],
            "is_time": new["is_time"],
        }
        old_agg = row["agg_strategy"]

        if (old_roles != new_roles
                or row["semantic_type"] != new["semantic_type"]
                or old_agg != new_agg):
            changes.append({
                "original_name": name,
                "field_type": row["field_type"],
                "old": {**old_roles, "semantic_type": row["semantic_type"],
                        "agg_strategy": old_agg},
                "new": {**new_roles, "semantic_type": new["semantic_type"],
                        "agg_strategy": new_agg},
                "reason": new["reason"],
            })
            updates.append({
                "name": name,
                "is_measure": new["is_measure"],
                "is_dimension": new["is_dimension"],
                "is_time": new["is_time"],
                "semantic_type": new["semantic_type"],
                "agg_strategy": new_agg,
            })

    # Apply updates in a transaction
    if updates:
        async with pool.acquire() as conn:
            async with conn.transaction():
                for u in updates:
                    await conn.execute(
                        """UPDATE smart_bi_pg_field_definitions
                           SET is_measure = $1, is_dimension = $2, is_time = $3,
                               semantic_type = $4, agg_strategy = $5
                           WHERE upload_id = $6 AND original_name = $7""",
                        u["is_measure"], u["is_dimension"], u["is_time"],
                        u["semantic_type"], u["agg_strategy"],
                        upload_id, u["name"],
                    )
        logger.info(
            f"[reclassify] upload {upload_id}: updated {len(updates)}/{len(rows)} field_defs"
        )

    # Optionally re-materialize (drops and regenerates cache)
    remat_result = None
    if rematerialize:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    """DELETE FROM smart_bi_pg_analysis_results
                       WHERE upload_id = $1 AND template_code IS NOT NULL""",
                    upload_id,
                )
        materialize_ctx: Dict[str, Any] = {}
        results = await materialize_upload(pool, upload_id, _out_ctx=materialize_ctx)

        # Derive domain (same pattern as /materialize endpoint)
        async with pool.acquire() as conn:
            ur = await conn.fetchrow(
                "SELECT factory_id, row_count FROM smart_bi_pg_excel_uploads WHERE id = $1",
                upload_id,
            )
            field_rows = await conn.fetch(
                """SELECT original_name, is_measure, is_dimension, is_time
                   FROM smart_bi_pg_field_definitions WHERE upload_id = $1
                   ORDER BY display_order""",
                upload_id,
            )
        field_meta = [dict(r) for r in field_rows]
        if field_meta:
            schema = build_schema(upload_id, ur['factory_id'], field_meta, ur['row_count'] or 0)
            domain_value = schema.domain.value
        else:
            domain_value = Domain.UNKNOWN.value
        saved = await save_materialization_results(
            pool, upload_id, user_factory, domain_value, results
        )
        remat_result = {
            "total_templates": len(results),
            "applied": sum(1 for r in results if r.applies),
            "skipped": sum(1 for r in results if not r.applies and not r.error),
            "errored": sum(1 for r in results if r.error),
            "saved": saved,
            "domain": domain_value,
        }

        # Also pre-warm the L2 aggregate cache (LLM fallback path). If the
        # materialize call exposed its polars DataFrame via out_ctx we use
        # that for a ~1s in-memory compute; otherwise we fall back to the SQL
        # path (~30-70s). Either way detached via _spawn_bg so the task
        # survives request lifecycle teardown (client disconnect, etc).
        from smartbi.services.upload_aggregate_cache import (
            compute_aggregates_from_polars as _compute_aggs_polars,
            compute_upload_aggregates as _compute_aggs,
            save_bundle_to_db as _save_bundle_db,
            get_cache as _get_agg_cache,
        )

        _polars_backend = materialize_ctx.get("backend")
        _polars_field_meta = materialize_ctx.get("field_meta")

        async def _warm_l2_aggregate_cache() -> None:
            try:
                if _polars_backend is not None and _polars_field_meta:
                    # Fast path: piggyback on materialize's in-memory polars DF.
                    bundle = _compute_aggs_polars(
                        _polars_backend, _polars_field_meta, sample_size=0
                    )
                    _get_agg_cache().set(upload_id, bundle)
                    await _save_bundle_db(pool, upload_id, bundle, factory_id=user_factory)
                    logger.info(
                        f"[reclassify→agg-warm] upload={upload_id} pre-warmed L2 "
                        f"via polars in {bundle.get('compute_time_s', 0):.2f}s"
                    )
                    # Drop polars DF + trim heap — see hooks.py for rationale.
                    try:
                        _polars_backend._df = None  # noqa: SLF001
                        materialize_ctx.clear()
                    except Exception:
                        pass
                    try:
                        from smartbi.services.memory_cleanup import release_and_trim
                        release_and_trim(label=f"reclassify_{upload_id}")
                    except Exception:
                        pass
                    return
                # Fallback: re-load field_meta and run the SQL path.
                async with pool.acquire() as warm_conn:
                    warm_rows = await warm_conn.fetch(
                        """SELECT original_name, standard_name, is_measure, is_dimension, is_time
                           FROM smart_bi_pg_field_definitions
                           WHERE upload_id = $1 ORDER BY display_order""",
                        upload_id,
                    )
                    warm_field_meta = [dict(r) for r in warm_rows]
                    if not warm_field_meta:
                        logger.warning(
                            f"[reclassify→agg-warm] upload={upload_id} has no "
                            "field_defs, skipping L2 warm"
                        )
                        return
                    bundle = await _compute_aggs(
                        warm_conn, pool, upload_id, warm_field_meta, sample_size=0
                    )
                _get_agg_cache().set(upload_id, bundle)
                await _save_bundle_db(pool, upload_id, bundle, factory_id=user_factory)
                logger.info(
                    f"[reclassify→agg-warm] upload={upload_id} pre-warmed L2 via "
                    f"SQL in {bundle.get('compute_time_s', 0):.1f}s (polars fallback)"
                )
            except Exception as warm_err:
                logger.warning(
                    f"[reclassify→agg-warm] upload={upload_id} failed: {warm_err}"
                )

        _spawn_bg(_warm_l2_aggregate_cache())
        remat_result["agg_cache_warm_scheduled"] = True

    # 数据织网 A spec §3.2: invalidate capability cache so next /capability/{factory_id}
    # call sees newly-detected canonical fields. This endpoint is the Java upload
    # path's terminus (Java POST /reclassify/{id} after upload completes), so it
    # complements the Python async path hook in excel_async.py.
    # Fire-and-forget — failure falls back to 5min TTL expiry.
    try:
        from smartbi.capability.api import _get_calculator
        _capability_calc = await _get_calculator()
        _capability_calc.invalidate(user_factory)
        logger.info(
            f"[reclassify] upload {upload_id}: invalidated capability cache for factory={user_factory}"
        )
    except Exception:
        logger.exception(
            f"[reclassify] capability invalidate failed for upload={upload_id}; "
            f"cache will naturally expire via 5min TTL"
        )

    return {
        "success": True,
        "upload_id": upload_id,
        "factory_id": user_factory,
        "total_fields": len(rows),
        "changes_count": len(changes),
        "changes": changes,
        "rematerialize": remat_result,
        "wall_ms": int((time.time() - t_start) * 1000),
    }
