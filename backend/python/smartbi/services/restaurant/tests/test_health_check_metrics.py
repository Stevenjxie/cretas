"""G4 — Unit tests for HealthCheckMetricsBuilder.

Pools are mocked (no DB). We exercise the pure aggregation/scale logic by
injecting fake row sets via monkeypatched private query helpers, and verify:
  - finance ratios stay 0-100 scale (matches benchmark range [35,45])
  - POS ratios stay 0-1 scale (matches inline thresholds)
  - coverage records skip reasons honestly (no fabricated metrics)
  - cost_rigidity only present when revenue declined
"""
from __future__ import annotations

import asyncio
from datetime import date

from smartbi.services.restaurant.health_check_metrics import (
    HealthCheckBundle,
    HealthCheckMetricsBuilder,
    _resolve_month_range,
)


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


# ── month range resolution (mirror Java RestaurantFinancialMetricsFetcher) ──


def test_resolve_month_range_explicit():
    start, end = _resolve_month_range("2026-04")
    assert start == date(2026, 4, 1)
    assert end == date(2026, 4, 30)


def test_resolve_month_range_chinese_format():
    start, end = _resolve_month_range("2026年2月")
    assert start == date(2026, 2, 1)
    assert end == date(2026, 2, 28)


def test_resolve_month_range_default_is_last_month():
    # None → previous month; just verify it's the 1st and a valid month-end.
    start, end = _resolve_month_range(None)
    assert start.day == 1
    assert end >= start


# ── builder: finance-only (no POS data) ────────────────────────────


def test_build_finance_only_no_pos(monkeypatch):
    builder = HealthCheckMetricsBuilder()

    finance = {
        "foodCostRatio": 48.3,   # 0-100 scale
        "laborCostRatio": 28.0,
        "costRigidity": None,
        "revenueChangePct": 0.02,  # revenue grew → cost_rigidity skip
        "revenue": 100000.0,
    }

    async def _empty_pos(self, fid, start, end, **kw):
        return {}  # no discount/delivery rows

    async def _empty_review(self, fid, start, end, **kw):
        return None  # no review trend

    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_pos_metrics", _empty_pos)
    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_review_decline", _empty_review)

    bundle = _run(builder.build(
        factory_id="RES_3101_009",
        period="2026-04",
        sub_sector="鱼类餐饮",
        finance_metrics=finance,
    ))
    assert isinstance(bundle, HealthCheckBundle)
    # finance ratios present, 0-100 scale unchanged
    assert bundle.metrics["food_cost_ratio"] == 48.3
    assert bundle.metrics["labor_cost_ratio"] == 28.0
    # cost_rigidity skipped because revenue did not decline
    assert "cost_rigidity" not in bundle.metrics
    assert bundle.coverage["cost_rigidity"].startswith("skipped")
    # POS metrics skipped
    assert "discount_rate" not in bundle.metrics
    assert bundle.coverage["delivery_dependency"].startswith("skipped")
    assert bundle.coverage["review_score_decline"].startswith("skipped")


def test_scale_food_cost_ratio_stays_100_scale(monkeypatch):
    """food_cost_ratio MUST be passed to engine on 0-100 scale (NOT /100)."""
    builder = HealthCheckMetricsBuilder()

    async def _empty_pos(self, fid, start, end, **kw):
        return {}

    async def _empty_review(self, fid, start, end, **kw):
        return None

    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_pos_metrics", _empty_pos)
    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_review_decline", _empty_review)

    bundle = _run(builder.build(
        factory_id="F", period="2026-04",
        finance_metrics={"foodCostRatio": 42.1, "laborCostRatio": 22.0,
                         "revenueChangePct": 0.0},
    ))
    # 42.1 NOT 0.421
    assert bundle.metrics["food_cost_ratio"] == 42.1
    assert bundle.metrics["food_cost_ratio"] > 1


def test_cost_rigidity_present_when_revenue_declines(monkeypatch):
    builder = HealthCheckMetricsBuilder()

    async def _empty_pos(self, fid, start, end, **kw):
        return {}

    async def _empty_review(self, fid, start, end, **kw):
        return None

    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_pos_metrics", _empty_pos)
    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_review_decline", _empty_review)

    bundle = _run(builder.build(
        factory_id="F", period="2026-04",
        finance_metrics={
            "foodCostRatio": 40.0, "laborCostRatio": 24.0,
            "revenueChangePct": -0.10,   # revenue fell
            "costRigidity": 0.45,        # Java already computed it (0-1 scale)
        },
    ))
    assert bundle.metrics["cost_rigidity"] == 0.45
    assert bundle.coverage["cost_rigidity"] == "ok"


# ── builder: POS-only (no finance) ─────────────────────────────────


def test_build_pos_only_no_finance(monkeypatch):
    builder = HealthCheckMetricsBuilder()

    async def _pos(self, fid, start, end, **kw):
        # 0-1 scale POS metrics
        return {
            "discount_rate": 18.5,            # discount_rate benchmark is %-scale
            "delivery_dependency": 0.72,      # 0-1
            "channel_collection_rate": 0.68,  # 0-1
        }

    async def _review(self, fid, start, end, **kw):
        return None

    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_pos_metrics", _pos)
    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_review_decline", _review)

    bundle = _run(builder.build(
        factory_id="RES_3101_009", period="2026-04", sub_sector="鱼类餐饮",
        finance_metrics=None,
    ))
    # POS present, 0-1 scale unchanged
    assert bundle.metrics["delivery_dependency"] == 0.72
    assert bundle.metrics["channel_collection_rate"] == 0.68
    # finance metrics skipped (none injected, no pool to query)
    assert "food_cost_ratio" not in bundle.metrics
    assert bundle.coverage["food_cost_ratio"].startswith("skipped")


def test_build_full_all_metrics(monkeypatch):
    builder = HealthCheckMetricsBuilder()

    async def _pos(self, fid, start, end, **kw):
        return {"discount_rate": 12.0, "delivery_dependency": 0.55,
                "channel_collection_rate": 0.74}

    async def _review(self, fid, start, end, **kw):
        return -0.03  # 5-star pct dropped 3pp

    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_pos_metrics", _pos)
    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_review_decline", _review)

    bundle = _run(builder.build(
        factory_id="RES_3101_009", period="2026-04", sub_sector="鱼类餐饮",
        finance_metrics={"foodCostRatio": 48.3, "laborCostRatio": 28.0,
                         "revenueChangePct": -0.08, "costRigidity": 0.45},
    ))
    for k in ("food_cost_ratio", "labor_cost_ratio", "cost_rigidity",
              "discount_rate", "delivery_dependency", "channel_collection_rate",
              "review_score_decline"):
        assert k in bundle.metrics, f"{k} missing"
        assert bundle.coverage[k] == "ok"
    assert bundle.metrics["review_score_decline"] == -0.03


# ── Fix 1: commission estimate must canonicalize short channel values ──
# fact_pos_transaction.channel_origin stores SHORT forms (美团/抖音/饿了么),
# but commission_rates.yaml keys are FULL names (美团外卖/抖音外卖/饿了么).
# Without alias mapping, default_rates.get('美团') → None → 0% commission →
# channel_collection_rate ≈ 1.0 false-healthy.


def test_estimate_commission_short_channel_resolves_rate():
    """'美团' short value must map to '美团外卖' rate (0.20), commission > 0."""
    builder = HealthCheckMetricsBuilder()
    chan_rows = [
        {"channel_origin": "美团", "order_type": "外卖", "net": 10000.0},
    ]
    commission = builder._estimate_commission(chan_rows, sub_sector="")
    # 10000 * 0.20 (美团外卖 default) = 2000
    assert commission > 0, "short channel '美团' must resolve to a non-zero rate"
    assert abs(commission - 2000.0) < 1e-6, f"expected 2000, got {commission}"


def test_estimate_commission_douyin_eleme_short_values():
    """'抖音'→抖音外卖 0.22, '饿了么' already canonical 0.20."""
    builder = HealthCheckMetricsBuilder()
    chan_rows = [
        {"channel_origin": "抖音", "order_type": "外卖", "net": 5000.0},
        {"channel_origin": "饿了么", "order_type": "外卖", "net": 5000.0},
    ]
    commission = builder._estimate_commission(chan_rows, sub_sector="")
    # 5000*0.22 + 5000*0.20 = 1100 + 1000 = 2100
    assert abs(commission - 2100.0) < 1e-6, f"expected 2100, got {commission}"


def test_estimate_commission_dine_in_zero_rate():
    """堂食/自点 (self-order dine-in) → 0% commission (no platform cut)."""
    builder = HealthCheckMetricsBuilder()
    chan_rows = [
        {"channel_origin": "自点", "order_type": "堂食", "net": 10000.0},
        {"channel_origin": None, "order_type": "堂食", "net": 5000.0},
    ]
    commission = builder._estimate_commission(chan_rows, sub_sector="")
    assert commission == 0.0


def test_channel_collection_rate_not_falsely_healthy_for_delivery():
    """End-to-end: delivery-heavy POS with short channel values must NOT
    produce channel_collection_rate ≈ 1.0 (the false-healthy bug)."""
    builder = HealthCheckMetricsBuilder()
    chan_rows = [
        {"channel_origin": "美团", "order_type": "外卖", "net": 60000.0},
        {"channel_origin": "抖音", "order_type": "外卖", "net": 40000.0},
    ]
    net = 100000.0
    commission = builder._estimate_commission(chan_rows, sub_sector="")
    rate = (net - commission) / net
    # 60000*0.20 + 40000*0.22 = 12000 + 8800 = 20800 commission
    # rate = (100000 - 20800)/100000 = 0.792 — clearly < 1.0
    assert rate < 1.0, f"delivery revenue must incur commission, rate={rate}"
    assert rate < 0.85, f"~21% blended commission expected, rate={rate}"


def test_estimate_commission_unmapped_channel_with_delivery_records_note():
    """A channel with delivery revenue but no matching rate must NOT be
    silently estimated at 0 — coverage note flags partial config."""
    builder = HealthCheckMetricsBuilder()
    # '京东' short form not in alias map nor a yaml key → unmapped delivery.
    chan_rows = [
        {"channel_origin": "美团", "order_type": "外卖", "net": 50000.0},
        {"channel_origin": "某新平台", "order_type": "外卖", "net": 50000.0},
    ]
    commission, note = builder._estimate_commission_with_note(chan_rows, sub_sector="")
    # 美团 maps (50000*0.20=10000); 某新平台 unmapped delivery → flagged
    assert commission >= 10000.0
    assert note, "unmapped delivery channel must produce a coverage note"
    assert "费率未配置" in note


def test_review_score_decline_skip_no_data(monkeypatch):
    builder = HealthCheckMetricsBuilder()

    async def _pos(self, fid, start, end, **kw):
        return {}

    async def _review(self, fid, start, end, **kw):
        return None  # no review data

    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_pos_metrics", _pos)
    monkeypatch.setattr(HealthCheckMetricsBuilder, "_fetch_review_decline", _review)

    bundle = _run(builder.build(
        factory_id="F", period="2026-04",
        finance_metrics={"foodCostRatio": 40.0, "revenueChangePct": 0.0},
    ))
    assert "review_score_decline" not in bundle.metrics
    assert "暂无点评" in bundle.coverage["review_score_decline"] or \
        bundle.coverage["review_score_decline"].startswith("skipped")
