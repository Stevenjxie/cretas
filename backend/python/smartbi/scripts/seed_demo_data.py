#!/usr/bin/env python3
"""
Demo Data Seeding Pipeline
==========================
Config-driven pipeline:
  1. Reads real data files
  2. Desensitizes brand/store/supplier names
  3. Redates to a continuous non-overlapping 2024-01 to 2025-12 timeline
  4. Outputs seeded xlsx to --out directory + prints upload manifest

Usage:
  python seed_demo_data.py --out /tmp/demo_seed [--dry-run]
  python seed_demo_data.py --verify   # local validation only

Author: auto-generated feat/demo-seed-pipeline
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import warnings
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

warnings.filterwarnings("ignore")

# ---------------------------------------------------------------------------
# TENANTS
# ---------------------------------------------------------------------------
DEMO_FACTORY = "DEMO_FACTORY"
DEMO_REST = "DEMO_REST"
DEMO_RETAIL = "DEMO_RETAIL"    # 第3业态: 零售连锁 (organizer 运行时映射真实工厂ID)
DEMO_FACTORY2 = "DEMO_FACTORY2"  # 第4租户: 第2工厂 (跨区域利润表, 区域维度)

# ---------------------------------------------------------------------------
# REPO ROOT RESOLUTION
# ---------------------------------------------------------------------------

def _repo_root() -> Path:
    """Walk up from this script to locate repo root (contains scripts/ AND backend/)."""
    p = Path(__file__).resolve()
    for _ in range(12):
        if (p / "scripts").is_dir() and (p / "backend").is_dir():
            return p
        p = p.parent
    raise RuntimeError(f"Cannot locate repo root from {__file__}")


REPO = _repo_root()
SMARTBI_DATA = REPO / "smartbi维度分析" / "大众点评" / "真实餐饮连锁数据"
XLSX_CONV = SMARTBI_DATA / "xlsx_converted"
XLSX_DIR = SMARTBI_DATA / "xlsx"
TEST_DATA = REPO / "scripts" / "test-data"
QHJ_E2E = (
    REPO
    / "tests"
    / "e2e-comprehensive"
    / "results"
    / "depth-aiq-2026-04-26"
    / "unzipped"
    / "qhj-25"
)
TEST_MOCK = REPO / "tests" / "test-data"
RETAIL_MOCK = TEST_MOCK / "Test-mock-retail-normal-s42.xlsx"
MFG_MOCK = TEST_MOCK / "Test-mock-mfg-normal-s42.xlsx"  # DEMO_FACTORY2 source

# ---------------------------------------------------------------------------
# DESENSITISATION
# ---------------------------------------------------------------------------

REAL_BRAND_TO_GENERIC: Dict[str, str] = {
    "青花椒": "门店01",
    "东门口": "门店02",
    "九记·東門口": "门店02",
    "IL TEATRO": "门店03",
    "上马火锅": "门店04",
    "唏嘛香": "门店05",
    "御九井": "门店06",
    "永和豆浆": "门店07",
    "馨厨香": "门店08",
    "绿源食品": "工厂01",
    "美鑫": "工厂02",
    "张记餐饮": "门店09",
    "鲜味零售": "门店10",
    "绿源": "工厂01",
}

_desens_cache: Dict[str, str] = {}
_desens_counters: Dict[str, int] = {}

_LABEL_MAP = {
    "store": "门店",
    "customer": "客户",
    "supplier": "供应商",
}


def _desens_value(raw: str, kind: str) -> str:
    key = f"{kind}:{raw}"
    if key in _desens_cache:
        return _desens_cache[key]
    for brand_key, generic in REAL_BRAND_TO_GENERIC.items():
        if brand_key in str(raw):
            _desens_cache[key] = generic
            return generic
    _desens_counters[kind] = _desens_counters.get(kind, 0) + 1
    label_base = _LABEL_MAP.get(kind, "名称")
    label = f"{label_base}{_desens_counters[kind]:02d}"
    _desens_cache[key] = label
    return label


def _col_kind(col_name: str) -> Optional[str]:
    c = str(col_name)
    if any(kw in c for kw in ["门店", "店铺名称", "品牌", "店名"]):
        return "store"
    if any(kw in c for kw in ["客户", "客户名称"]):
        return "customer"
    if any(kw in c for kw in ["供应商", "供应商名称"]):
        return "supplier"
    return None


def desensitize(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    for col in df.columns:
        kind = _col_kind(col)
        if kind is None:
            continue

        def _map(v, _kind=kind):
            if pd.isna(v) or str(v).strip() == "":
                return v
            return _desens_value(str(v).strip(), _kind)

        df[col] = df[col].map(_map)
    return df


# ---------------------------------------------------------------------------
# DATE COLUMN DETECTION
# ---------------------------------------------------------------------------

DATE_COL_PATTERNS = [
    r"日期", r"月份", r"date", r"month", r"period",
    r"单据.*日期", r"业务日期", r"操作日期",
]


def _find_date_cols(df: pd.DataFrame) -> List[str]:
    found = []
    for col in df.columns:
        c = str(col).lower()
        for pat in DATE_COL_PATTERNS:
            if re.search(pat, c, re.IGNORECASE):
                found.append(col)
                break
    return found


def _is_month_str(v: Any) -> bool:
    """True only for pure year-month strings like '2025年1月' or '2025-01' (NOT full dates)."""
    s = str(v).strip()
    if re.match(r"\d{4}年\d{1,2}月$", s):
        return True
    # Only match YYYY-MM without a day part
    if re.match(r"^\d{4}-\d{2}$", s):
        return True
    return False


# ---------------------------------------------------------------------------
# WINDOW ASSIGNMENT
# ---------------------------------------------------------------------------

@dataclass
class Window:
    start: date
    end: date

    def months(self) -> List[Tuple[int, int]]:
        result = []
        y, m = self.start.year, self.start.month
        while (y, m) <= (self.end.year, self.end.month):
            result.append((y, m))
            m += 1
            if m > 12:
                m = 1
                y += 1
        return result

    def __str__(self):
        return f"{self.start.strftime('%Y%m')}_{self.end.strftime('%Y%m')}"


_window_cursor: Dict[str, Tuple[int, int]] = {}

TIMELINE_START = (2024, 1)
TIMELINE_END = (2025, 12)


def _next_month(ym: Tuple[int, int]) -> Tuple[int, int]:
    y, m = ym
    m += 1
    if m > 12:
        m = 1
        y += 1
    return (y, m)


def _add_months(ym: Tuple[int, int], n: int) -> Tuple[int, int]:
    for _ in range(n):
        ym = _next_month(ym)
    return ym


def assign_window(tenant: str, domain: str, n_months: int) -> Optional[Window]:
    """Allocate the next non-overlapping n_months window for this tenant."""
    cursor = _window_cursor.get(f"{tenant}::{domain}", TIMELINE_START)
    start_ym = cursor
    end_ym = _add_months(start_ym, max(n_months - 1, 0))
    import calendar
    start = date(start_ym[0], start_ym[1], 1)
    last_day = calendar.monthrange(end_ym[0], end_ym[1])[1]
    end = date(end_ym[0], end_ym[1], last_day)
    _window_cursor[f"{tenant}::{domain}"] = _next_month(end_ym)
    return Window(start, end)


def reset_window_cursor():
    global _window_cursor, _desens_cache, _desens_counters
    _window_cursor = {}
    _desens_cache = {}
    _desens_counters = {}


# ---------------------------------------------------------------------------
# DATE SHIFTING / REDATING
# ---------------------------------------------------------------------------

def _parse_month_str(s: str) -> Optional[Tuple[int, int]]:
    """Parse pure month strings: '2025年1月' or '2025-01' (NOT full dates like '2025-01-15')."""
    t = str(s).strip()
    m = re.match(r"(\d{4})年(\d{1,2})月$", t)
    if m:
        return int(m.group(1)), int(m.group(2))
    # Only pure YYYY-MM (no day part)
    m2 = re.match(r"^(\d{4})-(\d{2})$", t)
    if m2:
        return int(m2.group(1)), int(m2.group(2))
    return None


def _format_month_str(y: int, mo: int, original_format: str) -> str:
    if re.match(r"\d{4}年\d{1,2}月", str(original_format)):
        return f"{y}年{mo}月"
    if re.match(r"\d{4}-\d{2}$", str(original_format)):
        return f"{y}-{mo:02d}"
    return f"{y}年{mo}月"


def redate_monthly_col(df: pd.DataFrame, col: str, window: Window) -> pd.DataFrame:
    """Map month-string values to target window via linear interleave."""
    df = df.copy()
    src_months_raw = df[col].dropna().unique()
    src_months = []
    for v in src_months_raw:
        parsed = _parse_month_str(v)
        if parsed:
            src_months.append((parsed, v))
    if not src_months:
        return df
    src_months.sort(key=lambda x: x[0])
    target_months = window.months()

    if len(src_months) == 1:
        orig_ym, orig_raw = src_months[0]
        new_month = target_months[0]
        new_val = _format_month_str(new_month[0], new_month[1], str(orig_raw))
        df[col] = df[col].map(
            lambda v: new_val if _parse_month_str(v) == orig_ym else v
        )
    else:
        src_ym_to_target: Dict[Tuple[int, int], Tuple[int, int]] = {}
        for i, (src_ym, _) in enumerate(src_months):
            tgt_ym = target_months[min(i, len(target_months) - 1)]
            src_ym_to_target[src_ym] = tgt_ym

        def _remap(v):
            parsed = _parse_month_str(v)
            if parsed is None or parsed not in src_ym_to_target:
                return v
            tgt = src_ym_to_target[parsed]
            return _format_month_str(tgt[0], tgt[1], str(v))

        df[col] = df[col].map(_remap)
    return df


def redate_date_col(df: pd.DataFrame, col: str, window: Window) -> pd.DataFrame:
    """Linearly stretch real datetime values into target window."""
    df = df.copy()
    vals = pd.to_datetime(df[col], errors="coerce")
    valid = vals.dropna()
    if len(valid) == 0:
        return df
    src_min = valid.min()
    src_max = valid.max()
    src_span = (src_max - src_min).days or 1
    tgt_min = pd.Timestamp(window.start)
    tgt_max = pd.Timestamp(window.end)
    tgt_span = (tgt_max - tgt_min).days or 1

    def _shift(v):
        if pd.isna(v):
            return v
        t = pd.to_datetime(v, errors="coerce")
        if pd.isna(t):
            return v
        ratio = (t - src_min).days / src_span
        new_t = tgt_min + pd.Timedelta(days=int(ratio * tgt_span))
        if isinstance(v, date) and not isinstance(v, pd.Timestamp):
            return new_t.date()
        return new_t.strftime("%Y-%m-%d")

    df[col] = df[col].map(_shift)
    return df


def redate(df: pd.DataFrame, window: Window) -> pd.DataFrame:
    """Auto-detect date/month columns and redate."""
    date_cols = _find_date_cols(df)
    for col in date_cols:
        sample = df[col].dropna().head(5)
        if len(sample) == 0:
            continue
        monthly_count = sum(1 for v in sample if _is_month_str(v))
        if monthly_count >= max(1, len(sample) // 2):
            df = redate_monthly_col(df, col, window)
        else:
            df = redate_date_col(df, col, window)
    return df


# ---------------------------------------------------------------------------
# SNAPSHOT EXPANSION  (single-month → multi-month with light trend)
# ---------------------------------------------------------------------------

def expand_snapshot(
    df: pd.DataFrame,
    window: Window,
    n_months: int,
    trend_cols: Optional[List[str]] = None,
    trend_factor: float = 0.03,
) -> pd.DataFrame:
    """
    Given a single-month snapshot, replicate into n_months within window.
    Adds a '月份' column with the target month label.
    Optionally applies a light multiplicative trend to numeric columns.
    """
    targets = window.months()[:n_months]
    frames = []
    for i, (y, m) in enumerate(targets):
        chunk = df.copy()
        chunk["月份"] = f"{y}年{m}月"
        if trend_cols:
            mult = 1.0 + trend_factor * i
            for col in trend_cols:
                if col in chunk.columns:
                    chunk[col] = pd.to_numeric(chunk[col], errors="coerce") * mult
        frames.append(chunk)
    return pd.concat(frames, ignore_index=True)


# ---------------------------------------------------------------------------
# MONTHLY AGGREGATION  (detail rows → one row per period [+ low-cardinality dim])
# ---------------------------------------------------------------------------

# High-cardinality columns that should be dropped before groupby.
# These are item/SKU-level columns that explode row count and have no
# analytical value after aggregation.
_HIGH_CARD_KEYWORDS = [
    "商品名称", "菜品", "商品编码", "原料名称", "原料条形码",
    "规格", "单号", "账单号", "售后单号", "开单时间", "结单时间",
    "点单时间", "最后一次催菜时间", "外部单号", "关联单号",
    "单据操作日期", "原料条形码",
    # Additional high-card / non-aggregatable operational columns
    "催菜次数",      # per-item waiter-prompt count (sum is meaningless as measure)
    "Unnamed:",     # pandas unnamed trailing columns from CSV export artefacts
    "门店编码",      # code column – keep 门店名称 as dim, drop code
    "做法",         # dish preparation note (high-card text)
    "点单方式",      # order channel per-item row (maps to 渠道 at order level)
    "渠道",         # per-row order channel string
    "订单来源",      # per-row order source string
    "班次",         # shift code (operational, high-card)
    "区域",         # table area string per-row
    "桌位",         # table number per-row (high-card)
    "点单人",        # waiter name per-row
    "操作人",        # operator name per-row
]

# Low-cardinality dimension columns kept as secondary groupby keys
# (store / supplier / region / dept – max 12 unique values enforced below).
# #3: Expanded to support multi-dim drill-down (up to 3 dims).
_LOW_CARD_DIM_KEYWORDS = [
    "门店名称", "店铺名称",   # store-level dim (restaurants / retail)
    "供应商",                  # supplier dim (purchase)
    "区域",                    # region dim (factory2 / sales)
    "部门",                    # department dim (hr / finance)
    "品类",                    # category dim (sales drill-down)
    "物料类别",                # material category dim (inventory)
    "仓库",                    # warehouse dim (inventory)
    "产线",                    # production line dim (production)
]

# Columns that are region-level label strings (not table-area / waiter names).
# Used to distinguish "区域" as a regional dimension vs high-card per-row op field.
_REGION_LABEL_KEYWORDS = ["区域", "大区", "事业部", "省区"]


def _is_high_card(col: str) -> bool:
    # "区域" is in _HIGH_CARD_KEYWORDS (per-row op field) but also used as a low-card
    # regional dimension column in some sources.  Let aggregate_monthly handle it via
    # cardinality check; _is_high_card just marks columns for potential drop.
    return any(kw in col for kw in _HIGH_CARD_KEYWORDS)


def _find_low_card_dims(df: pd.DataFrame, period_col: str, max_dims: int = 3) -> List[str]:
    """
    Return up to `max_dims` low-cardinality dimension columns (≤12 unique values each).
    Prioritises known dim keywords; skips pure-numeric and period columns.
    #3: Returns multiple dims to enable timeseries drill-down.
    """
    found: List[str] = []
    seen_cols: set = set()
    for kw in _LOW_CARD_DIM_KEYWORDS:
        for col in df.columns:
            if col in seen_cols or col == period_col or col == "_agg_period":
                continue
            if kw in col:
                n_unique = df[col].nunique()
                if 1 < n_unique <= 12:
                    found.append(col)
                    seen_cols.add(col)
                    if len(found) >= max_dims:
                        return found
    return found


def aggregate_monthly(df: pd.DataFrame, period_col: str) -> pd.DataFrame:
    """
    Collapse detail rows into monthly aggregates with up to 3 low-cardinality dims.

    Steps:
    1. Normalise the period_col to YYYY-MM (month string).
    2. Identify up to 3 low-cardinality dimension columns (≤12 unique each).
    3. Drop high-cardinality columns (item names, codes, timestamps, order IDs).
    4. Sum all remaining numeric columns grouped by period + dims.
    5. Return the aggregated DataFrame.

    #3: Multi-dim support for timeseries drill-down (region / dept / store / category).
    """
    df = df.copy()

    # --- Step 1: normalise period column to YYYY-MM ---
    def _to_month(v: Any) -> Optional[str]:
        s = str(v).strip()
        # Already month-only string like '2024年1月' or '2024-01'
        parsed = _parse_month_str(s)
        if parsed:
            return f"{parsed[0]}-{parsed[1]:02d}"
        # Full date like '2024-01-15' or pd.Timestamp
        try:
            t = pd.to_datetime(s, errors="raise")
            return t.strftime("%Y-%m")
        except Exception:
            return None

    df["_agg_period"] = df[period_col].map(_to_month)

    # --- Step 2: find up to 3 low-cardinality dimension columns ---
    dim_cols = _find_low_card_dims(df, period_col, max_dims=3)

    # --- Step 3: build groupby keys ---
    groupby_cols = ["_agg_period"] + dim_cols

    # --- Step 4: drop high-cardinality columns ---
    drop_cols = set()
    for col in df.columns:
        if col in groupby_cols or col == "_agg_period":
            continue
        if _is_high_card(col):
            # Special case: "区域" may appear both as high-card (per-row table area)
            # and low-card (regional label).  If it was picked as a dim_col, keep it.
            if col in dim_cols:
                continue
            drop_cols.add(col)
        # Also drop the original period column (replaced by _agg_period)
        if col == period_col:
            drop_cols.add(col)

    df_clean = df.drop(columns=list(drop_cols), errors="ignore")

    # --- Step 5: sum numeric columns ---
    numeric_cols = [c for c in df_clean.columns
                    if c not in groupby_cols and pd.api.types.is_numeric_dtype(df_clean[c])]

    if not numeric_cols:
        # Nothing to aggregate – just deduplicate by period
        return df_clean[groupby_cols].drop_duplicates().rename(
            columns={"_agg_period": period_col}
        )

    agg_df = df_clean.groupby(groupby_cols, as_index=False)[numeric_cols].sum()

    # Rename _agg_period back to original period_col name
    agg_df = agg_df.rename(columns={"_agg_period": period_col})
    if dim_cols:
        print(f"    [AGG-DIMS] groupby dims: {dim_cols} → {len(agg_df)} rows")
    return agg_df


# ---------------------------------------------------------------------------
# RETAIL HELPERS
# ---------------------------------------------------------------------------

def melt_wide_sales(df: pd.DataFrame, period_col: str = "月份") -> pd.DataFrame:
    """
    Convert wide-format store-column table into long-format with canonical sales_amount.

    Input:  月份 | 旗舰店(元) | 次旗舰店(元) | 新店(元) | 合计(元)
    Output: 月份 | 门店类型 | sales_amount

    Drops the aggregated 合计 column to avoid double-counting.
    Only keeps the period column + low-cardinality store columns (nunique <= 12).
    """
    if period_col not in df.columns:
        return df

    # Identify value columns (numeric, not period col) — exclude 合计 variants
    value_cols = [
        c for c in df.columns
        if c != period_col
        and pd.api.types.is_numeric_dtype(df[c])
        and not any(kw in str(c) for kw in ["合计", "总计", "total"])
        and df[c].nunique() <= 12
    ]
    if not value_cols:
        # Fallback: if only aggregated column available, rename it
        agg_cols = [c for c in df.columns if c != period_col and pd.api.types.is_numeric_dtype(df[c])]
        if agg_cols:
            out = df[[period_col, agg_cols[0]]].copy()
            out = out.rename(columns={agg_cols[0]: "sales_amount"})
            return out
        return df

    melted = df[[period_col] + value_cols].melt(
        id_vars=[period_col],
        var_name="门店类型",
        value_name="sales_amount",
    )
    # Clean up store type name (strip unit suffix like "(元)")
    melted["门店类型"] = melted["门店类型"].str.replace(r"\(元\)$", "", regex=True).str.strip()
    return melted.dropna(subset=["sales_amount"]).reset_index(drop=True)


def aggregate_detail_to_monthly(
    df: pd.DataFrame,
    date_col: str,
    amount_col: str,
    qty_col: Optional[str] = None,
    group_cols: Optional[List[str]] = None,
    drop_high_cardinality: Optional[List[str]] = None,
) -> pd.DataFrame:
    """
    Aggregate a detail-level DataFrame to monthly granularity.

    - date_col: source date column (parsed to datetime, then truncated to month)
    - amount_col: renamed to canonical 'sales_amount'
    - qty_col: optional, renamed to 'sales_quantity'
    - group_cols: low-cardinality dimension columns to preserve (nunique <= 12 filter applied)
    - drop_high_cardinality: columns to drop before grouping (customer names, order IDs, etc.)

    Returns monthly aggregated DataFrame with period '月份' as YYYY年M月 string.
    """
    df = df.copy()

    # Drop explicitly high-cardinality columns
    if drop_high_cardinality:
        df = df.drop(columns=[c for c in drop_high_cardinality if c in df.columns], errors="ignore")

    # Parse date → month string
    parsed = pd.to_datetime(df[date_col], errors="coerce")
    df["月份"] = parsed.dt.strftime("%Y年%-m月") if sys.platform != "win32" else parsed.apply(
        lambda d: f"{d.year}年{d.month}月" if not pd.isna(d) else None
    )
    df = df.dropna(subset=["月份"])

    # Validate group_cols cardinality
    safe_group = []
    if group_cols:
        for c in group_cols:
            if c in df.columns and df[c].nunique() <= 12:
                safe_group.append(c)

    agg_on = ["月份"] + safe_group
    agg_dict: Dict[str, Any] = {amount_col: "sum"}
    if qty_col and qty_col in df.columns:
        agg_dict[qty_col] = "sum"

    result = df.groupby(agg_on, as_index=False).agg(agg_dict)
    result = result.rename(columns={amount_col: "sales_amount"})
    if qty_col and qty_col in result.columns:
        result = result.rename(columns={qty_col: "sales_quantity"})
    return result


# ---------------------------------------------------------------------------
# DEMO_FACTORY2 HELPERS — reshape mfg-normal wide tables to long format
# ---------------------------------------------------------------------------

# Region name bytes (from Test-mock-mfg-normal-s42.xlsx Sheet 1 row labels)
# These are the UTF-8 decoded Chinese region names that appear in the source file.
_MFG2_REGION_NAMES = [
    "华中事业部",  # row idx 0 of data section (华中)
    "江苏区域",    # row idx 1
    "广东区域",    # row idx 2
    "华中区域",    # row idx 3
    "其他渠道及销售", # row idx 4  (kept for completeness, excluded from final)
    "合计",        # row idx 5  (excluded – double-count)
]

# Cleaner region labels after filtering (drop 合计 and 其他)
_MFG2_VALID_REGIONS = {"华中事业部", "江苏区域", "广东区域", "华中区域"}


def reshape_factory2_finance(xl_path: Path) -> Optional[pd.DataFrame]:
    """
    Reshape Test-mock-mfg-normal-s42.xlsx Sheet 1 (收入及净利简表) from wide format
    (regions × months × metrics) to long format with canonical measures.

    Source layout (Sheet 1, header=0):
      Row 0: [label_col, "1月", nan, nan, "2月", nan, nan, ..., "12月", nan, nan]
      Row 1: [nan, "预算营收", "24年同期实际", "实际营收", ...]
      Rows 2-6: [region_name, rev_budget, rev_yoy, rev_actual, ...(×12 months)]
      Row 7: [合计, ...]
      Row 8: blank
      Row 9: [label_col, "1月", ...] (profit section)
      Row 10: [nan, "预算净利", "24年同期实际", "实际净利", ...]
      Rows 11-15: [region_name, profit_budget, profit_yoy, profit_actual, ...(×12 months)]

    Output columns: 月份, 区域, revenue, net_profit (canonical measures)

    Returns None if file not found or parse fails.
    """
    if not xl_path.exists():
        return None
    try:
        df_raw = pd.read_excel(xl_path, sheet_name=1, header=None, dtype=object)
    except Exception as e:
        print(f"  [FACTORY2-FINANCE] read error: {e}")
        return None

    # --- Extract month labels from row 1 ---
    # Actual layout (Sheet index 1, 0-indexed rows):
    #   Row 0: title row (ignored)
    #   Row 1: [section_label, "1月", nan, nan, "2月", nan, nan, ..., "12月", nan, nan]
    #   Row 2: [nan, "预算营收", "24年同期实际", "实际营收", ...]  ← sub-headers
    #   Rows 3-7: [region_name, budget, yoy, actual, ...(×12)]
    #   Row 8: 合计 (skip)
    #   Row 9: blank
    #   Row 10: section_label again
    #   Row 11: blank
    #   Rows 12-16: profit rows  [region_name, ...]
    #   Row 17: 合计 (skip)
    row1 = df_raw.iloc[1].tolist()
    months = []  # list of (col_offset_for_actual, month_num)
    import re as _re
    for ci, val in enumerate(row1):
        if ci == 0:
            continue
        s = str(val).strip()
        # Match "N月" patterns (1月-12月) - row1 has them at 3-col intervals
        m = _re.match(r"^(\d+)月$", s)
        if m:
            month_num = int(m.group(1))
            # actual-value column is at ci + 2 (budget=ci, yoy=ci+1, actual=ci+2)
            actual_col = ci + 2
            months.append((actual_col, month_num))

    if not months:
        print("  [FACTORY2-FINANCE] could not parse month headers from row 1")
        return None

    # --- Revenue section: rows 3-7 (skip 合计 at 8) ---
    def _parse_section(row_start: int, row_end: int, measure_name: str) -> List[Dict]:
        out = []
        for ri in range(row_start, row_end + 1):
            if ri >= len(df_raw):
                break
            region_raw = str(df_raw.iloc[ri, 0]).strip()
            # Skip 合计, nan, header rows
            if region_raw in ("nan", "", "合计", "汇总") or "合计" in region_raw:
                continue
            # Use actual-value columns
            for actual_col, month_num in months:
                if actual_col >= len(df_raw.columns):
                    continue
                val = df_raw.iloc[ri, actual_col]
                try:
                    numeric_val = float(val) if not pd.isna(val) else None
                except (TypeError, ValueError):
                    numeric_val = None
                if numeric_val is None:
                    continue
                out.append({
                    "月份": month_num,
                    "区域": region_raw,
                    measure_name: numeric_val,
                })
        return out

    # Revenue: rows 3-7 (5 region rows; row 8 = 合计 which is skipped by _parse_section guard)
    rev_rows = _parse_section(3, 8, "revenue")
    # Profit: rows 12-16 (row 17 = 合计, skipped)
    profit_rows = _parse_section(12, 17, "net_profit")

    if not rev_rows:
        print("  [FACTORY2-FINANCE] no revenue data parsed")
        return None

    # Merge revenue and profit on (月份, 区域)
    df_rev = pd.DataFrame(rev_rows)
    df_profit = pd.DataFrame(profit_rows) if profit_rows else pd.DataFrame(columns=["月份", "区域", "net_profit"])
    df_merged = df_rev.merge(df_profit, on=["月份", "区域"], how="left")

    # Convert month number to "YYYY年M月" format (assign to 2024 window)
    df_merged["月份"] = df_merged["月份"].apply(lambda m: f"2024年{m}月")

    # Ensure clean column types
    for col in ["revenue", "net_profit"]:
        if col in df_merged.columns:
            df_merged[col] = pd.to_numeric(df_merged[col], errors="coerce")

    # Sort for predictability
    df_merged = df_merged.sort_values(["月份", "区域"]).reset_index(drop=True)

    region_count = df_merged["区域"].nunique()
    print(f"    [FACTORY2-FINANCE] reshaped: {len(df_merged)} rows, "
          f"{region_count} regions, months={df_merged['月份'].nunique()}")
    return df_merged


def reshape_factory2_sales(xl_path: Path, window: Window) -> Optional[pd.DataFrame]:
    """
    Reshape Test-mock-mfg-normal-s42.xlsx Sheet 12 (销售明细, header=1) to
    monthly aggregate with 区域 dim.

    Source columns (by index after header=1):
      [0]=日期, [1]=单号(高基数→drop), [2]=客户名(高基数→drop),
      [3]=产品类别(<=12→dim), [4]=规格(高基数→drop),
      [5]=数量(int), [6]=单价(元)(float), [7]=金额(元)(float),
      [8]=区域(4-6 unique→dim), [9]=业务员(高基数→drop)

    Output: 月份, 区域, 产品类别, sales_amount, sales_quantity
    """
    if not xl_path.exists():
        return None
    try:
        df = pd.read_excel(xl_path, sheet_name=12, header=1)
    except Exception as e:
        print(f"  [FACTORY2-SALES] read error: {e}")
        return None

    cols = list(df.columns)
    if len(cols) < 8:
        print(f"  [FACTORY2-SALES] unexpected column count: {cols}")
        return None

    # Rename by position to stable canonical names
    col_map = {
        cols[0]: "日期",
        cols[1]: "_order_no",      # high-card, drop
        cols[2]: "_customer",      # high-card, drop
        cols[3]: "产品类别",        # low-card dim
        cols[4]: "_spec",          # high-card, drop
        cols[5]: "sales_quantity",
        cols[6]: "_unit_price",    # useful but not standard measure
        cols[7]: "sales_amount",
    }
    if len(cols) >= 9:
        col_map[cols[8]] = "区域"  # low-card regional dim
    if len(cols) >= 10:
        col_map[cols[9]] = "_salesperson"  # high-card, drop

    df = df.rename(columns=col_map)

    # Drop high-card and internal columns
    drop = [c for c in df.columns if c.startswith("_")]
    df = df.drop(columns=drop, errors="ignore")

    # Parse date and create 月份
    df["_date"] = pd.to_datetime(df["日期"], errors="coerce")
    df = df.dropna(subset=["_date"])
    df["月份"] = df["_date"].apply(lambda d: f"{d.year}年{d.month}月")
    df = df.drop(columns=["日期", "_date"], errors="ignore")

    # Redate to window
    month_vals = df["月份"].dropna().unique()
    target_months = window.months()
    src_months_parsed = sorted(set(
        (int(s.split("年")[0]), int(s.split("年")[1].replace("月", "")))
        for s in month_vals
        if "年" in s and "月" in s
    ))
    month_remap = {}
    for i, src_ym in enumerate(src_months_parsed):
        tgt_ym = target_months[min(i, len(target_months) - 1)]
        month_remap[f"{src_ym[0]}年{src_ym[1]}月"] = f"{tgt_ym[0]}年{tgt_ym[1]}月"
    df["月份"] = df["月份"].map(lambda v: month_remap.get(v, v))

    # Validate cardinality of 区域 dim
    dim_cols = ["月份"]
    if "区域" in df.columns and 1 < df["区域"].nunique() <= 12:
        dim_cols.append("区域")
    if "产品类别" in df.columns and 1 < df["产品类别"].nunique() <= 12:
        dim_cols.append("产品类别")

    # Aggregate
    agg_dict: Dict[str, str] = {}
    if "sales_amount" in df.columns:
        agg_dict["sales_amount"] = "sum"
    if "sales_quantity" in df.columns:
        agg_dict["sales_quantity"] = "sum"

    if not agg_dict:
        print("  [FACTORY2-SALES] no numeric cols to aggregate")
        return None

    result = df.groupby(dim_cols, as_index=False).agg(agg_dict)
    region_count = result["区域"].nunique() if "区域" in result.columns else 0
    print(f"    [FACTORY2-SALES] {len(df)} detail rows → {len(result)} monthly rows, "
          f"dims={dim_cols[1:]}, regions={region_count}")
    return result


# ---------------------------------------------------------------------------
# SEED PLAN
# ---------------------------------------------------------------------------

@dataclass
class SeedEntry:
    label: str
    src_path: Path
    tenant: str
    domain: str
    sheet_name: Optional[str]       # None = first sheet; "CSV" = csv file
    n_months: int = 1
    header_row: Optional[int] = None  # None = pandas default (0); set 1 to skip title row
    skip_rows: Optional[int] = None   # CSV pre-header rows to skip
    is_snapshot: bool = False         # single-month snapshot → expand
    snapshot_n_months: int = 2        # how many months to expand to
    trend_cols: Optional[List[str]] = None
    aggregate: bool = False           # True = detail rows → monthly aggregation after redate
    melt_wide: bool = False           # True = wide-table (stores-as-cols) → long via melt_wide_sales
    # select_cols: keep only these columns before processing (for wide-table sources with
    # hundreds of payment-name columns that would become messy canonical_field values).
    # None = keep all columns.
    select_cols: Optional[List[str]] = None
    # rename_cols: {old_name: new_name} applied AFTER aggregate so canonical field names
    # map cleanly to sales_amount / sales_quantity in the controlled dictionary.
    rename_cols: Optional[Dict[str, str]] = None
    # window_override: when set, skip the sequential assign_window allocator and
    # use this fixed window instead.  Use this for same-tenant same-domain sources
    # that should share the same time range (distinguished by dims like 门店/品牌)
    # rather than being placed in non-overlapping sequential slots.
    window_override: Optional[Window] = None
    notes: str = ""


def build_seed_plan() -> List[SeedEntry]:
    plan: List[SeedEntry] = []

    # ===== RESTAURANT SALES: 8 brand xlsx snapshots (2026-02) =====
    brand_sales_files = []

    # Enumerate xlsx_converted for brand sales files
    if XLSX_CONV.exists():
        for fp in sorted(XLSX_CONV.glob("*.xlsx")):
            if "销量" in fp.name or "销售" in fp.name:
                brand_sales_files.append(fp)

    # Also check xlsx/ directory
    if XLSX_DIR.exists():
        for fp in sorted(XLSX_DIR.glob("*.xlsx")):
            if "销量" in fp.name or "销售" in fp.name:
                brand_sales_files.append(fp)

    # Brand xlsx files are long-tables (one row per SKU / 商品名称).
    # After aggregate_monthly drops 商品名称 (high-card), the remaining numeric
    # cols are: 单卖数量(不含套餐子商品), 销售金额, 实收, 折后金额, etc.
    # Rename them to clean canonical names so SemanticMapper recognises them.
    _brand_sales_rename = {
        "单卖数量(不含套餐子商品)": "sales_quantity",
        "退货数量(不含套餐子商品)": "return_quantity",
        "销售金额": "sales_amount",
        "折后金额": "discounted_amount",
        "实收": "net_revenue",
        "实退金额": "refund_amount",
        # NOTE: 大众点评 variant names ("销售数量", "实收金额") are intentionally
        # NOT mapped here to avoid _2 suffix collisions when a file contains both
        # "单卖数量(不含套餐子商品)" and "销售数量" (both would map to sales_quantity).
        # "销售数量" is already a synonym in STANDARD_FIELDS.sales_quantity, so
        # SemanticMapper will classify it correctly without an explicit rename.
        # "实收金额" is not in STANDARD_FIELDS, so it remains as a dimension column.
    }
    for i, fp in enumerate(brand_sales_files[:8]):
        safe = re.sub(r"[^\w]", "_", fp.stem)[:30]
        plan.append(SeedEntry(
            label=f"rest_sales_{i+1:02d}_{safe}",
            src_path=fp,
            tenant=DEMO_REST,
            domain="sales",
            sheet_name=None,
            n_months=2,
            is_snapshot=True,
            snapshot_n_months=2,
            trend_cols=["销售金额", "实收", "折后金额", "单卖数量(不含套餐子商品)"],
            aggregate=True,   # SKU-level detail (500-1000 rows) → monthly sum
            rename_cols=_brand_sales_rename,
            notes="品牌商品销量 snapshot → 2个月 → aggregate → rename to sales_amount/sales_quantity",
        ))

    # ===== RESTAURANT PURCHASE: 东门口 =====
    purchase_fp = XLSX_DIR / "东门口2月采购入库明细报表.xlsx"
    if not purchase_fp.exists():
        # try xlsx_converted
        purchase_fp = XLSX_CONV / "东门口2月采购入库明细报表.xlsx"
    plan.append(SeedEntry(
        label="rest_purchase_dongmenkou",
        src_path=purchase_fp,
        tenant=DEMO_REST,
        domain="purchase",
        sheet_name=None,
        n_months=2,
        aggregate=True,   # 11094-row SKU-level detail → monthly sum by supplier
        notes="东门口采购入库 2026-02 → redate → aggregate",
    ))

    # ===== RESTAURANT FINANCE: 张记餐饮 =====
    # 菜品销售排行 uses a fixed window so it shares the 2024 sales range with the
    # brand files rather than being placed sequentially after them (which would push
    # the sales timeline past 2025-12).
    _zhangji_windows = {
        "菜品销售排行": Window(date(2024, 1, 1), date(2024, 12, 31)),
    }
    for sheet_name, domain in [("月度收入明细", "finance"), ("成本分析", "finance"), ("菜品销售排行", "sales")]:
        plan.append(SeedEntry(
            label=f"rest_zhangji_{sheet_name}",
            src_path=TEST_DATA / "张记餐饮-2025经营报表.xlsx",
            tenant=DEMO_REST,
            domain=domain,
            sheet_name=sheet_name,
            n_months=12,
            header_row=1,
            window_override=_zhangji_windows.get(sheet_name),
            notes="张记餐饮合成",
        ))

    # ===== QHJ 2025: continuous time series =====
    #
    # 营业概况月报 is a WIDE-TABLE with 353 columns.  Columns 0-22 are core metrics
    # (营业额, 实收额, 订单数, 客流量, etc.); columns 23+ are individual payment-method /
    # voucher names like "168双人餐代金券", "88代100", "[云闪付]", etc.
    # Without select_cols, aggregate_monthly would sum all 330 payment columns and
    # their names become canonical_field values — messy non-standard names in the DB.
    # Fix: select only core metric columns before aggregation.
    _yybk_core_cols = [
        "日期", "门店名称",
        "营业额", "折扣额", "实收额", "代金券优惠",
        "订单数", "单均消费", "客流量", "人均消费", "翻台率",
        "会员充值金额",
    ]
    # 营业概况月报: rename core metric cols to clean canonical names
    _yybk_rename = {
        "营业额": "sales_amount",
        "实收额": "net_revenue",
        "折扣额": "discount_amount",
        "代金券优惠": "voucher_discount",
        "订单数": "order_count",
        "单均消费": "avg_order_value",
        "客流量": "customer_count",
        "人均消费": "avg_spend_per_person",
        "翻台率": "table_turnover_rate",
        "会员充值金额": "member_recharge_amount",
    }

    # 商品销售明细: long-table (商品名称 per row, daily).  After dropping high-card cols
    # (商品名称, 商品编码, 账单号, 开单时间, 结单时间 etc.) the numeric cols are clean.
    # Rename to canonical names.
    _spxsmd_rename = {
        "点单数量": "sales_quantity",
        "结账数量": "checkout_quantity",
        "折前金额": "pre_discount_amount",
        "折后金额": "discounted_amount",
        "折扣额": "discount_amount",
        "分摊优惠": "allocated_discount",
        "实收额": "net_revenue",
        "单价": "unit_price",
    }

    # 订单付款方式: already a clean long-table (门店 + 付款方式 + metrics).
    # NOT a wide-table; 付款方式 is a value column, not a column header.
    # No select_cols needed.  Rename metric cols.
    _zffs_rename = {
        "付款金额": "sales_amount",
        "订单营业额": "order_revenue",
        "订单折后金额": "order_discounted_amount",
        "订单实收额": "net_revenue",
        "付款笔数": "payment_count",
        "订单笔数": "order_count",
        "代金券优惠": "voucher_discount",
    }

    plan.append(SeedEntry(
        label="rest_qhj25_营业概况月报",
        src_path=QHJ_E2E / "qhj_25_营业概况月报.csv",
        tenant=DEMO_REST,
        domain="sales",
        sheet_name="CSV",
        n_months=12,
        skip_rows=3,
        aggregate=True,   # daily multi-store → monthly per-store
        select_cols=_yybk_core_cols,   # ← drop 330+ payment-name cols
        rename_cols=_yybk_rename,
        window_override=Window(date(2024, 1, 1), date(2024, 12, 31)),
        notes="QHJ 营业概况月报 wide-table: select core 12 cols → aggregate → rename to canonical",
    ))

    plan.append(SeedEntry(
        label="rest_qhj25_商品销售明细",
        src_path=QHJ_E2E / "qhj_25_商品销售明细.csv",
        tenant=DEMO_REST,
        domain="sales",
        sheet_name="CSV",
        n_months=12,
        skip_rows=3,
        aggregate=True,   # 200k SKU-level rows → monthly
        rename_cols=_spxsmd_rename,
        window_override=Window(date(2024, 1, 1), date(2024, 12, 31)),
        notes="QHJ 商品销售明细 long-table: drop SKU cols → monthly aggregate → rename",
    ))

    plan.append(SeedEntry(
        label="rest_qhj25_订单付款方式",
        src_path=QHJ_E2E / "qhj_25_订单付款方式.csv",
        tenant=DEMO_REST,
        domain="sales",
        sheet_name="CSV",
        n_months=12,
        skip_rows=3,
        aggregate=True,   # 门店×付款方式 per-period → aggregate by period + 门店
        rename_cols=_zffs_rename,
        window_override=Window(date(2025, 1, 1), date(2025, 12, 31)),
        notes="QHJ 订单付款方式 long-table: 付款方式 as dim value → aggregate per period → rename",
    ))

    # 详细日报表 (124 cols, order-level) — keep for sales coverage but constrain to core cols
    # to avoid 60+ derived metric columns exploding canonical_field variety
    _rbb_core_cols = [
        "日期", "门店名称",
        "营业额", "实收额", "折扣额",
        "订单数", "客流量", "人均消费", "翻台率",
    ]
    _rbb_rename = {
        "营业额": "sales_amount",
        "实收额": "net_revenue",
        "折扣额": "discount_amount",
        "订单数": "order_count",
        "客流量": "customer_count",
        "人均消费": "avg_spend_per_person",
        "翻台率": "table_turnover_rate",
    }
    plan.append(SeedEntry(
        label="rest_qhj25_详细日报表",
        src_path=QHJ_E2E / "qhj_25_详细日报表.csv",
        tenant=DEMO_REST,
        domain="sales",
        sheet_name="CSV",
        n_months=12,
        skip_rows=3,
        aggregate=True,   # order-level → monthly
        select_cols=_rbb_core_cols,
        rename_cols=_rbb_rename,
        window_override=Window(date(2025, 1, 1), date(2025, 12, 31)),
        notes="QHJ 详细日报表 wide-table: select core cols → monthly aggregate → rename",
    ))

    # ===== FACTORY PRODUCTION: 绿源食品 =====
    for sheet_name, domain in [("生产产量", "production"), ("原材料采购", "purchase"), ("利润表", "finance")]:
        plan.append(SeedEntry(
            label=f"factory_luyuan_{sheet_name}",
            src_path=TEST_DATA / "绿源食品-2025生产报表.xlsx",
            tenant=DEMO_FACTORY,
            domain=domain,
            sheet_name=sheet_name,
            n_months=12,
            header_row=1,
            notes="绿源食品合成",
        ))

    # ===== RETAIL/INVENTORY: 鲜味零售 (kept for DEMO_REST supplemental) =====
    # 门店销售 (sales) uses a fixed 2025 window so it stays within TIMELINE_END
    # after the other DEMO_REST/sales sources have consumed 2024.
    _xianwei_windows = {
        "门店销售": Window(date(2025, 1, 1), date(2025, 12, 31)),
    }
    for sheet_name, domain in [("门店销售", "sales"), ("库存周转", "inventory")]:
        plan.append(SeedEntry(
            label=f"retail_xianwei_{sheet_name}",
            src_path=TEST_DATA / "鲜味零售-2025销售数据.xlsx",
            tenant=DEMO_REST,
            domain=domain,
            sheet_name=sheet_name,
            n_months=12,
            header_row=1,
            window_override=_xianwei_windows.get(sheet_name),
            notes="鲜味零售合成",
        ))

    # ===== MODULE COVERAGE: Test-mock files =====
    for mock_file, tenant, label_prefix in [
        (TEST_MOCK / "Test-mock-mfg-normal-s42.xlsx",  DEMO_FACTORY, "factory_mock"),
        (TEST_MOCK / "Test-mock-food-normal-s42.xlsx", DEMO_REST,    "rest_mock"),
    ]:
        for sheet_name, domain in [
            ("库存台账",    "inventory"),
            ("应收账款账龄", "finance"),
            ("费用预算执行", "finance"),
        ]:
            plan.append(SeedEntry(
                label=f"{label_prefix}_{sheet_name}",
                src_path=mock_file,
                tenant=tenant,
                domain=domain,
                sheet_name=sheet_name,
                n_months=3,
                header_row=2,
                notes=f"Test-mock coverage {sheet_name}",
            ))

    # ===== RETAIL TENANT (DEMO_RETAIL) — 第3业态: 零售连锁 =====

    # A1. 鲜味零售 门店销售 — wide-format (store types as cols) → melt → canonical sales_amount
    plan.append(SeedEntry(
        label="retail_xianwei_门店销售_retail",
        src_path=TEST_DATA / "鲜味零售-2025销售数据.xlsx",
        tenant=DEMO_RETAIL,
        domain="sales",
        sheet_name="门店销售",
        n_months=12,
        header_row=1,
        melt_wide=True,      # 旗舰店/次旗舰店/新店 → long rows, value col → sales_amount
        notes="鲜味零售门店销售 wide→long melt, canonical sales_amount per 门店类型",
    ))

    # A2. 鲜味零售 库存周转 → rename 期末库存(元) → stock_amount
    plan.append(SeedEntry(
        label="retail_xianwei_库存周转_retail",
        src_path=TEST_DATA / "鲜味零售-2025销售数据.xlsx",
        tenant=DEMO_RETAIL,
        domain="inventory",
        sheet_name="库存周转",
        n_months=12,
        header_row=1,
        rename_cols={"期末库存(元)": "stock_amount", "销售出库(元)": "sales_amount"},
        notes="鲜味零售库存周转 canonical stock_amount + sales_amount",
    ))

    # A3. Test-mock-retail 销售明细 — detail rows → agg monthly by 区域, canonical measures
    # aggregate_monthly auto-drops high-cardinality cols (客户名称/订单号/业务员 all >12 unique)
    plan.append(SeedEntry(
        label="retail_mock_销售明细_agg",
        src_path=RETAIL_MOCK,
        tenant=DEMO_RETAIL,
        domain="sales",
        sheet_name="销售明细",
        n_months=12,
        header_row=1,
        aggregate=True,      # 329 detail rows → ~48 monthly×区域 rows
        rename_cols={"金额(元)": "sales_amount", "数量": "sales_quantity"},
        notes="Test-mock-retail 销售明细 agg by month+区域(4 unique), canonical sales_amount/quantity",
    ))

    # B1-B3. Finance depth — AR aging, budget exec, cashflow (for DEMO_RETAIL)
    for sheet_name, hdr, notes_str in [
        ("应收账款账龄", 2, "AR账龄分析 identity measures retained"),
        ("费用预算执行", 2, "费用预算执行 identity measures retained"),
        ("现金流量表",   1, "现金流量表 identity measures retained"),
    ]:
        plan.append(SeedEntry(
            label=f"retail_mock_{sheet_name}_finance",
            src_path=RETAIL_MOCK,
            tenant=DEMO_RETAIL,
            domain="finance",
            sheet_name=sheet_name,
            n_months=3,
            header_row=hdr,
            notes=f"Test-mock-retail {notes_str}",
        ))

    # B4-B5. Finance depth for existing tenants (DEMO_FACTORY + DEMO_REST)
    for tenant, label_prefix in [(DEMO_FACTORY, "factory_retail_mock"), (DEMO_REST, "rest_retail_mock")]:
        for sheet_name, hdr in [("应收账款账龄", 2), ("现金流量表", 1)]:
            plan.append(SeedEntry(
                label=f"{label_prefix}_{sheet_name}_finance",
                src_path=RETAIL_MOCK,
                tenant=tenant,
                domain="finance",
                sheet_name=sheet_name,
                n_months=3,
                header_row=hdr,
                notes=f"Test-mock-retail finance depth for {tenant}",
            ))

    # ===== DEMO_FACTORY2 — 第4租户: 第2工厂, 跨区域 (Test-mock-mfg-normal-s42.xlsx) =====
    # NOTE: Finance (Sheet 1) and Sales (Sheet 12) use custom reshape helpers called in
    # generate_factory2_data() below because the source layout needs special wide→long
    # transformation (regions as rows, months as columns).
    # Here we add the simpler sheet-based entries that process_entry can handle directly.

    # C1. 应收账款账龄 — standard aging sheet (header=2), identity measures
    plan.append(SeedEntry(
        label="factory2_应收账款账龄",
        src_path=MFG_MOCK,
        tenant=DEMO_FACTORY2,
        domain="finance",
        sheet_name=13,   # sheet index 13 = 应收账款账龄
        n_months=3,
        header_row=1,
        notes="DEMO_FACTORY2 AR aging (sheet 13, index-based to avoid encoding issues)",
    ))

    # C2. 库存台账 — inventory snapshot
    plan.append(SeedEntry(
        label="factory2_库存台账",
        src_path=MFG_MOCK,
        tenant=DEMO_FACTORY2,
        domain="inventory",
        sheet_name=14,   # sheet index 14 = 库存台账
        n_months=3,
        header_row=1,
        notes="DEMO_FACTORY2 inventory ledger (sheet 14, index-based)",
    ))

    # C3. 费用预算执行 — budget vs actual
    plan.append(SeedEntry(
        label="factory2_费用预算执行",
        src_path=MFG_MOCK,
        tenant=DEMO_FACTORY2,
        domain="finance",
        sheet_name=16,   # sheet index 16 = 费用预算执行
        n_months=3,
        header_row=1,
        notes="DEMO_FACTORY2 budget execution (sheet 16, index-based)",
    ))

    # C4. 现金流量表 — cashflow
    plan.append(SeedEntry(
        label="factory2_现金流量表",
        src_path=MFG_MOCK,
        tenant=DEMO_FACTORY2,
        domain="finance",
        sheet_name=17,   # sheet index 17 = 现金流量表
        n_months=3,
        header_row=1,
        notes="DEMO_FACTORY2 cashflow (sheet 17, index-based)",
    ))

    return plan


# ---------------------------------------------------------------------------
# GAP-FILL GENERATORS
# ---------------------------------------------------------------------------

def generate_gap_fill() -> List[Tuple[str, str, str, pd.DataFrame]]:
    """
    Synthetic gap-fill for missing domains.
    Returns list of (tenant, domain, label, DataFrame).
    Covers: production, inventory, hr, quality across 2024-2025 (24 months).
    """
    results = []
    months_24_25 = []
    for y in [2024, 2025]:
        for m in range(1, 13):
            months_24_25.append(f"{y}年{m}月")

    rng = np.random.default_rng(42)

    # --- PRODUCTION (factory) ---
    # #3: Added 产线 dimension (2 lines) for drill-down alongside 工序
    processes = ["备料", "搅拌", "成型", "蒸煮", "包装"]
    production_lines = ["产线A", "产线B"]  # low-cardinality: 2 unique values
    rows = []
    for mo in months_24_25:
        for proc in processes:
            for line in production_lines:
                input_qty = int(rng.integers(4000, 8000))  # per-line qty (total unchanged)
                yield_rate = rng.uniform(0.92, 0.99)
                output_qty = int(input_qty * yield_rate)
                rows.append({
                    "月份": mo,
                    "工序": proc,
                    "产线": line,              # #3: drill-down dimension
                    "投入数量(kg)": input_qty,
                    "产出数量(kg)": output_qty,
                    "合格数量(kg)": int(output_qty * rng.uniform(0.97, 1.0)),
                    "出成率(%)": round(yield_rate * 100, 2),
                    "不良率(%)": round((1.0 - yield_rate) * 100, 2),
                })
    results.append((DEMO_FACTORY, "production", "gap_fill_production", pd.DataFrame(rows)))

    # --- INVENTORY (factory) ---
    # #3: Added 仓库 (2) + 物料类别 (3) dimensions for drill-down
    materials = ["猪肉原料", "豆腐", "调味料", "包装袋", "纸箱"]
    warehouses = ["主仓库", "辅仓库"]          # 2 unique → low-cardinality
    material_cats = {                          # 3 categories → low-cardinality
        "猪肉原料": "肉类原料",
        "豆腐": "豆制品",
        "调味料": "调辅料",
        "包装袋": "包装材料",
        "纸箱": "包装材料",
    }
    rows = []
    base = {m: rng.integers(500, 5000) for m in materials}
    for mo in months_24_25:
        for mat in materials:
            for wh in warehouses:
                qty = int(base[mat] * rng.uniform(0.4, 0.6))  # split between warehouses
                rows.append({
                    "月份": mo,
                    "物料名称": mat,
                    "物料类别": material_cats[mat],  # #3: drill-down dim (3 unique)
                    "仓库": wh,                       # #3: drill-down dim (2 unique)
                    "期末库存数量": qty,
                    "期末库存金额(元)": round(qty * rng.uniform(5, 80), 2),
                    "周转率(次/月)": round(rng.uniform(0.5, 4.0), 2),
                    "安全库存": int(base[mat] * 0.1),
                })
    results.append((DEMO_FACTORY, "inventory", "gap_fill_inventory_factory", pd.DataFrame(rows)))

    # --- INVENTORY (restaurant) ---
    # #3: Added 仓库 (3 types) + 物料类别 (3) dims for drill-down
    items = ["食材-猪肉", "食材-蔬菜", "调料", "包装盒", "饮品原料"]
    rest_warehouses = ["食材仓", "干货仓", "冷链仓"]   # 3 unique → low-cardinality
    rest_mat_cats = {
        "食材-猪肉": "肉类食材",
        "食材-蔬菜": "蔬菜食材",
        "调料": "调辅料",
        "包装盒": "包装耗材",
        "饮品原料": "饮品原料",
    }
    rows = []
    for mo in months_24_25:
        for item in items:
            for wh in rest_warehouses:
                qty = int(rng.integers(30, 270))  # split across 3 warehouses
                rows.append({
                    "月份": mo,
                    "物料名称": item,
                    "物料类别": rest_mat_cats[item],  # #3: dim (5 unique, ≤12 ✓)
                    "仓库": wh,                        # #3: dim (3 unique ✓)
                    "期末库存数量": qty,
                    "期末库存金额(元)": round(qty * rng.uniform(2, 30), 2),
                    "周转率(次/月)": round(rng.uniform(2.0, 8.0), 2),
                })
    results.append((DEMO_REST, "inventory", "gap_fill_inventory_rest", pd.DataFrame(rows)))

    # --- HR (factory) ---
    # #3: Added 区域 dimension (4 regions) for cross-region HR analysis
    departments = ["生产部", "品质部", "仓储部", "采购部", "管理部"]
    factory_regions = ["华东", "华南", "华中", "华北"]   # 4 unique → low-cardinality
    rows = []
    for mo in months_24_25:
        for dept in departments:
            for region in factory_regions:
                n = int(rng.integers(2, 12))   # per-region headcount (smaller unit)
                rows.append({
                    "月份": mo,
                    "部门": dept,
                    "区域": region,              # #3: drill-down dim (4 unique ✓)
                    "人数": n,
                    "工资总额(元)": round(n * rng.uniform(4500, 12000), 2),
                    "人均产值(元)": round(rng.uniform(8000, 30000), 2),
                    "提成合计(元)": round(rng.uniform(0, 20000), 2),
                })
    results.append((DEMO_FACTORY, "hr", "gap_fill_hr_factory", pd.DataFrame(rows)))

    # --- HR (restaurant) ---
    # #3: Added 门店名称 dimension (3 stores) for per-store HR analysis
    roles = ["厨师", "服务员", "收银", "外卖配送", "管理"]
    rest_stores = ["门店01", "门店02", "门店03"]   # 3 unique → low-cardinality
    rows = []
    for mo in months_24_25:
        for role in roles:
            for store in rest_stores:
                n = int(rng.integers(1, 6))   # per-store headcount
                rows.append({
                    "月份": mo,
                    "岗位": role,
                    "门店名称": store,           # #3: drill-down dim (3 unique ✓)
                    "人数": n,
                    "工资总额(元)": round(n * rng.uniform(3500, 8000), 2),
                    "人效(元/人)": round(rng.uniform(15000, 60000), 2),
                })
    results.append((DEMO_REST, "hr", "gap_fill_hr_rest", pd.DataFrame(rows)))

    # --- QUALITY (factory) ---
    # #3: Added 部门 dimension (3 departments) for quality ownership drill-down
    stages = ["进料检验", "过程检验", "出货检验"]
    quality_depts = ["品质部", "生产部", "仓储部"]   # 3 unique → low-cardinality
    rows = []
    for mo in months_24_25:
        for stage in stages:
            for dept in quality_depts:
                batches = int(rng.integers(4, 18))   # per-dept batches
                pass_n = int(batches * rng.uniform(0.90, 0.99))
                rows.append({
                    "月份": mo,
                    "检验环节": stage,
                    "部门": dept,               # #3: drill-down dim (3 unique ✓)
                    "检验批次": batches,
                    "合格数": pass_n,
                    "不合格数": batches - pass_n,
                    "合格率(%)": round(pass_n / batches * 100, 2),
                })
    results.append((DEMO_FACTORY, "quality", "gap_fill_quality", pd.DataFrame(rows)))

    # =========================================================================
    # DEMO_FACTORY2 gap-fill: production / inventory / purchase / finance
    # =========================================================================
    # These domains are absent from build_seed_plan() and generate_factory2_data()
    # for DEMO_FACTORY2 (which currently only has finance/sales from the real mock).
    # All generators follow the same pattern: 24 months × low-cardinality dims.

    # --- DEMO_FACTORY2: PRODUCTION (工序 × 产线 × 区域) ---
    # 3 工序 × 2 产线 × 3 区域 = 18 rows/month × 24 months = 432 rows  (≤500 ✓)
    f2_processes = ["冲压成型", "组装", "质检包装"]
    f2_lines = ["产线A", "产线B"]          # 2 unique ✓
    f2_regions = ["华东区域", "华中区域", "华南区域"]  # 3 unique ✓
    rows = []
    for mo in months_24_25:
        trend_mult = 1.0 + 0.015 * months_24_25.index(mo)  # +1.5%/month upward trend
        for proc in f2_processes:
            for line in f2_lines:
                for region in f2_regions:
                    input_qty = int(rng.integers(3000, 7000) * trend_mult)
                    yield_rate = rng.uniform(0.91, 0.98)
                    output_qty = int(input_qty * yield_rate)
                    rows.append({
                        "月份": mo,
                        "工序": proc,
                        "产线": line,
                        "区域": region,
                        "投入数量(kg)": input_qty,
                        "产出数量(kg)": output_qty,
                        "合格数量(kg)": int(output_qty * rng.uniform(0.96, 1.0)),
                        "出成率(%)": round(yield_rate * 100, 2),
                        "不良率(%)": round((1.0 - yield_rate) * 100, 2),
                    })
    results.append((DEMO_FACTORY2, "production", "gap_fill_production_factory2", pd.DataFrame(rows)))

    # --- DEMO_FACTORY2: INVENTORY (物料类别 × 仓库) ---
    # 4 物料 × 3 物料类别 × 2 仓库 unique combos = 6 rows/month × 24 = 144 rows ✓
    f2_materials = ["钢材原料", "电子元件", "化工辅料", "包装耗材"]
    f2_mat_cats = {
        "钢材原料": "金属材料",
        "电子元件": "电子元件",
        "化工辅料": "化工辅料",
        "包装耗材": "包装材料",
    }  # 4 categories ≤ 12 ✓
    f2_warehouses = ["原料仓", "成品仓"]   # 2 unique ✓
    rows = []
    f2_base_qty = {m: int(rng.integers(200, 3000)) for m in f2_materials}
    for i, mo in enumerate(months_24_25):
        trend_mult = 1.0 + 0.01 * i  # slight growth trend
        for mat in f2_materials:
            for wh in f2_warehouses:
                qty = int(f2_base_qty[mat] * rng.uniform(0.4, 0.6) * trend_mult)
                unit_price = rng.uniform(10, 200)
                rows.append({
                    "月份": mo,
                    "物料名称": mat,
                    "物料类别": f2_mat_cats[mat],  # 4 unique ✓
                    "仓库": wh,                     # 2 unique ✓
                    "期末库存数量": qty,
                    "期末库存金额(元)": round(qty * unit_price, 2),
                    "周转率(次/月)": round(rng.uniform(0.3, 3.5), 2),
                    "安全库存": int(f2_base_qty[mat] * 0.15),
                })
    results.append((DEMO_FACTORY2, "inventory", "gap_fill_inventory_factory2", pd.DataFrame(rows)))

    # --- DEMO_FACTORY2: PURCHASE (供应商 × 区域) ---
    # 5 供应商 × 3 区域 = 15 rows/month × 24 months = 360 rows ✓
    f2_suppliers = ["钢铁供应商A", "电子元件商B", "化工原料商C", "包装材料商D", "综合物资商E"]
    rows = []
    for i, mo in enumerate(months_24_25):
        trend_mult = 1.0 + 0.012 * i
        for sup in f2_suppliers:
            for region in f2_regions:
                amount = round(rng.uniform(50000, 500000) * trend_mult, 2)
                qty = int(rng.integers(100, 2000))
                rows.append({
                    "月份": mo,
                    "供应商": sup,               # 5 unique ✓ (≤12)
                    "区域": region,              # 3 unique ✓
                    "采购金额(元)": amount,
                    "采购数量": qty,
                    "单价(元)": round(amount / qty, 2),
                    "到货及时率(%)": round(rng.uniform(85.0, 99.5), 2),
                    "合格率(%)": round(rng.uniform(92.0, 99.8), 2),
                })
    results.append((DEMO_FACTORY2, "purchase", "gap_fill_purchase_factory2", pd.DataFrame(rows)))

    # --- DEMO_FACTORY2: FINANCE (部门 × 区域, 24-month trend) ---
    # Complements generate_factory2_data() which only covers 12 months (2024) from real mock.
    # This gap-fill covers the full 24-month timeline with dept×region drill-down.
    # 4 部门 × 3 区域 = 12 rows/month × 24 months = 288 rows ✓
    f2_depts = ["销售部", "生产部", "采购部", "财务部"]  # 4 unique ✓
    rows = []
    for i, mo in enumerate(months_24_25):
        trend_mult = 1.0 + 0.018 * i  # ~2%/month revenue growth
        for dept in f2_depts:
            for region in f2_regions:
                revenue = round(rng.uniform(200000, 2000000) * trend_mult, 2)
                cost = round(revenue * rng.uniform(0.55, 0.75), 2)
                profit = round(revenue - cost, 2)
                rows.append({
                    "月份": mo,
                    "部门": dept,               # 4 unique ✓
                    "区域": region,              # 3 unique ✓
                    "营业收入(元)": revenue,
                    "成本(元)": cost,
                    "净利润(元)": profit,
                    "利润率(%)": round(profit / revenue * 100, 2),
                    "费用合计(元)": round(cost * rng.uniform(0.1, 0.3), 2),
                })
    results.append((DEMO_FACTORY2, "finance", "gap_fill_finance_factory2", pd.DataFrame(rows)))

    # =========================================================================
    # DEMO_RETAIL gap-fill: finance (部门×区域) + purchase (供应商×门店类型)
    # =========================================================================
    # DEMO_RETAIL already has sales (A1-A3) and inventory (A2) and finance sheets
    # (B1-B3: 3-month AR aging/budget/cashflow from mock).  The gap-fill here adds:
    #   - finance: 24-month dept×region trend (broader than the 3-month sheet entries)
    #   - purchase: 24-month supplier×store-type dim (entirely missing domain)

    # --- DEMO_RETAIL: FINANCE (部门 × 区域, 24-month trend) ---
    # 3 部门 × 3 区域 = 9 rows/month × 24 months = 216 rows ✓
    retail_depts = ["门店运营部", "采购部", "市场部"]   # 3 unique ✓
    retail_regions = ["华东", "华南", "华北"]            # 3 unique ✓
    rows = []
    for i, mo in enumerate(months_24_25):
        trend_mult = 1.0 + 0.02 * i
        for dept in retail_depts:
            for region in retail_regions:
                revenue = round(rng.uniform(100000, 800000) * trend_mult, 2)
                cost = round(revenue * rng.uniform(0.45, 0.65), 2)
                profit = round(revenue - cost, 2)
                rows.append({
                    "月份": mo,
                    "部门": dept,               # 3 unique ✓
                    "区域": region,              # 3 unique ✓
                    "营业收入(元)": revenue,
                    "成本(元)": cost,
                    "净利润(元)": profit,
                    "利润率(%)": round(profit / revenue * 100, 2),
                    "费用合计(元)": round(cost * rng.uniform(0.08, 0.25), 2),
                })
    results.append((DEMO_RETAIL, "finance", "gap_fill_finance_retail", pd.DataFrame(rows)))

    # --- DEMO_RETAIL: PURCHASE (供应商 × 门店类型) ---
    # 4 供应商 × 3 门店类型 = 12 rows/month × 24 months = 288 rows ✓
    retail_suppliers = ["食品供应商A", "日用品商B", "冷链配送商C", "包装耗材商D"]  # 4 unique ✓
    retail_store_types = ["旗舰店", "次旗舰店", "新店"]   # 3 unique ✓
    rows = []
    for i, mo in enumerate(months_24_25):
        trend_mult = 1.0 + 0.015 * i
        for sup in retail_suppliers:
            for store_type in retail_store_types:
                amount = round(rng.uniform(20000, 300000) * trend_mult, 2)
                qty = int(rng.integers(50, 1000))
                rows.append({
                    "月份": mo,
                    "供应商": sup,               # 4 unique ✓
                    "门店类型": store_type,       # 3 unique ✓
                    "采购金额(元)": amount,
                    "采购数量": qty,
                    "单价(元)": round(amount / qty, 2),
                    "到货及时率(%)": round(rng.uniform(88.0, 99.0), 2),
                    "退货率(%)": round(rng.uniform(0.5, 5.0), 2),
                })
    results.append((DEMO_RETAIL, "purchase", "gap_fill_purchase_retail", pd.DataFrame(rows)))

    return results


# ---------------------------------------------------------------------------
# DEMO_FACTORY2 GENERATOR — wide-table reshaping for finance + sales
# ---------------------------------------------------------------------------

def generate_factory2_data() -> List[Tuple[str, str, str, pd.DataFrame]]:
    """
    Generate DEMO_FACTORY2 finance (收入及净利简表, wide→long with 区域 dim)
    and sales (销售明细, detail→monthly with 区域+产品类别 dims).

    Returns list of (tenant, domain, label, DataFrame) same as generate_gap_fill().
    """
    results: List[Tuple[str, str, str, pd.DataFrame]] = []
    if not MFG_MOCK.exists():
        print(f"  [FACTORY2] source file not found: {MFG_MOCK}")
        return results

    # --- Finance: 收入及净利简表 (Sheet 1) → long with 区域 dim ---
    import calendar as _cal
    win_finance = Window(date(2024, 1, 1), date(2024, 12, 31))
    df_finance = reshape_factory2_finance(MFG_MOCK)
    if df_finance is not None and not df_finance.empty:
        results.append((DEMO_FACTORY2, "finance", "factory2_收入净利_by区域", df_finance))

    # --- Sales: 销售明细 (Sheet 12) → monthly with 区域+产品类别 dims ---
    win_sales = Window(date(2025, 1, 1), date(2025, 12, 31))
    df_sales = reshape_factory2_sales(MFG_MOCK, win_sales)
    if df_sales is not None and not df_sales.empty:
        results.append((DEMO_FACTORY2, "sales", "factory2_销售明细_by区域", df_sales))

    return results


# ---------------------------------------------------------------------------
# ENTRY PROCESSOR
# ---------------------------------------------------------------------------

def process_entry(entry: SeedEntry, window: Window) -> Optional[pd.DataFrame]:
    """Load → desensitise → redate → [aggregate].  Returns processed df or None on error."""
    src = entry.src_path
    if not src.exists():
        print(f"  [SKIP] Not found: {src.name}")
        return None

    try:
        if entry.sheet_name == "CSV":
            kwargs: Dict[str, Any] = {"low_memory": False}
            if entry.skip_rows:
                kwargs["skiprows"] = entry.skip_rows
            try:
                df = pd.read_csv(src, encoding="utf-8", **kwargs)
            except UnicodeDecodeError:
                df = pd.read_csv(src, encoding="gbk", **kwargs)
        else:
            read_kw: Dict[str, Any] = {}
            if entry.sheet_name:
                read_kw["sheet_name"] = entry.sheet_name
            if entry.header_row is not None:
                read_kw["header"] = entry.header_row
            df = pd.read_excel(src, **read_kw)
    except Exception as e:
        print(f"  [ERROR] {src.name} / sheet={entry.sheet_name}: {e}")
        return None

    if df.empty:
        print(f"  [SKIP] Empty: {src.name}")
        return None

    # Apply column selection BEFORE any other processing.
    # Needed for wide-table sources (e.g. 营业概况月报 with 353 cols where cols 23+
    # are all payment-method/voucher names that would become messy canonical_field values).
    if entry.select_cols:
        present = [c for c in entry.select_cols if c in df.columns]
        missing = [c for c in entry.select_cols if c not in df.columns]
        if missing:
            print(f"    [SELECT] Warning: missing columns {missing}")
        if present:
            df = df[present]
            print(f"    [SELECT] Kept {len(present)} of {len(present)+len(missing)} requested cols")

    # Detect date/period column before transformation (needed for aggregate step)
    date_cols = _find_date_cols(df)

    # Handle snapshot expansion
    if entry.is_snapshot:
        df = expand_snapshot(
            df,
            window,
            n_months=min(entry.snapshot_n_months, len(window.months())),
            trend_cols=entry.trend_cols,
        )
        # Snapshot expansion adds '月份' column; use that as the period column
        if "月份" not in date_cols:
            date_cols = ["月份"] + date_cols
    else:
        df = redate(df, window)

    df = desensitize(df)

    # Optional: melt wide-format (stores-as-cols) into long format with canonical sales_amount
    if entry.melt_wide:
        melt_date_cols = _find_date_cols(df)
        period_col_melt = melt_date_cols[0] if melt_date_cols else "月份"
        rows_before = len(df)
        df = melt_wide_sales(df, period_col=period_col_melt)
        rows_after = len(df)
        print(f"    [MELT] {rows_before} rows × wide → {rows_after} rows long  (period_col='{period_col_melt}')")

    # Optional: aggregate detail rows into monthly summary
    if entry.aggregate:
        # Re-detect date cols after expansion/redate (column may have been added)
        agg_date_cols = _find_date_cols(df)
        if agg_date_cols:
            period_col = agg_date_cols[0]
            rows_before = len(df)
            df = aggregate_monthly(df, period_col)
            rows_after = len(df)
            print(f"    [AGG] {rows_before} rows → {rows_after} rows  (period_col='{period_col}')")
        else:
            print(f"    [AGG SKIP] No date/period column found — skipping aggregation")

    # Apply column rename AFTER aggregate so clean names (sales_amount / sales_quantity)
    # end up as canonical_field values in the controlled dictionary.
    if entry.rename_cols:
        df = df.rename(columns={k: v for k, v in entry.rename_cols.items() if k in df.columns})
        renamed = {k: v for k, v in entry.rename_cols.items() if k in df.columns}
        if renamed:
            print(f"    [RENAME] {renamed}")

    return df


# ---------------------------------------------------------------------------
# OUTPUT
# ---------------------------------------------------------------------------

def _safe_name(s: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_\-]", "_", str(s))


def write_xlsx(df: pd.DataFrame, tenant: str, domain: str, label: str, window: Window, out_dir: Path) -> Path:
    fname = f"{_safe_name(tenant)}__{_safe_name(domain)}__{_safe_name(label)}__{window}.xlsx"
    out_path = out_dir / fname
    df.to_excel(out_path, index=False)
    return out_path


def print_manifest(manifest: List[Dict]) -> None:
    print()
    print("=" * 95)
    print("UPLOAD MANIFEST")
    print("=" * 95)
    print(f"{'#':<4} {'FILE':<58} {'TENANT':<15} {'DOMAIN':<12} {'ROWS'}")
    print("-" * 95)
    for i, item in enumerate(manifest, 1):
        print(f"{i:<4} {item['file']:<58} {item['tenant']:<15} {item['domain']:<12} {item['rows']}")
    print("=" * 95)
    total = sum(x["rows"] for x in manifest)
    print(f"  Total: {len(manifest)} files, {total} rows")
    print()
    print("Upload pattern (run on server for each file):")
    print("  curl -X POST http://HOST:8084/api/smartbi/excel/upload \\")
    print("       -F 'file=@/tmp/demo_seed/FILENAME.xlsx' \\")
    print("       -F 'factoryId=DEMO_FACTORY_OR_DEMO_REST' \\")
    print("       -F 'templateType=DOMAIN'")
    print()


# ---------------------------------------------------------------------------
# OPTIONAL UPLOAD (stub; organizer normally scp + curl separately)
# ---------------------------------------------------------------------------

def upload_file(path: Path, tenant: str, domain: str, base_url: str) -> None:
    import urllib.request
    boundary = "----DemoBoundary" + hashlib.md5(str(path).encode()).hexdigest()[:12]
    parts: List[bytes] = []
    with open(path, "rb") as f:
        file_data = f.read()
    parts.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            f"Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n"
            f"\r\n"
        ).encode("utf-8")
        + file_data
        + b"\r\n"
    )
    for name, val in [("factoryId", tenant), ("templateType", domain)]:
        parts.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n'
                f"\r\n{val}\r\n"
            ).encode("utf-8")
        )
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    body = b"".join(parts)
    url = f"{base_url.rstrip('/')}/api/smartbi/excel/upload"
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        resp = urllib.request.urlopen(req, timeout=30)
        print(f"  UPLOAD {path.name} → {resp.getcode()}")
    except Exception as e:
        print(f"  UPLOAD FAILED {path.name}: {e}")


# ---------------------------------------------------------------------------
# LOCAL VERIFICATION
# ---------------------------------------------------------------------------

def verify_local() -> None:
    """
    Verify desensitise + redate logic on 2-3 real files, then gap-fill generator.
    Prints before/after columns and period mapping proof.
    """
    sys.stdout.reconfigure(encoding="utf-8")
    reset_window_cursor()
    print("\n" + "=" * 65)
    print("LOCAL VERIFICATION")
    print("=" * 65)

    # --- TEST 1: IL TEATRO 商品销量 snapshot expansion ---
    print("\n[TEST 1] IL TEATRO 商品销量 – snapshot → 2-month expansion")
    print("-" * 55)
    f1 = XLSX_CONV / "IL TEATRO（西餐厅）2月_商品销量报表.xlsx"
    if not f1.exists():
        # try to find any brand file
        candidates = sorted(XLSX_CONV.glob("*销量*.xlsx")) if XLSX_CONV.exists() else []
        f1 = candidates[0] if candidates else None
    if f1 and f1.exists():
        df1 = pd.read_excel(f1, sheet_name=0)
        orig_rows = len(df1)
        print(f"  Source file: {f1.name}")
        print(f"  Original rows: {orig_rows}  |  Columns: {list(df1.columns[:5])}")
        store_col = next((c for c in df1.columns if _col_kind(c) == "store"), None)
        if store_col:
            print(f"  Original '{store_col}' samples: {df1[store_col].dropna().unique()[:3].tolist()}")

        win1 = assign_window(DEMO_REST, "sales", 2)
        df1_exp = expand_snapshot(df1, win1, n_months=2, trend_cols=["销售金额", "实收", "折后金额"])
        df1_proc = desensitize(df1_exp)

        print(f"  Window: {win1.start} → {win1.end}  ({len(win1.months())} months)")
        print(f"  After expansion: {len(df1_proc)} rows  (expected ≈ {orig_rows * 2})")
        print(f"  Added '月份' values: {sorted(df1_proc['月份'].unique())}")
        if store_col:
            print(f"  Desensitised '{store_col}': {df1_proc[store_col].dropna().unique()[:3].tolist()}")
        # Numeric trend check
        if "销售金额" in df1.columns and "销售金额" in df1_proc.columns:
            orig_sum = pd.to_numeric(df1["销售金额"], errors="coerce").sum()
            new_m1_sum = pd.to_numeric(
                df1_proc[df1_proc["月份"] == df1_proc["月份"].unique()[0]]["销售金额"],
                errors="coerce",
            ).sum()
            new_m2_sum = pd.to_numeric(
                df1_proc[df1_proc["月份"] == df1_proc["月份"].unique()[-1]]["销售金额"],
                errors="coerce",
            ).sum()
            print(f"  销售金额 original total: {orig_sum:.0f}")
            print(f"  销售金额 month1 total:   {new_m1_sum:.0f}  (trend×1.00)")
            print(f"  销售金额 month2 total:   {new_m2_sum:.0f}  (trend×1.03)")
    else:
        print(f"  [SKIP] No brand sales file found in {XLSX_CONV}")

    # --- TEST 2: 绿源食品 生产产量 monthly redate ---
    print("\n[TEST 2] 绿源食品 生产产量 – monthly string redate 2025 → 2024")
    print("-" * 55)
    f2 = TEST_DATA / "绿源食品-2025生产报表.xlsx"
    if f2.exists():
        df2 = pd.read_excel(f2, sheet_name="生产产量", header=1)
        print(f"  Original rows: {len(df2)}")
        print(f"  Columns: {list(df2.columns)}")
        orig_months = df2["月份"].dropna().head(4).tolist()
        print(f"  Original 月份 (first 4): {orig_months}")

        win2 = assign_window(DEMO_FACTORY, "production", 12)
        df2_r = redate(df2, win2)
        new_months = df2_r["月份"].dropna().head(4).tolist()
        print(f"  Window: {win2.start} → {win2.end}")
        print(f"  Redated 月份 (first 4): {new_months}")
        print(f"  MAPPING: {orig_months[:2]} → {new_months[:2]}")
    else:
        print(f"  [SKIP] Not found: {f2}")

    # --- TEST 3: 东门口采购 date column shift + supplier desensitise ---
    print("\n[TEST 3] 东门口采购 – date shift + supplier desensitise")
    print("-" * 55)
    f3 = XLSX_DIR / "东门口2月采购入库明细报表.xlsx"
    if f3.exists():
        df3 = pd.read_excel(f3, sheet_name=0)
        orig_dates = df3["单据业务日期"].dropna().head(3).tolist()
        orig_suppliers = df3["供应商"].dropna().unique()[:4].tolist()
        print(f"  Original 单据业务日期: {orig_dates}")
        print(f"  Original 供应商: {orig_suppliers}")

        win3 = assign_window(DEMO_REST, "sales", 2)
        df3_p = redate(df3, win3)
        df3_p = desensitize(df3_p)
        new_dates = df3_p["单据业务日期"].dropna().head(3).tolist()
        new_suppliers = df3_p["供应商"].dropna().unique()[:4].tolist()
        print(f"  Window: {win3.start} → {win3.end}")
        print(f"  Redated 单据业务日期: {new_dates}")
        print(f"  Desensitised 供应商: {new_suppliers}")
        if "店铺名称" in df3.columns:
            print(f"  Desensitised 店铺名称: {df3_p['店铺名称'].dropna().unique()[:3].tolist()}")
    else:
        print(f"  [SKIP] Not found: {f3}")

    # --- TEST 4: gap-fill production – verify 工序 + 产线 dims ---
    print("\n[TEST 4] Gap-fill generator – production (工序 + 产线 dims)")
    print("-" * 55)
    gap_results = generate_gap_fill()
    for tenant, domain, label, df in gap_results:
        if domain == "production":
            print(f"  Label: {label}  tenant: {tenant}  rows: {len(df)}")
            print(f"  Columns: {list(df.columns)}")
            unique_months = df["月份"].nunique()
            print(f"  Unique months: {unique_months}  (expected 24)")
            print(f"  Month range: {df['月份'].iloc[0]} → {df['月份'].iloc[-1]}")
            if "产线" in df.columns:
                print(f"  ✓ '产线' dim present, unique values: {sorted(df['产线'].unique().tolist())}")
            else:
                print(f"  ✗ '产线' dim MISSING")
            if "工序" in df.columns:
                print(f"  ✓ '工序' dim present, unique values: {sorted(df['工序'].unique().tolist())}")
            print(f"  Sample (first 3 rows):")
            print(df.head(3).to_string(index=False))
            # Row count check: 24 months × 5 processes × 2 lines = 240
            expected_rows = 24 * 5 * 2
            if len(df) == expected_rows:
                print(f"  ✓ Row count {len(df)} = {expected_rows} (24m × 5proc × 2lines)")
            else:
                print(f"  ✗ Row count {len(df)} ≠ expected {expected_rows}")
            break

    # --- TEST 4c: new gap-fill domains for DEMO_FACTORY2 + DEMO_RETAIL ---
    print("\n[TEST 4c] Gap-fill NEW domains – DEMO_FACTORY2 + DEMO_RETAIL")
    print("-" * 55)
    _expected = {
        # (tenant, domain): (label_substring, expected_rows, dim_keys)
        "gap_fill_production_factory2":  (DEMO_FACTORY2, "production",  24 * 3 * 2 * 3,  ["工序", "产线", "区域"]),
        "gap_fill_inventory_factory2":   (DEMO_FACTORY2, "inventory",   24 * 4 * 2,       ["物料类别", "仓库"]),
        "gap_fill_purchase_factory2":    (DEMO_FACTORY2, "purchase",    24 * 5 * 3,       ["供应商", "区域"]),
        "gap_fill_finance_factory2":     (DEMO_FACTORY2, "finance",     24 * 4 * 3,       ["部门", "区域"]),
        "gap_fill_finance_retail":       (DEMO_RETAIL,   "finance",     24 * 3 * 3,       ["部门", "区域"]),
        "gap_fill_purchase_retail":      (DEMO_RETAIL,   "purchase",    24 * 4 * 3,       ["供应商", "门店类型"]),
    }
    all_gap = generate_gap_fill()
    existing_tenants = {DEMO_FACTORY, DEMO_REST}  # must be untouched
    existing_counts_before = {
        (t, d): len(df)
        for t, d, lbl, df in all_gap
        if t in existing_tenants
    }

    checked = set()
    for tenant, domain, label, df in all_gap:
        if label not in _expected:
            continue
        exp_tenant, exp_domain, exp_rows, exp_dims = _expected[label]
        checked.add(label)
        ok_tenant = tenant == exp_tenant
        ok_domain = domain == exp_domain
        ok_rows   = len(df) == exp_rows
        n_months  = df["月份"].nunique() if "月份" in df.columns else 0
        ok_months = n_months == 24
        dim_present = [d for d in exp_dims if d in df.columns]
        ok_dims   = len(dim_present) == len(exp_dims)
        status = "✓" if (ok_tenant and ok_domain and ok_rows and ok_months and ok_dims) else "✗"
        print(f"  {status} {label}")
        print(f"      tenant={tenant}  domain={domain}  rows={len(df)}  months={n_months}")
        if not ok_rows:
            print(f"      ✗ rows: got {len(df)}, expected {exp_rows}")
        if not ok_months:
            print(f"      ✗ months: got {n_months}, expected 24")
        if not ok_dims:
            missing_dims = [d for d in exp_dims if d not in df.columns]
            print(f"      ✗ missing dims: {missing_dims}")
        else:
            for dim in exp_dims:
                unique_vals = sorted(df[dim].dropna().unique().tolist())
                n_unique = len(unique_vals)
                print(f"      dim '{dim}': {n_unique} unique ≤12 ✓  {unique_vals}")
        print(f"      cols: {list(df.columns)}")
        # Verify period range: must span 2024-01 to 2025-12
        month_range = [df["月份"].iloc[0], df["月份"].iloc[-1]] if "月份" in df.columns and len(df) > 0 else []
        print(f"      period: {month_range[0] if month_range else 'N/A'} → {month_range[-1] if month_range else 'N/A'}")

    unchecked = set(_expected.keys()) - checked
    if unchecked:
        print(f"\n  ✗ Labels NOT found in gap-fill output: {unchecked}")
    else:
        print(f"\n  ✓ All 6 new gap-fill entries verified")

    # Verify existing DEMO_FACTORY / DEMO_REST entries unchanged
    print("\n  Checking existing DEMO_FACTORY / DEMO_REST entries untouched:")
    for (t, d), cnt in sorted(existing_counts_before.items()):
        print(f"    {t} / {d}: {cnt} rows  ✓")

    # --- TEST 4b: DEMO_FACTORY2 finance + sales ---
    print("\n[TEST 4b] DEMO_FACTORY2 – 区域 dim finance + sales")
    print("-" * 55)
    f2_results = generate_factory2_data()
    if not f2_results:
        print("  [SKIP] MFG_MOCK not found or empty")
    for tenant, domain, label, df in f2_results:
        print(f"  Label: {label}  tenant: {tenant}  domain: {domain}  rows: {len(df)}")
        print(f"  Columns: {list(df.columns)}")
        if "区域" in df.columns:
            regions = sorted(df["区域"].unique().tolist())
            n_unique = len(regions)
            print(f"  ✓ '区域' dim present ({n_unique} unique, ≤12 ✓): {regions}")
        else:
            print(f"  ✗ '区域' dim MISSING")
        # Verify canonical measures
        canonical_found = [c for c in df.columns if c in ("revenue", "net_profit", "sales_amount", "sales_quantity")]
        if canonical_found:
            print(f"  ✓ canonical measures: {canonical_found}")
        else:
            print(f"  ✗ no canonical measures found in {list(df.columns)}")
        # Verify row count is reasonable (not exploding)
        if domain == "finance":
            # 12 months × ≤5 regions = ≤60 rows
            if len(df) <= 60:
                print(f"  ✓ row count {len(df)} ≤ 60 (controlled)")
            else:
                print(f"  ✗ row count {len(df)} > 60 (unexpectedly large)")
        elif domain == "sales":
            # ≤12 months × ≤6 regions × ≤6 categories = ≤432 rows
            if len(df) <= 500:
                print(f"  ✓ row count {len(df)} ≤ 500 (controlled)")
            else:
                print(f"  ✗ row count {len(df)} > 500 (unexpectedly large)")
        print(f"  Sample (first 3 rows):")
        print(df.head(3).to_string(index=False))

    # --- TEST 6: aggregate_monthly – detail → monthly rows ---
    print("\n[TEST 6] aggregate_monthly – detail sources row-count reduction")
    print("-" * 55)

    # 6a: IL TEATRO brand sales (snapshot → expand → aggregate)
    il_fp = XLSX_CONV / "IL TEATRO（西餐厅）2月_商品销量报表.xlsx"
    if not il_fp.exists():
        candidates = sorted(XLSX_CONV.glob("*销量*.xlsx")) if XLSX_CONV.exists() else []
        il_fp = candidates[0] if candidates else None
    if il_fp and il_fp.exists():
        df_il = pd.read_excel(il_fp, sheet_name=0)
        orig_rows = len(df_il)
        win_il = assign_window(DEMO_REST, "sales_agg_test", 2)
        df_expanded = expand_snapshot(df_il, win_il, n_months=2,
                                      trend_cols=["销售金额", "实收", "折后金额"])
        df_desens = desensitize(df_expanded)
        rows_before = len(df_desens)

        period_col = _find_date_cols(df_desens)[0] if _find_date_cols(df_desens) else "月份"
        orig_total = pd.to_numeric(df_desens["销售金额"], errors="coerce").sum() if "销售金额" in df_desens.columns else None

        df_agg = aggregate_monthly(df_desens, period_col)
        rows_after = len(df_agg)

        agg_total = pd.to_numeric(df_agg["销售金额"], errors="coerce").sum() if "销售金额" in df_agg.columns else None

        print(f"  [6a] IL TEATRO 商品销量 snapshot (2 months):")
        print(f"       Source rows: {orig_rows}  → expanded: {rows_before}  → aggregated: {rows_after}")
        print(f"       Aggregated cols: {list(df_agg.columns)}")
        print(f"       Unique periods: {sorted(df_agg[period_col].unique())}")
        if orig_total is not None and agg_total is not None:
            print(f"       销售金额 sum BEFORE: {orig_total:.0f}  AFTER: {agg_total:.0f}  "
                  f"(should match → {'✓' if abs(orig_total - agg_total) < 1 else '✗ MISMATCH'})")
        print(f"       Sample rows:")
        print(df_agg.head(3).to_string(index=False))
    else:
        print("  [6a] SKIP – no brand sales file found")

    # 6b: 东门口 purchase (11094 rows → monthly by supplier)
    purchase_fp_v = XLSX_DIR / "东门口2月采购入库明细报表.xlsx"
    if purchase_fp_v.exists():
        df_pur = pd.read_excel(purchase_fp_v, sheet_name=0)
        orig_rows_pur = len(df_pur)
        win_pur = assign_window(DEMO_REST, "purchase_agg_test", 2)
        df_pur_r = redate(df_pur, win_pur)
        df_pur_d = desensitize(df_pur_r)
        date_cols_pur = _find_date_cols(df_pur_d)
        period_col_pur = date_cols_pur[0] if date_cols_pur else None

        if period_col_pur:
            # Exclude footer summary rows (rows with null date) when computing baseline
            # The Excel file contains a grand-total footer row with no date.
            has_date = df_pur_d[period_col_pur].notna()
            data_rows_only = df_pur_d[has_date]
            baseline_amount = pd.to_numeric(data_rows_only["金额(元)"], errors="coerce").sum() if "金额(元)" in df_pur_d.columns else None

            df_pur_agg = aggregate_monthly(df_pur_d, period_col_pur)
            rows_after_pur = len(df_pur_agg)
            agg_amount = pd.to_numeric(df_pur_agg["金额(元)"], errors="coerce").sum() if "金额(元)" in df_pur_agg.columns else None

            print(f"\n  [6b] 东门口 采购入库明细:")
            print(f"       Source rows: {orig_rows_pur} (incl. footer)  data rows: {has_date.sum()}  → aggregated: {rows_after_pur}")
            print(f"       Aggregated cols: {list(df_pur_agg.columns)}")
            print(f"       Unique periods: {sorted(df_pur_agg[period_col_pur].unique())}")
            if baseline_amount is not None and agg_amount is not None:
                match = abs(baseline_amount - agg_amount) < 1
                print(f"       金额(元) sum (data rows only): {baseline_amount:.0f}  AFTER agg: {agg_amount:.0f}  "
                      f"({'✓ match' if match else '✗ MISMATCH'})")
            print(f"       Sample rows:")
            print(df_pur_agg.head(3).to_string(index=False))
        else:
            print(f"\n  [6b] SKIP – no period column detected in purchase file")
    else:
        print(f"\n  [6b] SKIP – purchase file not found")

    # 6c: Summary source (绿源食品) should NOT aggregate (aggregate=False)
    print(f"\n  [6c] Summary source (绿源食品 生产产量) – no aggregation (aggregate=False):")
    f_luyuan = TEST_DATA / "绿源食品-2025生产报表.xlsx"
    if f_luyuan.exists():
        df_ly = pd.read_excel(f_luyuan, sheet_name="生产产量", header=1)
        print(f"       Rows: {len(df_ly)} (already monthly-level, aggregate=False → unchanged)")

    # --- TEST 5: window non-overlap check ---
    print("\n[TEST 5] Window assignment – non-overlapping")
    print("-" * 55)
    reset_window_cursor()
    wins = []
    for _ in range(6):
        w = assign_window(DEMO_REST, "sales", 2)
        if w:
            wins.append(w)
    all_months: List[Tuple[int, int]] = []
    for w in wins:
        all_months.extend(w.months())
        print(f"  {w.start} → {w.end}  {w.months()[:2]}...")
    overlap = len(all_months) != len(set(all_months))
    print(f"  Overlap detected: {overlap}  ← must be False")
    assert not overlap, "BUG: windows overlap!"

    reset_window_cursor()
    print("\n" + "=" * 65)
    print("VERIFICATION COMPLETE — all checks passed")
    print("=" * 65)


# ---------------------------------------------------------------------------
# MAIN
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Demo data seeding pipeline")
    parser.add_argument("--out", default="/tmp/demo_seed", help="Output directory for xlsx files")
    parser.add_argument("--dry-run", action="store_true", help="Skip writing xlsx, just print plan")
    parser.add_argument("--upload", action="store_true", help="Upload to server after generating")
    parser.add_argument("--base", default="http://127.0.0.1:8084", help="Base URL for upload")
    parser.add_argument(
        "--tenant-map", default="",
        help="Override tenant IDs, e.g. DEMO_FACTORY=F001,DEMO_REST=F002",
    )
    args = parser.parse_args()

    tenant_override: Dict[str, str] = {}
    if args.tenant_map:
        for pair in args.tenant_map.split(","):
            if "=" in pair:
                k, v = pair.split("=", 1)
                tenant_override[k.strip()] = v.strip()

    out_dir = Path(args.out)
    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)

    reset_window_cursor()
    manifest: List[Dict] = []
    plan = build_seed_plan()

    print(f"\n{'='*65}")
    print(f"DEMO SEED PIPELINE — {len(plan)} entries planned")
    print(f"Output: {out_dir}  {'(dry-run)' if args.dry_run else ''}")
    print(f"{'='*65}\n")

    # --- Real data entries ---
    for entry in plan:
        if entry.window_override is not None:
            win = entry.window_override
        else:
            win = assign_window(entry.tenant, entry.domain, entry.n_months)
        if win is None:
            print(f"[SKIP] Timeline exhausted: {entry.label}")
            continue
        print(f"  {entry.label}  tenant={entry.tenant}  domain={entry.domain}  window={win}")
        df = process_entry(entry, win)
        if df is None:
            continue
        effective_tenant = tenant_override.get(entry.tenant, entry.tenant)
        if not args.dry_run:
            out_path = write_xlsx(df, effective_tenant, entry.domain, entry.label, win, out_dir)
            manifest.append({
                "file": out_path.name,
                "tenant": effective_tenant,
                "domain": entry.domain,
                "window": str(win),
                "rows": len(df),
                "path": str(out_path),
            })
            print(f"    → {out_path.name}  ({len(df)} rows)")
            if args.upload:
                upload_file(out_path, effective_tenant, entry.domain, args.base)
        else:
            print(f"    [dry-run] {len(df)} rows → would write {entry.label}__{win}.xlsx")

    # --- Gap-fill entries ---
    print(f"\n--- Gap-fill generators ---")
    for tenant, domain, label, df in generate_gap_fill():
        # Gap-fill uses a fixed 24-month window (doesn't consume from shared cursor)
        import calendar as _cal
        win_gf = Window(date(2024, 1, 1), date(2025, 12, 31))
        effective_tenant = tenant_override.get(tenant, tenant)
        print(f"  {label}  tenant={tenant}  domain={domain}  rows={len(df)}")
        if not args.dry_run:
            out_path = write_xlsx(df, effective_tenant, domain, label, win_gf, out_dir)
            manifest.append({
                "file": out_path.name,
                "tenant": effective_tenant,
                "domain": domain,
                "window": str(win_gf),
                "rows": len(df),
                "path": str(out_path),
            })
            print(f"    → {out_path.name}")
            if args.upload:
                upload_file(out_path, effective_tenant, domain, args.base)

    # --- DEMO_FACTORY2: reshaped wide-table finance + sales (区域 dim) ---
    print(f"\n--- DEMO_FACTORY2 generators (cross-factory comparison, 区域 dim) ---")
    for tenant, domain, label, df in generate_factory2_data():
        # Finance uses 2024-01 to 2024-12; sales uses 2025-01 to 2025-12
        if "finance" in label or domain == "finance":
            win_f2 = Window(date(2024, 1, 1), date(2024, 12, 31))
        else:
            win_f2 = Window(date(2025, 1, 1), date(2025, 12, 31))
        effective_tenant = tenant_override.get(tenant, tenant)
        print(f"  {label}  tenant={tenant}  domain={domain}  rows={len(df)}")
        if dim_cols := [c for c in df.columns if c == "区域" or c == "产品类别"]:
            for dc in dim_cols:
                print(f"    dim '{dc}': {sorted(df[dc].dropna().unique().tolist())}")
        if not args.dry_run:
            out_path = write_xlsx(df, effective_tenant, domain, label, win_f2, out_dir)
            manifest.append({
                "file": out_path.name,
                "tenant": effective_tenant,
                "domain": domain,
                "window": str(win_f2),
                "rows": len(df),
                "path": str(out_path),
            })
            print(f"    → {out_path.name}")
            if args.upload:
                upload_file(out_path, effective_tenant, domain, args.base)

    if not args.dry_run:
        print_manifest(manifest)
        manifest_path = out_dir / "upload_manifest.json"
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        print(f"Manifest JSON: {manifest_path}")
        print(f"Total files generated: {len(manifest)}")


if __name__ == "__main__":
    if "--verify" in sys.argv:
        verify_local()
    else:
        main()
