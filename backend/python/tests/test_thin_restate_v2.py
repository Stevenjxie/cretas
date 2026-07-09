"""P4 v2 thin-restate: closure whitelist (derived numbers), normalization,
entity-aware store-id handling, eligibility."""
from decimal import Decimal

from smartbi.agent.synthesis_engine import (
    _thin_restate_ok, _thin_restate_closure, _thin_normalize, _is_thin_restate_eligible,
)

_ATTR = {
    "laggard": {"store_name": "示范门店01", "revenue": 15018722.0, "delta_revenue": -72745.0,
                "traffic_effect": 569775.0, "ticket_effect": -619145.0,
                "bills": 65652, "avg_ticket": 228.80},
    "bench_bills": 63264, "chain_avg_ticket": 238.50, "bench_revenue": 15091467.0,
    "primary_cause": "客单价",
}


def test_normalize_units_and_commas():
    assert _thin_normalize("1501万") == Decimal("15010000")
    assert _thin_normalize("2,388") == Decimal("2388")
    assert _thin_normalize("228.80") == Decimal("228.80")


def test_closure_includes_derived_deltas():
    allowed = _thin_restate_closure(_ATTR)
    assert any(abs(v - Decimal("9.7")) <= Decimal("0.01") for v in allowed)    # ticket delta
    assert any(abs(v - Decimal("2388")) <= Decimal("0.5") for v in allowed)    # bills delta


def test_natural_restatement_passes_with_entity_id_and_wan():
    # The failure that broke v1: derived deltas (9.70/2388), 万-format (7.27万),
    # and the store-id "01" — all must pass now.
    txt = ("示范门店01拖后腿，客单价228.80元比全链238.50低9.70元，"
           "客流65,652单比平均63,264多2,388单，营收少7.27万")
    assert _thin_restate_ok(txt, _ATTR) is True


def test_fabricated_number_rejected():
    assert _thin_restate_ok("示范门店01客单价只有150元", _ATTR) is False


def test_eligibility_pure_or_finance_attribution_only():
    assert _is_thin_restate_eligible({"attribution": True, "finance": True}) is True
    assert _is_thin_restate_eligible({"attribution": True}) is True
    assert _is_thin_restate_eligible({"attribution": True, "weather": True}) is False   # multi-dim
    assert _is_thin_restate_eligible({"attribution": True, "review": True}) is False
    assert _is_thin_restate_eligible({"attribution": False, "finance": True}) is False
