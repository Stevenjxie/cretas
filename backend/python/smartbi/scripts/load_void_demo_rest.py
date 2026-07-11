"""One-off ops script — load the time-shifted DEMO_REST void-event JSONL
(produced by `extract_shift_void_2026.py`) into Postgres.

New restaurant analytics dimension: 撤单率 / 撤单稽核 (order void rate + staff
audit) — greenfield.

⛔ DELIBERATE CLOBBER-AVOIDANCE DESIGN — READ BEFORE EDITING ⛔
----------------------------------------------------------------
This script writes ONLY to `fact_pos_void` (+ the `dim_store` / `dim_staff`
upserts that `DimResolver.resolve_store` / `resolve_staff` perform as a
side effect — same as every other Silver ingestion path). It must NEVER
call:
  - smartbi.services.materialized_analytics.daily_void.materialize_daily_void
  - smartbi.gold.ingest_and_materialize
  - any other GoldMaterializer method
  - scripts.backfill_silver.backfill_upload

Why: same reasoning as load_billgrain_demo_rest.py's docstring — DEMO_REST's
existing Gold aggregates (agg_daily, agg_daily_cost, etc.) have already been
verified/pinned. This script's whole job is loading Silver (fact_pos_void)
rows — NOT touching Gold. Materializing agg_daily_void is a SEPARATE,
deliberate, reviewed step the organizer runs after this script (and after
confirming the Silver load looks right), via — with the SAME factory_id this
script loaded under (see --factory-id below; 🔒 must match or the aggregate
lands under the wrong tenant):

    from smartbi.services.materialized_analytics.daily_void import materialize_daily_void
    await materialize_daily_void(pool, "<same factory_id>", date(2026, 1, 1), date(2026, 7, 31))

Why not SilverNormalizer.ingest_rows (like load_billgrain_demo_rest.py)?
-------------------------------------------------------------------------
SilverNormalizer's CanonicalRow dataclass is shaped for fact_pos_transaction
(bill grain: gross_amount / net_amount / customer_count / ...) — it has no
concept of a void event (order_open_at / void_at / void_diff_minutes /
void_reason). Rather than force an unrelated shape through it, this script
uses DimResolver directly (same store/staff upsert-and-cache primitive
SilverNormalizer itself uses internally) + a raw asyncpg batch INSERT
against fact_pos_void.

Idempotency: fact_pos_void has UNIQUE (factory_id, source_type, store_id,
bill_no, table_no, order_open_at, void_at) — see
V20261005_01__fact_pos_void.sql's module comment for why bill_no alone
isn't a safe key (blank in ~18% of real rows) and why bill_no is
normalized to '' (never NULL — NULL would bypass the UNIQUE constraint
entirely). The INSERT below uses `ON CONFLICT ... DO NOTHING`, so
re-running this script is always safe — already-loaded rows are silently
skipped (counted in `duplicates_skipped`).

Usage (run by the organizer against prod, NOT by this session):
    cd backend/python
    python -m smartbi.scripts.load_void_demo_rest --dry-run   # preview only
    # 🔒 load under the tenant the CARD reads (aliased target):
    python -m smartbi.scripts.load_void_demo_rest --factory-id RES_3101_009
    # optionally also under DEMO_REST (for the health-check, which never aliases):
    python -m smartbi.scripts.load_void_demo_rest --factory-id DEMO_REST
    python -m smartbi.scripts.load_void_demo_rest --dsn postgresql://...  # explicit DSN

DB connection: by default uses `smartbi.config.get_pg_pool()` — the SAME
shared asyncpg pool the running smartbi service uses (reads
POSTGRES_HOST/POSTGRES_PORT/POSTGRES_DB/POSTGRES_USER/POSTGRES_PASSWORD from
the process environment). Run from the same shell/venv that has those env
vars set (cretas-python.service / cretas-python-test.service already export
them — see .claude/rules/db-credentials.md), OR pass --dsn explicitly.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Dict, List, Optional

# NOTE: only add backend/python itself (not backend/python/smartbi) to
# sys.path — see load_billgrain_demo_rest.py's identical comment for why
# (avoids scripts/ package name collision between backend/python/scripts/
# and backend/python/smartbi/scripts/).
_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
if str(_PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(_PYTHON_ROOT))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("load_void_demo_rest")

from smartbi.canonical.dim_resolver import DimResolver  # noqa: E402

_DEFAULT_JSONL_PATH = Path(__file__).resolve().parent / "_demo_rest_void_2026.jsonl"
# factory_id is a RUNTIME arg (--factory-id), NOT hardcoded. 🔒 CRITICAL: the
# card reads /void-rate via gold_reads._resolve_tenant, which ALIASES DEMO_REST
# -> RES_3101_009 (DEMO_GOLD_TENANT_ALIASES). So the voids must land under the
# SAME tenant the card actually queries. The organizer runs this for
# RES_3101_009 (what the card reads) and optionally DEMO_REST (for the
# health-check, which does NOT alias). A hardcoded DEMO_REST here would make
# the card show 0.00% forever (voids under DEMO_REST, card reads RES_3101_009).
_DEFAULT_FACTORY_ID = "DEMO_REST"
_SOURCE_TYPE = "excel"
_BATCH_SIZE = 1000

_INSERT_SQL = """
INSERT INTO fact_pos_void (
    factory_id, source_type, store_id, bill_no, table_no,
    order_open_at, void_at, void_diff_minutes, void_reason, staff_id, date
)
SELECT * FROM UNNEST(
    $1::text[], $2::text[], $3::bigint[], $4::text[], $5::text[],
    $6::timestamp[], $7::timestamp[], $8::numeric[], $9::text[], $10::bigint[], $11::date[]
)
ON CONFLICT (factory_id, source_type, store_id, bill_no, table_no, order_open_at, void_at)
DO NOTHING
RETURNING id
"""


@dataclass
class LoadStats:
    rows_read: int = 0
    rows_parsed: int = 0
    rows_parse_failed: int = 0
    voids_written: int = 0
    duplicates_skipped: int = 0
    stores_resolved: int = 0
    staff_resolved: int = 0


@dataclass
class VoidRecord:
    store_name: str
    bill_no: str
    table_no: str  # never None — '' for blank 桌位 (table_no is NOT NULL + part of uq_fact_pos_void)
    order_open_at: datetime
    void_at: datetime
    void_diff_minutes: Optional[Decimal]
    void_reason: Optional[str]
    staff_name: Optional[str]


def _to_decimal(v: Optional[float]) -> Optional[Decimal]:
    if v is None:
        return None
    try:
        return Decimal(str(v)).quantize(Decimal("0.01"))
    except (InvalidOperation, ValueError):
        return None


def _to_datetime(v: Optional[str]) -> Optional[datetime]:
    if not v:
        return None
    try:
        return datetime.fromisoformat(v)
    except ValueError:
        return None


def _build_void_record(record: Dict[str, Any]) -> Optional[VoidRecord]:
    store_name = (record.get("store_name") or "").strip()
    order_open_at = _to_datetime(record.get("order_open_at"))
    void_at = _to_datetime(record.get("void_at"))

    if not store_name or order_open_at is None or void_at is None:
        return None

    return VoidRecord(
        store_name=store_name,
        bill_no=(record.get("bill_no") or ""),  # '' preserved, never None (see migration note)
        table_no=(record.get("table_no") or ""),  # '' never None — NOT NULL + UNIQUE-key member (blank NULL would bypass dedup)
        order_open_at=order_open_at,
        void_at=void_at,
        void_diff_minutes=_to_decimal(record.get("void_diff_minutes")),
        void_reason=(record.get("void_reason") or None),
        staff_name=(record.get("staff_name") or None),
    )


def _iter_void_records(jsonl_path: Path, stats: LoadStats):
    with jsonl_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            stats.rows_read += 1
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                stats.rows_parse_failed += 1
                continue
            row = _build_void_record(record)
            if row is None:
                stats.rows_parse_failed += 1
                continue
            stats.rows_parsed += 1
            yield row


async def _get_pool(dsn: Optional[str]):
    if dsn:
        import asyncpg
        from smartbi.tenant_ctx import set_pg_connection_tenant
        return await asyncpg.create_pool(
            dsn, min_size=1, max_size=3, setup=set_pg_connection_tenant,
        )
    from smartbi.config import get_pg_pool
    return await get_pg_pool()


async def _write_batch(
    pool,
    resolver: DimResolver,
    batch: List[VoidRecord],
    stats: LoadStats,
    factory_id: str,
) -> None:
    factory_ids: List[str] = []
    source_types: List[str] = []
    store_ids: List[int] = []
    bill_nos: List[str] = []
    table_nos: List[str] = []
    order_open_ats: List[datetime] = []
    void_ats: List[datetime] = []
    void_diff_minutes: List[Optional[Decimal]] = []
    void_reasons: List[Optional[str]] = []
    staff_ids: List[Optional[int]] = []
    dates: List[date] = []

    for rec in batch:
        store_id = await resolver.resolve_store(rec.store_name)
        stats.stores_resolved += 1
        staff_id: Optional[int] = None
        if rec.staff_name:
            staff_id = await resolver.resolve_staff(rec.staff_name, store_id=store_id)
            stats.staff_resolved += 1

        factory_ids.append(factory_id)
        source_types.append(_SOURCE_TYPE)
        store_ids.append(store_id)
        bill_nos.append(rec.bill_no)
        table_nos.append(rec.table_no)
        order_open_ats.append(rec.order_open_at)
        void_ats.append(rec.void_at)
        void_diff_minutes.append(rec.void_diff_minutes)
        void_reasons.append(rec.void_reason)
        staff_ids.append(staff_id)
        dates.append(rec.void_at.date())

    async with pool.acquire() as conn:
        inserted_rows = await conn.fetch(
            _INSERT_SQL,
            factory_ids, source_types, store_ids, bill_nos, table_nos,
            order_open_ats, void_ats, void_diff_minutes, void_reasons,
            staff_ids, dates,
        )
    written = len(inserted_rows)
    stats.voids_written += written
    stats.duplicates_skipped += len(batch) - written


async def _run(jsonl_path: Path, dsn: Optional[str], dry_run: bool, factory_id: str) -> LoadStats:
    stats = LoadStats()
    rows: List[VoidRecord] = list(_iter_void_records(jsonl_path, stats))

    print(f"jsonl:            {jsonl_path}")
    print(f"factory_id:       {factory_id}")
    print(f"rows_read:        {stats.rows_read}")
    print(f"rows_parsed:      {stats.rows_parsed}")
    print(f"rows_parse_failed:{stats.rows_parse_failed}")

    if dry_run:
        print("--dry-run: not connecting to DB, not writing.")
        distinct_stores = {r.store_name for r in rows}
        print(f"distinct stores in file: {len(distinct_stores)}")
        return stats

    if not rows:
        print("nothing to write.")
        return stats

    pool = await _get_pool(dsn)
    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(factory_id)

    resolver = DimResolver(pool, factory_id=factory_id)

    for start in range(0, len(rows), _BATCH_SIZE):
        batch = rows[start:start + _BATCH_SIZE]
        await _write_batch(pool, resolver, batch, stats, factory_id)
        print(
            f"batch {start}-{start + len(batch)}: "
            f"written(cumulative)={stats.voids_written} "
            f"dup_skipped(cumulative)={stats.duplicates_skipped}"
        )

    print()
    print(f"TOTAL voids_written:      {stats.voids_written}")
    print(f"TOTAL duplicates_skipped: {stats.duplicates_skipped}")
    print(f"TOTAL stores resolved (incl. cache hits): {stats.stores_resolved}")
    print(f"TOTAL staff resolved (incl. cache hits):   {stats.staff_resolved}")
    print()
    print(
        "NOTE: this script does NOT call the Gold materializer. If "
        "agg_daily_void needs to reflect these rows, run "
        f"materialize_daily_void(pool, '{factory_id}', date_min, date_max) "
        "as a SEPARATE, deliberate step — see module docstring. "
        "🔒 Use this SAME factory_id or the aggregate lands under the wrong tenant."
    )
    return stats


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jsonl", default=str(_DEFAULT_JSONL_PATH), help="input JSONL path")
    ap.add_argument(
        "--factory-id",
        default=_DEFAULT_FACTORY_ID,
        help=(
            "tenant to load voids under. 🔒 The card reads /void-rate via an "
            "aliased tenant (DEMO_REST -> RES_3101_009), so to make the card "
            "render, load under RES_3101_009. Default DEMO_REST is for the "
            "health-check path (which never aliases)."
        ),
    )
    ap.add_argument("--dsn", default=None, help="explicit Postgres DSN (overrides env-based pool)")
    ap.add_argument("--dry-run", action="store_true", help="parse + report counts only, no DB writes")
    args = ap.parse_args()

    jsonl_path = Path(args.jsonl)
    if not jsonl_path.exists():
        raise SystemExit(f"jsonl not found: {jsonl_path} (run extract_shift_void_2026.py first)")

    asyncio.run(_run(jsonl_path, args.dsn, args.dry_run, args.factory_id))


if __name__ == "__main__":
    main()
