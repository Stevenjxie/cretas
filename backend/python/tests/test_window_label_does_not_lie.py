"""窗口标签不许把「实际有数据的范围」说成「他问的那个窗口」。

## 实测的坏长相（2026-08-16）

    上个季度（2026-06-29 至 2026-06-30）经营能看：覆盖 2 天

标签是**请求窗口**（上个季度 = 91 天），括号里是**实际有数据的范围**（2 天）——
两个口径拼成一句，读起来是「系统认为上个季度就是这 2 天」。

🔴 更糟的是它**把真正该被看见的那件事盖住了**：那个季度 97.8% 的日子没有数据，
而这句话让它看起来只是个短窗口。

⇒ 两个口径都写出来，并在实际范围明显窄于请求时**明说**。
⛔ 只留实际范围会丢「他问的是什么」；只留标签会丢「实际只有这些天」。

## 阳性对照（硬约束 9）

「窄了要明说」是正向断言；配一条**不窄**的：数据铺满整个请求窗口时
⛔ 不许冒出「请求…实际…」那套话 —— 否则每句话都带它，等于没说。
"""
import datetime

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import window_scope_text


#: 🔴 直接调**产品那个函数**, ⛔ 不复刻。
#:    第一版在测试里复刻了一份拼装逻辑, 结果两个真源码变异
#:    (退回旧拼法 / 只留实际范围)**一条都不红** —— 它守的是我自己的重写,
#:    不是产品(形态 D: 两份必漂)。
#:    ⇒ 为此把那段内嵌逻辑抽成了具名函数 `window_scope_text`。
_compose = window_scope_text


D = datetime.date


def test_narrowed_window_says_both_numbers():
    """请求 91 天只有 2 天有数 ⇒ 两个口径都要出现。"""
    text = _compose("上个季度", (D(2026, 4, 1), D(2026, 6, 30)),
                    (D(2026, 6, 29), D(2026, 6, 30)))
    assert "请求 2026-04-01 至 2026-06-30" in text, text
    assert "实际有数据的只有 2026-06-29 至 2026-06-30" in text, text
    # ⛔ 不许读成「上个季度就是那两天」: 紧跟标签的括号里必须先出现请求窗口
    assert text.index("2026-04-01") < text.index("2026-06-29"), (
        f"实际范围排在请求窗口前面 —— 又会被读成「季度就是那两天」:\n{text}")


def test_full_window_does_not_carry_the_extra_wording():
    """阳性对照：数据铺满请求窗口时 ⛔ 不许冒出「请求…实际…」那套话。

    ⚠️ 少了这一条，上面那条在「每句话都带这套话」时也会绿 —— 那等于没说。
    """
    text = _compose("上个季度", (D(2026, 4, 1), D(2026, 6, 30)),
                    (D(2026, 4, 1), D(2026, 6, 30)))
    assert "请求" not in text and "实际有数据的只有" not in text, text
    assert text == "上个季度（2026-04-01 至 2026-06-30）", text


#: 🔴 「窄了」不等于「值得说」。第一版判据是「端点动了就说」，实测把
#:    **正常 ETL 末端滞后**也念出来了（本月 25/26 天）——
#:    一句天天出现的提示等于没有提示（形态 E），而仓里那两条
#:    `aligns_..._trails_sunday` 测试守的正是「末端对齐、别啰嗦」。
#: ⇒ 只在**实质性缺失**时说：① 起点被截 ② 覆盖不到一半。
@pytest.mark.parametrize("label,req,actual,expect_say", [
    ("起点被截(2/91 天)", (D(2026, 4, 1), D(2026, 6, 30)),
     (D(2026, 6, 29), D(2026, 6, 30)), True),
    ("覆盖不到一半(3/31 天)", (D(2026, 7, 1), D(2026, 7, 31)),
     (D(2026, 7, 1), D(2026, 7, 3)), True),
    # ⛔ 下面三条是**阴性对照**：它们必须保持安静
    ("ETL 末端滞后一天(25/26)", (D(2026, 7, 1), D(2026, 7, 26)),
     (D(2026, 7, 1), D(2026, 7, 25)), False),
    ("周日未入库(6/7)", (D(2026, 7, 20), D(2026, 7, 26)),
     (D(2026, 7, 20), D(2026, 7, 25)), False),
    ("刚好铺满", (D(2026, 4, 1), D(2026, 6, 30)),
     (D(2026, 4, 1), D(2026, 6, 30)), False),
])
def test_only_material_gaps_are_called_out(label, req, actual, expect_say):
    text = _compose("上个季度", req, actual)
    assert ("实际有数据的只有" in text) is expect_say, f"[{label}] {text}"


def test_the_resolver_actually_calls_it():
    """🔴 上面几条调的是 `window_scope_text` 本身 —— 对「resolver 有没有调它」是盲的。

    ⚠️ 这正是本轮反复出现的形态：函数写得对、测试全绿、**生产上没人调它**。
    ⇒ 用 AST 找真正的 `Call` 节点，⛔ 不用字符串计数
      （docstring 里提到函数名的那几行会被文本 grep 数进去）。
    """
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    tree = ast.parse(inspect.getsource(rr))
    assigned = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        value = node.value
        if not (isinstance(value, ast.Call) and isinstance(value.func, ast.Name)):
            continue
        if value.func.id != "window_scope_text":
            continue
        assigned.extend(t.id for t in node.targets if isinstance(t, ast.Name))

    assert assigned, (
        "resolver 里没有 `X = window_scope_text(...)` —— 上面几条断言就成了"
        "「守着一个产品不用的函数」")
    assert "actual_window" in assigned, (
        f"结果赋给了 {assigned} 而不是 actual_window —— 算了但没用上")
