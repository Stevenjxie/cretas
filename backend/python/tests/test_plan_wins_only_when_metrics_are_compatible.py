"""「计划赢」还要求**指标兼容** —— 维度对了不等于答的是他问的那件事。

## 缺陷（📏 MOCK_REST prod 2026-08-18，部署后真跑，3/3 稳定）

PR #2805 的 [1a]（标签服务不了这些维度 ⇒ 计划赢）上线后：

```
哪道菜毛利最高 → 哪个卖得最多
  改前: 60 字「我准备算的东西跟你问的对不上」
  改后: **2479 字菜品毛利分析**（md5 0bbbf5de）
```

看起来「不再拒答了」，实际是**答非所问**：

- 老板问的是**销量**（`requested_metrics=('sales_volume',)`）
- 排行按**绝对毛利**排：鲈鱼 / 水煮牛肉 / 藤椒鸡 …
- 正文里「销量」只出现在一句副文（「主因：销量占比大、毛利率偏低」），
  **没有任何一处按销量排的表或结论**

▎老板很可能把第一名「鲈鱼」读成卖得最多的那道菜。
▎**那是一条经不起他去查的结论** —— 反目标里最重的一条。

按取舍顺序 **能不能决定 > 数字准不准 > 覆盖得全不全**：
他拿到一份毛利报告，那个决定**做不了**，而且被误导了。
⇒ 拒答严格好于答非所问。

## 真因：我只做对了一半

[1a] 的正当性是「计划能服务本轮**维度**」。而**维度对了不等于指标对了**：

```
📏 _default_metrics_for_code(GROSS_MARGIN)  = ('gross_profit', 'gross_margin')
   requested_metrics                        = ('sales_volume',)
   交集                                      = 空
```

⇒ 收紧：计划赢还要求**指标有交集**。判据用**既有**的
`_default_metrics_for_code`，⛔ 不新造一张表。

## ⛔ 诚实登记：这条收紧之后，[1a] 在当前样本上净效果为零

📏 实测到的形状② 3 条**全部**是「metrics=sales_volume / plan=GROSS_MARGIN」，
指标全不兼容 ⇒ 全部回到 60 字拒答（与 PR#2805 之前逐字相同）。

⇒ [1a] 的价值不是「消灭了 3 条拒答」（那个说法**是错的，我订正**），
   而是「守住『维度和指标都对』这个格子，并且不再制造答非所问」。
   它的正收益要等一个两边都对的样本出现。

⚠️ 顺带登记一个**没有解决**的产品缺口：「哪个卖得最多」（菜品 × 销量）
今天没有任何 resolver 服务这个组合 —— 那是能力表的事，不是消解矛盾的事。
"""
from __future__ import annotations

import ast
import inspect

import pytest

_SALES_SUMMARY = "RESTAURANT_OPS_SALES_SUMMARY"
_GROSS_MARGIN = "RESTAURANT_OPS_GROSS_MARGIN"


def _spec(code, dims, metrics, query="哪个卖得最多"):
    from smartbi.gold.restaurant import restaurant_intent as ri

    return ri._build_spec(code, query, confidence=0.9, tier="llm",
                          llm_dimensions=dims, llm_requested_metrics=metrics)


class TestTheMetricsMustAlsoLineUp:

    def test_the_prod_case_goes_back_to_a_refusal(self):
        """🔴 承重：prod 那条 3/3 稳定的「答非所问」必须回到拒答。

        `code=SALES_SUMMARY` 服务不了 dish，`plan=GROSS_MARGIN` 服务得了 dish
        —— 但 GROSS_MARGIN 给的是毛利，老板问的是销量。
        """
        spec = _spec(_SALES_SUMMARY, ("dish",), ("sales_volume",))
        assert spec.intent == _SALES_SUMMARY, (
            "计划赢了, 而它答的不是老板问的指标 —— 那比拒答更糟"
        )
        assert spec.intent not in spec.planned_intents, (
            "矛盾被消解了 —— 这一格今天应当留给下游诚实拒答"
        )

    def test_a_compatible_metric_still_lets_the_plan_win(self):
        """阴性对照：指标兼容时，[1a] 照旧生效，⛔ 不许被这次收紧一起关掉。

        `gross_margin` 在 GROSS_MARGIN 的默认指标里 ⇒ 两边都对 ⇒ 计划赢。
        """
        spec = _spec(_SALES_SUMMARY, ("dish",), ("gross_margin",),
                     query="哪道菜毛利最低")
        assert spec.intent == _GROSS_MARGIN, (
            "指标兼容却没让计划赢 —— 这次收紧把 [1a] 整个关掉了"
        )
        assert spec.intent in spec.planned_intents

    def test_the_two_sides_are_canonicalized_before_comparing(self):
        """🔴 两侧必须**归一**再比 —— 登记表写 `food_cost`，管线里叫 `recipe_cost`。

        📏 `_default_metrics_for_code(RECIPE_COST) = ('food_cost',)`
           而老板那句编译出来的是 `('recipe_cost',)` —— **同一个指标，两个名字**。
        不归一就是「口径不同的两个集合求交」，结果恒空，会把这一格也挡掉。

        ⚠️ 这条是变异对照逼出来的，而且**逼了两次**：

        1. 去掉 `_canonical_metrics` 后全绿 —— 我原来那几条用例的指标名两侧恰好
           相同（`gross_margin`/`sales_volume`），归一那一层没有任何断言在守。
        2. 补了一条 `query="哪道菜成本最高"` 的用例，**仍然全绿** —— 因为那句话里
           有「成本」，`_repair_backed_by_user_wording` 为真，走的是**原有那条**
           契约修复，镜像被挡住也照样修好。⇒ 那条用例对这个变异没有区分力。

        ⇒ 输入必须让**原有理由不成立**，只剩镜像这一条路：
        📏 `_repair_backed_by_user_wording("那这个呢", ("recipe_cost",))` = False，
           而 spec 仍然被修成 `RECIPE_COST` ⇒ **只能是镜像干的**。
        """
        from smartbi.gold.restaurant import restaurant_intent as ri

        # 阳性前提: 原有那条理由在这个输入上确实不成立(否则这条用例测的是它)
        assert ri._repair_backed_by_user_wording("那这个呢", ("recipe_cost",)) is False, (
            "措辞背书在这个输入上成立了 —— 这条用例会变成在测原有路径, "
            "对『两侧归一』毫无区分力"
        )
        spec = _spec(_SALES_SUMMARY, ("dish",), ("recipe_cost",), query="那这个呢")
        assert spec.intent == "RESTAURANT_OPS_RECIPE_COST", (
            "recipe_cost 与 food_cost 没被认成同一个指标 —— 两侧没归一"
        )
        assert spec.intent in spec.planned_intents

    def test_the_criterion_reads_the_existing_default_metric_table(self):
        """⛔ 不新造第二张「哪个 resolver 出哪些指标」的表（形态 D）。"""
        from smartbi.gold.restaurant import restaurant_intent as ri

        src = inspect.getsource(ri._build_spec)
        tree = ast.parse(src)
        called = {
            node.func.id
            for node in ast.walk(tree)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
        }
        assert "_default_metrics_for_code" in called, (
            "指标兼容判据没有读既有的默认指标表 —— 那就是第二份口径"
        )


class TestTheDefaultMetricTableIsWhatIThinkItIs:
    """📏 这几个数是判据的地基，钉住它们，⛔ 不靠记忆。"""

    @pytest.mark.parametrize("code,expected", [
        (_GROSS_MARGIN, ("gross_profit", "gross_margin")),
        (_SALES_SUMMARY, ("revenue", "orders", "avg_ticket")),
        ("RESTAURANT_OPS_RECIPE_COST", ("food_cost",)),
    ])
    def test_default_metrics(self, code, expected):
        from smartbi.gold.restaurant import restaurant_intent as ri

        assert ri._default_metrics_for_code(code, False) == expected

    def test_sales_volume_is_not_served_by_gross_margin(self):
        """本次收紧的那条实测读数。"""
        from smartbi.gold.restaurant import restaurant_intent as ri

        assert "sales_volume" not in ri._default_metrics_for_code(
            _GROSS_MARGIN, False)
