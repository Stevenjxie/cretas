"""RBAC price-strip wiring tests for the WS1 restaurant-ops Gold endpoints.

Covers the two NEW Gold endpoints in ``smartbi/api/restaurant_ops_gold.py``
that return revenue and previously shipped WITHOUT a price-strip:

* ``/api/smartbi/restaurant-ops/menu-quadrant``  (菜品四象限)
* ``/api/smartbi/restaurant-ops/store-comparison`` (门店对比)

Both leak 营收 to non-price-view roles (仓管 / operator) unless the shared
``strip_price_for_role`` helper nulls monetary fields. This mirrors the
``test_gold_reads_rbac_strip.py`` pattern (PR #435 / PR #455 ¥20.6M leak).

Strategy
--------
Unit-test the local helpers (``_apply_rbac_strip`` / ``_get_role`` /
``_strip_ops_extras``) against the exact response shapes produced by
``smartbi.gold.queries.menu_quadrant`` / ``store_comparison`` — no DB / no
FastAPI app required. The endpoints call ``_apply_rbac_strip(data, role)`` on
the inner ``data`` payload before wrapping in ``{"success": True, "data": ...}``,
so testing the helper against ``data`` exercises the production code path.
"""
from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from smartbi.api.restaurant_ops_gold import (
    _apply_rbac_strip,
    _get_role,
    _RESTAURANT_OPS_EXTRA_MONEY_KEYS,
    _strip_ops_extras,
)
from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

# A representative non-price-view role (matches the warehouse-manager scenario
# from the original ¥20.6M leak). Not in PRICE_VIEW_ROLES → strip applies.
_NON_PRICE_ROLE = "warehouse_manager"


# ============================================================
# Local helper unit tests
# ============================================================


def test_get_role_reads_request_state_role():
    """Middleware sets ``request.state.role`` from the JWT claim (same place
    ``_get_factory_id`` reads ``request.state.factory_id``)."""
    req = SimpleNamespace(state=SimpleNamespace(role="warehouse_manager"))
    assert _get_role(req) == "warehouse_manager"

    req_no_role = SimpleNamespace(state=SimpleNamespace())
    assert _get_role(req_no_role) is None


def test_extra_money_keys_contract():
    """``avgTicket`` (客单价) is the money key the shared ``_MONEY_PATTERN``
    misses; it must be in the local extras set. Adding a new camelCase money
    field to these shapes requires updating this set."""
    assert "avgTicket" in _RESTAURANT_OPS_EXTRA_MONEY_KEYS


def test_strip_ops_extras_nulls_avg_ticket_only():
    body = {"avgTicket": 42.5, "orderCount": 100, "name": "旗舰店"}
    _strip_ops_extras(body)
    assert body["avgTicket"] is None
    assert body["orderCount"] == 100
    assert body["name"] == "旗舰店"


def test_strip_ops_extras_recurses_into_lists():
    body = {
        "stores": [
            {"name": "A店", "avgTicket": 20.0, "orderCount": 5},
            {"name": "B店", "avgTicket": 30.0, "orderCount": 7},
        ],
    }
    _strip_ops_extras(body)
    assert body["stores"][0]["avgTicket"] is None
    assert body["stores"][1]["avgTicket"] is None
    assert body["stores"][0]["orderCount"] == 5


# ============================================================
# menu-quadrant — shape from smartbi.gold.queries.menu_quadrant
#   {items:[{name, qty, revenue, quadrant}], qtyMedian, revenueMedian}
# ============================================================


def _menu_quadrant_data() -> dict:
    return {
        "items": [
            {"name": "招牌A", "qty": 100, "revenue": 5000.0, "quadrant": "明星"},
            {"name": "高价B", "qty": 20, "revenue": 6000.0, "quadrant": "金牛"},
            {"name": "冷门D", "qty": 10, "revenue": 500.0, "quadrant": "瘦狗"},
        ],
        "qtyMedian": 20,
        "revenueMedian": 5000.0,
    }


def test_menu_quadrant_non_price_role_nulls_revenue():
    data = _menu_quadrant_data()
    _apply_rbac_strip(data, _NON_PRICE_ROLE)
    # Monetary fields nulled
    assert data["revenueMedian"] is None
    for it in data["items"]:
        assert it["revenue"] is None, it
    # Non-money fields preserved
    assert data["qtyMedian"] == 20
    for it in data["items"]:
        assert it["qty"] is not None
        assert it["quadrant"] is not None
        assert it["name"] is not None


@pytest.mark.parametrize("role", sorted(PRICE_VIEW_ROLES))
def test_menu_quadrant_price_view_role_preserves_revenue(role: str):
    data = _menu_quadrant_data()
    _apply_rbac_strip(data, role)
    assert data["revenueMedian"] == 5000.0
    assert data["items"][0]["revenue"] == 5000.0
    assert data["items"][1]["revenue"] == 6000.0
    assert data["qtyMedian"] == 20


# ============================================================
# store-comparison — shape from smartbi.gold.queries.store_comparison
#   {stores:[{name, revenue, orderCount, avgTicket}], medianRevenue,
#    weakStores:[name,...]}
# ============================================================


def _store_comparison_data() -> dict:
    return {
        "stores": [
            {"name": "旗舰店", "revenue": 10000.0, "orderCount": 500, "avgTicket": 20.0},
            {"name": "中位店", "revenue": 5000.0, "orderCount": 250, "avgTicket": 20.0},
            {"name": "弱店", "revenue": 2000.0, "orderCount": 200, "avgTicket": 10.0},
        ],
        "medianRevenue": 5000.0,
        "weakStores": ["弱店"],
    }


def test_store_comparison_non_price_role_nulls_revenue_and_avg_ticket():
    data = _store_comparison_data()
    _apply_rbac_strip(data, _NON_PRICE_ROLE)
    # Monetary fields nulled — revenue (regex) + medianRevenue (regex) +
    # avgTicket (extras set, regex misses camelCase 客单价).
    assert data["medianRevenue"] is None
    for s in data["stores"]:
        assert s["revenue"] is None, s
        assert s["avgTicket"] is None, s
    # Non-money fields preserved: orderCount, names, weakStores (店名列表)
    for s in data["stores"]:
        assert s["orderCount"] is not None
        assert s["name"] is not None
    assert data["weakStores"] == ["弱店"]


@pytest.mark.parametrize("role", sorted(PRICE_VIEW_ROLES))
def test_store_comparison_price_view_role_preserves_money(role: str):
    data = _store_comparison_data()
    _apply_rbac_strip(data, role)
    assert data["medianRevenue"] == 5000.0
    assert data["stores"][0]["revenue"] == 10000.0
    assert data["stores"][0]["avgTicket"] == 20.0
    assert data["stores"][2]["avgTicket"] == 10.0
    assert data["weakStores"] == ["弱店"]


# ============================================================
# Fail-closed: unknown / missing / empty role → strip applies
# ============================================================


@pytest.mark.parametrize("role", ["unactivated", None, "", "operator"])
def test_unknown_or_missing_role_strips_money(role: Any):
    data = _store_comparison_data()
    _apply_rbac_strip(data, role)
    assert data["medianRevenue"] is None
    assert data["stores"][0]["revenue"] is None
    assert data["stores"][0]["avgTicket"] is None


def test_apply_rbac_strip_returns_same_reference():
    data = _menu_quadrant_data()
    out = _apply_rbac_strip(data, _NON_PRICE_ROLE)
    assert out is data


def test_apply_rbac_strip_none_safe():
    assert _apply_rbac_strip(None, _NON_PRICE_ROLE) is None
