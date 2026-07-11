"""One-off ops script — load the time-shifted DEMO_REST bill-grain JSONL
(produced by `extract_shift_pos_2026_q.py`) into Postgres.

⛔ DELIBERATE CLOBBER-AVOIDANCE DESIGN — READ BEFORE EDITING ⛔
----------------------------------------------------------------
This script writes ONLY via `SilverNormalizer.ingest_rows()`, which touches
fact/dim tables (fact_pos_transaction + its store/staff dim upserts) and
NOTHING else. It must NEVER call:
  - scripts.backfill_silver.backfill_upload
  - smartbi.gold.ingest_and_materialize
  - smartbi.gold.materializer.* (or any GoldMaterializer)
  - smartbi.canonical.normalizer.SilverNormalizer.write_row combined with a
    follow-up manual agg_daily / agg_daily_* recompute

Why: DEMO_REST's Gold aggregates (agg_daily, agg_daily_cost, etc.) have
already been verified/pinned for the existing 2026-01..04 shifted data (see
reseed_demo_rest_cost.py's docstring re: agg_daily_cost drift). Re-running
the Gold materializer over DEMO_REST as a side effect of this backfill would
recompute aggregates from whatever is in Silver at that moment and could
silently reshape/clobber the already-verified Gold numbers. This script's
whole job is closing a bill-grain (Silver, fact_pos_transaction) gap — NOT
touching Gold. If Gold needs to reflect these new rows, that is a SEPARATE,
deliberate, reviewed step (not automatic here).

What this script does NOT write (by design, matching the narrower
intermediate JSONL schema — see extract_shift_pos_2026_q.py):
  - fact_pos_item (no combo_string in the JSONL -> 0 items per bill)
  - fact_pos_payment / fact_pos_discount (no payments/discounts in the JSONL)
This is a pure BILL-GRAIN backfill (fact_pos_transaction rows only).

Idempotency: fact_pos_transaction has UNIQUE (factory_id, source_type,
store_id, source_bill_no); SilverNormalizer.write_row's INSERT uses
ON CONFLICT (...) DO NOTHING, so re-running this script is always safe —
already-loaded bills are silently skipped (counted in `duplicates_skipped`).

Usage (run by the organizer against prod, NOT by this session):
    cd backend/python
    python -m smartbi.scripts.load_billgrain_demo_rest --dry-run   # preview only
    python -m smartbi.scripts.load_billgrain_demo_rest             # actually writes
    python -m smartbi.scripts.load_billgrain_demo_rest --dsn postgresql://...  # explicit DSN

DB connection: by default uses `smartbi.config.get_pg_pool()` — the SAME
shared asyncpg pool the running smartbi service uses, which reads
POSTGRES_HOST/POSTGRES_PORT/POSTGRES_DB/POSTGRES_USER/POSTGRES_PASSWORD from
the process environment (Settings/BaseSettings). On the server, run this
from the same shell/venv that has those env vars set (i.e. the ones
cretas-python.service / cretas-python-test.service already export — see
.claude/rules/db-credentials.md), OR pass --dsn explicitly.
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
# sys.path. Adding the smartbi/ dir too would make its own smartbi/scripts/
# subpackage shadow the real top-level scripts/ package (both dirs are
# literally named "scripts") and break `from scripts.backfill_silver import
# ...` below with a confusing ModuleNotFoundError — hit this exact collision
# while testing this script; only add the one root path.
_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
if str(_PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(_PYTHON_ROOT))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("load_billgrain_demo_rest")

from smartbi.canonical import CanonicalRow, SilverNormalizer  # noqa: E402

# Reuse the SAME meal-period shift-label normalization backfill_silver.py
# uses for real ingestion, for parity (belt-and-suspenders — the extract
# script already normalizes 中班/晚班 and any non-CHECK value to 未分类, but
# calling this too costs nothing and keeps the two scripts' semantics
# identical if backfill_silver's mapping table ever grows).
from scripts.backfill_silver import _normalize_meal_period  # noqa: E402

_DEFAULT_JSONL_PATH = Path(__file__).resolve().parent / "_demo_rest_billgrain_2026_q.jsonl"
_FACTORY_ID = "DEMO_REST"
_SOURCE_TYPE = "excel"
_BATCH_SIZE = 5000

# fact_pos_transaction.chk_meal_period allowed values — belt-and-suspenders
# re-check at load time (extract script already enforces this).
_MEAL_PERIOD_ALLOWED = {
    "早餐", "午餐", "下午茶", "晚餐", "其他", "午市", "晚市", "未分类",
}


@dataclass
class LoadStats:
    rows_read: int = 0
    rows_parsed: int = 0
    rows_parse_failed: int = 0
    transactions_written: int = 0
    duplicates_skipped: int = 0
    write_errors: int = 0


def _to_decimal(v: Optional[float]) -> Optional[Decimal]:
    if v is None:
        return None
    try:
        # Route through str() so we quantize the float's repr, not its raw
        # binary value (avoids 87.30000000000001-style artifacts).
        return Decimal(str(v)).quantize(Decimal("0.01"))
    except (InvalidOperation, ValueError):
        return None


def _to_date(v: Optional[str]) -> Optional[date]:
    if not v:
        return None
    try:
        return datetime.strptime(v, "%Y-%m-%d").date()
    except ValueError:
        return None


def _build_canonical_row(record: Dict[str, Any]) -> Optional[CanonicalRow]:
    """Mirror scripts.backfill_silver._build_canonical_row's required-field
    validation + type coercion, but reading from the pre-shifted narrow
    JSONL schema instead of a raw row_data dict + field_mappings walk.

    Fields NOT present in the JSONL (staff_name, table_no, combo_string,
    payments, discounts) are left at their CanonicalRow defaults
    (None / () respectively) — this is a pure bill-grain backfill, see
    module docstring.
    """
    store_name = (record.get("store_name") or "").strip()
    bill_no = (record.get("source_bill_no") or "").strip()
    parsed_date = _to_date(record.get("date"))

    if not store_name or not bill_no or parsed_date is None:
        return None

    meal_period = _normalize_meal_period(record.get("meal_period_raw"))
    if meal_period and meal_period not in _MEAL_PERIOD_ALLOWED:
        logger.warning(
            "meal_period %r outside chk_meal_period allowed set for bill=%s — "
            "coercing to 未分类 (extract script should have caught this)",
            meal_period, bill_no,
        )
        meal_period = "未分类"

    return CanonicalRow(
        factory_id=_FACTORY_ID,
        source_type=_SOURCE_TYPE,
        store_name=store_name,
        source_bill_no=bill_no,
        date=parsed_date,
        upload_id=None,
        order_type=(record.get("order_type") or None),
        channel_origin=(record.get("channel_origin") or None),
        meal_period=meal_period,
        customer_count=record.get("customer_count"),
        gross_amount=_to_decimal(record.get("gross_amount")),
        discount_amount=_to_decimal(record.get("discount_amount")),
        net_amount=_to_decimal(record.get("net_amount")),
        actual_receive=_to_decimal(record.get("actual_receive")),
        # combo_string / payments / discounts left at CanonicalRow defaults
        # (None / () / ()) — deliberate, see module docstring.
    )


def _iter_canonical_rows(jsonl_path: Path, stats: LoadStats):
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
            row = _build_canonical_row(record)
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


async def _run(jsonl_path: Path, dsn: Optional[str], dry_run: bool) -> LoadStats:
    stats = LoadStats()
    rows: List[CanonicalRow] = list(_iter_canonical_rows(jsonl_path, stats))

    print(f"jsonl:            {jsonl_path}")
    print(f"rows_read:        {stats.rows_read}")
    print(f"rows_parsed:      {stats.rows_parsed}")
    print(f"rows_parse_failed:{stats.rows_parse_failed}")

    if dry_run:
        print("--dry-run: not connecting to DB, not writing.")
        return stats

    if not rows:
        print("nothing to write.")
        return stats

    pool = await _get_pool(dsn)
    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(_FACTORY_ID)

    normalizer = SilverNormalizer(pool, factory_id=_FACTORY_ID)

    for start in range(0, len(rows), _BATCH_SIZE):
        batch = rows[start:start + _BATCH_SIZE]
        batch_stats = await normalizer.ingest_rows(batch)
        stats.transactions_written += batch_stats.transactions_written
        stats.duplicates_skipped += batch_stats.duplicates_skipped
        print(
            f"batch {start}-{start + len(batch)}: "
            f"written={batch_stats.transactions_written} "
            f"dup_skipped={batch_stats.duplicates_skipped}"
        )

    print()
    print(f"TOTAL transactions_written: {stats.transactions_written}")
    print(f"TOTAL duplicates_skipped:   {stats.duplicates_skipped}")
    return stats


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jsonl", default=str(_DEFAULT_JSONL_PATH), help="input JSONL path")
    ap.add_argument("--dsn", default=None, help="explicit Postgres DSN (overrides env-based pool)")
    ap.add_argument("--dry-run", action="store_true", help="parse + report counts only, no DB writes")
    args = ap.parse_args()

    jsonl_path = Path(args.jsonl)
    if not jsonl_path.exists():
        raise SystemExit(f"jsonl not found: {jsonl_path} (run extract_shift_pos_2026_q.py first)")

    asyncio.run(_run(jsonl_path, args.dsn, args.dry_run))


if __name__ == "__main__":
    main()
