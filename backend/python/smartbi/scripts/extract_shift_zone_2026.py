"""One-off ops script — extract + time-shift real 2025 二维火 区域销售报表
(in-store dining-zone sales report) rows into DEMO_REST's 2026-01..07 window.

Background
----------
New restaurant analytics dimension: 区域坪效 (in-store dining-zone
revenue/efficiency) — greenfield, zero prior zone-sales handling in this
codebase (RegionAnalysisService / RegionSummaryWriter are geographic-region
analytics, a DIFFERENT concept — see fact_zone_sales migration's module
comment). DEMO_REST already carries real-shifted bill-grain data for
2026-01..07 (extract_shift_pos_2026_q.py's sister loader,
load_billgrain_demo_rest.py) and 撤单 void-event data for the same window
(extract_shift_void_2026.py). This script closes the matching zone-sales gap
for the SAME window with DERIVED-FROM-REAL data (not fabricated rows), using
the SAME +1 year, same-month/day shift pattern.

Source file: a real customer's (青花椒) 二维火 "区域销售报表" export — UTF-8
with BOM, 3 preamble rows (title / 门店名称 filter / 查询条件) before the
real header row (first cell == 日期... wait, first cell is actually 门店名称
in the filter rows but the REAL header row's first cell is 日期 — see
_HEADER_MARKERS below, which checks for known marker tokens at position 0 of
candidate header rows; 门店名称 appears in the filter/preamble rows too, so
detection scans for the header row whose first cell is a KNOWN HEADER token,
which for this report is 日期). Columns verified against the real file
(~200,002 data rows across 2025-01-01..2025-12-31, plus a trailing 合计
grand-total footer row with blank 门店名称/区域名称 — skipped, see
_build_shifted_record): 日期/门店名称/区域名称/商品编码/商品分类/商品名称/
单价/数量/折前金额/折后金额.

⚠️ NOTE: the export carries a "查询结果已经超过最大导出条数..." truncation
warning in the source header metadata, but per-month row counts across the
full 2025-01..12 span are roughly even (~16-18k/month) — no sign the target
2025-01..07 window specifically was truncated (see this script's printed
summary for the actual counts observed).

Column data-quality notes (from the real export, informs the loader's
idempotency design — see V20261006_01__fact_zone_sales.sql):
  - 区域名称 (zone_name): blank in ~1/200,002 rows. 12 distinct real values
    seen: 大厅/小桌/中桌/大桌/无桌位(饿了么外卖)/无桌位(美团外卖)/
    无桌位(京东外卖)/无桌位(其他)/外卖/京东/饿了么 — several of these are
    DELIVERY-CHANNEL labels, not physical in-store zones (see caveat in
    smartbi/gold/queries.py:zone_efficiency).
  - 商品编码 (product code): frequently blank — informational only, not
    stored (fact_zone_sales has no product_code column).
  - 数量 (quantity): almost always integer-valued but NOT always (a
    handful of non-integer rows in the reference export, e.g. weighed
    items) — stored as NUMERIC, never rounded.

This script only EXTRACTS + SHIFTS + WRITES an intermediate JSONL. It does
NOT touch any database. `load_zone_demo_rest.py` (sister script, NOT run by
this session — the organizer runs it against prod) reads the JSONL and
writes to Postgres directly via DimResolver + raw asyncpg INSERT into
fact_zone_sales. See that script's module docstring for why it does NOT
call the Gold materializer.

Usage:
    cd backend/python
    python -m smartbi.scripts.extract_shift_zone_2026 \
        --zip-path "C:/path/to/20260422102412536_76d657c5d61_区域销售报表.zip"

Output: smartbi/scripts/_demo_rest_zone_2026.jsonl (next to this file).
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import zipfile
from collections import Counter
from datetime import date, datetime
from pathlib import Path
from typing import Any, Dict, Iterator, Optional

# ─── Constants ──────────────────────────────────────────────────────────
_DEFAULT_ZIP_PATH = (
    r"C:/Users/Steve/my-prototype-logistics/smartbi维度分析/大众点评/"
    r"真实餐饮连锁数据/青花椒25年/青花椒25年/"
    r"20260422102412536_76d657c5d61_区域销售报表.zip"
)
_OUTPUT_PATH = Path(__file__).resolve().parent / "_demo_rest_zone_2026.jsonl"

# 二维火 export prepends metadata rows before the real header; the real
# header row's first cell is one of these markers (mirrors
# smartbi/ingestion/pos_ingest.py:_detect_header_row's marker set, and
# extract_shift_void_2026.py's _HEADER_MARKERS). This report's real header
# starts with 日期 (not 门店名称 — that token appears in the preamble filter
# row instead), so 日期 is included in the marker set.
_HEADER_MARKERS = ("日期", "门店名称", "门店", "店铺", "账单号", "订单号")

# Target source months (2025) -> shifted target months (2026), +1 year,
# same month/day. Matches DEMO_REST's existing bill-grain + void window
# (2026-01..07, see extract_shift_pos_2026_q.py / extract_shift_void_2026.py).
# Filtered on 日期 (the sales date) — the only date column in this report.
_TARGET_SOURCE_MONTHS = (
    "2025-01", "2025-02", "2025-03", "2025-04",
    "2025-05", "2025-06", "2025-07",
)

_COL_DATE = "日期"
_COL_STORE_NAME = "门店名称"
_COL_ZONE_NAME = "区域名称"
_COL_PRODUCT_NAME = "商品名称"
_COL_UNIT_PRICE = "单价"
_COL_QUANTITY = "数量"
_COL_AMOUNT_BEFORE = "折前金额"
_COL_AMOUNT_AFTER = "折后金额"

_MIN_COLS = 8  # 日期..折后金额 (index 0..7 at minimum; real file has 10 named cols)


def _detect_header_row(reader: "csv._reader") -> list:
    """Consume rows from `reader` until the real header row is found
    (first cell matches a known marker). Returns the header row (list of
    column names). Raises ValueError if no marker found in first 30 rows."""
    for i, row in enumerate(reader):
        if not row:
            continue
        first = row[0].strip().strip('"').strip()
        if first in _HEADER_MARKERS:
            return row
        if i > 30:
            break
    raise ValueError(
        f"could not find header row (looked for first cell in {_HEADER_MARKERS} "
        "within first 30 rows) — CSV structure differs from expected 二维火 shape"
    )


def _parse_date(raw: Optional[str]) -> Optional[date]:
    """日期 is a plain date (no time component), e.g. '2025-02-03'. The
    export's trailing 合计 (grand-total) footer row has a non-date value
    here — returning None for it lets the caller skip it."""
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError:
        return None


def _parse_decimal_str(raw: Optional[str]) -> Optional[str]:
    """Return a normalized numeric string (or None) — kept as str in the
    JSONL intermediate to avoid float precision drift; the loader converts
    to Decimal."""
    if raw is None:
        return None
    s = raw.strip().replace(",", "")
    if not s:
        return None
    try:
        float(s)  # validate parseable
    except ValueError:
        return None
    return s


def _shift_date(d: date, years: int = 1) -> date:
    """Shift a date by `years`, same month/day. None of the target months
    (01-07) contain a Feb 29 boundary issue relevant to this shift (2025 ->
    2026, neither is itself Feb 29 source data since 2025 is not a leap
    year)."""
    return d.replace(year=d.year + years)


def _iter_target_rows(zip_path: Path) -> Iterator[Dict[str, Any]]:
    """Yield one dict per sales line (raw column name -> raw string value)
    for rows whose 日期 falls in _TARGET_SOURCE_MONTHS. Streams the CSV —
    never loads the whole file into memory at once (the reference export is
    ~25MB / 200k rows)."""
    with zipfile.ZipFile(zip_path) as z:
        members = z.namelist()
        if len(members) != 1:
            raise ValueError(
                f"expected exactly 1 CSV member in zip, found {len(members)}: {members}"
            )
        with z.open(members[0]) as fh:
            text_stream = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
            reader = csv.reader(text_stream)
            header = _detect_header_row(reader)
            for row in reader:
                if not row or len(row) < _MIN_COLS:
                    continue
                row_dict = dict(zip(header, row))
                date_raw = (row_dict.get(_COL_DATE) or "").strip()
                if date_raw[:7] not in _TARGET_SOURCE_MONTHS:
                    continue
                yield row_dict


def _build_shifted_record(row_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Convert one raw row dict -> the narrow intermediate schema, with 日期
    shifted +1 year. Returns None if a required field is missing
    (store_name / sale_date) — this is what filters out the trailing 合计
    footer row (blank store_name, unparseable date)."""
    store_name = (row_dict.get(_COL_STORE_NAME) or "").strip()
    zone_name = (row_dict.get(_COL_ZONE_NAME) or "").strip()
    product_name = (row_dict.get(_COL_PRODUCT_NAME) or "").strip()

    sale_date = _parse_date(row_dict.get(_COL_DATE))

    if not store_name or sale_date is None:
        return None

    shifted_date = _shift_date(sale_date, years=1)

    return {
        "store_name": store_name,
        "zone_name": zone_name or None,  # '' -> None; loader maps None -> '未分区' sentinel
        "product_name": product_name or None,
        "unit_price": _parse_decimal_str(row_dict.get(_COL_UNIT_PRICE)),
        "quantity": _parse_decimal_str(row_dict.get(_COL_QUANTITY)),
        "amount_before_discount": _parse_decimal_str(row_dict.get(_COL_AMOUNT_BEFORE)),
        "amount_after_discount": _parse_decimal_str(row_dict.get(_COL_AMOUNT_AFTER)),
        "date": shifted_date.isoformat(),
    }


def run(zip_path: Path, output_path: Path) -> None:
    month_counts: Counter = Counter()
    store_counts: Counter = Counter()
    zone_counts: Counter = Counter()
    skipped_missing_required = 0
    total_written = 0
    n_seen = 0

    with output_path.open("w", encoding="utf-8") as out_f:
        for row_dict in _iter_target_rows(zip_path):
            n_seen += 1
            record = _build_shifted_record(row_dict)
            if record is None:
                skipped_missing_required += 1
                continue
            month_counts[record["date"][:7]] += 1
            store_counts[record["store_name"]] += 1
            zone_counts[record["zone_name"] or "(blank)"] += 1
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            total_written += 1

    # ── Summary ─────────────────────────────────────────────────────
    print(f"source zip:            {zip_path}")
    print(f"output jsonl:          {output_path}")
    print(f"rows scanned (target months, pre-filter): {n_seen}")
    print(f"rows written:          {total_written}")
    print(f"rows skipped (missing required field, incl. 合计 footer): {skipped_missing_required}")
    print()
    print("per-month counts (shifted, 2026, by date):")
    for k in sorted(month_counts):
        print(f"  {k}: {month_counts[k]}")
    print()
    print(f"distinct stores:       {len(store_counts)}")
    for store, cnt in store_counts.most_common():
        print(f"  {store}: {cnt}")
    print()
    print(f"distinct zones:        {len(zone_counts)}")
    for zone, cnt in zone_counts.most_common():
        print(f"  {zone!r}: {cnt}")

    print()
    print("sample rows:")
    with output_path.open("r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= 3:
                break
            print(f"  {line.strip()}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--zip-path", default=_DEFAULT_ZIP_PATH, help="path to the 二维火 区域销售报表 export zip")
    ap.add_argument("--output", default=str(_OUTPUT_PATH), help="output JSONL path")
    args = ap.parse_args()

    zip_path = Path(args.zip_path)
    if not zip_path.exists():
        raise SystemExit(f"zip not found: {zip_path}")

    run(zip_path, Path(args.output))


if __name__ == "__main__":
    main()
