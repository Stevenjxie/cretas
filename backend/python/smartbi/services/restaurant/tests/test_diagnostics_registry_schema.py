"""Phase 1 #60 — Diagnostics registry schema + new-metric integrity tests.

Validates the YAML-driven diagnostics engine for the 2 Phase 1 metrics
(ingredient_waste_rate / avg_ticket_vs_target) and enforces structural
invariants across the whole restaurant registry:

  1. every registry metric referencing a playbook has the playbook file
  2. every playbook rx_action carries all 8 required fields
  3. engine.list_registered_metrics() contains the 2 new metrics
  4. ingredient_waste_rate severity boundaries (火锅 sub-sector, percent scale)
  5. avg_ticket_vs_target severity boundaries (inline threshold)

UNIT-SCALE NOTE (ingredient_waste_rate):
  The brief specced test inputs 0.30 / 0.06 on a 0-1 ratio scale, but the
  threshold_source benchmark `waste_loss_rate` is in PERCENT (火锅 range
  [3, 15]). To match the existing food_cost_ratio convention (which passes
  `food_cost / revenue * 100` to the engine), the analyzer feeds
  ingredient_waste_rate as a percentage (×100). The semantically-equivalent
  boundary tests therefore use 30.0 (=30% waste → critical) and
  6.0 (=6% waste → healthy/None). Both encode the same intent as the brief.
"""
from __future__ import annotations

from pathlib import Path

import yaml

from smartbi.shared.diagnostics_engine import DiagnosticsEngine, RxAction

_KNOWLEDGE_DIR = (
    Path(__file__).resolve().parents[3] / "knowledge" / "restaurant"
)
_REGISTRY_PATH = _KNOWLEDGE_DIR / "diagnostics_registry.yaml"
_PLAYBOOK_DIR = _KNOWLEDGE_DIR / "playbooks"

_REQUIRED_RX_FIELDS = (
    "id", "title", "description", "owner",
    "timeframe", "priority", "effort", "expected_impact",
)

_NEW_METRICS = ("ingredient_waste_rate", "avg_ticket_vs_target")

# Pre-existing registry metrics whose playbook YAML is intentionally absent on
# origin/main (BOM/cogs-gated or separately-gated, not wired by Phase 1 #60).
# Allowlisted so this schema test surfaces NEW gaps without failing on known
# legacy debt. Removing an entry here should make the test fail if the gap
# still exists.
_KNOWN_MISSING_PLAYBOOKS = {
    "channel_gross_margin": "channel_margin_low",        # requires_cogs (#57 BOM)
    "stored_value_dependency": "stored_value_dependency_high",
}


def _load_registry() -> dict:
    with open(_REGISTRY_PATH, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _load_playbook(name: str) -> dict:
    path = _PLAYBOOK_DIR / f"{name}.yaml"
    assert path.exists(), f"playbook YAML missing: {path}"
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


# ── (1) every registry metric with a playbook ref has the file ──


def test_all_registry_metrics_have_playbook_file():
    """Every registry metric with a playbook ref has the file, except the known
    pre-existing gaps allowlisted in _KNOWN_MISSING_PLAYBOOKS. Catches NEW gaps."""
    registry = _load_registry()
    metrics = registry.get("metrics", {})
    assert metrics, "registry has no metrics"
    missing: list[str] = []
    for key, mdef in metrics.items():
        playbook_id = mdef.get("playbook")
        if not playbook_id:
            continue
        if _KNOWN_MISSING_PLAYBOOKS.get(key) == playbook_id:
            continue  # documented pre-existing gap
        path = _PLAYBOOK_DIR / f"{playbook_id}.yaml"
        if not path.exists():
            missing.append(f"{key} -> {playbook_id}.yaml")
    assert not missing, f"registry metrics reference missing playbooks: {missing}"


# ── (2) every playbook rx_action has all 8 required fields ──


def test_all_playbook_rx_actions_have_required_fields():
    registry = _load_registry()
    playbook_ids = {
        mdef["playbook"]
        for mdef in registry.get("metrics", {}).values()
        if mdef.get("playbook")
        and mdef["playbook"] not in _KNOWN_MISSING_PLAYBOOKS.values()
    }
    assert playbook_ids, "no playbooks referenced by registry"
    problems: list[str] = []
    for pb_id in sorted(playbook_ids):
        pb = _load_playbook(pb_id)
        rx = pb.get("rx_actions") or []
        for item in rx:
            missing = [f for f in _REQUIRED_RX_FIELDS if not item.get(f)]
            if missing:
                problems.append(f"{pb_id}:{item.get('id', '?')} missing {missing}")
            if item.get("priority") and item["priority"] not in ("P0", "P1", "P2"):
                problems.append(f"{pb_id}:{item.get('id')} bad priority {item['priority']}")
            if item.get("effort") and item["effort"] not in ("low", "medium", "high"):
                problems.append(f"{pb_id}:{item.get('id')} bad effort {item['effort']}")
    assert not problems, f"rx_action field problems: {problems}"


def test_new_playbooks_have_at_least_three_rx_actions():
    for pb_id in ("ingredient_waste_rate_high", "avg_ticket_below_target"):
        pb = _load_playbook(pb_id)
        rx = pb.get("rx_actions") or []
        assert len(rx) >= 3, f"{pb_id} expected >=3 rx_actions, got {len(rx)}"
        for item in rx:
            assert all(item.get(f) for f in _REQUIRED_RX_FIELDS), \
                f"{pb_id}:{item.get('id')} incomplete"


# ── (3) engine registers the 2 new metrics ──


def test_engine_lists_new_metrics():
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    registered = engine.list_registered_metrics()
    for m in _NEW_METRICS:
        assert m in registered, f"{m} not registered; got {registered}"


# ── (4) ingredient_waste_rate severity boundaries (火锅, percent scale) ──
# 火锅 waste_loss_rate benchmark: range [3, 15], median 8, higher_is_worse=true.
# severe_offset = max((15-3)*0.25, 1.0) = 3.0 → critical when actual > 15+3 = 18.


def test_ingredient_waste_rate_critical():
    """30.0 (=30% waste, > 18) → critical with structured rx_actions."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("ingredient_waste_rate", 30.0)
    assert d is not None
    assert d.metric_key == "ingredient_waste_rate"
    assert d.severity == "critical"
    assert d.status == "严重偏高"
    assert d.playbook_id == "ingredient_waste_rate_high"
    assert len(d.rx_actions) >= 3
    assert all(isinstance(a, RxAction) for a in d.rx_actions)


def test_ingredient_waste_rate_healthy_returns_none():
    """6.0 (=6% waste, within [3,15]) → 正常/info → engine drops it (None)."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("ingredient_waste_rate", 6.0)
    assert d is None


def test_ingredient_waste_rate_warning_boundary():
    """16.0 (> 15 high, <= 18) → warning (偏高)."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("ingredient_waste_rate", 16.0)
    assert d is not None
    assert d.severity == "warning"
    assert d.status == "偏高"


# ── (5) avg_ticket_vs_target severity boundaries (inline threshold) ──
# healthy >= -0.05 / warning -0.15..-0.05 / critical < -0.15, higher_is_worse=false.


def test_avg_ticket_vs_target_critical():
    """-0.20 (< -0.15) → critical with rx_actions."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("avg_ticket_vs_target", -0.20)
    assert d is not None
    assert d.metric_key == "avg_ticket_vs_target"
    assert d.severity == "critical"
    assert d.status == "严重偏低"  # higher_is_worse=false → 偏低 direction
    assert d.playbook_id == "avg_ticket_below_target"
    assert len(d.rx_actions) >= 3


def test_avg_ticket_vs_target_healthy_returns_none():
    """-0.02 (>= -0.05) → healthy → None."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("avg_ticket_vs_target", -0.02)
    assert d is None


def test_avg_ticket_vs_target_warning_boundary():
    """-0.10 (-0.15 <= v < -0.05) → warning (偏低)."""
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("avg_ticket_vs_target", -0.10)
    assert d is not None
    assert d.severity == "warning"
    assert d.status == "偏低"


# ── (6) analyzer integration: guard skips metrics when source data absent ──


def test_analyzer_skips_new_metrics_when_source_data_absent():
    """No wastage_cost / actual_avg_ticket → metric_dict excludes the new keys."""
    from smartbi.services.restaurant.analyzer import RestaurantAnalyzerV2

    v2 = RestaurantAnalyzerV2(factory_id="TEST_F", sub_sector="火锅")
    metrics = v2._extract_financial_metrics(
        {"current": {"revenue": 100000, "food_cost": 42000, "labor_cost": 30000}}
    )
    assert metrics.ingredient_waste_rate is None
    assert metrics.avg_ticket_vs_target is None
    md = v2._diagnostics_metric_dict(metrics)
    assert "ingredient_waste_rate" not in md
    assert "avg_ticket_vs_target" not in md


def test_analyzer_computes_new_metrics_when_source_data_present():
    """With source data, percentage-scaled waste rate + target-relative ticket land."""
    from smartbi.services.restaurant.analyzer import RestaurantAnalyzerV2

    v2 = RestaurantAnalyzerV2(factory_id="TEST_F", sub_sector="火锅")
    metrics = v2._extract_financial_metrics(
        {
            "current": {
                "revenue": 100000,
                "food_cost": 42000,
                "wastage_cost": 9000,
                "food_cost_purchase": 21000,  # waste = 9000/(21000+9000)*100 = 30%
                "actual_avg_ticket": 60.0,    # target benchmark median 75 → 60/75-1 = -0.20
            }
        }
    )
    assert metrics.ingredient_waste_rate is not None
    assert abs(metrics.ingredient_waste_rate - 30.0) < 1e-6
    assert metrics.target_avg_ticket == 75.0  # 火锅 benchmark average_ticket median
    assert metrics.avg_ticket_vs_target is not None
    assert abs(metrics.avg_ticket_vs_target - (-0.20)) < 1e-6

    md = v2._diagnostics_metric_dict(metrics)
    assert "ingredient_waste_rate" in md
    assert "avg_ticket_vs_target" in md

    diags = v2.diagnostics_engine.run(md)
    by_key = {d.metric_key: d for d in diags}
    assert by_key["ingredient_waste_rate"].severity == "critical"
    assert by_key["avg_ticket_vs_target"].severity == "critical"
