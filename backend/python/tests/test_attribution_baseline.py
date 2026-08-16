"""归因基线：每种窗口都挑得出**不重叠**的对照期。

## 承重墙

原来只有「前移 7 天」一条规则，窗口一长基线就与主窗口重叠（实测：
本月至今 9/16 天、上季度 84/91 天、上半年 174/181 天）。
重叠的「对比」两边几乎是同一批数据 —— 拆出来的主因是噪音假装成洞察。

⇒ **不重叠**是这道闸的第一断言，且它对**每一种窗口**都要成立。

## 阳性对照（硬约束 9）

「不重叠」是个容易伪造的读数：返回 `None` 也不重叠。
⇒ 必须同时断言**挑得出来**（不是 None），否则「一个都挑不出」会全绿。
"""
import datetime

import pytest

from smartbi.gold.restaurant.attribution_baseline import pick_baseline

D = datetime.date

#: (名字, 主窗口, 期望的基线, 期望的名字)
CASES = [
    ("单日", (D(2026, 8, 16), D(2026, 8, 16)),
     (D(2026, 8, 9), D(2026, 8, 9)), "上周同一天"),
    ("一周", (D(2026, 8, 10), D(2026, 8, 16)),
     (D(2026, 8, 3), D(2026, 8, 9)), "上一个 7 天"),
    # ⚠️ 月初至今要跟**上月同一批日号**比, ⛔ 不是上月整月 ——
    #    拿 16 天跟 31 天比, 营收当然低一半, 那不是经营变化。
    ("月初至今", (D(2026, 8, 1), D(2026, 8, 16)),
     (D(2026, 7, 1), D(2026, 7, 16)), "上月同期"),
    ("完整月", (D(2026, 7, 1), D(2026, 7, 31)),
     (D(2026, 6, 1), D(2026, 6, 30)), "上个月"),
    ("完整季度", (D(2026, 4, 1), D(2026, 6, 30)),
     (D(2026, 1, 1), D(2026, 3, 31)), "上一季度"),
    ("上半年", (D(2026, 1, 1), D(2026, 6, 30)),
     (D(2025, 7, 1), D(2025, 12, 31)), "上一个半年"),
    ("下半年(完整)", (D(2026, 7, 1), D(2026, 12, 31)),
     (D(2026, 1, 1), D(2026, 6, 30)), "上一个半年"),
    ("零散区间 10 天", (D(2026, 8, 5), D(2026, 8, 14)),
     (D(2026, 7, 26), D(2026, 8, 4)), "上一个 10 天"),
]


@pytest.mark.parametrize("label,window,expect_base,expect_name",
                         CASES, ids=[c[0] for c in CASES])
def test_baseline_is_what_we_ruled(label, window, expect_base, expect_name):
    base, name = pick_baseline(*window)
    # 阳性对照: 挑得出来。⛔ 少了它,「不重叠」在全返回 None 时也成立
    assert base is not None, f"[{label}] 挑不出基线: {name}"
    assert base == expect_base, f"[{label}] {base} != {expect_base}"
    assert name == expect_name, f"[{label}] {name!r} != {expect_name!r}"


@pytest.mark.parametrize("label,window", [(c[0], c[1]) for c in CASES],
                         ids=[c[0] for c in CASES])
def test_baseline_never_overlaps(label, window):
    """🔴 承重墙：一天都不许重叠。"""
    start, end = window
    base, name = pick_baseline(start, end)
    assert base is not None, f"[{label}] 挑不出基线: {name}"
    b_start, b_end = base
    assert b_end < start, (
        f"[{label}] 基线 {b_start}~{b_end} 与主窗口 {start}~{end} 重叠 "
        f"{(min(end, b_end) - max(start, b_start)).days + 1} 天")


@pytest.mark.parametrize("label,window", [(c[0], c[1]) for c in CASES],
                         ids=[c[0] for c in CASES])
def test_baseline_length_is_comparable(label, window):
    """基线长度要和主窗口**可比** —— 差太多的话「少了多少」没有意义。

    ⚠️ 允许日历带来的天然差异（2 月 28 天 vs 1 月 31 天、季度 90~92 天），
    ⛔ 但不允许「16 天 vs 31 天」这种成倍的差 —— 那正是「月初至今」
       如果拿上月整月做基线会犯的错。
    """
    start, end = window
    base, _ = pick_baseline(start, end)
    span = (end - start).days + 1
    b_span = (base[1] - base[0]).days + 1
    assert 0.8 <= b_span / span <= 1.25, (
        f"[{label}] 主窗口 {span} 天 vs 基线 {b_span} 天 —— 长度不可比")


def test_month_to_date_is_not_compared_against_a_full_month():
    """把上面那条最容易犯的错单独钉一次（它是**方向性**错误，不是精度问题）。"""
    base, name = pick_baseline(D(2026, 8, 1), D(2026, 8, 16))
    assert base == (D(2026, 7, 1), D(2026, 7, 16)), base
    assert (base[1] - base[0]).days == 15, "基线不是同样的 16 天"
    assert name == "上月同期"


def test_day_overflow_is_clamped_to_month_end():
    """3-31 的上月同期不是 2-31（不存在）—— 钳到月末。"""
    base, name = pick_baseline(D(2026, 3, 1), D(2026, 3, 31))
    # 3-1~3-31 是完整月 ⇒ 走「上个月」
    assert base == (D(2026, 2, 1), D(2026, 2, 28)), base
    assert name == "上个月"


def test_reversed_window_is_refused_not_guessed():
    """起止颠倒 ⇒ 明确返回 None，⛔ 不猜一个基线出来。"""
    base, why = pick_baseline(D(2026, 8, 16), D(2026, 8, 1))
    assert base is None and "颠倒" in why, (base, why)


def test_the_non_overlap_net_actually_catches_a_bad_rule(monkeypatch):
    """🔴 直接测那张**承重墙**本身。

    `pick_baseline` 出口前有一条「基线不许与主窗口重叠」的断言。它是**防未来
    规则出错的网** —— 当前六条规则都不会触发它，所以
    「去掉这条断言」这个变异**一条测试都不红**（实测 U2）。
    ⇒ 那不是「断言没用」，是**没有输入能让它开火**（形态 C″ 的第二种）。

    这条用例给它一个：把日历平移打坏，让某条规则吐出一个重叠的基线，
    断言 `pick_baseline` **拒绝返回它**（返回 None + 说明），⛔ 不是原样吐出去。
    """
    import smartbi.gold.restaurant.attribution_baseline as ab

    # 让「上个月」算出一个与主窗口重叠的区间（原地不动 = 完全重叠）
    monkeypatch.setattr(ab, "_shift_months", lambda d, months: d)

    base, why = ab.pick_baseline(D(2026, 7, 1), D(2026, 7, 31))
    assert base is None, f"重叠的基线被原样吐了出去: {base}"
    assert "重叠" in why, why
    # 阳性对照: 打坏之前它是能挑出来的 —— 否则这条断言在「函数恒返回 None」时也绿
    monkeypatch.undo()
    ok_base, _ = ab.pick_baseline(D(2026, 7, 1), D(2026, 7, 31))
    assert ok_base == (D(2026, 6, 1), D(2026, 6, 30)), ok_base
