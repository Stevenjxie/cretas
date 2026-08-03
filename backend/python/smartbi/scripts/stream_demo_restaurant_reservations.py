"""Emit a bounded, auditable restaurant demo reservation stream.

This is a one-day production showcase utility, not a platform connector.  It
writes only to ``MOCK_REST`` with an explicit simulated source and stops at the
configured end time.  Every external reference is derived from its ten-second
tick, so a service restart replays idempotently instead of duplicating events.

Dry-run is the default.  Writes require both ``--apply`` and the exact tenant
confirmation.  The downstream staffing service remains the only numerical
author: these events enter the same reservation -> forecast FactBook -> LLM
explanation path as an authorized platform feed.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import math
from dataclasses import asdict, dataclass
from datetime import date, datetime, timedelta
from typing import Any, Sequence
from zoneinfo import ZoneInfo

from smartbi.config import get_pg_pool
from smartbi.services.restaurant.staffing_forecast import (
    DAYPARTS,
    RestaurantStaffingService,
    horizon_window,
)


TIMEZONE = ZoneInfo("Asia/Singapore")
FACTORY_ID = "MOCK_REST"
SOURCE = "cretas_live_showcase_20260805"
DEFAULT_INTERVAL_SECONDS = 10


@dataclass(frozen=True)
class StreamEvent:
    source: str
    external_ref: str
    store_id: int
    reservation_date: str
    daypart: str
    table_count: int
    guest_count: int
    status: str
    source_updated_at: str
    is_simulated: bool = True


def parse_datetime(value: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        raise ValueError("stream timestamps must include an explicit timezone")
    return parsed.astimezone(TIMEZONE)


def tick_for(now: datetime, interval_seconds: int) -> datetime:
    epoch = int(now.timestamp())
    return datetime.fromtimestamp(
        epoch - (epoch % interval_seconds), tz=TIMEZONE,
    )


def sequence_for_tick(tick: datetime, start: datetime, interval_seconds: int) -> int:
    seconds = int((tick - start).total_seconds())
    if seconds < 0:
        raise ValueError("tick precedes stream start")
    return seconds // interval_seconds


def target_date_for_sequence(start: datetime, sequence: int):
    as_of = start.date()
    horizon = ("tomorrow", "week", "month")[sequence % 3]
    window_start, window_end = horizon_window(horizon, as_of)
    days = (window_end - window_start).days + 1
    return horizon, window_start + timedelta(days=(sequence // 3) % days)


def event_for_tick(
    store_ids: Sequence[int],
    start: datetime,
    tick: datetime,
    interval_seconds: int,
) -> StreamEvent:
    if not store_ids:
        raise ValueError("MOCK_REST has no stores")
    sequence = sequence_for_tick(tick, start, interval_seconds)
    horizon, target_date = target_date_for_sequence(start, sequence)
    store_id = int(store_ids[sequence % len(store_ids)])
    daypart = DAYPARTS[(sequence // max(len(store_ids), 1)) % len(DAYPARTS)]
    # Deterministic small-party pattern.  These are explicitly simulated input
    # facts; all displayed totals are recalculated by the forecast FactBook.
    guest_count = 2 + (sequence % 5)
    status = "PENDING" if sequence % 4 == 3 else "CONFIRMED"
    return StreamEvent(
        source=SOURCE,
        external_ref=(
            f"{SOURCE}:{tick.strftime('%Y%m%dT%H%M%S')}:{horizon}:"
            f"{store_id}:{daypart}"
        ),
        store_id=store_id,
        reservation_date=target_date.isoformat(),
        daypart=daypart,
        table_count=max(1, math.ceil(guest_count / 4)),
        guest_count=guest_count,
        status=status,
        source_updated_at=tick.isoformat(),
    )


async def load_store_ids(pool: Any) -> list[int]:
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
            rows = await conn.fetch(
                "SELECT store_id FROM dim_store WHERE factory_id=$1 ORDER BY store_id",
                FACTORY_ID,
            )
    store_ids = [int(row["store_id"]) for row in rows]
    if not store_ids:
        raise RuntimeError("MOCK_REST has no tenant-owned stores")
    return store_ids


async def emit(service: RestaurantStaffingService, event: StreamEvent) -> dict[str, Any]:
    payload = asdict(event)
    payload["reservation_date"] = date.fromisoformat(event.reservation_date)
    payload["source_updated_at"] = datetime.fromisoformat(event.source_updated_at)
    result = await service.import_reservations(FACTORY_ID, [payload])
    return {
        "factory_id": FACTORY_ID,
        "source": SOURCE,
        "external_ref": event.external_ref,
        "source_updated_at": event.source_updated_at,
        "reservation_date": event.reservation_date,
        "daypart": event.daypart,
        "store_id": event.store_id,
        "guest_count": event.guest_count,
        "is_simulated": True,
        **result,
    }


async def run(args: argparse.Namespace) -> None:
    if args.factory != FACTORY_ID:
        raise RuntimeError(f"stream factory must be {FACTORY_ID}")
    if args.source != SOURCE:
        raise RuntimeError(f"stream source must be {SOURCE}")
    if args.interval_seconds != DEFAULT_INTERVAL_SECONDS:
        raise RuntimeError(f"stream interval must be {DEFAULT_INTERVAL_SECONDS} seconds")
    start = parse_datetime(args.start)
    end = parse_datetime(args.end)
    if end <= start:
        raise ValueError("stream end must be after start")
    expected_events = math.ceil((end - start).total_seconds() / args.interval_seconds)
    if expected_events > 1800:
        raise RuntimeError("stream exceeds the approved 1800-event ceiling")
    if args.apply and args.confirm != FACTORY_ID:
        raise RuntimeError(f"state change requires --confirm {FACTORY_ID}")

    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("SmartBI Postgres pool is unavailable")
    store_ids = await load_store_ids(pool)
    first_event = event_for_tick(store_ids, start, start, args.interval_seconds)
    plan = {
        "mode": "apply" if args.apply else "dry-run",
        "factory_id": FACTORY_ID,
        "source": SOURCE,
        "is_simulated": True,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "interval_seconds": args.interval_seconds,
        "event_ceiling": expected_events,
        "store_ids": store_ids,
        "first_event": asdict(first_event),
    }
    print(json.dumps({"event": "stream_plan", **plan}, ensure_ascii=False), flush=True)
    if not args.apply:
        return

    service = RestaurantStaffingService(pool)
    emitted = 0
    business_writes = 0
    idempotent_replays = 0
    last_tick: datetime | None = None
    while True:
        now = datetime.now(TIMEZONE)
        if now >= end:
            break
        if now < start:
            await asyncio.sleep(min((start - now).total_seconds(), 1.0))
            continue
        tick = tick_for(now, args.interval_seconds)
        if tick < start:
            await asyncio.sleep(0.2)
            continue
        if last_tick is None or tick > last_tick:
            event = event_for_tick(store_ids, start, tick, args.interval_seconds)
            result = await emit(service, event)
            emitted += 1
            business_writes += int(result["business_write_rows"])
            idempotent_replays += int(result["replay_ignored_rows"])
            last_tick = tick
            print(json.dumps({"event": "reservation_emitted", **result}, ensure_ascii=False), flush=True)
            if args.max_events and emitted >= args.max_events:
                break
        next_tick = tick + timedelta(seconds=args.interval_seconds)
        await asyncio.sleep(max(0.1, min((next_tick - datetime.now(TIMEZONE)).total_seconds(), 1.0)))

    print(json.dumps({
        "event": "stream_complete",
        "factory_id": FACTORY_ID,
        "source": SOURCE,
        "emitted_attempts": emitted,
        "business_write_rows": business_writes,
        "idempotent_replays": idempotent_replays,
        "stopped_at": datetime.now(TIMEZONE).isoformat(),
    }, ensure_ascii=False), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--factory", default=FACTORY_ID)
    parser.add_argument("--source", default=SOURCE)
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--interval-seconds", type=int, default=DEFAULT_INTERVAL_SECONDS)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm", default="")
    parser.add_argument("--max-events", type=int, default=0, help="test-only early stop")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
