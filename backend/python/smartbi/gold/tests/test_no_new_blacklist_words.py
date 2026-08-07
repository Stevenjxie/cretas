"""⛔ 黑名单不许再长。

🔴 Steve 2026-08-07 拍板：**确定性层只能「认得」，不能「猜」。**
黑名单是「对无限世界的枚举」——「这些词不是菜名」永远列不完。今天它已经 83 个词(34+10+9+30, 实测)，
而我自己这一轮就往里加过两次（「加权」、一批食材泛指词）。

⛔ 这道闸不追求把存量清零（食材那批还没有对应的目录可查，见下），它只保证
   **不再增长**：任何人想再加一个词，先得回答「目录为什么没拦住？」

判据来源就在代码里（`extract_dish_candidate` 最后一道守卫的注释）：
> 最后一道: 菜单说了算。**黑名单只能拦住有人想到过的词, 目录能拦住全部。**

## 存量为什么还留着

| 词表 | 能否用目录替代 | 状态 |
|---|---|---|
| `_DISH_GENERIC_TOKENS` / `_KITCHEN_OPS_NOUNS` | ✅ 菜品目录已覆盖（见 `test_catalogue_over_blacklist`） | 仅作目录不可用时的兜底 |
| `_INGREDIENT_GENERIC_TOKENS` | ❌ **食材没有对应目录** —— 菜品目录管不了食材 | 退役被阻塞 |

⇒ 想清空第二行，先得有一张食材目录。**那是数据/建模工作，不是改词表。**
"""
import re
from pathlib import Path

from smartbi.gold.restaurant import restaurant_intent as RI
from smartbi.gold.restaurant import restaurant_ops_router as RR

#: 2026-08-07 冻结的基线。**只允许降，不允许升。**
#: 降了就把这里改小（并在 commit 里说清楚是靠什么替代的）。
FROZEN = {
    "_DISH_GENERIC_TOKENS": 34,
    "_KITCHEN_OPS_NOUNS": 10,
    "_INGREDIENT_GENERIC_TOKENS": 9,
}


def _size(name):
    for mod in (RR, RI):
        v = getattr(mod, name, None)
        if v is not None:
            return len(v)
    raise AssertionError(f"{name} 不见了或改名了 —— 这道闸已经量不到东西")


def test_wordlists_do_not_grow():
    grown = {
        name: (_size(name), frozen)
        for name, frozen in FROZEN.items()
        if _size(name) > frozen
    }
    assert not grown, (
        f"黑名单变长了: {grown}（当前 vs 冻结基线）。\n"
        "⛔ 加词之前先回答：**菜单/食材目录为什么没拦住它？**\n"
        "   如果目录能拦 → 去修目录的接线，不要加词。\n"
        "   如果那类实体压根没有目录 → 那是要建目录，也不是加词。\n"
        "   判据（代码原文）：黑名单只能拦住有人想到过的词，目录能拦住全部。"
    )


def test_inline_blacklist_in_extractor_does_not_grow():
    """抽取器里还内联着一串词（不在任何常量里）—— 一并冻住，否则会从这里溜进来。"""
    src = Path(RR.__file__).read_text(encoding="utf-8")
    m = re.search(r"if any\(tok in candidate for tok in \(\n(.*?)\)\s*\+\s*_KITCHEN_OPS_NOUNS",
                  src, re.S)
    assert m, "内联黑名单的写法变了 —— 这道闸已经空转，先修正则"
    inline_words = re.findall(r'"([^"]+)"', m.group(1))

    # ⚠️ 30 是**实测**冻结值。我先前凭 `grep | wc -l` 估成 19 —— 统计口径错了
    #    (那条命令只数了某几行)。这道闸第一次跑就把我的错数抓了出来,
    #    判据: **冻结基线要用被测代码算出来, 不要用另一条命令估。**
    assert len(inline_words) <= 30, (
        f"抽取器内联黑名单从 30 涨到了 {len(inline_words)}: {inline_words}。"
        "同上：先问目录为什么没拦住。"
    )


def test_the_catalogue_veto_is_still_wired():
    """阴性对照：上面两条只是「别变大」，这条保证**目录那条路还在**。

    否则有人把目录否决删掉、黑名单不变，上面两条照样绿 —— 那才是最坏的情况：
    看着守住了词表，实际上把真正管用的那道守卫拆了。
    """
    src = Path(RR.__file__).read_text(encoding="utf-8")
    assert "_catalogue_says_not_a_dish(candidate)" in src, (
        "抽取器里的「菜单说了算」守卫不见了 —— 那黑名单就重新变成唯一防线了"
    )
