"""菜单目录裁决「这句话里有没有菜」—— 整类语义误读的根治。

见 ``test_restaurant_noun_misread_inventory`` 的模块 docstring: 菜名抽取原本是
**残差式**的(剥掉时间/门店/指标后剩下的就当菜名), 靠黑名单拦「不是菜的名词」
要穷举一个无界集合。这里把裁决权交给租户真实菜单: 命中才算菜名 —— 黑名单翻转
成白名单, 而白名单是菜单本身。

安全属性(测试重点): **失败一律开放**。目录没加载 / 加载失败 / 菜单为空时,
行为必须与历史逐字一致 —— 收紧会把该租户的菜品问答整片判死, 比误读更糟。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent import _detect_requested_metrics
from smartbi.gold.restaurant import restaurant_ops_router as R
from smartbi.gold.restaurant.restaurant_ops_router import (
    extract_dish_candidate,
    extract_dish_candidates,
    reset_dish_catalogue,
    set_dish_catalogue,
)


# 一份像样的菜单: 含单字/多字、含「招牌藤椒味」这种用户常说半截的名字。
_MENU = frozenset({
    "米饭", "娃娃菜", "招牌藤椒味", "宫保鸡丁", "冰粉", "罗氏虾", "酸菜鱼",
})


@pytest.fixture
def menu():
    token = set_dish_catalogue(_MENU)
    try:
        yield _MENU
    finally:
        reset_dish_catalogue(token)


# ── 目录加载后: 整类误读消失 ────────────────────────────────────────

@pytest.mark.parametrize("query", [
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
])
def test_owner_cost_categories_are_no_longer_read_as_dishes(menu, query):
    """这 17 条正是清单测试里记录的全部已知误读。"""
    assert extract_dish_candidates(query) == [], query
    assert "recipe_cost" not in _detect_requested_metrics(query), query


def test_gate_covers_nouns_nobody_put_on_a_blocklist(menu):
    """根治的判据: 没人想到过的词也被拦住 —— 黑名单永远做不到这点。"""
    for query in (
        "本月停车位成本是多少",
        "本月消防整改成本是多少",
        "本月员工体检成本是多少",
        "本月小程序开发成本是多少",
    ):
        assert extract_dish_candidates(query) == [], query
        assert "recipe_cost" not in _detect_requested_metrics(query), query


# ── 对照组: 真菜品问法一个都不能丢 ──────────────────────────────────

@pytest.mark.parametrize("query,dish", [
    ("本月米饭的成本是多少", "米饭"),
    ("本月娃娃菜的毛利是多少", "娃娃菜"),
    ("本月招牌藤椒味的销量是多少", "招牌藤椒味"),
])
def test_menu_dishes_still_extracted(menu, query, dish):
    assert extract_dish_candidates(query) == [dish], query


def test_partial_dish_name_still_matches(menu):
    """用户常只说半个菜名; 目录匹配语义与 _match_dish_rows 一致, 不能更严。"""
    assert extract_dish_candidate("本月藤椒的销量是多少") == "藤椒"


@pytest.mark.parametrize("query", [
    "本月米饭的成本是多少",     # 真菜名
    "菜品成本排行",             # 完整词组
    "本月食材成本是多少",       # 完整词组
    "本月单品成本排名",         # 完整词组
    "本月哪个菜的成本最高",     # 无具体菜名, 靠「哪个菜」指示词
    "本月每道菜的成本是多少",
])
def test_real_dish_cost_questions_survive_the_gate(menu, query):
    assert "recipe_cost" in _detect_requested_metrics(query), query


# ── 安全属性: 失败一律开放 ──────────────────────────────────────────

def test_without_catalogue_behaviour_is_unchanged():
    """目录未加载 = 历史行为。所有既有同步测试都跑在这个模式下。"""
    assert extract_dish_candidates("本月人力成本是多少") == ["人力"]
    assert "recipe_cost" in _detect_requested_metrics("本月人力成本是多少")


def test_empty_catalogue_does_not_suppress_dishes():
    """菜单尚未同步的租户不能被判死 —— 空目录必须等同于未加载。"""
    token = set_dish_catalogue(frozenset())
    try:
        assert extract_dish_candidates("本月米饭的成本是多少") == ["米饭"]
        assert "recipe_cost" in _detect_requested_metrics("本月米饭的成本是多少")
    finally:
        reset_dish_catalogue(token)


@pytest.mark.asyncio
async def test_catalogue_load_failure_fails_open():
    """DB 抛错时返回 None(=不可用), 绝不能把问答挡掉。"""
    class _BoomPool:
        def acquire(self):
            raise RuntimeError("db down")

    R._DISH_CATALOGUE_CACHE.pop("F_BOOM", None)
    assert await R.load_dish_catalogue(_BoomPool(), "F_BOOM") is None


@pytest.mark.asyncio
async def test_catalogue_load_reads_names_and_aliases():
    class _Conn:
        async def execute(self, *a):
            return "SELECT 1"

        async def fetch(self, sql, *a):
            assert "dim_product" in sql and "dim_product_alias" in sql
            return [
                {"name": "米饭", "normalized_name": "米饭"},
                {"name": "招牌藤椒味", "normalized_name": "招牌藤椒味(单人份)"},
                {"name": None, "normalized_name": None},
            ]

    class _Pool:
        def acquire(self):
            class _Ctx:
                async def __aenter__(self):
                    return _Conn()

                async def __aexit__(self, *exc):
                    return False

            return _Ctx()

    R._DISH_CATALOGUE_CACHE.pop("F_OK", None)
    names = await R.load_dish_catalogue(_Pool(), "F_OK")
    assert names == frozenset({"米饭", "招牌藤椒味", "招牌藤椒味(单人份)"})
    R._DISH_CATALOGUE_CACHE.pop("F_OK", None)
