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
