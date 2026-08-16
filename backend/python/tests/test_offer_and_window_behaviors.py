"""B-3 开价后果表述 + B-0/B-4 时间窗 —— **CI 版**。

这几条原本只活在 `smartbi/scripts/b3_offer_consequence_gate.py` 和
`b0_b4_window_gate.py`（手工探针）。审计实测：破坏它们，
**CI 会跑的那部分一条都不红**。

▎闸不跑 = 没有闸。⇒ 搬进 `tests/`。
"""
import datetime

import pytest

from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps
from smartbi.gold.restaurant.restaurant_ops_router import (
    _resolve_sales_date_range,
    extract_dish_candidate,
)

D = datetime.date
TODAY = D(2026, 8, 16)

# ── B-3 开价必须说清「不做的后果」和「做的好处」──────────────────────
GAPS = [{"name": "罗氏虾", "revenue": 188800.0}]
COVERAGE, DENOM = 0.451, 1000000.0


@pytest.fixture(scope="module")
def offer_text():
    offers = offers_for_cost_gaps(GAPS, COVERAGE, DENOM)
    assert offers, "一条开价都没出 —— 下面全部无意义（阳性对照）"
    return str(offers[0]["text"])


def test_offer_states_the_consequence_in_money(offer_text):
    """后果用**金额**说，⛔ 不用百分比。

    「54.9%」是个比例；「¥549,000 算不出毛利」才是他店里的钱。
    客户原话：「不是特别直观…要告诉他如果不做的后果是什么」。
    """
    uncovered = (1 - COVERAGE) * DENOM
    assert f"¥{uncovered:,.0f}" in offer_text, offer_text
    assert "算不出毛利" in offer_text, offer_text


def test_offer_states_the_benefit_in_money(offer_text):
    gained = sum(g["revenue"] for g in GAPS)
    assert f"¥{gained:,.0f}" in offer_text and "就能算进来" in offer_text, offer_text


def test_offer_keeps_the_underlying_numbers(offer_text):
    """⛔ 底层那两个百分数**保留** —— 「不直观」最容易的误读是「那就别给数了」。"""
    assert f"{COVERAGE * 100:.1f}%" in offer_text, offer_text
    assert "提到约" in offer_text and "先补这" in offer_text, offer_text


def test_offer_text_stays_single_line(offer_text):
    """`text` 在正文里被当 `> ` 引用块塞进去 —— 多行会让第二行掉出引用块。"""
    assert "\n" not in offer_text, repr(offer_text)


def test_no_offer_when_coverage_is_already_full():
    """阳性对照：满覆盖时不开价。

    ⛔ 少了它，「后果那句话」在任何数据上都出现，就不区分好坏了。
    """
    assert offers_for_cost_gaps(GAPS, 1.0, DENOM) == []


# ── B-0 时间词不许被当成菜名 ──────────────────────────────────────
@pytest.mark.parametrize("query", [
    "上半年营业额怎么样", "下半年生意如何", "上季度营收", "本季度怎么样", "今年营业额",
])
def test_time_words_are_not_dish_names(query):
    assert extract_dish_candidate(query) is None, query


@pytest.mark.parametrize("query,expect", [
    ("罗氏虾卖得怎么样", "罗氏虾"),
    ("总汇三明治的毛利率", "总汇三明治"),      # 含通用词的真菜名，⛔ 不许误伤
    ("半年陈花雕的销量", "半年陈花雕"),        # 含时间词的真菜名，⛔ 不许被切
])
def test_real_dish_names_still_extract(query, expect):
    """阳性对照：抽取器**还活着**。

    ⛔ 少了它，上面那五条「返回 None」只说明它什么都抽不出来。
    """
    assert extract_dish_candidate(query) == expect, query


# ── B-4 今天到现在 ≠ 开业至今 ────────────────────────────────────
@pytest.mark.parametrize("query,expect_range,expect_label", [
    ("今天到现在的经营情况", (D(2026, 8, 16), D(2026, 8, 16)), "今天"),
    ("今天截至目前的情况", (D(2026, 8, 16), D(2026, 8, 16)), "今天"),
])
def test_today_so_far_is_today(query, expect_range, expect_label):
    """客户原话：「中午也会问一次…把今天到中午的目前的所有信息整理出来」。"""
    assert _resolve_sales_date_range(query, today=TODAY) == (expect_range, expect_label)


@pytest.mark.parametrize("query", [
    "到今天为止的营收", "截至今天的累计", "开业至今营收", "截至目前营收",
])
def test_real_cumulative_is_not_swallowed(query):
    """🔴 阴性对照：真·累计**不许**被吞掉。

    ⚠️ 判别是「今天」在「到」**之前**。「含今天就当今天」这个错法
       在上面那一档上**全绿**，只有这一档抓得住 —— 而它会把
       「到今天为止的营收」这类问句一起改坏。
    """
    got = _resolve_sales_date_range(query, today=TODAY)
    assert got == ((D(2000, 1, 1), TODAY), "截至目前"), (query, got)


@pytest.mark.parametrize("query,expect_range,expect_label", [
    ("今天营业额", (D(2026, 8, 16), D(2026, 8, 16)), "今天"),
    ("昨天营业额", (D(2026, 8, 15), D(2026, 8, 15)), "昨天"),
    ("上个季度营收", (D(2026, 4, 1), D(2026, 6, 30)), "上个季度"),
    ("上周营收", (D(2026, 8, 3), D(2026, 8, 9)), "上周"),
])
def test_other_windows_unchanged(query, expect_range, expect_label):
    """回归对照：别的窗口没被这两条改动带偏。"""
    assert _resolve_sales_date_range(query, today=TODAY) == (expect_range, expect_label)
