"""「X成本」整类语义误读的**清单测试** —— 让这一类可度量, 而不是逐个词打补丁。

## 这个文件在测什么

判断「用户问的是不是菜品」目前建立在两件事上, 两件都是**中文无词边界的子串
匹配 + 黑名单**:

* ``_detect_requested_metrics``: recipe_cost 规则含裸「成本」, 谁在前面都认领;
* ``_extract_dish_candidate_single``: 剥掉时间/门店/指标后**剩下的就当菜名**
  (残差式启发法, 预设了「这句话里一定有个菜」)。

黑名单要穷举「所有不是菜的名词」—— 那是无界集合。2026-07-30 修掉
损耗/领料/库存/浪费/报损 (见 ``_KITCHEN_OPS_NOUNS``) 只是堵住了当时撞见的
那几个词; 「本月人力成本是多少」这类**餐饮标准成本科目**仍然会被判成菜品
成本, 并回一句「没有找到名为『人力』的菜品」。

## 为什么不用仓库自带的 SAMPLE_QUERIES

试过, 62 条全绿 —— 那批话术就是当初用来调关键词的, 属幸存者偏差, 证明不了
任何事。这里改用**餐饮真实成本科目**造句: 它们不在任何精选集里, 正是雷区。

## 清单式断言

``_KNOWN_MISREAD`` 钉住**当前已知坏的集合**, 断言实际误读集合与它**完全相等**。
这样两个方向都被守住:

* 冒出**新的**误读 (例如有人往关键词表里加了个词) → 测试红;
* 修好一个 → 必须从清单里删掉一行, 进度可度量。

目录校验落地后 (候选词查 dim_product 命中才算菜名), 这个清单应当**清空**,
届时把 ``_KNOWN_MISREAD`` 改成空集合即可 —— 那才是这一类被根治的判据。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent import _detect_requested_metrics
from smartbi.gold.restaurant.restaurant_ops_router import (
    extract_dish_candidates,
    reset_dish_catalogue,
    set_dish_catalogue,
)


# 生产条件: 解析期间菜单目录是绑定的 (dish_catalogue_scope)。清单必须在这个
# 条件下度量, 否则量的是「目录不可用」那条兜底路径, 与用户实际经历的不符。
_MENU = frozenset({"米饭", "娃娃菜", "招牌藤椒味", "宫保鸡丁", "冰粉", "酸菜鱼"})


@pytest.fixture(autouse=True)
def _bind_menu():
    token = set_dish_catalogue(_MENU)
    try:
        yield
    finally:
        reset_dish_catalogue(token)


# 餐饮真实成本科目 / 金额口径 —— 老板会原样说出口的话。
_REAL_OWNER_COST_QUESTIONS = (
    "本月人力成本是多少",
    "本月人工成本是多少",
    "本月员工成本是多少",
    "本月房租成本是多少",
    "本月水电成本是多少",
    "本月能耗成本是多少",
    "本月包装成本是多少",
    "本月外卖成本是多少",
    "本月配送成本是多少",
    "本月原料成本是多少",
    "本月备货成本是多少",
    "本月仓储成本是多少",
    "本月午市成本是多少",
    "本月夜宵成本是多少",
    "本月下午茶成本是多少",
    "本月获客成本是多少",
    "本月营销成本是多少",
)

# 已知误读清单 —— **已清空**。
#
# 曾经这里列着上面 17 条的全部, 因为判断「是不是菜」靠的是黑名单。菜单目录
# 校验落地后 (见 test_restaurant_dish_catalogue_gate), 裁决权归菜单, 这一类
# 整体消失, 清单归零 —— 这就是当初写下的根治判据。
#
# 清单留在这里不是摆设: 它现在是**回归闸**。任何一条重新被误读都会红, 而且
# 报错会直接点出是哪句话、被误读成了什么。
_KNOWN_MISREAD = frozenset()


def _misreads(query: str) -> list:
    """这句话被误读的方式 (空 = 读对了)。"""
    problems = []
    if "recipe_cost" in _detect_requested_metrics(query):
        problems.append("误判菜品成本")
    dishes = extract_dish_candidates(query)
    if dishes:
        problems.append(f"误当菜名{dishes}")
    return problems


def test_owner_cost_question_misread_inventory_is_exactly_as_recorded():
    """实际误读集合必须与清单完全相等 —— 多了是新回归, 少了是修好了忘删。"""
    actual = {q for q in _REAL_OWNER_COST_QUESTIONS if _misreads(q)}

    newly_broken = actual - _KNOWN_MISREAD
    assert not newly_broken, (
        "新增语义误读 —— 有人扩了关键词表或改了菜名抽取:\n"
        + "\n".join(f"  {q}: {_misreads(q)}" for q in sorted(newly_broken))
    )
    fixed = _KNOWN_MISREAD - actual
    assert not fixed, (
        "这些已经修好了, 请从 _KNOWN_MISREAD 里删掉 (清单为空即整类根治):\n"
        + "\n".join(f"  {q}" for q in sorted(fixed))
    )


def test_inventory_only_lists_questions_we_actually_ask_about():
    """清单不得含已不在题面里的僵尸条目。"""
    assert _KNOWN_MISREAD <= set(_REAL_OWNER_COST_QUESTIONS)


@pytest.mark.parametrize("query", [
    # 2026-07-30 (#2005) 已修的那批 —— 不许回潮。
    "本月全部门店食材损耗成本是多少",
    "本月损耗成本是多少",
    "本月全部门店领料成本是多少",
    "本月全部门店库存成本是多少",
    "本月浪费成本是多少",
])
def test_kitchen_ops_nouns_stay_fixed(query):
    assert _misreads(query) == [], query


@pytest.mark.parametrize("query", [
    "本月米饭的成本是多少",
    "菜品成本排行",
    "本月食材成本是多少",
    "本月单品成本排名",
    "本月哪个菜的成本最高",
])
def test_real_dish_cost_questions_are_never_suppressed(query):
    """对照组: 根治这一类时最容易误伤的就是这些 —— 它们必须一直检出 recipe_cost。"""
    assert "recipe_cost" in _detect_requested_metrics(query), query
