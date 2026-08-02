"""Guard rails for smartbi.scripts.refresh_demo_rest_agg_daily."""
from __future__ import annotations

import argparse
import asyncio
from pathlib import Path
import subprocess
import sys
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


def test_dish_facts_module_imports_from_production_shaped_cwd():
    python_root = Path(__file__).resolve().parents[1]
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "smartbi.scripts.refresh_demo_rest_dish_facts",
            "--help",
        ],
        cwd=python_root,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )

    assert result.returncode == 0, result.stderr
    assert "--factory" in result.stdout


class _SyntheticChannelConn:
    def __init__(self):
        self.executed = []

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        if "INSERT INTO fact_pos_transaction" in sql:
            return "INSERT 0 20"
        if "WITH marked AS" in sql:
            return "UPDATE 12"
        if "INSERT INTO agg_daily_order_type_meal" in sql:
            return "INSERT 0 18"
        raise AssertionError(f"unexpected SQL: {sql}")

    async def fetchval(self, sql, *args):
        assert "SELECT MIN(date)" in sql
        assert args[0] == "DEMO_REST"
        assert args[1] == dish_mod.TX_MARKER
        return date(2026, 8, 1)


def test_synthetic_transactions_include_channel_and_meal_dimensions():
    conn = _SyntheticChannelConn()
    inserted = asyncio.run(
        dish_mod._synthesize_transactions(conn, "DEMO_REST", date(2026, 8, 1))
    )

    sql = conn.executed[0][0]
    assert inserted == 20
    assert "order_type, channel_origin, meal_period" in sql
    assert "'堂食'" in sql
    assert "'外卖'" in sql
    assert "'自提'" in sql


def test_existing_marker_channels_are_null_only_and_gold_is_refreshed():
    conn = _SyntheticChannelConn()
    result = asyncio.run(
        dish_mod._refresh_synthetic_channels(
            conn, "DEMO_REST", date(2026, 8, 1)
        )
    )

    assert result == {"channel_rows_backfilled": 12, "channel_gold_rows": 18}
    update_sql = conn.executed[0][0]
    assert "source_type = $2" in update_sql
    assert "factory_id = $1" in update_sql
    assert "NULLIF(TRIM(order_type), '') IS NULL" in update_sql
    assert "INSERT INTO agg_daily_order_type_meal" in conn.executed[1][0]
