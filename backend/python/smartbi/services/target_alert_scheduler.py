"""#58 Phase 1 — pace 预警计算 + 夜间批处理调度.

compute_current_period_alert: 取当前周期 (month/week/day/year) 的目标值 +
  周期内累计实际 (gold agg_daily) → compute_pace_alert → 落 restaurant_target_
  alerts (UPSERT, 同周期一条最新)。
run_nightly_alert_batch: 给定工厂 (或全部 active 工厂) + 每个 active period level
  跑一遍预警 + 触发 forecast 重算。fail-soft: 单工厂/单周期失败仅 log,不中断整批。

Cron (documented): 02:00 daily — wire to existing ETL trigger if present;
否则 standalone callable (见 main.py / 部署文档)。
"""
from __future__ import annotations

import logging
from datetime import date
from decimal import Decimal
from typing import Any, Dict, List, Optional

import asyncpg

from smartbi.gold.queries import _period_bounds, _period_key_for_target
from smartbi.services.target_decomposition import compute_pace_alert

logger = logging.getLogger(__name__)


async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    await conn.execute(
        "SELECT set_config('app.factory_id', $1, false)", factory_id
    )


async def compute_current_period_alert(
    pool: asyncpg.Pool,
    factory_id: str,
    *,
    kpi_kind: str = "revenue",
    store_id: Optional[int] = None,
    period_type: str = "month",
    today: Optional[date] = None,
    persist: bool = False,
) -> Dict[str, Any]:
    """Compute the pace alert for the *current* period and (optionally) persist.

    1. period_key = _period_key_for_target(today, period_type).
    2. target = restaurant_target_hierarchy[level=period_type, period_key].
    3. actual = SUM(net_amount|bill_count) over the period span elapsed so far
       from gold agg_daily.
    4. compute_pace_alert(actual, target, period_first, period_last, today).
    5. persist=True → UPSERT restaurant_target_alerts.

    Honest no-target: target unset → alert_level='NO_TARGET', amounts None,
    message guides the user to set a target. Never raises a false alarm.
    """
    if not factory_id:
        raise ValueError("factory_id required")
    today = today or date.today()
    period_key = _period_key_for_target(today, period_type)
    p_first, p_last = _period_bounds(period_key, period_type)
    agg_col = "net_amount" if kpi_kind == "revenue" else "bill_count"
    # actual covers the period up to min(today, p_last)
    actual_end = min(today, p_last)

    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)

        if store_id is None:
            trow = await conn.fetchrow(
                """
                SELECT target_value FROM restaurant_target_hierarchy
                 WHERE factory_id = $1 AND kpi_kind = $2 AND level = $3
                   AND period_key = $4 AND store_id IS NULL
                """,
                factory_id, kpi_kind, period_type, period_key,
            )
        else:
            trow = await conn.fetchrow(
                """
                SELECT target_value FROM restaurant_target_hierarchy
                 WHERE factory_id = $1 AND kpi_kind = $2 AND level = $3
                   AND period_key = $4 AND store_id = $5
                """,
                factory_id, kpi_kind, period_type, period_key, store_id,
            )

        if store_id is None:
            arow = await conn.fetchrow(
                f"""
                SELECT SUM({agg_col})::numeric(18,2) AS actual
                  FROM agg_daily
                 WHERE factory_id = $1 AND date BETWEEN $2 AND $3
                """,
                factory_id, p_first, actual_end,
            )
        else:
            arow = await conn.fetchrow(
                f"""
                SELECT SUM({agg_col})::numeric(18,2) AS actual
                  FROM agg_daily
                 WHERE factory_id = $1 AND date BETWEEN $2 AND $3
                   AND store_id = $4
                """,
                factory_id, p_first, actual_end, store_id,
            )

        target = (
            Decimal(str(trow["target_value"]))
            if trow and trow["target_value"] is not None
            else None
        )
        actual = (
            Decimal(str(arow["actual"]))
            if arow and arow["actual"] is not None
            else None
        )

        if target is None:
            result = {
                "factory_id": factory_id,
                "kpi_kind": kpi_kind,
                "period_type": period_type,
                "period_key": period_key,
                "store_id": store_id,
                "alert_level": "NO_TARGET",
                "target_amount": None,
                "actual_amount": (float(actual) if actual is not None else None),
                "completion_pct": None,
                "elapsed_pct": None,
                "pace_gap_pct": None,
                "message": f"{period_type} 目标未设置 ({period_key})，无法计算 pace。",
            }
            return result

        pace = compute_pace_alert(
            actual_amount=actual, target_amount=target,
            period_first=p_first, period_last=p_last, today=today,
        )
        result = {
            "factory_id": factory_id,
            "kpi_kind": kpi_kind,
            "period_type": period_type,
            "period_key": period_key,
            "store_id": store_id,
            "alert_level": pace["alert_level"],
            "target_amount": float(target),
            "actual_amount": (float(actual) if actual is not None else None),
            "completion_pct": pace["completion_pct"],
            "elapsed_pct": pace["elapsed_pct"],
            "pace_gap_pct": pace["pace_gap_pct"],
            "days_elapsed": pace["days_elapsed"],
            "days_total": pace["days_total"],
            "data_missing": pace["data_missing"],
        }

        if persist:
            try:
                await _persist_alert(conn, factory_id, store_id, result)
            except Exception as exc:  # fail-soft: never doom the request
                logger.warning("alert persist failed (non-fatal): %s", exc)

    return result


async def _persist_alert(
    conn: asyncpg.Connection,
    factory_id: str,
    store_id: Optional[int],
    result: Dict[str, Any],
) -> None:
    """UPSERT the latest alert for a (factory, period_type, period_key, store).

    Idempotent via the partial unique indexes (store NULL vs not-NULL).
    """
    conflict = (
        "(factory_id, period_type, period_key) WHERE store_id IS NULL"
        if store_id is None
        else "(factory_id, period_type, period_key, store_id) "
        "WHERE store_id IS NOT NULL"
    )
    async with conn.transaction():
        await conn.execute(
            f"""
            INSERT INTO restaurant_target_alerts
                (factory_id, period_type, period_key, store_id, alert_level,
                 actual_amount, target_amount, completion_pct, elapsed_pct,
                 pace_gap_pct, computed_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NOW())
            ON CONFLICT {conflict}
            DO UPDATE SET alert_level = EXCLUDED.alert_level,
                          actual_amount = EXCLUDED.actual_amount,
                          target_amount = EXCLUDED.target_amount,
                          completion_pct = EXCLUDED.completion_pct,
                          elapsed_pct = EXCLUDED.elapsed_pct,
                          pace_gap_pct = EXCLUDED.pace_gap_pct,
                          computed_at = NOW()
            """,
            factory_id, result["period_type"], result["period_key"], store_id,
            result["alert_level"], result.get("actual_amount"),
            result.get("target_amount"), result.get("completion_pct"),
            result.get("elapsed_pct"), result.get("pace_gap_pct"),
        )


async def run_nightly_alert_batch(
    pool: asyncpg.Pool,
    *,
    factory_ids: Optional[List[str]] = None,
    period_types: Optional[List[str]] = None,
    today: Optional[date] = None,
    forecast: bool = True,
) -> Dict[str, Any]:
    """Nightly batch: compute + persist pace alerts (and refresh forecasts).

    For each factory (explicit list, or every factory with target rows) and
    each period level, computes the current-period pace alert and persists it.
    When forecast=True, also recomputes + persists the chain-level rolling
    forecast. Fully fail-soft: an error on one (factory, period) is logged and
    skipped; the batch always returns a summary.

    Cron: invoke at 02:00 daily (see deploy docs). No external scheduler dep —
    a standalone async callable the ETL trigger / a cron `python -c` can call.
    """
    today = today or date.today()
    period_types = period_types or ["month", "week", "day"]

    if factory_ids is None:
        factory_ids = await _active_target_factories(pool)

    if not factory_ids:
        # Review IMPORTANT: under FORCE RLS the smartbi_user-scoped scan returns []
        # (no app.factory_id set), so a None-arg cron call would silently no-op while
        # looking healthy. Surface it loudly + return a non-empty errors list so
        # monitoring catches it. Cron MUST pass an explicit per-tenant factory_ids list.
        logger.error(
            "run_nightly_alert_batch: 0 factories resolved — likely FORCE-RLS hiding "
            "rows from the smartbi_user scan. Cron MUST pass an explicit factory_ids list."
        )
        return {
            "today": today.isoformat(),
            "factories": 0,
            "alerts_computed": 0,
            "forecasts_computed": 0,
            "errors": ["no_factories_resolved: pass explicit factory_ids (FORCE-RLS no-op guard)"],
        }

    summary = {
        "today": today.isoformat(),
        "factories": len(factory_ids),
        "alerts_computed": 0,
        "forecasts_computed": 0,
        "errors": [],
    }

    for fid in factory_ids:
        for ptype in period_types:
            try:
                await compute_current_period_alert(
                    pool, fid, period_type=ptype, today=today, persist=True,
                )
                summary["alerts_computed"] += 1
            except Exception as exc:  # fail-soft per (factory, period)
                logger.warning(
                    "[alert-batch] factory=%s period=%s failed: %s",
                    fid, ptype, exc,
                )
                summary["errors"].append(f"{fid}/{ptype}: {exc}")

        if forecast:
            try:
                from smartbi.services.target_forecast import forecast_revenue
                await forecast_revenue(
                    pool, fid, horizon_days=30, window_days=90, persist=True,
                )
                summary["forecasts_computed"] += 1
            except Exception as exc:  # fail-soft per factory
                logger.warning(
                    "[alert-batch] factory=%s forecast failed: %s", fid, exc
                )
                summary["errors"].append(f"{fid}/forecast: {exc}")

    return summary


async def _active_target_factories(pool: asyncpg.Pool) -> List[str]:
    """Distinct factories that have any target row (RLS-bypass via no GUC set).

    NOTE: restaurant_target_hierarchy has FORCE RLS, so a plain SELECT without
    app.factory_id returns nothing for a non-superuser. The nightly batch runs
    as the pool's role; if that role is not a BYPASSRLS / superuser, callers
    MUST pass an explicit factory_ids list. This helper is best-effort and
    returns [] when RLS hides all rows (the batch then no-ops with a clear
    summary rather than erroring).
    """
    async with pool.acquire() as conn:
        try:
            rows = await conn.fetch(
                "SELECT DISTINCT factory_id FROM restaurant_target_hierarchy"
            )
        except Exception as exc:
            logger.warning("active-factory scan failed: %s", exc)
            return []
    return [r["factory_id"] for r in rows]
