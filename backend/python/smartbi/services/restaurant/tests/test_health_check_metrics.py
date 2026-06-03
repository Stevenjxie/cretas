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
