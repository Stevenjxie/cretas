"""点名「需要复盘的门店」之前，先问一句：他去查会不会一无所获。

## 📏 缺陷（prod 实测，MOCK_REST，「哪家店成本最高」n=1285）

答案里说：

```
需要复盘的门店: **模拟·宝山大场社区店** 毛利率 67.7%,
先查低毛利菜品占比、套餐折扣和损耗领料是否偏高。
```

而同一份答案的表里，10 家店的毛利率是 **67.7% ~ 68.0%** —— **差 0.3 个百分点**。

⇒ 老板照它去查那一家，**必然一无所获**。

▎**反目标第一条**：一条误发的提示，烧掉的是「这东西说的话能信」。
▎上新提示之前问一句 —— **排在最前面的那个命中，他去查会不会一无所获。**

⚠️ 那句提示原来是**无条件**发的（`if len(ranked_store_list) > 1` 就发），
   而它点的永远是「毛利率最低的那家」—— 哪怕只低 0.3 个百分点。

## ⛔ 阈值和极差都不新写

- 阈值复用 `_STORE_SPREAD_WORTH_CHASING_PCT`（5%），它**印在正文里**是刻意的：
  藏起来的阈值没人能质疑。
- 极差复用 `_store_revenue_spread` —— 它算的是 `(hi-lo)/hi*100` 的**相对**极差，
  与量纲无关，所以喂毛利率同样成立。
"""
from __future__ import annotations

import ast
import inspect

from smartbi.gold.restaurant import restaurant_ops_router as rr


def _weak_store_block():
    """取 `weak_store_text` 的那段赋值子树。"""
    src = inspect.cleandoc(inspect.getsource(rr.resolve_store_margin))
    tree = ast.parse(src)
    nodes = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "weak_store_text"
                for t in n.targets)
    ]
    assert nodes, "找不到 weak_store_text 的赋值 —— 用例没打中"
    return tree, nodes


# ── 🔴 承重：那句提示必须挂在「差得够多」这个条件上 ────────────────────────

def test_the_callout_is_gated_on_the_spread():
    """⛔ 无条件点名 = 每次都发一条经不起查的提示。

    📏 MOCK_REST 上 10 家店毛利率差 **0.3 个百分点**，
       而缺陷版本照样点名让他去查。
    """
    tree, _ = _weak_store_block()
    names = {n.id for n in ast.walk(tree) if isinstance(n, ast.Name)}
    assert "_STORE_SPREAD_WORTH_CHASING_PCT" in names, (
        "那句提示没有挂在差距阈值上 —— 它会在 0.3 个百分点时也点名让他去查"
    )


def test_the_threshold_is_the_shared_one():
    """⛔ 阈值只此一份（形态 D：两份阈值一定会漂）。

    ⚠️ 漂的表现是「建议说值得查、正文说不值得」。
    """
    src = inspect.getsource(rr.resolve_store_margin)
    # ⛔ 不许出现写死的数字阈值
    for hardcoded in ("> 5.0", ">= 5.0", "< 5.0", "<= 5.0", "* 0.05"):
        assert hardcoded not in src, f"写死了一个阈值: {hardcoded}"


def test_the_spread_reuses_the_shared_helper():
    """⛔ 极差只此一处计算 —— 复用 `_store_revenue_spread`。

    ⚠️ 它算的是**相对**极差 `(hi-lo)/hi*100`，与量纲无关，
       所以喂毛利率同样成立（函数名带 revenue 只是它最初的用途）。
    """
    tree, _ = _weak_store_block()
    called = {
        c.func.id for c in ast.walk(tree)
        if isinstance(c, ast.Call) and isinstance(c.func, ast.Name)
    }
    assert "_store_revenue_spread" in called, (
        "又自己算了一遍极差 —— 两份一定会漂"
    )


# ── 🔴 两侧都要有话说（⛔ 不是「差得小就闭嘴」）────────────────────────────

def test_it_says_something_when_the_spread_is_small():
    """差得少的时候要说「别按门店查」—— ⛔ 不是什么都不说。

    ▎那句话本身就是「一件他没想到的事」：他问「哪家店成本最高」，
    ▎产品告诉他**这个问题问错了方向**。
    """
    src = inspect.getsource(rr.resolve_store_margin)
    assert "按门店查多半找不到东西" in src, "差距小的时候什么都没说"
    assert "差异不在门店之间" in src, "没给出那个直接推论"


def test_it_does_not_guess_where_the_difference_is():
    """⛔ 说「不在门店之间」可以，⛔ 顺嘴指向菜品就是编。

    ⚠️ 「差异更可能在菜品结构上」是**推测**，没有依据 ——
       上新提示之前问一句：他去查会不会一无所获。
    """
    src = inspect.getsource(rr.resolve_store_margin)
    for guess in ("更可能在菜品", "多半是菜品", "问题出在菜品结构"):
        assert guess not in src, f"顺嘴猜了一个没有依据的方向: {guess}"


def test_the_big_spread_branch_still_names_the_store():
    """阳性对照：差得多的时候**照样**点名 —— ⛔ 不是把提示整个关掉。

    ⚠️ 形态 E 的反面：收窄一条提示时只说「减掉什么」是半条裁定，
       必须同时说清**保留什么**。
    """
    src = inspect.getsource(rr.resolve_store_margin)
    assert "需要复盘的门店" in src, "把提示整个关掉了 —— 差得多的时候也不点名了"
    assert "先查低毛利菜品占比" in src, "差得多时的排查方向被顺手删了"


def test_both_branches_print_the_spread():
    """🔴 两侧都要把差距**印在正文里** —— 藏起来的阈值没人能质疑。"""
    src = inspect.getsource(rr.resolve_store_margin)
    assert src.count("个百分点") >= 2, (
        "只有一侧印了差距 —— 另一侧的判断读的人无法反驳"
    )
