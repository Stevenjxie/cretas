"""毛利 resolver 的 role 必须被转发 —— 漏传就对所有人静默返 0。

## 为什么需要这个文件

`resolve_gross_margin` 用
    can_view_prices = bool(role) and role in PRICE_VIEW_ROLES
把毛利门住。调用时不传 role → `bool(None)` = False → **对所有调用方(含超管)
早退**，返回不含 kpis 的 OpsAnswer。调用方那段 `for kpi in ans.kpis` 一条都进不去，
毛利保持初始的全 0。

表现是「财务部门驾驶舱的毛利额恒为 0/『—』」，而 `/restaurant-ops/gross-margin`
专用页同期算得出 67.69% —— **两处不一致，且都不报错**。

同一个 bug 在 `store-margin` 已经修过一次(restaurant_ops_gold.py 第 613 行附近
的注释犹在)，但 summary 与 gross-margin 两个调用点漏了。**一个闸多处承载，
修一处不等于修完** —— 所以这条按「源码里所有调用点」扫，而不是只测一个。
"""
from __future__ import annotations

import ast
import io
from pathlib import Path

SRC = Path(__file__).resolve().parents[3] / "smartbi" / "api" / "restaurant_ops_gold.py"


def _calls_of(func_name: str):
    tree = ast.parse(io.open(SRC, encoding="utf-8", newline="").read())
    out = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            f = node.func
            name = getattr(f, "id", None) or getattr(f, "attr", None)
            if name == func_name:
                out.append(node)
    return out


def test_every_gross_margin_call_forwards_role():
    calls = _calls_of("resolve_gross_margin")
    assert calls, "一个调用点都没扫到 —— 函数改名了? 那本测试就成了哑的"
    missing = [c.lineno for c in calls
               if not any(kw.arg == "role" for kw in c.keywords)]
    assert not missing, (
        f"restaurant_ops_gold.py 第 {missing} 行调用 resolve_gross_margin 没传 role。"
        "resolver 会 bool(None)=False 早退, 毛利对**所有人**(含超管)静默变 0。"
    )


def test_every_store_margin_call_forwards_role():
    """同族: store-margin 是先被发现的那处, 一起钉住防回退。"""
    calls = _calls_of("resolve_store_margin")
    assert calls, "一个调用点都没扫到 —— 函数改名了?"
    missing = [c.lineno for c in calls
               if not any(kw.arg == "role" for kw in c.keywords)]
    assert not missing, f"第 {missing} 行 resolve_store_margin 没传 role"
