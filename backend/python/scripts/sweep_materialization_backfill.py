"""P1 cold-start sweep: re-run materialization over historical uploads.

For each upload in ``smart_bi_pg_excel_uploads`` belonging to the requested
factories, invoke the same materialization + LLM-insight pipeline that fires
at upload time (``hooks._trigger_materialization``).  Each run writes
(structured-context → LLM-insight) pairs into ``smart_bi_distillation_samples``
via the shared ``persist_distillation_sample`` path; the ON CONFLICT refresh
makes repeated runs idempotent.

Key design decisions
--------------------
- **Drives the REAL pipeline** (``generate_llm_insights`` → distillation
  capture) — zero synthetic inputs, highest corpus quality.
- **Idempotent**: ``smart_bi_distillation_samples`` has ``UNIQUE(input_hash)``;
  re-materializing the same upload only refreshes the existing row.
- **Budget bypass**: the materialization path (``llm_materializer``) calls
  ``llm_client.call_llm`` directly (not through ``AgentBudgetTracker``), so
  the per-factory daily token quota is not consumed.  The distillation gate
  ``SMARTBI_DISTILL_CAPTURE`` must be "1" (the default).
- **Tenant isolation**: set ``current_factory_id`` ContextVar before any pool
  acquire, matching the pattern in ``hooks._trigger_materialization``.

Usage (on server, PYTHONPATH=. from backend/python/)::

    # Dry-run: list uploads without LLM/DB writes
    python -m scripts.sweep_materialization_backfill --dry-run

    # Real sweep for all default factories (limit 50 uploads per factory)
    python -m scripts.sweep_materialization_backfill --limit 50

    # Specific factories
    python -m scripts.sweep_materialization_backfill --factories F001,F006,qhj_prod --limit 20

    # Unlimited (sweep all historical uploads)
    python -m scripts.sweep_materialization_backfill --factories F001 --limit 0

Environment
-----------
Same as the running server: DASHSCOPE_API_KEY / LLM_API_KEY for real LLM;
POSTGRES_* or SMARTBI_PROD_DSN for the DB pool.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Path bootstrap: works as ``python -m scripts.sweep_*`` (cwd = backend/python)
# or as a plain ``python scripts/sweep_*.py``.
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
logger = logging.getLogger("sweep_materialization_backfill")

# ---------------------------------------------------------------------------
# Default factory list — mirrors backfill and census conventions.
# F006 has no POS source so gold-based templates won't apply, but its
# Excel uploads (finance / production data) still benefit from materialization.
# ---------------------------------------------------------------------------
DEFAULT_FACTORIES: List[str] = ["F001", "F006", "qhj_prod", "RES_3101_009", "R_GML_DEMO"]

# Factories that are restaurant-domain (to set ContextVar correctly).
_RESTAURANT_PREFIXES = ("QHJ", "RES_", "R_", "RESTAURANT")


def _is_restaurant(factory_id: str) -> bool:
    u = factory_id.upper()
    return any(u.startswith(p) for p in _RESTAURANT_PREFIXES)


# ---------------------------------------------------------------------------
# Census helpers — query corpus rows before/after to report delta
# ---------------------------------------------------------------------------

async def _count_corpus_rows(pool, source: str = "materialization") -> int:
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
# Upload enumeration
# ---------------------------------------------------------------------------

async def _list_uploads(
    pool,
    factory_ids: List[str],
    limit: int,
) -> List[Dict[str, Any]]:
    """Return upload rows (id, factory_id, file_name, row_count) for the
    requested factories, newest first.  limit=0 → no limit."""
    rows: List[Dict[str, Any]] = []
    for fid in factory_ids:
        try:
            async with pool.acquire() as conn:
                if limit > 0:
                    fetched = await conn.fetch(
                        """SELECT id, factory_id, file_name, row_count, created_at
                           FROM smart_bi_pg_excel_uploads
                           WHERE factory_id = $1
                           ORDER BY created_at DESC
                           LIMIT $2""",
                        fid, limit,
                    )
                else:
                    fetched = await conn.fetch(
                        """SELECT id, factory_id, file_name, row_count, created_at
                           FROM smart_bi_pg_excel_uploads
                           WHERE factory_id = $1
                           ORDER BY created_at DESC""",
                        fid,
                    )
            rows.extend(dict(r) for r in fetched)
            logger.info(f"[enum] {fid}: found {len(fetched)} uploads")
        except Exception as e:
            logger.warning(f"[enum] {fid}: query failed: {e}")
    return rows


# ---------------------------------------------------------------------------
# Per-upload materialization (mirrors hooks._trigger_materialization)
# ---------------------------------------------------------------------------

async def _rematerialize_upload(
    pool,
    upload_id: int,
    factory_id: str,
    dry_run: bool,
) -> bool:
    """Re-run materialization + LLM insight generation for one upload.

    Returns True if materialization was attempted (or would be in dry_run),
    False if a pre-check error aborted early.
    """
    if dry_run:
        logger.info(f"[dry-run] would rematerialize upload_id={upload_id} factory={factory_id}")
        return True

    tenant_token = None
    try:
        from smartbi.tenant_ctx import set_factory_id, reset_factory_id
        from smartbi.services.materialized_analytics.materializer import (
            build_schema, materialize_upload,
        )
        from smartbi.services.materialized_analytics.persistence import (
            save_materialization_results,
        )
        from smartbi.services.materialized_analytics.schema import Domain
        from smartbi.services.materialized_analytics.llm_materializer import (
            generate_llm_insights,
        )

        tenant_token = set_factory_id(factory_id)

        t0 = time.time()
        materialize_ctx: dict = {}
        results = await materialize_upload(pool, upload_id, _out_ctx=materialize_ctx)

        # Derive domain from field_defs (mirrors hooks logic)
        async with pool.acquire() as conn:
            field_rows = await conn.fetch(
                """SELECT original_name, is_measure, is_dimension, is_time
                   FROM smart_bi_pg_field_definitions WHERE upload_id = $1
                   ORDER BY display_order""",
                upload_id,
            )
            upload_row = await conn.fetchrow(
                "SELECT row_count FROM smart_bi_pg_excel_uploads WHERE id = $1",
                upload_id,
            )
        field_meta = [dict(r) for r in field_rows]
        row_count = (upload_row["row_count"] or 0) if upload_row else 0
        if field_meta:
            schema = build_schema(upload_id, factory_id, field_meta, row_count)
            domain_value = schema.domain.value
        else:
            domain_value = Domain.UNKNOWN.value

        # LLM insight materialization — this is where distillation samples are written
        applied = 0
        try:
            exec_summary = await generate_llm_insights(results, domain_value, factory_id=factory_id)
            applied = sum(1 for r in results if r.llm_insight)
            logger.info(
                f"[mat] upload {upload_id}: LLM insights applied={applied},"
                f" domain={domain_value}, wall={time.time()-t0:.1f}s"
                + (" + exec summary" if exec_summary else "")
            )
        except Exception as llm_err:
            logger.warning(f"[mat] upload {upload_id}: LLM insight gen failed: {llm_err}")

        # Persist (idempotent ON CONFLICT DO NOTHING for templates)
        await save_materialization_results(pool, upload_id, factory_id, domain_value, results)
        return True

    except Exception as e:
        logger.error(f"[mat] upload {upload_id}: failed: {e}", exc_info=True)
        return False
    finally:
        if tenant_token is not None:
            try:
                from smartbi.tenant_ctx import reset_factory_id
                reset_factory_id(tenant_token)
            except Exception:
                pass


# ---------------------------------------------------------------------------
# Main sweep
# ---------------------------------------------------------------------------

async def run_sweep(
    factory_ids: List[str],
    limit: int,
    dry_run: bool,
    *,
    concurrency: int = 2,
    probe: bool = False,
) -> None:
    """Enumerate uploads, rematerialize each, report census delta."""
    if probe:
        # --probe: run ONE upload (first factory, first upload) for real;
        # print whether distillation rows were written.  Does NOT persist nothing
        # extra — the pipeline writes its own ON CONFLICT idempotent rows.
        logger.info(f"[probe] mode enabled — running ONE upload from first factory")
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
        if pool is None:
            print("[probe] ERROR: could not obtain DB pool")
            return
        # Pick one upload from the first factory
        probe_uploads = await _list_uploads(pool, factory_ids[:1], limit=1)
        if not probe_uploads:
            print(f"[probe] NO UPLOADS found for factory={factory_ids[0]!r}")
            return
        u = probe_uploads[0]
        fid = u["factory_id"]
        upload_id = u["id"]
        fname = u.get("file_name") or "?"
        before = await _count_corpus_rows(pool, "materialization")
        logger.info(f"[probe] calling _rematerialize_upload: upload_id={upload_id} factory={fid} file={fname!r}")
        ok = await _rematerialize_upload(pool, upload_id, fid, dry_run=False)
        after = await _count_corpus_rows(pool, "materialization")
        added = (after - before) if before >= 0 and after >= 0 else "?"
        if ok and added != "?" and int(str(added)) > 0:
            print(
                f"[probe] OK: factory={fid} upload_id={upload_id} file={fname!r}"
                f"  rows_before={before} rows_after={after} added={added}"
            )
        elif ok:
            print(
                f"[probe] COMPLETED (no new rows — likely idempotent re-run or no LLM output):"
                f" factory={fid} upload_id={upload_id} file={fname!r}"
                f" rows_before={before} rows_after={after}"
            )
        else:
            print(
                f"[probe] FAILED: factory={fid} upload_id={upload_id} file={fname!r}"
                f" (check logs for exception)"
            )
        return

    if dry_run:
        logger.info(
            f"[sweep] DRY-RUN: factories={factory_ids} limit_per_factory={limit or 'all'}"
        )
        # Dry-run: list planned work without connecting to DB
        for fid in factory_ids:
            logger.info(
                f"[dry-run] would enumerate uploads for factory={fid} limit={limit or 'all'}"
            )
            logger.info(
                f"[dry-run] would rematerialize each upload: set_factory_id → "
                f"materialize_upload → generate_llm_insights → save_materialization_results"
            )
        logger.info(
            f"[dry-run] factories={factory_ids} limit_per_factory={limit or 'all'} "
            f"concurrency={concurrency}"
        )
        logger.info("[census] before: materialization rows = ? (dry-run, no DB)")
        logger.info("[census] after:  materialization rows = ? (dry-run, no DB)")
        logger.info("[sweep] done (dry-run, no LLM calls, no DB writes)")
        return

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        logger.error("[sweep] could not obtain DB pool — aborting")
        return

    # Pre-sweep census
    before = await _count_corpus_rows(pool, "materialization")
    logger.info(f"[census] before: materialization rows = {before}")

    uploads = await _list_uploads(pool, factory_ids, limit)
    logger.info(f"[sweep] total uploads to process: {len(uploads)}")
    if not uploads:
        logger.info("[sweep] nothing to do")
        return

    attempted = succeeded = 0
    sem = asyncio.Semaphore(concurrency)

    async def _process_one(upload: Dict[str, Any]) -> None:
        nonlocal attempted, succeeded
        async with sem:
            upload_id = upload["id"]
            fid = upload["factory_id"]
            fname = upload.get("file_name") or "?"
            logger.info(
                f"[sweep] processing upload_id={upload_id} factory={fid} file={fname!r}"
            )
            attempted += 1
            ok = await _rematerialize_upload(pool, upload_id, fid, dry_run)
            if ok:
                succeeded += 1

    tasks = [asyncio.create_task(_process_one(u)) for u in uploads]
    await asyncio.gather(*tasks, return_exceptions=True)

    # Post-sweep census
    after = await _count_corpus_rows(pool, "materialization")
    added = (after - before) if before >= 0 and after >= 0 else "?"
    logger.info(
        f"[census] after:  materialization rows = {after}  (added/refreshed ~{added})"
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
        description="P1 cold-start: re-materialize historical uploads to fill distillation corpus.",
    )
    p.add_argument(
        "--factories",
        default=",".join(DEFAULT_FACTORIES),
        help=f"Comma-separated factory IDs (default: {','.join(DEFAULT_FACTORIES)})",
    )
    p.add_argument(
        "--limit",
        type=int,
        default=50,
        help="Max uploads per factory (0 = all; default 50)",
    )
    p.add_argument(
        "--concurrency",
        type=int,
        default=2,
        help="Max concurrent uploads (default 2)",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="List uploads without running LLM / persisting",
    )
    p.add_argument(
        "--probe",
        action="store_true",
        help=(
            "Run ONE real materialization (first factory, first upload) and print "
            "whether distillation rows were written. Pipeline: set_factory_id → "
            "materialize_upload → generate_llm_insights → save_materialization_results. "
            "Data-rich factories by default: F001, F006, qhj_prod, RES_3101_009. Does NOT skip persist."
        ),
    )
    return p


def main() -> None:
    args = _build_parser().parse_args()
    factory_ids = [f.strip() for f in args.factories.split(",") if f.strip()]
    asyncio.run(
        run_sweep(
            factory_ids,
            limit=args.limit,
            dry_run=args.dry_run,
            concurrency=args.concurrency,
            probe=args.probe,
        )
    )


if __name__ == "__main__":
    main()
