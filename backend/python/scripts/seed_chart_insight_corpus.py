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
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import random
import sys
from dataclasses import dataclass, field
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
import types as _types

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

_chart_svc_mod   = _import_from("smartbi.services.insights.chart_insight_service")
_distill_mod     = _import_from("smartbi.services.distillation_capture")

ChartInsightContext    = _chart_svc_mod.ChartInsightContext
_validate_claims       = _chart_svc_mod._validate_claims
_corpus_input_text     = _chart_svc_mod._corpus_input_text
_map_domain            = _chart_svc_mod._map_domain
_ABSOLUTE_AMOUNT_RE    = _chart_svc_mod._ABSOLUTE_AMOUNT_RE
_SYSTEM_PROMPT         = _chart_svc_mod._SYSTEM_PROMPT
ChartInsightService    = _chart_svc_mod.ChartInsightService
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
_SENTINEL_FACTORY    = "SEED_F"

# ---------------------------------------------------------------------------
# Budget-bypass stub — offline seeding is NOT user traffic.
# ---------------------------------------------------------------------------

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

_DOMAINS    = ["restaurant", "factory"]
_TIERS      = ["finance_visible", "finance_visible", "finance_hidden"]  # weight toward visible


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

def generate_scenarios(n: int, seed: int = RANDOM_SEED) -> List[ChartInsightContext]:
    """Generate n diverse synthetic ChartInsightContext scenarios.

    Scenarios are distributed round-robin across ALL (bucket × domain × tier)
    combinations, then padded with random repeats until n is reached.
    Each scenario's series_values are freshly generated with per-scenario RNG
    so input_hash differs even for same-bucket same-domain scenarios.

    Args:
        n: Number of scenarios to generate.
        seed: Random seed for reproducibility.

    Returns:
        List of ChartInsightContext objects.
    """
    rng = random.Random(seed)
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
        cycle     = i // total_combos
        bucket, domain, tier = combos[combo_idx]

        # Per-scenario seed derived from global seed + position so reruns are deterministic
        # but values differ between cycle repetitions.
        local_rng = random.Random(seed + i * 1_000_003 + cycle * 97)

        n_items = local_rng.randint(*bucket.n_items_range)
        labels  = _pick_labels(local_rng, domain, bucket.x_dim, n_items, bucket.family)

        # Values
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

        # Ensure labels and values are the same length (safety)
        min_len = min(len(labels), len(values))
        labels  = labels[:min_len]
        values  = values[:min_len]

        sentinel = _SENTINEL_RESTAURANT if domain == "restaurant" else _SENTINEL_FACTORY

        ctx = ChartInsightContext(
            chart_type      = bucket.chart_type,
            x_dim           = bucket.x_dim,
            y_metric        = bucket.y_metric,
            aggregation     = bucket.aggregation,
            domain          = domain,
            data_pattern    = bucket.data_pattern,
            permission_tier = tier,
            factory_id      = sentinel,
            series_values   = [float(v) for v in values],
            series_labels   = labels,
        )
        scenarios.append(ctx)

    return scenarios


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

async def seed(n: int, dry_run: bool = False, dsn: Optional[str] = None,
               seed_val: int = RANDOM_SEED) -> None:
    """Generate n synthetic scenarios and optionally call LLM + persist accepted outputs.

    Args:
        n: Number of scenarios to generate.
        dry_run: If True, only generate + print scenarios; no LLM calls, no DB.
        dsn: Optional asyncpg DSN override.
        seed_val: Random seed for scenario generation.
    """
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    scenarios = generate_scenarios(n, seed=seed_val)
    logger.info("Generated %d synthetic scenarios (seed=%d)", len(scenarios), seed_val)

    # ----------------------------------------------------------------
    # Dry-run: just print and exit
    # ----------------------------------------------------------------
    if dry_run:
        _print_dry_run_summary(scenarios)
        return

    # ----------------------------------------------------------------
    # Real run: LLM + persist
    # ----------------------------------------------------------------
    pool = await _make_pool(dsn)
    stub_budget = _NoBudgetTracker()
    svc = ChartInsightService(pool=pool, budget_tracker=stub_budget)

    # Per-family counters
    counters: Dict[str, Dict[str, int]] = {}
    total_generated = 0
    total_accepted  = 0
    total_rejected  = 0
    total_ygate     = 0

    for idx, ctx in enumerate(scenarios):
        total_generated += 1
        family = ctx.data_pattern.split(":")[0]
        if family not in counters:
            counters[family] = {"generated": 0, "accepted": 0, "rejected": 0}
        counters[family]["generated"] += 1

        try:
            llm_obj = await svc._call_llm(ctx)
        except Exception as exc:
            logger.warning("[%d/%d] _call_llm failed: %s", idx + 1, n, exc)
            total_rejected += 1
            counters[family]["rejected"] += 1
            continue

        if llm_obj is None:
            logger.debug("[%d/%d] LLM returned None — skipping", idx + 1, n)
            total_rejected += 1
            counters[family]["rejected"] += 1
            continue

        validated = _validate_claims(llm_obj, ctx)
        if validated is None:
            logger.debug("[%d/%d] _validate_claims rejected — skipping", idx + 1, n)
            total_rejected += 1
            counters[family]["rejected"] += 1
            continue

        # finance_hidden ¥ serve-gate
        if ctx.permission_tier == "finance_hidden":
            combined = " ".join(filter(None, [
                validated.get("finding"),
                validated.get("implication"),
                validated.get("suggestion"),
            ]))
            if _ABSOLUTE_AMOUNT_RE.search(combined):
                logger.debug("[%d/%d] finance_hidden ¥ gate blocked", idx + 1, n)
                total_ygate += 1
                total_rejected += 1
                counters[family]["rejected"] += 1
                continue

        # Persist
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
            system_prompt=_SYSTEM_PROMPT,
            teacher_model=teacher_model,
            metadata={
                "permission_tier": ctx.permission_tier,
                "gate": "passed",
                "source_kind": "synthetic_seed",
            },
        )
        total_accepted += 1
        counters[family]["accepted"] += 1

        if (idx + 1) % 50 == 0:
            logger.info(
                "[%d/%d] Running totals — accepted=%d rejected=%d ¥gate=%d",
                idx + 1, n, total_accepted, total_rejected, total_ygate,
            )

    # ----------------------------------------------------------------
    # Final report
    # ----------------------------------------------------------------
    logger.info("=" * 60)
    logger.info("SEED COMPLETE  n=%d  accepted=%d  rejected=%d  ¥gate=%d",
                n, total_accepted, total_rejected, total_ygate)
    logger.info("Per-family breakdown:")
    for fam, c in sorted(counters.items()):
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
    tier_counts   = Counter(ctx.permission_tier for ctx in scenarios)
    chart_counts  = Counter(ctx.chart_type for ctx in scenarios)

    print("Distribution summary:")
    print(f"  Families:  {dict(sorted(family_counts.items()))}")
    print(f"  Domains:   {dict(domain_counts)}")
    print(f"  Tiers:     {dict(tier_counts)}")
    print(f"  ChartType: {dict(chart_counts)}")
    print()

    # Combo space
    families   = set(ctx.data_pattern.split(":")[0] for ctx in scenarios)
    domains    = set(ctx.domain for ctx in scenarios)
    tiers      = set(ctx.permission_tier for ctx in scenarios)
    patterns   = set(ctx.data_pattern for ctx in scenarios)
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
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    asyncio.run(seed(
        n        = args.n,
        dry_run  = args.dry_run,
        dsn      = args.dsn or None,
        seed_val = args.seed,
    ))


if __name__ == "__main__":
    main()
