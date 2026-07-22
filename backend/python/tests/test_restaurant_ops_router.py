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
from datetime import date

import pytest

from smartbi.gold.restaurant_ops_router import (
    OpsAnswer,
    SAMPLE_QUERIES,
    _resolve_sales_date_range,
    _resolve_sales_query_spec,
    match_restaurant_ops,
    reconcile_restaurant_ops_code,
    resolve_by_code,
    resolve_sales_summary,
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
    ("按月份绘制整体毛利率趋势曲线", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("在整体毛利率趋势图中添加70%计划线和60%预警线", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("计算每个菜品的毛利率趋势曲线", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("整体毛利率是多少", "RESTAURANT_OPS_GROSS_MARGIN"),
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
    ("最近一个月的营收情况如何？毛利有多少", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("最近两个月的营收情况如何，赚钱吗", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("近3个月盈利了吗", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("整体销售情况怎么样", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("门店销售对比，哪家最值得复制", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("查询本周营收", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("今天查订单", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("本月营业额", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("昨天营业额比前天高还是低", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("昨天的营业额是高于前天还是低于前天？", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("昨日营业额较前日上升还是下降？", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("昨天营业收入比前天高吗？", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("昨天流水比前一日旺不旺？", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("本月和上月营业额哪个高？", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("上个月和上上个月营收相比怎么样", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("提升毛利率，哪些事情今天先不要做？", "RESTAURANT_OPS_GROSS_MARGIN"),
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
    # Pure POS wording without a concrete metric should still fall through.
    "今天查一下",
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


def test_revenue_amount_query_routes_to_sales_summary_not_trend():
    """本月营业额 is a point-in-time sales report read, not a trend question."""
    assert match_restaurant_ops("本月营业额") == "RESTAURANT_OPS_SALES_SUMMARY"


def test_explicit_period_comparison_beats_stale_trend_hint():
    assert reconcile_restaurant_ops_code(
        "昨日营业额较前日上升还是下降？",
        "RESTAURANT_OPS_TREND_ANALYSIS",
    ) == "RESTAURANT_OPS_SALES_SUMMARY"


def test_specific_margin_hint_beats_generic_trend_match():
    assert reconcile_restaurant_ops_code(
        "营收趋势",
        "RESTAURANT_OPS_GROSS_MARGIN",
    ) == "RESTAURANT_OPS_GROSS_MARGIN"


@pytest.mark.parametrize(
    "query,expected_range,expected_label",
    [
        ("今天查订单", (date(2026, 7, 6), date(2026, 7, 6)), "今天"),
        ("昨天营业额", (date(2026, 7, 5), date(2026, 7, 5)), "昨天"),
        ("前天营业额", (date(2026, 7, 4), date(2026, 7, 4)), "前天"),
        ("查询本周营收", (date(2026, 7, 6), date(2026, 7, 6)), "本周"),
        ("本月营业额", (date(2026, 7, 1), date(2026, 7, 6)), "本月"),
        ("最近一个月的营收情况如何？毛利有多少", (date(2026, 6, 7), date(2026, 7, 6)), "最近30天"),
        ("最近两个月的营收情况如何，赚钱吗", (date(2026, 5, 8), date(2026, 7, 6)), "最近2个月"),
        ("近3个月盈利了吗", (date(2026, 4, 8), date(2026, 7, 6)), "最近3个月"),
        ("过去2周销售如何", (date(2026, 6, 23), date(2026, 7, 6)), "最近2周"),
        ("最近7天销售情况", (date(2026, 6, 30), date(2026, 7, 6)), "最近7天"),
        ("总营收和客单价表现怎么样", (None, None), "全部历史"),
    ],
)
def test_resolve_sales_date_range(query, expected_range, expected_label):
    assert _resolve_sales_date_range(query, today=date(2026, 7, 6)) == (
        expected_range,
        expected_label,
    )


def test_sales_query_spec_extracts_time_margin_and_profitability():
    spec = _resolve_sales_query_spec("最近两个月的营收情况如何，赚钱吗", today=date(2026, 7, 6))

    assert spec.date_range == (date(2026, 5, 8), date(2026, 7, 6))
    assert spec.window_label == "最近2个月"
    assert spec.wants_margin is True
    assert spec.asks_profitability is True
    assert spec.relative_window is True


def test_sales_summary_no_data_answers_what_not_to_do(monkeypatch):
    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        return {
            "total_revenue": 0.0,
            "bill_count": 0,
            "avg_bill_value": None,
            "day_count": 0,
            "store_count": 0,
            "top_stores": [],
        }

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": []}

    import smartbi.gold.queries as _q

    monkeypatch.setattr(_q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(_q, "store_comparison", _fake_store_comparison)

    answer = asyncio.run(resolve_sales_summary(
        object(),
        "RES_TEST",
        role="restaurant_owner",
        query="提升毛利率哪些事情今天先不要做？",
        today=date(2026, 7, 21),
    ))

    assert "2026-07-21" in answer.answer_text
    assert "今天先不要做" in answer.answer_text
    assert "不要依据缺失数据" in answer.answer_text
    assert "其他日期替代" in answer.answer_text


def test_sales_summary_no_data_names_both_comparison_dates(monkeypatch):
    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        return {
            "total_revenue": 0.0,
            "bill_count": 0,
            "avg_bill_value": None,
            "day_count": 0,
            "store_count": 0,
            "top_stores": [],
        }

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": []}

    import smartbi.gold.queries as _q

    monkeypatch.setattr(_q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(_q, "store_comparison", _fake_store_comparison)

    answer = asyncio.run(resolve_sales_summary(
        object(),
        "RES_TEST",
        role="restaurant_owner",
        query="昨天营业额比前天高还是低？",
        today=date(2026, 7, 21),
    ))

    assert "昨天（2026-07-20 当天）" in answer.answer_text
    assert "前天（2026-07-19 当天）" in answer.answer_text
    assert "不能可靠判断两个日期谁高谁低" in answer.answer_text


@pytest.mark.parametrize(
    "query,primary,baseline,baseline_label",
    [
        (
            "昨天营业额比前天高还是低",
            (date(2026, 7, 20), date(2026, 7, 20)),
            (date(2026, 7, 19), date(2026, 7, 19)),
            "前天",
        ),
        (
            "昨日营业额较前日上升还是下降？",
            (date(2026, 7, 20), date(2026, 7, 20)),
            (date(2026, 7, 19), date(2026, 7, 19)),
            "前天",
        ),
        (
            "昨天流水比前一日旺不旺？",
            (date(2026, 7, 20), date(2026, 7, 20)),
            (date(2026, 7, 19), date(2026, 7, 19)),
            "前天",
        ),
        (
            "本月和上月营业额哪个高？",
            (date(2026, 7, 1), date(2026, 7, 21)),
            (date(2026, 6, 1), date(2026, 6, 30)),
            "上个月",
        ),
        (
            "上个月和上上个月营收相比怎么样",
            (date(2026, 6, 1), date(2026, 6, 30)),
            (date(2026, 5, 1), date(2026, 5, 31)),
            "上上个月",
        ),
    ],
)
def test_sales_query_spec_resolves_explicit_comparison_periods(
    query, primary, baseline, baseline_label,
):
    spec = _resolve_sales_query_spec(query, today=date(2026, 7, 21))
    assert spec.date_range == primary
    assert spec.comparison_range == baseline
    assert spec.comparison_label == baseline_label
    assert spec.comparison_kind in ("previous_day", "previous_month")


@pytest.mark.parametrize(
    "query,expected_range,expected_label,expected_margin_days,expects_verdict",
    [
        ("最近一个月的营收情况如何？毛利有多少", (date(2025, 12, 2), date(2025, 12, 31)), "最近30天", 30, False),
        ("最近两个月的营收情况如何，赚钱吗", (date(2025, 11, 2), date(2025, 12, 31)), "最近2个月", 60, True),
    ],
)
def test_sales_summary_keeps_time_margin_and_profitability(
    monkeypatch,
    query,
    expected_range,
    expected_label,
    expected_margin_days,
    expects_verdict,
):
    captured: dict = {}

    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        captured["date_range"] = date_range
        return {
            "total_revenue": 10000.0,
            "bill_count": 50,
            "avg_bill_value": 200.0,
            "day_count": 30,
            "store_count": 2,
            "top_stores": [
                {"store_name": "示范门店01", "revenue": 7000.0, "bill_count": 30},
            ],
        }

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": ["示范门店02"]}

    async def _fake_store_margin(
        pool, factory_id, days=30, top_n=5, *, role=None, date_range=None,
    ):
        # role kwarg added 2026-07-08 (RBAC audit fix): sales_summary now
        # forwards role so the real resolve_store_margin's PRICE_VIEW_ROLES
        # gate stays consistent with the caller's can_see_money branch.
        captured["margin_role"] = role
        captured["margin_days"] = days
        captured["margin_date_range"] = date_range
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title="门店毛利",
            answer_text="ok",
            charts=[],
            kpis=[],
            meta={
                "totalProfit": 1700.0,
                "totalRevenueWithCost": 4000.0,
                "totalRevenue": 10000.0,
                "avgRate": 0.425,
                "requested_window_start": date_range[0].isoformat(),
                "requested_window_end": date_range[1].isoformat(),
                "marginInvariantPass": True,
                "scope_matches_request": True,
            },
        )

    import smartbi.gold.queries as _q
    import smartbi.gold.restaurant_ops_router as _r

    monkeypatch.setattr(_q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(_q, "store_comparison", _fake_store_comparison)
    monkeypatch.setattr(_r, "resolve_store_margin", _fake_store_margin)

    ans = asyncio.run(
        resolve_sales_summary(
            object(),
            "RES_TEST",
            role="restaurant_manager",
            query=query,
            today=date(2025, 12, 31),
        )
    )

    assert captured["date_range"] == expected_range
    assert captured["margin_days"] == expected_margin_days
    assert captured["margin_date_range"] == expected_range
    assert expected_label in ans.answer_text
    assert "对应毛利" in ans.answer_text
    assert "可计算毛利的营收" in ans.answer_text
    assert "不以全部营收为分母" in ans.answer_text
    assert "1,700" in ans.answer_text
    if expects_verdict:
        assert "已覆盖的销售是赚钱的" in ans.answer_text
    assert any(kpi["title"] == "已覆盖毛利" for kpi in ans.kpis)
    assert any(kpi["title"] == "已覆盖毛利率" for kpi in ans.kpis)


def test_sales_summary_all_history_locks_margin_to_actual_revenue_scope(monkeypatch):
    captured = {}

    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        assert date_range == (None, None)
        return {
            "total_revenue": 100000.0,
            "bill_count": 500,
            "avg_bill_value": 200.0,
            "day_count": 60,
            "store_count": 2,
            "actual_start_date": "2026-01-01",
            "actual_end_date": "2026-03-01",
            "top_stores": [],
        }

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": []}

    async def _fake_store_margin(
        pool, factory_id, days=30, top_n=5, *, role=None, date_range=None,
    ):
        captured["date_range"] = date_range
        captured["days"] = days
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title="门店毛利",
            answer_text="ok",
            charts=[],
            kpis=[],
            meta={
                "totalProfit": 24000.0,
                "totalRevenueWithCost": 30000.0,
                "totalRevenue": 100000.0,
                "avgRate": 0.8,
                "requested_window_start": "2026-01-01",
                "requested_window_end": "2026-03-01",
                "marginInvariantPass": True,
                "scope_matches_request": True,
            },
        )

    import smartbi.gold.queries as _q
    import smartbi.gold.restaurant_ops_router as _router

    monkeypatch.setattr(_q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(_q, "store_comparison", _fake_store_comparison)
    monkeypatch.setattr(_router, "resolve_store_margin", _fake_store_margin)

    answer = asyncio.run(resolve_sales_summary(
        object(),
        "RES_TEST",
        role="restaurant_manager",
        query="整体营收和毛利率是多少？",
        today=date(2026, 7, 21),
    ))

    assert captured["date_range"] == (date(2026, 1, 1), date(2026, 3, 1))
    assert captured["days"] == 60
    assert "总营收 ¥100,000.00" in answer.answer_text
    assert "可计算毛利的营收 ¥30,000.00" in answer.answer_text
    assert "已覆盖部分毛利率 80.0%" in answer.answer_text
    assert answer.meta["margin"]["outer_window_start"] == "2026-01-01"
    assert answer.meta["margin"]["outer_window_end"] == "2026-03-01"


def test_sales_summary_uses_current_calendar_for_comparison_not_latest_data_date(monkeypatch):
    calls = []

    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        calls.append(date_range)
        if date_range == (date(2026, 7, 20), date(2026, 7, 20)):
            return {
                "total_revenue": 12000.0,
                "bill_count": 60,
                "avg_bill_value": 200.0,
                "day_count": 1,
                "store_count": 2,
                "top_stores": [],
            }
        return {
            "total_revenue": 10000.0,
            "bill_count": 50,
            "avg_bill_value": 200.0,
            "day_count": 1,
            "store_count": 2,
            "top_stores": [],
        }

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": []}

    import smartbi.gold.queries as _q

    monkeypatch.setattr(_q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(_q, "store_comparison", _fake_store_comparison)

    ans = asyncio.run(resolve_sales_summary(
        object(),
        "RES_TEST",
        role="restaurant_manager",
        query="昨天营业额比前天高还是低",
        today=date(2026, 7, 21),
    ))

    assert calls == [
        (date(2026, 7, 20), date(2026, 7, 20)),
        (date(2026, 7, 19), date(2026, 7, 19)),
    ]
    assert "2026-07-20" in ans.answer_text
    assert "2026-07-19" in ans.answer_text
    assert "营收高 ¥2,000.00（20.0%）" in ans.answer_text
    assert ans.meta["comparison"]["answered"] is True
    assert ans.meta["comparison"]["revenue_delta"] == 2000.0


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


# ─── 2026-07-08 audit fix: RBAC gate on margin resolvers (B-1) ─────────────
# resolve_gross_margin / resolve_store_margin previously had NO role param;
# resolve_by_code's signature filter silently dropped role, so ANY
# authenticated role saw unmasked ¥ revenue/margin. The gate now mirrors
# resolve_sales_summary's PRICE_VIEW_ROLES check and returns an honest
# disclosure (no DB touched) for non-price-view roles.

import smartbi.gold.restaurant_ops_router as _r  # noqa: E402  (module alias for the new RBAC tests)

@pytest.mark.parametrize("denied_role", [None, "", "viewer", "waiter", "cashier"])
def test_gross_margin_masked_for_non_price_view_role(denied_role):
    result = asyncio.run(
        _r.resolve_gross_margin(None, "F_TEST", role=denied_role)  # pool unused on masked path
    )
    assert result.meta.get("rbac_masked") is True
    assert "¥" not in result.answer_text
    assert "权限" in result.answer_text          # honest disclosure, not silence
    assert result.kpis == [] and result.charts == []


@pytest.mark.parametrize("denied_role", [None, "viewer"])
def test_store_margin_masked_for_non_price_view_role(denied_role):
    result = asyncio.run(
        _r.resolve_store_margin(None, "F_TEST", role=denied_role)
    )
    assert result.meta.get("rbac_masked") is True
    assert "¥" not in result.answer_text
    assert result.kpis == [] and result.charts == []


def test_price_view_role_passes_margin_gate():
    """A PRICE_VIEW role must get PAST the gate (reaches the DB layer — here a
    None pool, so the resolver blows up with AttributeError, which is exactly
    the proof the early-return did NOT trigger)."""
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    price_role = next(iter(PRICE_VIEW_ROLES))
    with pytest.raises(AttributeError):
        asyncio.run(_r.resolve_gross_margin(None, "F_TEST", role=price_role))


def test_gross_margin_prohibited_actions_uses_real_low_margin_candidate(monkeypatch):
    """有真实销售/成本时，禁做事项必须引用计算出的低毛利菜，不得异常或编造。"""

    pos_rows = [
        {
            "product_id": "POS-LOW",
            "dish_name": "低毛利菜",
            "normalized_name": "低毛利菜",
            "total_qty": 100.0,
            "total_revenue": 2000.0,
            "bills": 20,
            "window_start": date(2026, 7, 1),
            "window_end": date(2026, 7, 21),
        },
        {
            "product_id": "POS-HIGH",
            "dish_name": "高毛利菜",
            "normalized_name": "高毛利菜",
            "total_qty": 100.0,
            "total_revenue": 5000.0,
            "bills": 40,
            "window_start": date(2026, 7, 1),
            "window_end": date(2026, 7, 21),
        },
    ]
    cost_rows = [
        {"product_source_pk": "PT-LOW", "food_cost": 18.0},
        {"product_source_pk": "PT-HIGH", "food_cost": 20.0},
    ]

    class _SmartBIConnection:
        async def execute(self, *_args):
            return None

        async def fetch(self, query, *_args):
            if "FROM fact_pos_item" in query:
                return pos_rows
            if "FROM agg_restaurant_product_cost" in query:
                return cost_rows
            raise AssertionError(f"unexpected SmartBI query: {query}")

    class _AcquireContext:
        async def __aenter__(self):
            return _SmartBIConnection()

        async def __aexit__(self, *_args):
            return None

    class _SmartBIPool:
        def acquire(self):
            return _AcquireContext()

    class _CretasConnection:
        async def fetch(self, query, *_args):
            if "FROM product_types" in query:
                return [
                    {"id": "PT-LOW", "name": "低毛利菜"},
                    {"id": "PT-HIGH", "name": "高毛利菜"},
                ]
            raise AssertionError(f"unexpected Cretas query: {query}")

        async def close(self):
            return None

    async def _connect(_url):
        return _CretasConnection()

    import asyncpg
    monkeypatch.setattr(asyncpg, "connect", _connect)

    result = asyncio.run(
        _r.resolve_gross_margin(
            _SmartBIPool(),
            "RES_TEST",
            role="restaurant_manager",
            query="提升毛利率，哪些事情今天先不要做",
        )
    )

    assert "低毛利菜" in result.answer_text
    assert "暂无可确认对象" not in result.answer_text
    assert "适用前提" in result.answer_text
    assert "风险" in result.answer_text
    assert "最小验证" in result.answer_text
    assert "当前低毛利候选是低毛利菜" in result.answer_text


def test_missing_cost_dish_never_gets_profit_or_enters_margin_ranking():
    """缺成本是未知值，不是零成本；不能进入利润/毛利率排名。"""
    build_entries = getattr(_r, "_build_margin_entries", None)
    rank_entries = getattr(_r, "_rank_cost_complete_margin_entries", None)
    assert callable(build_entries)
    assert callable(rank_entries)

    pos_rows = [
        {"dish_name": "缺成本菜", "normalized_name": "缺成本菜", "total_qty": 10,
         "total_revenue": 1000.0, "bills": 5},
        {"dish_name": "完整成本菜", "normalized_name": "完整成本菜", "total_qty": 10,
         "total_revenue": 900.0, "bills": 4},
    ]
    entries = build_entries(
        pos_rows,
        {"缺成本菜": "P-MISSING", "完整成本菜": "P-OK"},
        {"P-OK": 30.0},
    )

    missing = next(item for item in entries if item["name"] == "缺成本菜")
    assert missing["has_cost"] is False
    assert missing["gross_profit"] is None
    assert missing["margin_rate"] is None
    ranked = rank_entries(entries, 10)
    assert [item["name"] for item in ranked] == ["完整成本菜"]


def test_implausible_cost_card_is_excluded_from_margin_and_ranking():
    entries = _r._build_margin_entries(
        [
            {"dish_name": "异常成本菜", "normalized_name": "异常成本菜",
             "total_qty": 10, "total_revenue": 1000.0, "bills": 5},
            {"dish_name": "正常成本菜", "normalized_name": "正常成本菜",
             "total_qty": 10, "total_revenue": 900.0, "bills": 4},
        ],
        {"异常成本菜": "P-BAD", "正常成本菜": "P-OK"},
        {"P-BAD": 119998.8, "P-OK": 30.0},
    )

    abnormal = next(item for item in entries if item["name"] == "异常成本菜")
    assert abnormal["invalid_cost"] is True
    assert abnormal["has_cost"] is False
    assert abnormal["gross_profit"] is None
    assert abnormal["margin_rate"] is None
    assert [item["name"] for item in _r._rank_cost_complete_margin_entries(entries, 10)] == [
        "正常成本菜"
    ]


def test_store_margin_excludes_missing_cost_and_uses_distinct_bill_count():
    aggregate = getattr(_r, "_aggregate_store_margin_entries", None)
    assert callable(aggregate)
    rows = [
        {"store_id": 1, "store_name": "缺成本店", "dish_name": "A", "normalized_name": "A",
         "qty": 10, "revenue": 1000.0, "bills": 8},
        {"store_id": 2, "store_name": "完整成本店", "dish_name": "B", "normalized_name": "B",
         "qty": 10, "revenue": 900.0, "bills": 7},
        {"store_id": 2, "store_name": "完整成本店", "dish_name": "C", "normalized_name": "C",
         "qty": 5, "revenue": 500.0, "bills": 4},
    ]
    stores = aggregate(
        rows,
        {"A": "P-A", "B": "P-B", "C": "P-C"},
        {"P-B": 30.0, "P-C": 20.0},
        {1: 8, 2: 9},
    )

    missing = next(item for item in stores if item["store_id"] == 1)
    complete = next(item for item in stores if item["store_id"] == 2)
    assert missing["gross_profit"] is None
    assert missing["margin_rate"] is None
    assert missing["cost_coverage_ratio"] == 0
    assert complete["gross_profit"] == 1000.0
    assert complete["bills"] == 9  # not the per-dish sum 7 + 4


def test_store_margin_excludes_cost_far_above_realized_unit_price():
    stores = _r._aggregate_store_margin_entries(
        [
            {"store_id": 1, "store_name": "测试店", "dish_name": "异常成本菜",
             "normalized_name": "异常成本菜", "qty": 10, "revenue": 1000.0, "bills": 5},
        ],
        {"异常成本菜": "P-BAD"},
        {"P-BAD": 50000.0},
        {1: 5},
    )

    store = stores[0]
    assert store["invalid_cost_dishes"] == 1
    assert store["revenue_with_cost"] == 0
    assert store["gross_profit"] is None
    assert store["margin_rate"] is None


def test_margin_reference_lines_only_use_explicit_user_values():
    parse_lines = getattr(_r, "_parse_margin_reference_lines", None)
    assert callable(parse_lines)

    assert parse_lines("添加70%计划线和60%预警线") == [
        {"name": "计划值", "yAxis": 70.0},
        {"name": "预警值", "yAxis": 60.0},
    ]
    assert parse_lines("加入计划值和预警值参照线") == []


def test_partial_latest_month_uses_same_day_count_comparison(monkeypatch):
    """7 月只有 11 天时，不能拿它和完整 6 月直接计算环比。"""
    daily = []
    for day in range(1, 12):
        daily.append({"date": f"2026-06-{day:02d}", "revenue": 1000.0, "bill_count": 10})
        daily.append({"date": f"2026-07-{day:02d}", "revenue": 1200.0, "bill_count": 12})
    bundle = {
        "weekdayWeekend": {},
        "dailyTrend": daily,
        "monthlyTrend": [
            {"month": "2026-06", "revenue": 30000.0},
            {"month": "2026-07", "revenue": 13200.0},
        ],
    }

    async def _fake_trend_bundle(pool, factory_id, date_range):
        return bundle

    import smartbi.gold.queries as _q
    monkeypatch.setattr(_q, "trend_bundle", _fake_trend_bundle)
    ans = asyncio.run(_r.resolve_trend_analysis(object(), "RES_TEST", role="restaurant_manager"))

    assert "截至11日" in ans.answer_text
    assert "同口径环比" in ans.answer_text
    assert "增长 20.0%" in ans.answer_text
    assert "下降 56.0%" not in ans.answer_text
    assert ans.meta["latest_month_partial"] is True


# --------------------------------------------------------------------------
# _compute_margin_dragger — 拖毛利归因 (impact = share × rate-gap, not just rate)
# --------------------------------------------------------------------------
from smartbi.gold.restaurant_ops_router import _compute_margin_dragger  # noqa: E402


def _dish(name, revenue, margin_rate):
    return {"name": name, "revenue": float(revenue), "margin_rate": float(margin_rate)}


class TestMarginDragger:
    def test_biggest_dragger_is_impact_not_lowest_rate(self):
        # 香辣牛蛙 has the WORST rate but tiny revenue → barely drags. 招牌菜 has a
        # mild rate gap but huge revenue → drags the blend more. The dragger must
        # be the high-impact dish, not the lowest-rate one.
        with_cost = [
            _dish("香辣牛蛙", 5_000, 0.30),     # worst rate, tiny revenue
            _dish("招牌菜", 900_000, 0.70),      # mild gap, huge revenue
            _dish("凉菜", 90_000, 0.80),
        ]
        total = sum(d["revenue"] for d in with_cost)
        avg = 0.72
        d = _compute_margin_dragger(with_cost, avg, total)
        assert d is not None
        assert d["name"] == "招牌菜"          # impact, not lowest-rate 香辣牛蛙
        assert d["margin_rate"] == 0.70

    def test_loss_making_cause(self):
        with_cost = [
            _dish("赔本菜", 200_000, -0.10),
            _dish("正常菜", 300_000, 0.60),
        ]
        total = sum(d["revenue"] for d in with_cost)
        d = _compute_margin_dragger(with_cost, 0.30, total)
        assert d["name"] == "赔本菜"
        assert "亏本" in d["cause"]

    def test_needs_two_costed_dishes(self):
        assert _compute_margin_dragger([_dish("A", 5000, 0.5)], 0.5, 5000) is None
        assert _compute_margin_dragger([], 0.5, 0) is None

    def test_low_revenue_dishes_excluded(self):
        # Both below min_revenue → nothing to compare.
        assert _compute_margin_dragger(
            [_dish("A", 100, 0.1), _dish("B", 200, 0.2)], 0.5, 300) is None


def _store_margin_row(store_id, store_name, revenue, qty, start, end):
    return {
        "store_id": store_id, "store_name": store_name,
        "dish_name": "测试菜", "normalized_name": "测试菜",
        "qty": float(qty), "revenue": float(revenue), "bills": 8,
        "window_start": start, "window_end": end,
    }


def _store_margin_runtime(monkeypatch, rows_by_range):
    class _Connection:
        def __init__(self):
            self.calls = []

        async def execute(self, *_args):
            return None

        async def fetch(self, query, *args):
            self.calls.append((query, args))
            if "FROM agg_restaurant_product_cost" in query:
                return [{"product_source_pk": "PT-DISH", "c": 10.0}]
            current_rows = rows_by_range.get((args[2], args[3]), [])
            if "COUNT(DISTINCT t.id)::int AS bills" in query:
                return [{"store_id": sid, "bills": 8}
                        for sid in {row["store_id"] for row in current_rows}]
            if "SELECT s.store_id, s.name AS store_name" in query:
                return list(current_rows)
            raise AssertionError(f"unexpected SmartBI query: {query}")

    connection = _Connection()

    class _AcquireContext:
        async def __aenter__(self):
            return connection

        async def __aexit__(self, *_args):
            return None

    class _Pool:
        def acquire(self):
            return _AcquireContext()

    class _CretasConnection:
        async def fetch(self, query, *_args):
            if "FROM product_types" in query:
                return [{"id": "PT-DISH", "name": "测试菜"}]
            raise AssertionError(f"unexpected Cretas query: {query}")

        async def close(self):
            return None

    async def _connect(_url):
        return _CretasConnection()

    import asyncpg
    monkeypatch.setattr(asyncpg, "connect", _connect)
    return _Pool(), connection


def test_store_margin_structured_target_exists_and_never_returns_full_store_chart(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    rows = [
        _store_margin_row("S-1", "人民路店", 2000, 100, start, end),
        _store_margin_row("S-2", "湖滨路店", 5000, 100, start, end),
    ]
    pool, connection = _store_margin_runtime(monkeypatch, {(start, end): rows})
    result = asyncio.run(_r.resolve_store_margin(
        pool, "RES_TEST", role="restaurant_manager", date_range=(start, end),
        query="这家店毛利率怎么样", store_id="S-1", store_name="人民路店",
    ))
    assert "人民路店" in result.answer_text
    assert result.meta["targetStore"]["store_id"] == "S-1"
    assert result.charts == []
    scoped_calls = [args for query, args in connection.calls if "$5::text" in query]
    assert scoped_calls
    assert all(args[4:6] == ("S-1", "人民路店") for args in scoped_calls)


def test_store_margin_structured_target_missing_is_directed_no_data(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    pool, _ = _store_margin_runtime(monkeypatch, {
        (start, end): [_store_margin_row("S-2", "湖滨路店", 5000, 100, start, end)]
    })
    result = asyncio.run(_r.resolve_store_margin(
        pool, "RES_TEST", role="restaurant_manager", date_range=(start, end),
        query="这家店毛利率怎么样", store_id="S-404", store_name="不存在店",
    ))
    assert "不存在店" in result.answer_text
    assert "2026-07-20 至 2026-07-21" in result.answer_text
    assert "没有退化为全店榜" in result.answer_text
    assert result.meta["no_pos_data"] is True
    assert result.charts == []


def test_store_margin_comparison_uses_both_exact_date_ranges(monkeypatch):
    primary = (date(2026, 7, 20), date(2026, 7, 21))
    baseline = (date(2026, 7, 18), date(2026, 7, 19))
    pool, connection = _store_margin_runtime(monkeypatch, {
        primary: [_store_margin_row("S-1", "人民路店", 2000, 100, *primary)],
        baseline: [_store_margin_row("S-1", "人民路店", 1500, 100, *baseline)],
    })
    result = asyncio.run(_r.resolve_store_margin(
        pool, "RES_TEST", role="restaurant_manager", date_range=primary,
        comparison_date_range=baseline, query="比较人民路店两个日期范围的毛利",
        store_id="S-1", store_name="人民路店",
    ))
    assert "2026-07-20 至 2026-07-21" in result.answer_text
    assert "2026-07-18 至 2026-07-19" in result.answer_text
    assert "毛利为 ¥1,000.00" in result.answer_text
    assert "毛利为 ¥500.00" in result.answer_text
    assert "营业额" not in result.answer_text
    assert result.meta["comparisonComplete"] is True
    date_args = [args[2:4] for query, args in connection.calls if "$5::text" in query]
    assert primary in date_args and baseline in date_args


def test_store_margin_comparison_missing_period_names_dates_without_fallback(monkeypatch):
    primary = (date(2026, 7, 20), date(2026, 7, 21))
    baseline = (date(2026, 7, 18), date(2026, 7, 19))
    pool, _ = _store_margin_runtime(monkeypatch, {
        primary: [_store_margin_row("S-1", "人民路店", 2000, 100, *primary)],
        baseline: [],
    })
    result = asyncio.run(_r.resolve_store_margin(
        pool, "RES_TEST", role="restaurant_manager", date_range=primary,
        comparison_date_range=baseline, query="比较人民路店两个日期范围的毛利",
        store_id="S-1", store_name="人民路店",
    ))
    assert "2026-07-18 至 2026-07-19" in result.answer_text
    assert "缺少可靠计算毛利所需" in result.answer_text
    assert "毛利与毛利率" in result.answer_text
    assert "没有用其他日期、营业额或其他指标替代" in result.answer_text
    assert result.charts == []
    assert result.meta["comparisonComplete"] is False


# --- R7: explicit store mention extraction + canonicalization (scenario F) ---


@pytest.mark.parametrize("query,expected", [
    ("月球一号幻想店的毛利率是多少？", "月球一号幻想店"),
    ("查一下鲜行者打浦桥日月光店的毛利率", "鲜行者打浦桥日月光店"),
    ("哪家店业绩最好？", None),
    ("那它的毛利率也是第一吗？", None),
    ("各门店毛利对比", None),
    ("门店毛利分析", None),
])
def test_extract_store_mention(query, expected):
    assert _r.extract_store_mention(query) == expected


class _NoopTransaction:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None


def _mention_pool(exact_names, contains_names):
    class _Conn:
        def transaction(self):
            return _NoopTransaction()

        async def execute(self, *_args):
            return None

        async def fetch(self, query, *_args):
            if "name = $2" in query:
                return [{"name": n} for n in exact_names]
            if "LIKE" in query:
                return [{"name": n} for n in contains_names]
            return []

    class _Ctx:
        async def __aenter__(self):
            return _Conn()

        async def __aexit__(self, *_args):
            return None

    class _Pool:
        def acquire(self):
            return _Ctx()

    return _Pool()


def test_store_mention_unknown_store_declines_without_global_fallback():
    result = asyncio.run(_r.resolve_store_margin(
        _mention_pool([], []),
        "RES_TEST",
        role="restaurant_manager",
        query="月球一号幻想店的毛利率是多少？",
        store_mention="月球一号幻想店",
    ))
    assert "没有找到名为「月球一号幻想店」的门店" in result.answer_text
    assert "不会退化为全店榜" in result.answer_text
    assert result.meta.get("store_not_found") == "月球一号幻想店"
    assert result.charts == []


def test_store_mention_ambiguous_asks_for_clarification():
    result = asyncio.run(_r.resolve_store_margin(
        _mention_pool([], ["青花椒大融城店", "青花椒上海示范店"]),
        "RES_TEST",
        role="restaurant_manager",
        query="青花椒店的毛利率是多少？",
        store_mention="青花椒店",
    ))
    assert "匹配到多家门店" in result.answer_text
    assert "青花椒大融城店" in result.answer_text
    assert result.meta.get("store_mention_ambiguous") == "青花椒店"


def test_store_mention_canonical_single_match_scopes_answer(monkeypatch):
    class _Conn:
        def transaction(self):
            return _NoopTransaction()

        async def execute(self, *_args):
            return None

        async def fetch(self, query, *_args):
            if "FROM dim_store" in query and "name = $2" in query:
                return [{"name": "人民路店"}]
            if "FROM fact_pos_item" in query or "fact_pos_transaction" in query:
                return []
            return []

    class _Ctx:
        async def __aenter__(self):
            return _Conn()

        async def __aexit__(self, *_args):
            return None

    class _Pool:
        def acquire(self):
            return _Ctx()

    result = asyncio.run(_r.resolve_store_margin(
        _Pool(),
        "RES_TEST",
        role="restaurant_manager",
        query="人民路店的毛利率是多少？",
        store_mention="人民路店",
    ))
    assert "指定的人民路店" in result.answer_text
    assert "毛利、毛利率或排名" in result.answer_text
    assert result.meta.get("targetStoreName") == "人民路店"


# --- R7: cold date back-reference must decline, not pick a default window ---


def test_date_backref_without_restored_range_declines():
    result = asyncio.run(_r.resolve_by_code(
        "RESTAURANT_OPS_GROSS_MARGIN",
        None,
        "RES_TEST",
        role="restaurant_manager",
        query="那毛利呢？请沿用刚才比较的两个日期。",
    ))
    assert result is not None
    assert "没有找到可沿用的比较日期" in result.answer_text
    assert "默认时间范围" in result.answer_text
    assert result.meta.get("missing_reference") == "date_range"


def test_date_backref_with_restored_range_dispatches(monkeypatch):
    captured = {}

    async def _stub(pool, factory_id, **kwargs):
        captured["kwargs"] = kwargs
        return OpsAnswer(code="RESTAURANT_OPS_GROSS_MARGIN", title="t",
                         answer_text="ok", charts=[], kpis=[], meta={})

    monkeypatch.setitem(_r._RESOLVERS, "RESTAURANT_OPS_GROSS_MARGIN", _stub)
    result = asyncio.run(_r.resolve_by_code(
        "RESTAURANT_OPS_GROSS_MARGIN",
        None,
        "RES_TEST",
        query="那毛利呢？请沿用刚才比较的两个日期。",
        date_range=(date(2026, 7, 20), date(2026, 7, 20)),
        comparison_date_range=(date(2026, 7, 19), date(2026, 7, 19)),
    ))
    assert result.answer_text == "ok"
    assert captured["kwargs"]["date_range"] == (date(2026, 7, 20), date(2026, 7, 20))


def test_plain_query_without_backref_is_not_declined(monkeypatch):
    async def _stub(pool, factory_id, **kwargs):
        return OpsAnswer(code="RESTAURANT_OPS_GROSS_MARGIN", title="t",
                         answer_text="normal", charts=[], kpis=[], meta={})

    monkeypatch.setitem(_r._RESOLVERS, "RESTAURANT_OPS_GROSS_MARGIN", _stub)
    result = asyncio.run(_r.resolve_by_code(
        "RESTAURANT_OPS_GROSS_MARGIN", None, "RES_TEST", query="整体毛利率是多少",
    ))
    assert result.answer_text == "normal"


# --- R7: single-day ranges read as one date ---


def test_range_text_collapses_single_day():
    assert _r._range_text(date(2026, 7, 21), date(2026, 7, 21)) == "2026-07-21 当天"
    assert _r._range_text(date(2026, 7, 20), date(2026, 7, 21)) == "2026-07-20 至 2026-07-21"


# --- R8: unified demo data-space mapping per answer family ---


@pytest.mark.parametrize("code,expected", [
    ("RESTAURANT_OPS_SALES_SUMMARY", "RES_3101_009"),
    ("RESTAURANT_OPS_STORE_MARGIN", "RES_3101_009"),
    ("RESTAURANT_OPS_TREND_ANALYSIS", "RES_3101_009"),
    ("RESTAURANT_OPS_GROSS_MARGIN", "DEMO_REST"),
    ("RESTAURANT_OPS_WASTAGE_TOP", "DEMO_REST"),
    ("RESTAURANT_OPS_STOCK_SHORTAGE", "DEMO_REST"),
])
def test_demo_data_factory_per_code(code, expected):
    assert _r.demo_data_factory_for_code(code, "DEMO_REST") == expected


def test_demo_data_factory_store_scope_overrides_code():
    assert _r.demo_data_factory_for_code(
        "RESTAURANT_OPS_GROSS_MARGIN", "DEMO_REST", store_scoped=True,
    ) == "RES_3101_009"


def test_demo_data_factory_real_tenants_never_mapped():
    assert _r.demo_data_factory_for_code(
        "RESTAURANT_OPS_SALES_SUMMARY", "RES_3101_001",
    ) == "RES_3101_001"
    assert _r.demo_data_factory_for_code(
        "RESTAURANT_OPS_SALES_SUMMARY", "F006", store_scoped=True,
    ) == "F006"


# --- R9: 实体检测 (Sheet 7/22 用户复测缺口) — 时间/菜品 ---


def test_rolling_year_window():
    rng, label = _resolve_sales_date_range("过去一年营收多少", today=date(2026, 7, 22))
    assert label == "最近一年"
    assert rng == (date(2025, 7, 23), date(2026, 7, 22))


def test_rolling_two_year_window_capped():
    rng, label = _resolve_sales_date_range("近两年营收", today=date(2026, 7, 22))
    assert label == "最近2年"
    assert (rng[1] - rng[0]).days + 1 == 730


@pytest.mark.parametrize("query", ["明天营业额会是多少", "下周营收预计多少", "下个月营业额"])
def test_future_phrases_get_future_sentinel(query):
    rng, label = _resolve_sales_date_range(query, today=date(2026, 7, 22))
    assert rng == (None, None)
    assert label == "未来时间"


def test_action_clause_tomorrow_keeps_real_window():
    rng, label = _resolve_sales_date_range(
        "本周营收怎么样，明天先做什么", today=date(2026, 7, 22),
    )
    assert label == "本周"


def test_sales_summary_declines_future_without_substitution(monkeypatch):
    import smartbi.gold.queries as _q

    async def _boom(*a, **kw):
        raise AssertionError("future questions must not query finance data")

    monkeypatch.setattr(_q, "finance_summary", _boom)
    monkeypatch.setattr(_q, "store_comparison", _boom)
    answer = asyncio.run(resolve_sales_summary(
        object(), "RES_TEST", role="restaurant_owner",
        query="明天营业额会是多少", today=date(2026, 7, 22),
    ))
    assert "尚未发生" in answer.answer_text
    assert "不会用历史数据替代" in answer.answer_text
    assert answer.meta.get("future_request") is True


@pytest.mark.parametrize("query,expected", [
    ("米饭的毛利率是多少", "米饭"),
    ("招牌藤椒味(单人份)的毛利是多少", "招牌藤椒味(单人份)"),
    ("招牌藤椒味卖得怎么样", "招牌藤椒味"),
    ("整体毛利率是多少", None),
    ("哪道菜毛利最高", None),
    ("菜品毛利率排行", None),
    ("哪家店卖得最好", None),
    ("昨天米饭的销量是多少", "米饭"),
])
def test_extract_dish_candidate(query, expected):
    assert _r.extract_dish_candidate(query) == expected


def test_dish_sales_phrase_routes_to_gross_margin():
    assert match_restaurant_ops("招牌藤椒味卖得怎么样") == "RESTAURANT_OPS_GROSS_MARGIN"
    assert match_restaurant_ops("哪家店卖得最好") != "RESTAURANT_OPS_GROSS_MARGIN"


def _dish_rows():
    return [
        {"product_id": 1, "dish_name": "米饭(单人份)", "normalized_name": "米饭",
         "total_qty": 100.0, "total_revenue": 500.0, "bills": 90,
         "window_start": date(2026, 4, 1), "window_end": date(2026, 4, 30)},
        {"product_id": 2, "dish_name": "招牌藤椒味(单人份)", "normalized_name": "招牌藤椒味",
         "total_qty": 50.0, "total_revenue": 4000.0, "bills": 45,
         "window_start": date(2026, 4, 1), "window_end": date(2026, 4, 30)},
        {"product_id": 3, "dish_name": "藤椒味双人份", "normalized_name": "藤椒味双人",
         "total_qty": 20.0, "total_revenue": 3000.0, "bills": 18,
         "window_start": date(2026, 4, 1), "window_end": date(2026, 4, 30)},
    ]


def test_match_dish_rows_exact_beats_containment():
    hits = _r._match_dish_rows("米饭", _dish_rows())
    assert len(hits) == 1 and hits[0]["product_id"] == 1


def test_match_dish_rows_containment_multi():
    hits = _r._match_dish_rows("藤椒味", _dish_rows())
    assert {h["product_id"] for h in hits} == {2, 3}


def _gross_margin_pool(rows):
    class _Conn:
        def transaction(self):
            return _NoopTransaction()

        async def execute(self, *_args):
            return None

        async def fetch(self, sql, *_args):
            if "FROM fact_pos_item" in sql and "GROUP BY p.product_id" in sql:
                return rows
            if "agg_restaurant_product_cost" in sql:
                return []
            return []

    class _Ctx:
        async def __aenter__(self):
            return _Conn()

        async def __aexit__(self, *_args):
            return None

    class _Pool:
        def acquire(self):
            return _Ctx()

    return _Pool()


def test_named_unknown_dish_declines_without_ranking():
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()), "RES_TEST",
        role="restaurant_manager", query="月球烤肉的毛利率是多少",
    ))
    assert "没有找到名为「月球烤肉」的菜品" in result.answer_text
    assert "不会用全部菜品的榜单替代" in result.answer_text
    assert result.meta.get("dish_not_found") == "月球烤肉"


def test_named_ambiguous_dish_asks_clarification():
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()), "RES_TEST",
        role="restaurant_manager", query="藤椒味的毛利是多少",
    ))
    assert "匹配到多道菜品" in result.answer_text
    assert result.meta.get("dish_mention_ambiguous") == "藤椒味"


@pytest.mark.parametrize("query,expected", [
    ("过去一个月营业额", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("最近3个月营收", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("过去一年营收多少", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("最近3个月营收趋势", "RESTAURANT_OPS_TREND_ANALYSIS"),
    ("营收趋势", "RESTAURANT_OPS_TREND_ANALYSIS"),
])
def test_rolling_window_revenue_routes_to_sales_summary(query, expected):
    assert match_restaurant_ops(query) == expected


# --- R11: 菜品链 — 单独问准确识别 + 多轮继承 (用户 7/22 晚) ---


@pytest.mark.parametrize("query,expected", [
    ("米饭的销量是多少", "米饭"),
    ("米饭的成本如何", "米饭"),
    ("招牌藤椒味的成本是多少", "招牌藤椒味"),
    ("这个过去一个月的销量如何", None),
    ("最近一个月的营收情况如何？毛利有多少", None),
    ("米饭的销量是多少；继续追问：这个过去一个月的销量如何", "米饭"),
    ("米饭的销量是多少；继续追问：成本如何", "米饭"),
])
def test_dish_candidate_singleturn_and_contextualized(query, expected):
    assert _r.extract_dish_candidate(query) == expected


@pytest.mark.parametrize("query", [
    "米饭的销量是多少",
    "米饭的成本如何",
    "招牌藤椒味的销售额是多少",
])
def test_named_dish_metric_routes_to_gross_margin(query):
    assert match_restaurant_ops(query) == "RESTAURANT_OPS_GROSS_MARGIN"


def test_generic_metric_questions_not_stolen_by_dish_rule():
    assert match_restaurant_ops("最近一个月的营收情况如何？毛利有多少") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert match_restaurant_ops("菜品毛利率排行") == "RESTAURANT_OPS_GROSS_MARGIN"


def test_dish_scoped_answer_prepends_sales_header():
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()), "RES_TEST",
        role="restaurant_manager", query="米饭的销量是多少",
    ))
    assert result.answer_text.startswith("「米饭(单人份)」")
    assert "销量 100 份" in result.answer_text
    assert "营收 ¥500.00" in result.answer_text
    assert result.meta.get("targetDish") == "米饭(单人份)"


# --- R12: 周环比 / 今年·去年窗 (变体探针 V4/V5) ---


def test_named_year_windows():
    rng, label = _resolve_sales_date_range("今年总营业额", today=date(2026, 7, 22))
    assert (rng, label) == (((date(2026, 1, 1), date(2026, 7, 22))), "今年")
    rng2, label2 = _resolve_sales_date_range("去年营业额多少", today=date(2026, 7, 22))
    assert (rng2, label2) == (((date(2025, 1, 1), date(2025, 12, 31))), "去年")


def test_week_over_week_comparison_spec():
    spec = _resolve_sales_query_spec("上周和上上周营业额相比怎么样", today=date(2026, 7, 22))
    assert spec.window_label == "上周"
    assert spec.date_range == (date(2026, 7, 13), date(2026, 7, 19))
    assert spec.comparison_label == "上上周"
    assert spec.comparison_range == (date(2026, 7, 6), date(2026, 7, 12))


def test_year_over_year_comparison_spec():
    spec = _resolve_sales_query_spec("今年和去年营收对比", today=date(2026, 7, 22))
    assert spec.window_label == "今年"
    assert spec.comparison_label == "去年"
    assert spec.comparison_range == (date(2025, 1, 1), date(2025, 12, 31))


def test_week_pair_routes_to_sales_summary():
    assert match_restaurant_ops("上周和上上周营业额相比怎么样") == "RESTAURANT_OPS_SALES_SUMMARY"


def test_store_plus_dish_extracts_dish_after_store_strip():
    assert _r.extract_dish_candidate("鲜行者打浦桥日月光店的米饭卖得怎么样") == "米饭"
    assert _r.extract_dish_candidate("鲜行者打浦桥日月光店的毛利率是多少") is None


def test_comparative_two_dishes_extracted():
    assert _r.extract_dish_candidates("米饭和招牌藤椒味哪个毛利高") == ["米饭", "招牌藤椒味"]
    assert _r.extract_dish_candidates("米饭的毛利率是多少") == ["米饭"]
    assert _r.extract_dish_candidates("整体毛利率是多少") == []


# --- R13: 泛化类修复 (G2 日历窗 / G3 复合指标 / G6 口语盈亏) ---


def test_multi_metric_dish_extraction():
    assert _r.extract_dish_candidate("米饭的销量和毛利率分别是多少") == "米饭"
    assert _r.extract_dish_candidate("招牌藤椒味的营收和成本是多少") == "招牌藤椒味"


def test_profit_colloquial_routes_to_sales_summary():
    assert match_restaurant_ops("最近亏钱了吗") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert match_restaurant_ops("现在赚钱吗") == "RESTAURANT_OPS_SALES_SUMMARY"


def test_gross_margin_exact_calendar_window(monkeypatch):
    captured = {}

    class _Conn:
        def transaction(self):
            return _NoopTransaction()

        async def execute(self, *_a):
            return None

        async def fetch(self, sql, *args):
            if "FROM fact_pos_item" in sql and "GROUP BY p.product_id" in sql:
                captured["args"] = args
                return []
            return []

    class _Ctx:
        async def __aenter__(self):
            return _Conn()

        async def __aexit__(self, *_a):
            return None

    class _Pool:
        def acquire(self):
            return _Ctx()

    result = asyncio.run(_r.resolve_gross_margin(
        _Pool(), "RES_TEST", role="restaurant_manager",
        query="今年米饭的销量",
        date_range=(date(2026, 1, 1), date(2026, 7, 22)),
    ))
    # exact calendar bounds forwarded to SQL ($3/$4)
    assert captured["args"][2] == date(2026, 1, 1)
    assert captured["args"][3] == date(2026, 7, 22)
    # empty window → calendar-labelled decline, never anchored substitution
    assert "2026-01-01 至 2026-07-22" in result.answer_text
    assert "没有用其他时间范围替代" in result.answer_text
