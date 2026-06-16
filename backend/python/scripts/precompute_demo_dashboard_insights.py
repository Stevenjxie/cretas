"""Precompute 经营驾驶舱 insights for demo tenants → warm corpus for instant serve.

Drives AgentOrchestrator.answer_insight() for each demo factory × time window ×
question, persisting (corpus_input_text → LLM_answer) pairs into
smart_bi_distillation_samples with quality=5 and source='agent_insight'.

After running, opening the dashboard for any demo tenant triggers the new
corpus read-back path (added to orchestrator.py step 1b), returning in O(ms)
with 0 LLM calls.

Demo tenants (restaurant AgentOrchestrator makes sense for these):
  DEMO_FACTORY   — 白垩纪 demo 工厂 1 (restaurant section)
  DEMO_FACTORY2  — 白垩纪 demo 工厂 2 (restaurant section)
  DEMO_REST      — demo 纯餐饮租户
  F004           — demo 租户 4

Usage::

    # Dry-run: list what would be run, no LLM calls
    python -m scripts.precompute_demo_dashboard_insights --dry-run

    # Full precompute (calls LLM, persists corpus rows)
    python -m scripts.precompute_demo_dashboard_insights

    # Force re-run even if rows already exist (ON CONFLICT updates created_at)
    python -m scripts.precompute_demo_dashboard_insights --force

    # Limit to specific tenants
    python -m scripts.precompute_demo_dashboard_insights --factories DEMO_REST,F004

LLM cost line (HARD RULE — per memory feedback_llm_cost_lanes_beyond_router):
  Uses SLOT.INSIGHTS (allowlist: qwen3-max via aliyun_b/C accounts with quota).
  Disabled allowlist models are NOT called — the router's denylist + per-account
  expiry gates enforce this.  Do NOT bypass the router (e.g. direct httpx).
  Monitor DashScope console after run to confirm spend stays in free tier.

Sys.path bootstrap: mirrors sweep_agent_insight_warmup.py pattern.
LLM env from systemd: mirrors sweep_agent_insight_warmup.py pattern.
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
# Path bootstrap (mirrors sweep_agent_insight_warmup.py)
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
if _PYTHON_ROOT not in sys.path:
    sys.path.insert(0, _PYTHON_ROOT)
# main.py adds smartbi/ to sys.path so bare `from services.X import ...` resolves.
_SMARTBI_DIR = os.path.join(_PYTHON_ROOT, "smartbi")
if _SMARTBI_DIR not in sys.path:
    sys.path.insert(0, _SMARTBI_DIR)

# ---------------------------------------------------------------------------
# LLM env bootstrap (for standalone execution outside uvicorn)
# ---------------------------------------------------------------------------
def _bootstrap_llm_env() -> None:
    """Pull LLM env vars from the live cretas-python systemd process environ.

    When run as a standalone script (not inside FastAPI), the LLM API keys
    are not inherited.  The systemd service stores them in the process environ.
    This mirrors the pattern in sweep_agent_insight_warmup.py and
    scripts/corpus_judge.py.

    Falls through silently if the service is not running or /proc is unavailable
    (e.g. Windows dev environment — keys must be set in the shell instead).
    """
    try:
        import subprocess
        result = subprocess.run(
            ["systemctl", "show", "cretas-python", "-p", "MainPID"],
            capture_output=True, text=True, timeout=5,
        )
        main_pid_line = result.stdout.strip()
        if not main_pid_line:
            return
        main_pid = main_pid_line.split("=", 1)[-1].strip()
        if not main_pid or main_pid == "0":
            return
        environ_path = f"/proc/{main_pid}/environ"
        if not os.path.exists(environ_path):
            return
        with open(environ_path, "rb") as f:
            env_bytes = f.read()
        for entry in env_bytes.split(b"\x00"):
            if b"=" in entry:
                k, _, v = entry.partition(b"=")
                key = k.decode("utf-8", errors="replace")
                val = v.decode("utf-8", errors="replace")
                if key not in os.environ and any(
                    key.startswith(p)
                    for p in ("LLM_", "DASHSCOPE_", "POSTGRES_", "SMARTBI_")
                ):
                    os.environ[key] = val
        logger.info("[env] bootstrapped LLM/DB env from cretas-python PID %s", main_pid)
    except Exception as e:
        logger.debug("[env] systemd env bootstrap skipped: %s", e)


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("precompute_demo_dashboard_insights")

# ---------------------------------------------------------------------------
# Demo tenants (restaurant data makes sense for AgentOrchestrator)
# ---------------------------------------------------------------------------
DEMO_RESTAURANT_FACTORIES: List[str] = [
    "DEMO_FACTORY",
    "DEMO_FACTORY2",
    "DEMO_REST",
    "F004",
]

# Questions covering the main intent categories in AgentOrchestrator
DEMO_QUESTIONS: List[str] = [
    "本期整体营业情况如何？",
    "哪些门店表现最好，哪些最差？",
    "最热卖的菜品是什么？",
    "折扣活动对营业额有什么影响？",
    "客单价变化趋势如何？",
]


# ---------------------------------------------------------------------------
# Budget-bypass stub (offline sweep, no per-factory quota consumption)
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
# Census helpers
# ---------------------------------------------------------------------------

async def _count_corpus_rows(pool, source: str = "agent_insight") -> int:
    """Count distillation rows for this source (pre/post sweep delta logging)."""
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT COUNT(*) AS n FROM smart_bi_distillation_samples WHERE source = $1",
                source,
            )
        return int(row["n"]) if row else 0
    except Exception as e:
        logger.warning("[census] count query failed: %s", e)
        return -1


async def _count_today_corpus_rows(pool, source: str = "agent_insight") -> int:
    """Count distillation rows created today."""
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT COUNT(*) AS n FROM smart_bi_distillation_samples
                WHERE source = $1 AND created_at::date = CURRENT_DATE
                """,
                source,
            )
        return int(row["n"]) if row else 0
    except Exception as e:
        logger.warning("[census] today count query failed: %s", e)
        return -1


# ---------------------------------------------------------------------------
# Date window resolution
# ---------------------------------------------------------------------------

async def _resolve_data_covering_windows(
    pool,
    factory_id: str,
) -> List[Tuple[date, date]]:
    """Build date windows anchored to the factory's REAL gold data range.

    Returns up to 3 windows:
      - last 30 days of real data
      - last 90 days of real data
      - full history

    Falls back to calendar-based windows (last 30/90/365 days) if the factory
    has no gold data.
    """
    try:
        from smartbi.gold.queries import data_range as gold_data_range
        info = await gold_data_range(pool, factory_id)
        min_d_str = info.get("min_date")
        max_d_str = info.get("max_date")
        day_count = info.get("day_count", 0)
        if not min_d_str or not max_d_str or not day_count:
            logger.info(
                "[windows] %s: no gold data (day_count=%d) — using calendar fallback",
                factory_id, day_count,
            )
            return _static_windows()
        min_date = date.fromisoformat(min_d_str)
        max_date = date.fromisoformat(max_d_str)
        logger.info(
            "[windows] %s: gold data %s → %s (%d days)",
            factory_id, min_d_str, max_d_str, day_count,
        )
        return [
            (max(min_date, max_date - timedelta(days=30)), max_date),
            (max(min_date, max_date - timedelta(days=90)), max_date),
            (min_date, max_date),
        ]
    except Exception as e:
        logger.warning("[windows] %s: data_range failed (%s) — using calendar fallback", factory_id, e)
        return _static_windows()


def _static_windows() -> List[Tuple[date, date]]:
    today = date.today()
    return [
        (today - timedelta(days=30), today),
        (today - timedelta(days=90), today),
        (today - timedelta(days=365), today),
    ]


# ---------------------------------------------------------------------------
# Per-combination sweep
# ---------------------------------------------------------------------------

async def _run_one(
    orchestrator,
    factory_id: str,
    date_range: Tuple[date, date],
    question: str,
    *,
    dry_run: bool,
    window_label: str = "",
) -> bool:
    """Run one (factory, date_range, question) combination. Returns True on success."""
    if dry_run:
        logger.info(
            "[dry-run] would precompute: factory=%s window=%s date=%s~%s q=%r",
            factory_id, window_label, date_range[0], date_range[1], question,
        )
        return True
    try:
        t0 = time.monotonic()
        # cache_ttl_hours=0 so we don't pollute the narrative cache with low-TTL
        # demo rows; the corpus persist is the durable artifact we want.
        resp = await orchestrator.answer_insight(
            factory_id, question, date_range, cache_ttl_hours=0,
        )
        elapsed = int((time.monotonic() - t0) * 1000)
        logger.info(
            "[precompute] factory=%s window=%s src=%s tokens=%d elapsed=%dms q=%r",
            factory_id, window_label, resp.source, resp.tokens, elapsed, question,
        )
        return True
    except Exception as e:
        logger.warning(
            "[precompute] FAILED factory=%s window=%s q=%r: %s",
            factory_id, window_label, question, e,
        )
        return False


# ---------------------------------------------------------------------------
# Main sweep
# ---------------------------------------------------------------------------

async def run_precompute(
    factory_ids: List[str],
    questions: List[str],
    *,
    dry_run: bool,
    force: bool,
    concurrency: int,
) -> None:
    logger.info(
        "[sweep] factories=%s questions=%d concurrency=%d%s%s",
        factory_ids, len(questions), concurrency,
        " DRY-RUN" if dry_run else "",
        " FORCE" if force else "",
    )

    if not dry_run:
        _bootstrap_llm_env()

    from smartbi.config import get_pg_pool, get_settings

    if not dry_run:
        pool = await get_pg_pool()
        if pool is None:
            logger.error("[sweep] could not obtain DB pool — aborting")
            return
    else:
        pool = None

    before_total = await _count_corpus_rows(pool, "agent_insight") if pool else -1
    before_today = await _count_today_corpus_rows(pool, "agent_insight") if pool else -1
    logger.info("[census] before: agent_insight total=%d today=%d", before_total, before_today)

    # Build orchestrators per factory (one shared pool, budget bypassed)
    orchestrators: Dict[str, Any] = {}
    if not dry_run:
        settings = get_settings()
        llm_api_key = (
            getattr(settings, "llm_api_key", None)
            or os.environ.get("LLM_API_KEY")
            or os.environ.get("DASHSCOPE_API_KEY")
            or ""
        )
        llm_base_url = (
            getattr(settings, "llm_base_url", None)
            or "https://dashscope.aliyuncs.com/compatible-mode/v1"
        )
        llm_model = getattr(settings, "llm_model", None) or "qwen3.7-max-2026-06-08"

        from smartbi.agent.orchestrator import AgentOrchestrator
        from smartbi.agent.narrative_cache import NarrativeCacheService

        for fid in factory_ids:
            orchestrators[fid] = AgentOrchestrator(
                pool=pool,
                llm_base_url=llm_base_url,
                llm_api_key=llm_api_key,
                llm_model=llm_model,
                budget_tracker=_NoBudgetTracker(),
                # Use real narrative cache (TTL=0 per _run_one, so no pollution)
                cache=NarrativeCacheService(pool),
            )

    # Per-factory data-covering window resolution (set tenant ContextVar for RLS)
    from smartbi.tenant_ctx import set_factory_id as _set_fid, reset_factory_id as _reset_fid

    factory_date_ranges: Dict[str, List[Tuple[date, date]]] = {}
    for fid in factory_ids:
        token = None
        try:
            token = _set_fid(fid)
            if pool is not None:
                ranges = await _resolve_data_covering_windows(pool, fid)
            else:
                ranges = _static_windows()
        finally:
            if token is not None:
                _reset_fid(token)
        factory_date_ranges[fid] = ranges

    # Build work list: (factory, date_range, question, window_label) tuples
    work: List[Tuple[str, Tuple[date, date], str, str]] = []
    for fid in factory_ids:
        ranges = factory_date_ranges.get(fid, _static_windows())
        for idx, dr in enumerate(ranges):
            labels = ["30d", "90d", "all"]
            wlabel = labels[idx] if idx < len(labels) else f"range{idx}"
            for q in questions:
                work.append((fid, dr, q, wlabel))
    logger.info("[sweep] total combinations: %d", len(work))

    attempted = succeeded = 0
    sem = asyncio.Semaphore(concurrency)

    async def _process(fid: str, dr: Tuple[date, date], q: str, wlabel: str) -> None:
        nonlocal attempted, succeeded
        async with sem:
            attempted += 1
            orch = orchestrators.get(fid)
            tok = None
            try:
                tok = _set_fid(fid)
                ok = await _run_one(orch, fid, dr, q, dry_run=dry_run, window_label=wlabel)
            finally:
                if tok is not None:
                    _reset_fid(tok)
            if ok:
                succeeded += 1

    tasks = [asyncio.create_task(_process(fid, dr, q, wl)) for fid, dr, q, wl in work]
    await asyncio.gather(*tasks, return_exceptions=True)

    after_total = await _count_corpus_rows(pool, "agent_insight") if pool else -1
    after_today = await _count_today_corpus_rows(pool, "agent_insight") if pool else -1
    added = (after_today - before_today) if before_today >= 0 and after_today >= 0 else "?"
    logger.info(
        "[census] after: agent_insight total=%d today=%d (added/refreshed today ~%s)",
        after_total, after_today, added,
    )
    logger.info(
        "[sweep] done: attempted=%d succeeded=%d%s",
        attempted, succeeded, " (dry-run)" if dry_run else "",
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            "Precompute 经营驾驶舱 insights for demo tenants, warming the "
            "serve-from-corpus flywheel so dashboards open instantly (0 LLM calls)."
        ),
    )
    p.add_argument(
        "--factories",
        default=",".join(DEMO_RESTAURANT_FACTORIES),
        help=(
            f"Comma-separated factory IDs "
            f"(default: {','.join(DEMO_RESTAURANT_FACTORIES)})"
        ),
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="List combinations without calling LLM or persisting anything",
    )
    p.add_argument(
        "--force",
        action="store_true",
        help=(
            "Force LLM re-run even if today's corpus row already exists "
            "(ON CONFLICT updates the row — same effect as normal run, "
            "included for documentation)"
        ),
    )
    p.add_argument(
        "--concurrency",
        type=int,
        default=2,
        help="Max concurrent LLM calls (default 2; keep low to avoid rate-limit)",
    )
    return p


def main() -> None:
    args = _build_parser().parse_args()
    factory_ids = [f.strip() for f in args.factories.split(",") if f.strip()]
    asyncio.run(
        run_precompute(
            factory_ids,
            DEMO_QUESTIONS,
            dry_run=args.dry_run,
            force=args.force,
            concurrency=args.concurrency,
        )
    )


if __name__ == "__main__":
    main()
