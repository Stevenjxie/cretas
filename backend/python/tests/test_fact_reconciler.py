"""阶段2 — 事实对账器单测: 安全(不误伤合法数)是首要, 其次哨兵+回填有效。"""
from __future__ import annotations

from decimal import Decimal

import pandas as pd
import pytest

from smartbi.services.insights.fact_reconciler import (
    build_factbook_from_metrics,
    overall_ratio_facts,
    overall_factory_facts,
    reconcile_insights,
)


def _ins(text, **kw):
    return [{"type": "kpi", "text": text, **kw}]


# ── 边界 guard: 被实体限定的指标名不纠正 (整体 35% 不改 门店甲的 38%) ──────────
def test_guard_skips_entity_qualified_metric():
    metrics = [{"name": "毛利率", "value": 35, "unit": "%", "success": True}]
    # "门店甲毛利率38%" 中的毛利率被"甲"限定 = 某店的, 不是整体 → 不改
    out = reconcile_insights(_ins("门店甲毛利率38%，门店乙更高"), metrics)
    assert "门店甲毛利率38%" in out[0]["text"]


def test_guard_corrects_clean_boundary_mention():
    metrics = [{"name": "毛利率", "value": 35, "unit": "%", "success": True}]
    # 句首干净边界 = 整体口径 → 纠正
    assert "毛利率35%" in reconcile_insights(_ins("毛利率38%，高于行业"), metrics)[0]["text"]
    # 标点后干净边界 → 纠正
    assert "毛利率35%" in reconcile_insights(_ins("整体看，毛利率38%偏低"), metrics)[0]["text"]


# ── Python 自算整体口径比率 (毛利率/净利率) ──────────────────────────────────
def test_overall_ratio_facts_from_clean_pnl():
    df = pd.DataFrame({"项目": ["营业收入", "营业成本", "净利润"], "金额": [1000, 600, 80]})
    d = {f["name"]: f["value"] for f in overall_ratio_facts(df)}
    assert d["净利率"] == 8.0          # 80/1000
    assert d["毛利率"] == 40.0         # (1000-600)/1000


def test_overall_ratio_facts_ambiguous_returns_empty():
    # 营业收入 匹配到 2 行 (营业收入 + 营业收入合计) → 歧义 → 放弃
    df = pd.DataFrame({"项目": ["营业收入", "营业收入合计", "净利润"], "金额": [1000, 1000, 80]})
    assert overall_ratio_facts(df) == []


def test_overall_ratio_facts_no_revenue_returns_empty():
    df = pd.DataFrame({"项目": ["产量", "良品数"], "值": [100, 98]})
    assert overall_ratio_facts(df) == []


def test_overall_ratio_facts_feeds_reconcile():
    # 端到端: Python 算出毛利率40%, LLM 误报48% (>5%偏差) → 纠正
    df = pd.DataFrame({"项目": ["营业收入", "营业成本"], "金额": [1000, 600]})
    facts = overall_ratio_facts(df)
    out = reconcile_insights(_ins("毛利率48%，表现不错"), facts)
    assert "毛利率40%" in out[0]["text"] and "48%" not in out[0]["text"]


def test_overall_ratio_small_divergence_within_tolerance_untouched():
    # 42% vs 40% 偏差<5% → 不改 (避免对正常四舍五入误差过度纠正)
    df = pd.DataFrame({"项目": ["营业收入", "营业成本"], "金额": [1000, 600]})
    out = reconcile_insights(_ins("毛利率42%"), overall_ratio_facts(df))
    assert "毛利率42%" in out[0]["text"]


# ── 安全: 无 metrics / 不匹配 → 完全不动 ──────────────────────────────────
def test_noop_when_no_metrics():
    ins = _ins("营业收入¥3,200,000，毛利率38%")
    out = reconcile_insights([dict(ins[0])], None)
    assert out[0]["text"] == ins[0]["text"]
    out2 = reconcile_insights([dict(ins[0])], [])
    assert out2[0]["text"] == ins[0]["text"]


def test_noop_when_metric_name_not_followed_by_number():
    metrics = [{"name": "毛利率", "value": 35, "unit": "%", "success": True}]
    # 文本提到毛利率但没紧跟数字 (数字在别处) → 不动
    ins = _ins("毛利率方面表现良好，整体达到了不错的水平 38 分")
    out = reconcile_insights([dict(ins[0])], metrics)
    assert out[0]["text"] == ins[0]["text"]


def test_noop_on_matching_number_within_tolerance():
    metrics = [{"name": "营业收入", "value": 3200000, "unit": "元", "success": True}]
    ins = _ins("营业收入¥3,200,000[毛]，同比增长")
    out = reconcile_insights([dict(ins[0])], metrics)
    assert "3,200,000" in out[0]["text"]  # 一致 → 不改
    assert "数据实际" not in out[0]["text"] and "需核实" not in out[0]["text"]


def test_derived_number_not_matching_metric_name_untouched():
    # 两店合计是合法推导, 不是任何 metric 名 → 不动 (避免误伤)
    metrics = [{"name": "门店甲营收", "value": 110000, "unit": "元", "success": True}]
    ins = _ins("两店合计¥208,000[净]，其中门店甲营收¥110,000")
    out = reconcile_insights([dict(ins[0])], metrics)
    assert "208,000" in out[0]["text"]   # 推导数不动
    assert "110,000" in out[0]["text"]   # 门店甲营收一致也不动


# ── 哨兵: 无数据指标被编了数字 → 加注 + 降 confidence ──────────────────────
def test_sentinel_flags_fabricated_number_on_missing_metric():
    metrics = [{"name": "净利率", "value": None, "unit": "%", "success": False}]
    ins = _ins("净利率8.5%，盈利能力健康", confidence=0.9)
    out = reconcile_insights([dict(ins[0])], metrics)
    assert "需核实" in out[0]["text"]
    assert "净利率数据不足" in out[0]["text"]
    assert out[0]["confidence"] <= 0.5


def test_sentinel_idempotent():
    metrics = [{"name": "净利率", "value": None, "unit": "%", "success": False}]
    ins = _ins("净利率8.5%", confidence=0.9)
    once = reconcile_insights([dict(ins[0])], metrics)
    twice = reconcile_insights([dict(once[0])], metrics)
    # 不重复加注
    assert twice[0]["text"].count("需核实") == 1


# ── 回填: LLM misquote → 用确定值纠正 ────────────────────────────────────
def test_backfill_corrects_divergent_number():
    metrics = [{"name": "毛利率", "value": 35, "unit": "%", "success": True}]
    ins = _ins("毛利率38%，高于行业")  # LLM 报 38, 实际 35 (偏差 >5%)
    out = reconcile_insights([dict(ins[0])], metrics)
    assert "毛利率35%" in out[0]["text"]
    assert "38%" not in out[0]["text"]


def test_backfill_idempotent_after_correction():
    metrics = [{"name": "毛利率", "value": 35, "unit": "%", "success": True}]
    ins = _ins("毛利率38%")
    once = reconcile_insights([dict(ins[0])], metrics)
    twice = reconcile_insights([dict(once[0])], metrics)
    assert once[0]["text"] == twice[0]["text"]  # 已纠正 → 再跑不动


# ── D5: 中文万/亿归一化, 避免假偏差 ──────────────────────────────────────
def test_wan_yi_normalization_no_false_divergence():
    metrics = [{"name": "营业收入", "value": 3200000, "unit": "元", "success": True}]
    ins = _ins("营业收入320万元，稳健")  # 320万 = 3,200,000 → 一致, 不该改
    out = reconcile_insights([dict(ins[0])], metrics)
    assert "320万" in out[0]["text"]  # 不动
    assert "数据实际" not in out[0]["text"]


def test_factbook_build_distinguishes_missing():
    fb = build_factbook_from_metrics([
        {"name": "营收", "value": 100, "success": True},
        {"name": "净利", "value": None, "success": False},
        {"name": "x", "success": True},  # name too short → skipped
    ])
    names = {f.name: f.value for f in fb.facts}
    assert names["营收"] == Decimal("100")
    assert names["净利"] is None
    assert "x" not in names


# ── A5: 工厂洞察 reconciler — overall_factory_facts ─────────────────────
def test_factory_yield_rate_computed():
    """出成率 = 产出/投入*100, 整体口径干净边界才纠正。"""
    df = pd.DataFrame({"工序": ["原料投入", "实际产出"], "重量": [1000.0, 540.0]})
    facts = overall_factory_facts(df)
    d = {f["name"]: f["value"] for f in facts}
    assert "出成率" in d
    assert d["出成率"] == pytest.approx(54.0, abs=0.1)


def test_factory_yield_rate_zero_input_returns_empty():
    """投入为0 → 不算出成率 (除零防护)。"""
    df = pd.DataFrame({"工序": ["原料投入", "实际产出"], "重量": [0.0, 100.0]})
    facts = overall_factory_facts(df)
    names = [f["name"] for f in facts]
    assert "出成率" not in names


def test_factory_cost_structure_material_ratio():
    """材料成本占比 = 材料成本/总成本*100。"""
    df = pd.DataFrame({"项目": ["总成本", "材料成本", "人工成本"], "金额": [10000.0, 6000.0, 2000.0]})
    facts = overall_factory_facts(df)
    d = {f["name"]: f["value"] for f in facts}
    assert "材料成本占比" in d
    assert d["材料成本占比"] == pytest.approx(60.0, abs=0.1)
    assert "人工成本占比" in d
    assert d["人工成本占比"] == pytest.approx(20.0, abs=0.1)


def test_factory_facts_feeds_reconcile_corrects_yield():
    """端到端: Python 算出出成率54%, LLM 误报70% (>5%偏差) → 纠正。"""
    df = pd.DataFrame({"工序": ["原料投入", "实际产出"], "重量": [1000.0, 540.0]})
    facts = overall_factory_facts(df)
    out = reconcile_insights(_ins("出成率70%，高于预期"), facts)
    assert "出成率54%" in out[0]["text"]
    assert "70%" not in out[0]["text"]


def test_factory_facts_within_tolerance_untouched():
    """52% vs 54% 偏差<5% → 不改。"""
    df = pd.DataFrame({"工序": ["原料投入", "实际产出"], "重量": [1000.0, 540.0]})
    facts = overall_factory_facts(df)
    out = reconcile_insights(_ins("出成率52%"), facts)
    assert "出成率52%" in out[0]["text"]


def test_factory_sentinel_flags_missing_yield():
    """yield 指标无数据但 LLM 编了数字 → 加注 + 降confidence。"""
    metrics = [{"name": "出成率", "value": None, "unit": "%", "success": False}]
    out = reconcile_insights(_ins("出成率75.5%，产线高效", confidence=0.9), metrics)
    assert "需核实" in out[0]["text"]
    assert out[0]["confidence"] <= 0.5


def test_factory_facts_empty_df_returns_empty():
    """空 DataFrame → 空列表 (no-op)。"""
    assert overall_factory_facts(None) == []
    assert overall_factory_facts(pd.DataFrame()) == []


def test_factory_facts_ambiguous_row_skips_that_metric():
    """多行匹配投入 → 歧义 → 跳过出成率但不影响其他指标。"""
    df = pd.DataFrame(
        {"项目": ["原料投入", "辅料投入", "实际产出", "总成本", "材料成本"],
         "值": [800.0, 200.0, 540.0, 5000.0, 3000.0]}
    )
    facts = overall_factory_facts(df)
    names = [f["name"] for f in facts]
    # "原料投入" 和 "辅料投入" 都包含"投入" → 歧义 → 出成率不算
    assert "出成率" not in names
    # 材料成本占比能算(总成本唯一, 材料成本唯一)
    assert "材料成本占比" in names


if __name__ == "__main__":
    import pytest
    raise SystemExit(pytest.main([__file__, "-v"]))
