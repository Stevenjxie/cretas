"""同一句 fail-closed 拒答的**每一个载体**都要留痕 —— 载体是算出来的，不是数出来的。

## 事故

2026-08-11 上午加了「供应商不可用的 fail-closed 要留痕」(#2485)，因为交接里有一条
待办「瞬时不可用之后下一轮被当成全新问题」**根本没法查**：那句拒答在
`python-prod.log` 出现 0 次、落库 7 天 0 条。

我给它装了日志 —— 但**只装了 2 个载体**（`restaurant_intent.py` 的两处）。
同一句话在 `chat.py` 还有 2 处。当天下午电池 [40] 真的走到了**没装的那两处**，
于是日志里**一点痕迹都没有**，我还在 grep 里找了半天。

📌 这是当天早上刚写进 memory 的判据（「这个闸的载体比它查的那张表多」）**当天又犯**。

## 判据

**载体要算出来，不能凭印象数。**

本闸不维护任何「有哪几处」的清单 —— 它扫源码，把**每一个**吐出这句拒答的地方
都找出来，逐个要求它旁边有留痕。新增第 5 个载体时，这条会自动变红。
"""
from __future__ import annotations

import ast
import pathlib
from typing import List, Tuple

#: 用户看到的那句话。
#:
#: ⚠️ 2026-08-12 改：原来是手抄的字面量 `"餐饮语义规划暂时不可用"`。当天把这句
#:    白话化并收敛成 `customer_text.PLANNER_UNAVAILABLE` 之后，那 4 处内联字符串
#:    全变成了常量引用 —— 本闸按**字符串字面量**扫，于是**载体数从 4 掉到 0**，
#:    `test_every_fail_closed_carrier_leaves_a_trace` 跟着变成恒真的绿。
#:    (只有 `test_there_is_more_than_one_carrier` 那条阴性对照拦住了它。
#:     没有那条，这次改文案就会把一道承重闸静默变瞎。)
#:
#: 判据：**闸认载体的方式，要跟着载体的形态走。**
#:       文案集中成常量之后，「载体」就是**引用那个常量的地方**，不再是字面量。
#:       两种形态都认 —— 万一有人又把它内联回去，也照样算载体。
from smartbi.gold.customer_text import PLANNER_UNAVAILABLE

_FAIL_CLOSED_TEXT = PLANNER_UNAVAILABLE
#: 常量名本身 —— 引用它的地方就是载体。
_FAIL_CLOSED_CONST = "PLANNER_UNAVAILABLE"

#: 留痕的标记。⛔ 与生产代码里写的那个字符串**逐字一致**，不另起一个名字。
_TRACE_MARKER = "planner-outage"

_ROOT = pathlib.Path(__file__).resolve().parents[3]
_SCAN = ("smartbi/gold/restaurant/restaurant_intent.py", "smartbi/api/chat.py")


def _carriers() -> List[Tuple[str, int, str]]:
    """(文件, 行号, 所在函数) —— 每一个吐出那句拒答的地方。

    ⛔ 判据是「这个字符串出现在**非注释、非 docstring** 的位置」：注释里提到它
       (比如本文件引用的那段历史)不算载体。用 AST 取字符串字面量，不用 grep。
    """
    out: List[Tuple[str, int, str]] = []
    for rel in _SCAN:
        path = _ROOT / rel
        src = path.read_text(encoding="utf-8")
        tree = ast.parse(src)

        docstrings = set()
        for node in ast.walk(tree):
            if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef,
                                 ast.AsyncFunctionDef)):
                body = getattr(node, "body", None)
                if (body and isinstance(body[0], ast.Expr)
                        and isinstance(body[0].value, ast.Constant)
                        and isinstance(body[0].value.value, str)):
                    docstrings.add(id(body[0].value))

        funcs = [(n.lineno, n.end_lineno, n.name) for n in ast.walk(tree)
                 if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))]

        for node in ast.walk(tree):
            # 形态一: 内联字面量(2026-08-12 之前的形态, 保留以防有人写回去)
            if isinstance(node, ast.Constant) and isinstance(node.value, str):
                if id(node) in docstrings or _FAIL_CLOSED_TEXT not in node.value:
                    continue
            # 形态二: 引用那个常量 —— 文案集中之后, 这才是载体
            elif isinstance(node, ast.Name) and node.id == _FAIL_CLOSED_CONST:
                # import 那一行不是载体 —— 它不吐拒答, 只是把名字拿进来。
                pass
            else:
                continue
            owner = ""
            best = -1
            for start, end, name in funcs:
                if start <= node.lineno <= (end or start) and start > best:
                    best, owner = start, name
            out.append((rel, node.lineno, owner))
    return out


def test_there_is_more_than_one_carrier():
    """⛔ 阴性对照: 只找到 1 个载体时, 下面那条几乎必然绿 —— 而事故正是「有 4 个」。

    这条也钉住扫描范围: 少扫一个文件, 载体数会掉下来。
    """
    found = _carriers()
    assert len(found) >= 4, (
        f"只找到 {len(found)} 个载体: {found} —— 扫描范围可能漏了文件")


def test_every_fail_closed_carrier_leaves_a_trace():
    """🔴 承重: 每一个吐出这句拒答的函数, 都必须在同一个函数里留下痕迹。

    2026-08-11 实测: `chat.py` 的两处没有留痕, 于是电池 [40] 真的走到那里时
    日志里一点痕迹都没有 —— **静默的 fail-closed 让它永远查不出根因**。
    """
    missing = []
    for rel, lineno, owner in _carriers():
        src = (_ROOT / rel).read_text(encoding="utf-8")
        tree = ast.parse(src)
        body_src = ""
        for node in ast.walk(tree):
            if (isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                    and node.name == owner):
                seg = ast.get_source_segment(src, node) or ""
                if len(seg) > len(body_src):
                    body_src = seg
        if _TRACE_MARKER not in body_src:
            missing.append(f"{rel}:{lineno} (在 {owner} 里)")
    assert not missing, (
        "这些地方吐 fail-closed 拒答但**不留痕** —— 复发时既查不到发生过, "
        "也拿不到当时的 session:\n  " + "\n  ".join(missing))


def test_the_marker_matches_what_production_actually_logs():
    """⛔ 阴性对照: 标记要与生产代码里写的**逐字一致**。

    我要是在这里写一个生产代码里不存在的标记, 上面那条会全部报缺失(假红);
    写一个到处都有的词(如 "logger"), 则会全部通过(假绿)。
    """
    intent = (_ROOT / "smartbi/gold/restaurant/restaurant_intent.py").read_text(
        encoding="utf-8")
    assert _TRACE_MARKER in intent, "标记在生产代码里不存在 —— 上面那条会假红"
    assert intent.count(_TRACE_MARKER) < 20, "标记太常见了 —— 上面那条会假绿"
