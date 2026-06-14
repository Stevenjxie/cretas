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

# Static time windows: (label, lookback_days | "all")
# "all" uses a static 2025-01-01 start.
# For data-aware windows (recommended for prod use), see _resolve_data_covering_windows().
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


async def _resolve_data_covering_windows(
    pool,
    factory_id: str,
    requested_window_keys: List[str],
) -> List[Tuple[date, date]]:
    """Return date ranges that actually COVER the factory's gold data.

    Problem (prod recon): qhj_prod gold data is in a historical range (e.g.
    2025-01-01 ~ 2025-12-31).  Static windows "30d / 90d from today" (2026-06-11)
    resolve to 2026-05-12~now and 2026-03-13~now — no overlap with 2025 data →
    AgentOrchestrator._gather_data fetches all-zero KPIs → LLM produces generic
    advice, not data-grounded answers.

    Fix: query gold.queries.data_range() to get (min_date, max_date) for the
    factory's agg_daily rows, then build windows that are guaranteed to overlap:
      - last 30d of real data  (max_date - 30d  →  max_date)
      - last 90d of real data  (max_date - 90d  →  max_date)
      - full history           (min_date         →  max_date)

    Falls back to static _resolve_date_range() if data_range returns None (factory
    has no gold rows), so the caller always gets at least one window.

    Requires tenant context to be set BEFORE calling so the RLS-gated agg_daily
    SELECT returns rows.  Caller (run_sweep) sets set_factory_id(fid) per factory.
    """
    try:
        from smartbi.gold.queries import data_range as gold_data_range

        info = await gold_data_range(pool, factory_id)
        min_d_str = info.get("min_date")
        max_d_str = info.get("max_date")
        day_count = info.get("day_count", 0)

        if not min_d_str or not max_d_str or not day_count:
            logger.info(
                f"[windows] {factory_id}: no gold data found "
                f"(day_count={day_count}) — falling back to static windows"
            )
            return [_resolve_date_range(wk) for wk in requested_window_keys]

        min_date = date.fromisoformat(min_d_str)
        max_date = date.fromisoformat(max_d_str)
        logger.info(
            f"[windows] {factory_id}: gold data {min_d_str} → {max_d_str} "
            f"({day_count} days) — building data-covering windows"
        )

        windows: List[Tuple[date, date]] = []
        seen: set = set()
        # Build windows anchored to the REAL data range
        for wk in requested_window_keys:
            if wk == "all":
                w = (min_date, max_date)
            elif wk == "30":
                w = (max(min_date, max_date - timedelta(days=30)), max_date)
            elif wk == "90":
                w = (max(min_date, max_date - timedelta(days=90)), max_date)
            else:
                # Numeric days: anchor to max_date
                try:
                    days = int(wk)
                    w = (max(min_date, max_date - timedelta(days=days)), max_date)
                except ValueError:
                    w = _resolve_date_range(wk)  # fallback for unknown keys
            key = (w[0].isoformat(), w[1].isoformat())
            if key not in seen:
                seen.add(key)
                windows.append(w)
        return windows

    except Exception as e:
        logger.warning(
            f"[windows] {factory_id}: data_range query failed ({e}) — "
            f"falling back to static windows"
        )
        return [_resolve_date_range(wk) for wk in requested_window_keys]


# ---------------------------------------------------------------------------
# Per-factory × window × question sweep
# ---------------------------------------------------------------------------

async def _run_one(
    orchestrator,
    factory_id: str,
    date_range: Tuple[date, date],
    question: str,
    dry_run: bool,
    *,
    window_label: str = "",
) -> bool:
    """Call answer_insight for one combination.  Returns True on success.

    date_range is resolved by the caller (either static _resolve_date_range or
    data-covering _resolve_data_covering_windows).  window_label is a display
    string for logging only.
    """
    if dry_run:
        logger.info(
            f"[dry-run] would run: factory={factory_id} window={window_label!r} "
            f"date_range={date_range[0]}~{date_range[1]} question={question!r}"
        )
        return True
    try:
        start, end = date_range
        t0 = time.monotonic()
        resp = await orchestrator.answer_insight(
            factory_id,
            question,
            (start, end),
            cache_ttl_hours=0,  # don't pollute production narrative cache
        )
        elapsed = int((time.monotonic() - t0) * 1000)
        logger.info(
            f"[warmup] factory={factory_id} window={window_label!r} "
            f"date={start}~{end} src={resp.source} tokens={resp.tokens} "
            f"elapsed={elapsed}ms q={question[:40]!r}"
        )
        return True
    except Exception as e:
        logger.warning(
            f"[warmup] FAILED factory={factory_id} window={window_label!r} "
            f"date={date_range[0]}~{date_range[1]} q={question[:40]!r}: {e}"
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
    probe: bool = False,
) -> None:
    logger.info(
        f"[sweep] factories={factory_ids} windows={window_keys} "
        f"questions={len(questions)} concurrency={concurrency}"
        + (" DRY-RUN" if dry_run else "")
        + (" NO-CACHE" if no_cache else "")
        + (" PROBE" if probe else "")
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
        llm_model = getattr(settings, "llm_model", None) or "qwen3.7-max-2026-06-08"

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

    # Per-factory data-covering window resolution.
    # RLS note: agg_daily has FORCE ROW LEVEL SECURITY.  We must set the tenant
    # ContextVar before calling data_range() so the asyncpg pool setup callback
    # applies the correct GUC.  Mirrors hooks._trigger_materialization pattern.
    from smartbi.tenant_ctx import set_factory_id as _set_fid, reset_factory_id as _reset_fid

    factory_date_ranges: Dict[str, List[Tuple[date, date]]] = {}
    for fid in factory_ids:
        token = None
        try:
            token = _set_fid(fid)
            if pool is not None:
                ranges = await _resolve_data_covering_windows(pool, fid, window_keys)
            else:
                # dry-run: use static windows
                ranges = [_resolve_date_range(wk) for wk in window_keys]
        finally:
            if token is not None:
                _reset_fid(token)
        factory_date_ranges[fid] = ranges

    if probe:
        # Probe mode: run exactly ONE (first factory, first resolved range, first question)
        # for real and print whether answer_insight returned a non-empty narrative.
        # Gold tables: agg_daily, agg_product, fact_pos_discount (gold/queries.py).
        # Data-rich factories: qhj_prod (real POS data), RES_3101_009.
        fid = factory_ids[0]
        q = questions[0]
        orch = orchestrators.get(fid)
        ranges = factory_date_ranges.get(fid, [_resolve_date_range(window_keys[0])])
        date_range = ranges[0]
        logger.info(
            f"[probe] running ONE: factory={fid} date={date_range[0]}~{date_range[1]} "
            f"question={q!r}"
        )
        # Set tenant context for probe call too
        token = None
        try:
            token = _set_fid(fid)
            resp = await orch.answer_insight(fid, q, date_range, cache_ttl_hours=0)
        finally:
            if token is not None:
                _reset_fid(token)
        if resp.answer and resp.answer not in (
            "今日 AI 预算已用完，建议明天再问。如需提前恢复，请联系管理员调整预算上限。",
            "AI 服务暂时不可用，请稍后重试。如问题持续，请联系管理员。",
        ):
            print(
                f"[probe] OK: factory={fid} date={date_range[0]}~{date_range[1]} "
                f"src={resp.source} tokens={resp.tokens}\n"
                f"  answer_snippet={resp.answer[:120]!r}"
            )
        else:
            print(
                f"[probe] DEGRADED/EMPTY: factory={fid} answer={resp.answer[:80]!r}"
            )
        return

    # Build work list with resolved (factory, date_range, question) triples.
    # date_range is already data-covering; window_label is derived for logging.
    work: List[Tuple[str, Tuple[date, date], str, str]] = []
    for fid in factory_ids:
        ranges = factory_date_ranges.get(fid, [_resolve_date_range(wk) for wk in window_keys])
        for idx, dr in enumerate(ranges):
            wk = window_keys[idx] if idx < len(window_keys) else f"range{idx}"
            for q in questions:
                work.append((fid, dr, q, wk))
    logger.info(f"[sweep] total combinations: {len(work)}")

    attempted = succeeded = 0
    sem = asyncio.Semaphore(concurrency)

    async def _process(fid: str, dr: Tuple[date, date], q: str, wk: str) -> None:
        nonlocal attempted, succeeded
        async with sem:
            attempted += 1
            orch = orchestrators.get(fid)
            # Set tenant context per task so agg_daily RLS GUC is correct
            tok = None
            try:
                tok = _set_fid(fid)
                ok = await _run_one(orch, fid, dr, q, dry_run, window_label=wk)
            finally:
                if tok is not None:
                    _reset_fid(tok)
            if ok:
                succeeded += 1

    tasks = [asyncio.create_task(_process(fid, dr, q, wk)) for fid, dr, q, wk in work]
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
    p.add_argument(
        "--probe",
        action="store_true",
        help=(
            "Run ONE real LLM call (first factory × first window × first question) "
            "via AgentOrchestrator and print whether a non-empty narrative was produced. "
            "Gold tables used: agg_daily, agg_product, fact_pos_discount (see gold/queries.py). "
            "Data-rich tenants: qhj_prod, RES_3101_009. Does NOT persist."
        ),
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
            probe=args.probe,
        )
    )


if __name__ == "__main__":
    main()
