"""那句「点名 + 差距 + 该不该单独去查」只接了三分之一。

## 📏 缺陷（AST 实测，`restaurant_ops_router.py`）

三个 resolver 共用**同一张**门店表（`_store_breakdown_block`），
而那句判断（`_store_lead_sentence`）**只有损耗接了**：

```
resolve_wastage_top        lead=True   block=True
resolve_stock_shortage     lead=False  block=True   🔴
resolve_requisition_trend  lead=False  block=True   🔴
```

▎形态 B（机制在、没接上）＋ 硬约束 8（改共享结构时只改了一处）。

## 📏 后果（prod 实测，MOCK_REST，「按门店看领料趋势」n=995）

交付定义② 的五个标记里**只命中 1 个**（d_建议）—— 给了食材榜 + 门店榜
**两张表**，却**一句判断都没有**。10 家店领料金额 ¥771,915 ~ ¥795,972
相差 3.1%，「差得很少，别按门店查」这个结论就在表里，产品没说。

## 🔴 它此前接不上的**结构原因**

这两个 resolver **收不到 `dimensions`** —— `resolve_by_code` 按签名过滤 kwargs，
没声明的**静默丢弃**（那条判据是跑一次证出来的，见
`smartbi/scripts/_dims_gap.py` 的 `_prove_silent_drop`）。
⇒ 它们判断不了「是不是按门店问」。

▎这也是**缺口 #9 理由的重写**：不是「答案逐字相同」（那个后果实测已不复现），
▎是「按门店问时那句判断出不来」。
"""
from __future__ import annotations

import ast
import inspect

from smartbi.gold.restaurant import restaurant_ops_router as rr


def _calls_in(func_name: str) -> set:
    """AST 数**调用**，⛔ 不 grep（注释里就有这些名字）。"""
    tree = ast.parse(inspect.getsource(rr))
    for n in ast.walk(tree):
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == func_name:
            return {
                c.func.id for c in ast.walk(n)
                if isinstance(c, ast.Call) and isinstance(c.func, ast.Name)
            }
    raise AssertionError(f"找不到 {func_name} —— 用例没打中")


_SHARE_THE_STORE_TABLE = (
    "resolve_wastage_top",
    "resolve_stock_shortage",
    "resolve_requisition_trend",
)


# ── 🔴 承重：用同一张表的，就要用同一句判断 ────────────────────────────────

def test_every_resolver_with_the_store_table_also_has_the_lead_sentence():
    """⛔ 共用 `_store_breakdown_block` 的，必须也调 `_store_lead_sentence`。

    ▎判据是「用同一张表的用同一句话」，⛔ 不是「某个函数存不存在」——
      后者在缺陷发生时是绿的（那句话一直都在，只是没人调）。
    """
    missing = []
    for fn in _SHARE_THE_STORE_TABLE:
        calls = _calls_in(fn)
        assert "_store_breakdown_block" in calls, (
            f"{fn} 不再用共享门店表了 —— 用例的前提变了，先读代码再改断言"
        )
        if "_store_lead_sentence" not in calls:
            missing.append(fn)
    assert not missing, (
        f"这些 resolver 有门店表却没有那句判断: {missing}\n"
        f"老板拿到两张表，却一句「差得多不多、要不要按门店查」都没有"
    )


def test_they_can_actually_receive_dimensions():
    """🔴 接得上的**前提**：签名里要有 `dimensions`。

    `resolve_by_code` 按签名过滤 kwargs，没声明的**静默丢弃** ——
    收不到维度就判断不了「是不是按门店问」，那句话永远出不来。
    ⚠️ 这条是「机制在、没接上」的**上游**：光加调用不加形参，
       线上行为一个字都不会变，而单测可能照样绿。
    """
    import inspect as _i

    for fn in _SHARE_THE_STORE_TABLE:
        params = _i.signature(getattr(rr, fn)).parameters
        catch_all = any(
            v.kind == _i.Parameter.VAR_KEYWORD for v in params.values())
        assert "dimensions" in params or catch_all, (
            f"{fn} 收不到 dimensions —— 它判断不了『是不是按门店问』"
        )


def test_the_gate_is_asked_by_store_not_a_handwritten_variant():
    """⛔ 判「是不是按门店问」只走 `asked_by_store`。

    维度名有两套写法（登记表键 / 管线内部名），归一只有
    `canonical_dimensions` 一个家 —— 手写 `'store' in dimensions` 的变体
    会在另一套写法上静默失效。
    """
    for fn in ("resolve_stock_shortage", "resolve_requisition_trend"):
        assert "asked_by_store" in _calls_in(fn), (
            f"{fn} 没走 asked_by_store —— 手写变体会在另一套维度写法上失效"
        )


# ── 🔴 阴性对照：不按门店问的时候，那句话不许出来 ─────────────────────────

def test_the_lead_is_gated_and_not_unconditional():
    """⛔ 那句话必须**挂在条件上** —— 无条件加就是一句每次都发的废话。

    ▎「一条误发的提示烧掉的是『这东西说的话能信』」——
      老板问「最近领料多少」（没提门店）时，读到「门店之间差得很少」
      会觉得答非所问。
    """
    tree = ast.parse(inspect.getsource(rr))
    for fn in ("resolve_stock_shortage", "resolve_requisition_trend"):
        node = next(
            n for n in ast.walk(tree)
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == fn
        )
        # 找 `lead = (...)` 的赋值，确认它是一个 IfExp（条件表达式）
        assigns = [
            a for a in ast.walk(node)
            if isinstance(a, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "lead" for t in a.targets)
        ]
        assert assigns, f"{fn} 里找不到 lead 的赋值 —— 用例没打中"
        assert any(isinstance(a.value, ast.IfExp) for a in assigns), (
            f"{fn} 的那句判断是**无条件**加的 —— 不按门店问时也会发"
        )


def test_the_lead_and_the_table_read_the_same_total():
    """⛔ 首段和表格的覆盖度判断必须读**同一个分母**。

    ⚠️ 两处各传一个总额，漂的表现是「首段说覆盖 100%、表格说只覆盖 2%」——
       每个数都对，合起来是谎。
    """
    tree = ast.parse(inspect.getsource(rr))
    for fn in _SHARE_THE_STORE_TABLE:
        node = next(
            n for n in ast.walk(tree)
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == fn
        )
        lead_args, block_args = None, None
        for c in ast.walk(node):
            if not (isinstance(c, ast.Call) and isinstance(c.func, ast.Name)):
                continue
            if c.func.id == "_store_lead_sentence":
                lead_args = {k.arg: ast.dump(k.value) for k in c.keywords}
            elif c.func.id == "_store_breakdown_block":
                block_args = [ast.dump(a) for a in c.args]
        assert lead_args is not None and block_args, f"{fn} 两处调用没都找到"
        assert "all_total" in lead_args, f"{fn} 的首段没传总额"
        assert lead_args["all_total"] in block_args, (
            f"{fn} 的首段和表格读的**不是同一个总额** —— 覆盖度会各说各的"
        )
