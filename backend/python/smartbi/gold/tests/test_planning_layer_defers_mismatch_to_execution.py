"""规划层不再自己拼通用反问 —— 它把「计划对不上」让给执行层去说清楚。

## 背景（2026-08-17）

`restaurant_intent._build_spec` 里原来有一道闸：`code not in planned_intents`
就置 `clarification_needed=True`，文案是固定的

    「这次是想看某道菜、某家门店，还是全店汇总？说一个就行 —— 我不会拿别的数据顶替。」

prod 实测它把**能力边界**说成了**信息不足**：老板问「哪家店成本最高」，
而 `RECIPE_COST` 只服务 `dish`（成本卡按菜录），**没有按门店的成本指标**。
他回答「门店」照样拿不到 —— 一次白点击。

反事实实测（`precomputed_spec` 抹掉 clarification，⛔ 不改代码）：
16 句里被拦 2 条，反事实**出数 0 条**，执行层给的是诚实且可行动的拒答。
⇒ 删掉规划层那道闸。

## 这道闸钉的是「删掉它之所以安全」的那条不变量

`_execution_mismatch` 第一行：

    if spec.plan_version != "restaurant-query-plan-v2":
        return None          # ← 非 v2 执行层**接不住**

删掉规划层的拦截之后，接住这件事**完全依赖**执行层。
所以只要 `_build_spec` 造出来的 spec 不再是 v2，删除就从「下沉」变成「放行」，
而放行的表现是**悄悄答成别的**，比反问糟得多，且不会报错。

⚠️ 这条不变量目前由构造保证（`_build_spec` 只有 1 个 return，
   `plan_version` 写死，全包唯一一处）。**构造保证的东西也会被改**，所以钉住它。
"""
import ast
import inspect

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant import restaurant_intent_service as svc


def _build_spec_source():
    return inspect.getsource(ri._build_spec)


class TestTheInvariantThatMakesTheRemovalSafe:
    def test_build_spec_still_stamps_the_v2_plan_version(self):
        """执行层只接 v2 —— 规划层必须一直造 v2，否则删除变成放行。"""
        tree = ast.parse(_build_spec_source())
        stamped = [
            kw.value.value
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            for kw in node.keywords
            if kw.arg == "plan_version" and isinstance(kw.value, ast.Constant)
        ]
        assert stamped == ["restaurant-query-plan-v2"], (
            f"`_build_spec` 盖的 plan_version 变成了 {stamped!r} —— "
            f"执行层 `_execution_mismatch` 只接 'restaurant-query-plan-v2'，"
            f"其余一律 return None ⇒ 规划层已删掉的那道闸不再有人接手，"
            f"「计划对不上」会被**放行去算**，而那是悄悄答成别的"
        )

    def test_build_spec_has_a_single_exit(self):
        """多一个 return 就多一条可能绕过 v2 盖章的路。"""
        tree = ast.parse(_build_spec_source())
        fn = tree.body[0]
        returns = [n for n in ast.walk(fn) if isinstance(n, ast.Return)]
        assert len(returns) == 1, (
            f"`_build_spec` 出口从 1 个变成 {len(returns)} 个 —— "
            f"逐个确认每条路都盖了 v2"
        )

    def test_execution_layer_still_catches_the_same_condition(self):
        """执行层必须仍然按 `spec.intent not in plan` 判 —— 那是规划层原来的条件。"""
        src = inspect.getsource(svc._execution_mismatch)
        assert "spec.intent not in plan" in src, (
            "执行层不再检查『主意图不在计划里』—— 规划层那道闸已经删了，"
            "这个条件现在没有任何人守"
        )


class TestThePlanningLayerNoLongerAsksTheGenericQuestion:
    def test_the_generic_clarification_is_gone_from_the_planner(self):
        """那句固定文案不许在规划层复活。

        ⚠️ 守的是**这一层不再拼它**，⛔ 不是「全仓不许有这句话」——
           别处（真正缺对象时的追问）用同样的措辞是合法的。

        🔴 第一版写成 `"…" not in 源码文本`，**当场被自己的注释打红** ——
           删除处的注释里引用了那句原文以说明删了什么。
           ⇒ 改成扫 **AST 字符串常量**：`#` 注释根本不进 AST，
             而「有没有在拼这句话」问的本来就是**代码结构**，不是文本
             （本仓 C⁸：闸用 AST，不用字符串计数）。
        """
        tree = ast.parse(_build_spec_source())
        literals = [
            node.value for node in ast.walk(tree)
            if isinstance(node, ast.Constant) and isinstance(node.value, str)
        ]
        offenders = [s for s in literals if "还是全店汇总" in s]
        assert not offenders, (
            f"规划层又开始自己拼那句通用反问了 —— 它会抢在执行层的"
            f"维度缺口说明之前, 把能力边界说成信息不足: {offenders!r}"
        )
