"""An entity slot that contains an interrogative can never be a proper name.

Measured on prod 2026-07-30 (MOCK_REST, restaurant_manager), the two-turn flow
production actually runs (session_key + session_summary):

    turn1 「哪个菜最赚钱」        → 澄清: 你想看哪个时间范围？
    turn2 「最近30天」            → **没有找到名为「哪个菜最」的菜品**

`_verbatim_entity` already blocks hallucinated slots (must be a verbatim
substring) and carries a blacklist of generic placeholders — but 「哪个菜最」 is
not in that list, and extending the blacklist is the trap this codebase already
documented elsewhere: it requires enumerating "every noun that is not a dish",
an unbounded set.

Interrogatives are a bounded, closed set, so the rule is structural: a candidate
containing 哪/什么/多少/怎么/是否/有没有 is a question fragment, not a name. It
cannot be right to answer 「查无此菜」 for a phrase the user never used as a name.

⚠️ This is NOT the same as rejecting a genuinely unknown dish: 「红烧肉卖了多少」
where 红烧肉 is off-menu must still say 查无此菜. Those cases are pinned below.
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri


@pytest.mark.parametrize(
    "fragment, query",
    [
        # The exact prod repro (continuation concatenates both turns).
        ("哪个菜最", "哪个菜最赚钱 最近30天"),
        ("哪个菜", "哪个菜卖得最好"),
        ("哪些菜", "哪些菜毛利率最低"),
        ("哪道菜", "哪道菜成本最高"),
        ("什么菜", "什么菜卖得最好"),
        ("多少钱", "采购花了多少钱"),
        ("哪几家", "哪几家店毛利最好"),
    ],
)
def test_interrogative_fragments_are_never_entity_names(fragment, query):
    assert ri._verbatim_entity(fragment, query) is None, (
        f"{fragment!r} is a question fragment; accepting it makes the resolver "
        f"answer 「没有找到名为「{fragment}」的菜品」 for a phrase the user never "
        f"used as a name"
    )


@pytest.mark.parametrize(
    "dish, query",
    [
        ("米饭", "米饭卖了多少"),
        ("红烧肉", "红烧肉卖了多少"),
        ("水煮牛肉", "水煮牛肉最近30天卖了多少"),
        ("干锅花菜", "全部门店干锅花菜的毛利"),
        ("罗氏虾", "罗氏虾损耗多少"),
    ],
)
def test_real_dish_names_still_pass(dish, query):
    """The guard must not swallow real names — including an off-menu one, whose
    「查无此菜」 answer is correct and must keep working."""
    assert ri._verbatim_entity(dish, query) == dish


def test_hallucination_guard_still_applies():
    """Unchanged: a slot that is not verbatim in the question is still rejected."""
    assert ri._verbatim_entity("宫保鸡丁", "哪个菜最赚钱") is None


def test_existing_placeholder_blacklist_still_applies():
    assert ri._verbatim_entity("这道菜", "这道菜卖了多少") is None
    assert ri._verbatim_entity("哪家店", "哪家店毛利最好") is None


# ── the deterministic extractor, which is the path that actually fired ───────
# _verbatim_entity guards the LLM slot; 「哪个菜最」 came from the regex
# _DISH_PROFIT_RE in restaurant_ops_router instead, so the first fix did not
# reach it (prod re-test after deploy still reproduced). The catalogue gate
# below it cannot help either: dish_catalogue_scope wraps only
# parse_restaurant_query, while the resolver runs outside it and the gate is
# fail-open when the catalogue is not loaded.

from smartbi.gold.restaurant.restaurant_ops_router import extract_dish_candidate


@pytest.mark.parametrize(
    "query",
    [
        "哪个菜最赚钱",
        "哪道菜最赚钱",
        "哪些菜盈利",
        "什么菜最赚钱",
        "全部门店最近30天哪个菜最赚钱",
    ],
)
def test_deterministic_extractor_rejects_interrogatives(query):
    got = extract_dish_candidate(query)
    assert got is None, f"{query!r} → dish candidate {got!r}; that becomes 「查无此菜」"


@pytest.mark.parametrize(
    "query, dish",
    [
        ("米饭赚钱吗", "米饭"),
        ("水煮牛肉赚钱吗", "水煮牛肉"),
    ],
)
def test_deterministic_extractor_still_finds_real_dishes(query, dish):
    assert extract_dish_candidate(query) == dish
