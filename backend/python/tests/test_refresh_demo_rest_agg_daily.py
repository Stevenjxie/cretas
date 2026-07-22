"""Guard rails for smartbi.scripts.refresh_demo_rest_agg_daily."""
from __future__ import annotations

import argparse
import asyncio
from datetime import date, timedelta

import pytest

from smartbi.scripts import refresh_demo_rest_agg_daily as mod
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
    assert mod.SEED_VERSION >= 9_000_000
