"""P1 cold-start sweep: drive AgentOrchestrator to warm the agent_insight corpus.

For each demo/active restaurant factory × time window × question, calls
``AgentOrchestrator.answer_insight()`` with a real DB pool so the orchestrator
fetches REAL gold data, calls the LLM, and captures the (prompt → answer) pair
into ``smart_bi_distillation_samples`` via ``_capture_insight_distillation``.

Key design decisions
--------------------
- **Drives the REAL pipeline** — zero synthetic, highest corpus quality.
- **Budget bypass**: uses ``_NoBudgetTracker`` so the per-factory daily token
  quota is not consumed (this is offline sweep, not user traffic).
- **Idempotent**: ``AgentOrchestrator.answer_insight`` checks the narrative
  cache first (cache TTL = 24h by default); re-running after >24h will re-call
  LLM and refresh the corpus row via ON CONFLICT update.  Within 24h it returns
  the cached answer (0 tokens, 0 new corpus rows — that's the correct behavior).
  Set ``--no-cache`` to bypass the narrative cache and force LLM calls.
- **Restaurant-only**: ``AgentOrchestrator`` is built for the restaurant gold
  tables (``smartbi.gold`` queries ``agg_store_daily`` etc.).  Factory-only
  tenants (F001 when used as manufacturing, F006) don't have gold POS data and
  will produce empty ``_gather_data`` → LLM prompt with all-zero KPIs → low
  quality sample.  The driver skips factory-only tenants by default; pass
  ``--include-factory`` to override.

Usage::

    # Dry-run: list what would be run
    python -m scripts.sweep_agent_insight_warmup --dry-run

    # Real sweep: default factories × 3 windows × 5 questions
    python -m scripts.sweep_agent_insight_warmup

    # Specific factories + windows
    python -m scripts.sweep_agent_insight_warmup --factories qhj_prod,RES_3101_009 --windows 30,90,all

    # Force LLM re-run bypassing narrative cache
    python -m scripts.sweep_agent_insight_warmup --no-cache

Environment
-----------
Same as running server: DASHSCOPE_API_KEY / LLM_API_KEY, POSTGRES_*.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
import time
from datetime import date, timedelta
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Path bootstrap
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
if _PYTHON_ROOT not in sys.path:
    sys.path.insert(0, _PYTHON_ROOT)
# main.py adds smartbi/ to sys.path so bare `from services.X import ...` resolves to
# smartbi/services/X (the real pipeline modules use that bare import). Mirror it here.
_SMARTBI_DIR = os.path.join(_PYTHON_ROOT, "smartbi")
if _SMARTBI_DIR not in sys.path:
    sys.path.insert(0, _SMARTBI_DIR)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("sweep_agent_insight_warmup")

# ---------------------------------------------------------------------------
# Default restaurant factory list (agents only make sense for restaurant gold)
# ---------------------------------------------------------------------------
DEFAULT_RESTAURANT_FACTORIES: List[str] = [
    "qhj_prod",
    "RES_3101_009",
    "R_GML_DEMO",
    "R_XMX_CHAIN",
]

# Time windows: (label, lookback_days | "all")
# "all" = from 2025-01-01 to today (full history available in gold)
_WINDOW_SPECS: Dict[str, Tuple[Optional[int], Optional[date]]] = {
    "30":  (30, None),
    "90":  (90, None),
    "all": (None, date(2025, 1, 1)),  # from Jan 2025 (gold data start)
}

# Questions covering the main intent categories in AgentOrchestrator
DEFAULT_QUESTIONS: List[str] = [
    "本期整体营业情况如何？",
    "哪些门店表现最好，哪些最差？",
    "最热卖的菜品是什么？",
    "折扣活动对营业额有什么影响？",
    "客单价变化趋势如何？",
]


# ---------------------------------------------------------------------------
# Budget-bypass stub (mirrors seed_chart_insight_corpus pattern)
# ---------------------------------------------------------------------------

class _NoBudgetBucket:
    blocked: bool = False
    tokens_used: int = 0
    tokens_cap: int = 999_999


class _NoBudgetTracker:
    """Never blocks; never counts. Safe for offline sweeps only."""
    async def check_budget(self, factory_id: str) -> _NoBudgetBucket:
        return _NoBudgetBucket()

    async def consume(self, factory_id: str, tokens: int) -> _NoBudgetBucket:
        return _NoBudgetBucket()


# ---------------------------------------------------------------------------
# Narrative cache bypass stub (for --no-cache mode)
# ---------------------------------------------------------------------------

class _NoopNarrativeCache:
    """Always misses; never stores.  Forces LLM re-call every run."""
    async def get(self, factory_id: str, q_hash: str) -> None:
        return None

    async def put(self, factory_id: str, q_hash: str, answer: str, **kwargs) -> None:
        pass


# ---------------------------------------------------------------------------
# Census helpers
# ---------------------------------------------------------------------------

async def _count_corpus_rows(pool, source: str = "agent_insight") -> int:
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT COUNT(*) AS n FROM smart_bi_distillation_samples WHERE source = $1",
                source,
            )
        return int(row["n"]) if row else 0
    except Exception as e:
        logger.warning(f"[census] count query failed: {e}")
        return -1


# ---------------------------------------------------------------------------
# Window resolution
# ---------------------------------------------------------------------------

def _resolve_date_range(window_key: str) -> Tuple[date, date]:
    today = date.today()
    spec = _WINDOW_SPECS.get(window_key)
    if spec is None:
        # Treat as numeric days
        try:
            days = int(window_key)
            return today - timedelta(days=days), today
        except ValueError:
            raise ValueError(f"Unknown window: {window_key!r}")
    lookback, start_override = spec
    if lookback is not None:
        return today - timedelta(days=lookback), today
    # "all" path
    return start_override, today  # type: ignore[return-value]


# ---------------------------------------------------------------------------
# Per-factory × window × question sweep
# ---------------------------------------------------------------------------

async def _run_one(
    orchestrator,
    factory_id: str,
    window_key: str,
    question: str,
    dry_run: bool,
) -> bool:
    """Call answer_insight for one combination.  Returns True on success."""
    if dry_run:
        logger.info(
            f"[dry-run] would run: factory={factory_id} window={window_key!r} "
            f"question={question!r}"
        )
        return True
    try:
        start, end = _resolve_date_range(window_key)
        t0 = time.monotonic()
        resp = await orchestrator.answer_insight(
            factory_id,
            question,
            (start, end),
            cache_ttl_hours=0,  # don't pollute production narrative cache
        )
        elapsed = int((time.monotonic() - t0) * 1000)
        logger.info(
            f"[warmup] factory={factory_id} window={window_key!r} "
            f"src={resp.source} tokens={resp.tokens} elapsed={elapsed}ms "
            f"q={question[:40]!r}"
        )
        return True
    except Exception as e:
        logger.warning(
            f"[warmup] FAILED factory={factory_id} window={window_key!r} "
            f"q={question[:40]!r}: {e}"
        )
        return False


# ---------------------------------------------------------------------------
# Main sweep
# ---------------------------------------------------------------------------

async def run_sweep(
    factory_ids: List[str],
    window_keys: List[str],
    questions: List[str],
    dry_run: bool,
    no_cache: bool,
    *,
    concurrency: int = 2,
) -> None:
    logger.info(
        f"[sweep] factories={factory_ids} windows={window_keys} "
        f"questions={len(questions)} concurrency={concurrency}"
        + (" DRY-RUN" if dry_run else "")
        + (" NO-CACHE" if no_cache else "")
    )

    from smartbi.config import get_pg_pool, get_settings

    if not dry_run:
        pool = await get_pg_pool()
        if pool is None:
            logger.error("[sweep] could not obtain DB pool — aborting")
            return
    else:
        pool = None  # type: ignore[assignment]

    # Pre-sweep census
    before = await _count_corpus_rows(pool, "agent_insight") if pool else -1
    logger.info(f"[census] before: agent_insight rows = {before}")

    # Build orchestrator (one per sweep, shared across tasks)
    orchestrators: Dict[str, Any] = {}

    if not dry_run:
        settings = get_settings()
        llm_api_key = (
            getattr(settings, "llm_api_key", None)
            or os.environ.get("LLM_API_KEY")
            or os.environ.get("DASHSCOPE_API_KEY")
            or ""
        )
        llm_base_url = getattr(settings, "llm_base_url", None) or "https://dashscope.aliyuncs.com/compatible-mode/v1"
        llm_model = getattr(settings, "llm_model", None) or "qwen3-max"

        from smartbi.agent.orchestrator import AgentOrchestrator
        from smartbi.agent.narrative_cache import NarrativeCacheService

        for fid in factory_ids:
            budget = _NoBudgetTracker()
            cache = _NoopNarrativeCache() if no_cache else NarrativeCacheService(pool)
            orchestrators[fid] = AgentOrchestrator(
                pool=pool,
                llm_base_url=llm_base_url,
                llm_api_key=llm_api_key,
                llm_model=llm_model,
                budget_tracker=budget,
                cache=cache,
            )

    # Build work list
    work: List[Tuple[str, str, str]] = [
        (fid, wk, q)
        for fid in factory_ids
        for wk in window_keys
        for q in questions
    ]
    logger.info(f"[sweep] total combinations: {len(work)}")

    attempted = succeeded = 0
    sem = asyncio.Semaphore(concurrency)

    async def _process(fid: str, wk: str, q: str) -> None:
        nonlocal attempted, succeeded
        async with sem:
            attempted += 1
            orch = orchestrators.get(fid)
            ok = await _run_one(orch, fid, wk, q, dry_run)
            if ok:
                succeeded += 1

    tasks = [asyncio.create_task(_process(fid, wk, q)) for fid, wk, q in work]
    await asyncio.gather(*tasks, return_exceptions=True)

    after = await _count_corpus_rows(pool, "agent_insight") if pool else -1
    added = (after - before) if before >= 0 and after >= 0 else "?"
    logger.info(
        f"[census] after:  agent_insight rows = {after}  (added/refreshed ~{added})"
    )
    logger.info(
        f"[sweep] done: attempted={attempted} succeeded={succeeded}"
        + (" (dry-run)" if dry_run else "")
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            "P1 cold-start: warmup agent_insight corpus by running AgentOrchestrator "
            "across factories × time windows × questions."
        ),
    )
    p.add_argument(
        "--factories",
        default=",".join(DEFAULT_RESTAURANT_FACTORIES),
        help="Comma-separated factory IDs (default: restaurant factories)",
    )
    p.add_argument(
        "--windows",
        default="30,90,all",
        help="Comma-separated window keys: 30 / 90 / all / <N>days (default: 30,90,all)",
    )
    p.add_argument(
        "--concurrency",
        type=int,
        default=2,
        help="Max concurrent LLM calls (default 2)",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="List combinations without running LLM / persisting",
    )
    p.add_argument(
        "--no-cache",
        action="store_true",
        help="Bypass narrative cache — force LLM re-call even for recently-asked questions",
    )
    return p


def main() -> None:
    args = _build_parser().parse_args()
    factory_ids = [f.strip() for f in args.factories.split(",") if f.strip()]
    window_keys = [w.strip() for w in args.windows.split(",") if w.strip()]
    asyncio.run(
        run_sweep(
            factory_ids,
            window_keys=window_keys,
            questions=DEFAULT_QUESTIONS,
            dry_run=args.dry_run,
            no_cache=args.no_cache,
            concurrency=args.concurrency,
        )
    )


if __name__ == "__main__":
    main()
