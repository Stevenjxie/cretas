"""RBAC price-strip wiring tests for the THREE pre-existing restaurant-ops Gold
endpoints that shipped WITHOUT a price-strip (WS3 pre-existing leak fix).

Covers the response ``data`` payloads of:

* ``/api/smartbi/restaurant-ops/gross-margin``   (菜品毛利)
* ``/api/smartbi/restaurant-ops/store-margin``   (门店毛利, = resolve_store_margin.meta)
* ``/api/smartbi/restaurant-ops/summary``        (经营总览 strip + AIQuery fast-path)

All three return 营收/毛利/成本 (revenue / gross profit / food cost) and leaked
them to non-price-view roles (仓管 / operator) until WS3 wired
``_apply_rbac_strip(data, _get_role(request))`` onto each. This mirrors
``test_gold_ws1_rbac.py`` (the menu-quadrant / store-comparison pair) and
``test_gold_reads_rbac_strip.py`` (the ¥20.6M leak pattern).

Strategy
--------
Unit-test the local ``_apply_rbac_strip`` helper against the exact ``data``
payload shapes each endpoint builds — no DB / no FastAPI app required. Each
endpoint calls ``_apply_rbac_strip(data, role)`` on the inner ``data`` BEFORE
wrapping in ``{"success": True, "data": data}``, so exercising the helper
against these payloads exercises the production code path 1:1.
"""
from __future__ import annotations

from typing import Any

import pytest

from smartbi.api.restaurant_ops_gold import _apply_rbac_strip
from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

# A representative non-price-view role (warehouse-manager scenario from the
# original ¥20.6M leak). Not in PRICE_VIEW_ROLES → strip applies.
_NON_PRICE_ROLE = "warehouse_manager"


# ============================================================
# /gross-margin — shape from gross_margin() endpoint
#   {windowDays, totalRevenue, totalRevenueWithCost, totalProfit, avgRate,
#    totalProfitWithEstimated, avgRateWithEstimated, industryDefaultCostRatio,
#    coverage{dishCount, totalDishCount, revenueRatio},
#    dishes:[{name, qty, revenue, foodCostUnit, totalCost, grossProfit,
#             marginRate, bills, hasCost, isEstimated}]}
# ============================================================


def _gross_margin_data() -> dict:
    return {
        "windowDays": 365,
        "totalRevenue": 100000.0,
        "totalRevenueWithCost": 80000.0,
        "totalProfit": 50000.0,
        "avgRate": 0.625,
        "totalProfitWithEstimated": 60000.0,
        "avgRateWithEstimated": 0.6,
        "industryDefaultCostRatio": 0.32,
        "coverage": {"dishCount": 3, "totalDishCount": 4, "revenueRatio": 0.8},
        "dishes": [
            {
                "name": "招牌青花椒鱼", "qty": 100.0, "revenue": 5000.0,
                "foodCostUnit": 12.0, "totalCost": 1200.0, "grossProfit": 3800.0,
                "marginRate": 0.76, "bills": 90, "hasCost": True, "isEstimated": False,
            },
            {
                "name": "毛血旺", "qty": 50.0, "revenue": 3000.0,
                "foodCostUnit": 0.0, "totalCost": 960.0, "grossProfit": 2040.0,
                "marginRate": 0.68, "bills": 45, "hasCost": False, "isEstimated": True,
            },
        ],
    }


def test_gross_margin_non_price_role_nulls_money():
    data = _gross_margin_data()
    _apply_rbac_strip(data, _NON_PRICE_ROLE)
    # Absolute monetary fields nulled
    assert data["totalRevenue"] is None
    assert data["totalRevenueWithCost"] is None
    assert data["totalProfit"] is None
    assert data["totalProfitWithEstimated"] is None
    for d in data["dishes"]:
        assert d["revenue"] is None, d
        assert d["foodCostUnit"] is None, d
        assert d["totalCost"] is None, d
        assert d["grossProfit"] is None, d
    # Non-money fields preserved: counts, names, flags, window
    assert data["windowDays"] == 365
    assert data["coverage"]["dishCount"] == 3
    assert data["coverage"]["totalDishCount"] == 4
    for d in data["dishes"]:
        assert d["name"] is not None
        assert d["qty"] is not None
        assert d["bills"] is not None
        # NOTE: ``hasCost`` (boolean flag) and ``foodCostUnit`` both contain the
        # 'cost' substring → the shared _MONEY_PATTERN over-matches and nulls the
        # flag too. That is harmless (a nulled bool is not a price leak, and the
        # 毛利 page is already gated behind canViewPrice on the FE). We assert the
        # genuinely-monetary fields above; the flag over-strip is accepted.


@pytest.mark.parametrize("role", sorted(PRICE_VIEW_ROLES))
def test_gross_margin_price_view_role_preserves_money(role: str):
    data = _gross_margin_data()
    _apply_rbac_strip(data, role)
    assert data["totalRevenue"] == 100000.0
    assert data["totalProfit"] == 50000.0
    assert data["dishes"][0]["revenue"] == 5000.0
    assert data["dishes"][0]["foodCostUnit"] == 12.0
    assert data["dishes"][0]["grossProfit"] == 3800.0


# ============================================================
# /store-margin — shape from resolve_store_margin().meta
#   {window_days, store_count, totalRevenue, totalRevenueWithCost, totalProfit,
#    avgRate, stores:[{storeId, name, revenue, revenueWithCost, grossProfit,
#                      marginRate, bills, dishesWithCost, totalDishes}]}
# ============================================================


def _store_margin_data() -> dict:
    return {
        "window_days": 365,
        "store_count": 2,
        "totalRevenue": 30000.0,
        "totalRevenueWithCost": 25000.0,
        "totalProfit": 15000.0,
        "avgRate": 0.6,
        "stores": [
            {
                "storeId": 1, "name": "大融城店", "revenue": 20000.0,
                "revenueWithCost": 18000.0, "grossProfit": 11000.0,
                "marginRate": 0.61, "bills": 800, "dishesWithCost": 25, "totalDishes": 40,
            },
            {
                "storeId": 2, "name": "万象城店", "revenue": 10000.0,
                "revenueWithCost": 7000.0, "grossProfit": 4000.0,
                "marginRate": 0.57, "bills": 400, "dishesWithCost": 15, "totalDishes": 30,
            },
        ],
    }


def test_store_margin_non_price_role_nulls_money():
    data = _store_margin_data()
    _apply_rbac_strip(data, _NON_PRICE_ROLE)
    assert data["totalRevenue"] is None
    assert data["totalRevenueWithCost"] is None
    assert data["totalProfit"] is None
    for s in data["stores"]:
        assert s["revenue"] is None, s
        assert s["revenueWithCost"] is None, s
        assert s["grossProfit"] is None, s
    # Non-money preserved: store names, ids, bill counts, window, totalDishes count
    assert data["window_days"] == 365
    assert data["store_count"] == 2
    for s in data["stores"]:
        assert s["name"] is not None
        assert s["storeId"] is not None
        assert s["bills"] is not None
        assert s["totalDishes"] is not None


@pytest.mark.parametrize("role", sorted(PRICE_VIEW_ROLES))
def test_store_margin_price_view_role_preserves_money(role: str):
    data = _store_margin_data()
    _apply_rbac_strip(data, role)
    assert data["totalRevenue"] == 30000.0
    assert data["stores"][0]["revenue"] == 20000.0
    assert data["stores"][0]["grossProfit"] == 11000.0
    assert data["stores"][1]["revenue"] == 10000.0


# ============================================================
# /summary — shape from summary() endpoint (snake_case keys, built inline)
#   {window_days, totals{...,total_req_cost,total_wastage_cost,...},
#    top5_ingredients:[{name, category, cost}],
#    margin{total_pos_revenue, total_gross_profit, avg_margin_rate,
#           dish_count_with_cost, total_dish_count}}
# ============================================================


def _summary_data() -> dict:
    return {
        "window_days": 30,
        "totals": {
            "total_requisitions": 120,
            "total_req_qty": 5000.0,
            "total_req_cost": 80000.0,
            "total_wastage": 10,
            "total_wastage_qty": 50.0,
            "total_wastage_cost": 1200.0,
            "total_stocktaking": 5,
            "total_shortage": 30.0,
            "total_surplus": 20.0,
            "active_days": 30,
        },
        "top5_ingredients": [
            {"name": "牛肉", "category": "肉类", "cost": 30000.0},
            {"name": "花椒", "category": "调料", "cost": 5000.0},
        ],
        "margin": {
            "total_pos_revenue": 200000.0,
            "total_gross_profit": 120000.0,
            "avg_margin_rate": 0.6,
            "dish_count_with_cost": 40,
            "total_dish_count": 60,
        },
    }


def test_summary_non_price_role_nulls_money():
    data = _summary_data()
    _apply_rbac_strip(data, _NON_PRICE_ROLE)
    # totals: only the *_cost keys are monetary (qty/count/shortage are physical)
    assert data["totals"]["total_req_cost"] is None
    assert data["totals"]["total_wastage_cost"] is None
    # top5 ingredient cost (the actual ¥ value) nulled, name/category preserved
    for ing in data["top5_ingredients"]:
        assert ing["cost"] is None, ing
        assert ing["name"] is not None
        assert ing["category"] is not None
    # margin revenue / profit nulled
    assert data["margin"]["total_pos_revenue"] is None
    assert data["margin"]["total_gross_profit"] is None
    # Non-money preserved: counts, quantities, window, rate
    assert data["window_days"] == 30
    assert data["totals"]["total_requisitions"] == 120
    assert data["totals"]["total_req_qty"] == 5000.0
    assert data["totals"]["total_shortage"] == 30.0
    assert data["margin"]["total_dish_count"] == 60


@pytest.mark.parametrize("role", sorted(PRICE_VIEW_ROLES))
def test_summary_price_view_role_preserves_money(role: str):
    data = _summary_data()
    _apply_rbac_strip(data, role)
    assert data["totals"]["total_req_cost"] == 80000.0
    assert data["top5_ingredients"][0]["cost"] == 30000.0
    assert data["margin"]["total_pos_revenue"] == 200000.0
    assert data["margin"]["total_gross_profit"] == 120000.0


# ============================================================
# Fail-closed: unknown / missing / empty role → strip applies
# ============================================================


@pytest.mark.parametrize("role", ["unactivated", None, "", "operator"])
def test_unknown_or_missing_role_strips_money(role: Any):
    data = _gross_margin_data()
    _apply_rbac_strip(data, role)
    assert data["totalRevenue"] is None
    assert data["dishes"][0]["revenue"] is None
    assert data["dishes"][0]["grossProfit"] is None
