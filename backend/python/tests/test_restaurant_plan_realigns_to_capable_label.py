"""「保留 planner 的判断」必须真的保留 —— 否则它与拒答是同一件事。

## 缺陷（2026-08-17 MOCK_REST prod 实测，稳定复现）

```
哪家店成本最高
  code = RESTAURANT_OPS_STORE_MARGIN    声明 ['dish','store'] —— **它能按门店**
  plan = ('RESTAURANT_OPS_RECIPE_COST',) 声明 ['dish']        —— 它不能
  dims = ('store',)
  ⇒ code ∉ plan ⇒ spec 自相矛盾 ⇒ 拒答
```

产品**明明算得出**（STORE_MARGIN 就是按门店×菜品的那条），
却因为两个内部推导不一致而拒答。

## 机制

契约修复有一道 `repair_target_serves_dimensions`：目标 resolver 服务不了本次
维度就跳过，日志写着「保留 planner 的判断」。**那句话是对的，但它没有被执行。**
跳过之后 `code` 与 `planned_intents` **原样保持矛盾**，下游 `_execution_mismatch`
正是拿这个矛盾拒答 —— planner 的判断一个字都没保留下来。

▎**「放弃修复」与「拒答」是同一件事** —— 这是同一句判断的第二次兑现。
▎第一次是环比那条（PR #2798，方向相反：那次计划赢，这次标签赢）。

## 判据同源

`_resolver_serves_dimensions` 是**抽出来的一份**，两个用处共用：
① 目标能不能服务（决定要不要覆盖）② 标签能不能服务（决定要不要对齐）。
⛔ 不各写一份 subset 检查 —— 漂的表现恰好又是「自己造成再自己拒答」（形态 D）。
"""
import ast
import inspect

import pytest

from smartbi.gold.restaurant.restaurant_intent import _resolver_serves_dimensions

_STORE_MARGIN = "RESTAURANT_OPS_STORE_MARGIN"
_RECIPE_COST = "RESTAURANT_OPS_RECIPE_COST"
_INVENTORY_WARNING = "RESTAURANT_OPS_INVENTORY_WARNING"


class TestTheCriterionMatchesTheDeclaredCapabilities:
    """判据必须读**能力声明表**，⛔ 不是硬编码一张自己的表。"""

    def test_store_margin_serves_store(self):
        """prod 那次该赢的那一边。"""
        assert _resolver_serves_dimensions(_STORE_MARGIN, ("store",)) is True

    def test_recipe_cost_does_not_serve_store(self):
        """prod 那次编译出来的计划 —— 它确实服务不了，所以不该覆盖标签。"""
        assert _resolver_serves_dimensions(_RECIPE_COST, ("store",)) is False

    def test_inventory_warning_does_not_serve_store(self):
        """🔴 阴性对照，取自同一轮的**另一句**「哪家店缺货最严重」。

        它的 code 是 INVENTORY_WARNING（只声明 ingredient），
        ⇒ 标签自己也服务不了 store ⇒ **不许**对齐，那次拒答是正当的。

        少了这条，「只要 code ∉ plan 就对齐到 code」这种过宽实现同样能让
        上面两条全绿，而它会把一个真能力边界伪装成能答。
        """
        assert _resolver_serves_dimensions(_INVENTORY_WARNING, ("store",)) is False

    def test_all_is_not_a_grouping_dimension(self):
        """`all` 是「不分组」，必须被 `grouping_dimensions` 减掉。

        不减的话判据对**任何全店合计问句**恒为 False（2026-08-13 实测踩过），
        这条闸就会把它们全部挡住。
        """
        assert _resolver_serves_dimensions(_RECIPE_COST, ("all",)) is True

    @pytest.mark.parametrize("resolver", ["", None])
    def test_empty_resolver_serves_nothing(self, resolver):
        """⛔ 空标签不许被当成「什么都能服务」—— 那会对齐到一个空计划。"""
        assert _resolver_serves_dimensions(resolver or "", ("store",)) is False


class TestThereIsOnlyOneCopyOfTheCriterion:
    """同一个判断有两个用处，⛔ 只许有一份实现。"""

    def test_the_skip_guard_and_the_realign_use_the_same_helper(self):
        """三个用处共用同一份判据。

        ⚠️ 2026-08-18 从 2 改成 3。第三处是**镜像那一支**（标签服务不了 ⇒
        计划赢，见 `tests/test_plan_wins_when_the_label_cannot_serve.py`）。
        📏 那一支消灭的是 prod 上 3/3 稳定的
        「哪道菜毛利最高 → 哪个卖得最多」（code=SALES_SUMMARY 不能按菜品、
        plan=GROSS_MARGIN 能，矛盾原样留着 ⇒ 60 字反问）。

        ⛔ 精确计数是**刻意**的（与 `test_real_gold_contracts` 那道签名闸同一条
        纪律）：任何新增/删除都必须来这里过一眼，确认它确实走的是同一份判据
        而不是又写了一份。同一天那道签名闸就靠这条抓到过我漏改的消费方。
        """
        from smartbi.gold.restaurant import restaurant_intent as ri

        src = inspect.getsource(ri._build_spec)
        assert src.count("_resolver_serves_dimensions(") == 3, (
            "三个用处没有共用同一份判据 —— 它们会漂, 而漂的表现恰好是"
            "「自己造成再自己拒答」"
        )
        assert "issubset(" not in src, (
            "_build_spec 里又出现了手写的 subset 检查 —— 那就是第二份口径"
        )


class TestTheRealignIsWiredIntoTheSkipBranch:
    """构造出来不算 —— 必须接在「跳过修复」那个分支里。

    🔴 上面所有断言测的都是 `_resolver_serves_dimensions` 这个 helper。
       把整段对齐逻辑删掉, **一条都不会红** —— 这正是本仓最常犯的
       「测了 helper，没测接线」。
    """

    @staticmethod
    def _skip_branch():
        """定位 `if not repair_target_serves_dimensions:` 那个分支。"""
        from smartbi.gold.restaurant import restaurant_intent as ri

        tree = ast.parse(inspect.getsource(ri._build_spec))
        for node in ast.walk(tree):
            if (
                isinstance(node, ast.If)
                and isinstance(node.test, ast.UnaryOp)
                and isinstance(node.test.op, ast.Not)
                and getattr(node.test.operand, "id", "")
                == "repair_target_serves_dimensions"
            ):
                return node
        return None

    def test_the_skip_branch_exists(self):
        assert self._skip_branch() is not None, (
            "没找到「跳过修复」那个分支 —— 下面的断言会失去意义, 先修这里"
        )

    def test_the_skip_branch_reassigns_the_plan(self):
        """🔴 承重: 跳过修复时必须把计划对齐到标签, ⛔ 不许只记一条日志。"""
        branch = self._skip_branch()
        assigns = [
            n
            for n in ast.walk(branch)
            if isinstance(n, ast.Assign)
            and any(getattr(t, "id", None) == "planned_intents" for t in n.targets)
        ]
        assert len(assigns) == 1, (
            "「跳过修复」分支里没有对 planned_intents 赋值 —— "
            "矛盾原样留给下游, 那条「保留 planner 的判断」的日志是空话"
        )

    def test_the_realign_is_gated_on_the_label_being_capable(self):
        """⛔ 不许无条件对齐 —— 标签自己服务不了时那次拒答是正当的。"""
        branch = self._skip_branch()
        guarded = [
            n
            for n in ast.walk(branch)
            if isinstance(n, ast.If)
            and "_resolver_serves_dimensions" in ast.dump(n.test)
        ]
        assert guarded, (
            "对齐没有以「标签能服务这些维度」为条件 —— "
            "会把真能力边界(如 INVENTORY_WARNING 按门店)伪装成能答"
        )
