"""「这一句是不是在收窄上一问的门店范围」—— 必须确定性可判, 零 LLM。

## 为什么这条判据承重

多店租户首轮问「米饭的销量」会**直接答全部门店 + 显式声明范围**(PR #2368)。
已知残余代价写在 `_apply_store_scope_guard` 的注释里:

> 拿到全店答案后再说一个店名来收窄, 那是一个**新问句**, 要重走一次 T3。

而 goal 的 hard criterion 明写「在原本确定性的路径上新增 LLM 调用」是设计失败 ——
2026-08-07 那次就是因为这个代价把改动撤回的。

本判据就是消除它的那一步: 判出「这一句只是在换范围」→ 直接拼回上一问的密封问句,
走既有显式槽位编译, **不调 T3**。

## ⛔ 它必须比 pending 的拼接严格

`restaurant_pending_clarifications` 的消费端是**无条件**拼接
(`combined_query = original + " " + new`)。那对「我问了你、你在回答」成立,
对「我答了你、你下一句可能是任何东西」**不成立** —— 撤回记录写明: 给已答完的
问题登记 pending, 下一个不相关的新问题会被拼到旧问句后面。

所以判据是「**去掉范围词之后什么都不剩**」, 而不是「句子里出现了门店名」:
「模拟·长宁龙之梦店的毛利率」出现了门店名, 但它是**新问题**(自带指标), 不是收窄。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent import scope_only_refinement

_STORES = ("模拟·静安嘉里中心店", "模拟·长宁龙之梦店", "模拟·打浦桥日月光店")


@pytest.mark.parametrize("query,expected_scope,expected_names", [
    ("模拟·长宁龙之梦店", "single", ("模拟·长宁龙之梦店",)),
    ("看模拟·长宁龙之梦店", "single", ("模拟·长宁龙之梦店",)),
    ("模拟·长宁龙之梦店呢", "single", ("模拟·长宁龙之梦店",)),
    ("全部门店", "all", ()),
    ("所有门店", "all", ()),
])
def test_pure_scope_answers_are_recognized(query, expected_scope, expected_names):
    got = scope_only_refinement(query, _STORES)
    assert got is not None, f"{query!r} 是纯范围收窄, 没认出来"
    assert got == (expected_scope, expected_names)


@pytest.mark.parametrize("query", [
    "模拟·长宁龙之梦店的毛利率",       # 自带指标 = 新问题
    "模拟·长宁龙之梦店上个月卖了多少",  # 自带时间+指标 = 新问题
    "今天天气怎么样",                  # 完全无关
    "那成本呢",                        # 换指标, 不是换范围
    "本月",                            # 换时间, 不是换范围
    "",
])
def test_anything_beyond_a_scope_is_not_a_refinement(query):
    """🔴 承重: 多一个词就不算收窄。

    松掉这条就退化成 pending 那种无条件拼接 —— 下一个不相关的新问题会被拼到
    旧问句后面(2026-08-07 撤回记录写明的那个后果)。
    """
    assert scope_only_refinement(query, _STORES) is None, (
        f"{query!r} 被误判成收窄 —— 它会被拼到上一问后面")


def test_unknown_store_name_is_not_a_refinement():
    """⛔ 说了一个租户没有的店名 —— 不是收窄, 也不许默默当成全部门店。

    判据与 `_apply_store_scope_guard` 里那条一致: 没说门店时补「全部」是**补全**;
    说了「东城店」而租户没这家店时补「全部」是**换了个问题回答**。
    """
    assert scope_only_refinement("东城店", _STORES) is None


def test_multiple_known_stores_are_kept_in_order():
    got = scope_only_refinement("模拟·静安嘉里中心店 模拟·长宁龙之梦店", _STORES)
    assert got == ("multi", ("模拟·静安嘉里中心店", "模拟·长宁龙之梦店"))


def test_empty_store_catalogue_never_matches():
    """租户名单拿不到时(dim_store 不可用)一律不认 —— 拿一个验不了的名字去收窄,
    会安静地算出一个空口径。"""
    assert scope_only_refinement("模拟·长宁龙之梦店", ()) is None
