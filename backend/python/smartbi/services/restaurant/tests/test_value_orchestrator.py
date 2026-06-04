"""#56 价值可视化回馈回路 — orchestrator 单元测试。

compute_signals_from_inputs: financial_data + shrinkage → signals + counts。
端到端验证 邓总火锅算例 (cost_rigidity 0.56 输入 → labor 信号年化 + critical 计数)。
"""
from __future__ import annotations

from smartbi.services.restaurant.value_orchestrator import compute_signals_from_inputs


def test_orchestrator_deng_huoguo_end_to_end():
    """邓总火锅 2026-02: 营收 731048 (环比 -47%), 人工 237660 → cost_rigidity 0.561。

    labor_cost_ratio 32.51% vs median 30 → labor_rigidity 信号 (revenue*deltaPp/100)。
    cost_rigidity 0.561 落 registry warning 区间 (>=0.5 and <0.85), 携带 5 条 rx_actions。
    """
    financial_data = {
        "current": {"revenue": 731048, "labor_cost": 237660, "food_cost": 336000},
        "previous": {"revenue": 1390503, "labor_cost": 323805, "food_cost": 560000},
    }
    result = compute_signals_from_inputs(
        financial_data=financial_data, sub_sector="火锅", shrinkage_report=None,
    )
    signals = result["signals"]
    # labor_cost_ratio = 237660/731048*100 = 32.51% vs median 30 → labor signal
    labor = next((s for s in signals if s.signal == "labor_rigidity"), None)
    assert labor is not None
    assert labor.amount is not None
    # 731048 * (32.51 - 30.0)/100 ≈ 18349
    assert abs(labor.amount - 18349) < 200  # 容差 (median 口径计算)
    assert result["diagnosisCount"] >= 1   # cost_rigidity diagnosed
    # cost_rigidity 0.561 → registry warning (非 critical, 阈值 <0.5); rx_actions 应非空
    assert result["rxActionCount"] >= 1


def test_orchestrator_with_shrinkage():
    financial_data = {
        "current": {"revenue": 500000, "labor_cost": 150000, "food_cost": 230000},
        "previous": {"revenue": 900000, "labor_cost": 250000, "food_cost": 400000},
    }
    shrinkage = {"totalVarianceAmount": 12500.0}
    result = compute_signals_from_inputs(
        financial_data=financial_data, sub_sector="火锅", shrinkage_report=shrinkage,
    )
    shr = next((s for s in result["signals"] if s.signal == "shrinkage_variance"), None)
    assert shr is not None
    assert abs(shr.amount - 12500.0) < 0.01
    assert shr.kind == "measured"


def test_orchestrator_no_data_empty_signals():
    """无 financial_data → 空信号 (不报假数据)。"""
    result = compute_signals_from_inputs(
        financial_data={}, sub_sector="火锅", shrinkage_report=None,
    )
    assert result["signals"] == []
    assert result["diagnosisCount"] == 0
    assert result["criticalCount"] == 0


def test_orchestrator_revenue_extracted():
    """revenue_current 应从 financial_data.current.revenue 取, 用于比率信号。"""
    financial_data = {
        "current": {"revenue": 500000, "food_cost": 230000},
        "previous": {"revenue": 480000, "food_cost": 220000},
    }
    result = compute_signals_from_inputs(
        financial_data=financial_data, sub_sector="火锅", shrinkage_report=None,
    )
    # food_cost_ratio = 230000/500000*100 = 46% vs median 43 → 偏高 → food signal
    food = next((s for s in result["signals"] if s.signal == "food_cost_savings"), None)
    assert food is not None
    assert food.amount is not None
    # 500000 * (46-43)/100 = 15000
    assert abs(food.amount - 15000) < 200
