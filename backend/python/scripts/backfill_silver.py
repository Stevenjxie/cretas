"""Backfill an existing upload from smart_bi_dynamic_data → Silver+Gold.

Week 4 Day 2 of Unified Data Layer v1 spec (Phase A.5).

Usage (CLI, admin-triggered):
    python -m scripts.backfill_silver --upload-id 4169 --factory F001 [--limit 500]

Or as a library:
    from scripts.backfill_silver import backfill_upload
    stats = await backfill_upload(pool, upload_id=4169, factory_id="F001")

What it does
------------
1. Reads the upload's field_mappings from smart_bi_pg_excel_uploads.
2. Streams rows from smart_bi_dynamic_data for the given upload_id.
3. For each row, applies field_mappings to extract canonical fields and
   constructs a CanonicalRow. Rows missing required fields (store_name,
   source_bill_no, date) are skipped + counted in the stats.
4. Delegates to gold.pipeline.ingest_and_materialize which writes Silver
   + materializes Gold.

This is Phase A.5 tooling — meant for backfilling the qhj 4169 upload
(per spec) so Phase B shadow-read can compare old vs. new paths. Safe
to re-run on the same upload_id; Silver's ON CONFLICT DO NOTHING makes
each row idempotent.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import re
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any, Dict, Iterable, List, Optional, Tuple

import asyncpg

from smartbi.canonical import CanonicalRow
from smartbi.canonical.aliases import ALIAS_TO_ATTR  # 数据织网 A v1.4 §3.0: 搬到 canonical/aliases.py
from smartbi.gold import PipelineStats, ingest_and_materialize

logger = logging.getLogger(__name__)


# 数据织网 A v1.4 §3.0 + Day 0 audit option 2: ALIAS dict 已搬到 smartbi/canonical/aliases.py
# 含 v1.0 新增 product_summary 维度字段 (商品名/编码/分类/销量/单价/退货等)
# 此处保留旧 _ALIAS_TO_ATTR 别名为向后兼容 (内部其它代码可能 import)
_ALIAS_TO_ATTR: Dict[str, str] = ALIAS_TO_ATTR


_REQUIRED_ATTRS = ("store_name", "source_bill_no", "date")


# Discount columns are detected by heuristic because qhj has 50+ voucher
# types (each 代金券 denomination is one column); a hardcoded list would
# rot as new vouchers are issued. Pattern: contains 券/优惠 OR 代<digit>
# (e.g. 点评98代100). Excluded: suffix "(不计)" — those are 0-charge
# markers (招待/员工餐/霸王餐/客诉免单) that don't contribute to revenue.
_DISCOUNT_MARKER_RE = re.compile(r'代\d|券|优惠')
_DISCOUNT_EXCLUDE_SUFFIX = "(不计)"


def _is_discount_column(col_name: str) -> bool:
    if _DISCOUNT_EXCLUDE_SUFFIX in col_name:
        return False
    return bool(_DISCOUNT_MARKER_RE.search(col_name))


# meal_period (班次/市段) normalization. The qhj 订单销售明细表 export uses
# operational shift labels (中班/晚班) for ~8.6% of rows that the
# fact_pos_transaction.chk_meal_period CHECK does NOT accept — those rows
# would either violate the constraint or be coerced out. Map shift labels to
# the canonical meal-period buckets the CHECK allows (V20260513_01: 午市/晚市
# /早餐/午餐/下午茶/晚餐/其他/未分类). 中班 (mid shift) → 午市, 晚班 (night
# shift) → 晚市. Anything already canonical (午市/晚市/...) passes through.
# (2026-05-30 — second of the two qhj ETL bugs; the first was the 外部单号
# alias clobber, see aliases.py.)
_MEAL_PERIOD_NORMALIZE = {
    "中班": "午市",
    "晚班": "晚市",
}


def _normalize_meal_period(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    s = str(raw).strip()
    if not s:
        return None
    return _MEAL_PERIOD_NORMALIZE.get(s, s)


# EAV payment columns — qhj exports one column per payment method. For each
# row, any of these columns with a non-zero decimal value becomes a
# fact_pos_payment entry. Everything else we don't know about stays in
# `unknown_canonical_names` for admin review.
#
# Why hardcoded rather than heuristic: "contains no 代/券" is fragile — e.g.
# "新美大" is a platform name with no marker. A curated set is auditable
# and covers the qhj schema exactly.
_PAYMENT_COLUMNS = {
    "现金",
    "微信",
    "[微信]",
    "美团",
    "[美团]",
    "[美团支付]",
    "新美大",
    "银行卡",
    "[支付宝]",
    "商家支付宝",
    "[云闪付]",
    "银联二维码",
    "饿了么",
    "[饿了么]",
    "京东外卖",
    "[京东]",
    "交行买单",
    "招行买单",
    "点评买单",
    "网络支付",
    "储值卡",
    "会员卡",
    "商场积分",
    "商场饭卡",
    "商场泷珠支付",
}


@dataclass
class BackfillStats:
    upload_id: int
    factory_id: str
    rows_read: int = 0
    rows_skipped_missing_required: int = 0
    rows_queued: int = 0
    unknown_canonical_names: List[str] = field(default_factory=list)
    pipeline: Optional[PipelineStats] = None


def _parse_date(raw: Any) -> Optional[date]:
    """Best-effort date parse. Accepts 'YYYY-MM-DD', 'YYYY/MM/DD', or
    already-parsed date/datetime objects. Returns None on failure."""
    if raw is None or raw == "":
        return None
    if isinstance(raw, date) and not isinstance(raw, datetime):
        return raw
    if isinstance(raw, datetime):
        return raw.date()
    s = str(raw).strip()
    # Common shapes: "2026-04-21", "2026/4/21", "2026-04-21 12:34:56"
    m = re.match(r"^(\d{4})[-/](\d{1,2})[-/](\d{1,2})", s)
    if not m:
        return None
    try:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def _parse_decimal(raw: Any) -> Optional[Decimal]:
    if raw is None or raw == "":
        return None
    if isinstance(raw, Decimal):
        return raw
    try:
        # Strip currency symbols / commas common in Excel exports.
        s = str(raw).replace("¥", "").replace(",", "").strip()
        if not s:
            return None
        return Decimal(s)
    except (InvalidOperation, ValueError):
        return None


def _parse_int(raw: Any) -> Optional[int]:
    if raw is None or raw == "":
        return None
    try:
        return int(float(str(raw).strip()))
    except (ValueError, TypeError):
        return None


def _normalize_field_mappings(raw: Any) -> Dict[str, str]:
    """Normalize field_mappings JSONB into a {original_col: standard_field} dict.

    Legacy storage has two shapes in the wild:
      1. Dict format: {"门店名称": "store_name", ...}
      2. Array-of-objects format: [{"originalColumn": "门店名称",
         "standardField": "category_name", "dataType": "TEXT", ...}]

    Both come back from asyncpg as parsed Python objects (dict / list) when
    the JSONB codec is active, or as strings when not — handle both.
    """
    if raw is None:
        return {}
    if isinstance(raw, str):
        import json
        raw = json.loads(raw) if raw else None
        if raw is None:
            return {}
    if isinstance(raw, dict):
        return {str(k): str(v) for k, v in raw.items() if v is not None}
    if isinstance(raw, list):
        out: Dict[str, str] = {}
        for item in raw:
            if not isinstance(item, dict):
                continue
            orig = item.get("originalColumn") or item.get("original")
            std = item.get("standardField") or item.get("standard")
            if orig:
                out[str(orig)] = str(std) if std else ""
        return out
    return {}


def _lookup_attr(original_col: str, canonical_name: Optional[str]) -> Optional[str]:
    """Two-path lookup for mapping a column to a CanonicalRow attr:

    1. Preferred: canonical_name (from semantic_mapper's standardField).
    2. Fallback: original_col (the raw Chinese/English column name).

    The fallback matters because semantic_mapper sometimes emits generic
    type tags like 'category_name' / 'time_period' instead of specific
    business fields. In that case the Chinese original column name is the
    only reliable signal — so we look it up too.
    """
    if canonical_name:
        attr = _ALIAS_TO_ATTR.get(canonical_name)
        if attr is not None:
            return attr
    return _ALIAS_TO_ATTR.get(original_col)


def _extract_payments(row_data: Dict[str, Any]) -> Tuple[Tuple[str, Decimal], ...]:
    """Scan row_data for known payment-method columns with non-zero amount.

    Returns a tuple of (channel_name, amount_decimal) entries for
    SilverNormalizer to write to fact_pos_payment. Empty tuple when no
    recognized payments found — normalizer handles that as "no children".

    Zero or empty values are skipped: a row where the customer paid 100
    cash will have 现金=100 and every other payment column empty or "0";
    we only emit the non-zero entry.
    """
    out: List[Tuple[str, Decimal]] = []
    for col_name in _PAYMENT_COLUMNS:
        raw = row_data.get(col_name)
        amount = _parse_decimal(raw)
        if amount is None or amount == 0:
            continue
        out.append((col_name, amount))
    return tuple(out)


def _extract_discounts(
    row_data: Dict[str, Any],
) -> Tuple[Tuple[str, Decimal, int], ...]:
    """Scan row_data for discount-like columns with non-zero amount.

    Returns (discount_name, amount_decimal, quantity=1) tuples. quantity
    defaults to 1 because qhj doesn't track how many of one voucher type
    were applied; Silver's dim_discount schema allows refining later.

    Heuristic: column matches _is_discount_column (contains 代N / 券 / 优惠,
    not suffixed with "(不计)"). Payment columns are explicitly excluded
    so they don't double-count (e.g. 点评买单 pays via 点评 platform —
    that's a payment channel, not a discount).
    """
    out: List[Tuple[str, Decimal, int]] = []
    for col_name, raw in row_data.items():
        if col_name in _PAYMENT_COLUMNS:
            continue
        if not _is_discount_column(col_name):
            continue
        amount = _parse_decimal(raw)
        if amount is None or amount == 0:
            continue
        out.append((col_name, amount, 1))
    return tuple(out)


def _build_canonical_row(
    row_data: Dict[str, Any],
    field_mappings: Dict[str, str],
    factory_id: str,
    source_type: str,
    upload_id: int,
    unknown_out: List[str],
) -> Optional[CanonicalRow]:
    """Convert one legacy row_data dict + field_mappings → CanonicalRow.

    Returns None if a required canonical field (store_name / source_bill_no
    / date) is missing or malformed.
    """
    attrs: Dict[str, Any] = {}

    for original_col, value in row_data.items():
        canonical_name = field_mappings.get(original_col)
        attr = _lookup_attr(original_col, canonical_name)
        if attr is None:
            # Known EAV columns (payments + discounts) are handled separately —
            # don't flag them as unknown so the admin review list stays useful.
            if original_col in _PAYMENT_COLUMNS:
                continue
            if _is_discount_column(original_col):
                continue
            # Track both paths' failures so admin can see what's dropped.
            unmapped_key = canonical_name or f"<raw>{original_col}"
            if unmapped_key not in unknown_out:
                unknown_out.append(unmapped_key)
            continue
        # Defensive non-empty guard: when two source columns alias to the
        # same canonical attr (e.g. historically 账单号 + 外部单号 → source_bill_no),
        # dict iteration order would let a later EMPTY column clobber an
        # earlier real value. Never overwrite an already-set, non-empty attr
        # with an empty/None value. This makes source_bill_no robust against
        # alias ordering regardless of which column comes first in row_data.
        if attr in attrs:
            existing = attrs[attr]
            existing_nonempty = existing is not None and str(existing).strip() != ""
            new_empty = value is None or str(value).strip() == ""
            if existing_nonempty and new_empty:
                continue
        attrs[attr] = value

    # Required field typing + validation
    store_name = attrs.get("store_name")
    bill_no = attrs.get("source_bill_no")
    parsed_date = _parse_date(attrs.get("date"))

    if not store_name or not bill_no or parsed_date is None:
        return None

    # EAV extraction for sub-facts.
    payments = _extract_payments(row_data)
    discounts = _extract_discounts(row_data)

    return CanonicalRow(
        factory_id=factory_id,
        source_type=source_type,
        store_name=str(store_name).strip(),
        source_bill_no=str(bill_no).strip(),
        date=parsed_date,
        staff_name=(str(attrs["staff_name"]).strip() if attrs.get("staff_name") else None),
        upload_id=upload_id,
        table_no=(str(attrs["table_no"]) if attrs.get("table_no") else None),
        order_type=(str(attrs["order_type"]) if attrs.get("order_type") else None),
        channel_origin=(str(attrs["channel_origin"]) if attrs.get("channel_origin") else None),
        # meal_period (Task C4) — .strip() per spec §5.5 writer-side normalization
        # + 班次 shift-label → canonical bucket mapping (中班→午市, 晚班→晚市) so
        # rows pass the chk_meal_period CHECK instead of being lost.
        meal_period=_normalize_meal_period(attrs.get("meal_period")),
        customer_count=_parse_int(attrs.get("customer_count")),
        gross_amount=_parse_decimal(attrs.get("gross_amount")),
        discount_amount=_parse_decimal(attrs.get("discount_amount")),
        net_amount=_parse_decimal(attrs.get("net_amount")),
        actual_receive=_parse_decimal(attrs.get("actual_receive")),
        combo_string=(str(attrs["combo_string"]) if attrs.get("combo_string") else None),
        payments=payments,
        discounts=discounts,
    )


async def backfill_upload(
    pool: asyncpg.Pool,
    upload_id: int,
    factory_id: str,
    *,
    source_type: str = "excel",
    limit: Optional[int] = None,
) -> BackfillStats:
    """Backfill one upload's rows through the Silver+Gold pipeline.

    `limit` caps rows processed — useful for dry-run / testing on a big
    historical upload before committing to the full run.
    """
    stats = BackfillStats(upload_id=upload_id, factory_id=factory_id)

    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT field_mappings FROM smart_bi_pg_excel_uploads WHERE id = $1",
            upload_id,
        )
        if row is None:
            raise ValueError(f"upload {upload_id} not found in smart_bi_pg_excel_uploads")
        field_mappings = _normalize_field_mappings(row["field_mappings"])

    # Stream rows — don't pull all ~200K into memory. Use server-side cursor.
    rows_to_send: List[CanonicalRow] = []
    unknown: List[str] = []

    async with pool.acquire() as conn:
        query = "SELECT row_data FROM smart_bi_dynamic_data WHERE upload_id = $1"
        if limit is not None:
            query += f" LIMIT {int(limit)}"
        async for pg_row in _stream_rows(conn, query, upload_id):
            stats.rows_read += 1
            row_data = pg_row["row_data"]
            if isinstance(row_data, str):
                import json
                row_data = json.loads(row_data)
            canonical = _build_canonical_row(
                row_data, field_mappings, factory_id, source_type, upload_id, unknown,
            )
            if canonical is None:
                stats.rows_skipped_missing_required += 1
                continue
            rows_to_send.append(canonical)

    stats.rows_queued = len(rows_to_send)
    stats.unknown_canonical_names = unknown

    if rows_to_send:
        stats.pipeline = await ingest_and_materialize(pool, factory_id, rows_to_send)

    logger.info(
        "backfill upload=%d factory=%s read=%d skipped=%d queued=%d pipeline=%s",
        upload_id, factory_id,
        stats.rows_read, stats.rows_skipped_missing_required, stats.rows_queued,
        "yes" if stats.pipeline else "no-rows",
    )
    return stats


async def _stream_rows(
    conn: asyncpg.Connection, query: str, *args
) -> Iterable:
    """Yield rows one at a time from a query. Uses asyncpg cursor under
    a transaction (asyncpg requires the cursor to live inside tx)."""
    async with conn.transaction():
        async for row in conn.cursor(query, *args):
            yield row


async def _main_cli():
    import sys
    parser = argparse.ArgumentParser(description="Backfill one upload to Silver+Gold")
    parser.add_argument("--upload-id", type=int, required=True)
    parser.add_argument("--factory", required=True, help="factory_id for tenant_ctx")
    parser.add_argument("--limit", type=int, default=None, help="cap rows processed")
    parser.add_argument("--source-type", default="excel")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    from smartbi.config import get_settings
    from smartbi.tenant_ctx import set_factory_id, set_pg_connection_tenant

    settings = get_settings()
    if not settings.postgres_url:
        print("No Postgres configured", file=sys.stderr)
        return 1

    pool = await asyncpg.create_pool(
        settings.postgres_url, min_size=1, max_size=3,
        setup=set_pg_connection_tenant,
    )
    set_factory_id(args.factory)
    try:
        stats = await backfill_upload(
            pool, upload_id=args.upload_id, factory_id=args.factory,
            source_type=args.source_type, limit=args.limit,
        )
    finally:
        await pool.close()

    print(f"upload_id:       {stats.upload_id}")
    print(f"factory_id:      {stats.factory_id}")
    print(f"rows_read:       {stats.rows_read}")
    print(f"rows_skipped:    {stats.rows_skipped_missing_required}")
    print(f"rows_queued:     {stats.rows_queued}")
    if stats.unknown_canonical_names:
        print(f"unknown fields:  {stats.unknown_canonical_names}")
    if stats.pipeline:
        n = stats.pipeline.normalize
        print(f"silver written:  txn={n.transactions_written} items={n.items_written} "
              f"dup={n.duplicates_skipped}")
        print(f"gold upserts:    {stats.pipeline.trigger.total_rows_upserted}")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(asyncio.run(_main_cli()))
