import argparse
import asyncio
from datetime import date

import pytest

from smartbi.scripts import refresh_qhj_demo_recent_agg as mod
from smartbi.scripts.refresh_qhj_demo_recent_agg import (
    ALLOWED_FACTORIES,
    DEMO_REST_FACTORY_ID,
    DEMO_REST_SEED_VERSION,
    DEMO_REST_TARGET_START,
    SEED_VERSION,
    SOURCE_END,
    SOURCE_START,
    TARGET_START,
    source_date_for_target,
    validate_target_end,
)


def test_approved_window_maps_to_continuous_template_without_drift():
    assert source_date_for_target(date(2026, 5, 1)) == SOURCE_START
    assert source_date_for_target(date(2026, 7, 20)) == SOURCE_END


def test_later_refresh_cycles_same_fixed_template_deterministically():
    assert source_date_for_target(date(2026, 7, 21)) == SOURCE_START
    assert source_date_for_target(date(2026, 7, 22)) == date(2026, 2, 10)


def test_target_before_approved_window_is_rejected():
    with pytest.raises(ValueError, match="precedes"):
        source_date_for_target(date(2026, 4, 30))


def test_future_or_incomplete_day_is_rejected():
    with pytest.raises(ValueError, match="latest complete day"):
        validate_target_end(date.today())


def test_seed_marker_is_reserved_and_distinct_from_normal_materializer_versions():
    assert SEED_VERSION > 1_000_000
    assert DEMO_REST_SEED_VERSION > 1_000_000
    assert DEMO_REST_SEED_VERSION != SEED_VERSION
    assert (SOURCE_END - SOURCE_START).days + 1 == 81
    assert TARGET_START == date(2026, 5, 1)


def test_demo_rest_uses_own_fixed_template_from_august_cutover():
    assert DEMO_REST_TARGET_START == date(2026, 8, 1)
    assert source_date_for_target(
        DEMO_REST_TARGET_START, DEMO_REST_FACTORY_ID,
    ) == SOURCE_START
    assert source_date_for_target(
        DEMO_REST_TARGET_START.replace(day=2), DEMO_REST_FACTORY_ID,
    ) == date(2026, 2, 10)


def test_demo_rest_target_before_cutover_is_rejected():
    with pytest.raises(ValueError, match="precedes"):
        source_date_for_target(date(2026, 7, 31), DEMO_REST_FACTORY_ID)


def test_only_two_demo_tenants_are_allowlisted():
    assert ALLOWED_FACTORIES == ("RES_3101_009", "DEMO_REST")


class _RollbackConn:
    def __init__(self):
        self.calls = []

    async def execute(self, sql, *args):
        self.calls.append((sql, *args))
        return "DELETE 22" if sql.startswith("DELETE") else "SELECT 1"


def test_demo_rest_rollback_uses_its_own_reserved_marker():
    conn = _RollbackConn()
    result = asyncio.run(mod.rollback_refresh(conn, DEMO_REST_FACTORY_ID))
    assert result == {"deleted_rows": 22}
    assert conn.calls[-1][1:] == (
        DEMO_REST_FACTORY_ID,
        DEMO_REST_SEED_VERSION,
    )


def test_demo_rest_state_change_requires_exact_confirmation():
    args = argparse.Namespace(
        factory=DEMO_REST_FACTORY_ID,
        apply=True,
        rollback=False,
        confirm="RES_3101_009",
        end="2026-08-01",
    )
    with pytest.raises(RuntimeError, match="--confirm DEMO_REST"):
        asyncio.run(mod.run(args))
