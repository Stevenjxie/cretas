"""Guard rails for smartbi.scripts.refresh_demo_rest_agg_daily."""
from __future__ import annotations

import argparse
import asyncio
from datetime import date, timedelta

import pytest

from smartbi.scripts import refresh_demo_rest_agg_daily as mod
from smartbi.scripts import refresh_demo_rest_dish_facts as dish_mod
from smartbi.scripts import refresh_qhj_demo_recent_agg as qhj


def test_yesterday_is_the_latest_allowed_end():
    mod.validate_target_end(date.today() - timedelta(days=1))


@pytest.mark.parametrize("offset_days", [0, 1, 30])
def test_future_or_incomplete_day_is_rejected(offset_days):
    with pytest.raises(ValueError):
        mod.validate_target_end(date.today() + timedelta(days=offset_days))


def test_state_change_requires_exact_tenant_confirmation():
    args = argparse.Namespace(apply=True, rollback=False, confirm="", end="")
    with pytest.raises(RuntimeError, match="--confirm DEMO_REST"):
        asyncio.run(mod.run(args))


def test_scope_is_demo_tenant_only():
    assert mod.FACTORY_ID == "DEMO_REST"


def test_seed_marker_is_reserved_and_distinct_from_qhj_seed():
    assert mod.SEED_VERSION != qhj.SEED_VERSION
    assert mod.SEED_VERSION != qhj.DEMO_REST_SEED_VERSION
    assert mod.SEED_VERSION >= 9_000_000


class _AggApplyConn:
    def __init__(self, target_end: date, pos_max_date: date):
        self.target_end = target_end
        self.pos_max_date = pos_max_date

    async def execute(self, _sql, *_args):
        return "INSERT 0 0"

    async def fetchrow(self, _sql, *_args):
        return {
            "rows": 10,
            "max_date": self.target_end,
            "pos_max_date": self.pos_max_date,
            "seeded_rows": 0,
        }


def test_aggregate_verification_rejects_stale_pos_grain():
    target_end = date(2026, 8, 1)
    conn = _AggApplyConn(target_end, date(2026, 7, 31))
    with pytest.raises(RuntimeError, match="POS coverage still ends"):
        asyncio.run(mod.apply_refresh(conn, target_end))


def test_aggregate_verification_reports_matching_pos_grain():
    target_end = date(2026, 8, 1)
    result = asyncio.run(
        mod.apply_refresh(_AggApplyConn(target_end, target_end), target_end)
    )
    assert result["agg_max_date_after"] == "2026-08-01"
    assert result["pos_max_date_after"] == "2026-08-01"


# ── refresh_demo_rest_dish_facts (R17) guard rails ──
def test_dish_facts_yesterday_is_latest_allowed_end():
    dish_mod.validate_target_end(date.today() - timedelta(days=1))
    with pytest.raises(ValueError):
        dish_mod.validate_target_end(date.today())


def test_dish_facts_state_change_requires_confirmation():
    args = argparse.Namespace(
        factory="DEMO_REST", apply=True, rollback=False, confirm="", end="")
    with pytest.raises(RuntimeError, match="--confirm DEMO_REST"):
        asyncio.run(dish_mod.run(args))


def test_dish_facts_factory_is_demo_allowlisted():
    args = argparse.Namespace(
        factory="F006", apply=True, rollback=False, confirm="F006", end="")
    with pytest.raises(RuntimeError, match="--factory must be one of"):
        asyncio.run(dish_mod.run(args))
    assert dish_mod.ALLOWED_FACTORIES == ("DEMO_REST", "RES_3101_009")


def test_dish_facts_scope_and_marker():
    assert dish_mod.FACTORY_ID == "DEMO_REST"
    assert dish_mod.MARKER.startswith("DEMO_ROLL_")
    assert dish_mod.TX_MARKER == "DEMO_ROLL_TX"
    assert dish_mod.MARKER != str(mod.SEED_VERSION)
