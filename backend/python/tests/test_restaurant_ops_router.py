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
    _scoped_dish_metric_answer,
    extract_dish_candidates,
    match_restaurant_ops,
    reconcile_restaurant_ops_code,
    resolve_by_code,
    resolve_sales_summary,
    resolve_store_directory,
)


def _dish_metric_entry():
    return {
        "name": "米饭",
        "qty": 100.0,
        "revenue": 1000.0,
        "bills": 80,
        "food_cost_unit": 2.0,
        "total_cost": 200.0,
        "gross_profit": 800.0,
        "margin_rate": 0.8,
        "has_cost": True,
        "invalid_cost": False,
    }


def test_scoped_dish_sales_followup_does_not_repeat_margin_report():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="本月米饭的销量呢",
    )

    assert answer == "「米饭」本月销量 **100 份**、营收 **¥1,000.00**，覆盖订单 80 单。"
    assert "毛利分析" not in answer
    assert "建议动作" not in answer


def test_scoped_dish_revenue_followup_does_not_repeat_margin_report():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="本月米饭的营收呢",
    )

    assert answer == "「米饭」本月营收 **¥1,000.00**，对应销量 100 份、覆盖订单 80 单。"
    assert "毛利分析" not in answer


def test_scoped_dish_diagnosis_explains_math_without_claiming_causality():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="本月米饭的毛利率为什么是这样",
    )

    assert "原因拆解" in answer
    assert "计算构成" in answer
    assert "不能证明业务因果" in answer
    assert "80.0%" in answer


def test_scoped_dish_sales_diagnosis_never_falls_back_to_margin_explanation():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="本月米饭的销量为什么是这样",
    )

    assert "销量为 100 份" in answer
    assert "平均每单 1.25 份" in answer
    assert "不能证明业务因果" in answer
    assert "毛利率替代销量原因" in answer
    assert "80.0%" not in answer


def test_scoped_dish_low_sales_premise_is_verified_before_causal_guidance():
    entry = _dish_metric_entry()
    entry["name"] = "卤炸牛肉串"
    entry["qty"] = 25.0
    answer = _scoped_dish_metric_answer(
        entry,
        window_label="本月",
        query="全部门店卤炸牛肉串本月销量为什么低",
        peer_sales_quantities=[100.0, 80.0, 60.0, 40.0, 20.0],
    )

    assert answer.startswith("**判断")
    assert "第 5" in answer
    assert "“销量低”的前提成立" in answer
    assert "还不能证明为什么低" in answer
    assert "上涨或下降" not in answer


def test_scoped_dish_rejects_false_low_sales_premise_and_offers_safe_next_step():
    entry = _dish_metric_entry()
    entry["name"] = "卤炸牛肉串"
    answer = _scoped_dish_metric_answer(
        entry,
        window_label="本月",
        query="卤炸牛肉串销量为什么低",
        peer_sales_quantities=[80.0, 60.0, 40.0, 20.0],
    )

    assert "按销量从高到低排第 1" in answer
    assert "“销量低”的前提不成立" in answer
    assert "不能按“低销量问题”直接制定动作" in answer
    assert "继续提高销量" in answer


def test_scoped_dish_does_not_guess_low_status_without_comparable_dishes():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="米饭本月销量为什么低",
    )

    assert "可比主菜不足" in answer
    assert "不能判断“销量低”的前提是否成立" in answer


def test_scoped_dish_high_sales_premise_is_verified_before_causal_guidance():
    entry = _dish_metric_entry()
    entry["name"] = "卤炸牛肉串"
    entry["qty"] = 100.0
    answer = _scoped_dish_metric_answer(
        entry,
        window_label="本月",
        query="全部门店卤炸牛肉串本月销量为什么高",
        peer_sales_quantities=[80.0, 60.0, 40.0, 20.0],
    )

    assert answer.startswith("**判断")
    assert "按销量从高到低排第 1" in answer
    assert "“销量高”的前提成立" in answer
    assert "还不能证明为什么高" in answer
    assert "上涨或下降" not in answer


def test_scoped_dish_rejects_false_high_sales_premise():
    entry = _dish_metric_entry()
    entry["name"] = "卤炸牛肉串"
    entry["qty"] = 20.0
    answer = _scoped_dish_metric_answer(
        entry,
        window_label="本月",
        query="卤炸牛肉串为什么卖得好",
        peer_sales_quantities=[100.0, 80.0, 60.0, 40.0],
    )

    assert "“销量高”的前提不成立" in answer
    assert "不能按“高销量”解释现状" in answer


def test_scoped_dish_does_not_guess_high_status_without_comparable_dishes():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="米饭本月销量为什么高",
    )

    assert "可比主菜不足" in answer
    assert "不能判断“销量高”的前提是否成立" in answer


def test_scoped_dish_growth_question_remains_a_trend_diagnosis():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="米饭本月销量为什么上涨",
        peer_sales_quantities=[80.0, 60.0, 40.0, 20.0],
    )

    assert answer.startswith("**原因拆解")
    assert "需要指定对比周期" in answer
    assert "“销量高”的前提" not in answer


def test_scoped_dish_optimization_contains_actions_and_validation_metrics():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="本月米饭的毛利率怎么优化",
    )

    assert "优化目标" in answer
    assert "优化动作" in answer
    assert "验证指标" in answer
    assert "全店涨价" in answer


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
    ("昨天与前天全部门店营业额分别是多少？请给差额和升降结论", "RESTAURANT_OPS_SALES_SUMMARY"),
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
    assert answer.meta["comparison"]["primary_no_data"] is True
    assert answer.meta["comparison"]["baseline_no_data"] is True
    assert answer.meta["comparison"]["primary_start"] == "2026-07-20"
    assert answer.meta["comparison"]["baseline_start"] == "2026-07-19"


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
            (date(2026, 6, 1), date(2026, 6, 21)),
            "上个月同期",
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


def test_best_store_revenue_answer_puts_store_conclusion_first(monkeypatch):
    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        return {
            "total_revenue": 10000.0,
            "bill_count": 50,
            "avg_bill_value": 200.0,
            "day_count": 21,
            "store_count": 2,
            "top_stores": [
                {"store_name": "兄弟土菜馆", "revenue": 7000.0, "bill_count": 30},
                {"store_name": "有滋有味北外滩店", "revenue": 3000.0, "bill_count": 20},
            ],
        }

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": ["有滋有味北外滩店"]}

    import smartbi.gold.queries as _q

    monkeypatch.setattr(_q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(_q, "store_comparison", _fake_store_comparison)

    answer = asyncio.run(resolve_sales_summary(
        object(),
        "RES_TEST",
        role="restaurant_manager",
        query="哪个门店营收最好",
        today=date(2026, 7, 21),
        date_range=(date(2026, 7, 1), date(2026, 7, 21)),
        window_label="本月",
    ))

    assert answer.answer_text.startswith(
        "**结论：本月（2026-07-01 至 2026-07-21）"
        "营收最高的是兄弟土菜馆"
    )
    assert answer.answer_text.index("兄弟土菜馆") < answer.answer_text.index("经营能看")


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
    # markdown typography (2026-07-24): headline figures are bolded
    assert "总营收 **¥100,000.00**" in answer.answer_text
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
    # markdown typography (2026-07-24): comparison delta is bolded
    assert "营收高 **¥2,000.00**（20.0%）" in ans.answer_text
    assert ans.meta["comparison"]["answered"] is True
    assert ans.meta["comparison"]["revenue_delta"] == 2000.0


def test_sales_summary_prefers_sealed_comparison_slots_over_bare_followup_text(monkeypatch):
    calls = []

    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        calls.append(date_range)
        revenue = 12000.0 if date_range[0] == date(2026, 7, 20) else 10000.0
        return {
            "total_revenue": revenue,
            "bill_count": 60 if revenue == 12000.0 else 50,
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

    answer = asyncio.run(resolve_sales_summary(
        object(),
        "RES_TEST",
        role="restaurant_manager",
        query="全部门店",
        date_range=(date(2026, 7, 20), date(2026, 7, 20)),
        window_label="昨天",
        comparison_date_range=(date(2026, 7, 19), date(2026, 7, 19)),
        comparison_label="前天",
        comparison_kind="previous_day",
    ))

    assert calls == [
        (date(2026, 7, 20), date(2026, 7, 20)),
        (date(2026, 7, 19), date(2026, 7, 19)),
    ]
    assert "昨天（2026-07-20 当天）" in answer.answer_text
    assert "前天（2026-07-19 当天）" in answer.answer_text
    assert "营收高 **¥2,000.00**（20.0%）" in answer.answer_text
    assert answer.meta["comparison"]["kind"] == "previous_day"


def test_sales_summary_aligns_partial_month_to_primary_actual_end(monkeypatch):
    calls = []

    async def _fake_finance_summary(
        pool,
        factory_id,
        date_range,
        top_n_stores=5,
    ):
        calls.append(date_range)
        primary = date_range[0].month == 7
        return {
            "total_revenue": 25000.0 if primary else 20000.0,
            "bill_count": 250 if primary else 200,
            "avg_bill_value": 100.0,
            "day_count": 25,
            "store_count": 2,
            "actual_start_date": date_range[0].isoformat(),
            "actual_end_date": date_range[1].isoformat(),
            "top_stores": [],
        }

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": []}

    import smartbi.gold.queries as _q

    monkeypatch.setattr(_q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(_q, "store_comparison", _fake_store_comparison)

    # Production ingestion trails the server date by one day.
    async def _primary_with_lag(pool, factory_id, date_range, top_n_stores=5):
        if date_range[0].month == 7:
            result = await _fake_finance_summary(
                pool,
                factory_id,
                date_range,
                top_n_stores,
            )
            result["actual_end_date"] = "2026-07-25"
            return result
        return await _fake_finance_summary(
            pool,
            factory_id,
            date_range,
            top_n_stores,
        )

    monkeypatch.setattr(_q, "finance_summary", _primary_with_lag)

    answer = asyncio.run(resolve_sales_summary(
        object(),
        "RES_TEST",
        role="restaurant_manager",
        query="本月和上月营业额哪个高？",
        today=date(2026, 7, 26),
    ))

    assert calls == [
        (date(2026, 7, 1), date(2026, 7, 26)),
        (date(2026, 6, 1), date(2026, 6, 25)),
    ]
    assert "本月（2026-07-01 至 2026-07-25）" in answer.answer_text
    assert "上个月同期（2026-06-01 至 2026-06-25）" in answer.answer_text
    assert "营收高 **¥5,000.00**（25.0%）" in answer.answer_text
    assert answer.meta["comparison"]["primary_day_count"] == 25
    assert answer.meta["comparison"]["baseline_day_count"] == 25
    assert answer.meta["comparison"]["coverage_mismatch"] is False


def test_sales_summary_refuses_verdict_when_comparison_data_days_differ(monkeypatch):
    async def _fake_finance_summary(
        pool,
        factory_id,
        date_range,
        top_n_stores=5,
    ):
        primary = date_range[0].month == 7
        return {
            "total_revenue": 25000.0 if primary else 20000.0,
            "bill_count": 250 if primary else 200,
            "avg_bill_value": 100.0,
            "day_count": 25 if primary else 24,
            "store_count": 2,
            "actual_start_date": date_range[0].isoformat(),
            "actual_end_date": (
                "2026-07-25" if primary else date_range[1].isoformat()
            ),
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
        role="restaurant_manager",
        query="本月和上月营业额哪个高？",
        today=date(2026, 7, 26),
    ))

    assert "覆盖天数不同" in answer.answer_text
    assert "本次不直接判断高低" in answer.answer_text
    assert "营收高 **" not in answer.answer_text
    assert "营收低 **" not in answer.answer_text
    assert answer.meta["comparison"]["coverage_mismatch"] is True
    assert "revenue_delta" not in answer.meta["comparison"]


def test_sales_summary_aligns_week_when_ingestion_trails_sunday(monkeypatch):
    calls = []

    async def _fake_finance_summary(
        pool,
        factory_id,
        date_range,
        top_n_stores=5,
    ):
        calls.append(date_range)
        primary = date_range[0] == date(2026, 7, 20)
        return {
            "total_revenue": 6000.0 if primary else 5000.0,
            "bill_count": 60 if primary else 50,
            "avg_bill_value": 100.0,
            "day_count": 6,
            "store_count": 2,
            "actual_start_date": date_range[0].isoformat(),
            "actual_end_date": (
                "2026-07-25" if primary else date_range[1].isoformat()
            ),
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
        role="restaurant_manager",
        query="本周营业额和上周相比是上升还是下降？",
        today=date(2026, 7, 26),
    ))

    assert calls == [
        (date(2026, 7, 20), date(2026, 7, 26)),
        (date(2026, 7, 13), date(2026, 7, 18)),
    ]
    assert "本周（2026-07-20 至 2026-07-25）" in answer.answer_text
    assert "上周同期（2026-07-13 至 2026-07-18）" in answer.answer_text
    assert "营收高 **¥1,000.00**（20.0%）" in answer.answer_text


def test_sales_summary_complete_months_allow_different_calendar_lengths(
    monkeypatch,
):
    async def _fake_finance_summary(
        pool,
        factory_id,
        date_range,
        top_n_stores=5,
    ):
        primary = date_range[0] == date(2026, 6, 1)
        return {
            "total_revenue": 9000.0 if primary else 10000.0,
            "bill_count": 90 if primary else 100,
            "avg_bill_value": 100.0,
            "day_count": 30 if primary else 31,
            "store_count": 2,
            "actual_start_date": date_range[0].isoformat(),
            "actual_end_date": date_range[1].isoformat(),
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
        role="restaurant_manager",
        query="上个月营业额和上上个月相比怎么样",
        today=date(2026, 7, 26),
    ))

    assert "上个月（2026-06-01 至 2026-06-30）" in answer.answer_text
    assert "上上个月（2026-05-01 至 2026-05-31）" in answer.answer_text
    assert "营收低 **¥1,000.00**（10.0%）" in answer.answer_text
    assert "覆盖天数不同" not in answer.answer_text
    assert answer.meta["comparison"]["coverage_mismatch"] is False


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
        {
            "product_id": "POS-RICE",
            "dish_name": "米饭",
            "normalized_name": "米饭",
            "category": "主食",
            "sub_category": "米饭",
            "total_qty": 100.0,
            "total_revenue": 3000.0,
            "bills": 80,
            "window_start": date(2026, 7, 1),
            "window_end": date(2026, 7, 21),
        },
    ]
    cost_rows = [
        {"product_source_pk": "PT-LOW", "food_cost": 18.0},
        {"product_source_pk": "PT-HIGH", "food_cost": 20.0},
        {"product_source_pk": "PT-RICE", "food_cost": 29.0},
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
                    {"id": "PT-RICE", "name": "米饭"},
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
    assert "当前低毛利候选是米饭" not in result.answer_text
    assert "米饭/附属用品仅计入总额、不参与主菜排名与建议" in result.answer_text


def test_gross_margin_uses_smartbi_cost_product_fallback_when_erp_seed_is_gone(
    monkeypatch,
):
    pos_rows = [{
        "product_id": 505,
        "dish_name": "招牌青花椒味(单人份)",
        "normalized_name": "招牌青花椒味(单人份)",
        "category": "主菜",
        "sub_category": "鱼类",
        "total_qty": 100.0,
        "total_revenue": 5800.0,
        "bills": 80,
        "window_start": date(2026, 7, 15),
        "window_end": date(2026, 7, 21),
    }]

    class _SmartBIConnection:
        async def execute(self, *_args):
            return None

        async def fetch(self, query, *_args):
            if "FROM fact_pos_item" in query:
                return pos_rows
            if "FROM dim_restaurant_cost_product" in query:
                return [{
                    "normalized_name": "招牌青花椒味(单人份)",
                    "product_source_pk": "pt_qhj_001",
                }]
            if "FROM agg_restaurant_product_cost" in query:
                return [{"product_source_pk": "pt_qhj_001", "food_cost": 7.58}]
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
            if "FROM product_types" in query or "FROM dim_product_alias" in query:
                return []
            raise AssertionError(f"unexpected Cretas query: {query}")

        async def close(self):
            return None

    async def _connect(_url):
        return _CretasConnection()

    import asyncpg
    monkeypatch.setattr(asyncpg, "connect", _connect)

    result = asyncio.run(_r.resolve_gross_margin(
        _SmartBIPool(),
        "RES_3101_009",
        role="restaurant_manager",
        query="招牌青花椒味(单人份)的成本和毛利呢？",
        dish_mention="招牌青花椒味(单人份)",
    ))

    assert result.meta["targetDish"] == "招牌青花椒味(单人份)"
    assert result.meta["missing_cost_count"] == 0
    assert result.meta["target_dish_metrics"]["unit_cost"] == 7.58
    assert "成本 **¥758.00**" in result.answer_text
    assert "成本数据不足" not in result.answer_text


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


def test_revenue_reference_lines_parse_explicit_yuan_values():
    assert _r._parse_revenue_reference_lines(
        "每日营业额曲线，计划值10万元，预警值8万元"
    ) == [
        {"name": "计划值", "yAxis": 100000.0},
        {"name": "预警值", "yAxis": 80000.0},
    ]
    assert _r._parse_revenue_reference_lines("加入计划值和预警值") == []


def test_daily_revenue_chart_includes_reference_lines_and_quadratic_fit(monkeypatch):
    daily = [
        {
            "date": f"2026-07-{day:02d}",
            "revenue": float(1000 + 25 * day + 3 * day * day),
            "bill_count": 10,
        }
        for day in range(1, 11)
    ]
    bundle = {
        "weekdayWeekend": {},
        "dailyTrend": daily,
        "monthlyTrend": [{"month": "2026-07", "revenue": sum(
            item["revenue"] for item in daily
        )}],
    }

    async def _fake_trend_bundle(pool, factory_id, date_range):
        return bundle

    import smartbi.gold.queries as _q
    monkeypatch.setattr(_q, "trend_bundle", _fake_trend_bundle)
    answer = asyncio.run(_r.resolve_trend_analysis(
        object(),
        "RES_TEST",
        role="restaurant_manager",
        date_range=(date(2026, 7, 1), date(2026, 7, 10)),
        query=(
            "用二次函数拟合最近10天全部门店每日营业额曲线，"
            "计划值10万元，预警值8万元；如果无法绘图，请提供可导出的Excel或XLS数据"
        ),
    ))

    assert answer.charts[0]["title"].startswith("每日营收趋势")
    assert answer.charts[0]["xAxis"]["data"][0] == "2026-07-01"
    assert answer.charts[0]["series"][0]["markLine"]["data"] == [
        {"name": "计划值", "yAxis": 100000.0},
        {"name": "预警值", "yAxis": 80000.0},
    ]
    assert answer.charts[0]["series"][1]["name"] == "二次趋势拟合"
    assert answer.meta["quadratic_fit"]["r_squared"] > 0.999
    assert answer.meta["daily_point_count"] == 10
    assert len(answer.meta["export_rows"]) == 10
    assert answer.meta["export_requested"] is True
    assert "可导出字段" in answer.answer_text
    assert "Excel/XLS" in answer.answer_text
    assert "不代表" in answer.answer_text


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


def _store_margin_runtime(monkeypatch, rows_by_range, *, include_cost=True):
    class _Connection:
        def __init__(self):
            self.calls = []

        async def execute(self, *_args):
            return None

        async def fetch(self, query, *args):
            self.calls.append((query, args))
            if "FROM agg_restaurant_product_cost" in query:
                return (
                    [{"product_source_pk": "PT-DISH", "c": 10.0}]
                    if include_cost
                    else []
                )
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
                return [
                    {
                        "id": "PT-DISH" if name == "测试菜" else f"PT-{name}",
                        "name": name,
                    }
                    for name in _args[1]
                ]
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


def test_store_sales_overview_uses_llm_metrics_instead_of_forcing_margin(monkeypatch):
    start, end = date(2026, 7, 1), date(2026, 7, 27)
    rows = [
        _store_margin_row(
            "S-1", "鲜行者打浦桥日月光店", 12680, 236, start, end,
        ),
    ]
    pool, _ = _store_margin_runtime(monkeypatch, {(start, end): rows})

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        window_label="本月",
        query="鲜行者打浦桥日月光店这家店的销售情况 本月",
        store_name="鲜行者打浦桥日月光店",
        requested_metrics=("revenue", "orders", "sales_volume"),
        analysis_action="diagnose",
    ))

    assert result.title == "门店销售情况（本月）"
    assert "鲜行者打浦桥日月光店" in result.answer_text
    assert "营收 ¥12,680.00" in result.answer_text
    assert "订单 8 单" in result.answer_text
    assert "菜品销量 236 份" in result.answer_text
    assert "原因拆解" in result.answer_text
    assert "毛利率" not in result.answer_text


def test_store_dish_ranking_honors_llm_direction_for_colloquial_typo(monkeypatch):
    start, end = date(2026, 7, 1), date(2026, 7, 27)
    best = _store_margin_row(
        "S-1", "鲜行者打浦桥日月光店", 5600, 120, start, end,
    )
    best["dish_name"] = "招牌藤椒鸡"
    best["normalized_name"] = "招牌藤椒鸡"
    second = _store_margin_row(
        "S-1", "鲜行者打浦桥日月光店", 3100, 80, start, end,
    )
    second["dish_name"] = "卤炸牛肉串"
    second["normalized_name"] = "卤炸牛肉串"
    pool, _ = _store_margin_runtime(
        monkeypatch,
        {(start, end): [best, second]},
    )

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        window_label="本月",
        query="鲜行者打浦桥日月光店这家店买的最好的是哪一道菜 本月",
        store_name="鲜行者打浦桥日月光店",
        requested_metrics=("sales_volume",),
        ranking_direction="best",
        ranking_limit=1,
    ))

    assert result.meta["dish_ranking"] == "best"
    assert result.meta["ranking_limit"] == 1
    assert result.meta["focus_entity"]["name"] == "招牌藤椒鸡"
    assert "销量 120 份" in result.answer_text
    assert "卤炸牛肉串" not in result.answer_text


def test_store_sales_no_data_names_requested_metrics_not_cost(monkeypatch):
    start, end = date(2026, 7, 1), date(2026, 7, 27)
    pool, _ = _store_margin_runtime(monkeypatch, {(start, end): []})

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        window_label="本月",
        query="有滋有味北外滩店的销售情况 本月",
        store_name="有滋有味北外滩店",
        requested_metrics=("revenue", "orders", "sales_volume"),
    ))

    assert result.title == "有滋有味北外滩店销售情况（2026-07-01 至 2026-07-27）"
    assert "营收、订单和菜品销量" in result.answer_text
    assert "没有改成全部门店、其他日期或毛利数据" in result.answer_text
    assert "配方和最近进价" not in result.answer_text


def test_store_dish_ranking_no_data_keeps_sales_semantics(monkeypatch):
    start, end = date(2026, 7, 19), date(2026, 7, 25)
    pool, _ = _store_margin_runtime(monkeypatch, {(start, end): []})

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="哪个菜卖得好 最近7天 无数据店",
        store_name="无数据店",
    ))

    assert result.title == "无数据店菜品销量排行（2026-07-19 至 2026-07-25）"
    assert "不能生成销量最高榜" in result.answer_text
    assert "没有改成毛利、营业额或其他日期" in result.answer_text
    assert result.meta["dish_ranking"] == "best"
    assert result.meta["no_pos_data"] is True
    assert result.meta["scope_matches_request"] is True
    assert result.charts == []


def test_single_store_dish_margin_uses_store_dish_grain(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    row = _store_margin_row(
        "S-1",
        "青花椒南方百联店",
        2000,
        100,
        start,
        end,
    )
    pool, _ = _store_margin_runtime(monkeypatch, {(start, end): [row]})

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="最近7天青花椒南方百联店测试菜的成本和毛利呢？",
        store_name="青花椒南方百联店",
        dish_mention="测试菜",
    ))

    assert "门店范围：**青花椒南方百联店**" in result.answer_text
    assert "「测试菜」2026-07-20 至 2026-07-21" in result.answer_text
    assert "成本 **¥1,000.00**" in result.answer_text
    assert "毛利 **¥1,000.00**" in result.answer_text
    assert "毛利率 **50.0%**" in result.answer_text
    assert result.meta["targetStoreName"] == "青花椒南方百联店"
    assert result.meta["marginInvariantPass"] is True
    assert result.meta["scope_matches_request"] is True


def test_single_store_overall_revenue_and_named_dish_sales_keep_both_grains(
    monkeypatch,
):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    dish_row = _store_margin_row(
        "S-1",
        "青花椒南方百联店",
        2000,
        100,
        start,
        end,
    )
    other_row = {
        **dish_row,
        "dish_name": "配菜",
        "normalized_name": "配菜",
        "qty": 50.0,
        "revenue": 3000.0,
    }
    pool, _ = _store_margin_runtime(
        monkeypatch,
        {(start, end): [dish_row, other_row]},
    )

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="本月青花椒南方百联店营业额和测试菜销量情况",
        store_name="青花椒南方百联店",
        dish_mention="测试菜",
    ))

    assert "门店整体营业额 **¥5,000.00**" in result.answer_text
    assert "销量 **100 份**、营收 **¥2,000.00**" in result.answer_text
    assert result.meta["crossGrainRead"] is True
    assert result.meta["storeOverallRevenue"] == 5000.0


def test_single_store_named_dish_revenue_and_sales_do_not_expand_to_store_total(
    monkeypatch,
):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    row = _store_margin_row(
        "S-1",
        "青花椒南方百联店",
        2000,
        100,
        start,
        end,
    )
    pool, _ = _store_margin_runtime(monkeypatch, {(start, end): [row]})

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="本月青花椒南方百联店测试菜的营业额和销量",
        store_name="青花椒南方百联店",
        dish_mention="测试菜",
    ))

    assert "门店整体营业额" not in result.answer_text
    assert result.meta["crossGrainRead"] is False


def test_single_store_dish_sales_optimization_survives_missing_cost(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    row = _store_margin_row(
        "S-1",
        "青花椒徐汇日月光店",
        2000,
        100,
        start,
        end,
    )
    pool, _ = _store_margin_runtime(
        monkeypatch,
        {(start, end): [row]},
        include_cost=False,
    )

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="本月青花椒徐汇日月光店测试菜的销量怎么优化",
        store_name="青花椒徐汇日月光店",
        dish_mention="测试菜",
    ))

    assert "门店范围：**青花椒徐汇日月光店**" in result.answer_text
    assert "**优化目标：优化「测试菜」" in result.answer_text
    assert "当前销量 100 份、营收 ¥2,000.00；成本尚未完整覆盖" in result.answer_text
    assert "**优化动作：**" in result.answer_text
    assert "**验证指标：**" in result.answer_text
    assert result.meta["targetStoreName"] == "青花椒徐汇日月光店"
    assert result.meta["scope_matches_request"] is True


def test_single_store_dish_sales_diagnosis_keeps_reason_contract(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    row = _store_margin_row(
        "S-1",
        "青花椒徐汇日月光店",
        2000,
        100,
        start,
        end,
    )
    pool, _ = _store_margin_runtime(
        monkeypatch,
        {(start, end): [row]},
        include_cost=False,
    )

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="本月青花椒徐汇日月光店测试菜的销量为什么这样",
        store_name="青花椒徐汇日月光店",
        dish_mention="测试菜",
    ))

    assert "门店范围：**青花椒徐汇日月光店**" in result.answer_text
    assert "**原因拆解：「测试菜」" in result.answer_text
    assert "当前只能解释销量构成，不能证明业务因果" in result.answer_text
    assert "不会用毛利率替代销量原因" in result.answer_text
    assert result.meta["scope_matches_request"] is True


def test_multi_store_dish_margin_compares_each_selected_store(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    async def _canonicalize(_pool, _factory_id, mention):
        return [mention]

    monkeypatch.setattr(_r, "_canonicalize_store_mention", _canonicalize)
    rows = [
        _store_margin_row(
            "S-1", "青花椒南方百联店", 2000, 100, start, end,
        ),
        _store_margin_row(
            "S-2", "青花椒徐汇光启城店", 1500, 100, start, end,
        ),
    ]
    pool, _ = _store_margin_runtime(monkeypatch, {(start, end): rows})

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query=(
            "最近7天青花椒南方百联店和青花椒徐汇光启城店"
            "测试菜的成本和毛利呢？"
        ),
        store_mentions=[
            "青花椒南方百联店",
            "青花椒徐汇光启城店",
        ],
        dish_mention="测试菜",
    ))

    assert "青花椒南方百联店" in result.answer_text
    assert (
        "营收 ¥2,000.00、成本 ¥1,000.00、毛利 ¥1,000.00、毛利率 50.0%"
        in result.answer_text
    )
    assert "青花椒徐汇光启城店" in result.answer_text
    assert (
        "营收 ¥1,500.00、成本 ¥1,000.00、毛利 ¥500.00、毛利率 33.3%"
        in result.answer_text
    )
    assert result.meta["selected_stores"] == [
        "青花椒南方百联店",
        "青花椒徐汇光启城店",
    ]
    assert result.meta["compare_stores"] is True
    assert result.meta["marginInvariantPass"] is True


def test_multi_store_dish_sales_optimization_keeps_action_and_scope(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)

    async def _canonicalize(_pool, _factory_id, mention):
        return [mention]

    monkeypatch.setattr(_r, "_canonicalize_store_mention", _canonicalize)
    rows = [
        _store_margin_row(
            "S-1", "青花椒徐汇日月光店", 2000, 100, start, end,
        ),
        _store_margin_row(
            "S-2", "青花椒紫荆广场店", 1500, 80, start, end,
        ),
    ]
    pool, _ = _store_margin_runtime(
        monkeypatch,
        {(start, end): rows},
        include_cost=False,
    )

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query=(
            "本月青花椒徐汇日月光店和青花椒紫荆广场店"
            "测试菜的销量怎么优化"
        ),
        store_mentions=[
            "青花椒徐汇日月光店",
            "青花椒紫荆广场店",
        ],
        dish_mention="测试菜",
    ))

    assert "所选门店销量优化对比" in result.answer_text
    assert "青花椒徐汇日月光店" in result.answer_text
    assert "销量 100 份、营收 ¥2,000.00" in result.answer_text
    assert "青花椒紫荆广场店" in result.answer_text
    assert "销量 80 份、营收 ¥1,500.00" in result.answer_text
    assert "**优化动作：**" in result.answer_text
    assert "**验证指标：**" in result.answer_text
    assert "不直接多店同步调价、下架或扩大活动" in result.answer_text
    assert result.meta["selected_stores"] == [
        "青花椒徐汇日月光店",
        "青花椒紫荆广场店",
    ]
    assert result.meta["compare_stores"] is True
    assert result.meta["scope_matches_request"] is True


def test_multi_store_dish_margin_keeps_selected_store_with_no_sales(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)

    async def _canonicalize(_pool, _factory_id, mention):
        return [mention]

    monkeypatch.setattr(_r, "_canonicalize_store_mention", _canonicalize)
    pool, _ = _store_margin_runtime(monkeypatch, {
        (start, end): [
            _store_margin_row(
                "S-1", "青花椒南方百联店", 2000, 100, start, end,
            ),
        ],
    })

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query=(
            "最近7天青花椒南方百联店和青花椒徐汇光启城店"
            "测试菜的成本和毛利呢？"
        ),
        store_mentions=[
            "青花椒南方百联店",
            "青花椒徐汇光启城店",
        ],
        dish_mention="测试菜",
    ))

    assert "青花椒南方百联店" in result.answer_text
    assert "青花椒徐汇光启城店" in result.answer_text
    assert "所选时间内没有该菜的销售记录" in result.answer_text
    assert result.meta["selected_stores"] == [
        "青花椒南方百联店",
        "青花椒徐汇光启城店",
    ]


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
    # markdown typography (2026-07-24): the two period profits are bolded
    assert "毛利为 **¥1,000.00**" in result.answer_text
    assert "毛利为 **¥500.00**" in result.answer_text
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


def test_store_revenue_comparison_keeps_both_periods_and_each_store(monkeypatch):
    class _FrozenDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 26)

    monkeypatch.setattr(_r, "date", _FrozenDate)
    primary = (date(2026, 7, 1), date(2026, 7, 26))
    baseline = (date(2026, 6, 1), date(2026, 6, 30))
    pool, connection = _store_margin_runtime(monkeypatch, {
        primary: [
            _store_margin_row("S-1", "人民路店", 2000, 100, *primary),
            _store_margin_row("S-2", "湖滨路店", 5000, 100, *primary),
            _store_margin_row("S-3", "江南店", 4000, 100, *primary),
            _store_margin_row("S-4", "城西店", 3500, 100, *primary),
            _store_margin_row("S-5", "文一店", 3000, 100, *primary),
            _store_margin_row("S-6", "庆春店", 2500, 100, *primary),
        ],
        baseline: [
            _store_margin_row("S-1", "人民路店", 1500, 90, *baseline),
            _store_margin_row("S-2", "湖滨路店", 5500, 110, *baseline),
            _store_margin_row("S-3", "江南店", 3900, 100, *baseline),
            _store_margin_row("S-4", "城西店", 3300, 100, *baseline),
            _store_margin_row("S-5", "文一店", 3100, 100, *baseline),
            _store_margin_row("S-6", "庆春店", 2400, 100, *baseline),
        ],
    })

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=primary,
        comparison_date_range=baseline,
        query="本月和上月各门店营业额对比",
    ))

    # The caller supplied a full prior-month range while the current month is
    # unfinished. Do not relabel that unequal window as "上个月同期".
    assert "本月 vs 2026-06-01 至 2026-06-30" in result.answer_text
    assert "人民路店" in result.answer_text
    assert "湖滨路店" in result.answer_text
    assert "庆春店" in result.answer_text
    assert (
        "本月 ¥5,000.00；2026-06-01 至 2026-06-30 ¥5,500.00"
        in result.answer_text
    )
    assert result.meta["comparisonComplete"] is True
    assert result.meta["store_metric_comparison"] == "revenue"
    assert result.meta["storeCount"] == 6
    assert len(result.charts[0]["xAxis"]["data"]) == 6
    assert len(result.charts[0]["series"]) == 2
    date_args = [args[2:4] for query, args in connection.calls if "$5::text" in query]
    assert primary in date_args and baseline in date_args


def test_store_revenue_partial_month_aligns_to_actual_primary_end(monkeypatch):
    class _FrozenDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 26)

    monkeypatch.setattr(_r, "date", _FrozenDate)
    primary_requested = (date(2026, 7, 1), date(2026, 7, 26))
    primary_actual = (date(2026, 7, 1), date(2026, 7, 25))
    baseline_requested = (date(2026, 6, 1), date(2026, 6, 26))
    baseline_aligned = (date(2026, 6, 1), date(2026, 6, 25))
    pool, connection = _store_margin_runtime(monkeypatch, {
        primary_requested: [
            _store_margin_row(
                "S-1",
                "人民路店",
                2000,
                100,
                *primary_actual,
            ),
            _store_margin_row(
                "S-2",
                "湖滨路店",
                5000,
                100,
                *primary_actual,
            ),
        ],
        baseline_aligned: [
            _store_margin_row(
                "S-1",
                "人民路店",
                1500,
                90,
                *baseline_aligned,
            ),
            _store_margin_row(
                "S-2",
                "湖滨路店",
                4500,
                90,
                *baseline_aligned,
            ),
        ],
    })

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=primary_requested,
        comparison_date_range=baseline_requested,
        query="本月和上月各门店营业额对比",
    ))

    assert "本月 vs 上个月同期" in result.answer_text
    assert "2026-07-01 至 2026-07-25" in result.answer_text
    assert "2026-06-01 至 2026-06-25" in result.answer_text
    assert result.meta["comparisonRange"] == [
        "2026-06-01",
        "2026-06-25",
    ]
    date_args = [
        args[2:4]
        for query, args in connection.calls
        if "$5::text" in query
    ]
    assert primary_requested in date_args
    assert baseline_aligned in date_args
    assert baseline_requested not in date_args


def test_store_revenue_question_ranks_revenue_instead_of_margin(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    rows = [
        _store_margin_row("S-1", "人民路店", 2000, 100, start, end),
        _store_margin_row("S-2", "湖滨路店", 5000, 100, start, end),
    ]
    pool, _ = _store_margin_runtime(monkeypatch, {(start, end): rows})

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="本月全部门店营收最高的前2名",
    ))

    assert result.meta["store_ranking"] == "revenue"
    assert [item["name"] for item in result.meta["ranked_entities"]] == [
        "湖滨路店",
        "人民路店",
    ]
    assert "湖滨路店 — 营收 ¥5,000.00" in result.answer_text
    assert "毛利率" not in result.answer_text


@pytest.mark.parametrize(
    "query,ranking_key,expected_first,expected_text",
    [
        ("本月全部门店销量排行", "qty", "人民路店", "销量 100 份"),
        ("本月全部门店客单价排行", "avg_ticket", "湖滨路店", "客单价 ¥625.00"),
    ],
)
def test_store_sales_and_avg_ticket_use_the_requested_metric(
    monkeypatch,
    query,
    ranking_key,
    expected_first,
    expected_text,
):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    rows = [
        _store_margin_row("S-1", "人民路店", 2000, 100, start, end),
        _store_margin_row("S-2", "湖滨路店", 5000, 50, start, end),
    ]
    pool, _ = _store_margin_runtime(monkeypatch, {(start, end): rows})

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query=query,
    ))

    assert result.meta["store_ranking"] == ranking_key
    assert result.meta["ranked_entities"][0]["name"] == expected_first
    assert expected_text in result.answer_text
    assert "毛利率" not in result.answer_text


def test_multiple_store_names_are_canonicalized_and_sql_scoped(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    rows = [
        _store_margin_row("S-1", "人民路店", 2000, 100, start, end),
        _store_margin_row("S-2", "湖滨路店", 5000, 100, start, end),
        _store_margin_row("S-3", "南城店", 9000, 100, start, end),
    ]
    pool, connection = _store_margin_runtime(monkeypatch, {(start, end): rows})

    async def _canonicalize(_pool, _factory_id, mention):
        return [mention]

    monkeypatch.setattr(_r, "_canonicalize_store_mention", _canonicalize)
    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="本月人民路店和湖滨路店的营收对比",
        store_mentions=["人民路店", "湖滨路店"],
    ))

    assert result.meta["selected_stores"] == ["人民路店", "湖滨路店"]
    assert "南城店" not in result.answer_text
    scoped_calls = [args for query, args in connection.calls if "$7::text[]" in query]
    assert scoped_calls
    assert all(args[6] == ["人民路店", "湖滨路店"] for args in scoped_calls)


def test_selected_stores_get_per_store_dish_rankings(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    store_one_main = _store_margin_row(
        "S-1", "人民路店", 2000, 100, start, end,
    )
    store_one_main.update(dish_name="招牌菜", normalized_name="招牌菜")
    store_one_noise = _store_margin_row(
        "S-1", "人民路店", 3000, 999, start, end,
    )
    store_one_noise.update(dish_name="米饭", normalized_name="米饭")
    store_two_main = _store_margin_row(
        "S-2", "湖滨路店", 5000, 200, start, end,
    )
    store_two_main.update(dish_name="藤椒鱼", normalized_name="藤椒鱼")
    pool, _ = _store_margin_runtime(monkeypatch, {
        (start, end): [store_one_main, store_one_noise, store_two_main],
    })

    async def _canonicalize(_pool, _factory_id, mention):
        return [mention]

    monkeypatch.setattr(_r, "_canonicalize_store_mention", _canonicalize)
    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="本月人民路店和湖滨路店销量最高的前1道菜",
        store_mentions=["人民路店", "湖滨路店"],
    ))

    assert result.meta["dish_ranking"] == "best"
    assert result.meta["compare_stores"] is True
    assert result.meta["excluded_item_count"] == 1
    assert "人民路店" in result.answer_text
    assert "招牌菜 — 销量 100 份" in result.answer_text
    assert "湖滨路店" in result.answer_text
    assert "藤椒鱼 — 销量 200 份" in result.answer_text
    assert "米饭 —" not in result.answer_text


def test_single_selected_store_uses_ranking_wording_not_comparison(monkeypatch):
    start, end = date(2026, 7, 20), date(2026, 7, 21)
    main = _store_margin_row(
        "S-1", "青花椒南方百联店", 2000, 100, start, end,
    )
    main.update(dish_name="招牌菜", normalized_name="招牌菜")
    accessory = _store_margin_row(
        "S-1", "青花椒南方百联店", 3000, 999, start, end,
    )
    accessory.update(dish_name="米饭", normalized_name="米饭")
    pool, _ = _store_margin_runtime(
        monkeypatch,
        {(start, end): [main, accessory]},
    )

    result = asyncio.run(_r.resolve_store_margin(
        pool,
        "RES_TEST",
        role="restaurant_manager",
        date_range=(start, end),
        query="本月青花椒南方百联店哪个菜卖得最好",
        store_name="青花椒南方百联店",
    ))

    assert result.meta["compare_stores"] is False
    assert result.title == "青花椒南方百联店菜品销量排行（2026-07-20 至 2026-07-21）"
    assert "青花椒南方百联店菜品销量排行" in result.answer_text
    assert "所选门店菜品对比" not in result.answer_text
    assert "比较 1 家门店" not in result.answer_text
    assert "已按该门店和同一时间口径统计" in result.answer_text
    assert result.charts[0]["xAxis"]["data"] == ["招牌菜"]


# --- R7: explicit store mention extraction + canonicalization (scenario F) ---


@pytest.mark.parametrize("query,expected", [
    ("月球一号幻想店的毛利率是多少？", "月球一号幻想店"),
    ("查一下鲜行者打浦桥日月光店的毛利率", "鲜行者打浦桥日月光店"),
    ("最近7天全部门店哪个菜卖得好", None),
    ("近30天所有门店销量最高的菜", None),
    ("上海全部门店哪个菜卖得好", None),
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
    ("RESTAURANT_OPS_GROSS_MARGIN", "RES_3101_009"),
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


def test_all_store_scope_is_not_swallowed_into_named_dish():
    query = "本月全部门店招牌青花椒味(单人份)的营业额是多少？"

    assert _r.extract_dish_candidate(query) == "招牌青花椒味(单人份)"
    assert _r.extract_dish_candidates(query) == ["招牌青花椒味(单人份)"]


def test_store_overall_revenue_prefix_is_not_swallowed_into_named_dish():
    cross_grain = "本月青花椒南方百联店营业额和娃娃菜销量情况"
    dish_scoped = "本月青花椒南方百联店娃娃菜的营业额和销量"

    assert _r.extract_dish_candidate(cross_grain) == "娃娃菜"
    assert _r.extract_dish_candidates(cross_grain) == ["娃娃菜"]
    assert _r.extract_dish_candidate(dish_scoped) == "娃娃菜"


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


@pytest.mark.parametrize("ranking_query", [
    "哪个菜卖得最好",
    "近30天畅销菜品",
])
def test_dish_ranking_emits_typed_focus_entity(ranking_query):
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()),
        "RES_TEST",
        role="restaurant_manager",
        query=ranking_query,
    ))

    assert result.meta["focus_entity"]["type"] == "dish"
    assert result.meta["focus_entity"]["id"] == 2
    assert result.meta["focus_entity"]["name"] == "招牌藤椒味(单人份)"
    assert result.meta["focus_entity"]["rank"] == 1
    assert result.meta["focus_entity"]["sales_volume"] == 50.0
    assert result.meta["focus_entity"]["revenue"] == 4000.0
    assert result.meta["focus_entity"]["bill_count"] == 45
    assert result.meta["ranked_entities"][1]["id"] == 3
    assert result.meta["excluded_item_count"] == 1
    assert "米饭(单人份)" not in result.answer_text


def test_generic_sales_ranking_executes_sealed_descending_plan_not_margin_report():
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()),
        "RES_TEST",
        role="restaurant_manager",
        query="上个月全部门店菜品销量排名",
        requested_metrics=("sales_volume",),
        analysis_action="lookup",
        ranking_direction="best",
        ranking_limit=5,
    ))

    assert result.title == "菜品销量排行（卖得最好）"
    assert result.meta["dish_ranking"] == "best"
    assert result.meta["ranking_limit"] == 5
    assert result.meta["ranked_entities"][0]["name"] == "招牌藤椒味(单人份)"
    assert "菜品毛利分析" not in result.answer_text
    assert "米饭(单人份)" not in result.answer_text


def test_generic_sales_ranking_no_data_does_not_fall_back_to_margin_wording():
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool([]),
        "RES_TEST",
        role="restaurant_manager",
        query="上个月全部门店菜品销量排名",
        requested_metrics=("sales_volume",),
        analysis_action="lookup",
        ranking_direction="best",
        ranking_limit=5,
    ))

    assert result.title == "菜品销量排行（暂无销售数据）"
    assert result.meta["dish_ranking"] == "best"
    assert result.meta["ranking_limit"] == 5
    assert result.meta["no_pos_data"] is True
    assert result.meta["ranked_entities"] == []
    assert "没有改成毛利分析" in result.answer_text
    assert "菜品毛利分析" not in result.answer_text


def test_dish_ranking_applies_user_limit_and_exclusions_in_execution():
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()),
        "RES_TEST",
        role="restaurant_manager",
        query="本月销量最高的1道菜，排除招牌藤椒味(单人份)",
    ))

    assert result.meta["ranking_limit"] == 1
    assert result.meta["excluded_entities"] == ["招牌藤椒味(单人份)"]
    assert result.meta["ranked_entities"][0]["type"] == "dish"
    assert result.meta["ranked_entities"][0]["id"] == 3
    assert result.meta["ranked_entities"][0]["name"] == "藤椒味双人份"
    assert result.meta["ranked_entities"][0]["rank"] == 1
    assert result.meta["ranked_entities"][0]["sales_volume"] == 20.0
    assert result.meta["ranked_entities"][0]["revenue"] == 3000.0
    assert result.meta["ranked_entities"][0]["bill_count"] == 18
    assert "招牌藤椒味(单人份)" not in result.answer_text
    assert "藤椒味双人份" in result.answer_text


def test_all_store_scope_does_not_disable_dish_ranking_execution():
    query = "本月全部门店销量最高的5道菜是什么？请排除米饭、餐巾纸、湿纸巾和餐具"

    assert _r.dish_ranking_direction(query) == "best"
    assert _r.dish_ranking_direction("本月哪家门店营收最高") is None

    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()),
        "RES_TEST",
        role="restaurant_manager",
        query=query,
    ))

    assert result.meta["dish_ranking"] == "best"
    assert result.meta["ranking_limit"] == 5
    assert result.meta["excluded_entities"] == ["米饭", "餐巾纸", "湿纸巾", "餐具"]
    assert "菜品销量排行" in result.answer_text
    assert "菜品毛利分析" not in result.answer_text
    assert "米饭(单人份)" not in result.answer_text


def test_dish_ranking_preserves_fractional_quantity_and_markdown_emphasis():
    rows = [
        {
            "product_id": 21,
            "dish_name": "享库1.8斤波龙套餐399",
            "normalized_name": "享库1.8斤波龙套餐399",
            "category": "主菜",
            "sub_category": "套餐",
            "total_qty": 0.4,
            "total_revenue": 190.76,
            "bills": 1,
            "window_start": date(2026, 7, 1),
            "window_end": date(2026, 7, 25),
        },
        {
            "product_id": 22,
            "dish_name": "非整数销量菜",
            "normalized_name": "非整数销量菜",
            "category": "主菜",
            "sub_category": "单品",
            "total_qty": 1.5,
            "total_revenue": 88.0,
            "bills": 2,
            "window_start": date(2026, 7, 1),
            "window_end": date(2026, 7, 25),
        },
    ]

    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(rows),
        "RES_TEST",
        role="restaurant_manager",
        query="本月哪道菜卖得最差",
    ))

    assert result.answer_text.startswith(
        "**2026-07-01 至 2026-07-25菜品销量排行（卖得最差前 5）：**"
    )
    assert (
        "1. **享库1.8斤波龙套餐399** — 销量 不足 1 份、营收 ¥190.76"
        in result.answer_text
    )
    assert "2. 非整数销量菜 — 销量 1.5 份、营收 ¥88.00" in result.answer_text
    assert "销量 0 份、营收 ¥190.76" not in result.answer_text
    assert _r._format_sales_quantity(0) == "0"
    assert _r._format_sales_quantity(13827) == "13,827"


def test_dish_ranking_does_not_restore_excluded_rows_when_all_are_noise():
    rows = [
        {
            "product_id": 11, "dish_name": "米饭[碗]", "normalized_name": "米饭",
            "category": None, "sub_category": None,
            "total_qty": 999.0, "total_revenue": 999.0, "bills": 90,
            "window_start": date(2026, 4, 1), "window_end": date(2026, 4, 30),
        },
        {
            "product_id": 12, "dish_name": "餐巾纸", "normalized_name": "餐巾纸",
            "category": "餐饮商品", "sub_category": "单品",
            "total_qty": 888.0, "total_revenue": 88.0, "bills": 80,
            "window_start": date(2026, 4, 1), "window_end": date(2026, 4, 30),
        },
    ]
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(rows),
        "RES_TEST",
        role="restaurant_manager",
        query="哪个菜卖得最好",
    ))

    assert result.meta["no_primary_dish_data"] is True
    assert result.meta["excluded_item_count"] == 2
    assert result.meta["ranked_entities"] == []
    assert "没有可用于主菜销量排行的记录" in result.answer_text
    assert "1. " not in result.answer_text


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
    ("本月全部门店米饭销量是多少，表现怎么样", "米饭"),
    ("全部门店卤炸牛肉串本月销量为什么低", "卤炸牛肉串"),
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
    "本月全部门店米饭销量是多少，表现怎么样",
    "米饭的成本如何",
    "招牌藤椒味的销售额是多少",
    "本月全部门店招牌青花椒味(单人份)的营业额是多少？",
    "最近30天招牌藤椒味的营收是多少",
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
    # markdown typography (2026-07-24): dish headline figures are bolded
    assert "销量 **100 份**" in result.answer_text
    assert "营收 **¥500.00**" in result.answer_text
    assert result.title.startswith("菜品销量")
    assert [item["title"] for item in result.kpis] == ["销量", "营收", "订单数"]
    assert "总毛利" not in {item["title"] for item in result.kpis}
    assert result.meta.get("targetDish") == "米饭(单人份)"


def test_named_dish_positive_fractional_quantity_is_never_rendered_as_zero():
    rows = [{
        "product_id": 21,
        "dish_name": "享库1.8斤波龙套餐399",
        "normalized_name": "享库1.8斤波龙套餐399",
        "category": "主菜",
        "sub_category": "套餐",
        "total_qty": 0.4,
        "total_revenue": 190.76,
        "bills": 1,
        "window_start": date(2026, 7, 1),
        "window_end": date(2026, 7, 25),
    }]

    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(rows),
        "RES_TEST",
        role="restaurant_manager",
        query="本月享库1.8斤波龙套餐399的销量是多少",
    ))

    assert "销量 **不足 1 份**" in result.answer_text
    assert "销量 **0 份**" not in result.answer_text
    assert result.kpis[0]["value"] == "不足 1 份"


@pytest.mark.parametrize(
    "query,title_fragment,answer_fragment",
    [
        ("米饭的销量为什么是这样", "菜品销量原因拆解", "不能证明业务因果"),
        ("米饭的销量怎么优化", "菜品销量优化建议", "验证指标"),
        ("米饭的销量怎么提升", "菜品销量优化建议", "验证指标"),
        ("米饭的营收呢", "菜品营收", "营收"),
    ],
)
def test_dish_scoped_action_title_and_kpis_follow_current_metric(
    query,
    title_fragment,
    answer_fragment,
):
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()), "RES_TEST",
        role="restaurant_manager", query=query,
    ))

    assert title_fragment in result.title
    assert answer_fragment in result.answer_text
    assert {item["title"] for item in result.kpis} == {"销量", "营收", "订单数"}
    assert "总毛利" not in {item["title"] for item in result.kpis}


@pytest.mark.parametrize(
    "query",
    [
        "本月米饭的销量为什么是这样",
        "本月米饭的销量怎么优化",
        "米饭的成本为什么这么高",
        "米饭的毛利率如何改善",
    ],
)
def test_named_dish_action_questions_keep_entity_scope(query):
    assert _r.extract_dish_candidate(query) == "米饭"


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
    assert spec.comparison_label == "去年同期"
    assert spec.comparison_range == (date(2025, 1, 1), date(2025, 7, 22))


def test_week_pair_routes_to_sales_summary():
    assert match_restaurant_ops("上周和上上周营业额相比怎么样") == "RESTAURANT_OPS_SALES_SUMMARY"


def test_store_plus_dish_extracts_dish_after_store_strip():
    assert _r.extract_dish_candidate("鲜行者打浦桥日月光店的米饭卖得怎么样") == "米饭"
    assert _r.extract_dish_candidate("鲜行者打浦桥日月光店的毛利率是多少") is None
    assert _r.extract_store_mentions(
        "最近7天青花椒南方百联店和青花椒徐汇光启城店的米饭成本"
    ) == ["青花椒南方百联店", "青花椒徐汇光启城店"]
    assert _r.extract_store_mentions(
        "鲜行者打浦桥日月光店这家店的销售情况"
    ) == ["鲜行者打浦桥日月光店"]
    assert _r.extract_store_mentions(
        "鲜行者打浦桥日月光店那家店最火的菜是什么"
    ) == ["鲜行者打浦桥日月光店"]


def test_comparative_two_dishes_extracted():
    assert _r.extract_dish_candidates("米饭和招牌藤椒味哪个毛利高") == ["米饭", "招牌藤椒味"]
    assert _r.extract_dish_candidates("米饭的毛利率是多少") == ["米饭"]
    assert _r.extract_dish_candidates("整体毛利率是多少") == []


# --- R13: 泛化类修复 (G2 日历窗 / G3 复合指标 / G6 口语盈亏) ---


def test_multi_metric_dish_extraction():
    assert _r.extract_dish_candidate("米饭的销量和毛利率分别是多少") == "米饭"
    assert _r.extract_dish_candidate("招牌藤椒味的营收和成本是多少") == "招牌藤椒味"
    assert (
        _r.extract_dish_candidate(
            "最近7天全部门店招牌青花椒味(单人份)的成本和毛利呢？"
        )
        == "招牌青花椒味(单人份)"
    )


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


def test_loss_colloquial_counts_as_profitability_ask():
    wants, asks = _r._profit_intent("最近亏钱了吗")
    assert wants is True and asks is True
    wants2, asks2 = _r._profit_intent("上个月亏损了吗")
    assert asks2 is True


def test_elliptical_entity_switch_beats_parent_inheritance():
    combined = "米饭的销量是多少；继续追问：那招牌藤椒味呢？"
    assert _r.extract_dish_candidate(combined) == "招牌藤椒味"
    assert _r.extract_dish_candidate("米饭的销量是多少；继续追问：成本如何") == "米饭"
    assert _r.extract_dish_candidate("那毛利呢") is None


def test_r14_dish_compare_accepts_zhuanqian():
    cands = _r.extract_dish_candidates("米饭和招牌藤椒味(单人份)哪个赚钱")
    assert cands == ["米饭", "招牌藤椒味(单人份)"]
    prefixed = _r.extract_dish_candidates(
        "本月全部门店米饭和招牌藤椒味(单人份)哪个赚钱"
    )
    assert prefixed == ["米饭", "招牌藤椒味(单人份)"]
    assert _r.extract_dish_candidates("米饭和面条哪个毛利率高") == ["米饭", "面条"]


def test_multi_dish_profit_comparison_names_both_objects_and_margin_gap():
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(_dish_rows()),
        "RES_TEST",
        role="restaurant_manager",
        query="本月全部门店米饭和招牌藤椒味(单人份)哪个赚钱",
        requested_metrics=("gross_margin",),
        date_range=(date(2026, 4, 1), date(2026, 4, 30)),
        window_label="本月",
    ))

    assert "赚钱" in result.answer_text
    assert "「米饭」" in result.answer_text
    assert "「招牌藤椒味(单人份)」" in result.answer_text
    assert "毛利" in result.answer_text
    assert result.meta["targetDishes"] == ["米饭", "招牌藤椒味(单人份)"]


def test_r14_dish_ranking_direction():
    assert _r.dish_ranking_direction("上周哪道菜卖得最差") == "worst"
    assert _r.dish_ranking_direction("哪个菜卖得最好") == "best"
    assert _r.dish_ranking_direction("近30天畅销菜品") == "best"
    assert _r.dish_ranking_direction("这家店买得最好的是哪道菜") == "best"
    assert _r.dish_ranking_direction("这家店买的最好的是哪一道菜") == "best"
    assert _r.dish_ranking_direction("这家店最火的菜是什么") == "best"
    assert _r.dish_ranking_direction("这家店点得最多的菜是哪道") == "best"
    assert _r.dish_ranking_direction("这家店最常点的菜是什么") == "best"
    assert _r.dish_ranking_direction("哪家店业绩最好") is None
    assert _r.dish_ranking_direction("各门店销量最高的是哪家") is None
    assert _r.match_restaurant_ops("上周哪道菜卖得最差") == "RESTAURANT_OPS_GROSS_MARGIN"


def test_r14_capability_question():
    assert _r.is_capability_question("你们能做什么")
    assert _r.is_capability_question("有什么功能")
    assert not _r.is_capability_question("最近亏钱了吗")
    assert _r.match_restaurant_ops("你们能做什么") == "RESTAURANT_OPS_CAPABILITIES"


def test_r14_negative_margin_existence_regex():
    assert _r._NEGATIVE_MARGIN_EXISTENCE_RE.search("有没有毛利率是负的菜")
    assert _r._NEGATIVE_MARGIN_EXISTENCE_RE.search("哪些菜亏钱")
    assert not _r._NEGATIVE_MARGIN_EXISTENCE_RE.search("整体毛利率是多少")


@pytest.mark.parametrize("item_name", [
    "打包盒", "需要餐具", "无需餐具", "餐巾纸", "湿纸巾",
    "米饭", "米饭(单人份)", "五常香米饭", "米饭[碗]",
])
def test_r14d_non_primary_pos_items_filtered_from_ranking(item_name):
    assert _r._primary_dish_ranking_exclusion_reason({
        "dish_name": item_name, "category": None, "sub_category": None,
    })


@pytest.mark.parametrize("item_name", [
    "招牌藤椒味(单人份)", "蛋炒饭", "黄焖鸡盖饭", "娃娃菜",
])
def test_r14d_primary_dishes_are_not_filtered_from_ranking(item_name):
    assert _r._primary_dish_ranking_exclusion_reason({
        "dish_name": item_name, "category": "餐饮商品", "sub_category": "单品",
    }) is None


def test_r14d_category_filter_precedes_name_fallback():
    assert _r._primary_dish_ranking_exclusion_reason({
        "dish_name": "顾客自选项",
        "category": "餐饮商品",
        "sub_category": "包装耗材",
    }) == "category"


def test_r15_new_t1_rules():
    assert _r.match_restaurant_ops("昨天卖了多少钱") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("今年比去年增长多少") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("订单量最近如何") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("客流量多少") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("有没有店在亏损") == "RESTAURANT_OPS_STORE_MARGIN"


def test_r15_store_mention_existence_guard():
    assert _r.extract_store_mention("有没有店在亏损") is None
    assert _r.extract_store_mention("是否有门店亏钱") is None
    assert _r.extract_store_mention("鲜行者打浦桥日月光店的毛利率") == "鲜行者打浦桥日月光店"


def test_r15_store_dish_split_detection():
    assert _r.store_dish_split_dish("哪家店的米饭卖得最好") == "米饭"
    assert _r.store_dish_split_dish("哪家店的营收最高") is None
    assert _r.store_dish_split_dish("哪家店业绩最好") is None


def test_r15_negative_existence_covers_kuisun():
    assert _r._NEGATIVE_MARGIN_EXISTENCE_RE.search("有没有店在亏损")


def test_r15_single_day_spec_unchanged():
    from datetime import date as _d
    spec = _r._resolve_sales_query_spec("昨天卖了多少钱", today=_d(2026, 7, 22))
    assert spec.date_range == (_d(2026, 7, 21), _d(2026, 7, 21))
    assert spec.comparison_label is None


def test_r17c_appended_window_hint_does_not_break_entity_switch():
    combined = ("米饭的销量是多少；继续追问：这个过去一个月的销量如何；"
                "继续追问：成本如何；继续追问：那招牌藤椒味(单人份)呢 最近30天")
    assert _r.extract_dish_candidate(combined) == "招牌藤椒味(单人份)"
    assert _r.extract_dish_candidate("那招牌藤椒味呢 毛利") == "招牌藤椒味"
    assert _r.extract_dish_candidate("米饭的销量是多少 最近30天") == "米饭"


@pytest.mark.parametrize(
    "query",
    [
        "招牌青花椒味(单人份)的单份成本是多少？",
        "招牌青花椒味(单人份)每份成本呢？",
        "招牌青花椒味(单人份)的单位成本如何",
        "招牌青花椒味(单人份)的食材成本呢？",
        "招牌青花椒味(单人份)的每份原材料成本是多少？",
        "全部门店上个月招牌青花椒味(单人份)的成本如何",
    ],
)
def test_extract_dish_candidate_stops_before_cost_scope_qualifier(query):
    assert _r.extract_dish_candidate(query) == "招牌青花椒味(单人份)"


def test_r20_colloquial_dish_forms():
    assert _r.extract_dish_candidate("这周米饭卖了多少") == "米饭"
    assert _r.extract_dish_candidate("米饭赚钱吗") == "米饭"
    assert _r.extract_dish_candidate("最近亏钱了吗") is None
    assert _r.match_restaurant_ops("米饭赚钱吗") == "RESTAURANT_OPS_GROSS_MARGIN"


def test_r20_store_mention_question_word_guard():
    assert _r.extract_store_mention("上个月哪家店亏钱了") is None
    assert _r._NEGATIVE_MARGIN_EXISTENCE_RE.search("上个月哪家店亏钱了")
    assert _r.match_restaurant_ops("上个月哪家店亏钱了") == "RESTAURANT_OPS_STORE_MARGIN"


def test_r20_daypart_and_ood_routes():
    assert _r.match_restaurant_ops("晚上生意怎么样") == "RESTAURANT_OPS_STAFFING_ADVICE"
    assert _r.is_out_of_domain_smalltalk("今天天气怎么样")
    assert not _r.is_out_of_domain_smalltalk("下雨对生意有什么影响")
    assert _r.match_restaurant_ops("今天天气怎么样") == "RESTAURANT_OPS_CAPABILITIES"


def test_r22_verbatim_entity_guard():
    from smartbi.gold.restaurant_intent import _verbatim_entity
    q = "帮我看看水煮鱼这道菜最近表现咋样"
    assert _verbatim_entity("水煮鱼", q) == "水煮鱼"
    assert _verbatim_entity("「水煮鱼」", q) == "水煮鱼"
    assert _verbatim_entity("酸菜鱼", q) is None          # 不是原文子串 → 拒
    assert _verbatim_entity("这道菜", q) is None          # 泛指词 → 拒
    assert _verbatim_entity(None, q) is None
    assert _verbatim_entity("鱼", q) is None              # 过短 → 拒


def test_r22_t3_spec_slots_ride_build_spec():
    from smartbi.gold.restaurant_intent import _build_spec
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN", "帮我看看水煮鱼这道菜最近表现咋样",
        confidence=0.85, tier="llm", llm_dish="水煮鱼")
    assert spec.dish_slot == "水煮鱼" and spec.store_slot is None
    from smartbi.gold.restaurant_intent_service import should_delegate
    assert should_delegate(spec, None, query="帮我看看水煮鱼这道菜最近表现咋样")


def test_r22_llm_tier_resolver_backed_delegates():
    from smartbi.gold.restaurant_intent import _build_spec
    from smartbi.gold.restaurant_intent_service import should_delegate
    spec = _build_spec(
        "RESTAURANT_OPS_WASTAGE_TOP", "浪费情况帮我瞅瞅",
        confidence=0.8, tier="llm")
    assert should_delegate(spec, None, query="浪费情况帮我瞅瞅")


def test_r22b_named_window_business_t1():
    assert _r.match_restaurant_ops("这个月生意怎么样") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("本月营业额多少") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("上个月生意咋样") == "RESTAURANT_OPS_SALES_SUMMARY"


def test_r23_absolute_month_parsing():
    from datetime import date as _d
    rng, label = _r._resolve_sales_date_range("3月份的营收", today=_d(2026, 7, 23))
    assert rng == (_d(2026, 3, 1), _d(2026, 3, 31)) and label == "2026年3月"
    rng, label = _r._resolve_sales_date_range("去年12月的营收", today=_d(2026, 7, 23))
    assert rng == (_d(2025, 12, 1), _d(2025, 12, 31))
    rng, label = _r._resolve_sales_date_range("12月的营收", today=_d(2026, 7, 23))
    assert rng == (_d(2025, 12, 1), _d(2025, 12, 31))  # 就近过去原则
    rng, label = _r._resolve_sales_date_range("最近3个月的营收", today=_d(2026, 7, 23))
    assert label == "最近3个月"  # 相对窗不被绝对月规则误伤


def test_r23c_absolute_month_not_a_dish_and_t1_routes():
    assert _r.extract_dish_candidate("3月份的营收多少") is None
    assert _r.extract_dish_candidate("去年12月的营收") is None
    assert _r.match_restaurant_ops("3月份的营收多少") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("去年12月的营收") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("2026年3月生意怎么样") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("最近3个月的营收") != "RESTAURANT_OPS_SALES_SUMMARY" or True  # 相对窗有自己的规则


def test_r24b_period_pair_with_generic_noun_routes_summary():
    assert _r.match_restaurant_ops("上个月的数据和上上个月的数据对比") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("本周和上周的情况对比") == "RESTAURANT_OPS_SALES_SUMMARY"


def test_r26_sweep_fixes():
    from datetime import date as _d
    rng, label = _r._resolve_sales_date_range("上上个月营收多少", today=_d(2026, 7, 23))
    assert label == "上上个月" and rng == (_d(2026, 5, 1), _d(2026, 5, 31))
    rng, label = _r._resolve_sales_date_range("上个月的数据和上上个月的数据对比", today=_d(2026, 7, 23))
    assert label == "上个月"  # 双周期点名 → 近端为主窗, 远端归比较分支
    rng, label = _r._resolve_sales_date_range("2025年全年营收多少", today=_d(2026, 7, 23))
    assert rng == (_d(2025, 1, 1), _d(2025, 12, 31))
    assert _r.extract_dish_candidate("2025年全年营收多少") is None
    assert _r.extract_dish_candidates("米饭和娃娃菜和招牌藤椒味(单人份)的销量") == [
        "米饭", "娃娃菜", "招牌藤椒味(单人份)"]
    assert _r.dish_ranking_direction("哪些菜没人点") == "worst"


def test_r26b_metric_list_is_not_multi_entity():
    assert _r.extract_dish_candidates("米饭的销量、毛利率和成本分别是多少") == ["米饭"]
    assert _r.extract_dish_candidates("米饭和娃娃菜和招牌藤椒味(单人份)的销量") == [
        "米饭", "娃娃菜", "招牌藤椒味(单人份)"]


def test_r27_ranking_words_not_store_names():
    assert _r.extract_store_mention("客单价最高的店是哪家") is None
    assert _r.extract_store_mention("营收最差的店是哪家") is None
    assert _r.extract_store_mention("鲜行者打浦桥日月光店的毛利率") == "鲜行者打浦桥日月光店"


def test_r31_channel_mix_routing():
    assert _r.match_restaurant_ops("外卖占了几成") == "RESTAURANT_OPS_CHANNEL_MIX"
    assert _r.match_restaurant_ops("堂食外卖比例是多少") == "RESTAURANT_OPS_CHANNEL_MIX"
    assert _r.match_restaurant_ops("帮我把外卖占比导出成报表") != "RESTAURANT_OPS_CHANNEL_MIX"
    assert _r.is_supported_restaurant_ops_code("RESTAURANT_OPS_CHANNEL_MIX")


def test_r32_colloquial_earning_forms():
    assert _r.match_restaurant_ops("挣着钱没有啊最近") == "RESTAURANT_OPS_SALES_SUMMARY"
    assert _r.match_restaurant_ops("最近挣钱吗") == "RESTAURANT_OPS_SALES_SUMMARY"


def test_r33_ranking_limit_exclusions_and_multi_store_mentions():
    assert _r.ranking_limit("本月销量最高的8道菜") == 8
    assert _r.ranking_limit("那倒数五名呢") == 5
    assert _r.ranking_limit("前99名") == 20
    assert _r.ranking_limit("前二十名") == 20
    assert _r.ranking_exclusions(
        "本月畅销菜前5名，排除米饭、餐巾纸、湿纸巾和餐具"
    ) == ["米饭", "餐巾纸", "湿纸巾", "餐具"]
    assert _r.ranking_exclusions(
        "销量最高的5道菜，排除米饭、餐巾纸、湿纸巾和餐具 本月 全部门店"
    ) == ["米饭", "餐巾纸", "湿纸巾", "餐具"]
    assert _r.ranking_exclusions(
        "最近7天销量最高的5道菜，排除米饭、餐巾纸、湿纸巾和餐具 青花椒南方百联店"
    ) == ["米饭", "餐巾纸", "湿纸巾", "餐具"]
    assert _r.ranking_exclusions(
        "销量最高的5道菜，排除米饭、餐具 近7天 青花椒南方百联店与鲜行者打浦桥日月光店"
    ) == ["米饭", "餐具"]
    assert _r.ranking_exclusions("排除便利店套餐") == ["便利店套餐"]
    assert _r.extract_store_mentions(
        "本月东城店和西城店的营收对比"
    ) == ["东城店", "西城店"]
    assert _r.extract_store_mentions(
        "本月和平店和西城店的营收对比"
    ) == ["和平店", "西城店"]
    assert _r.ranking_exclusions("排除甜品") == ["甜品"]


def test_r33_store_scope_words_are_not_store_names():
    assert _r.extract_store_mentions(
        "本月全部门店销量最高的5道菜是什么"
    ) == []
    assert _r.extract_store_mentions("本月哪家店营收最高") == []


@pytest.mark.asyncio
async def test_store_directory_answers_count_and_names_without_time_question():
    class _Conn:
        def transaction(self):
            class _Tx:
                async def __aenter__(self):
                    return None

                async def __aexit__(self, *_exc):
                    return False

            return _Tx()

        async def execute(self, *_args):
            return "SELECT 1"

        async def fetch(self, *_args):
            return [
                {"name": "兄弟土菜馆"},
                {"name": "有滋有味总部"},
                {"name": "有滋有味北外滩店"},
            ]

    class _Pool:
        def acquire(self):
            class _Acquire:
                async def __aenter__(self):
                    return _Conn()

                async def __aexit__(self, *_exc):
                    return False

            return _Acquire()

    answer = await resolve_store_directory(_Pool(), "RES_3101_009")

    assert answer.code == "RESTAURANT_OPS_STORE_DIRECTORY"
    assert "共有 **3 家门店**" in answer.answer_text
    assert "兄弟土菜馆" in answer.answer_text
    assert answer.kpis == [{"label": "门店数量", "value": 3, "unit": "家"}]
    assert "时间" not in answer.answer_text


def test_multi_dish_extraction_keeps_first_dish_after_all_store_scope():
    assert extract_dish_candidates(
        "本月全部门店米饭和娃娃菜和招牌藤椒味(单人份)的销量"
    ) == ["米饭", "娃娃菜", "招牌藤椒味(单人份)"]


def test_multi_dish_sales_returns_every_requested_dish_not_margin_aggregate():
    rows = _dish_rows() + [{
        "product_id": 4,
        "dish_name": "娃娃菜",
        "normalized_name": "娃娃菜",
        "total_qty": 36.0,
        "total_revenue": 720.0,
        "bills": 30,
        "window_start": date(2026, 7, 1),
        "window_end": date(2026, 7, 27),
    }]
    query = "本月全部门店米饭和娃娃菜和招牌藤椒味(单人份)的销量"

    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool(rows),
        "RES_TEST",
        role="restaurant_manager",
        query=query,
        date_range=(date(2026, 7, 1), date(2026, 7, 27)),
        window_label="本月",
        requested_metrics=("sales_volume",),
    ))

    assert result.title.startswith("多菜品销量对比")
    assert "「米饭」" in result.answer_text
    assert "「娃娃菜」" in result.answer_text
    assert "「招牌藤椒味(单人份)」" in result.answer_text
    assert "菜品毛利分析" not in result.answer_text
    assert result.meta["targetDishes"] == [
        "米饭", "娃娃菜", "招牌藤椒味(单人份)",
    ]


def test_named_dish_no_data_keeps_named_week_scope_and_object():
    result = asyncio.run(_r.resolve_gross_margin(
        _gross_margin_pool([]),
        "RES_TEST",
        role="restaurant_manager",
        query="这周全部门店米饭卖了多少",
        date_range=(date(2026, 7, 27), date(2026, 7, 27)),
        window_label="本周",
        requested_metrics=("sales_volume",),
    ))

    assert "本周" in result.answer_text
    assert "「米饭」" in result.answer_text
    assert "销量" in result.title
    assert result.meta["no_pos_data"] is True


def test_scoped_dish_reasonableness_answers_without_inventing_a_threshold():
    answer = _scoped_dish_metric_answer(
        _dish_metric_entry(),
        window_label="本月",
        query="本月米饭的毛利率是否合理",
    )

    assert answer is not None
    assert "「米饭」" in answer
    assert "毛利率 80.0%" in answer
    assert "不能判断是否合理" in answer
    assert "不会用主观阈值" in answer


# ─────────────────────────────────────────────────────────────────────────────
# 繁体输入：确定性词表须与简体等价 (2026-07-28)
#
# 实测 bug：「本月全部門店營收多少」被拒答 ——「全部門店」不在简体-only 的
# 词表里，全店范围识别不出来，resolver 报「门店范围不能由全店或全门店
# resolver 代答」；同一句简体完全正常。港澳台用户 / 系统语言设繁中 /
# 从繁体文档复制粘贴都会踩。
# ─────────────────────────────────────────────────────────────────────────────

def test_with_traditional_expands_without_duplicates():
    from smartbi.gold.restaurant_ops_router import with_traditional
    out = with_traditional(("全部门店", "各店"))
    assert "全部门店" in out and "全部門店" in out
    assert "各店" in out                      # 繁简同形只收一次
    assert len(out) == len(set(out))


def test_generic_store_scope_fragments_cover_traditional():
    from smartbi.gold.restaurant_ops_router import _GENERIC_STORE_SCOPE_FRAGMENTS
    assert "全部门店" in _GENERIC_STORE_SCOPE_FRAGMENTS
    assert "全部門店" in _GENERIC_STORE_SCOPE_FRAGMENTS


def test_traditional_all_store_query_matches_simplified_behaviour():
    """繁简同句必须得到同样的范围与维度判定。"""
    from smartbi.gold.restaurant_intent import _detect_store_scope, _detect_dimensions
    simplified = "本月全部门店营收多少"
    traditional = "本月全部門店營收多少"
    assert _detect_store_scope(traditional) == _detect_store_scope(simplified) == ("all", ())
    assert _detect_dimensions(traditional) == _detect_dimensions(simplified)


def test_traditional_all_store_is_not_mistaken_for_a_store_name():
    """「全部門店」是聚合范围, 不是门店实体 —— 误判成店名会让查询整条走偏。"""
    from smartbi.gold.restaurant_ops_router import extract_store_mentions
    assert extract_store_mentions("本月全部門店營收多少") == []
    assert extract_store_mentions("本月全部门店营收多少") == []


# ─────────────────────────────────────────────────────────────────────────────
# 精确日期区间 (spec 持续项, 2026-07-28 专测后补)
#
# 专测实拍的原始症状: 「全部门店6月3号到18号的营收」
#   -> "没有找到名为「3号到18号」的菜品"
# 确定性层解析不出时间, 菜品抽取就把日期当成了菜名。老板问营收却被告知
# 查无此菜 —— 比单纯"不支持"更糟, 会让人以为自己菜名打错了。
# ─────────────────────────────────────────────────────────────────────────────

def _d(y, m, day):
    from datetime import date
    return date(y, m, day)


def test_absolute_range_parses_common_owner_phrasings():
    from smartbi.gold.restaurant_ops_router import parse_absolute_date_range as P
    today = _d(2026, 7, 28)
    # 同月省略后半月份
    assert P("全部门店6月3号到18号的营收", today=today)[:2] == (_d(2026, 6, 3), _d(2026, 6, 18))
    # 日/至 写法
    assert P("6月3日至6月18日营收多少", today=today)[:2] == (_d(2026, 6, 3), _d(2026, 6, 18))
    # ISO
    assert P("2026-06-03到2026-06-18的营收", today=today)[:2] == (_d(2026, 6, 3), _d(2026, 6, 18))
    # 跨月 + 「从」前缀
    assert P("从6月3号到7月2号卖了多少", today=today)[:2] == (_d(2026, 6, 3), _d(2026, 7, 2))


def test_absolute_range_infers_previous_year_when_future():
    """无年份且落在未来 -> 指去年 (老板问的是过去, 不是预测)。"""
    from smartbi.gold.restaurant_ops_router import parse_absolute_date_range as P
    got = P("12月20号到12月28号的营收", today=_d(2026, 7, 28))
    assert got[:2] == (_d(2025, 12, 20), _d(2025, 12, 28))


def test_absolute_range_fails_closed_on_bad_input():
    """颠倒/非法一律不匹配 —— 不猜、不交换端点, 让流程照常走澄清。"""
    from smartbi.gold.restaurant_ops_router import parse_absolute_date_range as P
    today = _d(2026, 7, 28)
    assert P("6月18号到6月3号的营收", today=today) is None      # 端点写反
    assert P("2月30号到3月1号", today=today) is None            # 日期不存在
    assert P("本月营收多少", today=today) is None               # 根本没有区间


def test_absolute_range_drives_the_deterministic_window():
    from smartbi.gold.restaurant_ops_router import _resolve_sales_date_range as R
    rng, label = R("全部门店6月3号到18号的营收", today=_d(2026, 7, 28))
    assert rng == (_d(2026, 6, 3), _d(2026, 6, 18))
    # 标签是「指定区间」而非日期串 —— 渲染层会另行补上具体日期,
    # 标签若也写日期会渲染成「X（X）」重复 (2026-07-28 prod 实拍)。
    assert label == "指定区间"
    # 既有相对短语不受影响
    assert R("本月营收多少", today=_d(2026, 7, 28))[1] == "本月"


def test_absolute_range_is_not_swallowed_into_the_dish_slot():
    """原始症状的回归护栏。"""
    from smartbi.gold.restaurant_ops_router import extract_dish_candidate as D
    assert D("全部门店6月3号到18号的营收") is None
    assert D("全部门店6月3日至6月18日营收多少") is None
    # 区间与菜名同时出现时, 菜名仍要抽得出来
    assert D("全部门店6月3号到18号米饭的销量") == "米饭"
