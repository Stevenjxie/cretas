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


# ── 2026-08-11 打不全的门店名 ────────────────────────────────────────────
#
# 🔴 prod 实测(在「本月米饭的销量是多少」答完之后输入):
#      「模拟·长宁龙之梦店店」多打字 -> ✅ 正确解析
#      「长宁龙梦店」打错字         -> ✅ 诚实说没找到 + 给出路
#      **「龙之梦」打不全           -> ❌「我识别到的问题对象与准备执行的分析范围
#                                       不一致…」**(内部黑话, 店长读不懂)
#      **「长宁」更短               -> ❌ 同上**
#
#    而「龙之梦」「长宁」在 MOCK_REST 都**唯一命中一家店**, 信息完全够。
#    `_canonicalize_store_mention` 的包含匹配 SQL 早就写好了 —— 缺的是这个片段
#    压根没被当成门店名交给匹配层。**打不全比打错字更糟, 而它是最常见的输入方式。**
#
# ⛔ 不为此引语义置信度: 在一条本来确定性的路上新增 LLM 调用是架构红线(08-07 为此
#    撤回过), 且置信度会随模型强弱漂。确定性子串匹配已经够用。
def test_a_unique_fragment_resolves_to_the_full_store_name():
    """打不全但唯一命中 —— 必须认出来, 并给出**规范全名**(下游要拿它去查)。"""
    got = scope_only_refinement("龙之梦", _STORES)
    assert got is not None, "唯一命中的片段没被认成收窄 —— 用户会拿到一句内部黑话"
    scope, names = got
    assert names == ("模拟·长宁龙之梦店",), f"没有归一到全名: {names}"


def test_an_even_shorter_unique_fragment_also_resolves():
    got = scope_only_refinement("长宁", _STORES)
    assert got is not None
    assert got[1] == ("模拟·长宁龙之梦店",)


def test_an_ambiguous_fragment_returns_every_candidate():
    """⛔ 歧义片段不许挑一个 —— 要把候选**都**带出来, 由上层给按钮。

    「唯一命中」与「多家命中」的区别只能由上层处置(确认 vs 候选), 这里的职责是
    如实报出匹配到几家。少报一家 = 把歧义压成确定, 而那正是本仓反复在拆的形状。
    """
    stores = ("模拟·宝山大场社区店", "模拟·普陀真如社区店", "模拟·长宁龙之梦店")
    got = scope_only_refinement("社区店", stores)
    assert got is not None
    assert got[1] == ("模拟·宝山大场社区店", "模拟·普陀真如社区店")


def test_a_typo_fragment_is_still_not_a_refinement():
    """⛔ 阴性对照: 打错字(不是任何门店名的子串)照旧不认。

    现在的处理已经是对的 ——「没有找到名为「长宁龙梦店」的门店」+ 给出路。
    片段匹配**不能**放松到模糊匹配, 否则「长宁龙梦店」会被硬塞给某家店,
    用户拿到一个看着像答案的错答案。
    """
    assert scope_only_refinement("长宁龙梦店", _STORES) is None


def test_a_fragment_plus_a_metric_is_still_a_new_question():
    """⛔ 承重不变: 片段匹配不许把「去掉范围词后什么都不剩」这条判据放松掉。"""
    assert scope_only_refinement("龙之梦的毛利率", _STORES) is None
