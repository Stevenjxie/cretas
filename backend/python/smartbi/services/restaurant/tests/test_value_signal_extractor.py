"""#56 价值可视化回馈回路 — ValueSignalExtractor 单元测试。

覆盖 spec §测试计划:
  - 邓总火锅算例: cost_rigidity 0.56 / revenue 731047, labor_cost_ratio 32.51 vs
    median 30.0 → 月度人工节省 ≈ 18349 (对 playbook expected_savings_formula)。
  - 食材 46% (中位 42%) 营收 50万 → ≈ 20000 (delta_pp × revenue)。
  - 数据不足 → None (不入 signal_sources, 禁用 0 填 null)。
  - RxAction 金额正则: "人工成本 -¥3.2K/月" → 3200 / "额外营收 ≈ ¥15K" → 15000 /
    "月度净利润 +¥4K" → 4000 / "月度损耗 -¥2.5K" → 2500 / 万 单位 → ×10000。
  - null 防降级: 任何无法计算的信号金额 = None, 不是 0。
"""
from __future__ import annotations

from smartbi.services.restaurant.value_signal_extractor import (
    ValueSignal,
    extract_from_diagnosis,
    extract_rx_action_impact,
    parse_amount_token,
)


# ── RxAction 金额正则提取 ─────────────────────────────────


def test_parse_amount_k_suffix():
    assert parse_amount_token("人工成本 -¥3.2K/月") == 3200.0


def test_parse_amount_w_suffix():
    # ¥1.5W = 1.5 万 = 15000
    assert parse_amount_token("营收 ¥1.5W") == 15000.0


def test_parse_amount_wan_chinese():
    assert parse_amount_token("月省 ¥5万") == 50000.0


def test_parse_amount_approx_k():
    assert parse_amount_token("额外营收 ≈ ¥15K, 同时释放预收款负债") == 15000.0


def test_parse_amount_plain_with_comma():
    assert parse_amount_token("单月可节省 ¥18,349") == 18349.0


def test_parse_amount_fullwidth_yen():
    # 全角 ￥
    assert parse_amount_token("月度净利润 +￥4K") == 4000.0


def test_parse_amount_no_currency_returns_none():
    # 没有 ¥/￥ 前缀 → 不提 (避免误抓百分比/数量)
    assert parse_amount_token("cost_rigidity 从 0.561 → 0.68") is None


def test_parse_amount_empty_returns_none():
    assert parse_amount_token("") is None
    assert parse_amount_token(None) is None


def test_extract_rx_action_impact_picks_first_amount():
    rx = {"expectedImpact": "人工成本 -¥3.2K/月, cost_rigidity 从 0.561 → 0.68"}
    assert extract_rx_action_impact(rx) == 3200.0


def test_extract_rx_action_impact_snake_case_field():
    # 兼容 expected_impact (dataclass 风格) 和 expectedImpact (to_dict 风格)
    rx = {"expected_impact": "额外营收 ≈ ¥15K"}
    assert extract_rx_action_impact(rx) == 15000.0


def test_extract_rx_action_impact_no_amount_returns_none():
    rx = {"expectedImpact": "BOM 精度 ±15% → ±8%, 为后续损溢分析打基础"}
    assert extract_rx_action_impact(rx) is None


# ── 食材成本改善空间: delta_pp × revenue ─────────────────


def test_food_cost_savings_deng_example():
    """食材 46% (中位 42%) 营收 50万 → delta_pp=4.0 → 4/100 * 500000 = 20000。"""
    diagnoses = [
        {
            "metricKey": "food_cost_ratio",
            "metricNameZh": "食材成本率",
            "actualValue": 46.0,
            "benchmarkMedian": 42.0,
            "deltaPp": 4.0,
            "severity": "warning",
            "rxActions": [],
        }
    ]
    signals = extract_from_diagnosis(diagnoses, shrinkage_report=None, revenue_current=500000.0)
    food = next((s for s in signals if s.signal == "food_cost_savings"), None)
    assert food is not None
    assert food.amount is not None
    assert abs(food.amount - 20000.0) < 0.01
    assert food.kind == "estimate"


def test_labor_rigidity_deng_example():
    """邓总火锅: labor_cost_ratio 32.51 vs median 30.0 (deltaPp 2.51), 营收 731047
    → 月度 ≈ 18349 (playbook expected_savings_formula: revenue * excess_pp/100)。"""
    diagnoses = [
        {
            "metricKey": "labor_cost_ratio",
            "metricNameZh": "人力成本率",
            "actualValue": 32.51,
            "benchmarkMedian": 30.0,
            "deltaPp": 2.51,
            "severity": "warning",
            "rxActions": [],
        }
    ]
    signals = extract_from_diagnosis(diagnoses, shrinkage_report=None, revenue_current=731047.52)
    labor = next((s for s in signals if s.signal == "labor_rigidity"), None)
    assert labor is not None
    assert labor.amount is not None
    # 731047.52 * 2.51 / 100 = 18349.29
    assert abs(labor.amount - 18349.29) < 1.0
    assert labor.kind == "estimate"


def test_discount_savings_delta_pp():
    """折扣率改善空间: discount_rate_high deltaPp 3.0, 营收 40万 → 12000。"""
    diagnoses = [
        {
            "metricKey": "discount_rate",
            "metricNameZh": "折扣率",
            "actualValue": 18.0,
            "benchmarkMedian": 15.0,
            "deltaPp": 3.0,
            "severity": "warning",
            "rxActions": [],
        }
    ]
    signals = extract_from_diagnosis(diagnoses, shrinkage_report=None, revenue_current=400000.0)
    disc = next((s for s in signals if s.signal == "discount_savings"), None)
    assert disc is not None
    assert abs(disc.amount - 12000.0) < 0.01


# ── 数据不足 → None, 禁降级填 0 ───────────────────────────


def test_food_cost_no_revenue_amount_is_none_not_zero():
    """营收缺失 → 食材改善金额 = None (禁用 0 填 null)。"""
    diagnoses = [
        {
            "metricKey": "food_cost_ratio",
            "actualValue": 46.0,
            "benchmarkMedian": 42.0,
            "deltaPp": 4.0,
            "severity": "warning",
            "rxActions": [],
        }
    ]
    signals = extract_from_diagnosis(diagnoses, shrinkage_report=None, revenue_current=None)
    food = next((s for s in signals if s.signal == "food_cost_savings"), None)
    assert food is not None
    assert food.amount is None  # NOT 0.0


def test_delta_pp_not_worse_skips_signal():
    """delta_pp <= 0 (食材成本率低于中位, 是好事) → 不产生节省信号。"""
    diagnoses = [
        {
            "metricKey": "food_cost_ratio",
            "actualValue": 40.0,
            "benchmarkMedian": 42.0,
            "deltaPp": -2.0,
            "severity": "info",
            "rxActions": [],
        }
    ]
    signals = extract_from_diagnosis(diagnoses, shrinkage_report=None, revenue_current=500000.0)
    food = [s for s in signals if s.signal == "food_cost_savings"]
    assert food == []


# ── 档口损溢: shrinkage totalVarianceAmount (本月实测) ─────


def test_shrinkage_variance_signal():
    shrinkage = {"totalVarianceAmount": 12500.0, "topOffenders": [{"department": "凉菜"}]}
    signals = extract_from_diagnosis([], shrinkage_report=shrinkage, revenue_current=500000.0)
    shr = next((s for s in signals if s.signal == "shrinkage_variance"), None)
    assert shr is not None
    assert abs(shr.amount - 12500.0) < 0.01
    assert shr.kind == "measured"  # 本月实测, NOT estimate


def test_shrinkage_negative_variance_is_savings_not_signal():
    """负 variance (实际低于标准, 是省钱) → 不作为"超标损溢"信号 (只报正超标)。"""
    shrinkage = {"totalVarianceAmount": -3000.0}
    signals = extract_from_diagnosis([], shrinkage_report=shrinkage, revenue_current=500000.0)
    shr = [s for s in signals if s.signal == "shrinkage_variance"]
    assert shr == []


def test_shrinkage_zero_variance_no_signal():
    shrinkage = {"totalVarianceAmount": 0.0}
    signals = extract_from_diagnosis([], shrinkage_report=shrinkage, revenue_current=500000.0)
    assert [s for s in signals if s.signal == "shrinkage_variance"] == []


# ── RxAction 聚合进 signals ──────────────────────────────


def test_rx_action_signals_extracted():
    diagnoses = [
        {
            "metricKey": "labor_cost_ratio",
            "deltaPp": 2.51,
            "severity": "warning",
            "rxActions": [
                {"id": "CR-A01", "title": "砍周二/周三晚班帮工",
                 "expectedImpact": "人工成本 -¥3.2K/月, cost_rigidity 从 0.561 → 0.68"},
                {"id": "CR-A05", "title": "BOM 成本卡标准化",
                 "expectedImpact": "BOM 精度 ±15% → ±8%"},
            ],
        }
    ]
    signals = extract_from_diagnosis(diagnoses, shrinkage_report=None, revenue_current=731047.0)
    rx_signals = [s for s in signals if s.signal == "rx_action"]
    # 只有有金额的 RxAction 进 signals (CR-A01 有, CR-A05 无)
    assert len(rx_signals) == 1
    assert abs(rx_signals[0].amount - 3200.0) < 0.01
    assert rx_signals[0].kind == "estimate"


def test_value_signal_dataclass_to_dict():
    sig = ValueSignal(signal="food_cost_savings", label="食材成本改善空间",
                      amount=20000.0, kind="estimate", period="month")
    d = sig.to_dict()
    assert d == {
        "signal": "food_cost_savings",
        "label": "食材成本改善空间",
        "amount": 20000.0,
        "kind": "estimate",
        "period": "month",
    }


def test_value_signal_none_amount_preserved_in_dict():
    sig = ValueSignal(signal="food_cost_savings", label="食材成本改善空间",
                      amount=None, kind="estimate", period="month")
    assert sig.to_dict()["amount"] is None  # not 0
