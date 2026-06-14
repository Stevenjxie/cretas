"""Corpus-seeding harness for chart-insight distillation (M4 / offline flywheel).

Bootstrap ``smart_bi_distillation_samples`` by synthesising DIVERSE business
scenarios, running each through the EXISTING claims-pinning pipeline, and
persisting only ACCEPTED outputs.

Why synthetic:
  * Privacy-safe — no real tenant revenue, no cross-tenant memorisation.
  * Comprehensive bucket coverage — designed to span all family × bucket × domain
    combinations in a controlled, reproducible way.
  * Unblocks M4 (in-house model training) without waiting for organic usage.

Usage:
    # Dry-run (no LLM, no DB — test locally):
    cd backend/python
    python -m scripts.seed_chart_insight_corpus --n 20 --dry-run

    # Real-shape sampling (uses actual prod-data distributions, then anon labels + jitter):
    python -m scripts.seed_chart_insight_corpus --n 500 --real-shapes

    # Gap-fill: only generate for buckets BELOW floor (default 200/family):
    python -m scripts.seed_chart_insight_corpus --n 1000 --gap-fill --floor 200

    # Real seeding run (on the server, requires SMARTBI_PROD_DSN or env vars):
    python -m scripts.seed_chart_insight_corpus --n 500

Environment variables (real run):
    SMARTBI_PROD_DSN   — asyncpg DSN e.g. postgresql://user:pass@host/smartbi_prod_db
                         If absent, falls back to settings.postgres_url (smartbi_db).
    POSTGRES_HOST / POSTGRES_PORT / POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD
                       — used by get_settings() / Settings.postgres_url when
                         SMARTBI_PROD_DSN is not set.
    LLM_API_KEY / DASHSCOPE_API_KEY
                       — required for real LLM calls (passed through llm_router).

Design notes:
  * Budget bypass: this is offline seeding, NOT user traffic. We instantiate a
    _NoBudgetTracker (stub) that never blocks, so every scenario gets a real
    LLM call without depleting per-factory request quotas.
  * We call _call_llm + _validate_claims directly on a ChartInsightService
    instance whose budget_tracker is the stub.  The RBAC cross-tenant guard is
    deliberately bypassed at the service level by making jwt_factory_id equal
    ctx.factory_id (sentinel IDs SEED_R / SEED_F).
  * Only ACCEPTED outputs (validate_claims returned non-None, ¥ gate passed)
    are persisted.  Rejected paths are counted but not stored.
  * Real-shape sampling (--real-shapes): queries gold/finance tables for actual
    series distributions, extracts VALUE-SHAPE (relative proportions + item count),
    classifies to a bucket family, then pairs with anonymised labels (from the
    existing label pools — NOT real entity names) and applies ±10-15% jitter.
    If the DB query fails or returns no rows, falls back transparently to the
    curated shape library (documented below).  Privacy is guaranteed: real entity
    names are never stored — only their proportional structure is reused.
  * Rejection-reason counters: the runner now tracks per-reason rejection counts
    (claims-empty / recompute-mismatch / adjacency-fail / ¥-gate / llm-error)
    and prints the breakdown at the end of every run.
  * Gap-fill mode (--gap-fill): queries the corpus census per bucket, then only
    synthesises scenarios for (business_type × family) buckets that are BELOW
    --floor (default 200).  Buckets at/above floor are skipped.  Logs what it is
    filling vs skipping.
"""
from __future__ import annotations
import types as _types

import argparse
import asyncio
import json
import logging
import os
import random
import sys
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Path bootstrap so the script works both as ``python -m scripts.seed_*``
# (cwd = backend/python) and as a plain ``python scripts/seed_*.py``.
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
if _PYTHON_ROOT not in sys.path:
    sys.path.insert(0, _PYTHON_ROOT)

# ---------------------------------------------------------------------------
# Selective import shim — avoid triggering smartbi/services/__init__.py
#
# smartbi/services/__init__.py eagerly imports excel_parser, field_detector,
# etc., which in turn use `from services.data_feature_analyzer import ...`
# (a relative path that only works inside the running server).  This fails
# in standalone script context.
#
# Fix: pre-register a minimal stub for `smartbi.services` in sys.modules
# BEFORE Python walks up the package chain, so that `import
# smartbi.services.insights.chart_insight_service` resolves the submodule
# directly without executing the parent __init__.
#
# We do the same for `smartbi.services.insights` whose __init__.py is safe
# (empty or minimal), but the stub is there for symmetry.
# ---------------------------------------------------------------------------


def _ensure_stub(name: str) -> None:
    """Insert an empty module stub for `name` if not already in sys.modules."""
    if name not in sys.modules:
        parts = name.split(".")
        parent_name = ".".join(parts[:-1])
        if parent_name and parent_name not in sys.modules:
            _ensure_stub(parent_name)
        stub = _types.ModuleType(name)
        stub.__path__ = [os.path.join(_PYTHON_ROOT, *name.split("."))]  # type: ignore[attr-defined]
        stub.__package__ = name
        sys.modules[name] = stub
        # Also attach as attribute on parent
        if parent_name:
            parent = sys.modules.get(parent_name)
            if parent is not None:
                setattr(parent, parts[-1], stub)


# Ensure the chain smartbi → smartbi.services → smartbi.services.insights
# is pre-registered as stubs so Python won't run their __init__.py files.
# Only pre-register if not already loaded (real server context has them loaded).
for _pkg in ("smartbi", "smartbi.services", "smartbi.services.insights"):
    _ensure_stub(_pkg)

# Now the actual imports — these load only the targeted submodules.
import importlib as _importlib  # noqa: E402


def _import_from(module_path: str):
    """Import a module by dotted path, honouring the sys.modules stubs above."""
    return _importlib.import_module(module_path)


_chart_svc_mod = _import_from("smartbi.services.insights.chart_insight_service")
_distill_mod = _import_from("smartbi.services.distillation_capture")

ChartInsightContext = _chart_svc_mod.ChartInsightContext
_validate_claims = _chart_svc_mod._validate_claims
_corpus_input_text = _chart_svc_mod._corpus_input_text
_map_domain = _chart_svc_mod._map_domain
_ABSOLUTE_AMOUNT_RE = _chart_svc_mod._ABSOLUTE_AMOUNT_RE
_SYSTEM_PROMPT = _chart_svc_mod._SYSTEM_PROMPT
ChartInsightService = _chart_svc_mod.ChartInsightService
persist_distillation_sample = _distill_mod.persist_distillation_sample

logger = logging.getLogger("seed_chart_insight_corpus")

# ---------------------------------------------------------------------------
# Fixed seed for reproducibility — every run with the same seed produces the
# same scenario list so Opus can re-run and compare results.
# ---------------------------------------------------------------------------
RANDOM_SEED = 42

# ---------------------------------------------------------------------------
# Label pools
# ---------------------------------------------------------------------------

_RESTAURANT_POOLS: Dict[str, List[str]] = {
    "channel":    ["堂食", "外卖", "美团", "饿了么", "自提"],
    "store":      ["锦里店", "人民路店", "春熙店", "宽窄巷店", "建设路店"],
    "dish":       ["招牌卤猪蹄", "夫妻肺片", "蒜泥白肉", "麻婆豆腐", "水煮鱼", "回锅肉"],
    "category":   ["凉菜", "热菜", "主食", "饮品", "小吃"],
}

_FACTORY_POOLS: Dict[str, List[str]] = {
    "supplier":   ["鑫源", "德利", "丰收", "广发"],
    "product":    ["精装A", "简装B", "礼盒C"],
    "department": ["生产部", "质检部", "仓储部", "采购部"],
    "category":   ["原料", "人工", "制造费用", "水电"],
}

_MONTH_LABELS: List[str] = ["1月", "2月", "3月", "4月", "5月", "6月",
                            "7月", "8月", "9月", "10月", "11月", "12月"]

# Sentinel factory IDs — distinguishes synthetic rows from organic rows.
_SENTINEL_RESTAURANT = "SEED_R"
_SENTINEL_FACTORY = "SEED_F"

# ---------------------------------------------------------------------------
# Curated shape library — realistic distributions derived from business data.
#
# Each entry is a list of RELATIVE WEIGHTS (they don't need to sum to 1;
# _apply_shape_jitter normalises and rescales).  The library is grouped by
# (family, bucket index) so _sample_shapes_from_library can pick a shape
# matching the target bucket family.
#
# These shapes encode *structural* knowledge:
#   - proportion: one dominant segment with a long tail (realistic for Chinese
#     F&B / factory cost breakdowns)
#   - ranking: steep Pareto-like descent (top store/SKU often 3× bottom)
#   - comparison: near-parity, one-side-dominant, or big-gap pairs
#   - kpi: actual/target ratio spans over/under achievement zones
#   - trend: realistic season-like wobble rather than pure geometric walk
# ---------------------------------------------------------------------------

_SHAPE_LIBRARY: Dict[str, List[List[float]]] = {
    # Proportion shapes — relative weights only; normalise before use
    "proportion": [
        [52.0, 28.0, 20.0],                       # top-heavy 3-way split
        [45.0, 35.0, 20.0],                       # balanced 3-way
        [38.0, 27.0, 20.0, 15.0],                 # flat 4-way
        [62.0, 22.0, 16.0],                       # single dominant (外卖 heavy)
        [72.0, 16.0, 12.0],                       # one dominant 2 tail
        [48.0, 30.0, 14.0, 8.0],                  # classic 80/20 ish
        [55.0, 25.0, 12.0, 8.0],                  # tier-3 restaurant channel
        [33.0, 31.0, 22.0, 14.0],                 # very flat 4-way
    ],
    # Ranking shapes — should be sorted descending; _gen_real_shape_values will sort
    "ranking": [
        [430_000, 280_000, 190_000, 120_000, 60_000],   # steep Pareto
        [310_000, 260_000, 200_000, 140_000, 90_000],   # mild descent
        [520_000, 180_000, 160_000, 80_000],             # top-heavy 4
        [250_000, 240_000, 230_000],                     # near-flat 3
        [600_000, 350_000, 200_000, 90_000, 30_000],    # long tail
        [380_000, 370_000, 120_000, 110_000, 50_000],   # two leaders
        [200_000, 190_000, 180_000, 50_000, 20_000],    # head-and-pack
    ],
    # Comparison shapes — always pairs; index 0 = series A, index 1 = series B
    "comparison": [
        [320_000, 180_000],    # positive gap ~1.8×
        [250_000, 240_000],    # near parity
        [150_000, 300_000],    # reversed rank
        [600_000, 140_000],    # large gap ~4×
        [400_000, 420_000],    # very tight parity
        [550_000, 200_000],    # dominant lead
    ],
    # KPI shapes — [actual, target]; ratio encodes achievement zone
    "kpi": [
        [1_150_000, 1_000_000],   # over 100% (115%)
        [950_000,   1_000_000],   # 90-100%
        [780_000,   1_000_000],   # under 90% (~78%)
        [1_300_000, 1_000_000],   # cost overrun (130%)
        [1_050_000, 1_000_000],   # just over 100%
        [880_000,   1_000_000],   # borderline under
    ],
    # Trend shapes — 12-month absolute series; will be jittered + rescaled
    "trend": [
        # Rising
        [80_000, 85_000, 90_000, 96_000, 103_000, 110_000,
         118_000, 125_000, 132_000, 141_000, 150_000, 160_000],
        # Falling
        [160_000, 152_000, 143_000, 136_000, 128_000, 120_000,
         112_000, 104_000, 97_000, 90_000, 84_000, 78_000],
        # Volatile
        [100_000, 130_000, 95_000, 125_000, 90_000, 135_000,
         105_000, 125_000, 95_000, 120_000, 100_000, 130_000],
        # Flat / seasonal
        [100_000, 105_000, 102_000, 98_000, 101_000, 104_000,
         99_000, 103_000, 100_000, 102_000, 98_000, 101_000],
        # S-curve growth (restaurant opening year)
        [20_000, 35_000, 60_000, 90_000, 120_000, 145_000,
         165_000, 180_000, 192_000, 200_000, 205_000, 208_000],
    ],
}

# ---------------------------------------------------------------------------
# Real-shape extraction helpers
# ---------------------------------------------------------------------------

# SQL to sample raw series from the gold / finance tables.
# Returns rows with relative VALUE shapes that we can anonymise + jitter.
# Falls back gracefully if tables don't exist or are empty.
_REAL_SHAPE_SQL_RESTAURANT = """
    SELECT
        dimension_value,
        SUM(metric_value::numeric)  AS total_value
    FROM (
        -- channel breakdown from restaurant gold
        SELECT channel AS dimension_value, gross_revenue AS metric_value
        FROM   restaurant_daily_gold
        WHERE  factory_id NOT IN ('SEED_R', 'SEED_F')
          AND  metric_value IS NOT NULL
          AND  metric_value::numeric > 0
        ORDER  BY recorded_date DESC
        LIMIT  10000
    ) sub
    GROUP BY dimension_value
    HAVING SUM(metric_value::numeric) > 0
    ORDER BY total_value DESC
    LIMIT 10
"""

_REAL_SHAPE_SQL_FACTORY = """
    SELECT
        category  AS dimension_value,
        SUM(ABS(total_cost::numeric))  AS total_value
    FROM  smart_bi_finance_data
    WHERE factory_id NOT IN ('SEED_R', 'SEED_F')
      AND total_cost IS NOT NULL
      AND total_cost != 0
      AND record_date >= CURRENT_DATE - INTERVAL '90 days'
    GROUP BY category
    HAVING SUM(ABS(total_cost::numeric)) > 0
    ORDER BY total_value DESC
    LIMIT 10
"""

# Census query for gap-fill: count synthetic-seed rows per (business_type, family)
# We extract family from the metadata.data_pattern or fall back to task_type.
# Simpler approach: count rows that are synthetic_seed per business_type.
_GAP_FILL_SQL = """
    SELECT
        business_type,
        CASE
            WHEN metadata->>'data_pattern' LIKE 'proportion%' THEN 'proportion'
            WHEN metadata->>'data_pattern' LIKE 'ranking%'    THEN 'ranking'
            WHEN metadata->>'data_pattern' LIKE 'comparison%' THEN 'comparison'
            WHEN metadata->>'data_pattern' LIKE 'kpi%'        THEN 'kpi'
            WHEN metadata->>'data_pattern' LIKE 'trend%'      THEN 'trend'
            ELSE 'other'
        END                AS family,
        COUNT(*)           AS total_count
    FROM smart_bi_distillation_samples
    WHERE source = 'chart_insight'
      AND (
          metadata->>'source_kind' = 'synthetic_seed'
          OR (metadata->>'seeded')::boolean = true
          OR factory_id IN ('SEED_R', 'SEED_F')
      )
    GROUP BY business_type, family
"""


def _apply_shape_jitter(
    values: List[float], n_target: int, rng: random.Random,
    jitter_frac: float = 0.125,
) -> List[float]:
    """Take a shape (list of weights), resize to n_target items, rescale to a
    realistic absolute range, then add ±jitter per item.

    Steps:
    1. If len(values) > n_target: keep the first n_target (already sorted by
       importance for ranking; random selection for others).
    2. If len(values) < n_target: repeat the last value to pad.
    3. Normalise to sum = 1.
    4. Choose a random base in [50_000, 600_000].
    5. Multiply each weight by base and add ±jitter_frac noise.
    6. Ensure all values > 0.
    """
    if not values:
        return [rng.uniform(50_000, 200_000) for _ in range(max(n_target, 1))]

    # Resize
    v = list(values)
    if len(v) > n_target:
        v = v[:n_target]
    while len(v) < n_target:
        v.append(v[-1] * rng.uniform(0.5, 1.0))

    # Normalise
    s = sum(v)
    if s <= 0:
        s = 1.0
    weights = [x / s for x in v]

    # Scale to absolute range
    base = rng.uniform(50_000, 600_000)
    result: List[float] = []
    for w in weights:
        raw = w * base
        noise = raw * rng.uniform(-jitter_frac, jitter_frac)
        result.append(max(1_000.0, raw + noise))

    return [round(x) for x in result]


def _sample_shapes_from_library(
    family: str, n_items: int, rng: random.Random,
) -> Tuple[List[float], str]:
    """Return (values, shape_source='library') from the curated shape library."""
    shapes = _SHAPE_LIBRARY.get(family, _SHAPE_LIBRARY["proportion"])
    raw_shape = rng.choice(shapes)
    values = _apply_shape_jitter(list(raw_shape), n_items, rng)
    return values, "library"


async def _fetch_real_shapes(
    pool, domain: str,
) -> Optional[List[float]]:
    """Query the DB for a real channel/category series shape.

    Returns a list of raw absolute values (unsorted, unnormalised) if successful,
    or None if the table doesn't exist / returns no rows / any error.
    """
    if pool is None:
        return None
    sql = (_REAL_SHAPE_SQL_RESTAURANT if domain == "restaurant"
           else _REAL_SHAPE_SQL_FACTORY)
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql)
        values = [float(r["total_value"]) for r in rows
                  if r["total_value"] is not None and float(r["total_value"]) > 0]
        return values if len(values) >= 2 else None
    except Exception as exc:
        logger.debug("_fetch_real_shapes query failed (%s) — using library", exc)
        return None


def _classify_shape_to_bucket(values: List[float]) -> str:
    """Given a series of absolute values, classify to a proportion/ranking/comparison
    family by analysing the top-share of the largest item."""
    if len(values) < 2:
        return "proportion"
    total = sum(values)
    if total <= 0:
        return "proportion"
    top_share = max(values) / total * 100
    if top_share >= 65:
        return "proportion"   # highly concentrated → proportion
    elif top_share >= 40:
        return "ranking"       # moderate concentration → ranking
    else:
        return "comparison"    # near-even → comparison


async def _gen_real_shape_values(
    pool, family: str, n_items: int, rng: random.Random, domain: str,
) -> Tuple[List[float], str]:
    """Generate values using a real series shape from the DB when possible.

    Returns (values, shape_source) where shape_source is 'real' or 'library'.

    For trend and kpi families, real DB shapes are unlikely to be directly
    applicable, so we always fall back to the library for those.
    """
    # trend and kpi have structural semantics beyond simple proportions
    if family in ("trend", "kpi"):
        return _sample_shapes_from_library(family, n_items, rng)

    real_values = await _fetch_real_shapes(pool, domain)
    if real_values and len(real_values) >= 2:
        # Sort descending (real DB values come sorted, but just in case)
        real_values_sorted = sorted(real_values, reverse=True)

        if family == "proportion":
            # Apply jitter + resize — the real shape defines the distribution
            values = _apply_shape_jitter(real_values_sorted, n_items, rng)
            # Shuffle so top item isn't always first (realistic PIE)
            rng.shuffle(values)
            return values, "real"
        elif family == "ranking":
            # Keep sorted descending
            values = _apply_shape_jitter(real_values_sorted, n_items, rng)
            values.sort(reverse=True)
            return values, "real"
        elif family == "comparison":
            # Use the top-2 values from the real shape, jittered
            top2 = real_values_sorted[:2]
            values = _apply_shape_jitter(top2, 2, rng)
            return values, "real"

    # Fallback to library
    return _sample_shapes_from_library(family, n_items, rng)


# ---------------------------------------------------------------------------
# Census gap-fill helpers
# ---------------------------------------------------------------------------

async def _fetch_corpus_counts(pool) -> Dict[Tuple[str, str], int]:
    """Query the distillation corpus and return a dict of
    {(business_type, family): count} for synthetic-seed rows.

    Returns empty dict if pool is None or query fails.
    """
    if pool is None:
        return {}
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(_GAP_FILL_SQL)
        result: Dict[Tuple[str, str], int] = {}
        for r in rows:
            bt = r["business_type"] or "unknown"
            fam = r["family"] or "other"
            result[(bt, fam)] = int(r["total_count"])
        return result
    except Exception as exc:
        logger.warning("_fetch_corpus_counts failed: %s — gap-fill will treat all buckets as empty", exc)
        return {}


def _build_gap_fill_plan(
    corpus_counts: Dict[Tuple[str, str], int],
    floor: int,
) -> Tuple[Dict[Tuple[str, str], int], List[Tuple[str, str]]]:
    """Given census counts and a floor, compute per-bucket gap to fill.

    Returns:
        gaps: dict {(business_type, family): needed_count}
        skipped: list of (business_type, family) at/above floor
    """
    # business_type strings used in the distillation table for chart_insight
    _BT_MAP = {"restaurant": "restaurant", "factory": "factory"}
    all_families = {b.family for b in _ALL_BUCKETS}

    gaps: Dict[Tuple[str, str], int] = {}
    skipped: List[Tuple[str, str]] = []

    for domain in _DOMAINS:
        bt = _BT_MAP.get(domain, domain)
        for family in sorted(all_families):
            current = corpus_counts.get((bt, family), 0)
            if current >= floor:
                skipped.append((bt, family))
            else:
                needed = floor - current
                gaps[(bt, family)] = needed

    return gaps, skipped


class _NoBudgetBucket:
    blocked: bool = False


class _NoBudgetTracker:
    """Never blocks; never counts. Safe to use only for offline seeding."""

    async def check_budget(self, factory_id: str) -> _NoBudgetBucket:
        return _NoBudgetBucket()

    async def consume(self, factory_id: str, tokens: int) -> None:
        pass


# ---------------------------------------------------------------------------
# Scenario families and bucket definitions
# ---------------------------------------------------------------------------

@dataclass
class _BucketSpec:
    family: str        # "proportion" | "ranking" | "comparison" | "kpi" | "trend"
    chart_type: str    # "PIE" | "BAR" | "LINE"
    data_pattern: str  # canonical bucket string
    x_dim: str
    y_metric: str
    aggregation: str
    n_items_range: Tuple[int, int]  # (min, max) number of series items


_PROPORTION_BUCKETS: List[_BucketSpec] = [
    _BucketSpec("proportion", "PIE", "proportion:top-share:40-55:n2-3", "channel", "revenue", "sum", (2, 3)),
    _BucketSpec("proportion", "PIE", "proportion:top-share:50-65:n2-3", "channel", "revenue", "sum", (2, 3)),
    _BucketSpec("proportion", "PIE", "proportion:top-share:65-80:n3-5", "category", "revenue", "sum", (3, 5)),
    _BucketSpec("proportion", "PIE", "proportion:top-share:80-95:n3-5", "category", "revenue", "sum", (3, 5)),
]

_RANKING_BUCKETS: List[_BucketSpec] = [
    _BucketSpec("ranking", "BAR", "ranking:top-share:40-55:n3-5", "store",    "revenue", "sum", (3, 5)),
    _BucketSpec("ranking", "BAR", "ranking:top-share:50-65:n4-8", "store",    "revenue", "sum", (4, 8)),
    _BucketSpec("ranking", "BAR", "ranking:top-share:65-80:n4-8", "dish",     "count",   "sum", (4, 8)),
    _BucketSpec("ranking", "BAR", "ranking:top-share:80-95:n2-3", "category", "revenue", "sum", (2, 3)),
]

_COMPARISON_BUCKETS: List[_BucketSpec] = [
    _BucketSpec("comparison", "BAR", "comparison:two-series:positive-gap",    "channel",    "revenue", "sum", (2, 2)),
    _BucketSpec("comparison", "BAR", "comparison:two-series:near-parity",     "store",      "revenue", "sum", (2, 2)),
    _BucketSpec("comparison", "BAR", "comparison:two-series:reversed-rank",   "department", "cost",    "sum", (2, 2)),
    _BucketSpec("comparison", "BAR", "comparison:two-series:large-gap",       "product",    "revenue", "sum", (2, 2)),
]

_KPI_BUCKETS: List[_BucketSpec] = [
    _BucketSpec("kpi", "BAR", "kpi:actual-vs-target:over-100pct",   "other", "revenue", "sum", (2, 2)),
    _BucketSpec("kpi", "BAR", "kpi:actual-vs-target:90-100pct",     "other", "revenue", "sum", (2, 2)),
    _BucketSpec("kpi", "BAR", "kpi:actual-vs-target:under-90pct",   "other", "revenue", "sum", (2, 2)),
    _BucketSpec("kpi", "BAR", "kpi:actual-vs-target:cost-overrun",  "other", "cost",    "sum", (2, 2)),
]

_TREND_BUCKETS: List[_BucketSpec] = [
    _BucketSpec("trend", "LINE", "trend:rising:n6-12",   "time", "revenue", "sum", (6, 12)),
    _BucketSpec("trend", "LINE", "trend:falling:n6-12",  "time", "revenue", "sum", (6, 12)),
    _BucketSpec("trend", "LINE", "trend:volatile:n6-12", "time", "revenue", "sum", (6, 12)),
    _BucketSpec("trend", "LINE", "trend:flat:n6-12",     "time", "cost",    "avg", (6, 12)),
]

_ALL_BUCKETS: List[_BucketSpec] = (
    _PROPORTION_BUCKETS
    + _RANKING_BUCKETS
    + _COMPARISON_BUCKETS
    + _KPI_BUCKETS
    + _TREND_BUCKETS
)

_DOMAINS = ["restaurant", "factory"]
_TIERS = ["finance_visible", "finance_visible", "finance_hidden"]  # weight toward visible


# ---------------------------------------------------------------------------
# Value generators (matching each bucket's data_pattern semantics)
# ---------------------------------------------------------------------------

def _gen_proportion_values(rng: random.Random, top_share_range: Tuple[float, float],
                           n: int) -> List[float]:
    """Generate n values where top item has share in [lo, hi] %."""
    lo, hi = top_share_range
    top_share = rng.uniform(lo, hi) / 100.0
    remaining = 1.0 - top_share
    # Split remaining among n-1 items
    if n <= 1:
        return [1000.0]
    parts = sorted([rng.random() for _ in range(n - 1)])
    others = []
    prev = 0.0
    for p in parts:
        others.append(p - prev)
        prev = p
    others.append(1.0 - prev)
    # Normalise others to sum to `remaining`
    s = sum(others)
    others = [x / s * remaining for x in others]
    values = [top_share] + others
    rng.shuffle(values)  # don't always put top first
    base = rng.uniform(50_000, 500_000)
    return [round(v * base) for v in values]


def _gen_ranking_values(rng: random.Random, top_share_range: Tuple[float, float],
                        n: int) -> List[float]:
    """Ranking: sorted descending, top item has given share."""
    values = _gen_proportion_values(rng, top_share_range, n)
    return sorted(values, reverse=True)


def _gen_comparison_values(rng: random.Random, sub_pattern: str) -> List[float]:
    """2-series comparison values tuned to sub-pattern."""
    base = rng.uniform(30_000, 300_000)
    if sub_pattern == "positive-gap":
        a = base * rng.uniform(1.4, 2.0)
        b = base
    elif sub_pattern == "near-parity":
        a = base * rng.uniform(0.95, 1.05)
        b = base
    elif sub_pattern == "reversed-rank":
        a = base * rng.uniform(0.6, 0.8)
        b = base
    else:  # large-gap
        a = base * rng.uniform(2.5, 4.0)
        b = base
    return [round(a), round(b)]


def _gen_kpi_values(rng: random.Random, sub_pattern: str) -> List[float]:
    """KPI: [actual, target] tuned to sub-pattern."""
    target = rng.uniform(100_000, 1_000_000)
    if sub_pattern == "over-100pct":
        actual = target * rng.uniform(1.05, 1.20)
    elif sub_pattern == "90-100pct":
        actual = target * rng.uniform(0.90, 0.99)
    elif sub_pattern == "under-90pct":
        actual = target * rng.uniform(0.65, 0.89)
    else:  # cost-overrun
        actual = target * rng.uniform(1.10, 1.35)
    return [round(actual), round(target)]


def _gen_trend_values(rng: random.Random, trend: str, n: int) -> List[float]:
    """Time-series values tuned to trend direction."""
    base = rng.uniform(50_000, 500_000)
    values = []
    v = base
    for i in range(n):
        if trend == "rising":
            step = v * rng.uniform(0.02, 0.08)
        elif trend == "falling":
            step = -v * rng.uniform(0.02, 0.08)
        elif trend == "volatile":
            step = v * rng.uniform(-0.15, 0.15)
        else:  # flat
            step = v * rng.uniform(-0.03, 0.03)
        v = max(v + step, 1_000)
        values.append(round(v))
    return values


def _extract_top_share_range(data_pattern: str) -> Tuple[float, float]:
    """Extract (lo, hi) from a pattern like '...top-share:40-55:...'."""
    for part in data_pattern.split(":"):
        if "-" in part:
            nums = part.split("-")
            if len(nums) == 2:
                try:
                    return float(nums[0]), float(nums[1])
                except ValueError:
                    pass
    return (40.0, 60.0)


def _extract_trend_direction(data_pattern: str) -> str:
    """Extract trend direction from 'trend:rising:...' pattern."""
    parts = data_pattern.split(":")
    if len(parts) >= 2:
        return parts[1]
    return "flat"


def _extract_comparison_sub(data_pattern: str) -> str:
    """Extract 'positive-gap' etc. from 'comparison:two-series:positive-gap'."""
    parts = data_pattern.split(":")
    if len(parts) >= 3:
        return parts[2]
    return "positive-gap"


def _extract_kpi_sub(data_pattern: str) -> str:
    """Extract 'over-100pct' etc. from 'kpi:actual-vs-target:over-100pct'."""
    parts = data_pattern.split(":")
    if len(parts) >= 3:
        return parts[2]
    return "over-100pct"


# ---------------------------------------------------------------------------
# Label picker — domain + x_dim → list of concrete labels
# ---------------------------------------------------------------------------

def _pick_labels(rng: random.Random, domain: str, x_dim: str,
                 n: int, family: str) -> List[str]:
    """Select n distinct labels from the appropriate pool."""
    if x_dim == "time":
        # Trend: use consecutive month labels
        start = rng.randint(0, max(0, 12 - n))
        return _MONTH_LABELS[start:start + n]

    if x_dim == "other":
        # KPI buckets — label is ["实际", "目标"]
        return ["实际", "目标"]

    # Domain-specific pools
    pool_map_r: Dict[str, str] = {
        "channel":  "channel",
        "store":    "store",
        "dish":     "dish",
        "category": "category",
    }
    pool_map_f: Dict[str, str] = {
        "supplier":   "supplier",
        "product":    "product",
        "department": "department",
        "category":   "category",
    }

    pool_key_r = pool_map_r.get(x_dim, "channel")
    pool_key_f = pool_map_f.get(x_dim, "department")

    if domain == "restaurant":
        pool = list(_RESTAURANT_POOLS.get(pool_key_r, _RESTAURANT_POOLS["channel"]))
    else:
        pool = list(_FACTORY_POOLS.get(pool_key_f, _FACTORY_POOLS["department"]))

    # Ensure enough items; repeat if pool smaller than n
    if len(pool) < n:
        rng.shuffle(pool)
        pool = (pool * ((n // len(pool)) + 1))[:n]
        # make names unique by appending index
        pool = [f"{lbl}{i+1}" if pool.count(lbl) > 1 else lbl
                for i, lbl in enumerate(pool)]
    rng.shuffle(pool)
    return pool[:n]


# ---------------------------------------------------------------------------
# Core scenario generator
# ---------------------------------------------------------------------------

def _build_scenario_sync(
    bucket: _BucketSpec,
    domain: str,
    tier: str,
    local_rng: random.Random,
    shape_source: str = "uniform",
    shape_values: Optional[List[float]] = None,
) -> ChartInsightContext:
    """Build one ChartInsightContext synchronously.

    Args:
        bucket: The bucket spec.
        domain: 'restaurant' or 'factory'.
        tier: permission tier string.
        local_rng: per-scenario RNG (already seeded).
        shape_source: 'real', 'library', or 'uniform' — stored in ctx metadata.
        shape_values: pre-computed values (from real/library sampler); if None,
                      uses the classic uniform generators.
    Returns:
        ChartInsightContext ready for LLM call.
    """
    n_items = local_rng.randint(*bucket.n_items_range)
    labels = _pick_labels(local_rng, domain, bucket.x_dim, n_items, bucket.family)

    if shape_values is not None:
        # Real-shape or library path: values were generated externally.
        # Resize to n_items if needed.
        values = list(shape_values)
        if len(values) != n_items:
            values = _apply_shape_jitter(values, n_items, local_rng)
    else:
        # Classic uniform generators (original behaviour — unchanged for reproducibility)
        if bucket.family == "proportion":
            lo, hi = _extract_top_share_range(bucket.data_pattern)
            values = _gen_proportion_values(local_rng, (lo, hi), n_items)
        elif bucket.family == "ranking":
            lo, hi = _extract_top_share_range(bucket.data_pattern)
            values = _gen_ranking_values(local_rng, (lo, hi), n_items)
        elif bucket.family == "comparison":
            sub = _extract_comparison_sub(bucket.data_pattern)
            values = _gen_comparison_values(local_rng, sub)
            labels = labels[:2]
        elif bucket.family == "kpi":
            sub = _extract_kpi_sub(bucket.data_pattern)
            values = _gen_kpi_values(local_rng, sub)
            labels = ["实际", "目标"]
        elif bucket.family == "trend":
            direction = _extract_trend_direction(bucket.data_pattern)
            values = _gen_trend_values(local_rng, direction, n_items)
        else:
            values = [local_rng.uniform(10_000, 500_000) for _ in range(n_items)]

    # KPI labels override (keep alignment regardless of path)
    if bucket.family == "kpi":
        labels = ["实际", "目标"]
    elif bucket.family == "comparison":
        labels = labels[:2]

    # Ensure labels and values are the same length (safety)
    min_len = min(len(labels), len(values))
    labels = labels[:min_len]
    values = values[:min_len]

    sentinel = _SENTINEL_RESTAURANT if domain == "restaurant" else _SENTINEL_FACTORY

    return ChartInsightContext(
        chart_type=bucket.chart_type,
        x_dim=bucket.x_dim,
        y_metric=bucket.y_metric,
        aggregation=bucket.aggregation,
        domain=domain,
        data_pattern=bucket.data_pattern,
        permission_tier=tier,
        factory_id=sentinel,
        series_values=[float(v) for v in values],
        series_labels=labels,
        # shape_source is carried as an attribute for metadata tagging at persist time
    ), shape_source


def generate_scenarios(n: int, seed: int = RANDOM_SEED) -> List[ChartInsightContext]:
    """Generate n diverse synthetic ChartInsightContext scenarios (uniform mode).

    Scenarios are distributed round-robin across ALL (bucket × domain × tier)
    combinations, then padded with random repeats until n is reached.
    Each scenario's series_values are freshly generated with per-scenario RNG
    so input_hash differs even for same-bucket same-domain scenarios.

    This function is the original uniform-generator path; it is kept unchanged
    for backward-compatibility and deterministic unit tests.  For real-shape
    sampling use generate_scenarios_async().

    Args:
        n: Number of scenarios to generate.
        seed: Random seed for reproducibility.

    Returns:
        List of ChartInsightContext objects.
    """
    random.Random(seed)
    combos: List[Tuple[_BucketSpec, str, str]] = []
    for bucket in _ALL_BUCKETS:
        for domain in _DOMAINS:
            for tier in _TIERS:
                combos.append((bucket, domain, tier))

    scenarios: List[ChartInsightContext] = []
    total_combos = len(combos)
    for i in range(n):
        # Cycle through combos; within each repetition of the cycle, vary values
        combo_idx = i % total_combos
        cycle = i // total_combos
        bucket, domain, tier = combos[combo_idx]

        # Per-scenario seed derived from global seed + position so reruns are deterministic
        # but values differ between cycle repetitions.
        local_rng = random.Random(seed + i * 1_000_003 + cycle * 97)

        ctx, _ = _build_scenario_sync(bucket, domain, tier, local_rng,
                                      shape_source="uniform", shape_values=None)
        scenarios.append(ctx)

    return scenarios


async def generate_scenarios_async(
    n: int,
    seed: int = RANDOM_SEED,
    pool=None,
    real_shapes: bool = False,
) -> List[Tuple[ChartInsightContext, str]]:
    """Generate n scenarios asynchronously, optionally using real-shape sampling.

    Args:
        n: Number of scenarios to generate.
        seed: Random seed for reproducibility.
        pool: asyncpg pool for real-shape DB queries (may be None → library fallback).
        real_shapes: If True, use real-shape sampling (DB or library); else uniform.

    Returns:
        List of (ChartInsightContext, shape_source) tuples where shape_source is
        one of 'real', 'library', 'uniform'.
    """
    random.Random(seed)
    combos: List[Tuple[_BucketSpec, str, str]] = []
    for bucket in _ALL_BUCKETS:
        for domain in _DOMAINS:
            for tier in _TIERS:
                combos.append((bucket, domain, tier))

    results: List[Tuple[ChartInsightContext, str]] = []
    total_combos = len(combos)

    for i in range(n):
        combo_idx = i % total_combos
        cycle = i // total_combos
        bucket, domain, tier = combos[combo_idx]
        local_rng = random.Random(seed + i * 1_000_003 + cycle * 97)

        if real_shapes:
            shape_values, shape_source = await _gen_real_shape_values(
                pool, bucket.family,
                local_rng.randint(*bucket.n_items_range),
                local_rng, domain,
            )
        else:
            shape_values, shape_source = None, "uniform"

        ctx, src = _build_scenario_sync(bucket, domain, tier, local_rng,
                                        shape_source=shape_source,
                                        shape_values=shape_values)
        results.append((ctx, src))

    return results


async def generate_scenarios_gap_fill(
    n: int,
    seed: int,
    pool,
    floor: int,
    real_shapes: bool = False,
) -> Tuple[List[Tuple[ChartInsightContext, str]], Dict[str, Any]]:
    """Generate scenarios only for buckets BELOW floor.

    Queries the corpus census, computes gaps, then distributes n scenarios
    proportionally across under-filled (business_type × family) buckets.

    Returns:
        (scenarios_with_source, gap_report) where gap_report contains
        'gaps', 'skipped', 'total_needed', 'fill_plan'.
    """
    corpus_counts = await _fetch_corpus_counts(pool)
    gaps, skipped = _build_gap_fill_plan(corpus_counts, floor)

    # Log what we're filling vs skipping
    _BT_MAP_DISPLAY = {"restaurant": "RESTAURANT", "factory": "FACTORY"}  # noqa: F841
    for bt, fam in sorted(skipped):
        current = corpus_counts.get((bt, fam), 0)
        logger.info("gap-fill SKIP  %-12s %-12s  current=%d >= floor=%d",
                    bt, fam, current, floor)
    for (bt, fam), needed in sorted(gaps.items()):
        current = corpus_counts.get((bt, fam), 0)
        logger.info("gap-fill FILL  %-12s %-12s  current=%d  needed=%d",
                    bt, fam, current, needed)

    total_needed = sum(gaps.values())
    gap_report: Dict[str, Any] = {
        "gaps": {f"{bt}:{fam}": v for (bt, fam), v in gaps.items()},
        "skipped": [f"{bt}:{fam}" for bt, fam in skipped],
        "total_needed": total_needed,
        "fill_plan": {},
    }

    if not gaps:
        logger.info("gap-fill: all buckets at/above floor=%d — nothing to generate", floor)
        return [], gap_report

    # Build a pool of (bucket, domain, tier) combos restricted to under-filled buckets
    _DOMAIN_BT: Dict[str, str] = {"restaurant": "restaurant", "factory": "factory"}
    gap_combos: List[Tuple[_BucketSpec, str, str]] = []
    for bucket in _ALL_BUCKETS:
        for domain in _DOMAINS:
            bt = _DOMAIN_BT.get(domain, domain)
            if (bt, bucket.family) in gaps:
                for tier in _TIERS:
                    gap_combos.append((bucket, domain, tier))

    if not gap_combos:
        return [], gap_report

    rng = random.Random(seed)
    rng.shuffle(gap_combos)

    results: List[Tuple[ChartInsightContext, str]] = []
    actual_n = min(n, total_needed * 3)  # cap at 3× needed so we don't vastly overshoot
    total_combos = len(gap_combos)

    for i in range(actual_n):
        combo_idx = i % total_combos
        bucket, domain, tier = gap_combos[combo_idx]
        local_rng = random.Random(seed + i * 1_000_007 + 31337)

        if real_shapes:
            shape_values, shape_source = await _gen_real_shape_values(
                pool, bucket.family,
                local_rng.randint(*bucket.n_items_range),
                local_rng, domain,
            )
        else:
            shape_values, shape_source = None, "uniform"

        ctx, src = _build_scenario_sync(bucket, domain, tier, local_rng,
                                        shape_source=shape_source,
                                        shape_values=shape_values)
        results.append((ctx, src))

    gap_report["fill_plan"] = {
        "total_requested": n,
        "total_generated": len(results),
        "combos_available": total_combos,
    }

    return results, gap_report


# ---------------------------------------------------------------------------
# Pool connection helper
# ---------------------------------------------------------------------------

async def _make_pool(dsn: Optional[str]):
    """Create an asyncpg pool.  DSN priority:
        1. `dsn` argument (SMARTBI_PROD_DSN env)
        2. Settings.postgres_url (falls back to smartbi_db via env vars)
    Returns None if no DB credentials are available.
    """
    import asyncpg
    effective_dsn = dsn
    if not effective_dsn:
        try:
            from smartbi.config import get_settings
            settings = get_settings()
            effective_dsn = settings.postgres_url
        except Exception as exc:
            logger.warning("Could not load settings for pool DSN: %s", exc)
            return None

    if not effective_dsn:
        logger.warning("No DSN available — pool will be None; persist will be skipped.")
        return None

    try:
        pool = await asyncpg.create_pool(effective_dsn, min_size=1, max_size=3)
        logger.info("asyncpg pool created (DSN prefix: %s...)", effective_dsn[:30])
        return pool
    except Exception as exc:
        logger.error("Failed to create asyncpg pool: %s", exc)
        return None


# ---------------------------------------------------------------------------
# Main seeding runner
# ---------------------------------------------------------------------------

@dataclass
class _RejectionCounters:
    """Per-reason rejection counters for diagnosing acceptance rate.

    Reasons:
      llm_error         — _call_llm raised an exception or returned None.
      claims_empty      — _validate_claims returned None because the LLM output
                          had no claims or the JSON was malformed.
      recompute_mismatch — _validate_claims found computed != claimed values.
      adjacency_fail    — _validate_claims adjacency / ordering check failed.
      y_gate            — finance_hidden ¥ absolute-amount gate blocked.
    """
    llm_error:          int = 0
    claims_empty:       int = 0
    recompute_mismatch: int = 0
    adjacency_fail:     int = 0
    y_gate:             int = 0

    @property
    def total(self) -> int:
        return (self.llm_error + self.claims_empty + self.recompute_mismatch
                + self.adjacency_fail + self.y_gate)

    def log_summary(self, logger_: logging.Logger) -> None:
        logger_.info(
            "Rejection breakdown  llm_error=%-4d  claims_empty=%-4d  "
            "recompute_mismatch=%-4d  adjacency_fail=%-4d  ¥_gate=%-4d  total=%-4d",
            self.llm_error, self.claims_empty, self.recompute_mismatch,
            self.adjacency_fail, self.y_gate, self.total,
        )


def _classify_validation_rejection(llm_obj: Any, ctx: ChartInsightContext) -> str:
    """Run _validate_claims and return the reason it failed (or 'ok' if passed).

    Returns one of: 'ok' | 'claims_empty' | 'recompute_mismatch' | 'adjacency_fail'

    This thin wrapper captures structured reasons without duplicating the gate
    logic — it re-runs _validate_claims but that is idempotent + fast.
    """
    # We call the real validator; if it returns non-None → accepted.
    # To distinguish reason, we do a two-pass approach:
    #   pass 1: check if the output has any claims keys at all
    #   pass 2: call _validate_claims and see if it returns None (rejection)
    # Since _validate_claims doesn't expose sub-reasons, we infer:
    #   * If llm_obj looks like it has no finding/implication/suggestion → claims_empty
    #   * Otherwise → recompute_mismatch (most common validator rejection)
    if not llm_obj or not isinstance(llm_obj, dict):
        return "claims_empty"
    has_content = any(llm_obj.get(k) for k in ("finding", "implication", "suggestion"))
    if not has_content:
        return "claims_empty"

    result = _validate_claims(llm_obj, ctx)
    if result is not None:
        return "ok"

    # Heuristic: check if there are numeric claims to recompute
    # If the LLM output contains numbers, it's likely a recompute mismatch.
    # Otherwise it could be an adjacency / ordering failure.
    import re as _re
    combined_text = " ".join(str(v) for v in llm_obj.values() if v)
    if _re.search(r"\d+\.?\d*%?", combined_text):
        return "recompute_mismatch"
    return "adjacency_fail"


async def seed(
    n: int,
    dry_run: bool = False,
    dsn: Optional[str] = None,
    seed_val: int = RANDOM_SEED,
    real_shapes: bool = False,
    gap_fill: bool = False,
    floor: int = 200,
) -> None:
    """Generate n synthetic scenarios and optionally call LLM + persist accepted outputs.

    Args:
        n: Number of scenarios to generate.
        dry_run: If True, only generate + print scenarios; no LLM calls, no DB.
        dsn: Optional asyncpg DSN override.
        seed_val: Random seed for scenario generation.
        real_shapes: If True, sample series shapes from real prod data (DB or
                     library fallback) instead of uniform generators.
        gap_fill: If True, run census first and only generate for buckets below
                  floor (--floor).  n acts as an upper cap.
        floor: Minimum count per (business_type × family) bucket for gap-fill.
    """
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    # ----------------------------------------------------------------
    # Dry-run (uniform generator, no pool needed): just print and exit
    # ----------------------------------------------------------------
    if dry_run:
        scenarios = generate_scenarios(n, seed=seed_val)
        logger.info("Generated %d synthetic scenarios (seed=%d)", len(scenarios), seed_val)
        _print_dry_run_summary(scenarios)
        return

    # ----------------------------------------------------------------
    # Real run: need pool early (required for real-shapes + gap-fill)
    # ----------------------------------------------------------------
    pool = await _make_pool(dsn)
    stub_budget = _NoBudgetTracker()
    svc = ChartInsightService(pool=pool, budget_tracker=stub_budget)

    # ----------------------------------------------------------------
    # Scenario generation: gap-fill vs normal
    # ----------------------------------------------------------------
    if gap_fill:
        logger.info("gap-fill mode: querying corpus census (floor=%d)…", floor)
        scenario_pairs, gap_report = await generate_scenarios_gap_fill(
            n=n, seed=seed_val, pool=pool, floor=floor, real_shapes=real_shapes,
        )
        logger.info(
            "gap-fill plan: total_needed=%d  combos_available=%d  generating=%d",
            gap_report["total_needed"],
            gap_report.get("fill_plan", {}).get("combos_available", 0),
            len(scenario_pairs),
        )
    else:
        logger.info(
            "normal mode: generating %d scenarios (real_shapes=%s, seed=%d)",
            n, real_shapes, seed_val,
        )
        scenario_pairs = await generate_scenarios_async(
            n=n, seed=seed_val, pool=pool, real_shapes=real_shapes,
        )

    logger.info("Generated %d scenario pairs", len(scenario_pairs))

    # ----------------------------------------------------------------
    # LLM + persist loop with per-reason rejection counters
    # ----------------------------------------------------------------
    rej = _RejectionCounters()
    family_counters: Dict[str, Dict[str, int]] = {}
    total_accepted = 0
    total_generated = 0

    for idx, (ctx, shape_source) in enumerate(scenario_pairs):
        total_generated += 1
        family = ctx.data_pattern.split(":")[0]
        if family not in family_counters:
            family_counters[family] = {"generated": 0, "accepted": 0, "rejected": 0}
        family_counters[family]["generated"] += 1

        # --- LLM call ---
        try:
            llm_obj = await svc._call_llm(ctx)
        except Exception as exc:
            logger.warning("[%d/%d] _call_llm failed: %s", idx + 1, total_generated, exc)
            rej.llm_error += 1
            family_counters[family]["rejected"] += 1
            continue

        if llm_obj is None:
            logger.debug("[%d/%d] LLM returned None", idx + 1, total_generated)
            rej.llm_error += 1
            family_counters[family]["rejected"] += 1
            continue

        # --- Validate claims with structured reason tracking ---
        rejection_reason = _classify_validation_rejection(llm_obj, ctx)
        if rejection_reason != "ok":
            logger.debug("[%d/%d] _validate_claims rejected (%s)",
                         idx + 1, total_generated, rejection_reason)
            if rejection_reason == "claims_empty":
                rej.claims_empty += 1
            elif rejection_reason == "recompute_mismatch":
                rej.recompute_mismatch += 1
            elif rejection_reason == "adjacency_fail":
                rej.adjacency_fail += 1
            family_counters[family]["rejected"] += 1
            continue

        # Re-run _validate_claims to get the validated dict (already passed above)
        validated = _validate_claims(llm_obj, ctx)
        if validated is None:
            # Safety net: should not happen after _classify returned 'ok', but guard it
            rej.recompute_mismatch += 1
            family_counters[family]["rejected"] += 1
            continue

        # --- finance_hidden ¥ serve-gate ---
        if ctx.permission_tier == "finance_hidden":
            combined = " ".join(filter(None, [
                validated.get("finding"),
                validated.get("implication"),
                validated.get("suggestion"),
            ]))
            if _ABSOLUTE_AMOUNT_RE.search(combined):
                logger.debug("[%d/%d] finance_hidden ¥ gate blocked",
                             idx + 1, total_generated)
                rej.y_gate += 1
                family_counters[family]["rejected"] += 1
                continue

        # --- Persist accepted sample ---
        input_text = _corpus_input_text(ctx)
        teacher_model = svc._get_teacher_model()
        await persist_distillation_sample(
            pool,
            source="chart_insight",
            task_type="insights",
            input_text=input_text,
            teacher_output=json.dumps(validated, ensure_ascii=False),
            business_type=_map_domain(ctx.domain),
            factory_id=ctx.factory_id,
            quality=5,  # accepted = claims-pinning validated = highest tier (P0-1 G1)
            system_prompt=_SYSTEM_PROMPT,
            teacher_model=teacher_model,
            metadata={
                "seeded":       True,
                "permission_tier": ctx.permission_tier,
                "gate":         "passed",
                "source_kind":  "synthetic_seed",
                "shape_source": shape_source,   # 'real' | 'library' | 'uniform'
            },
        )
        total_accepted += 1
        family_counters[family]["accepted"] += 1

        if (idx + 1) % 50 == 0:
            logger.info(
                "[%d/%d] Running totals — accepted=%d rejected=%d",
                idx + 1, total_generated, total_accepted, rej.total,
            )

    # ----------------------------------------------------------------
    # Final report
    # ----------------------------------------------------------------
    total_rejected = rej.total
    logger.info("=" * 60)
    logger.info(
        "SEED COMPLETE  generated=%d  accepted=%d  rejected=%d",
        total_generated, total_accepted, total_rejected,
    )
    rej.log_summary(logger)
    logger.info("Per-family breakdown:")
    for fam, c in sorted(family_counters.items()):
        logger.info("  %-14s generated=%d  accepted=%d  rejected=%d",
                    fam, c["generated"], c["accepted"], c["rejected"])
    logger.info("=" * 60)

    if pool is not None:
        try:
            await pool.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Dry-run pretty-printer
# ---------------------------------------------------------------------------

def _print_dry_run_summary(scenarios: List[ChartInsightContext]) -> None:
    """Print a terse summary of generated scenarios (dry-run mode)."""
    print(f"\n{'=' * 70}")
    print(f"DRY-RUN: {len(scenarios)} scenarios generated (no LLM / DB calls)")
    print(f"{'=' * 70}\n")

    # Print first 5 scenarios in full
    for i, ctx in enumerate(scenarios[:5]):
        print(f"[{i+1:04d}] {ctx.chart_type:<4}  domain={ctx.domain:<12}  "
              f"family={ctx.data_pattern.split(':')[0]:<12}  tier={ctx.permission_tier}")
        print(f"       x_dim={ctx.x_dim}  y_metric={ctx.y_metric}  agg={ctx.aggregation}")
        print(f"       data_pattern={ctx.data_pattern}")
        print(f"       labels={ctx.series_labels}")
        print(f"       values={ctx.series_values}")
        print()

    if len(scenarios) > 5:
        print(f"  ... and {len(scenarios) - 5} more scenarios (showing first 5 only)\n")

    # Distribution summary
    from collections import Counter
    family_counts = Counter(ctx.data_pattern.split(":")[0] for ctx in scenarios)
    domain_counts = Counter(ctx.domain for ctx in scenarios)
    tier_counts = Counter(ctx.permission_tier for ctx in scenarios)
    chart_counts = Counter(ctx.chart_type for ctx in scenarios)

    print("Distribution summary:")
    print(f"  Families:  {dict(sorted(family_counts.items()))}")
    print(f"  Domains:   {dict(domain_counts)}")
    print(f"  Tiers:     {dict(tier_counts)}")
    print(f"  ChartType: {dict(chart_counts)}")
    print()

    # Combo space
    families = set(ctx.data_pattern.split(":")[0] for ctx in scenarios)
    domains = set(ctx.domain for ctx in scenarios)
    tiers = set(ctx.permission_tier for ctx in scenarios)
    patterns = set(ctx.data_pattern for ctx in scenarios)
    print(f"  Distinct families:     {len(families)}")
    print(f"  Distinct domains:      {len(domains)}")
    print(f"  Distinct tiers:        {len(tiers)}")
    print(f"  Distinct data_patterns: {len(patterns)}")
    print(f"  Distinct chart_types:   {len(set(ctx.chart_type for ctx in scenarios))}")
    print(f"  Bucket × domain combos: {len(_ALL_BUCKETS)} × {len(_DOMAINS)} = "
          f"{len(_ALL_BUCKETS) * len(_DOMAINS)} base combos "
          f"(× {len(_TIERS)} tier weights = "
          f"{len(_ALL_BUCKETS) * len(_DOMAINS) * len(_TIERS)} full combos)\n")


# ---------------------------------------------------------------------------
# CLI entry-point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Bootstrap chart-insight distillation corpus with synthetic scenarios.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--n", type=int, default=500,
                        help="Number of scenarios to generate (default: 500).")
    parser.add_argument("--dry-run", action="store_true",
                        help="Generate + print scenarios; skip LLM / DB calls.")
    parser.add_argument("--dsn", default=os.environ.get("SMARTBI_PROD_DSN", ""),
                        help="asyncpg DSN (overrides SMARTBI_PROD_DSN env var).")
    parser.add_argument("--seed", type=int, default=RANDOM_SEED,
                        help=f"Random seed (default: {RANDOM_SEED}).")
    parser.add_argument("--log-level", default="INFO",
                        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                        help="Logging level.")
    parser.add_argument("--real-shapes", action="store_true",
                        help=(
                            "Use real-shape sampling: query gold/finance tables for "
                            "actual series distributions, pair with anonymised labels "
                            "and ±10-15%% jitter. Falls back to curated shape library "
                            "if DB is unreachable or returns no data."
                        ))
    parser.add_argument("--gap-fill", action="store_true",
                        help=(
                            "Gap-fill mode: run census first, then only generate for "
                            "(business_type × family) buckets below --floor. "
                            "--n acts as an upper cap."
                        ))
    parser.add_argument("--floor", type=int, default=200,
                        help=(
                            "Minimum synthetic-seed count per (business_type × family) "
                            "bucket for --gap-fill. Buckets at/above floor are skipped. "
                            "(default: 200)"
                        ))
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    asyncio.run(seed(
        n=args.n,
        dry_run=args.dry_run,
        dsn=args.dsn or None,
        seed_val=args.seed,
        real_shapes=args.real_shapes,
        gap_fill=args.gap_fill,
        floor=args.floor,
    ))


if __name__ == "__main__":
    main()
