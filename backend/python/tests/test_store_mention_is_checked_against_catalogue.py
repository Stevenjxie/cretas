"""归一 ≠ 核对 —— 消解不到的提及，⛔ 不许当门店名去拒答。

## 🔴 缺陷（2026-08-18 prod 实测，MOCK_REST，3 轮零抖动）

```
「有几家店是亏钱的」
    store_slot='有几家店'   store_scope='all'   dimensions=()
    → 「你问的是某家门店，我这次挑到的却是全店合计，所以这次我没敢算。」

「有没有店是亏钱的」（对照组，3/3 答上）
    → 「最近30天可计算毛利的 10 家门店中，**没有毛利为负的门店**」
```

▎**产品完全答得出这个问题**，是它自己造的槽位把它拦了。
▎拒答理由念的正是 `store_slot`（说「某家门店」）与 `store_scope='all'`
▎（说「全店合计」）的矛盾 —— 而那个矛盾来自一个**根本不是门店名**的值。

## 成因：fallthrough

`_resolve_store_mentions` 消解不到时的分支是「**走原路**」，
而走原路就是**保留正则抽出来的原文**：

```
门店简称对不上, 走原路: '有几家店' (…)      ← 消解器知道它对不上
                                            ← 而这个结论被丢掉了
```

▎形态 A¹⁰：兜底把「我不知道这是不是门店」翻译成「它就是门店」。
▎owner 的原话：**「归一 ≠ 核对」** —— 归一是「简称 → 全名」，
▎而「有几家店」压根不是店名，归一必然失败；**失败之后走哪条路**才是缺陷。

## ⛔ 修法不新写判据

复用 `_resolve_store_mentions` 用的同一个 `resolve_mention`
（它**不查库** —— 候选清单就是传进去的 `spec.store_options`）。
⛔ 不新写一份「像不像门店名」的判据（形态 D）。

## ⚠️ fail-open 是既有纪律

目录为空 / 消解器抛异常 ⇒ **一律放行**。
▎判据坏了要退回「不拦」，⛔ 不能退回「拦住」——
▎后者会把一个能答的问题变成拒答，而那正是本次要修的病。
"""
from __future__ import annotations

import ast
import inspect
import types

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc

#: prod `dim_store` 实读（MOCK_REST，10 家）。⛔ 不用我自己编的名字。
REAL = (
    "模拟·打浦桥日月光店",
    "模拟·徐汇美罗城店",
    "模拟·静安嘉里中心店",
    "模拟·陆家嘴正大店",
    "模拟·长宁龙之梦店",
)


async def _keep(mentions, options=REAL):
    return await svc._mentions_backed_by_catalogue(mentions, options)


# ── 🔴 承重：查不到的提及要被丢掉 ────────────────────────────────────────

@pytest.mark.asyncio
async def test_the_prod_failure_no_longer_survives():
    """📏 这是 2026-08-18 的线上原值：`store_slot='有几家店'`。"""
    assert await _keep(("有几家店",)) == ()


@pytest.mark.asyncio
async def test_other_question_fragments_are_dropped_too():
    """⚠️ 这些**不是**靠词表列举的 —— 判据是「目录里查不到」。

    ▎所以它对没见过的变体同样成立，而词表永远补不全。
    """
    for fragment in ("有几家店", "多少家店", "哪几家店", "量子纠缠火箭发射器"):
        assert await _keep((fragment,)) == (), f"{fragment!r} 没被丢掉"


# ── 🔴 阳性对照：真门店名和简称都必须留下 ────────────────────────────────

@pytest.mark.asyncio
async def test_real_store_names_survive():
    """⛔ 少了这条，一个「什么都丢掉」的实现会让上面全绿。"""
    assert await _keep(REAL) == REAL


@pytest.mark.asyncio
async def test_a_shorthand_still_survives():
    """🔴 ⛔ 不许破坏门店简称消解（#2854 刚上线的）。

    📏 「宝山店」→「模拟·宝山大场社区店」是**唯一归一**，
       所以「宝山店」必须过得了核对这一关 —— 它对得上目录里的一家。
    """
    options = REAL + ("模拟·宝山大场社区店",)
    assert await svc._mentions_backed_by_catalogue(("宝山",), options) == ("宝山",)


@pytest.mark.asyncio
async def test_an_ambiguous_shorthand_survives_too():
    """对上多家也要留下 —— 那一支要走**确认式反问**，⛔ 不是静默丢弃。"""
    options = ("模拟·宝山大场社区店", "模拟·宝山万达店", "模拟·徐汇美罗城店")
    assert await svc._mentions_backed_by_catalogue(("宝山",), options) == ("宝山",)


# ── 🔴 fail-open：判据坏了要退回「不拦」 ─────────────────────────────────

@pytest.mark.asyncio
async def test_no_catalogue_means_pass_through():
    """目录为空 ⇒ 放行。⛔ 不能因为读不到目录就把所有问句拒答。"""
    assert await _keep(("有几家店",), ()) == ("有几家店",)
    assert await _keep(("模拟·徐汇美罗城店",), ()) == ("模拟·徐汇美罗城店",)


@pytest.mark.asyncio
async def test_a_broken_resolver_means_pass_through(monkeypatch):
    """消解器抛异常 ⇒ 放行。

    ⚠️ 这条守的是**故障方向**：核对失败要退回「不拦」，
       退回「拦住」会把能答的问题变成拒答 —— 那正是本次要修的病。
    """
    async def _boom(*_a, **_k):
        raise RuntimeError("shortlist down")

    fake = types.ModuleType("smartbi.canonical.entity_resolution.shortlist")
    fake.resolve_mention = _boom
    monkeypatch.setitem(
        __import__("sys").modules,
        "smartbi.canonical.entity_resolution.shortlist", fake)
    assert await _keep(("有几家店",)) == ("有几家店",)


# ── ⛔ 只此一份判据（形态 D）─────────────────────────────────────────────

def test_it_reuses_the_existing_resolver():
    """⛔ 不许新写一份「像不像门店名」的判据。

    ⚠️ 用 AST 数**真正的调用**，⛔ 不 grep 源码文本
       —— 注释里提到函数名会被数进去（本仓栽过两次）。
    """
    tree = ast.parse(inspect.cleandoc(
        inspect.getsource(svc._mentions_backed_by_catalogue)))
    called = {
        n.func.id for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
    }
    assert "resolve_mention" in called, (
        "没有复用既有消解器 —— 又写了第二份判据，两份一定会漂"
    )
    # ⛔ 不许出现写死的门店名/片段清单
    consts = [n.value for n in ast.walk(tree)
              if isinstance(n, ast.Constant) and isinstance(n.value, str)]
    for c in consts:
        assert "店" not in c or "门店" in c or "%" in c or "核对" in c, (
            f"判据里出现了写死的门店字样: {c!r} —— 那是词表不是核对"
        )


def test_it_is_wired_at_the_place_that_refuses():
    """🔴 形态 B：机制在、没接上。

    拒答发生在 `tiered_answer` 里 `store_mention` 的推导处 ——
    核对必须接在**那里**，⛔ 不是接在一个没人调的辅助函数上。
    """
    src = inspect.getsource(svc.tiered_answer)
    tree = ast.parse(inspect.cleandoc(src))
    called = {
        n.func.id for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
    }
    assert "_mentions_backed_by_catalogue" in called, (
        "核对没接到 tiered_answer 上 —— 它守不到那句拒答"
    )
