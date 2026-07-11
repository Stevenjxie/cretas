# -*- coding: utf-8 -*-
"""P2 反回扣(anti-kickback) alerts unit tests (spec 2026-07-11):

collect_alerts derives structured alerts SOLELY from grounded FactBook signals
(supplier_anomaly / period_comparison.cost_ratio) — never fabricated. A wrong
alert would falsely accuse a supplier or imply kickback/theft, so every case
below asserts both the honest wording AND the honest absence (empty list) when
no real signal is present.

All pure (no live DB) — mirrors test_synthesis_ditan_integration.py style.
"""
from smartbi.agent.factbook import FactBook
from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine


def _engine() -> ComprehensiveSynthesisEngine:
    # collect_alerts touches no instance state — __new__ mirrors the existing
    # test_plan_dimensions_no_dish_margin_keyerror pattern (no DB pool needed).
    return ComprehensiveSynthesisEngine.__new__(ComprehensiveSynthesisEngine)


def test_no_signal_returns_empty_alerts():
    """Honest absence: neither supplier_anomaly nor period_comparison present."""
    fb = FactBook(period="x")
    assert ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb) == []


def test_supplier_anomaly_high_risk_alert():
    fb = FactBook(period="x", supplier_anomaly={"anomalies": [
        {"ingredientName": "青菜", "supplierName": "老王蔬菜", "deltaPct": 25.0,
         "direction": "UP", "riskLevel": "HIGH", "oldPrice": 2.0, "newPrice": 2.5},
    ]})
    alerts = ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb)
    assert len(alerts) == 1
    a = alerts[0]
    assert a["type"] == "supplier_price_anomaly"
    assert a["level"] == "high"
    assert "青菜" in a["title"]
    assert "老王蔬菜" in a["detail"] and "青菜" in a["detail"]
    assert "25.0%" in a["detail"]
    assert "威慑非处罚" not in a["detail"]  # detail wording is "记录趋势要求解释，非处罚"
    assert "记录趋势要求解释，非处罚" in a["detail"]
    assert "高风险" in a["detail"]


def test_supplier_anomaly_medium_risk_alert():
    fb = FactBook(period="x", supplier_anomaly={"anomalies": [
        {"ingredientName": "洗洁精", "supplierName": "洁净供应", "deltaPct": 36.4,
         "direction": "UP", "riskLevel": "MEDIUM", "oldPrice": 110.0, "newPrice": 150.0},
    ]})
    alerts = ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb)
    assert len(alerts) == 1
    assert alerts[0]["level"] == "medium"
    assert "中风险" in alerts[0]["detail"]


def test_supplier_anomaly_multiple_anomalies_all_included():
    fb = FactBook(period="x", supplier_anomaly={"anomalies": [
        {"ingredientName": "青菜", "supplierName": "老王蔬菜", "deltaPct": 25.0,
         "direction": "UP", "riskLevel": "HIGH", "oldPrice": 2.0, "newPrice": 2.5},
        {"ingredientName": "洗洁精", "supplierName": "洁净供应", "deltaPct": -10.0,
         "direction": "DOWN", "riskLevel": "MEDIUM", "oldPrice": 150.0, "newPrice": 135.0},
    ]})
    alerts = ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb)
    assert len(alerts) == 2
    assert {a["level"] for a in alerts} == {"high", "medium"}
    # DOWN direction rendered honestly too (降 not 涨)
    down_alert = next(a for a in alerts if "洗洁精" in a["title"])
    assert "降" in down_alert["detail"]


def test_supplier_anomaly_unknown_risk_level_skipped_not_guessed():
    """An anomaly with an unrecognized riskLevel must be skipped, not defaulted
    to a level — guessing a severity would be a fabricated accusation."""
    fb = FactBook(period="x", supplier_anomaly={"anomalies": [
        {"ingredientName": "青菜", "supplierName": "老王蔬菜", "deltaPct": 25.0,
         "direction": "UP", "riskLevel": "LOW", "oldPrice": 2.0, "newPrice": 2.5},
    ]})
    assert ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb) == []


def test_cost_ratio_rising_medium_below_threshold():
    pc = {"cost_ratio": {"current": 32.0, "mom_pct": 0.6, "mom_available": True,
                          "yoy_pct": None, "yoy_available": False}}
    fb = FactBook(period="x", period_comparison=pc)
    alerts = ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb)
    assert len(alerts) == 1
    a = alerts[0]
    assert a["type"] == "cost_ratio_rising"
    assert a["level"] == "medium"
    assert "领料成本率环比上升" in a["title"]
    assert "+0.6个百分点" in a["detail"]
    assert "32.0%" in a["detail"]
    assert "疑似用料增加/漏损/回扣" in a["detail"]
    assert "非配方理论COGS" in a["detail"]


def test_cost_ratio_rising_high_at_threshold():
    pc = {"cost_ratio": {"current": 35.0, "mom_pct": 1.0, "mom_available": True,
                          "yoy_pct": None, "yoy_available": False}}
    fb = FactBook(period="x", period_comparison=pc)
    alerts = ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb)
    assert len(alerts) == 1
    assert alerts[0]["level"] == "high"


def test_cost_ratio_falling_no_alert():
    """Honest: a FALLING cost ratio (mom_pct < 0) is good news, not an alert."""
    pc = {"cost_ratio": {"current": 28.0, "mom_pct": -2.0, "mom_available": True,
                          "yoy_pct": None, "yoy_available": False}}
    fb = FactBook(period="x", period_comparison=pc)
    assert ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb) == []


def test_cost_ratio_unavailable_no_alert():
    """Honest degradation: mom_available False (no prior window data) → no alert,
    never fabricated from a missing comparison."""
    pc = {"cost_ratio": {"current": 30.0, "mom_pct": None, "mom_available": False,
                          "yoy_pct": None, "yoy_available": False}}
    fb = FactBook(period="x", period_comparison=pc)
    assert ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb) == []


def test_cost_ratio_zero_no_alert():
    """mom_pct == 0 (flat) is not 'rising' — only strictly > 0 triggers."""
    pc = {"cost_ratio": {"current": 30.0, "mom_pct": 0.0, "mom_available": True,
                          "yoy_pct": None, "yoy_available": False}}
    fb = FactBook(period="x", period_comparison=pc)
    assert ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb) == []


def test_both_signals_produce_two_alerts():
    fb = FactBook(
        period="x",
        supplier_anomaly={"anomalies": [
            {"ingredientName": "青菜", "supplierName": "老王蔬菜", "deltaPct": 25.0,
             "direction": "UP", "riskLevel": "HIGH", "oldPrice": 2.0, "newPrice": 2.5},
        ]},
        period_comparison={"cost_ratio": {
            "current": 32.0, "mom_pct": 2.5, "mom_available": True,
            "yoy_pct": None, "yoy_available": False,
        }},
    )
    alerts = ComprehensiveSynthesisEngine.collect_alerts(_engine(), fb)
    assert len(alerts) == 2
    assert {a["type"] for a in alerts} == {"supplier_price_anomaly", "cost_ratio_rising"}
