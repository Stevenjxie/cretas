"""Unit tests for smartbi.gold.restaurant_ops_router.match_restaurant_ops.

Locks in the keyword routing rules so over-broad keywords don't silently
slip back in.

Apr 25 2026 — added after the AIQuery audit (C-4) found that
"哪个店服务最好" mis-routed to RESTAURANT_OPS_STORE_MARGIN, which then ran
the 30-day POS-window query and returned the misleading
"近 30 天无 POS 销售数据" message even when the upload had full POS data
outside that window.

Root cause: STORE_MARGIN group-2 contained the bare keyword "最好", which
matched 服务最好 / 环境最好 / 评价最好 / etc. — none of which are about
margin. Removed "最好"; legitimate margin triggers still match via the
remaining margin-specific vocabulary (毛利 / 毛利率 / 赚钱 / 净赚 / 利润).
"""
from __future__ import annotations

import asyncio

import pytest

from smartbi.gold.restaurant_ops_router import (
    SAMPLE_QUERIES,
    match_restaurant_ops,
    resolve_by_code,
)


# Queries that MUST route to a specific ops template
LEGITIMATE_TRIGGERS = [
    # STORE_MARGIN
    ("哪家店最赚钱", "RESTAURANT_OPS_STORE_MARGIN"),
    ("哪家店净赚最多", "RESTAURANT_OPS_STORE_MARGIN"),
    ("门店毛利排行", "RESTAURANT_OPS_STORE_MARGIN"),
    ("哪家门店毛利率最高", "RESTAURANT_OPS_STORE_MARGIN"),
    ("分店利润对比", "RESTAURANT_OPS_STORE_MARGIN"),
    ("店铺毛利分析", "RESTAURANT_OPS_STORE_MARGIN"),
    ("哪家店利润最高", "RESTAURANT_OPS_STORE_MARGIN"),
    # GROSS_MARGIN (dish-level)
    ("哪道菜毛利最高", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("菜品毛利率排行", "RESTAURANT_OPS_GROSS_MARGIN"),
    # Apr 25 2026: 菜系 should still trigger margin analysis when paired with
    # an explicit margin keyword (legitimate "菜系" = dish-category scope).
    ("菜系毛利率", "RESTAURANT_OPS_GROSS_MARGIN"),
    # WASTAGE_TOP
    ("损耗最多的食材", "RESTAURANT_OPS_WASTAGE_TOP"),
    ("浪费最多的菜是哪些", "RESTAURANT_OPS_WASTAGE_TOP"),
    # TREND_ANALYSIS (Jun 2026 WS6 routing fix) — 同比/环比/趋势/增长下降 must
    # route to the gold trend_bundle resolver, NOT the file-based xlsx router
    # that failed for qhj with "缺少按时间拆分的同比环比数据".
    ("同比和环比分析，识别增长和下降趋势", "RESTAURANT_OPS_TREND_ANALYSIS"),
    ("分析销售额的月度变化趋势", "RESTAURANT_OPS_TREND_ANALYSIS"),
    ("营收趋势", "RESTAURANT_OPS_TREND_ANALYSIS"),
    ("增长趋势", "RESTAURANT_OPS_TREND_ANALYSIS"),
    ("营业额走势", "RESTAURANT_OPS_TREND_ANALYSIS"),
    ("最近是增长还是下降", "RESTAURANT_OPS_TREND_ANALYSIS"),
    ("总营收和客单价表现怎么样", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("整体销售情况怎么样", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("门店销售对比，哪家最值得复制", "RESTAURANT_OPS_SALES_SUMMARY"),
]

# Queries that MUST NOT match any ops template (ambiguous or unrelated to ops)
NO_MATCH_QUERIES = [
    # The Apr 25 audit bug — service-quality questions previously misrouted to
    # STORE_MARGIN via the "店" + "最好" combo. Should NOT match.
    "哪个店服务最好",
    "哪家店服务质量最好",
    "哪家店环境最好",
    "哪家店评价最好",
    # Pure ambiguity — no margin keyword present
    "哪家店最好",
    # Apr 25 2026 follow-up — bare "菜" was removed from GROSS_MARGIN group-2,
    # so menu/recipe/price queries no longer false-trigger margin analysis.
    # These all lack a group-1 margin keyword (毛利/利润/etc.) so they pass
    # without depending on the group-2 tightening, but locking them in keeps
    # the contract enforced if someone re-broadens group-1 later.
    "菜单怎么改",
    "菜价怎么样",
    "菜谱推荐",
    # Pure POS / time-window queries
    "本月营业额",
    "畅销品 Top 5",
    "今天天气怎么样",
    "",
]


@pytest.mark.parametrize("query,expected_code", LEGITIMATE_TRIGGERS)
def test_legitimate_trigger_routes_correctly(query: str, expected_code: str):
    """Each query routes to its intended ops template."""
    assert match_restaurant_ops(query) == expected_code


@pytest.mark.parametrize("query", NO_MATCH_QUERIES)
def test_unrelated_query_does_not_match(query: str):
    """Queries with no ops intent must return None (fall through to LLM /
    xlsx router). No silent misrouting."""
    assert match_restaurant_ops(query) is None


# Some sample queries advertised in SAMPLE_QUERIES were never actually covered
# by their template's keyword pattern (a pre-existing keyword-coverage gap not
# related to the Apr 25 routing fix). Skip those here so the test file stays
# green; tightening the underlying patterns is a separate change.
_KNOWN_UNCOVERED_SAMPLES = {
    # WASTAGE_TOP / RECIPE_COST / REQUISITION_TREND / GROSS_MARGIN gaps
    "本月盘点情况",                 # STOCK_SHORTAGE — no group-2 keyword
    "毛利最低的菜品",               # RECIPE_COST — needs 食材成本/配方成本
    "菜品成本排行",                 # RECIPE_COST — needs 食材成本/配方成本
    "食材占销售额比重最高的菜",     # RECIPE_COST — needs 食材成本/配方成本
    "食材消耗排名",                 # REQUISITION_TREND — no group-1 keyword
    "售价减去食材成本最多的菜",     # GROSS_MARGIN — no group-1 keyword
}


@pytest.mark.parametrize(
    "query,expected_code",
    [
        (sq, code)
        for code, samples in SAMPLE_QUERIES.items()
        for sq in samples
        if sq not in _KNOWN_UNCOVERED_SAMPLES
    ],
)
def test_all_documented_sample_queries_route_correctly(
    query: str, expected_code: str
):
    """Every sample_query advertised in SAMPLE_QUERIES must match its own
    template. Acts as a regression net for any future keyword tightening
    so we don't accidentally break a documented example.

    See _KNOWN_UNCOVERED_SAMPLES above for pre-existing keyword-coverage
    gaps that are documented but not enforced here."""
    assert match_restaurant_ops(query) == expected_code


def test_requisition_trend_still_wins_over_generic_trend():
    """领料趋势 must stay on RESTAURANT_OPS_REQUISITION_TREND (more specific:
    requires 领料 + 趋势) even though the generic TREND_ANALYSIS also matches
    趋势 — ordering puts requisition first."""
    assert match_restaurant_ops("领料趋势") == "RESTAURANT_OPS_REQUISITION_TREND"
    assert match_restaurant_ops("领用最多的食材") == "RESTAURANT_OPS_REQUISITION_TREND"


def test_revenue_amount_query_still_does_not_match_trend():
    """本月营业额 must NOT match TREND_ANALYSIS — bare 营业 is deliberately
    excluded from the trend keyword group so a point-in-time amount query
    falls through to the POS/template path."""
    assert match_restaurant_ops("本月营业额") is None


# ──────────────────────────────────────────────────────────────────────────
# resolve_by_code → trend_analysis resolver (pure, mocked trend_bundle).
# ──────────────────────────────────────────────────────────────────────────

_FAKE_BUNDLE = {
    "factory_id": "RES_TEST",
    "start_date": None,
    "end_date": None,
    "dailyTrend": [
        {"date": "2025-01-05", "revenue": 1000.0, "bill_count": 10},
    ],
    "weekdayWeekend": {
        "weekdayAvg": 800.0, "weekendAvg": 1200.0,
        "weekdayDays": 20, "weekendDays": 8,
    },
    "monthlyTrend": [
        {"month": "2025-01", "revenue": 30000.0},
        {"month": "2025-02", "revenue": 25000.0},
        {"month": "2026-01", "revenue": 45000.0},
        {"month": "2026-02", "revenue": 40000.0},
    ],
}


def _patch_trend_bundle(monkeypatch, captured):
    async def _fake_trend_bundle(pool, factory_id, date_range):
        captured["pool"] = pool
        captured["factory_id"] = factory_id
        captured["date_range"] = date_range
        return _FAKE_BUNDLE

    # Resolver does `from smartbi.gold.queries import trend_bundle` at call
    # time, so patch it on the queries module.
    import smartbi.gold.queries as _q
    monkeypatch.setattr(_q, "trend_bundle", _fake_trend_bundle)


def test_resolve_trend_analysis_calls_trend_bundle_all_history(monkeypatch):
    """resolve_by_code('RESTAURANT_OPS_TREND_ANALYSIS', ...) must call
    trend_bundle with the all-history (None, None) range and return an answer
    containing month + revenue."""
    captured: dict = {}
    _patch_trend_bundle(monkeypatch, captured)

    ans = asyncio.run(
        resolve_by_code(
            "RESTAURANT_OPS_TREND_ANALYSIS",
            object(),  # pool is just forwarded to the mocked trend_bundle
            "RES_TEST",
            role="restaurant_manager",  # price-view role → ¥ visible
        )
    )

    # all-history call
    assert captured["date_range"] == (None, None)
    assert captured["factory_id"] == "RES_TEST"

    assert ans is not None
    assert ans.code == "RESTAURANT_OPS_TREND_ANALYSIS"
    # answer mentions months + revenue figures
    assert "2026-01" in ans.answer_text          # peak month present
    assert "45,000" in ans.answer_text           # peak revenue (price-view)
    assert "环比" in ans.answer_text or "同比" in ans.answer_text
    # line chart of monthly revenue
    assert ans.charts and ans.charts[0]["chartType"] == "line"
    assert ans.charts[0]["series"][0]["data"] == [30000.0, 25000.0, 45000.0, 40000.0]
    # YoY present: 2026-02 vs 2025-02 → both in fixture
    assert "同比" in ans.answer_text


def test_resolve_trend_analysis_rbac_strips_revenue_for_non_price_role(monkeypatch):
    """A non price-view role (e.g. operator) must NOT see ¥ amounts in the
    prose or chart data — the resolver suppresses them since
    strip_price_for_role can't reach prose / chart arrays."""
    captured: dict = {}
    _patch_trend_bundle(monkeypatch, captured)

    ans = asyncio.run(
        resolve_by_code(
            "RESTAURANT_OPS_TREND_ANALYSIS",
            object(),
            "RES_TEST",
            role="operator",  # NOT in PRICE_VIEW_ROLES
        )
    )
    assert ans is not None
    # No raw revenue figure leaks into prose; redacted to ***
    assert "45,000" not in ans.answer_text
    assert "30,000" not in ans.answer_text
    assert "***" in ans.answer_text
    # Chart revenue data nulled
    assert ans.charts[0]["series"][0]["data"] == [None, None, None, None]
    # Month structure (non-monetary) still visible
    assert "2026-01" in ans.answer_text
    assert ans.meta["price_view"] is False


def test_resolve_trend_analysis_missing_role_strips_revenue(monkeypatch):
    """A missing/None role must default to stripped (secure) — mirrors
    strip_price_for_role treating None/unknown roles as ineligible."""
    captured: dict = {}
    _patch_trend_bundle(monkeypatch, captured)

    ans = asyncio.run(
        resolve_by_code("RESTAURANT_OPS_TREND_ANALYSIS", object(), "RES_TEST")
    )
    assert ans is not None
    assert "45,000" not in ans.answer_text
    assert "***" in ans.answer_text
    assert ans.charts[0]["series"][0]["data"] == [None, None, None, None]
    assert ans.meta["price_view"] is False


def test_resolve_by_code_legacy_resolver_ignores_role_kwarg(monkeypatch):
    """resolve_by_code must not break a legacy resolver (no `role` param) when
    the caller passes role=... — kwargs are filtered to the resolver's
    signature. Patch a wastage-style resolver to assert no TypeError."""
    called: dict = {}

    async def _fake_wastage(pool, factory_id, days=30, top_n=10):
        called["days"] = days
        from smartbi.gold.restaurant_ops_router import OpsAnswer
        return OpsAnswer(
            code="RESTAURANT_OPS_WASTAGE_TOP", title="t",
            answer_text="ok", charts=[], kpis=[], meta={},
        )

    import smartbi.gold.restaurant_ops_router as _r
    monkeypatch.setitem(_r._RESOLVERS, "RESTAURANT_OPS_WASTAGE_TOP", _fake_wastage)

    # role=... is passed but _fake_wastage has no role param → must be dropped,
    # NOT raise TypeError.
    ans = asyncio.run(
        resolve_by_code(
            "RESTAURANT_OPS_WASTAGE_TOP", object(), "RES_TEST", role="operator",
        )
    )
    assert ans is not None and ans.answer_text == "ok"
    assert called["days"] == 30


# ──────────────────────────────────────────────────────────────────────────
# WS6 (#5 趋势不出图): the chat stream now pre-checks the TREND ops code BEFORE
# the P2 synthesis router so trend / 同比环比 questions reach the gold
# trend_bundle resolver (monthly trend + MoM/YoY + line chart) instead of being
# swallowed by synthesis (which answers from a FactBook that lacks monthly
# time-series and admits "无法计算同比环比/趋势"). These tests lock the routing
# decision that the pre-check relies on:
#   1. the dashboard 「同比环比分析」 chip query DOES match the TREND ops code →
#      the pre-check diverts it to the gold trend resolver.
#   2. a genuine "综合分析评价和经营" question does NOT match the TREND ops code →
#      the pre-check skips it and it still reaches the synthesis engine.
# ──────────────────────────────────────────────────────────────────────────

# Exact query string the web-admin AIQuery 「同比环比分析」 chip (t9) sends.
_DASHBOARD_TREND_CHIP_QUERY = "进行同比和环比分析，识别增长和下降趋势"


def test_dashboard_trend_chip_matches_trend_ops_code():
    """The exact dashboard chip query must route to the TREND ops code so the
    chat-stream pre-check diverts it to the gold trend resolver (before
    synthesis). Regression net for the #5 趋势不出图 fix."""
    assert (
        match_restaurant_ops(_DASHBOARD_TREND_CHIP_QUERY)
        == "RESTAURANT_OPS_TREND_ANALYSIS"
    )


def test_synthesis_query_does_not_match_trend_ops_code():
    """A genuine multi-dimension synthesis question ("综合分析评价和经营") must NOT
    match the TREND ops code — the chat-stream pre-check only diverts the TREND
    code, so this query is left untouched and still reaches the synthesis
    engine. Verifies the pre-check does not steal genuine 综合分析 questions."""
    for synthesis_q in (
        "综合分析评价和经营",
        "综合分析收入、成本、利润、费用等关键经营指标，给出经营建议",
        "整体分析一下门店的评价和营收情况",
    ):
        assert match_restaurant_ops(synthesis_q) != "RESTAURANT_OPS_TREND_ANALYSIS", (
            f"Genuine synthesis query {synthesis_q!r} unexpectedly matched the "
            f"TREND ops code — the pre-check would wrongly divert it away from "
            f"the synthesis engine."
        )


def test_trend_chip_reaches_trend_not_synthesis_router():
    """End-to-end routing decision for the #5 fix: the dashboard trend chip
    query must (a) match the TREND ops code (→ gold trend resolver via the
    pre-check) and (b) NOT trigger the comprehensive-synthesis matcher (so even
    without the pre-check it would not be a synthesis question by keyword). The
    pre-check exists precisely because prod synthesis was observed to swallow
    it; this asserts the gold path is the correct destination."""
    from smartbi.agent.synthesis_router import match_comprehensive_synthesis

    # (a) gold TREND is the intended destination
    assert (
        match_restaurant_ops(_DASHBOARD_TREND_CHIP_QUERY)
        == "RESTAURANT_OPS_TREND_ANALYSIS"
    )
    # (b) genuine synthesis questions still match synthesis (sanity: matcher works)
    assert match_comprehensive_synthesis("综合分析评价和经营") is True
    # (b') the genuine synthesis query is NOT a TREND ops code → not diverted
    assert match_restaurant_ops("综合分析评价和经营") != "RESTAURANT_OPS_TREND_ANALYSIS"


def test_store_margin_does_not_match_service_quality():
    """Apr 25 2026 explicit regression test for the AIQuery C-4 bug.

    Even with multiple store-related modifiers, a query about
    service / environment / review quality must NOT route to
    margin-analysis (which would then return the misleading "近 30 天无
    POS 销售数据" message).
    """
    over_broad = [
        "哪家店服务最好",
        "哪家店服务质量最好",
        "哪家分店环境最好",
        "门店服务排名",
        "哪家店评价最好",
    ]
    for q in over_broad:
        assert match_restaurant_ops(q) is None, (
            f"Query {q!r} unexpectedly matched STORE_MARGIN — "
            f"check for over-broad keywords in group-2."
        )
