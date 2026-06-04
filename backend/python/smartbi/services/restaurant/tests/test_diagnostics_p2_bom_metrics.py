"""Phase 2 #60 — BOM-dependent diagnostic metrics (gross_margin_per_dish /
recipe_coverage_rate) + analyzer integration with agg_restaurant_product_cost.

These two metrics depend on #57's per-dish cost rollup (agg_restaurant_product_cost:
food_cost + has_price_data per dish). Unlike the Phase 1 metrics (gold-available),
they require recipe cost data, so the analyzer computes them from a dish-level
``product_costs`` list threaded through ``financial_data``.

GUARD (sparse-data honesty): if fewer than 3 dishes carry ``has_price_data``,
the analyzer skips BOTH new metrics (does not add the key → engine skips unknown
keys → honest no-fire, NOT a fabricated healthy reading).

Scales:
  - gross_margin_per_dish: 0-1 ratio. healthy>=0.55 / warning>=0.40 / critical<0.40.
    higher_is_worse=false (lower margin is worse).
  - recipe_coverage_rate: 0-1 ratio (priced dishes / active dishes).
    healthy>=0.70 / warning>=0.40 / critical<0.40. higher_is_worse=false.
"""
from __future__ import annotations

from pathlib import Path

import yaml

from smartbi.shared.diagnostics_engine import DiagnosticsEngine, RxAction

_KNOWLEDGE_DIR = Path(__file__).resolve().parents[3] / "knowledge" / "restaurant"
_REGISTRY_PATH = _KNOWLEDGE_DIR / "diagnostics_registry.yaml"
_PLAYBOOK_DIR = _KNOWLEDGE_DIR / "playbooks"

_REQUIRED_RX_FIELDS = (
    "id", "title", "description", "owner",
    "timeframe", "priority", "effort", "expected_impact",
)

_NEW_P2_METRICS = ("gross_margin_per_dish", "recipe_coverage_rate")
_NEW_P2_PLAYBOOKS = ("gross_margin_per_dish_low", "recipe_coverage_low")


def _load_registry() -> dict:
    with open(_REGISTRY_PATH, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _load_playbook(name: str) -> dict:
    path = _PLAYBOOK_DIR / f"{name}.yaml"
    assert path.exists(), f"playbook YAML missing: {path}"
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


# ── (1) registry registers both P2 metrics with inline thresholds ──


def test_registry_has_p2_metrics_with_inline_thresholds():
    registry = _load_registry()
    metrics = registry.get("metrics", {})
    for m in _NEW_P2_METRICS:
        assert m in metrics, f"{m} not in registry"
        mdef = metrics[m]
        assert mdef.get("threshold_inline"), f"{m} must use threshold_inline"
        assert mdef.get("higher_is_worse") is False, f"{m} higher_is_worse must be false"
        assert mdef.get("playbook") in _NEW_P2_PLAYBOOKS, f"{m} playbook ref wrong"


def test_engine_lists_p2_metrics():
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    registered = engine.list_registered_metrics()
    for m in _NEW_P2_METRICS:
        assert m in registered, f"{m} not registered; got {registered}"


# ── (2) both P2 playbooks exist + have >=3 fully-formed rx_actions ──


def test_p2_playbooks_have_three_complete_rx_actions():
    for pb_id in _NEW_P2_PLAYBOOKS:
        pb = _load_playbook(pb_id)
        rx = pb.get("rx_actions") or []
        assert len(rx) >= 3, f"{pb_id} expected >=3 rx_actions, got {len(rx)}"
        for item in rx:
            missing = [f for f in _REQUIRED_RX_FIELDS if not item.get(f)]
            assert not missing, f"{pb_id}:{item.get('id')} missing {missing}"
            assert item["priority"] in ("P0", "P1", "P2")
            assert item["effort"] in ("low", "medium", "high")


# ── (3) gross_margin_per_dish severity boundaries (inline, higher_is_worse=false) ──
# healthy >= 0.55 / warning 0.40..0.55 / critical < 0.40.


def test_gross_margin_per_dish_critical():
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("gross_margin_per_dish", 0.30)  # < 0.40
    assert d is not None
    assert d.metric_key == "gross_margin_per_dish"
    assert d.severity == "critical"
    assert d.status == "严重偏低"  # lower is worse
    assert d.playbook_id == "gross_margin_per_dish_low"
    assert len(d.rx_actions) >= 3
    assert all(isinstance(a, RxAction) for a in d.rx_actions)


def test_gross_margin_per_dish_warning():
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("gross_margin_per_dish", 0.45)  # 0.40..0.55
    assert d is not None
    assert d.severity == "warning"
    assert d.status == "偏低"


def test_gross_margin_per_dish_healthy_returns_none():
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("gross_margin_per_dish", 0.62)  # >= 0.55
    assert d is None


# ── (4) recipe_coverage_rate severity boundaries (inline, higher_is_worse=false) ──
# healthy >= 0.70 / warning 0.40..0.70 / critical < 0.40.


def test_recipe_coverage_rate_critical():
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("recipe_coverage_rate", 0.30)  # < 0.40
    assert d is not None
    assert d.metric_key == "recipe_coverage_rate"
    assert d.severity == "critical"
    assert d.status == "严重偏低"
    assert d.playbook_id == "recipe_coverage_low"
    assert len(d.rx_actions) >= 3


def test_recipe_coverage_rate_warning():
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("recipe_coverage_rate", 0.55)  # 0.40..0.70
    assert d is not None
    assert d.severity == "warning"
    assert d.status == "偏低"


def test_recipe_coverage_rate_healthy_returns_none():
    engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    d = engine.evaluate_one("recipe_coverage_rate", 0.85)  # >= 0.70
    assert d is None


# ── (5) analyzer integration: compute from product_costs dish list ──


def _dish(food_cost, revenue, qty=1, has_price=True):
    return {
        "food_cost": food_cost,
        "revenue": revenue,
        "qty": qty,
        "has_price_data": has_price,
    }


def test_analyzer_computes_p2_metrics_from_product_costs():
    """>=3 priced dishes → both metrics land in metric_dict + diagnose."""
    from smartbi.services.restaurant.analyzer import RestaurantAnalyzerV2

    v2 = RestaurantAnalyzerV2(factory_id="TEST_F", sub_sector="火锅")
    # 4 active dishes, 3 priced. Priced dishes' aggregate margin:
    #   dish A: rev 100, cost 70 (margin 0.30)
    #   dish B: rev 100, cost 65 (margin 0.35)
    #   dish C: rev 100, cost 60 (margin 0.40)
    #   aggregate = (300 - 195) / 300 = 0.35 → critical (<0.40)
    # coverage = 3 priced / 4 active = 0.75 → healthy → engine drops it.
    product_costs = [
        _dish(70, 100),
        _dish(65, 100),
        _dish(60, 100),
        _dish(0, 80, has_price=False),
    ]
    metrics = v2._extract_financial_metrics(
        {"current": {"revenue": 380000}, "product_costs": product_costs}
    )
    assert metrics.gross_margin_per_dish is not None
    assert abs(metrics.gross_margin_per_dish - 0.35) < 1e-6
    assert metrics.recipe_coverage_rate is not None
    assert abs(metrics.recipe_coverage_rate - 0.75) < 1e-6

    md = v2._diagnostics_metric_dict(metrics)
    assert "gross_margin_per_dish" in md
    assert "recipe_coverage_rate" in md

    diags = v2.diagnostics_engine.run(md)
    by_key = {d.metric_key: d for d in diags}
    assert by_key["gross_margin_per_dish"].severity == "critical"
    # coverage 0.75 healthy → not in diagnoses
    assert "recipe_coverage_rate" not in by_key


def test_analyzer_skips_p2_metrics_when_fewer_than_three_priced_dishes():
    """GUARD: <3 priced dishes → NO key for either metric (honest no-fire)."""
    from smartbi.services.restaurant.analyzer import RestaurantAnalyzerV2

    v2 = RestaurantAnalyzerV2(factory_id="TEST_F", sub_sector="火锅")
    product_costs = [
        _dish(70, 100),
        _dish(65, 100),
        _dish(0, 80, has_price=False),
        _dish(0, 90, has_price=False),
    ]  # only 2 priced
    metrics = v2._extract_financial_metrics(
        {"current": {"revenue": 370000}, "product_costs": product_costs}
    )
    assert metrics.gross_margin_per_dish is None
    assert metrics.recipe_coverage_rate is None
    md = v2._diagnostics_metric_dict(metrics)
    assert "gross_margin_per_dish" not in md
    assert "recipe_coverage_rate" not in md


def test_analyzer_skips_p2_metrics_when_no_product_costs():
    """No product_costs at all → neither metric computed."""
    from smartbi.services.restaurant.analyzer import RestaurantAnalyzerV2

    v2 = RestaurantAnalyzerV2(factory_id="TEST_F", sub_sector="火锅")
    metrics = v2._extract_financial_metrics(
        {"current": {"revenue": 100000, "food_cost": 42000}}
    )
    assert metrics.gross_margin_per_dish is None
    assert metrics.recipe_coverage_rate is None


def test_analyzer_recipe_coverage_critical_with_enough_priced_dishes():
    """coverage critical needs >=3 priced AND priced/active < 0.40.
    Use 3 priced of 8 active = 0.375 → critical."""
    from smartbi.services.restaurant.analyzer import RestaurantAnalyzerV2

    v2 = RestaurantAnalyzerV2(factory_id="TEST_F", sub_sector="火锅")
    product_costs = [
        _dish(40, 100),  # margin 0.60
        _dish(45, 100),  # 0.55
        _dish(50, 100),  # 0.50
    ] + [_dish(0, 80, has_price=False) for _ in range(5)]  # 5 unpriced
    metrics = v2._extract_financial_metrics(
        {"current": {"revenue": 700000}, "product_costs": product_costs}
    )
    assert metrics.recipe_coverage_rate is not None
    assert abs(metrics.recipe_coverage_rate - 3 / 8) < 1e-6  # 0.375
    md = v2._diagnostics_metric_dict(metrics)
    diags = v2.diagnostics_engine.run(md)
    by_key = {d.metric_key: d for d in diags}
    assert by_key["recipe_coverage_rate"].severity == "critical"
    # priced aggregate margin = (300 - 135)/300 = 0.55 → healthy, dropped
    assert "gross_margin_per_dish" not in by_key
