"""Shared helpers for /api/smartbi/{factory_id}/revenue-report/* endpoints.

Spec: docs/qa-specs/2026-05-12-qhj-revenue-report-design.md §7.3 + §10.7 + §11.3
Plan: docs/superpowers/plans/2026-05-12-qhj-revenue-report.md Task G1

Single source of truth for:
  - cache_key construction (with Gold materialized_at for invalidation)
  - _generate_with_cache (compute → render → cache → audit-log)
  - JWT / X-Internal-Secret factory_id extraction (for cross-factory guard)
  - audit log write helper

Cache strategy:
  Currently in-process cachetools.TTLCache (24h, 50 entries). Future: swap
  the backend to Redis with no call-site change — cache key + content stays
  the same. cache_key embeds Gold materialized_at so new uploads naturally
  invalidate stale entries.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import time
from io import BytesIO
from typing import Optional

from cachetools import TTLCache
from fastapi import HTTPException, Request

from smartbi.canonical.templates.qhj_revenue_report import (
    RevenueReportParams,
    compute_qhj_revenue_report,
)
from smartbi.services.excel_renderers import RENDERERS
from smartbi.services.excel_renderers._labels import LABELS
from smartbi.services.excel_renderers._metrics import (
    REPORT_CACHE_HIT,
    REPORT_CACHE_MISS,
    REPORT_GEN_ERRORS,
    REPORT_GEN_FILE_BYTES,
    REPORT_GEN_SECONDS,
)

logger = logging.getLogger(__name__)


# ─── In-process cache ───────────────────────────────────────────────────

# 50 keys × ~5 MB max xlsx = ~250 MB worst case; ttl 24h
REVENUE_REPORT_CACHE: TTLCache = TTLCache(maxsize=50, ttl=24 * 3600)


# ─── cache key ──────────────────────────────────────────────────────────

def compute_cache_key(params: RevenueReportParams, gold_ts: str) -> str:
    """revenue_report:{factory}:sha256(params):{gold_materialized_at}.

    Spec §7.3: gold_ts is the last segment so cache_key flips when fresh
    data arrives — old cache entry naturally misses on next lookup.
    """
    body = json.dumps({
        "store_ids": sorted(params.store_ids),
        "date_from": params.date_from.isoformat(),
        "date_to": params.date_to.isoformat(),
        "meal_periods": sorted(params.meal_periods or []),
        "include_yoy": params.include_yoy,
    }, sort_keys=True).encode("utf-8")
    h = hashlib.sha256(body).hexdigest()
    return f"revenue_report:{params.factory_id}:{h}:{gold_ts}"


# ─── _generate_with_cache ───────────────────────────────────────────────

async def _query_gold_freshness(pool, factory_id: str, params: RevenueReportParams) -> str:
    """MAX(computed_at) from agg_daily_order_type_meal for the requested range.

    Returns ISO string for embedding in cache_key. None-coalesced to a
    sentinel so cache_key is well-formed even when no Gold rows exist yet
    (first-time upload not yet materialized).
    """
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        gold_max = await conn.fetchval(
            """
            SELECT MAX(computed_at)
            FROM agg_daily_order_type_meal
            WHERE factory_id = $1
              AND date BETWEEN $2 AND $3
            """,
            factory_id, params.date_from, params.date_to,
        )
    return gold_max.isoformat() if gold_max else "no-data"


def _build_preview_data(template_data: dict, limit: int = 10) -> dict:
    """Trim block data to top N rows per table for inline preview.

    /prepare returns this so the Vue page can render <el-table> previews
    without round-tripping the full xlsx. Limit=10 keeps payload <50KB
    even for 50-store factories.
    """
    return {
        "block1_yoy": (template_data.get("block1_yoy") or [])[:limit],
        "block2_mom": (template_data.get("block2_mom") or [])[:limit],
        "block3_meal_split": (template_data.get("block3_meal_split") or [])[:limit],
        "meta": template_data.get("meta", {}),
    }


async def _generate_with_cache(
    pool, params: RevenueReportParams, user_id: str,
) -> tuple[str, dict, BytesIO, dict]:
    """Returns (cache_key, summary_dict, BytesIO of xlsx, preview_data dict).

    preview_data has block1/2/3 trimmed to first 10 rows + meta — used by
    /prepare to render inline tables in the Vue page (so the user sees the
    converted 3-table layout BEFORE downloading the full xlsx).

    Spec §11.3 metric semantics:
      cache hit  → cache_hit_counter.inc only
      cache miss → gen_seconds + file_bytes histograms + cache_miss_counter
    """
    t0 = time.time()

    gold_ts = await _query_gold_freshness(pool, params.factory_id, params)
    cache_key = compute_cache_key(params, gold_ts)

    cached = REVENUE_REPORT_CACHE.get(cache_key)
    # Cache value shape: (file_bytes, preview_data) tuple. Legacy entries
    # may still be raw bytes — treat them as "preview missing" and recompute.
    if isinstance(cached, tuple) and len(cached) == 2:
        cached_bytes, cached_preview = cached
        REPORT_CACHE_HIT.labels(report_type="qhj_revenue_v1").inc()
        summary = {
            "store_count": len(params.store_ids),
            "date_range": f"{params.date_from} - {params.date_to}",
            "gold_materialized_at": gold_ts,
            "file_size_bytes": len(cached_bytes),
            "cache_hit": True,
            "is_stale": False,
        }
        await _log_audit(
            pool, params, user_id, cache_key, summary,
            duration_ms=int((time.time() - t0) * 1000),
            status="ok",
        )
        return cache_key, summary, BytesIO(cached_bytes), cached_preview

    REPORT_CACHE_MISS.labels(report_type="qhj_revenue_v1").inc()

    # Compute + render (cache miss path).
    try:
        template_result = await compute_qhj_revenue_report(pool, params)
        renderer = RENDERERS["qhj_revenue_v1"]
        buf = renderer(template_result.data, labels=LABELS["zh-CN"])
        preview_data = _build_preview_data(template_result.data)
    except Exception as e:
        REPORT_GEN_ERRORS.labels(type=type(e).__name__).inc()
        await _log_audit(
            pool, params, user_id, cache_key, {},
            duration_ms=int((time.time() - t0) * 1000),
            status="error", error=str(e),
        )
        raise

    file_bytes = buf.getvalue()
    REPORT_GEN_FILE_BYTES.labels(report_type="qhj_revenue_v1").observe(len(file_bytes))
    REPORT_GEN_SECONDS.labels(report_type="qhj_revenue_v1", status="ok").observe(
        time.time() - t0
    )

    REVENUE_REPORT_CACHE[cache_key] = (file_bytes, preview_data)

    summary = {
        "store_count": len(params.store_ids),
        "date_range": f"{params.date_from} - {params.date_to}",
        "gold_materialized_at": gold_ts,
        "file_size_bytes": len(file_bytes),
        "cache_hit": False,
        "is_stale": False,
    }
    await _log_audit(
        pool, params, user_id, cache_key, summary,
        duration_ms=int((time.time() - t0) * 1000),
        status="ok",
    )
    return cache_key, summary, BytesIO(file_bytes), preview_data


# ─── audit log ──────────────────────────────────────────────────────────

async def _log_audit(
    pool,
    params: RevenueReportParams,
    user_id: str,
    cache_key: str,
    summary: dict,
    duration_ms: int,
    status: str,
    error: Optional[str] = None,
) -> None:
    """Best-effort insert into smart_bi_report_audit_log. Logs on failure
    but never raises — audit shouldn't break the user-visible path.
    """
    try:
        params_snapshot = {
            "store_ids": params.store_ids,
            "date_from": params.date_from.isoformat(),
            "date_to": params.date_to.isoformat(),
            "meal_periods": params.meal_periods,
            "include_yoy": params.include_yoy,
        }
        params_hash = hashlib.sha256(
            json.dumps(params_snapshot, sort_keys=True).encode("utf-8")
        ).hexdigest()
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", params.factory_id
            )
            await conn.execute(
                """
                INSERT INTO smart_bi_report_audit_log
                    (factory_id, report_type, generated_by, params_snapshot,
                     params_hash, cache_key, cache_hit, file_size_bytes,
                     duration_ms, status, error_message, gold_materialized_at)
                VALUES ($1, $2, $3, $4::jsonb, $5, $6, $7, $8, $9, $10, $11,
                        CASE WHEN $12 = 'no-data' THEN NULL
                             ELSE $12::timestamp END)
                """,
                params.factory_id, "qhj_revenue_v1", str(user_id),
                json.dumps(params_snapshot), params_hash, cache_key,
                summary.get("cache_hit", False),
                summary.get("file_size_bytes"),
                duration_ms, status, error,
                summary.get("gold_materialized_at"),
            )
    except Exception as e:
        logger.warning("[audit-log] insert failed (non-fatal): %s", e)


# ─── caller factory_id extraction ───────────────────────────────────────

def _extract_caller_factory_id(request: Request) -> str:
    """JWT Bearer / X-Internal-Secret factory_id resolution.

    Mirrors excel_async.py:auto-parse-status path so behavior is identical
    for users coming from the frontend (JWT) or Java internal (X-Internal-Secret).
    Returns factory_id string. Raises 401 if no credential.
    """
    caller = (
        getattr(request.state, "factory_id", None)
        if hasattr(request, "state") else None
    )
    if caller:
        return caller

    # X-Internal-Secret path (Java cretas-api calling Python).
    internal_secret = request.headers.get("x-internal-secret", "")
    expected = os.environ.get("INTERNAL_API_SECRET") or "cretas-internal-2026"
    if expected and internal_secret == expected:
        return request.headers.get("x-factory-id") or "INTERNAL"

    # Bearer JWT path (frontend).
    auth_header = request.headers.get("authorization", "")
    if auth_header.startswith("Bearer "):
        token = auth_header[7:]
        try:
            import jwt as pyjwt
            raw_secret = os.environ.get("JWT_SECRET", "default-secret")
            key_bytes = raw_secret.encode("utf-8")
            if len(key_bytes) < 32:
                key_bytes = key_bytes + b"\x00" * (32 - len(key_bytes))
            claims = pyjwt.decode(
                token, key_bytes,
                algorithms=["HS256"],
                options={"verify_exp": True},
            )
            return claims.get("factoryId")
        except Exception as e:
            logger.warning("[revenue-report-auth] JWT verify failed: %s", e)

    raise HTTPException(
        status_code=401,
        detail="Authentication required (Bearer token or X-Internal-Secret).",
    )


def _enforce_factory_match(url_factory_id: str, request: Request) -> str:
    """Resolves caller factory_id, raises 403 if it doesn't match URL.

    INTERNAL caller (Java) is allowed to act on any factory.
    """
    caller = _extract_caller_factory_id(request)
    if caller == "INTERNAL":
        return url_factory_id
    if caller != url_factory_id:
        raise HTTPException(
            status_code=403,
            detail=(
                f"factory_id mismatch: caller={caller!r} url={url_factory_id!r}"
            ),
        )
    return caller
