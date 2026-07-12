"""One-off ops script — load the time-shifted DEMO_REST zone-sales JSONL
(produced by `extract_shift_zone_2026.py`) into Postgres.

New restaurant analytics dimension: 区域坪效 (in-store dining-zone
revenue/efficiency) — greenfield.

⛔ DELIBERATE CLOBBER-AVOIDANCE DESIGN — READ BEFORE EDITING ⛔
----------------------------------------------------------------
This script writes ONLY to `fact_zone_sales` (+ the `dim_store` upsert that
`DimResolver.resolve_store` performs as a side effect — same as every other
Silver ingestion path). It must NEVER call:
  - smartbi.services.materialized_analytics.daily_zone.materialize_daily_zone
  - smartbi.gold.ingest_and_materialize
  - any other GoldMaterializer method
  - scripts.backfill_silver.backfill_upload

Why: same reasoning as load_void_demo_rest.py's docstring — DEMO_REST's
existing Gold aggregates (agg_daily, agg_daily_cost, agg_daily_void, etc.)
have already been verified/pinned. This script's whole job is loading
Silver (fact_zone_sales) rows — NOT touching Gold. Materializing
agg_daily_zone is a SEPARATE, deliberate, reviewed step the organizer runs
after this script (and after confirming the Silver load looks right), via
— with the SAME factory_id this script loaded under (see --factory-id
below; 🔒 must match or the aggregate lands under the wrong tenant):

    from smartbi.services.materialized_analytics.daily_zone import materialize_daily_zone
    await materialize_daily_zone(pool, "<same factory_id>", date(2026, 1, 1), date(2026, 7, 31))

Why not SilverNormalizer.ingest_rows (like load_billgrain_demo_rest.py)?
-------------------------------------------------------------------------
SilverNormalizer's CanonicalRow dataclass is shaped for fact_pos_transaction
(bill grain: gross_amount / net_amount / customer_count / ...) — it has no
concept of a zone-sales line (zone_name / product_name / unit_price /
quantity / amount_before_discount / amount_after_discount). Rather than
force an unrelated shape through it, this script uses DimResolver directly
(same store upsert-and-cache primitive SilverNormalizer itself uses
internally) + a raw asyncpg batch INSERT against fact_zone_sales.

Idempotency: fact_zone_sales has UNIQUE (factory_id, source_type,
source_row_hash) — see V20261006_01__fact_zone_sales.sql's module comment
for why a synthetic hash (not a natural key) is used: this report carries
no bill_no/order_id, so a line-level sha256 over the raw fields is the only
practical dedup key. The INSERT below uses `ON CONFLICT ... DO NOTHING`, so
re-running this script is always safe — already-loaded rows are silently
skipped (counted in `duplicates_skipped`).

Usage (run by the organizer against prod, NOT by this session):
    cd backend/python
    python -m smartbi.scripts.load_zone_demo_rest --dry-run   # preview only
    # 🔒 load under the tenant the CARD reads (aliased target):
    python -m smartbi.scripts.load_zone_demo_rest --factory-id RES_3101_009
    # optionally also under DEMO_REST (for any health-check that never aliases):
    python -m smartbi.scripts.load_zone_demo_rest --factory-id DEMO_REST
    python -m smartbi.scripts.load_zone_demo_rest --dsn postgresql://...  # explicit DSN

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
import hashlib
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
logger = logging.getLogger("load_zone_demo_rest")

from smartbi.canonical.dim_resolver import DimResolver  # noqa: E402

_DEFAULT_JSONL_PATH = Path(__file__).resolve().parent / "_demo_rest_zone_2026.jsonl"
# factory_id is a RUNTIME arg (--factory-id), NOT hardcoded. 🔒 CRITICAL: the
# card reads /zone-efficiency via gold_reads._resolve_tenant, which ALIASES
# DEMO_REST -> RES_3101_009 (DEMO_GOLD_TENANT_ALIASES). So the zone-sales
# rows must land under the SAME tenant the card actually queries. The
# organizer runs this for RES_3101_009 (what the card reads) and optionally
# DEMO_REST (for any health-check path that does NOT alias). A hardcoded
# DEMO_REST here would make the card show no data forever (rows under
# DEMO_REST, card reads RES_3101_009).
_DEFAULT_FACTORY_ID = "DEMO_REST"
_SOURCE_TYPE = "excel"
_BATCH_SIZE = 1000
_ZONE_SENTINEL = "未分区"

_INSERT_SQL = """
INSERT INTO fact_zone_sales (
    factory_id, source_type, store_id, zone_name, product_name,
    unit_price, quantity, amount_before_discount, amount_after_discount,
    date, source_row_hash
)
SELECT * FROM UNNEST(
    $1::text[], $2::text[], $3::bigint[], $4::text[], $5::text[],
    $6::numeric[], $7::numeric[], $8::numeric[], $9::numeric[],
    $10::date[], $11::text[]
)
ON CONFLICT (factory_id, source_type, source_row_hash)
DO NOTHING
RETURNING id
"""


@dataclass
class LoadStats:
    rows_read: int = 0
    rows_parsed: int = 0
    rows_parse_failed: int = 0
    lines_written: int = 0
    duplicates_skipped: int = 0
    stores_resolved: int = 0


@dataclass
class ZoneSalesRecord:
    store_name: str
    zone_name: str  # '' -> _ZONE_SENTINEL happens in _write_batch (mirrors materializer's COALESCE, but Silver keeps the raw value; only Gold sentinel-buckets)
    product_name: Optional[str]
    unit_price: Optional[Decimal]
    quantity: Optional[Decimal]
    amount_before_discount: Optional[Decimal]
    amount_after_discount: Optional[Decimal]
    sale_date: date


def _to_decimal(v: Optional[str]) -> Optional[Decimal]:
    if v is None:
        return None
    try:
        return Decimal(v).quantize(Decimal("0.01"))
    except (InvalidOperation, ValueError):
        return None


def _to_date(v: Optional[str]) -> Optional[date]:
    if not v:
        return None
    try:
        return datetime.fromisoformat(v).date()
    except ValueError:
        return None


def _build_zone_record(record: Dict[str, Any]) -> Optional[ZoneSalesRecord]:
    store_name = (record.get("store_name") or "").strip()
    sale_date = _to_date(record.get("date"))

    if not store_name or sale_date is None:
        return None

    return ZoneSalesRecord(
        store_name=store_name,
        zone_name=(record.get("zone_name") or "").strip(),  # '' preserved; sentinel applied at write time
        product_name=(record.get("product_name") or None),
        unit_price=_to_decimal(record.get("unit_price")),
        quantity=_to_decimal(record.get("quantity")),
        amount_before_discount=_to_decimal(record.get("amount_before_discount")),
        amount_after_discount=_to_decimal(record.get("amount_after_discount")),
        sale_date=sale_date,
    )


def _row_hash(factory_id: str, rec: ZoneSalesRecord) -> str:
    """sha256 over the raw fields — see V20261006_01's module comment for
    why this report needs a synthetic hash key (no bill_no/order_id).
    Deliberately includes ALL distinguishing fields so two genuinely
    different lines never collide, and reruns of the SAME source data
    always reproduce the SAME hash (idempotent)."""
    payload = "|".join([
        factory_id,
        rec.store_name,
        rec.zone_name,
        rec.product_name or "",
        str(rec.unit_price) if rec.unit_price is not None else "",
        str(rec.quantity) if rec.quantity is not None else "",
        str(rec.amount_after_discount) if rec.amount_after_discount is not None else "",
        rec.sale_date.isoformat(),
    ])
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _iter_zone_records(jsonl_path: Path, stats: LoadStats):
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
            row = _build_zone_record(record)
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
    batch: List[ZoneSalesRecord],
    stats: LoadStats,
    factory_id: str,
) -> None:
    factory_ids: List[str] = []
    source_types: List[str] = []
    store_ids: List[int] = []
    zone_names: List[str] = []
    product_names: List[Optional[str]] = []
    unit_prices: List[Optional[Decimal]] = []
    quantities: List[Optional[Decimal]] = []
    amounts_before: List[Optional[Decimal]] = []
    amounts_after: List[Optional[Decimal]] = []
    dates: List[date] = []
    row_hashes: List[str] = []

    for rec in batch:
        store_id = await resolver.resolve_store(rec.store_name)
        stats.stores_resolved += 1

        factory_ids.append(factory_id)
        source_types.append(_SOURCE_TYPE)
        store_ids.append(store_id)
        zone_names.append(rec.zone_name or _ZONE_SENTINEL)
        product_names.append(rec.product_name)
        unit_prices.append(rec.unit_price)
        quantities.append(rec.quantity)
        amounts_before.append(rec.amount_before_discount)
        amounts_after.append(rec.amount_after_discount)
        dates.append(rec.sale_date)
        row_hashes.append(_row_hash(factory_id, rec))

    async with pool.acquire() as conn:
        inserted_rows = await conn.fetch(
            _INSERT_SQL,
            factory_ids, source_types, store_ids, zone_names, product_names,
            unit_prices, quantities, amounts_before, amounts_after,
            dates, row_hashes,
        )
    written = len(inserted_rows)
    stats.lines_written += written
    stats.duplicates_skipped += len(batch) - written


async def _run(jsonl_path: Path, dsn: Optional[str], dry_run: bool, factory_id: str) -> LoadStats:
    stats = LoadStats()
    rows: List[ZoneSalesRecord] = list(_iter_zone_records(jsonl_path, stats))

    print(f"jsonl:            {jsonl_path}")
    print(f"factory_id:       {factory_id}")
    print(f"rows_read:        {stats.rows_read}")
    print(f"rows_parsed:      {stats.rows_parsed}")
    print(f"rows_parse_failed:{stats.rows_parse_failed}")

    if dry_run:
        print("--dry-run: not connecting to DB, not writing.")
        distinct_stores = {r.store_name for r in rows}
        distinct_zones = {r.zone_name or _ZONE_SENTINEL for r in rows}
        print(f"distinct stores in file: {len(distinct_stores)}")
        print(f"distinct zones in file:  {len(distinct_zones)}")
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
            f"written(cumulative)={stats.lines_written} "
            f"dup_skipped(cumulative)={stats.duplicates_skipped}"
        )

    print()
    print(f"TOTAL lines_written:      {stats.lines_written}")
    print(f"TOTAL duplicates_skipped: {stats.duplicates_skipped}")
    print(f"TOTAL stores resolved (incl. cache hits): {stats.stores_resolved}")
    print()
    print(
        "NOTE: this script does NOT call the Gold materializer. If "
        "agg_daily_zone needs to reflect these rows, run "
        f"materialize_daily_zone(pool, '{factory_id}', date_min, date_max) "
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
            "tenant to load zone-sales rows under. 🔒 The card reads "
            "/zone-efficiency via an aliased tenant (DEMO_REST -> "
            "RES_3101_009), so to make the card render, load under "
            "RES_3101_009. Default DEMO_REST is for any health-check path "
            "(which never aliases)."
        ),
    )
    ap.add_argument("--dsn", default=None, help="explicit Postgres DSN (overrides env-based pool)")
    ap.add_argument("--dry-run", action="store_true", help="parse + report counts only, no DB writes")
    args = ap.parse_args()

    jsonl_path = Path(args.jsonl)
    if not jsonl_path.exists():
        raise SystemExit(f"jsonl not found: {jsonl_path} (run extract_shift_zone_2026.py first)")

    asyncio.run(_run(jsonl_path, args.dsn, args.dry_run, args.factory_id))


if __name__ == "__main__":
    main()
