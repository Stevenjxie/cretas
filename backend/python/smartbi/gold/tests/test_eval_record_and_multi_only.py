"""电池 harness 的两个新能力：`--record` 落原文、`--only` 收多个片段。

两条都不是便利功能，各自堵一个实测过的坑：
  · 落 flat 而不是原文 → 排版判据永久恒真（08-11 那 8 张塌掉的表就是这么过的）
  · `--only` 只收一个片段 → 想跑覆盖多问法类的子集只能跑 N 次命令，
    而每次都要重付一遍 `_preflight_fixture`（那一步自己要打 7 次真实问答）
"""
import pytest

from smartbi.scripts.restaurant_ai_eval import (
    CASES,
    build_units,
    record_row,
    select_units,
)

_UNITS = build_units(CASES)


_RAW_TABLE_ANSWER = "**排行：**\n\n| # | 菜 |\n|---|---|\n| 1 | 米饭 |"


def test_record_keeps_raw_newlines_not_the_flattened_form():
    """🔴 承重: `record_row` 取的是 `message` 键，不是 `flat` 键。

    变异实测: 把 record_row 里的 `outcome.get("message")` 改成 `outcome.get("flat")`
      → 红: `AssertionError: 落盘的答案被压平了` —— 红在「换行没了」上。

    ⚠️ 这一条**只守 record_row 自己**。整条路径(HTTP→_run_case→record_row)
       由下面那条守 —— 分开写是因为 2026-08-12 变异实测发现：只有这一条时，
       把 `_run_case` 里的 `"message": message` 改成 `"message": flat`
       **它不会红**（本条根本没走 `_run_case`）。
       判据: 断言守的是它**真的调到**的那一段，不是它注释里说的那一段。
    """
    row = record_row(3, {"q": "排行"}, {
        "message": _RAW_TABLE_ANSWER, "flat": " ".join(_RAW_TABLE_ANSWER.split()),
        "problems": [], "table_problems": [], "followups": "", "elapsed": 1.0,
    })
    assert "\n" in row["message"], "落盘的答案被压平了，排版判据会永远绿"
    assert row["message"] == _RAW_TABLE_ANSWER


def test_run_case_hands_back_the_unflattened_answer(monkeypatch):
    """🔴 承重: **整条路径**上原文不被压平 —— 从 HTTP 响应一路到落盘的那一行。

    变异实测: 把 `_run_case` 里的 `"message": message` 改成 `"message": flat`
      → 红: `AssertionError: _run_case 把原文压平了` —— 红在「原文没了换行」上。
    """
    import smartbi.scripts.restaurant_ai_eval as ev

    def _fake_post(url, payload, headers=None, timeout=240):
        return {"data": {"message": _RAW_TABLE_ANSWER, "resultData": {}}}

    monkeypatch.setattr(ev, "_post_json", _fake_post)
    outcome = ev._run_case("http://x", {}, "sid", {"q": "排行"})
    assert "\n" in outcome["message"], (
        f"_run_case 把原文压平了: {outcome['message'][:60]!r}")
    row = record_row(1, {"q": "排行"}, outcome)
    assert row["message"] == _RAW_TABLE_ANSWER


def test_record_carries_which_assertion_went_red_not_just_a_boolean():
    """🔴 承重: 存的是断言的**结论文本**，不只是通过与否。

    四象限里「误报」那一格要逐条读「断言红在哪一句」才能判断它是不是挂在
    措辞上。只存一个布尔值就得回头重跑电池才知道 —— 而重跑电池正是要省的。
    """
    row = record_row(9, {"q": "毛利"}, {
        "message": "x", "flat": "x", "problems": ["缺少「毛利率」"],
        "table_problems": [], "followups": "", "elapsed": 1.0,
    })
    assert row["assertion_problems"] == ["缺少「毛利率」"]


def test_only_accepts_several_comma_separated_needles():
    """🔴 承重: 逗号分隔的多个片段，任一命中即选中。

    变异实测: 把 select_units 里的 split(",") 去掉（退回单片段）
      → 红: `AssertionError: 多片段没生效` —— 红在「只选中了一个片段的用例」上。
    """
    one = select_units(_UNITS, "哪个菜卖得好")
    two = select_units(_UNITS, "哪个菜卖得好,这月挣了多少")
    assert len(one) >= 1
    assert len(two) > len(one), "多片段没生效：两个片段选出的单元不比一个多"
    picked = {c["q"] for _chain, steps in two for _i, c in steps}
    assert any("哪个菜卖得好" in q for q in picked)
    assert any("这月挣了多少" in q for q in picked)


def test_multi_needle_still_pulls_in_the_whole_chain():
    """🔴 承重: 多片段不许破坏「链要整条跑」这条不变量。

    链是有状态的，单跑其中一步会话里缺前置轮次 —— 那样跑出来的答案是错的，
    而判定会把它当成被测系统的问题报上来（假盲区）。

    变异实测: 把 select_units 的返回改成只保留命中的那一步
      → 红: `AssertionError: 链 dish 被截断` —— 红在「链没整条跑」上。
    """
    chain_lengths = {c: len(steps) for c, steps in _UNITS if c}
    # 「那成本呢」只出现在 dish 链中间某一步
    picked = select_units(_UNITS, "那成本呢,本月全部门店哪些菜没人点")
    for chain, steps in picked:
        if chain:
            assert len(steps) == chain_lengths[chain], (
                f"链 {chain} 被截断：选中 {len(steps)} 步 / 实际 {chain_lengths[chain]} 步")
    assert any(c == "dish" for c, _ in picked), "命中链中间一步时整条 dish 链应被选中"


@pytest.mark.parametrize("only", ["", "   ", ",", " , "])
def test_blank_needles_mean_everything_not_nothing(only):
    """空/全是逗号 → 跑全量，**不是**一条都不跑。

    ⛔ 方向搞反的代价不对称：跑全量只是贵，一条不跑却会打印
       `== 0 passed, 0 failed ==` 然后 rc=0 —— 一次什么都没测的运行，
       读起来跟全绿一模一样。
    """
    assert len(select_units(_UNITS, only)) == len(_UNITS)
