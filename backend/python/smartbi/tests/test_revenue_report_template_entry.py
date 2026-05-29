"""Tests for canonical.templates.qhj_revenue_report — entry point + params shape.

Spec: docs/qa-specs/2026-05-12-qhj-revenue-report-design.md §6.1-§6.2 + §11.2
Plan: docs/superpowers/plans/2026-05-12-qhj-revenue-report.md Task E1
"""
from datetime import date
from unittest.mock import AsyncMock, MagicMock

import pytest

from smartbi.canonical.templates.base import TemplateResult
from smartbi.canonical.templates.qhj_revenue_report import (
    RevenueReportParams,
    compute_qhj_revenue_report,
)


def test_params_defaults_yoy_enabled():
    """May 30 2026: YoY now enabled by default (2025 full + 2026 data loaded).

    include_yoy flipped True; Block 1 compares against the prev-year same
    range. When prev year has no data the LEFT JOIN yields total=0 → ratios
    None → UI '—' (graceful, not an error).
    """
    p = RevenueReportParams(
        factory_id="R_QINGHUAJIAO_REAL",
        store_ids=[1, 2],
        date_from=date(2025, 10, 1),
        date_to=date(2025, 10, 7),
    )
    assert p.meal_periods is None
    assert p.include_yoy is True


def test_params_accepts_meal_periods():
    p = RevenueReportParams(
        factory_id="R_QINGHUAJIAO_REAL",
        store_ids=[1],
        date_from=date(2025, 10, 1),
        date_to=date(2025, 10, 7),
        meal_periods=["午市", "晚市"],
    )
    assert p.meal_periods == ["午市", "晚市"]


def _make_pool_with_canned_data():
    """Pool yielding empty fetches so the 4 block stubs don't error."""
    conn = AsyncMock()
    conn.fetch = AsyncMock(return_value=[])
    conn.execute = AsyncMock(return_value="SET")
    conn.fetchval = AsyncMock(return_value=None)
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool = MagicMock()
    pool.acquire = MagicMock(return_value=ctx)
    return pool, conn


@pytest.mark.asyncio
async def test_compute_returns_template_result_with_4_blocks():
    pool, _ = _make_pool_with_canned_data()
    params = RevenueReportParams(
        factory_id="R_QINGHUAJIAO_REAL",
        store_ids=[1, 2],
        date_from=date(2025, 10, 1),
        date_to=date(2025, 10, 7),
    )
    result = await compute_qhj_revenue_report(pool, params)
    assert isinstance(result, TemplateResult)
    assert result.code == "qhj_revenue_report"
    assert result.title == "收入管理报表"
    for key in ("block1_yoy", "block2_mom", "block3_meal_split", "block4_diner_dist"):
        assert key in result.data, f"missing block {key}"


@pytest.mark.asyncio
async def test_meta_yoy_unavailable_when_prev_year_empty():
    """Empty prev-year fetch → yoy_available False + honest 'missing data' note.

    The mock pool returns empty fetches, so the prev-year (2024) query yields
    no rows → every prev_total is 0 → has_prev_data False. Report shows '—'
    with an honest note naming the missing year, not a false YoY claim.
    """
    pool, _ = _make_pool_with_canned_data()
    params = RevenueReportParams(
        factory_id="R_QINGHUAJIAO_REAL",
        store_ids=[1],
        date_from=date(2025, 10, 1),
        date_to=date(2025, 10, 7),
    )
    result = await compute_qhj_revenue_report(pool, params)
    meta = result.data["meta"]
    assert meta["yoy_available"] is False
    assert "2024" in meta["yoy_note"]  # honest note names the missing prev year
    assert meta["date_from"] == "2025-10-01"
    assert meta["date_to"] == "2025-10-07"


@pytest.mark.asyncio
async def test_meta_yoy_available_when_prev_year_has_data():
    """Prev-year fetch with data → yoy_available True + '同比基准' note.

    Only the period-agg SQL (Block 1/2) returns rows; Block 3/4 fetches stay
    empty so the meta-level YoY availability is what's under test, not the
    per-block shapes.
    """
    period_rows = [
        {"store_id": 1, "store_name": "店A", "total": 100000,
         "dine_in": 80000, "takeout": 20000},
    ]

    async def fetch_side_effect(sql, *args, **kwargs):
        # _PERIOD_AGG_SQL selects total/dine_in/takeout — feed those rows so
        # Block 1's prev-year query produces a positive prev_total.
        if "AS total" in sql and "AS dine_in" in sql and "dine_in_revenue" not in sql:
            return period_rows
        return []

    conn = AsyncMock()
    conn.fetch = AsyncMock(side_effect=fetch_side_effect)
    conn.execute = AsyncMock(return_value="SET")
    conn.fetchval = AsyncMock(return_value="店A")
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool = MagicMock()
    pool.acquire = MagicMock(return_value=ctx)

    params = RevenueReportParams(
        factory_id="R_QINGHUAJIAO_REAL",
        store_ids=[1],
        date_from=date(2026, 1, 1),
        date_to=date(2026, 1, 31),
        include_yoy=True,
    )
    result = await compute_qhj_revenue_report(pool, params)
    meta = result.data["meta"]
    assert meta["yoy_available"] is True
    assert "2025" in meta["yoy_note"]  # prev-year same period = 2025 for a 2026 report
    assert "同比基准" in meta["yoy_note"]
