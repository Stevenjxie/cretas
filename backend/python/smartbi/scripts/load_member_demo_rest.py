"""One-off ops script — load the time-shifted DEMO_REST member-profile
JSONLs (produced by `extract_shift_member_2026.py`) into Postgres.

New restaurant analytics dimension: 会员储值 + 画像 (member stored-value +
demographic profile) — greenfield.

⛔ DELIBERATE CLOBBER-AVOIDANCE DESIGN — READ BEFORE EDITING ⛔
----------------------------------------------------------------
This script writes ONLY to `dim_member` + `fact_member_recharge` (+ the
`dim_store` upserts that `DimResolver.resolve_store` performs as a side
effect — same as every other Silver ingestion path). It must NEVER call:
  - smartbi.services.materialized_analytics.member_profile.materialize_member_tier_profile
  - smartbi.services.materialized_analytics.member_profile.materialize_member_recharge_daily
  - smartbi.gold.ingest_and_materialize
  - any other GoldMaterializer method
  - scripts.backfill_silver.backfill_upload

Why: same reasoning as load_void_demo_rest.py's docstring — DEMO_REST's
existing Gold aggregates (agg_daily, agg_daily_cost, agg_daily_void, etc.)
have already been verified/pinned. This script's whole job is loading
Silver (dim_member / fact_member_recharge) rows — NOT touching Gold.
Materializing agg_member_tier / agg_member_birth_month /
agg_member_recharge_daily is a SEPARATE, deliberate, reviewed step the
organizer runs after this script (and after confirming the Silver load
looks right), via — with the SAME factory_id this script loaded under (see
--factory-id below; 🔒 must match or the aggregate lands under the wrong
tenant):

    from smartbi.services.materialized_analytics.member_profile import (
        materialize_member_tier_profile, materialize_member_recharge_daily,
    )
    await materialize_member_tier_profile(pool, "<same factory_id>")
    await materialize_member_recharge_daily(pool, "<same factory_id>", date(2026, 1, 1), date(2026, 7, 31))

Why not SilverNormalizer.ingest_rows (like load_billgrain_demo_rest.py)?
-------------------------------------------------------------------------
SilverNormalizer's CanonicalRow dataclass is shaped for fact_pos_transaction
(bill grain) — it has no concept of a member-card snapshot or a recharge
transaction. Rather than force an unrelated shape through it, this script
uses DimResolver directly (same store upsert-and-cache primitive
SilverNormalizer itself uses internally) + raw asyncpg batch INSERTs
against dim_member and fact_member_recharge.

🔒 PII: the input JSONLs (produced by extract_shift_member_2026.py) already
contain ZERO PII fields (no card_no/name/phone/full-birthdate — only
tier/gender/birth_month/balance/issue_at for member rows, and no per-member
detail at all for recharge rows). This script does not need to do any
additional PII stripping; it just needs to not accidentally widen the
schema to add one back.

Idempotency:
  - dim_member: UNIQUE (factory_id, source_type, issue_store_id, issue_at)
    — 发卡日期 in the real export is a full timestamp (down to the second),
    so this is a solid natural key without needing card_no (which isn't
    stored). ON CONFLICT DO NOTHING makes reruns safe.
  - fact_member_recharge: UNIQUE (factory_id, source_type, store_id,
    recharge_date, channel) — the source report is already pre-aggregated
    to this grain. ON CONFLICT DO NOTHING makes reruns safe.

Usage (run by the organizer against prod, NOT by this session):
    cd backend/python
    python -m smartbi.scripts.load_member_demo_rest --dry-run   # preview only
    # 🔒 load under the tenant the CARD reads (aliased target):
    python -m smartbi.scripts.load_member_demo_rest --factory-id RES_3101_009
    python -m smartbi.scripts.load_member_demo_rest --dsn postgresql://...  # explicit DSN

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
logger = logging.getLogger("load_member_demo_rest")

from smartbi.canonical.dim_resolver import DimResolver  # noqa: E402

_DEFAULT_DETAIL_JSONL_PATH = Path(__file__).resolve().parent / "_demo_rest_member_detail_2026.jsonl"
_DEFAULT_RECHARGE_JSONL_PATH = Path(__file__).resolve().parent / "_demo_rest_member_recharge_2026.jsonl"
# factory_id is a RUNTIME arg (--factory-id), NOT hardcoded. 🔒 CRITICAL: the
# card reads /member-profile via gold_reads._resolve_tenant, which ALIASES
# DEMO_REST -> RES_3101_009 (DEMO_GOLD_TENANT_ALIASES). So the member rows
# must land under the SAME tenant the card actually queries. The organizer
# runs this for RES_3101_009 (what the card reads). A hardcoded DEMO_REST
# here would make the card show "未上传会员数据" forever (rows under
# DEMO_REST, card reads RES_3101_009) — same MUST-FIX 1 class of bug as
# load_void_demo_rest.py.
_DEFAULT_FACTORY_ID = "DEMO_REST"
_SOURCE_TYPE = "excel"
_BATCH_SIZE = 1000

_DETAIL_INSERT_SQL = """
INSERT INTO dim_member (
    factory_id, source_type, issue_store_id, tier, gender, birth_month,
    balance, issue_at
)
SELECT * FROM UNNEST(
    $1::text[], $2::text[], $3::bigint[], $4::text[], $5::text[],
    $6::smallint[], $7::numeric[], $8::timestamp[]
)
ON CONFLICT (factory_id, source_type, issue_store_id, issue_at)
DO NOTHING
RETURNING id
"""

_RECHARGE_INSERT_SQL = """
INSERT INTO fact_member_recharge (
    factory_id, source_type, store_id, recharge_date, channel,
    recharge_count, principal, bonus, other_amount, fee, actual_received
)
SELECT * FROM UNNEST(
    $1::text[], $2::text[], $3::bigint[], $4::date[], $5::text[],
    $6::int[], $7::numeric[], $8::numeric[], $9::numeric[], $10::numeric[], $11::numeric[]
)
ON CONFLICT (factory_id, source_type, store_id, recharge_date, channel)
DO NOTHING
RETURNING id
"""


@dataclass
class LoadStats:
    detail_rows_read: int = 0
    detail_rows_parsed: int = 0
    detail_rows_parse_failed: int = 0
    detail_written: int = 0
    detail_duplicates_skipped: int = 0
    recharge_rows_read: int = 0
    recharge_rows_parsed: int = 0
    recharge_rows_parse_failed: int = 0
    recharge_written: int = 0
    recharge_duplicates_skipped: int = 0
    stores_resolved: int = 0


@dataclass
class MemberDetailRecord:
    store_name: str  # 发卡门店
    tier: Optional[str]
    gender: Optional[str]
    birth_month: Optional[int]  # 1-12, or None
    balance: Optional[Decimal]
    issue_at: datetime


@dataclass
class MemberRechargeRecord:
    store_name: str  # 充值门店
    recharge_date: date
    channel: str  # '' never None — NOT NULL + UNIQUE-key member
    recharge_count: int
    principal: Decimal
    bonus: Decimal
    other_amount: Optional[Decimal]
    fee: Optional[Decimal]
    actual_received: Optional[Decimal]


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


def _to_date(v: Optional[str]) -> Optional[date]:
    if not v:
        return None
    try:
        return date.fromisoformat(v)
    except ValueError:
        return None


def _build_detail_record(record: Dict[str, Any]) -> Optional[MemberDetailRecord]:
    store_name = (record.get("store_name") or "").strip()
    issue_at = _to_datetime(record.get("issue_at"))
    if not store_name or issue_at is None:
        return None

    birth_month = record.get("birth_month")
    if birth_month is not None:
        try:
            birth_month = int(birth_month)
            if not (1 <= birth_month <= 12):
                birth_month = None
        except (TypeError, ValueError):
            birth_month = None

    return MemberDetailRecord(
        store_name=store_name,
        tier=(record.get("tier") or None),
        gender=(record.get("gender") or None),
        birth_month=birth_month,
        balance=_to_decimal(record.get("balance")),
        issue_at=issue_at,
    )


def _build_recharge_record(record: Dict[str, Any]) -> Optional[MemberRechargeRecord]:
    store_name = (record.get("store_name") or "").strip()
    d = _to_date(record.get("date"))
    if not store_name or d is None:
        return None

    count_raw = record.get("recharge_count")
    try:
        recharge_count = int(count_raw) if count_raw is not None else 0
    except (TypeError, ValueError):
        recharge_count = 0

    return MemberRechargeRecord(
        store_name=store_name,
        recharge_date=d,
        channel=(record.get("channel") or ""),  # '' preserved, never None (see migration note)
        recharge_count=recharge_count,
        principal=_to_decimal(record.get("principal")) or Decimal("0"),
        bonus=_to_decimal(record.get("bonus")) or Decimal("0"),
        other_amount=_to_decimal(record.get("other_amount")),
        fee=_to_decimal(record.get("fee")),
        actual_received=_to_decimal(record.get("actual_received")),
    )


def _iter_detail_records(jsonl_path: Path, stats: LoadStats):
    with jsonl_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            stats.detail_rows_read += 1
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                stats.detail_rows_parse_failed += 1
                continue
            row = _build_detail_record(record)
            if row is None:
                stats.detail_rows_parse_failed += 1
                continue
            stats.detail_rows_parsed += 1
            yield row


def _iter_recharge_records(jsonl_path: Path, stats: LoadStats):
    with jsonl_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            stats.recharge_rows_read += 1
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                stats.recharge_rows_parse_failed += 1
                continue
            row = _build_recharge_record(record)
            if row is None:
                stats.recharge_rows_parse_failed += 1
                continue
            stats.recharge_rows_parsed += 1
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


async def _write_detail_batch(
    pool, resolver: DimResolver, batch: List[MemberDetailRecord], stats: LoadStats, factory_id: str,
) -> None:
    factory_ids: List[str] = []
    source_types: List[str] = []
    store_ids: List[int] = []
    tiers: List[str] = []
    genders: List[str] = []
    birth_months: List[Optional[int]] = []
    balances: List[Decimal] = []
    issue_ats: List[datetime] = []

    for rec in batch:
        store_id = await resolver.resolve_store(rec.store_name)
        stats.stores_resolved += 1

        factory_ids.append(factory_id)
        source_types.append(_SOURCE_TYPE)
        store_ids.append(store_id)
        tiers.append(rec.tier or "未知")
        genders.append(rec.gender or "未知")
        birth_months.append(rec.birth_month)
        balances.append(rec.balance if rec.balance is not None else Decimal("0"))
        issue_ats.append(rec.issue_at)

    async with pool.acquire() as conn:
        inserted_rows = await conn.fetch(
            _DETAIL_INSERT_SQL,
            factory_ids, source_types, store_ids, tiers, genders,
            birth_months, balances, issue_ats,
        )
    written = len(inserted_rows)
    stats.detail_written += written
    stats.detail_duplicates_skipped += len(batch) - written


async def _write_recharge_batch(
    pool, resolver: DimResolver, batch: List[MemberRechargeRecord], stats: LoadStats, factory_id: str,
) -> None:
    factory_ids: List[str] = []
    source_types: List[str] = []
    store_ids: List[int] = []
    dates: List[date] = []
    channels: List[str] = []
    counts: List[int] = []
    principals: List[Decimal] = []
    bonuses: List[Decimal] = []
    others: List[Optional[Decimal]] = []
    fees: List[Optional[Decimal]] = []
    actuals: List[Optional[Decimal]] = []

    for rec in batch:
        store_id = await resolver.resolve_store(rec.store_name)
        stats.stores_resolved += 1

        factory_ids.append(factory_id)
        source_types.append(_SOURCE_TYPE)
        store_ids.append(store_id)
        dates.append(rec.recharge_date)
        channels.append(rec.channel)
        counts.append(rec.recharge_count)
        principals.append(rec.principal)
        bonuses.append(rec.bonus)
        others.append(rec.other_amount)
        fees.append(rec.fee)
        actuals.append(rec.actual_received)

    async with pool.acquire() as conn:
        inserted_rows = await conn.fetch(
            _RECHARGE_INSERT_SQL,
            factory_ids, source_types, store_ids, dates, channels,
            counts, principals, bonuses, others, fees, actuals,
        )
    written = len(inserted_rows)
    stats.recharge_written += written
    stats.recharge_duplicates_skipped += len(batch) - written


async def _run(
    detail_jsonl_path: Path,
    recharge_jsonl_path: Path,
    dsn: Optional[str],
    dry_run: bool,
    factory_id: str,
) -> LoadStats:
    stats = LoadStats()
    detail_rows: List[MemberDetailRecord] = list(_iter_detail_records(detail_jsonl_path, stats))
    recharge_rows: List[MemberRechargeRecord] = list(_iter_recharge_records(recharge_jsonl_path, stats))

    print(f"detail jsonl:          {detail_jsonl_path}")
    print(f"recharge jsonl:        {recharge_jsonl_path}")
    print(f"factory_id:            {factory_id}")
    print(f"detail rows_read:      {stats.detail_rows_read}")
    print(f"detail rows_parsed:    {stats.detail_rows_parsed}")
    print(f"detail rows_parse_failed: {stats.detail_rows_parse_failed}")
    print(f"recharge rows_read:    {stats.recharge_rows_read}")
    print(f"recharge rows_parsed:  {stats.recharge_rows_parsed}")
    print(f"recharge rows_parse_failed: {stats.recharge_rows_parse_failed}")

    if dry_run:
        print("--dry-run: not connecting to DB, not writing.")
        distinct_stores = {r.store_name for r in detail_rows} | {r.store_name for r in recharge_rows}
        print(f"distinct stores across both files: {len(distinct_stores)}")
        return stats

    if not detail_rows and not recharge_rows:
        print("nothing to write.")
        return stats

    pool = await _get_pool(dsn)
    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(factory_id)

    resolver = DimResolver(pool, factory_id=factory_id)

    for start in range(0, len(detail_rows), _BATCH_SIZE):
        batch = detail_rows[start:start + _BATCH_SIZE]
        await _write_detail_batch(pool, resolver, batch, stats, factory_id)
        print(
            f"detail batch {start}-{start + len(batch)}: "
            f"written(cumulative)={stats.detail_written} "
            f"dup_skipped(cumulative)={stats.detail_duplicates_skipped}"
        )

    for start in range(0, len(recharge_rows), _BATCH_SIZE):
        batch = recharge_rows[start:start + _BATCH_SIZE]
        await _write_recharge_batch(pool, resolver, batch, stats, factory_id)
        print(
            f"recharge batch {start}-{start + len(batch)}: "
            f"written(cumulative)={stats.recharge_written} "
            f"dup_skipped(cumulative)={stats.recharge_duplicates_skipped}"
        )

    print()
    print(f"TOTAL detail rows written:      {stats.detail_written}")
    print(f"TOTAL detail duplicates_skipped: {stats.detail_duplicates_skipped}")
    print(f"TOTAL recharge rows written:      {stats.recharge_written}")
    print(f"TOTAL recharge duplicates_skipped: {stats.recharge_duplicates_skipped}")
    print(f"TOTAL stores resolved (incl. cache hits): {stats.stores_resolved}")
    print()
    print(
        "NOTE: this script does NOT call the Gold materializer. If "
        "agg_member_tier / agg_member_birth_month / agg_member_recharge_daily "
        "need to reflect these rows, run "
        f"materialize_member_tier_profile(pool, '{factory_id}') and "
        f"materialize_member_recharge_daily(pool, '{factory_id}', date_min, date_max) "
        "as a SEPARATE, deliberate step — see module docstring. "
        "🔒 Use this SAME factory_id or the aggregates land under the wrong tenant."
    )
    return stats


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--detail-jsonl", default=str(_DEFAULT_DETAIL_JSONL_PATH), help="input JSONL path (member card detail)")
    ap.add_argument("--recharge-jsonl", default=str(_DEFAULT_RECHARGE_JSONL_PATH), help="input JSONL path (member recharge)")
    ap.add_argument(
        "--factory-id",
        default=_DEFAULT_FACTORY_ID,
        help=(
            "tenant to load member data under. 🔒 The card reads "
            "/member-profile via an aliased tenant (DEMO_REST -> "
            "RES_3101_009), so to make the card render, load under "
            "RES_3101_009."
        ),
    )
    ap.add_argument("--dsn", default=None, help="explicit Postgres DSN (overrides env-based pool)")
    ap.add_argument("--dry-run", action="store_true", help="parse + report counts only, no DB writes")
    args = ap.parse_args()

    detail_jsonl_path = Path(args.detail_jsonl)
    recharge_jsonl_path = Path(args.recharge_jsonl)
    if not detail_jsonl_path.exists():
        raise SystemExit(f"jsonl not found: {detail_jsonl_path} (run extract_shift_member_2026.py first)")
    if not recharge_jsonl_path.exists():
        raise SystemExit(f"jsonl not found: {recharge_jsonl_path} (run extract_shift_member_2026.py first)")

    asyncio.run(_run(detail_jsonl_path, recharge_jsonl_path, args.dsn, args.dry_run, args.factory_id))


if __name__ == "__main__":
    main()
