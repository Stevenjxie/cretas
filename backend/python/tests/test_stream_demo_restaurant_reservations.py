import asyncio
from argparse import Namespace
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

from smartbi.scripts.stream_demo_restaurant_reservations import (
    APPROVED_FACTORIES,
    FACTORY_ID,
    SOURCE,
    event_for_tick,
    emit,
    parse_datetime,
    run,
    sequence_for_tick,
    target_date_for_sequence,
    tick_for,
)


TZ = ZoneInfo("Asia/Singapore")
START = datetime(2026, 8, 5, 9, 0, tzinfo=TZ)


def test_stream_identity_is_fixed_to_two_simulated_restaurant_tenants():
    event = event_for_tick([11, 12], START, START, 10)
    assert FACTORY_ID == "MOCK_REST"
    assert APPROVED_FACTORIES == {"MOCK_REST", "RES_3101_009"}
    assert SOURCE == "cretas_live_showcase_20260805"
    assert event.source == SOURCE
    assert event.is_simulated is True
    assert event.store_id == 11
    assert event.source_updated_at == "2026-08-05T09:00:00+08:00"


def test_each_ten_second_tick_has_a_stable_idempotency_reference():
    tick = START + timedelta(seconds=70)
    first = event_for_tick([11, 12], START, tick, 10)
    replay = event_for_tick([11, 12], START, tick, 10)
    following = event_for_tick([11, 12], START, tick + timedelta(seconds=10), 10)
    assert first.external_ref == replay.external_ref
    assert first.external_ref != following.external_ref
    assert sequence_for_tick(tick, START, 10) == 7


def test_ticks_round_down_and_never_claim_future_receipt_time():
    now = START + timedelta(seconds=29, microseconds=900_000)
    assert tick_for(now, 10) == START + timedelta(seconds=20)


def test_events_rotate_across_all_factbook_horizons():
    horizons = [target_date_for_sequence(START, sequence)[0] for sequence in range(9)]
    assert horizons == ["tomorrow", "week", "month"] * 3
    for sequence in range(9):
        _, target = target_date_for_sequence(START, sequence)
        assert target > START.date()


def test_event_preserves_store_date_daypart_tables_guests_status_and_update_time():
    event = event_for_tick([21, 22, 23], START, START + timedelta(seconds=30), 10)
    assert event.store_id in {21, 22, 23}
    assert event.reservation_date
    assert event.daypart in {"午市", "下午茶", "晚市", "夜宵"}
    assert event.table_count >= 1
    assert 2 <= event.guest_count <= 6
    assert event.status in {"PENDING", "CONFIRMED"}
    assert event.source_updated_at.endswith("+08:00")


def test_naive_schedule_timestamp_is_rejected():
    with pytest.raises(ValueError, match="explicit timezone"):
        parse_datetime("2026-08-05T09:00:00")


def stream_args(**overrides):
    values = {
        "factory": "MOCK_REST",
        "source": SOURCE,
        "start": "2026-08-05T09:00:00+08:00",
        "end": "2026-08-05T14:00:00+08:00",
        "interval_seconds": 10,
        "apply": False,
        "confirm": "",
        "max_events": 0,
    }
    values.update(overrides)
    return Namespace(**values)


def test_stream_rejects_every_tenant_outside_the_explicit_restaurant_allowlist():
    with pytest.raises(RuntimeError, match="MOCK_REST, RES_3101_009"):
        asyncio.run(run(stream_args(factory="F006")))


def test_apply_requires_confirmation_for_the_selected_restaurant_tenant():
    with pytest.raises(RuntimeError, match="--confirm RES_3101_009"):
        asyncio.run(run(stream_args(
            factory="RES_3101_009",
            apply=True,
            confirm="MOCK_REST",
        )))


def test_emit_passes_native_date_and_datetime_to_the_service():
    captured = {}

    class Service:
        async def import_reservations(self, factory_id, records):
            captured["factory_id"] = factory_id
            captured["record"] = records[0]
            return {
                "received": 1, "deduplicated": 1, "inserted_rows": 1,
                "updated_rows": 0, "stale_ignored_rows": 0,
                "replay_ignored_rows": 0, "business_write_rows": 1,
            }

    event = event_for_tick([11], START, START, 10)
    result = asyncio.run(emit(Service(), FACTORY_ID, event))
    assert captured["factory_id"] == FACTORY_ID
    assert isinstance(captured["record"]["reservation_date"], date)
    assert isinstance(captured["record"]["source_updated_at"], datetime)
    assert result["business_write_rows"] == 1
