"""阶段2 — 事实对账器单测: 安全(不误伤合法数)是首要, 其次哨兵+回填有效。"""
from __future__ import annotations

from decimal import Decimal

from smartbi.services.insights.fact_reconciler import (
    build_factbook_from_metrics,
    reconcile_insights,
)


def _ins(text, **kw):
    return [{"type": "kpi", "text": text, **kw}]


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


if __name__ == "__main__":
    import pytest
    raise SystemExit(pytest.main([__file__, "-v"]))
