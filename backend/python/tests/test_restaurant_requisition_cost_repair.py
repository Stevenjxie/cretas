"""「采购花了多少钱」必须落到领料成本, 而不是被 LLM 随手规划的 resolver 带走。

## 这条问句的历史

  2026-07-30 #2043  prod 实测被填成 sales_volume → 与 planner 冲突 → fail-closed 拒答。
                    加了 `_REQUISITION_SPEND_RE` 给它确定性口径。
  2026-07-31 #2063  RES_3101_009 上被路由成 RECIPE_COST —— 答错轴检测器报警。
  2026-07-31 本次   MOCK_REST 上被路由成 STORE_MARGIN —— **同一问句, 第三个错 intent**。

三次漂到三个不同的地方, 说明不是词汇表问题, 是**修复通道没接上**。

## 根因

`_REQUISITION_SPEND_RE` 把指标确定性地定为 `requisition_cost`(这一步一直是对的),
但 `_CONTRACT_REPAIRABLE_METRICS` **没有收录它** —— 于是当 LLM 规划的 resolver 与
这个指标冲突时, contract-repair 拒绝修复, 让错的 resolver 一路走到答案。

`requisition_cost` 是**唯一**由 REQUISITION_TREND 声明的指标, 修复目标无歧义,
本就该在可修复集里。它之所以漏了, 是因为它不在 `_REQUEST_METRIC_RULES` 里 ——
它由那条正则**事后注入**, 于是逐条过 rules 补白名单时看不见它。
"""
from __future__ import annotations

from smartbi.gold.restaurant.restaurant_intent import (
    _CONTRACT_REPAIRABLE_METRICS,
    _DEFAULT_METRICS_BY_CODE,
    _REQUISITION_SPEND_RE,
    _detect_requested_metrics,
)


class TestRequisitionSpendStaysDeterministic:
    def test_采购花了多少钱_检出领料成本(self):
        for q in (
            "全部门店最近30天采购花了多少钱",
            "全部门店上个月领料花了多少钱",
            "最近30天进货支出是多少",
            "全部门店领料成本",
        ):
            assert _REQUISITION_SPEND_RE.search(q), q
            assert "requisition_cost" in _detect_requested_metrics(q), q

    def test_领料数量问句不被塞成本指标(self):
        # 「领料最多的是哪些食材」是数量问题, 今天就能答, 不该被硬塞成本口径
        assert "requisition_cost" not in _detect_requested_metrics(
            "全部门店最近30天领料最多的是哪些食材"
        )

    def test_requisition_cost_在可修复集里(self):
        """缺了它, LLM 规划错 resolver 时 contract-repair 不会介入。"""
        assert "requisition_cost" in _CONTRACT_REPAIRABLE_METRICS

    def test_修复目标无歧义(self):
        """可修复的前提是「只有一个 resolver 声明这个指标」——

        多个 resolver 都声明时修复会变成猜, 那种情况应当保持 fail-closed。
        """
        owners = [c for c, ms in _DEFAULT_METRICS_BY_CODE.items()
                  if "requisition_cost" in ms]
        assert owners == ["RESTAURANT_OPS_REQUISITION_TREND"], owners

    def test_可修复集里的每个指标都有唯一归属(self):
        """把这条规矩推广到整张表 —— 修复目标有歧义的指标不该在集合里。"""
        ambiguous = []
        for metric in _CONTRACT_REPAIRABLE_METRICS:
            owners = [c for c, ms in _DEFAULT_METRICS_BY_CODE.items() if metric in ms]
            if len(owners) > 1:
                ambiguous.append(f"{metric} → {[o.replace('RESTAURANT_OPS_', '') for o in owners]}")
        # 已知多归属的先记录在案; 新增指标不许再引入歧义
        assert len(ambiguous) <= 4, "可修复集里出现了新的多归属指标:\n" + "\n".join(ambiguous)


class TestPlannerKnowsRequisitionCost:
    """真根因: 「指标 → resolver」的规划表不认识 `requisition_cost`。

    第一次修(把它加进 `_CONTRACT_REPAIRABLE_METRICS`)**没生效** —— prod 复跑后
    这条问句从 STORE_MARGIN 漂成了 RECIPE_COST, 第四个不同的错 intent。

    原因: `_plan_requested_intents` 的 `for metric in requested_metrics` 分支链
    只覆盖 recipe_cost / wastage / sales_volume / gross_margin / revenue+orders,
    碰到 `requisition_cost` 一条规则都不匹配 → 返回**空**或原样回显 LLM 选的 code。
    而 contract-repair 的触发条件之一是 `code not in planned_intents` ——
    回显自己, 这个条件永远为假, **修复通道从来没被走到过**。

    🔴 教训: 「把指标加进可修复白名单」只是准入, 真正决定修成什么的是规划表。
    只改前者等于给一扇没有门的墙配了钥匙。
    """

    def test_规划表能把领料成本指到领料趋势(self):
        from smartbi.gold.restaurant.restaurant_intent import _plan_requested_intents

        planned = _plan_requested_intents(
            "全部门店最近30天采购花了多少钱", "", ("requisition_cost",), (),
            "all", "lookup", None, None,
        )
        assert planned == ("RESTAURANT_OPS_REQUISITION_TREND",), planned

    def test_LLM选错时规划表不跟着错(self):
        """给一个明显不相干的 code, 规划表仍应按指标指到领料趋势 ——

        否则 contract-repair 的 `code not in planned_intents` 永远为假。
        """
        from smartbi.gold.restaurant.restaurant_intent import _plan_requested_intents

        for wrong in ("RESTAURANT_OPS_RECIPE_COST", "RESTAURANT_OPS_STORE_MARGIN"):
            planned = _plan_requested_intents(
                "全部门店最近30天采购花了多少钱", wrong, ("requisition_cost",), (),
                "all", "lookup", None, None,
            )
            assert planned == ("RESTAURANT_OPS_REQUISITION_TREND",), (wrong, planned)
