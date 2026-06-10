"""ETL: cretas_db intent_match_records / smartbi_db smart_bi_llm_fallback_log
→ smart_bi_distillation_samples (source='intent_llm', task_type='intent').

This is a one-time + re-runnable (idempotent via input_hash) admin ETL job that
cold-starts the intent-routing corpus bucket by harvesting EXISTING (query →
LLM-answer) pairs that were written at intent-inference time.

Sources
-------
1. cretas_db.intent_match_records
   WHERE llm_called = TRUE AND llm_response IS NOT NULL
   These are the LLM-fallback rows where the Java AIIntentService called the LLM
   to resolve ambiguous user queries. The llm_response is the raw teacher answer.

2. smartbi_db.smart_bi_llm_fallback_log
   WHERE answer IS NOT NULL AND source IS NULL OR source = 'fallback'
   SmartBI chat queries that were escalated to the LLM. These are intent-adjacent
   (free-form data queries) and may not be strict intent-routing labels, so they
   are included but rated quality=3 by default and labelled with etl_source so
   Opus can review them later.

Cross-DB design
---------------
Both databases live on the same PostgreSQL server (localhost:5432). The ETL
opens two separate asyncpg connections:
  - cretas_read_dsn  → cretas_db (source of intent_match_records)
  - smartbi_write_dsn → smartbi_db / smartbi_prod_db (destination corpus +
                         source of smart_bi_llm_fallback_log)

DSN resolution (priority order):
  1. CLI flags --cretas-dsn / --smartbi-dsn
  2. Env vars CRETAS_DB_DSN / SMARTBI_DB_DSN
  3. Derived from the app's existing Settings:
       cretas  = food_kb_db_url  (points to cretas_db, same as get_cretas_pool)
       smartbi = postgres_url    (points to smartbi_db / smartbi_prod_db)

RLS note
--------
intent_match_records has no RLS (it uses soft-delete via deleted_at, not
per-tenant RLS — BaseEntity + @Where(clause="deleted_at IS NULL")). The app user
that cretas_db runs as (cretas_user) can read all rows. smart_bi_distillation_samples
has NO RLS intentionally (see migration comment: internal ML sink). This ETL is an
admin job, never exposed to a user API.

Usage
-----
    cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
    PYTHONPATH=.:smartbi python scripts/etl_intent_corpus.py --dry-run
    PYTHONPATH=.:smartbi python scripts/etl_intent_corpus.py --limit 5000
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from typing import Any, Dict, List, Optional

# Path bootstrap — main.py adds backend/python AND backend/python/smartbi to sys.path so
# bare `from services.X import ...` (used transitively by distillation_capture) resolves to
# smartbi/services/X. Standalone scripts must mirror it or persist fails "No module named services".
_PYTHON_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("etl_intent_corpus")

# ---------------------------------------------------------------------------
# Business-type derivation
# ---------------------------------------------------------------------------
_RESTAURANT_PREFIXES = ("qhj",)  # known restaurant factory_id prefixes (lower-cased)
_FACTORY_PREFIXES = ("f",)       # known manufacturing factory_id prefixes (e.g. F001, F006)


def _derive_business_type(factory_id: Optional[str]) -> str:
    """Derive 业态 from factory_id.

    Convention used across the codebase:
      qhj* (case-insensitive)  → 'restaurant'
      F* / f* (F001, F006 …)  → 'factory'
      None / unknown           → 'unknown'
    """
    if not factory_id:
        return "unknown"
    fid = factory_id.strip().lower()
    for prefix in _RESTAURANT_PREFIXES:
        if fid.startswith(prefix):
            return "restaurant"
    for prefix in _FACTORY_PREFIXES:
        if fid.startswith(prefix):
            return "factory"
    return "unknown"


# ---------------------------------------------------------------------------
# input_text builder — make it self-contained (per §7 safety)
# ---------------------------------------------------------------------------

def _build_intent_input_text(
    user_input: str,
    top_candidates: Optional[str],
    factory_id: Optional[str],
) -> str:
    """Build a self-contained input_text for intent-routing distillation.

    Format:
        intent_routing|query:{user_input}|candidates:{top_candidates_json_or_none}|factory:{factory_id}

    The 'intent_routing|' prefix makes the task type unambiguous when the
    distillation corpus is later filtered / split.  Including top_candidates
    (the shortlist of intent codes the Java side surfaced) gives the student
    model realistic context about the routing decision.
    """
    candidates_str = "none"
    if top_candidates:
        try:
            # top_candidates is stored as JSON string in the DB; include it
            # compactly so the hash stays deterministic for the same input.
            parsed = json.loads(top_candidates)
            candidates_str = json.dumps(parsed, ensure_ascii=False, separators=(",", ":"))
        except (json.JSONDecodeError, TypeError):
            candidates_str = top_candidates.strip()

    fid = factory_id or "unknown"
    return f"intent_routing|query:{user_input}|candidates:{candidates_str}|factory:{fid}"


def _build_fallback_input_text(query: str, factory_id: Optional[str]) -> str:
    """Build self-contained input_text for smart_bi_llm_fallback_log rows."""
    fid = factory_id or "unknown"
    return f"intent_routing|query:{query}|candidates:none|factory:{fid}"


# ---------------------------------------------------------------------------
# Quality mapping
# ---------------------------------------------------------------------------

def _quality_from_confirmed(user_confirmed: Optional[bool]) -> int:
    """Map user_confirmed column to corpus quality score.

    true  → 4  (user explicitly confirmed the LLM's routing = verified)
    other → 3  (unverified but plausible — LLM answered, no explicit rejection)
    """
    if user_confirmed is True:
        return 4
    return 3


def _quality_from_feedback(user_feedback: Optional[int]) -> int:
    """Map user_feedback SMALLINT to quality.

    1  → 4 (thumbs-up → high quality)
    other → 3 (no signal → unverified)
    """
    if user_feedback is not None and user_feedback > 0:
        return 4
    return 3


# ---------------------------------------------------------------------------
# Persistence via distillation_capture
# ---------------------------------------------------------------------------

async def _persist_one(
    pool,
    *,
    input_text: str,
    teacher_output: str,
    business_type: str,
    factory_id: Optional[str],
    quality: int,
    teacher_model: Optional[str],
    metadata: Dict[str, Any],
    dry_run: bool,
) -> bool:
    """Persist one sample, returns True if written (or would be in dry-run)."""
    if not input_text or not teacher_output:
        return False
    if dry_run:
        return True
    try:
        from smartbi.services.distillation_capture import persist_distillation_sample
        await persist_distillation_sample(
            pool,
            source="intent_llm",
            task_type="intent",
            input_text=input_text,
            teacher_output=teacher_output,
            business_type=business_type,
            factory_id=factory_id,
            system_prompt=None,
            teacher_model=teacher_model or "unknown",
            template_codes=None,
            quality=quality,
            metadata=metadata,
        )
        return True
    except Exception as exc:
        logger.warning("persist failed for input_text[:80]=%r: %s", input_text[:80], exc)
        return False


# ---------------------------------------------------------------------------
# Source 1: cretas_db.intent_match_records
# ---------------------------------------------------------------------------

async def _etl_intent_match_records(
    cretas_conn,
    smartbi_pool,
    *,
    limit: int,
    dry_run: bool,
) -> int:
    """Read LLM-fallback rows from cretas_db and write to smartbi corpus.

    Returns number of rows processed (written or would-be in dry-run).
    """
    sql = """
        SELECT
            id,
            factory_id,
            user_input,
            llm_response,
            llm_intent_code,
            matched_intent_code,
            user_confirmed,
            top_candidates,
            created_at
        FROM intent_match_records
        WHERE llm_called = TRUE
          AND llm_response IS NOT NULL
          AND deleted_at IS NULL
        ORDER BY created_at DESC
        LIMIT $1
    """
    rows = await cretas_conn.fetch(sql, limit)
    logger.info("[intent_match_records] fetched %d rows (limit=%d)", len(rows), limit)

    written = 0
    for row in rows:
        user_input = row["user_input"] or ""
        llm_response = row["llm_response"] or ""
        factory_id = row["factory_id"]

        if not user_input.strip() or not llm_response.strip():
            continue

        input_text = _build_intent_input_text(
            user_input=user_input,
            top_candidates=row["top_candidates"],
            factory_id=factory_id,
        )
        business_type = _derive_business_type(factory_id)
        quality = _quality_from_confirmed(row["user_confirmed"])
        metadata = {
            "etl_source": "intent_match_records",
            "matched_intent": row["matched_intent_code"],
            "llm_intent": row["llm_intent_code"],
            "record_id": str(row["id"]) if row["id"] else None,
        }

        ok = await _persist_one(
            smartbi_pool,
            input_text=input_text,
            teacher_output=llm_response,
            business_type=business_type,
            factory_id=factory_id,
            quality=quality,
            teacher_model=None,  # not recorded in intent_match_records
            metadata=metadata,
            dry_run=dry_run,
        )
        if ok:
            written += 1

    return written


# ---------------------------------------------------------------------------
# Source 2: smartbi_db.smart_bi_llm_fallback_log
# ---------------------------------------------------------------------------

async def _etl_llm_fallback_log(
    smartbi_pool,
    *,
    limit: int,
    dry_run: bool,
) -> int:
    """Read LLM-fallback rows from smartbi_db.smart_bi_llm_fallback_log.

    Returns number of rows processed.
    """
    sql = """
        SELECT
            id,
            query,
            factory_id,
            answer,
            user_feedback,
            source,
            created_at
        FROM smart_bi_llm_fallback_log
        WHERE answer IS NOT NULL
          AND (source IS NULL OR source = 'fallback')
        ORDER BY created_at DESC
        LIMIT $1
    """
    async with smartbi_pool.acquire() as conn:
        rows = await conn.fetch(sql, limit)
    logger.info("[smart_bi_llm_fallback_log] fetched %d rows (limit=%d)", len(rows), limit)

    written = 0
    for row in rows:
        query = row["query"] or ""
        answer = row["answer"] or ""
        factory_id = row["factory_id"]

        if not query.strip() or not answer.strip():
            continue

        input_text = _build_fallback_input_text(query=query, factory_id=factory_id)
        business_type = _derive_business_type(factory_id)
        quality = _quality_from_feedback(row["user_feedback"])
        metadata = {
            "etl_source": "fallback_log",
            "record_id": row["id"],
            "user_feedback": row["user_feedback"],
        }

        ok = await _persist_one(
            smartbi_pool,
            input_text=input_text,
            teacher_output=answer,
            business_type=business_type,
            factory_id=factory_id,
            quality=quality,
            teacher_model=None,
            metadata=metadata,
            dry_run=dry_run,
        )
        if ok:
            written += 1

    return written


# ---------------------------------------------------------------------------
# Pre/post corpus counts
# ---------------------------------------------------------------------------

async def _corpus_count(pool, source: str) -> int:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT COUNT(*) AS n FROM smart_bi_distillation_samples WHERE source = $1",
            source,
        )
        return int(row["n"]) if row else 0


# ---------------------------------------------------------------------------
# DSN resolution helpers
# ---------------------------------------------------------------------------

def _resolve_dsn(
    cli_dsn: Optional[str],
    env_var: str,
    settings_attr: str,
) -> Optional[str]:
    """Resolve a DSN in priority order: CLI → env-var → Settings."""
    if cli_dsn:
        return cli_dsn
    env = os.getenv(env_var)
    if env:
        return env
    try:
        from smartbi.config import get_settings
        settings = get_settings()
        url = getattr(settings, settings_attr, None)
        return url or None
    except Exception as exc:
        logger.debug("Could not load Settings for %s: %s", settings_attr, exc)
        return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def main() -> None:
    ap = argparse.ArgumentParser(
        description=(
            "P1-2 intent_llm ETL: harvest (query → LLM-answer) pairs from "
            "cretas_db.intent_match_records and smartbi_db.smart_bi_llm_fallback_log "
            "into smart_bi_distillation_samples (source='intent_llm', task_type='intent')."
        )
    )
    ap.add_argument(
        "--limit", type=int, default=10_000,
        help="max rows to read from each source (default: 10000)",
    )
    ap.add_argument(
        "--dry-run", action="store_true",
        help="print what would be ETL'd without writing anything",
    )
    ap.add_argument(
        "--cretas-dsn", default=None,
        help="asyncpg DSN for cretas_db (overrides CRETAS_DB_DSN env + Settings)",
    )
    ap.add_argument(
        "--smartbi-dsn", default=None,
        help="asyncpg DSN for smartbi_db (overrides SMARTBI_DB_DSN env + Settings)",
    )
    ap.add_argument(
        "--skip-fallback-log", action="store_true",
        help="skip smart_bi_llm_fallback_log source (intent_match_records only)",
    )
    args = ap.parse_args()

    # -- Resolve DSNs ---------------------------------------------------------
    cretas_dsn = _resolve_dsn(args.cretas_dsn, "CRETAS_DB_DSN", "food_kb_db_url")
    smartbi_dsn = _resolve_dsn(args.smartbi_dsn, "SMARTBI_DB_DSN", "postgres_url")

    if not cretas_dsn:
        logger.error(
            "Cannot resolve cretas_db DSN. Set --cretas-dsn, CRETAS_DB_DSN env, "
            "or configure food_kb_db_url in .env"
        )
        sys.exit(1)
    if not smartbi_dsn:
        logger.error(
            "Cannot resolve smartbi_db DSN. Set --smartbi-dsn, SMARTBI_DB_DSN env, "
            "or configure postgres_url in .env"
        )
        sys.exit(1)

    if args.dry_run:
        logger.info("[DRY-RUN] No writes will occur.")

    # -- Connect --------------------------------------------------------------
    import asyncpg

    logger.info("Connecting to cretas_db …")
    cretas_conn = await asyncpg.connect(cretas_dsn)

    logger.info("Creating smartbi_db pool …")
    smartbi_pool = await asyncpg.create_pool(smartbi_dsn, min_size=1, max_size=4)

    try:
        # Pre-count
        count_before = await _corpus_count(smartbi_pool, "intent_llm")
        logger.info("[corpus] rows with source='intent_llm' BEFORE: %d", count_before)

        # Source 1: intent_match_records (cretas_db)
        written1 = await _etl_intent_match_records(
            cretas_conn=cretas_conn,
            smartbi_pool=smartbi_pool,
            limit=args.limit,
            dry_run=args.dry_run,
        )
        logger.info("[intent_match_records] %s %d rows", "would write" if args.dry_run else "wrote", written1)

        # Source 2: smart_bi_llm_fallback_log (smartbi_db)
        written2 = 0
        if not args.skip_fallback_log:
            written2 = await _etl_llm_fallback_log(
                smartbi_pool=smartbi_pool,
                limit=args.limit,
                dry_run=args.dry_run,
            )
            logger.info(
                "[smart_bi_llm_fallback_log] %s %d rows",
                "would write" if args.dry_run else "wrote",
                written2,
            )

        # Post-count
        count_after = await _corpus_count(smartbi_pool, "intent_llm")
        logger.info("[corpus] rows with source='intent_llm' AFTER:  %d", count_after)
        logger.info(
            "[corpus] net new (may be <total due to ON CONFLICT refresh): %d",
            count_after - count_before,
        )
        logger.info(
            "ETL complete — intent_match_records: %d, fallback_log: %d, total: %d",
            written1, written2, written1 + written2,
        )

    finally:
        await cretas_conn.close()
        await smartbi_pool.close()


if __name__ == "__main__":
    asyncio.run(main())
