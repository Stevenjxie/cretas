"""标签服务不了这些维度、而计划能 ⇒ **计划赢**（PR#2799 那一支的镜像）。

## 缺陷（📏 MOCK_REST prod 2026-08-18，14 条追问链 × 3 轮，⛔ 不是读代码推的）

42 条样本里 `code ∉ planned_intents` 有 12 条，全部落在追问链上、
全部归宿 `clarification`（60 字通用反问）。逐轮读数 3/3 稳定，只有两种形状：

```
形状①  9 条  dims=∅        code_serves=True   plan0_serves=True   ← 判不出高下
形状②  3 条  dims=('dish',) code_serves=False  plan0_serves=True   ← 本文件守这一种
```

形状②的实例（3 轮完全一致）：

```
哪道菜毛利最高 → 哪个卖得最多
  code = RESTAURANT_OPS_SALES_SUMMARY   声明 ['store']          —— 它不能按菜品
  plan = ('RESTAURANT_OPS_GROSS_MARGIN',) 声明 ['dish','time']  —— **它能**
  dims = ('dish',)   metrics = ('sales_volume',)
  ⇒ code ∉ plan 原样留着 ⇒ 60 字「我准备算的东西跟你问的对不上」
```

## 为什么是「镜像」而不是新规则

PR#2799 处理的是 **plan 服务不了 ⇒ 标签赢**（`planned_intents = (code,)`）。
这一支是 **标签服务不了 ⇒ 计划赢**（`code = planned_intents[0]`）——
判据是**同一个** `_resolver_serves_dimensions`，方向相反。

▎两支的共同点：⛔ **都不许把矛盾留给下游当拒答理由。**
▎「放弃修复」与「拒答」在代码里是同一件事。

## ⛔ 显式登记：本文件**不管**形状①

`dims=∅` 时两边都 serves，维度判据**没有区分力**。而按架构 §反问⑥
「那最差的呢」没说是店/菜/时段 ⇒ **反问本身是对的**，要改的是措辞而不是消解矛盾。
⛔ 不在这里用一条「总是消解」的规则把它一起吃掉 —— 那会把
「哪家店卖得最好 → 那成本呢」（成本只能按菜录，按门店的成本**不存在**）
从诚实拒答变成「拿别的数据凑」。
"""
import ast
import inspect

import pytest

from smartbi.gold.restaurant.restaurant_intent import _resolver_serves_dimensions

_SALES_SUMMARY = "RESTAURANT_OPS_SALES_SUMMARY"
_GROSS_MARGIN = "RESTAURANT_OPS_GROSS_MARGIN"
_INVENTORY_WARNING = "RESTAURANT_OPS_INVENTORY_WARNING"


class TestTheCriterionMatchesTheDeclaredCapabilities:
    """判据读**能力声明表**，⛔ 不硬编码第二张。数字取自 prod 那 3 轮。"""

    def test_sales_summary_does_not_serve_dish(self):
        """prod 那次 LLM 给的标签 —— 它确实服务不了菜品粒度。"""
        assert _resolver_serves_dimensions(_SALES_SUMMARY, ("dish",)) is False

    def test_gross_margin_serves_dish(self):
        """prod 那次编译出来的计划 —— **它能**，所以该赢的是计划。"""
        assert _resolver_serves_dimensions(_GROSS_MARGIN, ("dish",)) is True

    def test_neither_side_serves_is_still_a_refusal(self):
        """🔴 阴性对照：两边都服务不了时，那次拒答是**正当的**。

        少了这条，「只要 code ∉ plan 就把 code 换成 plan[0]」这种过宽实现
        同样能让上面两条全绿，而它会把真能力边界伪装成能答。
        """
        assert _resolver_serves_dimensions(_INVENTORY_WARNING, ("store",)) is False
        assert _resolver_serves_dimensions(_SALES_SUMMARY, ("ingredient",)) is False


class TestBothDirectionsLiveInTheSameBranchAndShareTheCriterion:
    """两个方向必须**同一个家**，且读同一份判据。"""

    @staticmethod
    def _dims_branch():
        """定位 `if repair_candidate and dimensions:` 那一整块。"""
        from smartbi.gold.restaurant import restaurant_intent as ri

        tree = ast.parse(inspect.getsource(ri._build_spec))
        for node in ast.walk(tree):
            if isinstance(node, ast.If) and isinstance(node.test, ast.BoolOp):
                names = {
                    getattr(v, "id", None) for v in node.test.values
                }
                if {"repair_candidate", "dimensions"} <= names:
                    return node
        return None

    def test_the_branch_exists(self):
        assert self._dims_branch() is not None, (
            "没找到 `if repair_candidate and dimensions:` —— 下面的断言会失去意义"
        )

    def test_the_mirror_is_gated_on_the_label_being_incapable(self):
        """⛔ 不许无条件让计划赢 —— 标签能服务时该赢的是标签（PR#2799）。"""
        branch = self._dims_branch()
        dumped = ast.dump(branch)
        assert dumped.count("_resolver_serves_dimensions") >= 3, (
            "镜像那一支没有以「标签服务不了这些维度」为条件"
        )

    def test_no_second_hand_written_criterion(self):
        """⛔ 三个用处必须共用同一份判据（形态 D）。"""
        from smartbi.gold.restaurant import restaurant_intent as ri

        src = inspect.getsource(ri._build_spec)
        assert "issubset(" not in src, (
            "_build_spec 里出现了手写的 subset 检查 —— 那就是第二份口径"
        )


class TestItActuallyRuns:
    """🔴 行为级承重 —— AST 断言在这里**不够**，而这是变异当场抓出来的。

    第一版这两条写的是 AST：「分支里有没有 `code = ...` 赋值」「authority 那个
    字面量在不在白名单里」。变异实测：

        M1 把整支关成 `elif False:`  → **不红**（分支体还在，AST 照样看得见）
        M3 把 authority 换成一个没白名单化的新串 → **全绿**（它不以
           `_contract_repair` 结尾，我的正则/字面量检查根本没扫到它）

    ⇒ 两条都是恒真式（形态 B′）。判据必须打在**行为**上：跑一次 `_build_spec`，
      看它交出来的 spec 自不自洽、authority 可不可信。
    """

    @staticmethod
    def _spec(code, dims, metrics):
        from smartbi.gold.restaurant import restaurant_intent as ri

        return ri._build_spec(
            code, "哪个卖得最多", confidence=0.9, tier="llm",
            llm_dimensions=dims, llm_requested_metrics=metrics,
        )

    def test_mirror_direction_produces_a_self_consistent_spec(self):
        """标签 SALES_SUMMARY 服务不了 dish，计划 GROSS_MARGIN 能 ⇒ 计划赢。

        ⚠️ 2026-08-18 订正输入：原来用的是 `('sales_volume',)`，
        那正是 prod 上线后被抓到的「答非所问」那一格 —— **维度对了不等于指标对了**，
        它现在应当回到拒答（`test_plan_wins_only_when_metrics_are_compatible.py`）。
        这条守的性质没变（镜像那一支会生效），只是换成一个**指标也兼容**的输入。
        形态 C‴：断言守的是需求，而需求被订正了 ⇒ 改断言，⛔ 不是回退改动。
        """
        spec = self._spec(_SALES_SUMMARY, ("dish",), ("gross_margin",))
        assert spec.planned_intents, "计划编译成空的, 这条用例测不到东西"
        assert spec.intent in spec.planned_intents, (
            f"交给执行的 spec 仍然自相矛盾: intent={spec.intent} "
            f"plan={list(spec.planned_intents)} —— 下游会拿这个矛盾拒答"
        )
        assert spec.intent == _GROSS_MARGIN, (
            "赢的不是计划 —— 镜像那一支没生效"
        )

    def test_mirror_direction_keeps_the_authority_trusted(self):
        """⚠️ 修好矛盾却换来另一句拒答, 是这个改动最容易长出的坏形状。"""
        from smartbi.gold.restaurant.restaurant_intent import (
            TRUSTED_PLANNER_AUTHORITIES,
        )

        spec = self._spec(_SALES_SUMMARY, ("dish",), ("gross_margin",))
        assert spec.planner_authority in TRUSTED_PLANNER_AUTHORITIES, (
            f"planner_authority={spec.planner_authority!r} 不在白名单里 —— "
            f"`_execution_mismatch` 会判「这次的问题我没理解到有把握的程度」"
        )

    def test_the_other_direction_still_wins_when_the_label_is_capable(self):
        """阴性对照：PR#2799 那一支 ⛔ 不许被这次改动抢走。

        标签 STORE_MARGIN 能按门店、计划 RECIPE_COST 不能 ⇒ **标签**赢。
        """
        spec = self._spec("RESTAURANT_OPS_STORE_MARGIN", ("store",),
                          ("recipe_cost",))
        assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN", (
            "标签能服务这些维度时该赢的是标签, 被镜像那一支抢走了"
        )
        assert spec.intent in spec.planned_intents

    def test_shape_one_dims_empty_is_deliberately_left_untouched(self):
        """⛔ 登记：形状①（`dims=∅`）**故意不改**，行为与改动前逐字相同。

        📏 prod 12 条 `code ∉ plan` 里有 9 条是这个形状，3 轮稳定。
        两边都 serves（空集是任何集合的子集）⇒ 维度判据**没有区分力**；
        而按架构 §反问⑥「那最差的呢」没说是店/菜/时段 ⇒ **反问本身是对的**。
        统一消解会把「哪家店卖得最好 → 那成本呢」（成本只能按菜录，按门店的
        成本**不存在**）从诚实拒答变成拿别的数据凑。

        ⚠️ 这条断言守的是**今天的裁定**，不是永远的需求（形态 C‴）：
        哪天决定修形状①，它会红 —— 那时该改的是这条断言，不是把改动回退。
        它同时抓住「有人把镜像那一支的 `and dimensions` 条件去掉」这种过宽实现。
        """
        spec = self._spec(_SALES_SUMMARY, (), ("gross_margin",))
        if spec.planned_intents and spec.intent:
            assert spec.intent not in spec.planned_intents, (
                "dims=∅ 这个形状被消解了 —— 本轮明确登记为不改, "
                "先去更新登记再改行为"
            )

    def test_neither_capable_is_left_alone(self):
        """阴性对照：两边都服务不了时 ⛔ 不许硬凑一个出来。

        INVENTORY_WARNING 只声明 ingredient；问的是 store。
        这时候拒答是**正当的**，产品确实没有按门店的缺货数据。
        """
        spec = self._spec("RESTAURANT_OPS_INVENTORY_WARNING", ("store",), ())
        assert spec.intent == "RESTAURANT_OPS_INVENTORY_WARNING", (
            "两边都服务不了却把 code 换掉了 —— 那就是拿别的数据凑"
        )


class TestTheTwoHistoricalIncidentsStayClosed:
    """`label_cannot_serve_dimensions` **旁路**了三道有事故背景的 guard
    （`supported_requested_metrics` / `_repair_backed_by_user_wording` /
    `_CONTRACT_REPAIRABLE_METRICS`）。必须证明它没有把那两次事故重新打开。

    📏 反事实实测（基线树 origin/main vs 本分支，同一条命令、同一份输入）：
    6 行读数**只有 1 行不同**，就是本改动要修的那一行。这 5 条把那个结论钉住。

    ⚠️ 判据是 (intent, planned_intents, clarification_needed) 三元组，
    ⛔ 不是「有没有报错」—— 那两次事故的症状都不是异常，是**答成了别的**。
    """

    @staticmethod
    def _spec(code, dims, metrics, query):
        from smartbi.gold.restaurant import restaurant_intent as ri

        return ri._build_spec(code, query, confidence=0.9, tier="llm",
                              llm_dimensions=dims, llm_requested_metrics=metrics)

    def test_2026_07_30_purchase_incident_stays_closed(self):
        """「全部门店最近30天采购花了多少钱」—— planner 的 REQUISITION_TREND
        当年被一个纯 LLM 指标槽推翻过。它必须原样保留。"""
        spec = self._spec("RESTAURANT_OPS_REQUISITION_TREND", ("ingredient",),
                          ("sales_volume",), "全部门店最近30天采购花了多少钱")
        assert spec.intent == "RESTAURANT_OPS_REQUISITION_TREND"
        assert list(spec.planned_intents) == ["RESTAURANT_OPS_REQUISITION_TREND"]

    def test_2026_07_31_stocktake_incident_stays_closed(self):
        """「全部门店最近30天盘点亏了多少」—— 当年 STOCK_SHORTAGE 被 WASTAGE_TOP
        覆盖掉。⚠️ 这里的期望是**当前行为**（矛盾保留，交下游），⛔ 不是「理想行为」。"""
        spec = self._spec("RESTAURANT_OPS_STOCK_SHORTAGE", (), ("wastage",),
                          "全部门店最近30天盘点亏了多少")
        assert spec.intent == "RESTAURANT_OPS_STOCK_SHORTAGE"
        assert list(spec.planned_intents) == ["RESTAURANT_OPS_WASTAGE_TOP"]

    def test_comparison_repair_pr2798_is_untouched(self):
        """PR#2798 那条（环比驱动 ⇒ 计划赢）必须原样。"""
        spec = self._spec("RESTAURANT_OPS_TREND_ANALYSIS", (), ("revenue",),
                          "这个月比上个月好还是差")
        assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
        assert spec.planner_authority == "llm_contract_repair"

    def test_pr2799_case_is_untouched(self):
        """PR#2799 那条（标签能服务 ⇒ 标签赢）必须原样。"""
        spec = self._spec("RESTAURANT_OPS_STORE_MARGIN", ("store",),
                          ("recipe_cost",), "哪家店成本最高")
        assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
        assert spec.planner_authority == "llm"

    def test_a_plain_consistent_question_is_untouched(self):
        """阴性对照：本来就一致的句子, authority 不许被拼上修复后缀。"""
        spec = self._spec("RESTAURANT_OPS_SALES_SUMMARY", (), ("revenue",),
                          "最近生意好还是差")
        assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
        assert spec.planner_authority == "llm", (
            "本来就一致的句子被当成修复过了 —— authority 会误导下游与飞轮"
        )


class TestTheRepairedAuthorityStaysTrusted:
    """⚠️ `_execution_mismatch` 拿 `planner_authority` 当**白名单**在判。

    新造一个字符串会让它落到「这次的问题我没理解到有把握的程度」——
    修好了矛盾却换来另一句拒答。所以镜像必须复用已在白名单里的后缀。
    """

    @pytest.mark.parametrize("authority", [
        "llm_contract_repair",
        "validated_plan_cache_contract_repair",
        "trusted_context_contract_repair",
    ])
    def test_contract_repair_suffixes_are_whitelisted(self, authority):
        from smartbi.gold.restaurant.restaurant_intent import (
            TRUSTED_PLANNER_AUTHORITIES,
        )

        assert authority in TRUSTED_PLANNER_AUTHORITIES

    def test_the_mirror_does_not_invent_a_new_authority_string(self):
        """AST：镜像分支里对 `effective_planner_authority` 的赋值只许拼
        已白名单化的 `_contract_repair` 后缀。"""
        from smartbi.gold.restaurant import restaurant_intent as ri
        from smartbi.gold.restaurant.restaurant_intent import (
            TRUSTED_PLANNER_AUTHORITIES,
        )

        src = inspect.getsource(ri._build_spec)
        tree = ast.parse(src)
        literals = {
            node.value
            for node in ast.walk(tree)
            if isinstance(node, ast.Constant) and isinstance(node.value, str)
            and node.value.endswith("_contract_repair")
        }
        for lit in literals:
            assert lit in TRUSTED_PLANNER_AUTHORITIES or lit.startswith("_"), (
                f"{lit!r} 不在 TRUSTED_PLANNER_AUTHORITIES 里 —— "
                f"`_execution_mismatch` 会拿它当不可信而拒答"
            )
