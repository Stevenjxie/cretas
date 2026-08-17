"""环比问句被自己的 spec 拒答 —— 「放弃修复」与「拒答」成了同一件事。

## 缺陷（2026-08-17 MOCK_REST prod 实测，每句 2 轮全部稳定复现）

```
这个月比上个月好还是差   code=TREND_ANALYSIS  plan=(SALES_SUMMARY,)  → 拒答 2/2
这周比上周怎么样         code=TREND_ANALYSIS  plan=(SALES_SUMMARY,)  → 拒答 2/2
最近生意好还是差         无环比词 ⇒ code=SALES_SUMMARY 一致          → 正常答 2/2
```

老板问的最基础的一件事 ——「比上个月好还是差」—— 答不了。

## 机制

`_plan_requested_intents` 的 `elif comparison:` 分支排在 `selected_code ==
TREND_ANALYSIS` **之前**，所以一带环比就无条件编译成 SALES_SUMMARY，
**覆盖了 planner 的标签却没改那个标签**。spec 就此自相矛盾，
下游 `_execution_mismatch` 正是拿这个矛盾拒答。

而本该消解矛盾的 contract-repair 被 `_repair_backed_by_user_wording` 挡住了：
**它只会问一种证据 —— 指标**。「好还是差」里没有任何指标词，那个 `revenue`
是 LLM 填的，于是闸判定「模型的猜测推翻模型的判断」——**判得没错，问错了证据**。
真正驱动这次覆盖的是 `comparison`，而「比上个月」明明就在用户嘴里。

⇒ 日志写着「保留 planner 的判断」，实际发生的是拒答。

## ⛔ 修法不是无条件让计划赢

那会把注释里记着的两条事故重新打开（2026-07-30 采购 / 07-31 盘点，
实测这两句现在都正常答）。也不是再写第 4 个一次性补丁 ——
`llm_contract_repair` 已经有三个手写补丁了。

## ✅ 反事实归因

把某个信号关掉重编译一次，计划变了 ⇒ 这个信号是决定性的。
`comparison` 由 `_detect_comparison(text)` 纯文本抽出（零 LLM 参与）
⇒ 决定性 + 用户措辞 ⇒ 这次覆盖正当。

对**未来任何新分支**自动生效，⛔ 不需要在 20 个分支里各记一份
「我是被谁驱动的」（那正是形态 D：同一个东西有两份，它一定会漂）。
"""
import ast
import inspect

import pytest

from smartbi.gold.restaurant.restaurant_intent import (
    _detect_comparison,
    _plan_requested_intents,
)

# prod 实测那两句拒答的真实 spec 形状（除 comparison 外逐格照抄）。
_TREND = "RESTAURANT_OPS_TREND_ANALYSIS"
_SALES = "RESTAURANT_OPS_SALES_SUMMARY"


def _plan(text, selected_code, metrics, comparison):
    return _plan_requested_intents(
        text, selected_code, metrics, (), "all", "lookup", comparison, None,
    )


class TestTheComparisonComesFromTheUsersOwnWords:
    """闸的全部正当性来源 —— 这个信号必须**不是**模型填的。"""

    @pytest.mark.parametrize("query,kind", [
        ("这个月比上个月好还是差", "previous_month"),
        ("这周比上周怎么样", "wow"),
        ("今年比去年怎么样", "yoy"),
        ("环比如何", "mom"),
    ])
    def test_detected_from_text_alone(self, query, kind):
        assert _detect_comparison(query) == kind

    @pytest.mark.parametrize("query", [
        # 🔴 阴性对照: 注释里记着的两条历史事故句。它们句子里没有任何环比词,
        #    所以这道新开的门对它们**关着** —— 少了这两条, 上面的放宽可能把
        #    2026-07-30 / 07-31 两条事故重新打开而没有任何断言会红。
        "全部门店最近30天采购花了多少钱",
        "全部门店最近30天盘点亏了多少",
        # 同形: 没有「比」的经营问句照旧不走这条路
        "最近生意好还是差",
        "这个月生意怎么样",
    ])
    def test_no_comparison_word_no_door(self, query):
        assert _detect_comparison(query) is None


class TestTheComparisonIsWhatDecidedThePlan:
    """反事实: 把环比关掉, 计划就变了 ⇒ 这次覆盖是环比驱动的。"""

    @pytest.mark.parametrize("query,comparison", [
        ("这个月比上个月好还是差", "previous_month"),
        ("这周比上周怎么样", "wow"),
    ])
    def test_comparison_is_decisive(self, query, comparison):
        with_it = _plan(query, _TREND, ("revenue",), comparison)
        without = _plan(query, _TREND, ("revenue",), None)
        assert with_it == (_SALES,), with_it
        assert without == (_TREND,), without
        assert with_it != without, "环比不是决定性的 —— 归因前提不成立"

    def test_the_plan_contradicts_the_label_which_is_the_defect_itself(self):
        """🔴 承重: 这正是 prod 上那个自相矛盾的 spec。"""
        planned = _plan("这个月比上个月好还是差", _TREND, ("revenue",), "previous_month")
        assert _TREND not in planned, (
            "计划里含 planner 的标签 —— 那 prod 上那次拒答就不是这个成因"
        )

    @pytest.mark.parametrize("metrics,expected", [
        # 🔴 阴性对照: 环比在**别的指标**上不是决定性的 —— 关掉它计划不变,
        #    所以归因不会把那些问句也放进门。少了这条, 「有环比词就放行」
        #    这种过宽的实现同样能让上面全绿。
        (("wastage",), "RESTAURANT_OPS_WASTAGE_TOP"),
        (("requisition_cost",), "RESTAURANT_OPS_REQUISITION_TREND"),
        (("stocktaking_shortage",), "RESTAURANT_OPS_STOCK_SHORTAGE"),
    ])
    def test_comparison_is_not_decisive_for_other_metrics(self, metrics, expected):
        query = "这个月比上个月损耗多少"
        with_it = _plan(query, _TREND, metrics, "previous_month")
        without = _plan(query, _TREND, metrics, None)
        assert with_it == without == (expected,), (with_it, without)


class TestTheAttributionIsWiredIntoTheGuard:
    """构造出来不算 —— 必须接在那道 guard 上。

    🔴 这个类存在的理由是本仓最常犯的那条: 「测了 helper, 没测接线」。
       把 `or comparison_drove_the_plan` 从 guard 里删掉, 上面所有断言
       **一条都不会红** —— 它们测的是 `_plan_requested_intents` 和
       `_detect_comparison`, 两个都没变。

    ⚠️ 这两条第一次跑就红了, 而且红得有用: 我写断言时以为 contract-repair 在
       `parse_restaurant_query` 里, 实际在 `_build_spec`。⇒ 接线断言不只是守
       将来的回归, 它当场纠正了我对代码位置的假设。
    """

    @staticmethod
    def _guard_test_src():
        from smartbi.gold.restaurant import restaurant_intent as ri

        tree = ast.parse(inspect.getsource(ri._build_spec))
        for node in ast.walk(tree):
            if not isinstance(node, ast.If):
                continue
            src = ast.dump(node.test)
            if "planned_intents" in src and "supported_requested_metrics" in src:
                return src
        return ""

    def test_the_repair_guard_consults_the_attribution(self):
        src = self._guard_test_src()
        assert src, "没找到 contract-repair 的 guard —— 断言失去意义, 先修这里"
        assert "comparison_drove_the_plan" in src, (
            "guard 没有引用反事实归因 —— 环比问句照旧因 spec 自相矛盾被拒答"
        )

    def test_the_attribution_flips_off_the_comparison_and_recompiles(self):
        """归因必须是**重编译一次**得出的, ⛔ 不许退化成「句子里有没有比字」。"""
        from smartbi.gold.restaurant import restaurant_intent as ri

        src = inspect.getsource(ri._build_spec)
        marker = "comparison_drove_the_plan = planned_intents != _plan_requested_intents("
        assert marker in src, (
            "归因不是靠重编译得出的 —— 那样它就不再随规划表的分支自动生效"
        )
