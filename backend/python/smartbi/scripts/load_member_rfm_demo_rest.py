"""One-off ops script — load the DEMO_REST 有滋有味 member RFM (+ optional
画像/储值趋势) JSONLs (produced by `extract_member_rfm.py`) into Postgres.

New restaurant analytics dimension: 会员 RFM (CRM P0) — greenfield.

⛔ DELIBERATE CLOBBER-AVOIDANCE DESIGN — READ BEFORE EDITING ⛔
----------------------------------------------------------------
This script writes ONLY to `fact_member_consumption` (+, if --load-profile is
passed, ALSO `dim_member` / `fact_member_recharge` — see the "cohort switch"
note below) + the `dim_store` upserts that `DimResolver.resolve_store`
performs as a side effect. It must NEVER call:
  - smartbi.services.materialized_analytics.member_rfm.materialize_member_rfm
  - smartbi.services.materialized_analytics.member_profile.materialize_member_tier_profile
  - smartbi.services.materialized_analytics.member_profile.materialize_member_recharge_daily
  - smartbi.gold.ingest_and_materialize
  - any other GoldMaterializer method
  - scripts.backfill_silver.backfill_upload

Why: same reasoning as load_member_demo_rest.py's docstring — this script's
whole job is loading Silver rows. Materializing the Gold aggregates is a
SEPARATE, deliberate, reviewed step the organizer runs after this script
(and after confirming the Silver load looks right), via — with the SAME
factory_id this script loaded under (🔒 must match or the aggregate lands
under the wrong tenant):

    from smartbi.services.materialized_analytics.member_rfm import materialize_member_rfm
    await materialize_member_rfm(pool, "<same factory_id>")

    # If --load-profile was also used:
    from smartbi.services.materialized_analytics.member_profile import (
        materialize_member_tier_profile, materialize_member_recharge_daily,
    )
    await materialize_member_tier_profile(pool, "<same factory_id>")
    await materialize_member_recharge_daily(pool, "<same factory_id>", date_min, date_max)

⚠️ COHORT-SWAP DECISION — NOT MADE BY THIS SCRIPT (organizer's call)
----------------------------------------------------------------------
DEMO_REST's existing member_profile dimension (V20261007_*) was seeded from
a DIFFERENT real customer chain (青花椒). The CRM P0 task brief's stated
goal is "cohort 换成有滋有味 (RFM/画像/储值同源一致)" — i.e. eventually make
BOTH member-analytics cards read from the SAME 有滋有味 cohort. This script
does NOT delete/replace the existing 青花椒-sourced dim_member /
fact_member_recharge rows — `--load-profile` only ADDS 有滋有味 rows
alongside them (idempotent ON CONFLICT DO NOTHING, mirrors
load_member_demo_rest.py exactly). Whether the 青花椒 rows should be purged
first (to avoid mixing two different real chains' cohorts under one tenant)
is a data-policy decision for the organizer, not this script. By default
(`--load-profile` NOT passed), this script touches ONLY
fact_member_consumption (required RFM data) and leaves member_profile's
existing tables untouched.

Idempotency:
  - fact_member_consumption: UNIQUE (factory_id, card_no) — 卡消费排行 is a
    live cumulative snapshot (like dim_member), so re-running against a
    REFRESHED export should UPDATE a card's numbers (spend grows,
    last_spend_date moves forward), not silently skip. ON CONFLICT ...
    DO UPDATE (NOT DO NOTHING — see V20261008_01's header note for why this
    differs from dim_member/fact_member_recharge's DO NOTHING semantics).
  - dim_member / fact_member_recharge (only touched with --load-profile):
    same UNIQUE keys + ON CONFLICT DO NOTHING as load_member_demo_rest.py.

Usage (run by the organizer against prod, NOT by this session):
    cd backend/python
    python -m smartbi.scripts.load_member_rfm_demo_rest --dry-run   # preview only
    # 🔒 load under the tenant the card reads (aliased target):
    python -m smartbi.scripts.load_member_rfm_demo_rest --factory-id RES_3101_009
    # also refresh member_profile's 画像/储值趋势 dimension from the SAME
    # 有滋有味 cohort (adds alongside any existing 青花椒 rows — see note above):
    python -m smartbi.scripts.load_member_rfm_demo_rest --factory-id RES_3101_009 --load-profile
    python -m smartbi.scripts.load_member_rfm_demo_rest --dsn postgresql://...  # explicit DSN

DB connection: by default uses `smartbi.config.get_pg_pool()` — see
load_member_demo_rest.py's identical note on env vars / .claude/rules/db-credentials.md.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Dict, List, Optional

# NOTE: only add backend/python itself (not backend/python/smartbi) to
# sys.path — see load_billgrain_demo_rest.py's identical comment for why.
_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
if str(_PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(_PYTHON_ROOT))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("load_member_rfm_demo_rest")

from smartbi.canonical.dim_resolver import DimResolver  # noqa: E402

_DEFAULT_RFM_JSONL_PATH = Path(__file__).resolve().parent / "_demo_rest_member_rfm_2026.jsonl"
_DEFAULT_DETAIL_JSONL_PATH = Path(__file__).resolve().parent / "_demo_rest_member_profile_detail.jsonl"
_DEFAULT_RECHARGE_JSONL_PATH = Path(__file__).resolve().parent / "_demo_rest_member_profile_recharge.jsonl"
# 🔒 CRITICAL: the card reads /member-rfm and /member-profile via
# gold_reads._resolve_tenant, which ALIASES DEMO_REST -> RES_3101_009
# (DEMO_GOLD_TENANT_ALIASES). Rows must land under the SAME tenant the card
# actually queries — see load_member_demo_rest.py's identical note.
_DEFAULT_FACTORY_ID = "DEMO_REST"
_SOURCE_TYPE = "excel"
_BATCH_SIZE = 1000

_RFM_UPSERT_SQL = """
INSERT INTO fact_member_consumption AS f (
    factory_id, source_type, card_no, member_name, tier, store_id,
    issue_store_id, principal_balance, bonus_balance, total_balance,
    cum_principal_spend, cum_bonus_spend, cum_spend, spend_count,
    last_spend_date, spend_interval_days, issue_date
)
SELECT * FROM UNNEST(
    $1::text[], $2::text[], $3::text[], $4::text[], $5::text[], $6::bigint[],
    $7::bigint[], $8::numeric[], $9::numeric[], $10::numeric[],
    $11::numeric[], $12::numeric[], $13::numeric[], $14::int[],
    $15::timestamp[], $16::int[], $17::date[]
)
ON CONFLICT (factory_id, card_no)
DO UPDATE SET
    member_name          = EXCLUDED.member_name,
    tier                 = EXCLUDED.tier,
    store_id             = EXCLUDED.store_id,
    issue_store_id       = EXCLUDED.issue_store_id,
    principal_balance    = EXCLUDED.principal_balance,
    bonus_balance        = EXCLUDED.bonus_balance,
    total_balance        = EXCLUDED.total_balance,
    cum_principal_spend  = EXCLUDED.cum_principal_spend,
    cum_bonus_spend      = EXCLUDED.cum_bonus_spend,
    cum_spend            = EXCLUDED.cum_spend,
    spend_count          = EXCLUDED.spend_count,
    last_spend_date      = EXCLUDED.last_spend_date,
    spend_interval_days  = EXCLUDED.spend_interval_days,
    issue_date           = EXCLUDED.issue_date
RETURNING (xmax = 0) AS was_insert
"""

# --load-profile secondary path — mirrors load_member_demo_rest.py's SQL
# exactly (dim_member / fact_member_recharge schemas are V20261007_01's,
# unchanged by this script).
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
    rfm_rows_read: int = 0
    rfm_rows_parsed: int = 0
    rfm_rows_parse_failed: int = 0
    rfm_written: int = 0
    rfm_updated: int = 0
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
class RfmRecord:
    card_no: str
    member_name: Optional[str]
    tier: Optional[str]
    store_name: str          # 卡所属门店
    issue_store_name: str    # 发卡门店
    principal_balance: Optional[Decimal]
    bonus_balance: Optional[Decimal]
    total_balance: Optional[Decimal]
    cum_principal_spend: Optional[Decimal]
    cum_bonus_spend: Optional[Decimal]
    cum_spend: Optional[Decimal]
    spend_count: int
    last_spend_date: datetime
    spend_interval_days: Optional[int]
    issue_date: date


@dataclass
class MemberDetailRecord:
    store_name: str
    tier: Optional[str]
    gender: Optional[str]
    birth_month: Optional[int]
    balance: Optional[Decimal]
    issue_at: datetime


@dataclass
class MemberRechargeRecord:
    store_name: str
    recharge_date: date
    channel: str
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


# ── RFM (required) ──────────────────────────────────────────────────────


def _build_rfm_record(record: Dict[str, Any]) -> Optional[RfmRecord]:
    card_no = (record.get("card_no") or "").strip()
    store_name = (record.get("store_name") or "").strip()
    issue_store_name = (record.get("issue_store_name") or "").strip()
    last_spend_date = _to_datetime(record.get("last_spend_date"))
    issue_date = _to_date(record.get("issue_date"))

    if not card_no or not store_name or not issue_store_name or last_spend_date is None or issue_date is None:
        return None

    spend_count_raw = record.get("spend_count")
    try:
        spend_count = int(spend_count_raw) if spend_count_raw is not None else 0
    except (TypeError, ValueError):
        spend_count = 0

    interval_raw = record.get("spend_interval_days")
    try:
        spend_interval_days = int(interval_raw) if interval_raw is not None else None
    except (TypeError, ValueError):
        spend_interval_days = None

    return RfmRecord(
        card_no=card_no,
        member_name=(record.get("member_name") or None),
        tier=(record.get("tier") or None),
        store_name=store_name,
        issue_store_name=issue_store_name,
        principal_balance=_to_decimal(record.get("principal_balance")),
        bonus_balance=_to_decimal(record.get("bonus_balance")),
        total_balance=_to_decimal(record.get("total_balance")),
        cum_principal_spend=_to_decimal(record.get("cum_principal_spend")),
        cum_bonus_spend=_to_decimal(record.get("cum_bonus_spend")),
        cum_spend=_to_decimal(record.get("cum_spend")),
        spend_count=spend_count,
        last_spend_date=last_spend_date,
        spend_interval_days=spend_interval_days,
        issue_date=issue_date,
    )


def _iter_rfm_records(jsonl_path: Path, stats: LoadStats):
    with jsonl_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            stats.rfm_rows_read += 1
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                stats.rfm_rows_parse_failed += 1
                continue
            row = _build_rfm_record(record)
            if row is None:
                stats.rfm_rows_parse_failed += 1
                continue
            stats.rfm_rows_parsed += 1
            yield row


async def _write_rfm_batch(
    pool, resolver: DimResolver, batch: List[RfmRecord], stats: LoadStats, factory_id: str,
) -> None:
    factory_ids: List[str] = []
    source_types: List[str] = []
    card_nos: List[str] = []
    member_names: List[Optional[str]] = []
    tiers: List[str] = []
    store_ids: List[int] = []
    issue_store_ids: List[int] = []
    principal_balances: List[Decimal] = []
    bonus_balances: List[Decimal] = []
    total_balances: List[Decimal] = []
    cum_principal_spends: List[Decimal] = []
    cum_bonus_spends: List[Decimal] = []
    cum_spends: List[Decimal] = []
    spend_counts: List[int] = []
    last_spend_dates: List[datetime] = []
    spend_interval_days_list: List[Optional[int]] = []
    issue_dates: List[date] = []

    for rec in batch:
        store_id = await resolver.resolve_store(rec.store_name)
        issue_store_id = await resolver.resolve_store(rec.issue_store_name)
        stats.stores_resolved += 2

        factory_ids.append(factory_id)
        source_types.append(_SOURCE_TYPE)
        card_nos.append(rec.card_no)
        member_names.append(rec.member_name)
        tiers.append(rec.tier or "未知")
        store_ids.append(store_id)
        issue_store_ids.append(issue_store_id)
        principal_balances.append(rec.principal_balance if rec.principal_balance is not None else Decimal("0"))
        bonus_balances.append(rec.bonus_balance if rec.bonus_balance is not None else Decimal("0"))
        total_balances.append(rec.total_balance if rec.total_balance is not None else Decimal("0"))
        cum_principal_spends.append(rec.cum_principal_spend if rec.cum_principal_spend is not None else Decimal("0"))
        cum_bonus_spends.append(rec.cum_bonus_spend if rec.cum_bonus_spend is not None else Decimal("0"))
        cum_spends.append(rec.cum_spend if rec.cum_spend is not None else Decimal("0"))
        spend_counts.append(rec.spend_count)
        last_spend_dates.append(rec.last_spend_date)
        spend_interval_days_list.append(rec.spend_interval_days)
        issue_dates.append(rec.issue_date)

    async with pool.acquire() as conn:
        rows = await conn.fetch(
            _RFM_UPSERT_SQL,
            factory_ids, source_types, card_nos, member_names, tiers, store_ids,
            issue_store_ids, principal_balances, bonus_balances, total_balances,
            cum_principal_spends, cum_bonus_spends, cum_spends, spend_counts,
            last_spend_dates, spend_interval_days_list, issue_dates,
        )
    inserted = sum(1 for r in rows if r["was_insert"])
    updated = len(rows) - inserted
    stats.rfm_written += inserted
    stats.rfm_updated += updated


# ── profile detail / recharge (optional, --load-profile) ────────────────
# Identical logic to load_member_demo_rest.py — see that script for the
# same functions' original commentary.


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
        channel=(record.get("channel") or ""),
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


async def _write_detail_batch(
    pool, resolver: DimResolver, batch: List[MemberDetailRecord], stats: LoadStats, factory_id: str,
) -> None:
    factory_ids, source_types, store_ids = [], [], []
    tiers, genders, birth_months, balances, issue_ats = [], [], [], [], []

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
    factory_ids, source_types, store_ids, dates, channels = [], [], [], [], []
    counts, principals, bonuses, others, fees, actuals = [], [], [], [], [], []

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


async def _get_pool(dsn: Optional[str]):
    if dsn:
        import asyncpg
        from smartbi.tenant_ctx import set_pg_connection_tenant
        return await asyncpg.create_pool(
            dsn, min_size=1, max_size=3, setup=set_pg_connection_tenant,
        )
    from smartbi.config import get_pg_pool
    return await get_pg_pool()


async def _run(
    rfm_jsonl_path: Path,
    detail_jsonl_path: Path,
    recharge_jsonl_path: Path,
    dsn: Optional[str],
    dry_run: bool,
    factory_id: str,
    load_profile: bool,
) -> LoadStats:
    stats = LoadStats()
    rfm_rows: List[RfmRecord] = list(_iter_rfm_records(rfm_jsonl_path, stats))

    print(f"rfm jsonl:             {rfm_jsonl_path}")
    print(f"factory_id:            {factory_id}")
    print(f"load_profile:          {load_profile}")
    print(f"rfm rows_read:         {stats.rfm_rows_read}")
    print(f"rfm rows_parsed:       {stats.rfm_rows_parsed}")
    print(f"rfm rows_parse_failed: {stats.rfm_rows_parse_failed}")

    detail_rows: List[MemberDetailRecord] = []
    recharge_rows: List[MemberRechargeRecord] = []
    if load_profile:
        if detail_jsonl_path.exists():
            detail_rows = list(_iter_detail_records(detail_jsonl_path, stats))
            print(f"detail jsonl:          {detail_jsonl_path}")
            print(f"detail rows_read:      {stats.detail_rows_read}")
            print(f"detail rows_parsed:    {stats.detail_rows_parsed}")
        else:
            print(f"⚠️ --load-profile set but detail jsonl not found: {detail_jsonl_path}")
        if recharge_jsonl_path.exists():
            recharge_rows = list(_iter_recharge_records(recharge_jsonl_path, stats))
            print(f"recharge jsonl:        {recharge_jsonl_path}")
            print(f"recharge rows_read:    {stats.recharge_rows_read}")
            print(f"recharge rows_parsed:  {stats.recharge_rows_parsed}")
        else:
            print(f"⚠️ --load-profile set but recharge jsonl not found: {recharge_jsonl_path}")

    if dry_run:
        print("--dry-run: not connecting to DB, not writing.")
        distinct_stores = {r.store_name for r in rfm_rows} | {r.issue_store_name for r in rfm_rows}
        print(f"distinct stores across rfm rows: {len(distinct_stores)}")
        return stats

    if not rfm_rows and not detail_rows and not recharge_rows:
        print("nothing to write.")
        return stats

    pool = await _get_pool(dsn)
    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(factory_id)

    resolver = DimResolver(pool, factory_id=factory_id)

    for start in range(0, len(rfm_rows), _BATCH_SIZE):
        batch = rfm_rows[start:start + _BATCH_SIZE]
        await _write_rfm_batch(pool, resolver, batch, stats, factory_id)
        print(
            f"rfm batch {start}-{start + len(batch)}: "
            f"inserted(cumulative)={stats.rfm_written} updated(cumulative)={stats.rfm_updated}"
        )

    for start in range(0, len(detail_rows), _BATCH_SIZE):
        batch = detail_rows[start:start + _BATCH_SIZE]
        await _write_detail_batch(pool, resolver, batch, stats, factory_id)
        print(
            f"detail batch {start}-{start + len(batch)}: "
            f"written(cumulative)={stats.detail_written} dup_skipped(cumulative)={stats.detail_duplicates_skipped}"
        )

    for start in range(0, len(recharge_rows), _BATCH_SIZE):
        batch = recharge_rows[start:start + _BATCH_SIZE]
        await _write_recharge_batch(pool, resolver, batch, stats, factory_id)
        print(
            f"recharge batch {start}-{start + len(batch)}: "
            f"written(cumulative)={stats.recharge_written} dup_skipped(cumulative)={stats.recharge_duplicates_skipped}"
        )

    print()
    print(f"TOTAL rfm rows inserted:  {stats.rfm_written}")
    print(f"TOTAL rfm rows updated:   {stats.rfm_updated}")
    if load_profile:
        print(f"TOTAL detail rows written:        {stats.detail_written}")
        print(f"TOTAL detail duplicates_skipped:  {stats.detail_duplicates_skipped}")
        print(f"TOTAL recharge rows written:      {stats.recharge_written}")
        print(f"TOTAL recharge duplicates_skipped: {stats.recharge_duplicates_skipped}")
    print(f"TOTAL stores resolved (incl. cache hits): {stats.stores_resolved}")
    print()
    print(
        "NOTE: this script does NOT call the Gold materializer. If "
        "agg_member_rfm_segment / agg_member_rfm_tier / agg_member_lifecycle "
        "need to reflect these rows, run "
        f"materialize_member_rfm(pool, '{factory_id}') as a SEPARATE, "
        "deliberate step — see module docstring. 🔒 Use this SAME "
        "factory_id or the aggregates land under the wrong tenant."
    )
    return stats


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--rfm-jsonl", default=str(_DEFAULT_RFM_JSONL_PATH), help="input JSONL path (member consumption RFM, required)")
    ap.add_argument("--detail-jsonl", default=str(_DEFAULT_DETAIL_JSONL_PATH), help="input JSONL path (member profile detail, optional, needs --load-profile)")
    ap.add_argument("--recharge-jsonl", default=str(_DEFAULT_RECHARGE_JSONL_PATH), help="input JSONL path (member recharge, optional, needs --load-profile)")
    ap.add_argument(
        "--factory-id",
        default=_DEFAULT_FACTORY_ID,
        help=(
            "tenant to load member RFM data under. 🔒 The card reads "
            "/member-rfm via an aliased tenant (DEMO_REST -> RES_3101_009), "
            "so to make the card render, load under RES_3101_009."
        ),
    )
    ap.add_argument(
        "--load-profile",
        action="store_true",
        help=(
            "ALSO load dim_member / fact_member_recharge from the 有滋有味 "
            "画像/储值趋势 JSONLs (ADDS alongside any existing 青花椒-sourced "
            "rows under the same tenant — does NOT delete them; see module "
            "docstring's 'COHORT-SWAP DECISION' note). Off by default."
        ),
    )
    ap.add_argument("--dsn", default=None, help="explicit Postgres DSN (overrides env-based pool)")
    ap.add_argument("--dry-run", action="store_true", help="parse + report counts only, no DB writes")
    args = ap.parse_args()

    rfm_jsonl_path = Path(args.rfm_jsonl)
    if not rfm_jsonl_path.exists():
        raise SystemExit(f"jsonl not found: {rfm_jsonl_path} (run extract_member_rfm.py first)")

    asyncio.run(_run(
        rfm_jsonl_path,
        Path(args.detail_jsonl),
        Path(args.recharge_jsonl),
        args.dsn, args.dry_run, args.factory_id, args.load_profile,
    ))


if __name__ == "__main__":
    main()
