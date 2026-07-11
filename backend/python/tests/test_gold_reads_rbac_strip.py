"""RBAC price-strip wiring tests for ``/api/smartbi/gold/*`` endpoints.

Mirrors the PR #435 / PR #443 / PR #458 pattern used across
``smartbi_compat.api.analysis_*`` for Java→Python ported endpoints: the
shared ``strip_price_for_role`` helper nulls monetary fields for roles
outside ``PRICE_VIEW_ROLES``. PR #455 chat 2 E2E surfaced a ¥20.6M
aggregate leak from ``/api/smartbi/gold/finance-summary`` to
``warehouse_mgr1`` because the Gold reads router predates the strip-
helper convention.

Scope
-----
The 7 Gold read endpoints in ``smartbi/api/gold_reads.py``:
finance-summary, daily-trend, top-products, channel-breakdown,
discount-breakdown, analysis-results, kpi-summary.

Strategy
--------
* Unit-test the local helpers (``_apply_rbac_strip``, ``_strip_extras``,
  ``_get_role``) against synthesised Gold response shapes — no DB / no
  FastAPI app required.
* Each endpoint's exact response shape is tested so a future refactor
  that changes a key name (e.g. ``revenue`` → ``rev_amt``) is caught
  here before reaching customer data.
"""
from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from smartbi.api.gold_reads import (
    _apply_rbac_strip,
    _get_role,
    _GOLD_EXTRA_MONEY_KEYS,
    _strip_extras,
)
from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES


# ============================================================
# Local helper unit tests
# ============================================================


def test_get_role_reads_request_state_role():
    """Middleware sets ``scope['state']['role']`` from the JWT claim — the
    helper reads via ``request.state.role`` (``getattr`` so missing →
    None)."""
    req = SimpleNamespace(state=SimpleNamespace(role="warehouse_manager"))
    assert _get_role(req) == "warehouse_manager"

    req_no_role = SimpleNamespace(state=SimpleNamespace())
    assert _get_role(req_no_role) is None


def test_gold_extra_keys_documented():
    """Document the contract: these camelCase/underscored money fields are
    the ones the shared ``_MONEY_PATTERN`` doesn't catch in current Gold
    responses (``avg_bill_value`` / ``avg_per_capita`` + the /trend-bundle
    weekday/weekend average-revenue keys + the /member-profile 储值/充值
    money keys). Adding a new money key to Gold responses requires also
    updating this set."""
    assert _GOLD_EXTRA_MONEY_KEYS == frozenset(
        {
            "avg_bill_value", "avg_per_capita", "weekdayAvg", "weekendAvg",
            "total_balance", "principal", "bonus",
        }
    )


def test_strip_extras_nulls_per_bill_average():
    body = {"avg_bill_value": 42.5, "bill_count": 100, "store_count": 3}
    _strip_extras(body)
    assert body["avg_bill_value"] is None
    assert body["bill_count"] == 100
    assert body["store_count"] == 3


def test_strip_extras_recurses_into_lists_and_nested_dicts():
    body = {
        "points": [
            {"date": "2026-05-01", "revenue": 100.0, "avg_bill_value": 25.0},
            {"date": "2026-05-02", "revenue": 200.0, "avg_bill_value": 50.0},
        ],
        "meta": {"avg_per_capita": 17.5, "customer_count": 12},
    }
    _strip_extras(body)
    assert body["points"][0]["avg_bill_value"] is None
    assert body["points"][1]["avg_bill_value"] is None
    assert body["points"][0]["revenue"] == 100.0  # untouched by extras
    assert body["meta"]["avg_per_capita"] is None
    assert body["meta"]["customer_count"] == 12


# ============================================================
# Per-endpoint response shape tests — warehouse_manager
# ============================================================


def _warehouse_strip(body: Any) -> Any:
    """Apply strip as a non-privileged role."""
    return _apply_rbac_strip(body, "warehouse_manager")


def test_finance_summary_shape_warehouse_nulls_all_money():
    """``/finance-summary`` shape from ``smartbi.gold.queries.finance_summary``."""
    body = {
        "factory_id": "F001",
        "start_date": "2026-04-01",
        "end_date": "2026-04-30",
        "total_revenue": 20_600_000.0,
        "bill_count": 1234,
        "avg_bill_value": 16693.68,
        "store_count": 12,
        "day_count": 30,
        "top_stores": [
            {"store_id": 1, "store_name": "总店", "revenue": 8_000_000.0, "bill_count": 500},
            {"store_id": 2, "store_name": "二店", "revenue": 5_000_000.0, "bill_count": 400},
        ],
    }
    _warehouse_strip(body)
    # Aggregate money — null
    assert body["total_revenue"] is None
    assert body["avg_bill_value"] is None
    # Per-store breakdown — revenue null, name + counts preserved
    for store in body["top_stores"]:
        assert store["revenue"] is None
        assert store["store_name"] is not None
        assert store["bill_count"] is not None
    # Tenant + counts preserved
    assert body["factory_id"] == "F001"
    assert body["bill_count"] == 1234
    assert body["store_count"] == 12


def test_daily_trend_shape_warehouse_nulls_per_point_revenue():
    """``/daily-trend`` shape from ``smartbi.gold.queries.daily_trend``."""
    body = {
        "factory_id": "F001",
        "start_date": "2026-04-01",
        "end_date": "2026-04-02",
        "points": [
            {"date": "2026-04-01", "revenue": 50000.0, "bill_count": 100, "avg_bill_value": 500.0},
            {"date": "2026-04-02", "revenue": 65000.0, "bill_count": 130, "avg_bill_value": 500.0},
        ],
    }
    _warehouse_strip(body)
    for p in body["points"]:
        assert p["revenue"] is None
        assert p["avg_bill_value"] is None
        assert p["bill_count"] is not None  # not money
        assert p["date"] is not None


def test_top_products_shape_warehouse_nulls_revenue_keeps_qty():
    """``/top-products`` shape from ``smartbi.gold.queries.top_products``."""
    body = {
        "factory_id": "F001",
        "start_month": "2026-04-01",
        "end_month": "2026-04-01",
        "top_products": [
            {
                "product_id": 1, "product_name": "招牌菜",
                "qty_sold": 320.5, "revenue": 95000.0, "bill_count": 180,
                "confidence": 0.95, "source": "POS",
                "source_upload_id": 1234, "entity_id": "1", "field_name": "revenue",
            },
        ],
    }
    _warehouse_strip(body)
    p = body["top_products"][0]
    assert p["revenue"] is None
    assert p["qty_sold"] == 320.5
    assert p["bill_count"] == 180
    assert p["product_name"] == "招牌菜"
    assert p["confidence"] == 0.95


def test_channel_breakdown_shape_warehouse_nulls_amounts():
    """``/channel-breakdown`` shape from ``smartbi.gold.queries.channel_breakdown``."""
    body = {
        "factory_id": "F001",
        "start_date": "2026-04-01",
        "end_date": "2026-04-30",
        "total_amount": 1_500_000.0,
        "channels": [
            {"channel_id": 1, "channel_name": "微信支付", "amount": 900000.0, "bill_count": 700, "share_pct": 60.0},
            {"channel_id": 2, "channel_name": "支付宝", "amount": 600000.0, "bill_count": 400, "share_pct": 40.0},
        ],
    }
    _warehouse_strip(body)
    assert body["total_amount"] is None
    for c in body["channels"]:
        assert c["amount"] is None
        assert c["channel_name"] is not None
        assert c["bill_count"] is not None
        # share_pct is a percentage, not money — preserved
        assert c["share_pct"] is not None


def test_discount_breakdown_shape_warehouse_nulls_amounts():
    body = {
        "factory_id": "F001",
        "start_date": "2026-04-01",
        "end_date": "2026-04-30",
        "total_amount": 80000.0,
        "discounts": [
            {"discount_id": 1, "discount_name": "新人券", "amount": 50000.0, "bill_count": 250, "share_pct": 62.5},
        ],
    }
    _warehouse_strip(body)
    assert body["total_amount"] is None
    assert body["discounts"][0]["amount"] is None
    assert body["discounts"][0]["discount_name"] == "新人券"
    assert body["discounts"][0]["share_pct"] == 62.5


def test_kpi_summary_shape_warehouse_nulls_all_money_including_gaps():
    """``/kpi-summary`` shape has the most exposure — both the shared
    pattern (``revenue``) and the local extras (``avg_bill_value`` /
    ``avg_per_capita``) must be nulled."""
    body = {
        "factory_id": "F001",
        "start_date": "2026-04-01",
        "end_date": "2026-04-30",
        "revenue": 20_600_000.0,
        "bill_count": 1234,
        "item_count": 5000,
        "customer_count": 800,
        "store_count": 12,
        "day_count": 30,
        "avg_bill_value": 16693.68,
        "items_per_bill": 4.05,
        "avg_per_capita": 25750.0,
    }
    _warehouse_strip(body)
    assert body["revenue"] is None
    assert body["avg_bill_value"] is None
    assert body["avg_per_capita"] is None
    # Non-money counts + non-money rate preserved
    assert body["bill_count"] == 1234
    assert body["items_per_bill"] == 4.05
    assert body["store_count"] == 12


def test_analysis_results_shape_warehouse_strips_kpi_values_jsonb():
    """``/analysis-results`` payload carries arbitrary JSONB in
    ``kpi_values`` / ``analysis_result`` / ``insights`` — the strip walk
    must descend depth-first into each."""
    body = {
        "items": [
            {
                "upload_id": 90050,
                "template_code": "FIN_REVENUE_OVERVIEW",
                "domain": "finance",
                "analysis_type": "materialized:FIN_REVENUE_OVERVIEW",
                "analysis_result": {"summary": {"totalRevenue": 12345.67, "billCount": 100}},
                "chart_configs": [{"chartType": "bar", "title": "营收"}],
                "kpi_values": {
                    "primary": [
                        {"key": "total_revenue", "title": "总营收", "value": "1.2万元",
                         "rawValue": 12345.67, "unit": "元"},
                    ],
                },
                "insights": [{"type": "trend", "text": "营收同比 +12%"}],
                "created_at": "2026-05-12T10:00:00",
                "upload_label": "test.csv",
                "upload_created_at": "2026-05-12T09:00:00",
            },
        ],
        "missing_codes": [],
        "never_materialized_codes": [],
    }
    _warehouse_strip(body)
    item = body["items"][0]
    assert item["analysis_result"]["summary"]["totalRevenue"] is None
    assert item["analysis_result"]["summary"]["billCount"] == 100
    kpi = item["kpi_values"]["primary"][0]
    assert kpi["value"] is None
    assert kpi["rawValue"] is None
    # Non-money envelope fields preserved
    assert item["template_code"] == "FIN_REVENUE_OVERVIEW"
    assert item["upload_id"] == 90050


# ============================================================
# Per-endpoint shape tests — privileged roles pass through
# ============================================================


@pytest.mark.parametrize("role", sorted(PRICE_VIEW_ROLES))
def test_privileged_roles_pass_through_finance_summary(role: str):
    """Every role in ``PRICE_VIEW_ROLES`` retains every monetary value."""
    body = {
        "factory_id": "F001",
        "total_revenue": 20_600_000.0,
        "avg_bill_value": 16693.68,
        "top_stores": [{"store_id": 1, "store_name": "总店", "revenue": 8_000_000.0}],
    }
    _apply_rbac_strip(body, role)
    assert body["total_revenue"] == 20_600_000.0
    assert body["avg_bill_value"] == 16693.68
    assert body["top_stores"][0]["revenue"] == 8_000_000.0


def test_unknown_role_treated_as_ineligible():
    body = {"revenue": 9999.99, "avg_bill_value": 42.0}
    _apply_rbac_strip(body, "unactivated")
    assert body["revenue"] is None
    assert body["avg_bill_value"] is None


def test_none_role_treated_as_ineligible():
    """Missing role (token without ``role`` claim → middleware sets to
    None) gets stripped — fail-CLOSED."""
    body = {"revenue": 9999.99}
    _apply_rbac_strip(body, None)
    assert body["revenue"] is None


def test_empty_role_treated_as_ineligible():
    body = {"revenue": 9999.99}
    _apply_rbac_strip(body, "")
    assert body["revenue"] is None


# ============================================================
# Idempotency + null-input safety
# ============================================================


def test_idempotent_double_strip():
    """Re-applying the strip on a stripped payload is a no-op (everything
    sensitive is already None)."""
    body = {
        "revenue": 100.0,
        "avg_bill_value": 25.0,
        "points": [{"revenue": 50.0, "avg_bill_value": 25.0, "bill_count": 2}],
    }
    _warehouse_strip(body)
    snapshot = dict(body)
    snapshot_points = [dict(p) for p in body["points"]]
    _warehouse_strip(body)
    assert body == snapshot
    assert body["points"] == snapshot_points


def test_empty_payload_passes_through():
    assert _apply_rbac_strip(None, "warehouse_manager") is None
    assert _apply_rbac_strip({}, "warehouse_manager") == {}
    assert _apply_rbac_strip([], "warehouse_manager") == []


def test_returns_same_reference_for_caller_convenience():
    body = {"revenue": 100.0}
    out = _apply_rbac_strip(body, "warehouse_manager")
    assert out is body


# ============================================================
# PR #547 fix — restaurant_owner (and restaurant_purchaser) must be
# in PRICE_VIEW_ROLES so gold revenue is NOT stripped for owner queries.
# Live-verify symptom: "本月营业收入" as restaurant_owner → ¥0 (bug).
# ============================================================


def test_restaurant_owner_in_price_view_roles():
    """PR #547: restaurant_owner must be in PRICE_VIEW_ROLES.

    Java PermissionServiceImpl.PRICE_VIEW_ROLES line 315 includes
    restaurant_owner; the Python mirror was missing it — owner's own
    revenue was being nulled by _apply_rbac_strip.
    """
    assert "restaurant_owner" in PRICE_VIEW_ROLES, (
        "restaurant_owner must be in PRICE_VIEW_ROLES — "
        "owner's gold revenue was stripped to None (PR #547 bug)"
    )


def test_restaurant_purchaser_in_price_view_roles():
    """PR #547: restaurant_purchaser is in Java PRICE_VIEW_ROLES (line 316);
    Python mirror should include it for parity."""
    assert "restaurant_purchaser" in PRICE_VIEW_ROLES, (
        "restaurant_purchaser must be in PRICE_VIEW_ROLES "
        "(mirrors Java PermissionServiceImpl.PRICE_VIEW_ROLES)"
    )


def test_restaurant_owner_revenue_not_stripped():
    """restaurant_owner query for 本月营业收入 must return real revenue value."""
    body = {
        "factory_id": "F_RESTAURANT",
        "total_revenue": 2_100_000.0,
        "bill_count": 3_120,
        "avg_bill_value": 673.08,
        "store_count": 1,
        "top_stores": [
            {
                "store_id": 1,
                "store_name": "总店",
                "revenue": 2_100_000.0,
                "bill_count": 3_120,
            }
        ],
    }
    _apply_rbac_strip(body, "restaurant_owner")
    # Revenue MUST NOT be stripped for owner
    assert body["total_revenue"] == 2_100_000.0, (
        "restaurant_owner must see total_revenue — "
        "PR #547 fix: owner was seeing ¥0 before this patch"
    )
    assert body["avg_bill_value"] == 673.08, (
        "restaurant_owner must see avg_bill_value"
    )
    assert body["top_stores"][0]["revenue"] == 2_100_000.0, (
        "restaurant_owner must see per-store revenue"
    )
    # Non-money counts also preserved
    assert body["bill_count"] == 3_120
    assert body["store_count"] == 1


def test_restaurant_owner_trend_bundle_revenue_not_stripped():
    """Regression: trend-bundle / daily-trend revenue also intact for owner."""
    body = {
        "points": [
            {"date": "2026-06-01", "revenue": 70_000.0, "bill_count": 104},
            {"date": "2026-06-02", "revenue": 68_500.0, "bill_count": 99},
        ]
    }
    _apply_rbac_strip(body, "restaurant_owner")
    for p in body["points"]:
        assert p["revenue"] is not None, (
            "restaurant_owner must see per-day revenue in trend-bundle"
        )
    assert body["points"][0]["revenue"] == 70_000.0
    assert body["points"][1]["revenue"] == 68_500.0
    assert body["points"][0]["bill_count"] == 104


def test_restaurant_chef_revenue_still_stripped():
    """restaurant_chef is NOT in PRICE_VIEW_ROLES — should still be stripped."""
    assert "restaurant_chef" not in PRICE_VIEW_ROLES, (
        "restaurant_chef must NOT be in PRICE_VIEW_ROLES — "
        "chefs should not see monetary data"
    )
    body = {"revenue": 50_000.0, "bill_count": 80}
    _apply_rbac_strip(body, "restaurant_chef")
    assert body["revenue"] is None
    assert body["bill_count"] == 80
