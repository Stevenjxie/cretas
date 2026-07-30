"""Wastage ranking by cost — closes a declared-but-unimplemented capability.

``SAMPLE_QUERIES["RESTAURANT_OPS_WASTAGE_TOP"]`` has advertised "损耗金额排名"
since the template shipped, but the Gold layer only ever aggregated wastage
**cost by wastage_type** (``wastage_cost_by_type``) and wastage **quantity per
ingredient** (``wastage_qty``).  There was no per-ingredient cost aggregation at
all, so ``resolve_wastage_top`` could only ever rank by quantity.

Two distinct user-visible failures followed from that one gap:

1. "本月食材损耗成本是多少" was *refused*.  ``answer_contract`` derives a
   ``recipe_cost`` requirement from the word 成本 and then demands the answer
   text actually contain one of 菜品成本/食材成本/配方成本/成本.  The wastage
   answer said "损耗金额" and "损失 ¥…" but never "成本", so ``request_coverage``
   failed and the generic "没有可靠覆盖…" decline was returned.
2. "本月损耗金额最高的食材" was *silently answered on the wrong axis* — it
   passed the contract (only the 损耗 token was required) but returned the
   quantity ranking.  A cheap high-volume ingredient outranks an expensive one,
   so the answer named the wrong ingredient.  (2) is the more dangerous of the
   two: a refusal is visible, a wrong ranking is not.

These tests pin the fix: a per-ingredient ``wastage_cost`` KPI in the ETL, and
an axis chosen from the question wording in the resolver.
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

import pytest

import smartbi.gold.restaurant.restaurant_ops_etl as etl
from smartbi.gold.restaurant import answer_contract as _contract
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec
from smartbi.gold.restaurant.restaurant_ops_router import resolve_wastage_top


# ────────────────────────────────────────────────────────────────────
# Fakes — SQL-fragment keyed, and recording so we can assert the axis
# actually sent to PostgreSQL (ORDER BY is what does the ranking).
# ────────────────────────────────────────────────────────────────────


class _RecordingConn:
    def __init__(
        self,
        *,
        fetch_map: Optional[Dict[str, List[Any]]] = None,
        fetchrow_map: Optional[Dict[str, Any]] = None,
        execute_result: str = "INSERT 0 3",
    ):
        self._fetch_map = fetch_map or {}
        self._fetchrow_map = fetchrow_map or {}
        self._execute_result = execute_result
        self.fetch_calls: List[Tuple[str, tuple]] = []
        self.executed: List[str] = []

    async def execute(self, sql, *args):
        self.executed.append(sql)
        return self._execute_result

    async def fetch(self, sql, *args):
        self.fetch_calls.append((sql, args))
        for key, rows in self._fetch_map.items():
            if key in sql:
                return rows
        return []

    async def fetchrow(self, sql, *args):
        for key, row in self._fetchrow_map.items():
            if key in sql:
                return row
        return None

    def transaction(self):
        class _Tx:
            async def __aenter__(_self):
                return None

            async def __aexit__(_self, *exc):
                return False

        return _Tx()


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


# Two ingredients where the two axes disagree: 土豆 is the quantity leader,
# 三文鱼 is the cost leader. Any answer that confuses the axes names the wrong
# ingredient, which is exactly the silent failure mode being fixed.
_TOP_ROWS = [
    {"name": "三文鱼", "category": "水产", "unit": "kg", "cost": 4200.0, "qty": 12.0},
    {"name": "土豆", "category": "蔬菜", "unit": "kg", "cost": 180.0, "qty": 300.0},
]
_TYPE_ROWS = [
    {"type": "SPOILED", "cost": 3000.0},
    {"type": "EXPIRED", "cost": 1380.0},
]
_TOTALS = {"total_qty": 312.0, "total_cost": 4380.0, "total_count": 25}


def _resolver_pool() -> _RecordingConn:
    return _RecordingConn(
        fetch_map={
            "JOIN dim_ingredient": _TOP_ROWS,
            "wastage_cost_by_type": _TYPE_ROWS,
        },
        fetchrow_map={"agg_restaurant_daily_totals": _TOTALS},
    )


def _top_sql(conn: _RecordingConn) -> str:
    """The per-ingredient ranking query (the one that joins dim_ingredient)."""
    for sql, _args in conn.fetch_calls:
        if "JOIN dim_ingredient" in sql:
            return sql
    raise AssertionError("resolver never issued the per-ingredient ranking query")


# ────────────────────────────────────────────────────────────────────
# ETL — per-ingredient wastage cost aggregation
# ────────────────────────────────────────────────────────────────────


def test_wastage_cost_agg_sums_estimated_cost_per_ingredient():
    """New KPI mirrors _AGG_WASTAGE_QTY_SQL but sums money, not quantity."""
    sql = getattr(etl, "_AGG_WASTAGE_COST_SQL", None)
    assert sql, "_AGG_WASTAGE_COST_SQL is missing — no per-ingredient cost KPI"

    assert "'wastage_cost'" in sql
    assert "estimated_cost" in sql
    assert "GROUP BY factory_id, date, ingredient_id" in sql
    # Each fact table has its own status vocabulary; wastage counts only
    # APPROVED. Getting this wrong does not error, it silently zeroes the KPI.
    assert "status = 'APPROVED'" in sql
    # Must not accidentally reuse the by-type string dimension.
    assert "wastage_type" not in sql


@pytest.mark.asyncio
async def test_materialize_gold_daily_ops_runs_and_reports_wastage_cost():
    conn = _RecordingConn(execute_result="INSERT 0 7")
    stats = await etl.materialize_gold_daily_ops(_RecordingPool(conn), "MOCK_REST")

    assert "wastage_cost" in stats, f"stat key missing; got {sorted(stats)}"
    assert stats["wastage_cost"] == 7
    assert any("'wastage_cost'" in sql for sql in conn.executed), (
        "materialize_gold_daily_ops never executed the wastage_cost aggregation"
    )
    # The pre-existing quantity KPI must keep running alongside it.
    assert stats["wastage_qty"] == 7


# ────────────────────────────────────────────────────────────────────
# Resolver — axis chosen from the question wording
# ────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_cost_worded_question_ranks_wastage_by_cost():
    conn = _resolver_pool()
    answer = await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", query="本月损耗金额最高的食材是什么"
    )

    assert answer.meta["rank_axis"] == "cost"
    assert "ORDER BY cost" in _top_sql(conn)
    # The cost leader must be the headline, not the quantity leader.
    assert "三文鱼" in answer.answer_text
    assert answer.answer_text.index("三文鱼") < answer.answer_text.index("土豆")
    assert "按金额" in answer.answer_text


@pytest.mark.asyncio
async def test_plain_question_still_ranks_wastage_by_quantity():
    """Regression: the default axis stays quantity for non-money wording."""
    conn = _resolver_pool()
    answer = await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", query="最近7天损耗最多的食材是什么"
    )

    assert answer.meta["rank_axis"] == "qty"
    assert "ORDER BY qty" in _top_sql(conn)
    assert "按数量" in answer.answer_text


@pytest.mark.asyncio
async def test_wastage_axis_defaults_to_quantity_without_a_query():
    """Internal callers that pass no query keep the historical behaviour."""
    conn = _resolver_pool()
    answer = await resolve_wastage_top(_RecordingPool(conn), "MOCK_REST")

    assert answer.meta["rank_axis"] == "qty"
    assert "ORDER BY qty" in _top_sql(conn)


@pytest.mark.asyncio
async def test_cost_axis_lists_money_per_ingredient():
    """A money ranking has to show the money, otherwise it is unreadable."""
    conn = _resolver_pool()
    answer = await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", query="本月哪个食材损耗成本最高"
    )

    assert "¥4,200.00" in answer.answer_text or "¥4200.00" in answer.answer_text


# ────────────────────────────────────────────────────────────────────
# Contract — the 成本 wording must stop being refused
# ────────────────────────────────────────────────────────────────────


def _cost_spec() -> RestaurantQuerySpec:
    """Spec shaped like the parse of "本月全部门店食材损耗成本是多少".

    ``成本`` yields a ``recipe_cost`` requirement and ``损耗`` a ``wastage``
    one; ``request_coverage`` then demands both vocabularies appear in the
    answer text.
    """
    return RestaurantQuerySpec(
        intent="RESTAURANT_OPS_WASTAGE_TOP",
        domain="restaurant",
        date_range=(None, None),
        window_label="本月",
        relative_window=True,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="keyword",
        requested_metrics=("recipe_cost", "wastage"),
    )


@pytest.mark.asyncio
async def test_cost_axis_answer_satisfies_recipe_cost_coverage():
    conn = _resolver_pool()
    answer = await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", query="本月全部门店食材损耗成本是多少"
    )

    result = _contract.validate(_cost_spec(), answer.answer_text, answer.kpis, answer.meta)

    assert result.passed, (
        f"cost-worded wastage question still declined; missing={result.missing}"
    )


@pytest.mark.asyncio
async def test_cost_axis_discloses_when_the_kpi_is_not_materialized_yet():
    """No fake ¥0 ranking in the window between deploy and backfill.

    ``wastage_cost`` rows do not exist until ``materialize_gold_daily_ops``
    has run for a tenant.  Until then every ingredient reads ¥0, and ranking
    by cost would emit a confident ordering of zeros — a silent wrong answer.
    The daily totals table is computed straight from Silver, so a non-zero
    total with an all-zero per-ingredient breakdown identifies the gap exactly.
    """
    conn = _RecordingConn(
        fetch_map={
            "JOIN dim_ingredient": [
                {"name": "三文鱼", "category": "水产", "unit": "kg", "cost": 0.0, "qty": 12.0},
                {"name": "土豆", "category": "蔬菜", "unit": "kg", "cost": 0.0, "qty": 300.0},
            ],
            "wastage_cost_by_type": _TYPE_ROWS,
        },
        fetchrow_map={"agg_restaurant_daily_totals": _TOTALS},
    )
    answer = await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", query="本月损耗金额最高的食材"
    )

    assert answer.meta["cost_axis_unavailable"] is True
    assert "暂无" in answer.answer_text
    # The real money it DOES have (total + type split) is still reported.
    assert "4,380.00" in answer.answer_text or "4380.00" in answer.answer_text
    # But no per-ingredient money ranking is presented as fact.
    assert "1. **三文鱼**" not in answer.answer_text

    # Still honest enough to satisfy the contract rather than a bare decline.
    result = _contract.validate(_cost_spec(), answer.answer_text, answer.kpis, answer.meta)
    assert result.passed, f"missing={result.missing}"


def test_quantity_axis_answer_would_not_have_satisfied_it():
    """Documents the original defect: the qty-only wording lacks 成本.

    Guards against someone "fixing" coverage by sprinkling the word 成本 into
    the quantity answer instead of actually ranking by cost.
    """
    qty_only_text = (
        "近 30 天损耗总览:\n- 总损耗 25 次, 312.00 单位, 损失 **¥4380.00**\n"
        "损耗食材前 2 名（按数量）:\n\n1. 土豆 (蔬菜): 300.00 kg"
    )
    result = _contract.validate(_cost_spec(), qty_only_text, [], {})
    assert not result.passed
    assert "request_coverage" in result.missing
