"""One-off ops script — extract + time-shift real 2025 二维火 卡详情一览
(member card) + 卡充值统计 (member recharge) reports into DEMO_REST's
2026-01..07 window.

Background
----------
New restaurant analytics dimension: 会员储值 + 画像 (member stored-value +
demographic profile) — greenfield, mirrors extract_shift_void_2026.py's
sister-script pattern for the 撤单率 dimension. DEMO_REST already carries
real-shifted bill-grain data for 2026-01..07 (see
load_billgrain_demo_rest.py). This script closes the matching member-profile
gap for the SAME window with DERIVED-FROM-REAL data (not fabricated rows),
using the SAME +1 year, same-month/day shift pattern.

⚠️ NOT full RFM: 青花椒 only exports 卡详情 (card snapshot: tier/balance/
gender/birth date) + 卡充值 (recharge transactions) — there is no 卡消费
(per-order consumption tied to a card_no) report in this customer's data.
Recency/frequency cannot be derived. See gold/queries.py member_profile()'s
docstring for the caveat surfaced to the frontend.

Source files (real 青花椒 customer exports, UTF-8 with BOM, 3 preamble rows
before the real header row):

  A. 卡详情一览 — one row per member card (snapshot grain). Real columns
     (verified against the reference export): 卡号/卡类型/领用状态/状态/
     本金/赠送金/余额(元)/会员/剩余积分/等级/性别/手机/生日/押金/售卡人/
     发卡日期/卡所属门店/发卡门店.

     🔒 PII MINIMIZATION AT EXTRACT TIME (not just masking-on-read): this
     script reads 卡号/会员(昵称)/手机 ONLY to skip malformed rows — it
     NEVER writes them to the output JSONL. 生日 is reduced to birth_month
     (1-12) immediately; day and year are discarded here, before the data
     ever reaches a file or a database row. See
     V20261006_01__member_profile_silver.sql's header note.

  B. 卡充值统计 — pre-aggregated by the POS to (store, date, channel) grain
     already (no card_no column at all — no PII to begin with). Real
     columns: 充值门店/日期/充值笔数/本金/赠送金额/合计/其它/操作渠道/
     手续费/实收金额.

This script only EXTRACTS + SHIFTS + WRITES two intermediate JSONL files. It
does NOT touch any database. `load_member_demo_rest.py` (sister script, NOT
run by this session — the organizer runs it against prod) reads both JSONL
files and writes to Postgres directly via DimResolver + raw asyncpg INSERT
into dim_member / fact_member_recharge. See that script's module docstring
for why it does NOT call the Gold materializer.

Usage:
    cd backend/python
    python -m smartbi.scripts.extract_shift_member_2026 \
        --card-detail-zip "C:/path/to/..._卡详情一览（定）.zip" \
        --card-recharge-zip "C:/path/to/..._卡充值统计.zip"

Output (next to this file):
    smartbi/scripts/_demo_rest_member_detail_2026.jsonl
    smartbi/scripts/_demo_rest_member_recharge_2026.jsonl
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
_DEFAULT_CARD_DETAIL_ZIP = (
    r"C:/Users/Steve/my-prototype-logistics/smartbi维度分析/大众点评/"
    r"真实餐饮连锁数据/青花椒25年/青花椒25年/"
    r"20260422102202144_6c34ea20b31_卡详情一览（定）.zip"
)
_DEFAULT_CARD_RECHARGE_ZIP = (
    r"C:/Users/Steve/my-prototype-logistics/smartbi维度分析/大众点评/"
    r"真实餐饮连锁数据/青花椒25年/青花椒25年/"
    r"20260422103653220_690a5bdf0f1_卡充值统计.zip"
)
_OUTPUT_DIR = Path(__file__).resolve().parent
_OUTPUT_DETAIL_PATH = _OUTPUT_DIR / "_demo_rest_member_detail_2026.jsonl"
_OUTPUT_RECHARGE_PATH = _OUTPUT_DIR / "_demo_rest_member_recharge_2026.jsonl"

# 二维火 export prepends metadata rows before the real header; the real
# header row's first cell is one of these markers (mirrors
# smartbi/ingestion/pos_ingest.py:_detect_header_row's marker set).
_DETAIL_HEADER_MARKERS = ("卡号",)
_RECHARGE_HEADER_MARKERS = ("充值门店",)

# Target source months (2025) -> shifted target months (2026), +1 year,
# same month/day. Matches DEMO_REST's existing bill-grain window
# (2026-01..07, see extract_shift_pos_2026_q.py / extract_shift_void_2026.py).
_TARGET_SOURCE_MONTHS = (
    "2025-01", "2025-02", "2025-03", "2025-04",
    "2025-05", "2025-06", "2025-07",
)

# ── 卡详情一览 column names ──────────────────────────────────────────
_D_COL_TIER = "等级"
_D_COL_GENDER = "性别"
_D_COL_BALANCE = "余额(元)"
_D_COL_BIRTHDAY = "生日"
_D_COL_ISSUE_AT = "发卡日期"
_D_COL_ISSUE_STORE = "发卡门店"
_D_MIN_COLS = 18  # 卡号..发卡门店 (index 0..17)

# ── 卡充值统计 column names ──────────────────────────────────────────
_R_COL_STORE_NAME = "充值门店"
_R_COL_DATE = "日期"
_R_COL_COUNT = "充值笔数"
_R_COL_PRINCIPAL = "本金"
_R_COL_BONUS = "赠送金额"
_R_COL_OTHER = "其它"
_R_COL_CHANNEL = "操作渠道"
_R_COL_FEE = "手续费"
_R_COL_ACTUAL = "实收金额"
_R_MIN_COLS = 10  # 充值门店..实收金额 (index 0..9)


def _detect_header_row(reader: "csv._reader", markers: tuple) -> list:
    """Consume rows from `reader` until the real header row is found
    (first cell matches a known marker). Returns the header row (list of
    column names). Raises ValueError if no marker found in first 30 rows."""
    for i, row in enumerate(reader):
        if not row:
            continue
        first = row[0].strip().strip('"').strip()
        if first in markers:
            return row
        if i > 30:
            break
    raise ValueError(
        f"could not find header row (looked for first cell in {markers} "
        "within first 30 rows) — CSV structure differs from expected 二维火 shape"
    )


def _parse_float(raw: Optional[str]) -> Optional[float]:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    try:
        return float(s.replace(",", ""))
    except ValueError:
        return None


def _parse_date_only(raw: Optional[str]) -> Optional[date]:
    """生日 is a bare date ('1994-01-03'), sometimes blank."""
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    for fmt in ("%Y-%m-%d", "%Y/%m/%d"):
        try:
            return datetime.strptime(s, fmt).date()
        except ValueError:
            continue
    return None


def _parse_datetime(raw: Optional[str]) -> Optional[datetime]:
    """发卡日期 has seconds ('2025-01-01 09:20:44')."""
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None


def _shift_datetime(dt: datetime, years: int = 1) -> datetime:
    """Shift a datetime by `years`, same month/day/time. Neither 2025 nor
    2026 is itself a leap-year-boundary concern for this shift within the
    01-07 target months."""
    return dt.replace(year=dt.year + years)


def _shift_date(d: date, years: int = 1) -> date:
    return d.replace(year=d.year + years)


# ── 卡详情一览 extraction ────────────────────────────────────────────


def _iter_detail_rows(zip_path: Path) -> Iterator[Dict[str, Any]]:
    """Yield one dict per member-card row (raw column name -> raw string
    value) for rows whose 发卡日期 falls in _TARGET_SOURCE_MONTHS. Streams
    the CSV — never loads the whole file into memory at once."""
    with zipfile.ZipFile(zip_path) as z:
        members = z.namelist()
        if len(members) != 1:
            raise ValueError(
                f"expected exactly 1 CSV member in zip, found {len(members)}: {members}"
            )
        with z.open(members[0]) as fh:
            text_stream = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
            reader = csv.reader(text_stream)
            header = _detect_header_row(reader, _DETAIL_HEADER_MARKERS)
            for row in reader:
                if not row or len(row) < _D_MIN_COLS:
                    continue
                row_dict = dict(zip(header, row))
                issue_at_raw = (row_dict.get(_D_COL_ISSUE_AT) or "").strip()
                if issue_at_raw[:7] not in _TARGET_SOURCE_MONTHS:
                    continue
                yield row_dict


def _build_shifted_detail_record(row_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Convert one raw 卡详情 row -> the narrow, PII-minimized intermediate
    schema, with 发卡日期 shifted +1 year and 生日 reduced to birth_month
    (day/year discarded — never written out). Returns None if a required
    field is missing (store_name / issue_at)."""
    store_name = (row_dict.get(_D_COL_ISSUE_STORE) or "").strip()
    issue_dt = _parse_datetime(row_dict.get(_D_COL_ISSUE_AT))

    if not store_name or issue_dt is None:
        return None

    shifted_issue = _shift_datetime(issue_dt, years=1)

    birthday = _parse_date_only(row_dict.get(_D_COL_BIRTHDAY))
    birth_month = birthday.month if birthday is not None else None

    return {
        "store_name": store_name,
        "tier": (row_dict.get(_D_COL_TIER) or "").strip() or None,
        "gender": (row_dict.get(_D_COL_GENDER) or "").strip() or None,
        "birth_month": birth_month,  # 1-12 or None — day/year discarded here, PERMANENTLY
        "balance": _parse_float(row_dict.get(_D_COL_BALANCE)),
        "issue_at": shifted_issue.isoformat(),
    }


def _run_detail(zip_path: Path, output_path: Path) -> None:
    tier_counts: Counter = Counter()
    gender_counts: Counter = Counter()
    store_counts: Counter = Counter()
    birth_month_present = 0
    skipped_missing_required = 0
    total_written = 0
    n_seen = 0

    with output_path.open("w", encoding="utf-8") as out_f:
        for row_dict in _iter_detail_rows(zip_path):
            n_seen += 1
            record = _build_shifted_detail_record(row_dict)
            if record is None:
                skipped_missing_required += 1
                continue
            tier_counts[record["tier"] or "未知"] += 1
            gender_counts[record["gender"] or "未知"] += 1
            store_counts[record["store_name"]] += 1
            if record["birth_month"] is not None:
                birth_month_present += 1
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            total_written += 1

    print("=== 卡详情一览 (member card detail) ===")
    print(f"source zip:            {zip_path}")
    print(f"output jsonl:          {output_path}")
    print(f"rows scanned (target months, pre-filter): {n_seen}")
    print(f"rows written:          {total_written}")
    print(f"rows skipped (missing required field):     {skipped_missing_required}")
    print()
    pct_birth = (birth_month_present / total_written * 100) if total_written else 0.0
    print(f"birth_month present:   {birth_month_present}/{total_written} ({pct_birth:.1f}%)")
    print()
    print("tier distribution (counts, NOT written to output as raw member rows anywhere PII):")
    for tier, cnt in tier_counts.most_common(10):
        print(f"  {tier}: {cnt}")
    print()
    print("gender distribution:")
    for gender, cnt in gender_counts.most_common():
        print(f"  {gender}: {cnt}")
    print()
    print(f"distinct issue stores: {len(store_counts)}")
    for store, cnt in store_counts.most_common(10):
        print(f"  {store}: {cnt}")
    print()
    print("sample rows (aggregate-safe — no card_no/name/phone/full-birthdate ever extracted):")
    with output_path.open("r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= 3:
                break
            print(f"  {line.strip()}")
    print()


# ── 卡充值统计 extraction ────────────────────────────────────────────


def _iter_recharge_rows(zip_path: Path) -> Iterator[Dict[str, Any]]:
    """Yield one dict per recharge row (already pre-aggregated by the POS to
    store+date+channel grain — no card_no column, no PII). Filters on 日期
    falling in _TARGET_SOURCE_MONTHS."""
    with zipfile.ZipFile(zip_path) as z:
        members = z.namelist()
        if len(members) != 1:
            raise ValueError(
                f"expected exactly 1 CSV member in zip, found {len(members)}: {members}"
            )
        with z.open(members[0]) as fh:
            text_stream = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
            reader = csv.reader(text_stream)
            header = _detect_header_row(reader, _RECHARGE_HEADER_MARKERS)
            for row in reader:
                if not row or len(row) < _R_MIN_COLS:
                    continue
                row_dict = dict(zip(header, row))
                date_raw = (row_dict.get(_R_COL_DATE) or "").strip()
                if date_raw[:7] not in _TARGET_SOURCE_MONTHS:
                    continue
                yield row_dict


def _build_shifted_recharge_record(row_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Convert one raw 卡充值 row -> the narrow intermediate schema, with
    日期 shifted +1 year. Returns None if a required field is missing
    (store_name / date)."""
    store_name = (row_dict.get(_R_COL_STORE_NAME) or "").strip()
    d = _parse_date_only(row_dict.get(_R_COL_DATE))

    if not store_name or d is None:
        return None

    shifted_date = _shift_date(d, years=1)

    return {
        "store_name": store_name,
        "date": shifted_date.isoformat(),
        "channel": (row_dict.get(_R_COL_CHANNEL) or "").strip() or None,
        "recharge_count": _parse_float(row_dict.get(_R_COL_COUNT)),
        "principal": _parse_float(row_dict.get(_R_COL_PRINCIPAL)),
        "bonus": _parse_float(row_dict.get(_R_COL_BONUS)),
        "other_amount": _parse_float(row_dict.get(_R_COL_OTHER)),
        "fee": _parse_float(row_dict.get(_R_COL_FEE)),
        "actual_received": _parse_float(row_dict.get(_R_COL_ACTUAL)),
    }


def _run_recharge(zip_path: Path, output_path: Path) -> None:
    month_counts: Counter = Counter()
    store_counts: Counter = Counter()
    channel_counts: Counter = Counter()
    skipped_missing_required = 0
    total_written = 0
    n_seen = 0
    total_principal = 0.0
    total_bonus = 0.0

    with output_path.open("w", encoding="utf-8") as out_f:
        for row_dict in _iter_recharge_rows(zip_path):
            n_seen += 1
            record = _build_shifted_recharge_record(row_dict)
            if record is None:
                skipped_missing_required += 1
                continue
            month_counts[record["date"][:7]] += 1
            store_counts[record["store_name"]] += 1
            channel_counts[record["channel"] or "未标注"] += 1
            total_principal += record["principal"] or 0.0
            total_bonus += record["bonus"] or 0.0
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            total_written += 1

    print("=== 卡充值统计 (member recharge) ===")
    print(f"source zip:            {zip_path}")
    print(f"output jsonl:          {output_path}")
    print(f"rows scanned (target months, pre-filter): {n_seen}")
    print(f"rows written:          {total_written}")
    print(f"rows skipped (missing required field):     {skipped_missing_required}")
    print()
    print("per-month counts (shifted, 2026, by date):")
    for k in sorted(month_counts):
        print(f"  {k}: {month_counts[k]}")
    print()
    print(f"distinct stores:       {len(store_counts)}")
    for store, cnt in store_counts.most_common(10):
        print(f"  {store}: {cnt}")
    print()
    print("channel distribution:")
    for channel, cnt in channel_counts.most_common(10):
        print(f"  {channel}: {cnt}")
    print()
    print(f"total principal (本金):   {total_principal:,.2f}")
    print(f"total bonus (赠送金额):   {total_bonus:,.2f}")
    print()
    print("sample rows:")
    with output_path.open("r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= 3:
                break
            print(f"  {line.strip()}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--card-detail-zip", default=_DEFAULT_CARD_DETAIL_ZIP, help="path to the 二维火 卡详情一览 export zip")
    ap.add_argument("--card-recharge-zip", default=_DEFAULT_CARD_RECHARGE_ZIP, help="path to the 二维火 卡充值统计 export zip")
    ap.add_argument("--output-detail", default=str(_OUTPUT_DETAIL_PATH), help="output JSONL path for member card detail")
    ap.add_argument("--output-recharge", default=str(_OUTPUT_RECHARGE_PATH), help="output JSONL path for member recharge")
    args = ap.parse_args()

    detail_zip = Path(args.card_detail_zip)
    recharge_zip = Path(args.card_recharge_zip)
    if not detail_zip.exists():
        raise SystemExit(f"zip not found: {detail_zip}")
    if not recharge_zip.exists():
        raise SystemExit(f"zip not found: {recharge_zip}")

    _run_detail(detail_zip, Path(args.output_detail))
    print()
    _run_recharge(recharge_zip, Path(args.output_recharge))


if __name__ == "__main__":
    main()
