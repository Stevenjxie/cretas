"""Wastage answers must honor the *requested* window, not just its length.

Observed on prod (MOCK_REST, 2026-07-31), asking three different windows:

    问「上个月损耗金额最高的食材」  → 标题「近 30 天」, ¥215,561.26
    问「本月…」                    → 标题「近 31 天」, ¥220,515.85
    问「最近7天…」                  → 标题「近 7 天」,  ¥102,241.29

Three different amounts look like proof the date range was applied. It is not.
Querying Gold directly for that tenant:

    rolling 30d = 215,561.26   ← what 「上个月」 returned
    rolling 31d = 220,515.85   ← what 「本月」 returned
    rolling  7d = 102,241.29   ← what 「最近7天」 returned
    calendar June = 10,071.77  ← what 「上个月」 *should* have returned

The amounts differ only because the window *lengths* differ (30/31/7). The
requested range itself is discarded twice over:

  1. ``_resolver_kwargs`` collapses ``spec.date_range`` to
     ``days = (end - start).days + 1`` — start/end survive only as a duration.
  2. ``resolve_by_code`` filters kwargs down to each resolver's declared
     parameters, and ``resolve_wastage_top`` declared neither ``date_range``
     nor ``window_label`` — so both were silently dropped for this resolver.

The SQL then anchors on ``CURRENT_DATE - days``, i.e. always a window ending
today. Asking for June returns July.

Note the label 「近 30 天」 was *truthful* about what got computed; relabeling it
「上个月」 while keeping the rolling data would stamp a June heading onto July
numbers — precisely the failure mode that got the wastage section pulled from
the monthly report template (see reporting/template.py).

These tests pin the real fix: the requested start/end reach SQL, and the
heading reflects the requested window.
"""
from __future__ import annotations

from datetime import date
from typing import Any, Dict, List, Optional, Tuple

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    resolve_by_code,
    resolve_wastage_top,
)


class _RecordingConn:
    """Records every SQL string and its bound args."""

    def __init__(
        self,
        *,
        fetch_map: Optional[Dict[str, List[Any]]] = None,
        fetchrow_map: Optional[Dict[str, Any]] = None,
    ):
        self._fetch_map = fetch_map or {}
        self._fetchrow_map = fetchrow_map or {}
        self.fetch_calls: List[Tuple[str, tuple]] = []
        self.fetchrow_calls: List[Tuple[str, tuple]] = []

    async def execute(self, sql, *args):
        return "SET"

    async def fetch(self, sql, *args):
        self.fetch_calls.append((sql, args))
        for key, rows in self._fetch_map.items():
            if key in sql:
                return rows
        return []

    async def fetchrow(self, sql, *args):
        self.fetchrow_calls.append((sql, args))
        for key, row in self._fetchrow_map.items():
            if key in sql:
                return row
        return None


class _RecordingPool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


_TOP_ROWS = [
    {"name": "三文鱼", "category": "水产", "unit": "kg", "cost": 4200.0, "qty": 12.0},
    {"name": "土豆", "category": "蔬菜", "unit": "kg", "cost": 180.0, "qty": 300.0},
]
_TYPE_ROWS = [{"type": "SPOILED", "cost": 3000.0}]
_TOTALS = {"total_qty": 312.0, "total_cost": 4380.0, "total_count": 25}

_JUNE = (date(2026, 6, 1), date(2026, 6, 30))


def _pool_conn() -> _RecordingConn:
    return _RecordingConn(
        fetch_map={
            "JOIN dim_ingredient": _TOP_ROWS,
            "wastage_cost_by_type": _TYPE_ROWS,
        },
        fetchrow_map={"agg_restaurant_daily_totals": _TOTALS},
    )


def _all_calls(conn: _RecordingConn) -> List[Tuple[str, tuple]]:
    """Every window-bearing query the resolver issued (ranking, types, totals)."""
    return conn.fetch_calls + conn.fetchrow_calls


def test_resolver_declares_window_params_so_dispatch_cannot_drop_them():
    """Root cause #2, pinned structurally.

    ``resolve_by_code`` keeps only kwargs matching the resolver's signature.
    A resolver that does not declare these has them dropped *silently* — no
    error, just a rolling window. This assertion is what makes the drop loud.
    """
    import inspect

    params = inspect.signature(resolve_wastage_top).parameters
    assert "date_range" in params, "date_range would be filtered out by resolve_by_code"
    assert "window_label" in params, "window_label would be filtered out by resolve_by_code"


@pytest.mark.asyncio
async def test_explicit_range_is_sent_to_sql():
    """The requested start/end must reach PostgreSQL as bound parameters."""
    conn = _pool_conn()
    await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", days=30, date_range=_JUNE,
        window_label="上个月", query="上个月损耗金额最高的食材",
    )

    calls = _all_calls(conn)
    assert calls, "resolver issued no queries"
    for sql, args in calls:
        assert _JUNE[0] in args, (
            f"requested start 2026-06-01 never reached SQL; args={args}\n{sql}"
        )
        assert _JUNE[1] in args, (
            f"requested end 2026-06-30 never reached SQL; args={args}\n{sql}"
        )


@pytest.mark.asyncio
async def test_explicit_range_is_not_anchored_on_today():
    """A window ending today is the bug: asking for June must not return July.

    Guards the specific regression — an implementation that binds the dates but
    still ORs/ANDs in ``CURRENT_DATE - days`` would pass the args assertion
    above while still reading July.
    """
    conn = _pool_conn()
    await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", days=30, date_range=_JUNE,
        window_label="上个月", query="上个月损耗金额最高的食材",
    )

    for sql, args in _all_calls(conn):
        if "CURRENT_DATE" not in sql:
            continue
        # CURRENT_DATE may remain only as a COALESCE fallback for the
        # no-range case; it must not be the active bound when a range is given.
        assert "COALESCE" in sql, (
            "explicit range given but SQL still anchors the window on "
            f"CURRENT_DATE unconditionally:\n{sql}"
        )


@pytest.mark.asyncio
async def test_heading_reflects_requested_window_not_a_day_count():
    conn = _pool_conn()
    answer = await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", days=30, date_range=_JUNE,
        window_label="上个月", query="上个月损耗金额最高的食材",
    )

    assert "上个月" in answer.answer_text, answer.answer_text[:200]
    assert "近 30 天损耗总览" not in answer.answer_text, (
        "asking for 上个月 still headed 「近 30 天」 — the exact prod symptom"
    )
    assert "上个月" in answer.title, answer.title
    # The concrete dates make the window auditable rather than trusting a label.
    assert "2026-06-01" in answer.answer_text and "2026-06-30" in answer.answer_text


@pytest.mark.asyncio
async def test_meta_reports_the_window_actually_queried():
    conn = _pool_conn()
    answer = await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", days=30, date_range=_JUNE,
        window_label="上个月", query="上个月损耗金额最高的食材",
    )

    assert answer.meta.get("window_start") == "2026-06-01"
    assert answer.meta.get("window_end") == "2026-06-30"
    assert answer.meta.get("window_label") == "上个月"


@pytest.mark.asyncio
async def test_no_range_keeps_rolling_behaviour():
    """Backward compatibility: callers that pass only ``days`` are unchanged."""
    conn = _pool_conn()
    answer = await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", days=7, query="最近7天损耗最多的食材",
    )

    assert "近 7 天" in answer.answer_text
    assert answer.meta["window_days"] == 7
    for sql, _args in _all_calls(conn):
        assert "CURRENT_DATE" in sql, (
            "rolling fallback lost — a caller passing only days must still get "
            f"a window ending today:\n{sql}"
        )


@pytest.mark.asyncio
async def test_dispatch_delivers_range_to_wastage_resolver():
    """End-to-end through the dispatcher — where the kwargs were dropped.

    Mutating only the resolver signature back would make this fail, which is
    the point: the defect lived at the call site, not in the function body.
    """
    conn = _pool_conn()
    answer = await resolve_by_code(
        "RESTAURANT_OPS_WASTAGE_TOP",
        _RecordingPool(conn),
        "MOCK_REST",
        days=30,
        date_range=_JUNE,
        window_label="上个月",
        query="上个月损耗金额最高的食材",
        role="restaurant_manager",
    )

    assert answer is not None
    assert "上个月" in answer.answer_text
    assert any(_JUNE[0] in args for _sql, args in _all_calls(conn)), (
        "resolve_by_code filtered the requested range out again"
    )
