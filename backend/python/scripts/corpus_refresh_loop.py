"""M4 weekly-refresh orchestration loop — one command for the periodic
self-distillation refresh (spec §3 #5 / §6 P2).

Runs in sequence:
  Step 1 — corpus_census    : Print per-bucket readiness (READ-ONLY).
  Step 2 — corpus_judge     : Score quality=3 rows without 'judge' metadata
                               up to --judge-limit (default 200).  Promotes
                               good rows to quality=4.
  Step 3 — gap-fill seeding : For chart_insight buckets BELOW --floor (default
                               200 quality>=4 rows), trigger the seeder in
                               gap-fill mode.  Skipped if --no-seed.
  Step 4 — final census     : Print updated readiness + GREEN summary.

Suggested cron (weekly, Sunday 02:00 server time):
    0 2 * * 0  cd /www/wwwroot/cretas/code/backend/python && \
               source venv38/bin/activate && \
               PYTHONPATH=.:smartbi python -m scripts.corpus_refresh_loop \
               --judge-limit 500 >> /www/wwwroot/cretas/logs/corpus-refresh.log 2>&1

Usage:
    # Dry-run — print all steps, no LLM / DB writes:
    cd backend/python
    python -m scripts.corpus_refresh_loop --dry-run

    # Real run (default limits):
    python -m scripts.corpus_refresh_loop

    # Skip gap-fill seeding (judge only):
    python -m scripts.corpus_refresh_loop --no-seed --judge-limit 300

    # Custom floor and judge limit:
    python -m scripts.corpus_refresh_loop --floor 500 --judge-limit 1000
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import subprocess
import sys
from typing import Optional

# ---------------------------------------------------------------------------
# Path bootstrap
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("corpus_refresh_loop")

# ---------------------------------------------------------------------------
# Step helpers
# ---------------------------------------------------------------------------

def _separator(title: str) -> None:
    width = 70
    print("\n" + "=" * width)
    print(f"  {title}")
    print("=" * width)


async def _step1_census(dsn: Optional[str]) -> None:
    """Run corpus_census (read-only)."""
    _separator("STEP 1 — corpus census (readiness check)")
    from scripts.corpus_census import _run as census_run  # noqa: PLC0415
    await census_run(dsn)


async def _step2_judge(
    judge_limit: int,
    dsn: Optional[str],
    dry_run: bool,
) -> None:
    """Run corpus_judge on quality=3 unjudged rows."""
    _separator(f"STEP 2 — LLM-judge (quality=3→4 promotion, limit={judge_limit})")
    from scripts.corpus_judge import run_judge  # noqa: PLC0415
    await run_judge(
        limit=judge_limit,
        source=None,
        dry_run=dry_run,
        dsn=dsn,
    )


async def _step3_gapfill(
    floor: int,
    dsn: Optional[str],
    dry_run: bool,
    no_seed: bool,
) -> None:
    """Trigger gap-fill seeding for chart_insight buckets below floor."""
    _separator(f"STEP 3 — gap-fill seeding (chart_insight, floor={floor})")

    if no_seed:
        print("  --no-seed: seeding step skipped.")
        logger.info("Gap-fill seeding skipped (--no-seed).")
        return

    # Build the command; we always subprocess this because the seeder imports
    # are heavy and we want isolation in case it fails.
    seeder_script = os.path.join(_SCRIPT_DIR, "seed_chart_insight_corpus.py")
    cmd = [
        sys.executable,
        seeder_script,
        "--gap-fill",
        f"--floor={floor}",
        "--n=200",          # seed up to 200 per gap-fill run (conservative)
    ]
    if dry_run:
        cmd.append("--dry-run")
    if dsn:
        cmd.extend(["--dsn", dsn])

    cmd_str = " ".join(cmd)
    if dry_run:
        print(f"  DRY-RUN: would run: {cmd_str}")
        logger.info("DRY-RUN gap-fill command: %s", cmd_str)
        return

    logger.info("Running gap-fill seeder: %s", cmd_str)
    result = subprocess.run(cmd, capture_output=False)
    if result.returncode != 0:
        logger.warning(
            "Gap-fill seeder exited with code %d — continuing loop.",
            result.returncode,
        )
    else:
        logger.info("Gap-fill seeder completed successfully.")


async def _step4_final_census(dsn: Optional[str]) -> None:
    """Run a final census to show updated GREEN/YELLOW/RED state."""
    _separator("STEP 4 — final census (post-refresh state)")
    from scripts.corpus_census import _run as census_run  # noqa: PLC0415
    await census_run(dsn)


# ---------------------------------------------------------------------------
# Main orchestration
# ---------------------------------------------------------------------------

async def run_refresh_loop(
    judge_limit: int = 200,
    floor: int = 200,
    no_seed: bool = False,
    dry_run: bool = False,
    dsn: Optional[str] = None,
) -> None:
    """Run the full M4 weekly-refresh sequence.

    Args:
        judge_limit: Max rows to judge in Step 2.
        floor: Minimum quality>=4 count per bucket before gap-fill triggers.
        no_seed: Skip Step 3 entirely.
        dry_run: Print all steps, perform no LLM calls or DB writes.
        dsn: Optional asyncpg DSN override for all steps.
    """
    mode_tag = "DRY-RUN" if dry_run else "LIVE"
    _separator(f"M4 WEEKLY CORPUS REFRESH LOOP  [{mode_tag}]")
    print(
        f"  judge_limit = {judge_limit}\n"
        f"  gap-fill floor = {floor}\n"
        f"  seed = {'disabled' if no_seed else 'enabled'}\n"
        f"  dry_run = {dry_run}"
    )

    # Step 1: initial census
    await _step1_census(dsn)

    # Step 2: LLM-judge
    await _step2_judge(judge_limit, dsn, dry_run)

    # Step 3: gap-fill seeding
    await _step3_gapfill(floor, dsn, dry_run, no_seed)

    # Step 4: final census
    await _step4_final_census(dsn)

    # Summary
    _separator("REFRESH LOOP COMPLETE")
    if dry_run:
        print("  DRY-RUN: no DB or LLM operations were performed.")
    else:
        print("  All steps completed.  Check census above for GREEN bucket count.")
    print(
        "\n  Suggested cron (weekly Sunday 02:00):\n"
        "  0 2 * * 0  cd /www/wwwroot/cretas/code/backend/python && \\\n"
        "             source venv38/bin/activate && \\\n"
        "             PYTHONPATH=.:smartbi python -m scripts.corpus_refresh_loop \\\n"
        "             --judge-limit 500 >> "
        "/www/wwwroot/cretas/logs/corpus-refresh.log 2>&1\n"
    )


# ---------------------------------------------------------------------------
# CLI entry-point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="M4 weekly-refresh loop: census → judge → gap-fill → census.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--judge-limit", type=int, default=200,
        help="Max rows to judge in Step 2 (default: 200).",
    )
    parser.add_argument(
        "--floor", type=int, default=200,
        help="Bucket floor: gap-fill triggers when quality>=4 count < floor (default: 200).",
    )
    parser.add_argument(
        "--no-seed", action="store_true",
        help="Skip Step 3 gap-fill seeding entirely.",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Print all steps without performing LLM calls or DB writes.",
    )
    parser.add_argument(
        "--dsn", default=os.environ.get("SMARTBI_PROD_DSN", ""),
        help="asyncpg DSN override (default: SMARTBI_PROD_DSN env or app settings).",
    )
    parser.add_argument(
        "--log-level", default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    asyncio.run(run_refresh_loop(
        judge_limit=args.judge_limit,
        floor=args.floor,
        no_seed=args.no_seed,
        dry_run=args.dry_run,
        dsn=args.dsn or None,
    ))


if __name__ == "__main__":
    main()
