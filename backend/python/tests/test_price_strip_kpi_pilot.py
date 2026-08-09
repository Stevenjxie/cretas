"""Phase 2A RBAC price-strip helper + endpoint wire-up tests.

Targets ``smartbi_compat._rbac_strip.strip_price_for_role`` and verifies the
helper is correctly invoked by every SmartBI analysis / dashboard endpoint
that returns monetary KPI cards or amount fields.

Mirrors Java ``PriceFieldResponseAdvice`` (PR #423) behavior: roles in the
white-list see KPI values intact; roles outside (e.g. ``warehouse_manager``)
get ``null`` on every monetary leaf and KPI card carrier.

Spec
----
* ``docs/qa-specs/2026-05-12-smartbi-python-port-deep-e2e-spec.md`` §11.1
* ``.claude/rules/python-java-port.md`` Rule 4 (Decimal serialization —
  stripped fields stay ``null``, never collapse to ``0``).
"""
from __future__ import annotations

import os
from datetime import date
from decimal import Decimal
from typing import Any

import jwt
import pytest

# JWT secret must be set before auth import.
os.environ.setdefault("JWT_SECRET", "price-strip-kpi-pilot-test-secret")

from smartbi_compat._rbac_strip import (  # noqa: E402
    PRICE_VIEW_ROLES,
    _is_money_key,
    _is_money_kpi_card,
    strip_price_for_role,
)
from smartbi_compat.auth import JWT_ALGORITHM  # noqa: E402

# Phase 2B-final endpoint markers (see tests/conftest.py KNOWN_ENDPOINTS).
# This file covers RBAC price-strip integration across every endpoint that
# returns monetary KPI cards. We tag the canonical endpoint names so the §6
# backfill gate credits this file toward the ≥3-tests rule for each. Any
# additional feature-level marker (e.g. ``price_strip_kpi_filter``) is
# intentionally omitted — the gate's "unknown marker" warning would block
# CI when activated.
pytestmark = [
    pytest.mark.api_endpoint("analysis_finance"),
    pytest.mark.api_endpoint("analysis_sales"),
    pytest.mark.api_endpoint("analysis_inventory"),
    pytest.mark.api_endpoint("analysis_procurement"),
    pytest.mark.api_endpoint("analysis_drilldown"),
    pytest.mark.api_endpoint("dashboard_composite_executive"),
    pytest.mark.api_endpoint("dashboard_data_date_range"),
]


# ============================================================
# Test JWT factory
#
# Sister pilot files each call ``os.environ.setdefault("JWT_SECRET", ...)``
# at import time, so when pytest collects multiple files the *first*
# imported wins and the others' bearer tokens fail signature verification.
# We sidestep this by signing tokens with whatever secret happens to be in
# the environment at call time (``_active_secret``) — matches whichever
# value won the import race, and the per-test ``_jwt_env`` fixture pins
# it to our value for isolation against sister files run in parallel.
# ============================================================

JWT_SECRET = "price-strip-kpi-pilot-test-secret"


@pytest.fixture(autouse=True)
def _jwt_env(monkeypatch):
    """Force ``JWT_SECRET`` to our value for the duration of each test —
    survives import-order collisions with sister ``test_analysis_*_pilot``
    files that set their own secret at module load."""
    monkeypatch.setenv("JWT_SECRET", JWT_SECRET)
    yield


def _active_secret() -> str:
    """Read the JWT secret from the environment at call time so our tokens
    match whatever ``auth.verify_jwt_and_factory`` will use when validating."""
    return os.environ.get("JWT_SECRET", JWT_SECRET)


def _make_token(*, role: str, factory_id: str = "F001", user_id: int = 42) -> str:
    from time import time

    payload: dict = {
        "userId": user_id,
        "username": f"user-{role}",
        "role": role,
        "exp": int(time()) + 3600,
    }
    if factory_id is not None:
        payload["factoryId"] = factory_id
    return jwt.encode(payload, _active_secret(), algorithm=JWT_ALGORITHM)


def _auth(role: str, factory_id: str = "F001") -> dict:
    return {"Authorization": f"Bearer {_make_token(role=role, factory_id=factory_id)}"}


# ============================================================
# Section A — Helper unit tests (≥10 cases)
# ============================================================


def test_price_view_roles_match_java_whitelist():
    """``PRICE_VIEW_ROLES`` 必须与 Java ``PermissionServiceImpl`` 一致。

    ⛔ 2026-08-10 改法: 期望值**从 Java 源码解析**, 不再手抄。

    原来是手抄的 10 个角色 + 一句「keep this assertion in sync with line
    264-278」。Java 后来加了 `restaurant_owner` / `restaurant_purchaser`
    (注释: 餐饮老板/采购需查看采购价格, 价格异常审批需对比价格), Python 侧跟上了,
    **只有这份手抄没跟上** —— 于是用例红了两个多月, 被挂进 ci-gate-excludes.txt,
    看起来还像是「Python 比 Java 宽松」的权限缺陷。

    判据: **判据里出现手写清单, 就问「这张表错了会怎样」**。这里的答案是: 它会
    伪装成一个安全问题。手抄的表没有权威性 —— 权威是 Java 那份, 就去读它。
    """
    import re
    from pathlib import Path

    java = (
        Path(__file__).resolve().parents[3]
        / "backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl"
        / "PermissionServiceImpl.java"
    )
    if not java.exists():        # 纯 python 检出时跳过, 但要说清为什么
        pytest.skip(f"Java 源码不在此检出中, 无法比对权威白名单: {java}")

    block = re.search(
        r"PRICE_VIEW_ROLES\s*=\s*Set\.of\((.*?)\);", java.read_text(encoding="utf-8"),
        re.S)
    assert block, "Java 侧找不到 PRICE_VIEW_ROLES = Set.of(...) —— 先修这条解析"
    expected = set(re.findall(r"FactoryUserRole\.(\w+)", block.group(1)))
    assert expected, "解析出 0 个角色 —— 正则与 Java 写法脱节了"

    assert set(PRICE_VIEW_ROLES) == expected, (
        f"与 Java 白名单不一致。Python 多出 {set(PRICE_VIEW_ROLES) - expected}, "
        f"少了 {expected - set(PRICE_VIEW_ROLES)}")


def test_admin_role_pass_through():
    """``factory_super_admin`` retains every monetary leaf."""
    body = {
        "totalAmount": 12345.67,
        "kpiCards": [
            {"key": "total_revenue", "title": "总营收", "value": "12,345.67元",
             "rawValue": Decimal("12345.67"), "unit": "元"},
        ],
    }
    result = strip_price_for_role(body, "factory_super_admin")
    assert result["totalAmount"] == 12345.67
    assert result["kpiCards"][0]["value"] == "12,345.67元"
    assert result["kpiCards"][0]["rawValue"] == Decimal("12345.67")


def test_warehouse_role_strips_kpi_cards():
    """Non-white-listed role nulls every money KPI card carrier."""
    body = {
        "kpiCards": [
            {"key": "total_revenue", "title": "总营收", "value": "12,345.67元",
             "rawValue": Decimal("12345.67"), "unit": "元",
             "change": Decimal("1000"), "changeRate": Decimal("8.5")},
            {"key": "bill_count", "title": "账单数", "value": "123",
             "rawValue": 123, "unit": "单"},
        ],
    }
    result = strip_price_for_role(body, "warehouse_manager")
    money_card = result["kpiCards"][0]
    count_card = result["kpiCards"][1]
    # Money card: carriers nulled, rate preserved
    assert money_card["value"] is None
    assert money_card["rawValue"] is None
    assert money_card["change"] is None
    assert money_card["changeRate"] == Decimal("8.5")  # rate preserved
    # Non-money card untouched
    assert count_card["value"] == "123"
    assert count_card["rawValue"] == 123


def test_unknown_role_strips():
    """Unknown / empty / None role is treated as ineligible (strip applied)."""
    body = {"totalAmount": 999}
    assert strip_price_for_role({"totalAmount": 999}, None) == {"totalAmount": None}
    assert strip_price_for_role({"totalAmount": 999}, "") == {"totalAmount": None}
    assert strip_price_for_role({"totalAmount": 999}, "unactivated") == {"totalAmount": None}
    # ``body`` outer reference was not consumed — independent calls above
    assert body == {"totalAmount": 999}


def test_direct_money_key_leaf_nulled():
    """Top-level money-named leaf values are nulled regardless of type."""
    body = {
        "totalAmount": 12345.67,
        "unitPrice": Decimal("99.99"),
        "purchaseCost": 5000,
        "qty": 100,
        "date": "2026-05-12",
    }
    result = strip_price_for_role(body, "warehouse_manager")
    assert result["totalAmount"] is None
    assert result["unitPrice"] is None
    assert result["purchaseCost"] is None
    # Non-money keys untouched
    assert result["qty"] == 100
    assert result["date"] == "2026-05-12"


def test_money_key_container_recurses():
    """A money-named key whose value is a container recurses, so nested
    KPI cards inside (e.g. ``budgetMetrics``) get stripped, but non-money
    leaves at that level remain visible."""
    body = {
        "budgetMetrics": {
            "metricName": "预算执行率",
            "value": "85%",
            "completionRate": Decimal("0.85"),
            "label": "完成率",
        },
        "spendingDetails": [
            {"category": "原料", "totalAmount": 12000, "qty": 50},
        ],
    }
    result = strip_price_for_role(body, "warehouse_manager")
    # Top-level money key is a container → walked, not nulled
    assert isinstance(result["budgetMetrics"], dict)
    # Inside the container, money-pattern keys/cards get stripped
    assert result["spendingDetails"][0]["totalAmount"] is None
    assert result["spendingDetails"][0]["qty"] == 50
    assert result["spendingDetails"][0]["category"] == "原料"


def test_money_kpi_card_detected_by_unit():
    """A KPI card with ``unit=元`` is monetary even if its title is neutral."""
    card = {"key": "kpi_001", "title": "总计", "value": "1,000元", "rawValue": Decimal("1000"), "unit": "元"}
    assert _is_money_kpi_card(card) is True

    body = {"summary": card.copy()}
    result = strip_price_for_role(body, "operator")
    assert result["summary"]["value"] is None
    assert result["summary"]["rawValue"] is None


def test_non_money_kpi_card_preserved():
    """KPI cards whose title is a count / quantity stay visible."""
    cards = [
        {"key": "bill_count", "title": "账单数", "value": "123", "rawValue": 123, "unit": "单"},
        {"key": "store_count", "title": "门店数", "value": "5", "rawValue": 5, "unit": "家"},
    ]
    for c in cards:
        assert _is_money_kpi_card(c) is False
    body = {"kpiCards": cards}
    result = strip_price_for_role(body, "warehouse_manager")
    assert result["kpiCards"][0]["value"] == "123"
    assert result["kpiCards"][1]["rawValue"] == 5


def test_deeply_nested_structure():
    """Strip survives nested lists / dicts / lists / dicts."""
    body = {
        "data": {
            "regions": [
                {
                    "regionName": "华东",
                    "stores": [
                        {
                            "storeId": "S001",
                            "metrics": {
                                "kpi": {
                                    "key": "store_revenue",
                                    "title": "门店营收",
                                    "value": "5000元",
                                    "rawValue": Decimal("5000"),
                                    "unit": "元",
                                },
                                "orderCount": 50,
                            },
                        },
                    ],
                },
            ],
        },
    }
    strip_price_for_role(body, "warehouse_manager")
    kpi = body["data"]["regions"][0]["stores"][0]["metrics"]["kpi"]
    assert kpi["value"] is None
    assert kpi["rawValue"] is None
    assert body["data"]["regions"][0]["stores"][0]["metrics"]["orderCount"] == 50


def test_idempotent():
    """Re-applying the strip is a no-op (sensitive fields already ``None``)."""
    body = {
        "totalAmount": 999,
        "kpiCards": [{"key": "revenue", "title": "营收", "value": "999元",
                      "rawValue": Decimal("999"), "unit": "元"}],
    }
    once = strip_price_for_role(body, "warehouse_manager")
    snapshot = {
        "totalAmount": once["totalAmount"],
        "value": once["kpiCards"][0]["value"],
        "rawValue": once["kpiCards"][0]["rawValue"],
    }
    twice = strip_price_for_role(once, "warehouse_manager")
    assert twice["totalAmount"] == snapshot["totalAmount"] is None
    assert twice["kpiCards"][0]["value"] == snapshot["value"] is None
    assert twice["kpiCards"][0]["rawValue"] == snapshot["rawValue"] is None


def test_none_and_empty_payloads():
    """``None`` body returns ``None``; empty dict / list pass through clean."""
    assert strip_price_for_role(None, "warehouse_manager") is None
    assert strip_price_for_role({}, "warehouse_manager") == {}
    assert strip_price_for_role([], "warehouse_manager") == []


def test_rule4_invariant_null_not_zero():
    """Stripped fields must be ``None`` (JSON ``null``), never ``0``/``""``
    (preserves the missing-vs-zero distinction Rule 4 mandates)."""
    body = {
        "totalAmount": Decimal("0.00"),       # legitimate zero
        "salesRevenue": Decimal("12345.67"),  # actual money
    }
    strip_price_for_role(body, "warehouse_manager")
    # Both stripped to None (no special-case for zero), preserving the
    # null contract — caller can tell "stripped" from "actual zero".
    assert body["totalAmount"] is None
    assert body["salesRevenue"] is None
    # And specifically NOT 0 / "" / [] / {}
    assert body["totalAmount"] != 0
    assert body["totalAmount"] != ""


def test_helper_does_not_mutate_when_role_eligible():
    """Eligible role pass-through must not even touch the body — identity check."""
    body: dict[str, Any] = {"totalAmount": 100, "nested": {"unitPrice": 9.9}}
    snapshot_outer_id = id(body)
    snapshot_nested_id = id(body["nested"])
    result = strip_price_for_role(body, "finance_manager")
    assert id(result) == snapshot_outer_id
    assert id(result["nested"]) == snapshot_nested_id
    assert result["totalAmount"] == 100
    assert result["nested"]["unitPrice"] == 9.9


def test_money_key_detection_pattern():
    """Spot-check the key-name detector — common positives and negatives."""
    # English positives
    for k in (
        "totalAmount", "unitPrice", "grossProfit", "netProfit", "salesRevenue",
        "purchaseCost", "materialCost", "receivableAmount", "payableAmount",
        "budgetSpending", "monthlyIncome", "payrollTotal", "GMV", "Turnover",
    ):
        assert _is_money_key(k), f"expected positive: {k}"

    # English negatives
    for k in (
        "qty", "date", "createdAt", "factoryId", "billCount", "storeId",
        "name", "unit", "status", "description", "category", "rate", "level",
    ):
        assert not _is_money_key(k), f"expected negative: {k}"

    # Chinese positives
    for k in ("销售额", "采购金额", "应收账款", "应付账款", "毛利率",  # 毛利 in 毛利率
              "净利润", "营收总额", "客单价", "总营收"):
        assert _is_money_key(k), f"expected positive (zh): {k}"

    # Chinese negatives
    for k in ("账单数", "门店数", "状态", "类别", "日期"):
        assert not _is_money_key(k), f"expected negative (zh): {k}"


# ============================================================
# Section B — Endpoint integration tests (6 modules × admin/warehouse)
#
# Each test patches the internal data-fetch function to a synthetic payload
# containing KPI cards + direct money fields, then asserts admin sees the
# values and warehouse_manager gets ``null``.
# ============================================================


@pytest.fixture
def fastapi_client():
    """Single TestClient bound to a FastAPI app with every endpoint router
    mounted — keeps each integration test self-contained."""
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from smartbi_compat.api import (
        analysis_drilldown,
        analysis_finance,
        analysis_inventory,
        analysis_procurement,
        analysis_sales,
        dashboard,
        dashboard_composite,
    )

    app = FastAPI()
    app.include_router(analysis_finance.router)
    app.include_router(analysis_sales.router)
    app.include_router(analysis_inventory.router)
    app.include_router(analysis_procurement.router)
    app.include_router(analysis_drilldown.router)
    app.include_router(dashboard.router)
    app.include_router(dashboard_composite.router)
    return TestClient(app)


# Synthetic payloads mirror the actual response shape for each endpoint —
# realistic enough that a real strip walk hits the expected keys.

# Synthetic payloads use plain ints/floats — the real per-type handlers
# pass results through ``_decimal_to_number`` (Rule 4) before they reach
# ``wrap_response``, so mocking with raw Decimal would diverge from the
# actual wire shape (FastAPI serializes Decimal as a JSON string).
_SYNTHETIC_FINANCE = {
    "kpiCards": [
        {"key": "total_revenue", "title": "总营收", "value": "12,345元",
         "rawValue": 12345, "unit": "元", "change": 100,
         "changeRate": 5.5},
        {"key": "bill_count", "title": "账单数", "value": "200",
         "rawValue": 200, "unit": "单"},
    ],
    "rankings": {},
    "charts": {"trendChart": {"options": {"xAxis": {}, "yAxis": {}}}},
    "dateRange": {"startDate": "2026-05-01", "endDate": "2026-05-12"},
    "totalAmount": 12345,
}

_SYNTHETIC_SALES = {
    "kpiCards": [
        {"key": "store_revenue", "title": "门店营收", "value": "9,999元",
         "rawValue": 9999, "unit": "元"},
    ],
    "salesAmount": 9999,
    "orderCount": 88,
}

_SYNTHETIC_INVENTORY = {
    "overview": {
        "kpiCards": [
            {"key": "inventory_value", "title": "库存价值", "value": "50,000元",
             "rawValue": 50000, "unit": "元"},
            {"key": "sku_count", "title": "SKU数", "value": "120",
             "rawValue": 120, "unit": "种"},
        ],
        "totalCost": 50000,
    }
}

_SYNTHETIC_PROCUREMENT = {
    "kpiCards": [
        {"key": "procurement_amount", "title": "采购总额", "value": "20,000元",
         "rawValue": 20000, "unit": "元"},
    ],
    "purchaseCost": 20000,
    "supplierCount": 12,
}

_SYNTHETIC_DRILLDOWN = {
    "drillPath": ["product"],
    "level": 1,
    "dimension": "product",
    "data": [
        {"productName": "鱼丸", "salesRevenue": 3000.0, "qty": 30},
        {"productName": "牛丸", "salesRevenue": 4500.0, "qty": 45},
    ],
}

_SYNTHETIC_DASHBOARD_EXEC = {
    "kpiCards": [
        {"key": "exec_revenue", "title": "高管营收", "value": "100,000元",
         "rawValue": 100000, "unit": "元"},
    ],
    "totalRevenue": 100000,
    "metricCount": 8,
}


async def _async_return(value):
    """Helper: turn a value into an awaitable for monkeypatch."""
    return value


def _patch_async(monkeypatch, module, attr, value):
    async def _f(*args, **kwargs):
        return value

    monkeypatch.setattr(module, attr, _f)


# ── analysis_finance composite ────────────────────────────────────────────


def test_endpoint_finance_admin_sees_kpi_values(monkeypatch, fastapi_client):
    from smartbi_compat.api import analysis_finance

    _patch_async(monkeypatch, analysis_finance, "_get_comprehensive_finance_analysis",
                 {"kpiCards": [dict(c) for c in _SYNTHETIC_FINANCE["kpiCards"]],
                  "totalAmount": 12345})
    r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/analysis/finance",
        params={"startDate": "2026-05-01", "endDate": "2026-05-12"},
        headers=_auth("factory_super_admin"),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    cards = body["data"]["kpiCards"]
    # Admin sees real values
    assert cards[0]["value"] == "12,345元"
    assert cards[0]["rawValue"] == 12345
    assert body["data"]["totalAmount"] == 12345


def test_endpoint_finance_warehouse_sees_null_kpi(monkeypatch, fastapi_client):
    from smartbi_compat.api import analysis_finance

    _patch_async(monkeypatch, analysis_finance, "_get_comprehensive_finance_analysis",
                 {"kpiCards": [dict(c) for c in _SYNTHETIC_FINANCE["kpiCards"]],
                  "totalAmount": 12345})
    r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/analysis/finance",
        params={"startDate": "2026-05-01", "endDate": "2026-05-12"},
        # ⚠️ 2026-08-10: 本用例原本用 warehouse_manager。Java PERMISSION_MATRIX
        # 里 warehouse_manager **没有 analytics 这一项**(只有 dashboard/warehouse/
        # production/scheduling/inventory/report/procurement/sales), 所以它连端点
        # 都进不来, 拿到的是 403 而不是「null 金额」—— 用例想验的「有分析权限但无
        # 调价权限 → 金额被剥空」它已经承载不了了。
        # 求差集 ANALYTICS_READ_ROLES - PRICE_VIEW_ROLES 得到 viewer, 换成它。
        # warehouse_manager 的真实行为(403)另起用例钉住, 不让它悄悄消失。
        headers=_auth("viewer"),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    cards = body["data"]["kpiCards"]
    # Money KPI nulled, count KPI preserved
    assert cards[0]["value"] is None
    assert cards[0]["rawValue"] is None
    assert cards[1]["value"] == "200"
    assert cards[1]["rawValue"] == 200
    assert body["data"]["totalAmount"] is None


# ── analysis_sales ────────────────────────────────────────────────────────


def test_endpoint_sales_admin_vs_warehouse(monkeypatch, fastapi_client):
    from smartbi_compat.api import analysis_sales

    def make_payload():
        return {"kpiCards": [dict(c) for c in _SYNTHETIC_SALES["kpiCards"]],
                "salesAmount": 9999, "orderCount": 88}

    _patch_async(monkeypatch, analysis_sales, "_get_comprehensive_sales_analysis",
                 make_payload())
    admin_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/analysis/sales",
        params={"startDate": "2026-05-01", "endDate": "2026-05-12"},
        headers=_auth("sales_manager"),
    )
    assert admin_r.status_code == 200, admin_r.text
    assert admin_r.json()["data"]["salesAmount"] == 9999

    # Re-patch (each test re-binds the fixture function)
    _patch_async(monkeypatch, analysis_sales, "_get_comprehensive_sales_analysis",
                 make_payload())
    # 同上: warehouse_manager 无 analytics 权限, 换成 viewer(有分析、无调价)。
    warehouse_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/analysis/sales",
        params={"startDate": "2026-05-01", "endDate": "2026-05-12"},
        headers=_auth("viewer"),
    )
    assert warehouse_r.status_code == 200, warehouse_r.text
    body = warehouse_r.json()
    assert body["data"]["kpiCards"][0]["value"] is None
    assert body["data"]["kpiCards"][0]["rawValue"] is None
    assert body["data"]["salesAmount"] is None
    assert body["data"]["orderCount"] == 88  # non-money preserved


# ── analysis_inventory ────────────────────────────────────────────────────


def test_endpoint_inventory_admin_vs_warehouse(monkeypatch, fastapi_client):
    from smartbi_compat.api import analysis_inventory

    def make_payload():
        return {"overview": {
            "kpiCards": [dict(c) for c in _SYNTHETIC_INVENTORY["overview"]["kpiCards"]],
            "totalCost": 50000}}

    # ``analysis_inventory`` default path goes through ``_get_default_mode`` —
    # patch that. ``analysisType`` omitted falls into the default branch.
    if hasattr(analysis_inventory, "_get_default_mode"):
        _patch_async(monkeypatch, analysis_inventory, "_get_default_mode", make_payload())
    else:
        # Some refactors renamed it; patch turnover as a fallback test path.
        _patch_async(monkeypatch, analysis_inventory, "_get_turnover_mode", make_payload())

    admin_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/analysis/inventory",
        params={"startDate": "2026-05-01", "endDate": "2026-05-12",
                "analysisType": "turnover" if not hasattr(analysis_inventory, "_get_default_mode") else None},
        headers=_auth("factory_super_admin"),
    )
    assert admin_r.status_code == 200, admin_r.text
    cards = admin_r.json()["data"]["overview"]["kpiCards"]
    assert cards[0]["rawValue"] == 50000

    # Warehouse
    if hasattr(analysis_inventory, "_get_default_mode"):
        _patch_async(monkeypatch, analysis_inventory, "_get_default_mode", make_payload())
    else:
        _patch_async(monkeypatch, analysis_inventory, "_get_turnover_mode", make_payload())
    warehouse_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/analysis/inventory",
        params={"startDate": "2026-05-01", "endDate": "2026-05-12",
                "analysisType": "turnover" if not hasattr(analysis_inventory, "_get_default_mode") else None},
        headers=_auth("viewer"),
    )
    body = warehouse_r.json()
    overview = body["data"]["overview"]
    assert overview["kpiCards"][0]["value"] is None
    assert overview["kpiCards"][0]["rawValue"] is None
    assert overview["kpiCards"][1]["value"] == "120"   # sku_count untouched
    assert overview["totalCost"] is None


# ── analysis_procurement ──────────────────────────────────────────────────


def test_endpoint_procurement_admin_vs_warehouse(monkeypatch, fastapi_client):
    from smartbi_compat.api import analysis_procurement

    def make_payload():
        return {"kpiCards": [dict(c) for c in _SYNTHETIC_PROCUREMENT["kpiCards"]],
                "purchaseCost": 20000, "supplierCount": 12}

    _patch_async(monkeypatch, analysis_procurement, "_get_procurement_analysis",
                 make_payload())
    # ⚠️ admin 这一侧要的是「**有** analytics 且 **有** price-view」。
    #    procurement_manager 有 price-view 但**没有 analytics**(Java 矩阵里它只有
    #    dashboard/procurement/warehouse/production/finance/inventory/report),
    #    所以它当不了这一侧 —— 用 finance_manager(两者都有)。
    #    ⛔ 我 2026-08-10 用全局替换把这里也换成了 viewer, 结果 admin 侧和被剥空
    #       的一侧变成同一个角色, 断言退化成「None == 20000」。判据: **批量替换
    #       角色名前先分清每一处是「允许」还是「拒绝」那一侧。**
    admin_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/analysis/procurement",
        params={"startDate": "2026-05-01", "endDate": "2026-05-12"},
        headers=_auth("finance_manager"),
    )
    assert admin_r.status_code == 200, admin_r.text
    assert admin_r.json()["data"]["purchaseCost"] == 20000

    _patch_async(monkeypatch, analysis_procurement, "_get_procurement_analysis",
                 make_payload())
    warehouse_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/analysis/procurement",
        params={"startDate": "2026-05-01", "endDate": "2026-05-12"},
        headers=_auth("viewer"),
    )
    body = warehouse_r.json()
    assert body["data"]["kpiCards"][0]["rawValue"] is None
    assert body["data"]["purchaseCost"] is None
    assert body["data"]["supplierCount"] == 12


# ── dashboard_composite (executive) ───────────────────────────────────────


def test_endpoint_dashboard_executive_admin_vs_warehouse(monkeypatch, fastapi_client):
    from smartbi_compat.api import dashboard_composite

    def make_payload():
        return {"kpiCards": [dict(c) for c in _SYNTHETIC_DASHBOARD_EXEC["kpiCards"]],
                "totalRevenue": 100000, "metricCount": 8}

    _patch_async(monkeypatch, dashboard_composite, "_build_executive_dashboard",
                 make_payload())
    admin_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/dashboard/executive",
        params={"period": "month"},
        headers=_auth("factory_super_admin"),
    )
    assert admin_r.status_code == 200, admin_r.text
    assert admin_r.json()["data"]["totalRevenue"] == 100000

    _patch_async(monkeypatch, dashboard_composite, "_build_executive_dashboard",
                 make_payload())
    warehouse_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/dashboard/executive",
        params={"period": "month"},
        headers=_auth("viewer"),
    )
    body = warehouse_r.json()
    assert body["data"]["kpiCards"][0]["value"] is None
    assert body["data"]["kpiCards"][0]["rawValue"] is None
    assert body["data"]["totalRevenue"] is None
    assert body["data"]["metricCount"] == 8


# ── dashboard (data-date-range) ───────────────────────────────────────────


def test_endpoint_dashboard_date_range_admin_vs_warehouse(monkeypatch, fastapi_client):
    """``data-date-range`` returns a date envelope. No money fields → no
    strip impact, but the wire-up still has to compile and execute cleanly
    for both roles."""
    from smartbi_compat.api import dashboard

    def fake_query(factory_id):
        return (date(2026, 1, 1), date(2026, 5, 12))

    monkeypatch.setattr(dashboard, "_query_date_range", fake_query)
    admin_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/data-date-range",
        headers=_auth("factory_super_admin"),
    )
    assert admin_r.status_code == 200, admin_r.text
    payload = admin_r.json()["data"]
    assert payload["hasData"] is True
    assert payload["startDate"] == "2026-01-01"

    warehouse_r = fastapi_client.get(
        "/api/mobile/F001/smart-bi/data-date-range",
        headers=_auth("viewer"),
    )
    body = warehouse_r.json()
    # No money fields in this envelope — both roles see identical data.
    assert body["data"]["hasData"] is True
    assert body["data"]["startDate"] == "2026-01-01"
    assert body["data"]["endDate"] == "2026-05-12"


# ── analysis_drilldown ────────────────────────────────────────────────────


def test_endpoint_drilldown_admin_vs_warehouse(monkeypatch, fastapi_client):
    from smartbi_compat.api import analysis_drilldown

    def make_payload():
        return {
            "drillPath": ["product"],
            "level": 1,
            "dimension": "product",
            "data": [
                {"productName": "鱼丸", "salesRevenue": 3000.0, "qty": 30},
                {"productName": "牛丸", "salesRevenue": 4500.0, "qty": 45},
            ],
        }

    _patch_async(monkeypatch, analysis_drilldown, "_process_drilldown_tx",
                 make_payload())

    payload = {"dimension": "product", "level": 1}
    admin_r = fastapi_client.post(
        "/api/mobile/F001/smart-bi/drill-down",
        json=payload,
        headers={**_auth("factory_super_admin"), "Content-Type": "application/json"},
    )
    assert admin_r.status_code == 200, admin_r.text
    rows = admin_r.json()["data"]["data"]
    assert rows[0]["salesRevenue"] == 3000.0

    _patch_async(monkeypatch, analysis_drilldown, "_process_drilldown_tx",
                 make_payload())
    warehouse_r = fastapi_client.post(
        "/api/mobile/F001/smart-bi/drill-down",
        json=payload,
        headers={**_auth("warehouse_manager"), "Content-Type": "application/json"},
    )
    rows = warehouse_r.json()["data"]["data"]
    assert rows[0]["salesRevenue"] is None
    assert rows[0]["qty"] == 30
    assert rows[0]["productName"] == "鱼丸"


def test_roles_without_analytics_permission_are_rejected_not_stripped(fastapi_client):
    """无 analytics 权限的角色拿到的是 **403**, 不是「金额被剥空的 200」。

    ⚠️ 这条是 2026-08-10 新加的, 用来钉住上面那批用例**换掉的那个行为**。

    本文件原先用 warehouse_manager / procurement_manager 来验「非调价角色看到
    null 金额」。但 Java PERMISSION_MATRIX 里这两个角色**都没有 analytics 这一项**
    (warehouse: dashboard/warehouse/production/scheduling/inventory/report/
    procurement/sales; procurement: dashboard/procurement/warehouse/production/
    finance/inventory/report), 所以它们连端点都进不来 —— 用例想验的东西它们已经
    承载不了了, 换成了 viewer(有 analytics、无 price-view)。

    判据: **换掉一个用例的载体时, 要给被换掉的那个行为单独立一条**, 否则「这两个
    角色其实是 403」这个事实会随着改动悄悄消失, 下次有人放宽 RBAC 也没人拦。
    """
    from smartbi_compat._rbac_role import RbacForbiddenException

    for role in ("warehouse_manager", "procurement_manager"):
        with pytest.raises(RbacForbiddenException) as exc:
            fastapi_client.get(
                "/api/mobile/F001/smart-bi/analysis/finance",
                params={"startDate": "2026-05-01", "endDate": "2026-05-12"},
                headers=_auth(role),
            )
        assert "analytics:read" in str(exc.value)
