"""指标限定词不该被抽成菜名。

🔴 2026-08-07 prod 实测: 「最近30天加权毛利率是多少」被答成
   「**没有找到名为「加权」的菜品**，不能给出该菜的销量或毛利」——
   抽取器把「加权毛利率」的前缀当成了菜名, 于是一个全店指标问句被打成「查无此菜」。

判据: 这类词是**指标的限定语**, 不可能是菜。排除是**整名相等**比较, 所以含这些字
的真菜名(总汇三明治…)不受影响 —— 下面有阴性对照钉住这一点。
"""
import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    extract_dish_candidate,
    extract_dish_candidates,
)


@pytest.mark.parametrize("query", [
    "最近30天加权毛利率是多少",
    "加权毛利率是多少",
    "本月平均客单价多少",
    "人均消费是多少",
    "全店毛利率怎么样",
    "整店营收多少",
    "综合毛利率是多少",
])
def test_metric_qualifier_is_not_a_dish(query):
    """🔴 指标限定词不得被当成菜名 —— 否则整句被打成「查无此菜」。"""
    assert extract_dish_candidate(query) is None, query
    assert extract_dish_candidates(query) == [], query


@pytest.mark.parametrize("query,dish", [
    ("总汇三明治的毛利率是多少", "总汇三明治"),
    ("罗氏虾的销量是多少", "罗氏虾"),
    ("米饭的毛利率是多少", "米饭"),
])
def test_real_dishes_still_extracted(query, dish):
    """阴性对照: 排除是**整名相等**, 含「总」「平均」等字的真菜名不受影响。

    没有这条, 上面那批词只要有人改成子串匹配就会静默吃掉真菜名, 而症状是
    「问某道菜却拿到全店榜」—— 比查无此菜更难发现。
    """
    got = extract_dish_candidate(query)
    assert got == dish, f"{query} -> {got!r}"
