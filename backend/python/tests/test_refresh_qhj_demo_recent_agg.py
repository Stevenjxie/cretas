from datetime import date

import pytest

from smartbi.scripts.refresh_qhj_demo_recent_agg import (
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
    assert (SOURCE_END - SOURCE_START).days + 1 == 81
    assert TARGET_START == date(2026, 5, 1)
