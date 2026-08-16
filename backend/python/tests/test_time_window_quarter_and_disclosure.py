"""时间窗: ① 季度由代码算 · ② 静默换窗口必须披露 · ④ end 不许晚于今天。

## 本轮的成因（写在最前面，因为它决定了这些闸守什么）

用户问「上个季度」:
    → 模型吐 relative/month/n=3      ← 词表里表达不了「季度」，降级
    → 短语「最近3个月」
    → 代码算出 (2026-05-18, 2026-08-15)   ← **代码算得完全正确**
不是「算错日期」，是「输入给代码的那个词已经被换过了」，而且不反问、不披露。

⇒ ① 修的是**已经发现的那个词**；② 修的是**下一个还没发现的词**。②比①重要。

## ⛔ 不许顺手改掉的东西（③ 已裁定不做）

`上半年 / 下半年 → 全部历史` 是 `_resolve_sales_date_range` 注释里写明的
**诚实回退**（日历半年不是滚动窗口，不猜）。下面有一条回归闸钉住它，
防止做 ② 的时候顺手把它一起「修」了。
"""
from __future__ import annotations

import ast
import datetime
import inspect
import re

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant import restaurant_intent_service as ris
from smartbi.gold.restaurant.restaurant_ops_router import _resolve_sales_date_range

TODAY = datetime.date(2026, 8, 15)          # 周六; 本季度 = Q3(7-9)


# ── ① 季度：边界客观可验 ──────────────────────────────────────────────
@pytest.mark.parametrize("query,expected_range,expected_label", [
    ("上个季度营业额", (datetime.date(2026, 4, 1), datetime.date(2026, 6, 30)), "上个季度"),
    ("上季度营业额", (datetime.date(2026, 4, 1), datetime.date(2026, 6, 30)), "上个季度"),
    ("本季度营业额", (datetime.date(2026, 7, 1), TODAY), "本季度"),
    ("这个季度营业额", (datetime.date(2026, 7, 1), TODAY), "本季度"),
])
def test_quarter_is_resolved_by_code(query, expected_range, expected_label):
    got_range, got_label = _resolve_sales_date_range(query, today=TODAY)
    assert (got_range, got_label) == (expected_range, expected_label), (
        f"{query!r} -> {got_range} {got_label!r}")


def test_last_quarter_crosses_the_year_boundary():
    """跨年是季度最容易写错的一格 —— Q1 的上一季是**去年** Q4。"""
    got_range, got_label = _resolve_sales_date_range(
        "上个季度营业额", today=datetime.date(2026, 1, 20))
    assert got_range == (datetime.date(2025, 10, 1), datetime.date(2025, 12, 31))
    assert got_label == "上个季度"


@pytest.mark.parametrize("anchor,prev_start,prev_end,cur_start", [
    (datetime.date(2026, 2, 10), datetime.date(2025, 10, 1),
     datetime.date(2025, 12, 31), datetime.date(2026, 1, 1)),   # Q1: 上一季跨年
    (datetime.date(2026, 5, 10), datetime.date(2026, 1, 1),
     datetime.date(2026, 3, 31), datetime.date(2026, 4, 1)),    # Q2
    (datetime.date(2026, 8, 15), datetime.date(2026, 4, 1),
     datetime.date(2026, 6, 30), datetime.date(2026, 7, 1)),    # Q3
    (datetime.date(2026, 11, 3), datetime.date(2026, 7, 1),
     datetime.date(2026, 9, 30), datetime.date(2026, 10, 1)),   # Q4
])
def test_all_four_quarters(anchor, prev_start, prev_end, cur_start):
    """四个季度全走一遍 —— 季度算术最容易只在自己测的那一个季度上成立。

    ⚠️ 只钉「今天所在的那个季度」= 在一个恰好成立的样本上验收(本仓形态 B⁴)。
    """
    assert _resolve_sales_date_range("上个季度营业额", today=anchor)[0] == (prev_start, prev_end)
    assert _resolve_sales_date_range("本季度营业额", today=anchor)[0] == (cur_start, anchor)


def test_this_quarter_never_reaches_into_the_future():
    """进行中的周期右端点是今天, ⛔ 不是季度末 —— 否则窗口含未来日期。"""
    (start, end), _ = _resolve_sales_date_range("本季度营业额", today=TODAY)
    assert end == TODAY, f"本季度右端点 {end} != 今天 {TODAY}"
    assert start == datetime.date(2026, 7, 1)


def test_month_wins_when_both_periods_are_mentioned():
    """两个周期同时出现时主窗取近端 —— 与既有 R26 约定一致。"""
    _, label = _resolve_sales_date_range("上个季度和上个月对比", today=TODAY)
    assert label == "上个月", f"主窗应为近端周期, 实际 {label!r}"


# ── ① 载体闸：给模型的词表 与 解析表 是一对，两份必须一致 ───────────────
def test_named_vocabulary_and_parser_table_agree():
    """⛔ 形态 D: 两份必漂。多出来的键是死键, 少的键让模型静默降级。

    ⚠️ 判据取自**源码原文**, 不是我手抄的名单。
    """
    parser_src = inspect.getsource(ri._parse_t3_time_range)
    tree = ast.parse(parser_src.strip())
    # 解析表里的键 = _parse_t3_time_range 中那个 dict 字面量的 key
    parser_keys = {
        k.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Dict)
        for k in node.keys
        if isinstance(k, ast.Constant) and isinstance(k.value, str)
        and re.fullmatch(r"[a-z_]+", k.value)
    }
    prompt_src = inspect.getsource(ri._build_t3_prompt)
    # ⛔ 不要用 re.search 只取第一处: prompt 里 `named` 出现**多次**(词表 + 若干
    #    示例), 而第一处是只含 this_month 的示例。第一版就是这么写的, 当场读出
    #    「词表只有 this_month」这个假读数 —— 与本仓「搜索面太窄」是同一形状。
    occurrences = re.findall(r'"type":\s*"named",\s*"value":\s*([^}]+)\}', prompt_src)
    assert occurrences, "prompt 里找不到 named 取值清单 —— 先查闸本身(解析类的闸要 assert 总数>0)"
    prompt_keys = {k for occ in occurrences for k in re.findall(r'"([a-z_]+)"', occ)}

    assert prompt_keys, "prompt 词表解析出 0 个 —— 「一个都没找到」最像「一切正常」"
    for key in ("this_quarter", "last_quarter"):
        assert key in prompt_keys, f"{key} 没写进 prompt 词表, 模型永远不会吐它"
    missing_in_parser = prompt_keys - parser_keys
    assert not missing_in_parser, (
        f"prompt 让模型吐这些, 而 _parse_t3_time_range 不认 ⇒ 静默返回空短语: "
        f"{sorted(missing_in_parser)}")


@pytest.mark.parametrize("named,phrase", [
    ("last_quarter", "上个季度"), ("this_quarter", "本季度"),
    ("this_month", "本月"), ("today", "今天"), ("this_week", "本周"),
])
def test_named_time_range_round_trips_to_a_resolvable_phrase(named, phrase):
    """模型给的每个 named 值, 都必须能一路走到**解得出**的窗口。"""
    got = ri._parse_t3_time_range({"type": "named", "value": named})
    assert got == phrase
    _, label = _resolve_sales_date_range(got, today=TODAY)
    assert label != "全部历史", f"{named} -> {got!r} 解不出窗口"


def test_every_approved_direct_time_phrase_is_resolvable():
    """⛔ 形态 D 的另一半: `_APPROVED_DIRECT_TIME_PHRASES` 登记为「已批准的
    直答说法」, 却有一个(本季度)是 resolver 解不出的 —— 用户照它回答会落全部历史。
    """
    unresolvable = [
        p for p in ri._APPROVED_DIRECT_TIME_PHRASES
        if _resolve_sales_date_range(p, today=TODAY)[1] == "全部历史"
    ]
    assert ri._APPROVED_DIRECT_TIME_PHRASES, "清单是空的 —— 先查闸本身"
    assert not unresolvable, f"这些说法已批准给用户用, 但 resolver 解不出: {unresolvable}"


# ── ③ 上半年/下半年 = 日历半年（2026-08-16 推翻裁定 ③）─────────────────
@pytest.mark.parametrize("query,expected_range,expected_label", [
    ("上半年营业额", (datetime.date(2026, 1, 1), datetime.date(2026, 6, 30)), "上半年"),
    ("下半年营业额", (datetime.date(2026, 7, 1), TODAY), "下半年"),
])
def test_half_year_is_a_calendar_half(query, expected_range, expected_label):
    """🔴 这条**推翻了裁定 ③**（原文: 落「全部历史」是已经想清楚的取舍, ⛔ 不做）。

    推翻理由见 `docs/decisions/2026-08-16-上半年下半年-推翻裁定3.md`, 三条:
      ① 「回退」用词不对 —— 全部历史不是「我不知道」, 是**另一个具体答案**,
         而且不吭声。仓里自己的 T6②「静默换窗口必须披露」正好否定它。
      ② 「不在此猜测」的前提不成立 —— 日历半年是**定义**不是推断,
         真正要猜的「半年」(滚动 183 天)那一支本来就已经处理了。
      ③ 同模块里**季度已经按日历算**(上个季度 -> 4-1~6-30), 半年留成唯一例外
         就是同一个概念两种做法。

    ⚠️ 改断言**不是删断言**: 原来守的是「落全部历史」这个字面结果,
       现在守的是**性质** —— 窗口精确等于日历半年, 且右端点夹到今天。
       保护面比原来大(下面还有一条阴性对照)。
    """
    got_range, label = _resolve_sales_date_range(query, today=TODAY)
    assert (got_range, label) == (expected_range, expected_label)


def test_half_year_right_edge_never_reaches_into_the_future():
    """阴性对照: 下半年问在 8 月, ⛔ 答案不许给到 12-31（T6④ 的不变式）。"""
    (_, end), _ = _resolve_sales_date_range("下半年营业额", today=TODAY)
    assert end == TODAY, f"右端点 {end} 落在未来"


def test_rolling_half_year_is_untouched():
    """回归对照: 「半年」(不带上/下) 仍是**滚动 183 天**, ⛔ 不许顺手改成日历半年。"""
    (start, end), label = _resolve_sales_date_range("最近半年营业额", today=TODAY)
    assert label == "最近半年" and end == TODAY
    assert (end - start).days == 182, f"滚动半年的天数变了: {(end - start).days}"


# ── ④ end 不许晚于今天（作用域=历史查询）────────────────────────────────
def test_absolute_range_reaching_into_the_future_is_clamped_to_today():
    (start, end), label = _resolve_sales_date_range(
        "2026-08-01到2026-08-31的营业额", today=TODAY)
    assert start == datetime.date(2026, 8, 1)
    assert end == TODAY, f"右端点 {end} 落在未来, 没被截到今天"
    assert label == "指定区间"


@pytest.mark.parametrize("query", [
    "今天营业额", "昨天营业额", "前天营业额", "本周营业额", "上周营业额",
    "本月营业额", "上个月营业额", "本季度营业额", "上个季度营业额",
    "今年营业额", "去年营业额", "前年营业额", "最近30天营业额",
    "最近3周营业额", "最近2个月营业额", "最近半年营业额", "上上周营业额",
    "上上个月营业额", "2025年全年营收", "截至目前营业额",
    "2026-08-01到2026-08-31的营业额",
])
def test_no_resolved_window_ends_in_the_future(query):
    """全分支不变式。⛔ 预测类不走这个函数(排班预测那条路根本不调它),
    所以这条断言的作用域天然只在历史查询上 —— 例外不是靠记得, 是结构性的。
    """
    (start, end), label = _resolve_sales_date_range(query, today=TODAY)
    if end is None:
        return
    assert end <= TODAY, f"{query!r} -> {label!r} 的右端点 {end} 晚于今天"
    if start is not None:
        assert start <= end, f"{query!r} -> start {start} > end {end}"


def test_the_future_branch_is_still_reachable():
    """阳性对照: 未来词仍然走 `_FUTURE_WINDOW_LABEL`, ⛔ 没被 ④ 顺手吞掉。"""
    got_range, label = _resolve_sales_date_range("下周营业额", today=TODAY)
    assert got_range == (None, None)
    assert label == "未来时间"


# ── ② 静默换窗口必须披露 ──────────────────────────────────────────────
class _Spec:
    def __init__(self, **kw):
        self.window_from_llm_phrase = kw.get("window_from_llm_phrase", False)
        self.window_label = kw.get("window_label", "")
        self.date_range = kw.get("date_range", (None, None))
        self.store_scope_defaulted = False
        self.time_range_defaulted = kw.get("time_range_defaulted", False)
        self.store_options = ()
        self.resolver_query_seed = ""


def test_substituted_window_is_disclosed_with_the_actual_dates():
    text = ris._time_window_substitution_disclosure(_Spec(
        window_from_llm_phrase=True, window_label="最近3个月",
        date_range=(datetime.date(2026, 5, 18), TODAY)))
    assert text, "换了窗口却一个字都没说 —— 那正是本轮的缺陷"
    # 守**性质**不守字面: 必须说出实际用的窗口(标签 + 两个端点)
    assert "最近3个月" in text
    assert "2026-05-18" in text and "2026-08-15" in text


def test_window_the_user_actually_said_is_not_disclosed():
    """阴性对照: 用户原话解得出时不啰嗦, 否则每句都挂一段废话(形态 E)。"""
    assert ris._time_window_substitution_disclosure(_Spec(
        window_from_llm_phrase=False, window_label="本月",
        date_range=(datetime.date(2026, 8, 1), TODAY))) == ""


def test_no_disclosure_when_there_is_no_window_to_name():
    """⛔ 没有可说的窗口就别硬说 —— 那会变成另一种谎报。"""
    assert ris._time_window_substitution_disclosure(_Spec(
        window_from_llm_phrase=True, window_label="全部历史",
        date_range=(None, None))) == ""


def test_default_window_does_not_get_disclosed_twice():
    """`time_range_defaulted` 已有自己的披露, 两条一起会说两遍。"""
    src = inspect.getsource(ri._build_spec)
    assert "window_from_llm_phrase = not time_range_defaulted" in src, (
        "两种披露的互斥没有落在代码里 —— 用户会看到两段自相矛盾的时间说明")


def test_the_disclosure_is_actually_wired_into_the_answer():
    """🔴 形态 B: 机制在、没接上。单测直接调函数是看不见这一层的。"""
    src = inspect.getsource(ris)
    assert "_time_window_substitution_disclosure(spec)" in src, (
        "披露函数没有任何调用点 —— 它守不住任何东西")
