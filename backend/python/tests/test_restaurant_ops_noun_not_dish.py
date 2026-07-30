"""后厨域名词不是菜名, 也不是菜品成本 —— 「损耗成本」类问句的两层误读。

实拍 (2026-07-30, prod MOCK_REST 端到端):
「本月全部门店食材损耗成本是多少」除了正确的损耗分析外, 还多出一段

    菜品毛利
    **没有找到名为「食材损耗」的菜品**, 不能给出该菜的销量或毛利...

追根因追到同一类误读的两层:

1. ``_detect_requested_metrics``: ``recipe_cost`` 规则含**裸「成本」**,
   于是「食材损耗成本」被判成「菜品成本」需求 → ``requested_metrics`` 变成
   ``('recipe_cost', 'wastage')``。这个假需求既往 ``planned_intents`` 里塞了
   第二个菜品类意图(那段噪音), 又给答案契约加了 recipe_cost 覆盖要求。
   这里的「成本」修饰的是「损耗」, 不是菜品。

2. ``_extract_dish_candidate_single``: 拒绝表里全是**指标词**(成本/毛利/销量)
   和**通用菜品词**(菜/菜品/单品), 没有**别的域的名词**。「成本」已被正则当
   指标后缀吃掉, 剩下的「食材损耗」不含任何拒绝词 → 当成菜名返回。
   ``_plan_requested_intents`` 的 recipe_cost 分支正是按
   ``extract_dish_candidate(text)`` 分叉的, 拿到假菜名就选了 GROSS_MARGIN
   并把它限域到一个不存在的菜 —— 这就是那段「没有找到名为…的菜品」。

不是单个词的问题: 损耗/领料/库存/浪费/报损 同属一类。
「本月领料成本是多少」当时会被判成 ``('recipe_cost',)``, 拿**菜品成本排行**
回答领料成本问题 —— 同类里更严重的一个, 一并钉住。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent import _detect_requested_metrics
from smartbi.gold.restaurant.restaurant_ops_router import (
    extract_dish_candidate,
    extract_dish_candidates,
)


# ── 第 1 层: 后厨域的「成本」不是菜品成本 ──────────────────────────────

@pytest.mark.parametrize("query", [
    "本月全部门店食材损耗成本是多少",
    "本月损耗成本是多少",
    "本月全部门店领料成本是多少",
    "上个月报损成本是多少",
    "本月浪费成本是多少",
])
def test_kitchen_ops_cost_is_not_recipe_cost(query):
    """「<后厨域名词>成本」问的是那个域的钱, 不是菜品成本。"""
    assert "recipe_cost" not in _detect_requested_metrics(query), query


def test_wastage_cost_question_keeps_only_the_wastage_requirement():
    """假 recipe_cost 消失后, 损耗需求必须还在 —— 别把整条需求一起误杀。"""
    assert _detect_requested_metrics("本月全部门店食材损耗成本是多少") == ("wastage",)


@pytest.mark.parametrize("query,expected", [
    # 真·菜品成本的问法必须继续检出 recipe_cost。
    ("本月米饭的成本是多少", "recipe_cost"),
    ("菜品成本排行", "recipe_cost"),
    ("本月哪个菜的食材成本最高", "recipe_cost"),
    ("本月单品成本排名", "recipe_cost"),
    ("本月配方成本怎么算", "recipe_cost"),
])
def test_real_dish_cost_questions_still_detect_recipe_cost(query, expected):
    assert expected in _detect_requested_metrics(query), query


def test_calendar_period_guard_still_holds():
    """既有守卫不能被新守卫顶掉: 「生成本月…」里的「成本」是断词假阳性。"""
    assert "recipe_cost" not in _detect_requested_metrics("生成本月营收报表")


# ── 第 2 层: 后厨域名词不是菜名 ────────────────────────────────────────

@pytest.mark.parametrize("query", [
    "本月全部门店食材损耗成本是多少",
    "本月损耗成本是多少",
    "本月全部门店领料成本是多少",
    "本月全部门店库存成本是多少",
    "本月浪费成本是多少",
])
def test_kitchen_ops_nouns_are_not_dish_candidates(query):
    """假菜名会让 recipe_cost 分支选中菜品毛利并限域到不存在的菜。"""
    assert extract_dish_candidates(query) == [], query
    assert extract_dish_candidate(query) is None, query


@pytest.mark.parametrize("query,dish", [
    ("本月米饭的成本是多少", "米饭"),
    ("本月招牌藤椒味的销量是多少", "招牌藤椒味"),
    ("本月全部门店娃娃菜的毛利是多少", "娃娃菜"),
])
def test_real_dish_names_still_extracted(query, dish):
    """对照组: 真菜名必须照旧被识别, 否则限域问答整片失效。"""
    assert extract_dish_candidates(query) == [dish], query
