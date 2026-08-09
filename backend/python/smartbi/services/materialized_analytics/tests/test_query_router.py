"""test_query_router.py"""
from smartbi.services.materialized_analytics.query_router import (
    match_template, format_cached_as_sse,
)


def test_dish_sales_query():
    """菜品销量排行 → `top_n_by_dim`。

    ⚠️ 本用例原本断言 `dish_sales_top_n`, 而那个模板**从来没有被物化过**
    (query_router.py 里记着: "template never materialized — latent registry
    bug")。路由命中它等于路由到一个不存在的东西, qhj-01「卖得最好的菜 Top 10」
    就是这么失败的。2026-04-26 修成路由到真实存在的 `top_n_by_dim`, 但这条
    用例没跟上, 于是它一直**钉着那个 bug**, 并被挂进 ci-gate-excludes.txt。

    判据: **测试红了先问「它断言的那个东西存在吗」** —— 断言一个不存在的模板名,
    比断言错值更难看出来, 因为字符串本身看着很合理。
    """
    assert match_template("哪些菜品一个月的销量") == "top_n_by_dim"
    assert match_template("商品销量最高的是什么") == "top_n_by_dim"
    assert match_template("菜品销售 Top") == "top_n_by_dim"


def test_dish_slow_query():
    # "滞销" keyword triggers the more specific template
    assert match_template("哪些菜品卖得不好") == "dish_slow_movers"
    assert match_template("滞销菜品有哪些") == "dish_slow_movers"


def test_time_slot_query():
    assert match_template("上午和下午每个区域的营业额") == "time_slot_revenue"


def test_member_query():
    assert match_template("会员每月消费金额和频次") == "member_consumption"


def test_refund_query():
    assert match_template("哪些菜品退菜次数多") == "refund_analysis"


def test_category_query():
    assert match_template("某一分类的销量") == "dish_category_breakdown"


def test_table_type_query():
    # Pure table-type comparison (no dish/点单 keyword) routes to table_type_comparison.
    # When the query ALSO mentions 菜品/点单/点菜/偏好 the more specific
    # dish_by_table_type wins (Apr 24 2026 W4 routing per query_router.py:44 comment).
    assert match_template("包厢和大厅客单价对比") == "table_type_comparison"
    assert match_template("包厢跟大厅客人的点单数量") == "dish_by_table_type"


def test_weekday_weekend_query():
    assert match_template("工作日和周末的营业额对比") == "weekday_weekend_pattern"


def test_generic_trend_query():
    # period_comparison_trend was added in W4 (Apr 22 2026) and is intentionally
    # more specific — it claims any query carrying 月度/同比/环比 OR 趋势 keyword.
    # monthly_trend is the broader fallback for 走势/变化 phrasing combined with a
    # period dim (日/周/时间) but without 趋势/月度 — those are now period_comparison_trend.
    assert match_template("营业额的月度走势如何") == "period_comparison_trend"
    assert match_template("每日销售走势") == "monthly_trend"


def test_store_performance_routing_fork():
    # 措施①-bug1 (May 31 2026): single-store / drill-down performance queries
    # must route to store_performance (not fall through to top_n_by_dim).
    # Before the fix "哪个门店营收最高" hit top_n_by_dim while its synonym
    # "哪家店业绩最好" hit store_performance via pgvector — same intent, split.
    assert match_template("哪个门店营收最高") == "store_performance"
    assert match_template("哪家店业绩最好") == "store_performance"
    assert match_template("单店表现如何") == "store_performance"
    assert match_template("哪家店卖得最多") == "store_performance"
    assert match_template("哪家店客单价最高") == "store_performance"
    # NOTE: "这家店..." carries the "这家" hard modifier (pronominal reference),
    # so it intentionally returns None → LLM fallback with conversation context.
    # That is NOT a store_performance routing case.


def test_store_performance_does_not_steal_generic_comparison():
    # Risk#1 mitigation: generic multi-store comparison / channel queries lack
    # the single-store drill-down marker → must NOT route to store_performance.
    assert match_template("包厢和大厅客单价对比") == "table_type_comparison"
    # Generic "门店对比" (no 哪家/单店 marker) must not hit store_performance.
    assert match_template("门店对比") != "store_performance"
    assert match_template("各门店销售情况") != "store_performance"


def test_no_match_returns_none():
    assert match_template("今天天气怎么样") is None
    assert match_template("") is None


def test_format_cached_as_sse_builds_answer():
    tpl = {
        "code": "dish_sales_top_n",
        "title": "菜品销量 Top 20",
        "insight_text": "招牌青花椒鱼销量 Top 1,卖出 100 份。",
        "chart_config": {"type": "bar", "series": []},
        "kpis": {"top_dish": "招牌青花椒鱼", "top_qty": 100, "top_revenue": 5800.50},
    }
    payload = format_cached_as_sse(tpl, "菜品销量")
    assert "菜品销量 Top 20" in payload["answer"]
    assert "招牌青花椒鱼" in payload["answer"]
    assert payload["charts"][0]["type"] == "bar"
    assert payload["template_code"] == "dish_sales_top_n"
    assert payload["source"] == "materialized_cache"


def test_phrase_shortcut_deadend_fix():
    # 死胡同修复 (May 31 2026): short colloquial phrases that miss the keyword
    # _PATTERNS (which require a keyword from EVERY group) must still pin to
    # their materialized template via the phrase-shortcut fallback, instead of
    # returning None → false "暂无可分析的数据" dead-end. These 4 templates ARE
    # materialized (time_slot_revenue / refund_analysis / business_overview /
    # store_customer_stratification) per the content-quality audit.
    assert match_template("时段营收") == "time_slot_revenue"
    assert match_template("营业时段") == "time_slot_revenue"
    assert match_template("退款") == "refund_analysis"
    assert match_template("退单") == "refund_analysis"
    assert match_template("翻台") == "business_overview_summary"
    assert match_template("翻台率") == "business_overview_summary"
    assert match_template("客户分层") == "store_customer_stratification"
    assert match_template("会员分层") == "store_customer_stratification"


def test_phrase_shortcut_does_not_override_keyword():
    # The phrase shortcut is a LAST-resort fallback — it must never override an
    # existing keyword/pattern match. "工作日和周末的营业额对比" already routes
    # to weekday_weekend_pattern via _PATTERNS and must stay there even though
    # it contains no shortcut phrase.
    assert match_template("工作日和周末的营业额对比") == "weekday_weekend_pattern"
    # Unrelated / non-restaurant queries still return None (no shortcut hit).
    assert match_template("今天天气怎么样") is None
    assert match_template("你好") is None
    assert match_template("") is None


def test_phrase_shortcut_function_direct():
    # Direct unit test of the matcher: substring containment, first-match wins.
    from smartbi.services.materialized_analytics.query_router import (
        match_phrase_shortcut,
    )
    assert match_phrase_shortcut("看一下翻台率") == "business_overview_summary"
    assert match_phrase_shortcut("各时段营收情况") == "time_slot_revenue"
    assert match_phrase_shortcut("无关内容") is None
    assert match_phrase_shortcut("") is None
    assert match_phrase_shortcut(None) is None


def test_phrase_shortcut_metric_beats_time_dimension():
    """2026-05-31 audit fix: a combined query containing BOTH a metric phrase
    and the broad 时段/各时段 modifier must route to the METRIC, not time_slot.

    Pre-fix bug: 各时段 (time_slot_revenue) was listed before 翻台率
    (business_overview_summary) + first-match-wins → "各时段的翻台率" wrongly
    returned time_slot_revenue (revenue-by-slot) instead of the turnover-rate
    template. Metric groups now precede the time-slot group.
    """
    from smartbi.services.materialized_analytics.query_router import (
        match_phrase_shortcut,
    )
    # METRIC must win over the time/dimension modifier
    assert match_phrase_shortcut("各时段的翻台率") == "business_overview_summary"
    assert match_phrase_shortcut("各时段翻台情况") == "business_overview_summary"
    assert match_phrase_shortcut("分时段退款") == "refund_analysis"
    assert match_phrase_shortcut("各时段客户分层") == "store_customer_stratification"
    # Standalone time-slot queries (no metric phrase) STILL route to time_slot
    assert match_phrase_shortcut("各时段营收") == "time_slot_revenue"
    assert match_phrase_shortcut("时段营收") == "time_slot_revenue"
    assert match_phrase_shortcut("营业时段") == "time_slot_revenue"
    # Standalone metric queries unaffected
    assert match_phrase_shortcut("翻台率") == "business_overview_summary"
    assert match_phrase_shortcut("退款") == "refund_analysis"


def test_format_cached_no_chart_config():
    tpl = {
        "code": "anomaly_detection", "title": "异常值检测",
        "insight_text": "发现 0 条异常。",
        "chart_config": None, "kpis": {"outlier_count": 0},
    }
    payload = format_cached_as_sse(tpl, "异常")
    assert payload["charts"] == []
    assert "异常值检测" in payload["answer"]
