"""菜单目录说了算 —— 黑名单退役的前提与边界。

🔴 Steve 2026-08-07 拍板：**确定性层只能「认得」，不能「猜」。**
「加权不像菜名」是人在猜，编码成词表；「加权不在 dim_product 里」是可查证的事实。

代码里原本就写着同一句话（`extract_dish_candidate` 最后一道守卫的注释）：
> 最后一道: 菜单说了算。**黑名单只能拦住有人想到过的词, 目录能拦住全部。**

## prod 实测（MOCK_REST，2026-08-07）

    目录否决('加权')   = True      目录否决('罗氏虾') = False
    目录否决('环比')   = True      目录否决('鲈鱼')   = False
    目录否决('综合')   = True

7 个代表性问句，**有黑名单与无黑名单结果逐条相同** ——
⇒ **目录加载时，黑名单是冗余的。**

## ⛔ 但退役有前提

`_catalogue_says_not_a_dish` 在目录**不可用**时 fail-open（不否决）。
那一刻黑名单是唯一防线。所以本文件钉住的是：
  1. 目录在场 → 目录足够，黑名单可以不参与；
  2. 目录缺席 → 必须仍然拦得住，**否则不许退役黑名单**。
"""
import pytest

from smartbi.gold.restaurant import restaurant_ops_router as R

#: 真实菜名（MOCK_REST 目录里的），必须被认出来。
REAL_DISHES = ("罗氏虾", "鲈鱼")

#: 「像菜名但不是菜」的词。这些正是黑名单曾经逐个补进去的东西 ——
#: 本轮我自己就往里加过「加权」和一批食材泛指词。
NOT_DISHES = ("加权", "环比", "综合", "同比", "整体", "平均")


@pytest.fixture
def catalogue(monkeypatch):
    """装一个假目录（不连库）。真库验证见模块 docstring 的 prod 实测。"""
    def _install(names):
        token = R.set_dish_catalogue(set(names))
        return token

    tokens = []
    yield lambda names: tokens.append(_install(names))
    for t in reversed(tokens):
        R.reset_dish_catalogue(t)


def test_catalogue_vetoes_every_non_dish(catalogue):
    """目录在场时，「像菜名但不是菜」的词**全部**被否决 —— 不靠有人想到过它们。"""
    catalogue(REAL_DISHES)

    for word in NOT_DISHES:
        assert R._catalogue_says_not_a_dish(word) is True, (
            f"{word!r} 不在目录里却没被否决 —— 那这道守卫就没在工作"
        )


def test_catalogue_keeps_real_dishes(catalogue):
    """阴性对照：真菜名不许被否决。

    没有这条，上面那条可以靠「一律否决」作弊通过。
    """
    catalogue(REAL_DISHES)

    for dish in REAL_DISHES:
        assert R._catalogue_says_not_a_dish(dish) is False, f"{dish!r} 是真菜, 不该被否决"


def test_catalogue_absent_fails_open(catalogue):
    """⛔ 目录不可用时 fail-open（不否决）—— 这就是黑名单还不能全退的原因。

    判据：目录缺席的那一刻，黑名单是唯一防线。谁要退役黑名单，
    必须先证明目录在所有抽取路径上都在场。
    """
    catalogue(set())  # 空目录 = 不可用

    for word in NOT_DISHES:
        assert R._catalogue_says_not_a_dish(word) is False, (
            "目录不可用时不该否决 —— 否则新上架、还没进目录的菜会被误杀"
        )


def test_extraction_agrees_with_and_without_blacklist(catalogue, monkeypatch):
    """🔴 核心断言：目录在场时，**去掉黑名单不改变任何抽取结果**。

    这是「黑名单冗余」的可执行证据。将来有人想再往黑名单里加词时，
    这条会提醒他：先问问目录为什么没拦住。
    """
    catalogue(REAL_DISHES)
    queries = [
        "加权毛利率是多少",
        "环比毛利率是多少",
        "综合毛利率是多少",
        "罗氏虾的毛利率是多少",
        "鲈鱼卖了多少",
        "本月人力成本是多少",
    ]

    before = {q: R.extract_dish_candidate(q) for q in queries}

    monkeypatch.setattr(R, "_DISH_GENERIC_TOKENS", frozenset())
    monkeypatch.setattr(R, "_KITCHEN_OPS_NOUNS", tuple())
    after = {q: R.extract_dish_candidate(q) for q in queries}

    assert before == after, (
        f"去掉黑名单后抽取结果变了: "
        f"{ {q: (before[q], after[q]) for q in queries if before[q] != after[q]} } —— "
        "说明目录没能覆盖这些情况, 黑名单还不能退"
    )
