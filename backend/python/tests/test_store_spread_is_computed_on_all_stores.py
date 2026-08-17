"""正文里那个「最高与最低相差 X%」必须按**全部门店**算，⛔ 不是前 5 家。

## 缺陷（📏 MOCK_REST prod 2026-08-18）

「哪家店卖得最好」的正文里有两句都在说这个极差：

```
…最高与最低相差 0.7%（¥2,100,493.00 vs ¥2,086,348.77）。
建议：这段时间门店之间差得很少（最高与最低相差 0.7%，低于我们认为值得单独去查的 5%）…
```

而**同一份答案里的门店表有 10 家**。那个 0.7% 是
`finance_summary(top_n_stores=5)["top_stores"]` —— **前 5 名内部**的极差。

📏 直接量（同一租户同一窗口，阳性对照 n20=10 > n5=5）：

```
前 5 名极差   0.7%   ¥2,173,119.81 / ¥2,157,418.33
全部 10 家极差 2.7%   ¥2,173,119.81 / ¥2,114,240.16
```

▎老板去核对那张 10 行的表，会发现最低的那家**不是括号里写的那家**、金额也对不上。
▎**一条经不起查的结论** —— 反目标里最重的那一条。

## ⚠️ 两个值得单独记的地方

1. **注释里写的是真值，代码用的是截断值。** `_advice_line` 自己的 docstring 写着
   「10 家店只差 **2.6%**」，而它算出来印在正文里的是 0.7%。
2. **这次建议方向碰巧没反**（0.7% 与 2.7% 都 < 5%）。
   ⛔ 不能因为这次没出事就说它没问题 —— 第 6~10 名里只要有一家显著低，方向就会反。

## 修法

① 调用点传 `store_comparison` 的**全量** `stores`（它本来就在同一个函数里）；
② 极差计算收敛成 `_store_revenue_spread` 一处（改前有两处各算一遍）。
"""
from __future__ import annotations

import ast
import inspect

from smartbi.gold.restaurant.restaurant_ops_router import (
    _STORE_SPREAD_WORTH_CHASING_PCT,
    _advice_line,
    _store_revenue_spread,
)

#: 📏 prod 实拍的 10 家营收（最近30天，MOCK_REST）。前 5 名与全量给出不同的极差 ——
#: 这正是缺陷的形状，⛔ 不要换成随手编的数字。
_PROD_TEN = (
    2173119.81, 2161085.38, 2160951.20, 2160596.15, 2157418.33,
    2146168.43, 2138326.95, 2136319.51, 2117586.00, 2114240.16,
)


def _rows(*revenues):
    return [{"store_name": f"店{i}", "revenue": r, "bill_count": 100}
            for i, r in enumerate(revenues, 1)]


class TestTheSpreadItself:

    def test_top5_and_full_list_really_do_differ(self):
        """🔴 阳性前提：这两个数**确实不同**。

        少了这条，下面的断言可能只是在测一个「反正都一样」的输入 ——
        那时它们红不了（形态 B′）。
        """
        top5 = _store_revenue_spread(_rows(*_PROD_TEN[:5]))
        full = _store_revenue_spread(_rows(*_PROD_TEN))
        assert top5 is not None and full is not None
        assert round(top5[2], 1) == 0.7, top5
        assert round(full[2], 1) == 2.7, full
        assert round(top5[2], 1) != round(full[2], 1)

    def test_lowest_matches_the_full_list_not_the_top5(self):
        """括号里那个「最低」必须是老板在表里看得到的那一家。"""
        hi, lo, _pct = _store_revenue_spread(_rows(*_PROD_TEN))
        assert lo == min(_PROD_TEN)
        assert hi == max(_PROD_TEN)

    def test_fewer_than_two_stores_has_no_spread(self):
        """阴性对照：一家店没有「差距」可言，⛔ 不许编一个 0%。"""
        assert _store_revenue_spread(_rows(100.0)) is None
        assert _store_revenue_spread([]) is None

    def test_zero_revenue_has_no_spread(self):
        """阴性对照：全 0 时 ⛔ 不许除零, 也不许说「相差 0%」当结论。"""
        assert _store_revenue_spread(_rows(0.0, 0.0)) is None


class TestOnlyOnePlaceComputesIt:
    """⛔ 改前有**两处**各算一遍 `(hi-lo)/hi`，两处都拿的截断名单。"""

    def test_the_module_computes_the_spread_in_exactly_one_function(self):
        from smartbi.gold.restaurant import restaurant_ops_router as rr

        src = inspect.getsource(rr)
        tree = ast.parse(src)

        def _is_spread_formula(node) -> bool:
            """`(A - B) / A` —— **分母就是减法的左操作数**。

            ⚠️ 这个判据被收窄过**两次**，两次都是被自己的误报逼的：

            1. 第一版按变量名匹配（`'_hi' in dump and 'Div' in dump`），
               把**解包**那一行（`hi, lo, pct = spread`）也算成「在算」。
               ⇒ 名字匹配数的是文本，不是结构（形态 C⁸）。
            2. 第二版只要求「除法左边是减法」，于是把**环比/同比增长率**
               全抓了进来 —— `resolve_trend_analysis` 里 5 处
               `(本期 - 上期) / 上期`。那不是门店极差。
               ⇒ 一道会误报的闸最终会被关掉（形态 E）。

            区分点很干净：
                门店极差   `(hi - lo) / hi`     分母 == 减法的**左**操作数
                增长率     `(last - prev) / prev` 分母 == 减法的**右**操作数

            ⛔ 这是一条**代理判据**（用表达式形状代替「这是不是门店极差」），
               标出来是为了下一个人知道它看不见什么：换成
               `(hi - lo) * 100 / hi` 或先算差值再除，它都抓不到。
            """
            if not (
                isinstance(node, ast.BinOp)
                and isinstance(node.op, ast.Div)
                and isinstance(node.left, ast.BinOp)
                and isinstance(node.left.op, ast.Sub)
            ):
                return False
            return ast.dump(node.right) == ast.dump(node.left.left)

        owners = {
            fn.name
            for fn in ast.walk(tree)
            if isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef))
            for node in ast.walk(fn)
            if _is_spread_formula(node)
        }
        assert owners == {"_store_revenue_spread"}, (
            f"极差不止一处在算: {sorted(owners)} —— 两份一定会漂, "
            f"而漂的表现是正文里两句话给出不同的百分比"
        )

    def test_the_advice_call_site_passes_the_full_store_list(self):
        """🔴 承重：调用点传的必须是全量 `stores`，⛔ 不是 `top_stores`。

        ⚠️ 这条是**名字级**断言。它守得住的原因是：这两个变量就在同一个函数里，
        一个来自 `store_comparison`（全量），一个来自
        `finance_summary(top_n_stores=…)`（截断）。传错哪一个，用户看到的数就错。
        """
        from smartbi.gold.restaurant import restaurant_ops_router as rr

        tree = ast.parse(inspect.getsource(rr))
        args = [
            [getattr(a, "id", None) for a in node.args]
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and getattr(node.func, "id", None) == "_advice_line"
        ]
        assert args, "没找到 `_advice_line` 的调用点 —— 这条断言失去意义"
        for a in args:
            assert a and a[0] == "stores", (
                f"`_advice_line` 收到的是 {a[0]!r} —— 传 top_stores 会让建议"
                f"按前 5 名的极差给（prod 实测 0.7% vs 真值 2.7%）"
            )

    def test_no_spread_is_ever_computed_from_the_truncated_list(self):
        """🔴 承重：⛔ 任何一处都不许把 `top_stores` 喂给极差函数。

        ⚠️ 这条是变异对照逼出来的：上一条只钉了 `_advice_line` 的实参，
        而正文里**另一句**（「最高与最低相差 X%（¥A vs ¥B）」）是内联算的 ——
        把它改回 `top_stores`，上一条纹丝不动。⇒ 缺一条覆盖内联那处的断言。
        """
        from smartbi.gold.restaurant import restaurant_ops_router as rr

        tree = ast.parse(inspect.getsource(rr))
        bad = [
            node.lineno
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and getattr(node.func, "id", None) == "_store_revenue_spread"
            and any(getattr(a, "id", None) == "top_stores" for a in node.args)
        ]
        assert bad == [], (
            f"第 {bad} 行把被截断的 top_stores 喂给了极差函数 —— "
            f"prod 实测那会让正文印出 0.7% 而真值是 2.7%"
        )


class TestAdviceStillTracksTheSpread:
    """阴性对照：`_advice_line` 的既有行为逐字不变（它自己的用例另有一套）。"""

    def test_flat_spread_still_says_do_not_split_by_store(self):
        text = _advice_line(_rows(*_PROD_TEN), True)
        assert "按门店拆多半找不到东西" in text, text
        assert "2.7%" in text, f"用的不是全量极差：{text}"
        assert f"{_STORE_SPREAD_WORTH_CHASING_PCT:.0f}%" in text

    def test_wide_spread_still_keeps_the_store_hunt(self):
        text = _advice_line(_rows(100.0, 50.0), True)
        assert "值得单独看" in text, text
