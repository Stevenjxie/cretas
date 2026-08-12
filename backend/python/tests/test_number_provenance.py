"""数字出处子集闸的断言 —— 重点是**证明它不是恒真式**。

上一轮实测过一个「自洽闸」左右来源相同、一次都红不了。这里每一条正向断言
都配一条能让它红的对照。
"""
import pytest

from smartbi.gold.restaurant.number_provenance import (
    audit_answer,
    distribution,
    extract_numbers,
    source_numbers,
)


def test_normalization_lives_inside_the_gate():
    """① 归一在闸里：带千分位的正文数字要能对上裸浮点的执行产出。

    ⛔ 反过来(要求调用方先洗)就变成「我把两边洗成一样再断言一样」。
    """
    out = audit_answer("全部营收 **¥22,405,943.00**", {"kpis": [{"v": 22405943.0}]})
    assert out["unsourced"] == 0
    assert out["source"] == 1


def test_it_actually_catches_a_fabricated_number():
    """阴性对照：编一个数进去，闸必须红。没有这条，上面那条不算数。"""
    out = audit_answer("全部营收 **¥22,405,943.00**", {"kpis": [{"v": 999.0}]})
    assert out["unsourced"] == 1
    assert out["verdicts"][0]["kind"] == "unsourced"


def test_derived_ratio_is_allowed_on_purpose():
    """② 派生数显式放行：毛利率不在源数据里，但由源数字算得出。"""
    # 毛利 200 / 营收 600 → 33.3333%
    out = audit_answer("毛利率 33.3333%", {"kpis": [{"gp": 200.0}, {"rev": 600.0}]})
    assert out["unsourced"] == 0
    assert out["derived"] == 1


def test_derived_is_one_layer_only():
    """②的边界：两层派生**不**放行 —— 否则任何数都'找得到出处'。

    600/200=3, 3*7=21 需要两层；源里没有 7，21 必须判红。
    """
    out = audit_answer("神秘数字 21", {"kpis": [{"gp": 200.0}, {"rev": 600.0}]})
    assert out["unsourced"] == 1


def test_allow_derived_false_would_redden_a_correct_answer():
    """把 ② 的决定翻过来会发生什么 —— 正确答案被判红。

    这条把「为什么必须显式决定」变成可执行的证据，而不是一句注释。
    """
    strict = audit_answer("毛利率 33.3333%", {"kpis": [{"gp": 200.0}, {"rev": 600.0}]},
                          allow_derived=False)
    assert strict["unsourced"] == 1, "严格模式必须把正确的派生数判红, 否则 ② 没有意义"


def test_structural_numbers_do_not_count():
    """「最近30天」的 30 不是查出来的数。⚠️ 这个豁免集合是闸最容易被撑大的地方。"""
    out = audit_answer("最近30天（共 2 个月）营收 500", {"kpis": [{"v": 500.0}]})
    assert out["structural"] == 2
    assert out["unsourced"] == 0


def test_extract_and_source_agree_on_shape():
    assert [v for _, v in extract_numbers("a 1,234.50 b -7")] == [
        pytest.approx(1234.5), pytest.approx(-7)]
    assert len(source_numbers({"rows": [{"a": 1}, {"b": "2.5"}], "kpis": [3]})) == 3


def test_distribution_flags_both_degenerate_ends():
    """④ 近 0% 和近 100% 都要被当成仪器问题报出来。"""
    all_ok = distribution([{"hit_rate": 1.0}, {"hit_rate": 1.0}])
    assert "归一/派生太松" in all_ok["warning"]

    none_ok = distribution([{"hit_rate": 0.0}, {"hit_rate": 0.0}])
    assert "执行产出没喂对" in none_ok["warning"]

    mixed = distribution([{"hit_rate": 1.0}, {"hit_rate": 0.5}, {"hit_rate": 0.0}])
    assert "warning" not in mixed
    assert mixed["median"] == pytest.approx(0.5)

    empty = distribution([{"hit_rate": None}])
    assert "仪器没接上" in empty["warning"]


def test_empty_execution_output_makes_everything_unsourced():
    """执行产出为空时不许假装通过 —— 那正是「我没喂对」的样子。"""
    out = audit_answer("营收 12345", {})
    assert out["hit_rate"] == 0.0
    assert out["source_pool"] == 0
