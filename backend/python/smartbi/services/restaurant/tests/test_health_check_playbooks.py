"""G4 — Unit tests for the 3 new health-check playbooks.

Verifies the YAML files load, expose complete rx_actions (all 8 fields), and
that DiagnosticsEngine produces the correct severity for the inline-threshold
metrics (channel_collection_rate / review_score_decline / delivery_dependency).
"""
from __future__ import annotations

from pathlib import Path

import yaml

from smartbi.shared.diagnostics_engine import DiagnosticsEngine, RxAction

_PLAYBOOK_DIR = (
    Path(__file__).resolve().parents[3]
    / "knowledge"
    / "restaurant"
    / "playbooks"
)

_REQUIRED_RX_FIELDS = (
    "id", "title", "description", "owner",
    "timeframe", "priority", "effort", "expected_impact",
)


def _load_playbook(name: str) -> dict:
    path = _PLAYBOOK_DIR / f"{name}.yaml"
    assert path.exists(), f"playbook YAML missing: {path}"
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


# ── YAML load + structure ─────────────────────────────────────────


def test_channel_collection_rate_low_playbook_loads():
    pb = _load_playbook("channel_collection_rate_low")
    assert pb["diagnosis_code"] == "channel_collection_rate_low"
    assert pb["trigger_metric"] == "channel_collection_rate"


def test_review_score_decline_playbook_loads():
    pb = _load_playbook("review_score_decline")
    assert pb["diagnosis_code"] == "review_score_decline"
    assert pb["trigger_metric"] == "review_score_decline"


def test_delivery_dependency_high_playbook_loads():
    pb = _load_playbook("delivery_dependency_high")
    assert pb["diagnosis_code"] == "delivery_dependency_high"
    assert pb["trigger_metric"] == "delivery_dependency"


def test_channel_collection_rate_low_has_complete_rx_actions():
    pb = _load_playbook("channel_collection_rate_low")
    rx = pb.get("rx_actions") or []
    assert len(rx) >= 3, f"expected >=3 rx_actions, got {len(rx)}"
    for item in rx:
        missing = [f for f in _REQUIRED_RX_FIELDS if not item.get(f)]
        assert not missing, f"rx_action {item.get('id')} missing fields {missing}"
        assert item["priority"] in ("P0", "P1", "P2")
        assert item["effort"] in ("low", "medium", "high")


def test_review_score_decline_has_complete_rx_actions():
    pb = _load_playbook("review_score_decline")
    rx = pb.get("rx_actions") or []
    assert len(rx) >= 3
    for item in rx:
        missing = [f for f in _REQUIRED_RX_FIELDS if not item.get(f)]
        assert not missing, f"rx_action {item.get('id')} missing fields {missing}"


def test_delivery_dependency_high_has_complete_rx_actions():
    pb = _load_playbook("delivery_dependency_high")
    rx = pb.get("rx_actions") or []
    assert len(rx) >= 3
    for item in rx:
        missing = [f for f in _REQUIRED_RX_FIELDS if not item.get(f)]
        assert not missing, f"rx_action {item.get('id')} missing fields {missing}"


# ── DiagnosticsEngine integration (inline thresholds, 0-1 scale) ──


def test_diagnostics_engine_channel_collection_rate_below_critical():
    """0.65 (< 0.70) → critical, with structured rx_actions from the new playbook."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"channel_collection_rate": 0.65})
    assert len(diags) == 1
    d = diags[0]
    assert d.metric_key == "channel_collection_rate"
    assert d.severity == "critical"
    assert d.playbook_id == "channel_collection_rate_low"
    assert len(d.rx_actions) >= 3
    assert all(isinstance(a, RxAction) for a in d.rx_actions)


def test_diagnostics_engine_channel_collection_rate_warning():
    """0.74 (0.70 <= v < 0.78) → warning."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"channel_collection_rate": 0.74})
    assert len(diags) == 1
    assert diags[0].severity == "warning"


def test_diagnostics_engine_channel_collection_rate_healthy_returns_none():
    """0.85 (>= 0.78) → healthy → engine drops it (no diagnosis)."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"channel_collection_rate": 0.85})
    assert diags == []


def test_diagnostics_engine_review_score_decline_warning():
    """-0.03 (>= -0.05 and < -0.02) → warning."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"review_score_decline": -0.03})
    assert len(diags) == 1
    assert diags[0].metric_key == "review_score_decline"
    assert diags[0].severity == "warning"
    assert diags[0].playbook_id == "review_score_decline"
    assert len(diags[0].rx_actions) >= 3


def test_diagnostics_engine_review_score_decline_critical():
    """-0.08 (< -0.05) → critical."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"review_score_decline": -0.08})
    assert len(diags) == 1
    assert diags[0].severity == "critical"


def test_diagnostics_engine_delivery_dependency_critical():
    """0.75 (>= 0.70) → critical, higher_is_worse=True."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"delivery_dependency": 0.75})
    assert len(diags) == 1
    assert diags[0].metric_key == "delivery_dependency"
    assert diags[0].severity == "critical"
    assert diags[0].playbook_id == "delivery_dependency_high"
    assert len(diags[0].rx_actions) >= 3


def test_diagnostics_engine_delivery_dependency_warning():
    """0.55 (>= 0.50 and < 0.70) → warning."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"delivery_dependency": 0.55})
    assert len(diags) == 1
    assert diags[0].severity == "warning"


# ── Fix 2: inline-threshold status TEXT must follow higher_is_worse ──
# delivery_dependency is higher_is_worse=true → warning/critical mean the
# value is too HIGH. Status text must say 偏高/严重偏高, never 偏低 (which
# contradicts the diagnosis title "外卖依赖度偏高").


def test_delivery_dependency_warning_status_text_is_high():
    """higher_is_worse=true warning → status '偏高' (NOT '偏低')."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"delivery_dependency": 0.55})
    assert diags[0].severity == "warning"
    assert diags[0].status == "偏高", \
        f"higher_is_worse warning must show 偏高, got {diags[0].status!r}"


def test_delivery_dependency_critical_status_text_is_severe_high():
    """higher_is_worse=true critical → status '严重偏高' (NOT '严重偏低'/'异常')."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"delivery_dependency": 0.75})
    assert diags[0].severity == "critical"
    assert diags[0].status == "严重偏高", \
        f"higher_is_worse critical must show 严重偏高, got {diags[0].status!r}"


def test_cost_rigidity_lower_is_worse_status_text_is_low():
    """higher_is_worse=false (cost_rigidity) critical → '严重偏低' preserved."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"cost_rigidity": 0.40})  # < 0.5 → critical
    assert diags[0].severity == "critical"
    assert diags[0].status == "严重偏低", \
        f"lower_is_worse critical must show 严重偏低, got {diags[0].status!r}"


def test_cost_rigidity_lower_is_worse_warning_status_text_is_low():
    """higher_is_worse=false warning → '偏低' preserved."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="鱼类餐饮")
    diags = engine.run({"cost_rigidity": 0.60})  # 0.5 <= v < 0.85 → warning
    assert diags[0].severity == "warning"
    assert diags[0].status == "偏低", \
        f"lower_is_worse warning must show 偏低, got {diags[0].status!r}"
