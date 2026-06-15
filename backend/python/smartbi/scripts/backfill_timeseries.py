"""Backfill smart_bi_timeseries for existing COMPLETED uploads.

Phase 2 Task 5 — wires the same extract+write path as excel_async.py:_async_worker_impl
but driven from the command line, operating over all historical uploads that
predate the timeseries hook being added.

Usage
-----
    # All factories, all domains (dry-run first)
    python -m smartbi.scripts.backfill_timeseries --dry-run

    # Single factory
    python -m smartbi.scripts.backfill_timeseries --factory F001

    # Single domain only (e.g. production)
    python -m smartbi.scripts.backfill_timeseries --factory F001 --domain production

Idempotent: write_timeseries uses per-period replace, newest-wins guard.
Replaying the same upload twice leaves the table identical to a single run.

See spec §6 + plan Task 5.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from typing import Any, Dict, List, Optional

import asyncpg

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Helpers — mirrors of what excel_async._async_worker_impl does
# ---------------------------------------------------------------------------


def _fmt_period(v: Any) -> Optional[str]:
    """Truncate period values to VARCHAR(50); handle datetime/date.

    Mirrors excel_async.py:_fmt_period exactly.
    """
    if v is None:
        return None
    s = str(v)
    # Prefer ISO date (YYYY-MM-DD) if string is a longer ISO timestamp
    if len(s) > 10 and s[4] == "-" and s[7] == "-":
        s = s[:10]
    return s[:50] if len(s) > 50 else s


def _normalize_field_mappings(raw: Any) -> Dict[str, str]:
    """Normalise field_mappings JSONB (dict OR legacy array-of-objects form).

    asyncpg returns JSONB as a parsed Python object (dict / list).
    The upload pipeline stores dicts: {original_col: standard_name}.
    Older uploads may carry the Java array-of-objects form.
    """
    import json as _json

    if raw is None:
        return {}
    if isinstance(raw, str):
        raw = _json.loads(raw) if raw else None
        if raw is None:
            return {}
    if isinstance(raw, dict):
        return {str(k): str(v) for k, v in raw.items() if v is not None}
    if isinstance(raw, list):
        out: Dict[str, str] = {}
        for item in raw:
            if not isinstance(item, dict):
                continue
            orig = item.get("originalColumn") or item.get("original")
            std = item.get("standardField") or item.get("standard")
            if orig:
                out[str(orig)] = str(std) if std else ""
        return out
    return {}


# ---------------------------------------------------------------------------
# Core backfill logic for one upload
# ---------------------------------------------------------------------------


async def backfill_one_upload(
    conn: asyncpg.Connection,
    *,
    upload_id: int,
    factory_id: str,
    field_mappings_raw: Any,
    detected_table_type: Optional[str],
    dry_run: bool,
) -> Dict[str, Any]:
    """Process a single upload: extract timeseries rows + write (or dry-run).

    Returns a summary dict with keys: upload_id, extracted, written, skipped.
    Never raises — logs errors and returns skipped=True on failure.
    """
    try:
        from smartbi.services.timeseries_extractor import extract_timeseries
        from smartbi.services.timeseries_writer import write_timeseries
        from smartbi.services.semantic_mapper import SemanticMapper

        # ── 1. Resolve field_mappings {original_col → standard_name} ─────────
        field_mappings = _normalize_field_mappings(field_mappings_raw)
        if not field_mappings:
            logger.warning(
                "[backfill] upload=%d has no field_mappings, skipping", upload_id
            )
            return {"upload_id": upload_id, "extracted": 0, "written": 0, "skipped": True}

        # ── 2. Load field_definitions for this upload ─────────────────────────
        # We need the is_measure/is_dimension/is_time flags to build field_kind.
        # Key by standard_name (mirrors excel_async _kind_map construction).
        field_def_rows = await conn.fetch(
            """
            SELECT standard_name, is_measure, is_dimension, is_time
              FROM smart_bi_pg_field_definitions
             WHERE upload_id = $1
            """,
            upload_id,
        )

        if not field_def_rows:
            logger.warning(
                "[backfill] upload=%d has no field_definitions, skipping", upload_id
            )
            return {"upload_id": upload_id, "extracted": 0, "written": 0, "skipped": True}

        # Build _kind_map {standard_name → 'measure'|'dimension'|'time'|'skip'}
        # ⛔ Must use field_def flags, NOT STANDARD_FIELDS/category_of — that's
        # the #899 bug: identity-mapped columns like "产出数量" have no STANDARD_FIELDS
        # entry and would be silently dropped. The DB flags are always correct.
        _kind_map: Dict[str, str] = {}
        for row in field_def_rows:
            sname = row["standard_name"]
            if row["is_measure"]:
                _kind_map[sname] = "measure"
            elif row["is_dimension"]:
                _kind_map[sname] = "dimension"
            elif row["is_time"]:
                _kind_map[sname] = "time"
            else:
                _kind_map[sname] = "skip"

        def _field_kind(standard_name: str) -> str:
            return _kind_map.get(standard_name, "skip")

        # ── 3. Determine period column (mirrors excel_async logic) ────────────
        # The time column is whichever original column maps to a standard_name
        # whose field_def has is_time=True.
        time_standard_names = {sn for sn, k in _kind_map.items() if k == "time"}
        # Find original columns whose standard_name is in the time set
        time_original_cols = [
            orig
            for orig, std in field_mappings.items()
            if std in time_standard_names
        ]
        # Also check if any original col maps to the literal standard name "period"
        period_mapping_col = next(
            (orig for orig, std in field_mappings.items() if std == "period"),
            None,
        )
        # Primary time col: first is_time column (same heuristic as excel_async
        # which calls find_time_column which picks the first is_time def)
        time_col = time_original_cols[0] if time_original_cols else None

        def _period_of(row: Dict[str, Any]) -> Optional[str]:
            """Extract and format period from a raw row dict."""
            if time_col and time_col in row:
                return _fmt_period(row.get(time_col))
            if period_mapping_col and period_mapping_col in row:
                return _fmt_period(row.get(period_mapping_col))
            return None

        # ── 4. Compute template_key from original column headers ───────────────
        # headers = the original column names (keys of field_mappings)
        headers = list(field_mappings.keys())
        _template_key = SemanticMapper._compute_template_key(headers)

        # ── 5. Fetch dynamic_data rows ─────────────────────────────────────────
        # Use server-side cursor inside a transaction (asyncpg cursor requirement)
        # to avoid loading potentially hundreds of thousands of rows into memory.
        parsed_rows: List[Dict[str, Any]] = []
        async with conn.transaction():
            async for pg_row in conn.cursor(
                "SELECT row_data FROM smart_bi_dynamic_data WHERE upload_id = $1"
                " ORDER BY row_index ASC",
                upload_id,
            ):
                row_data = pg_row["row_data"]
                if isinstance(row_data, str):
                    import json as _json
                    row_data = _json.loads(row_data)
                parsed_rows.append(row_data)

        if not parsed_rows:
            logger.info(
                "[backfill] upload=%d has 0 dynamic_data rows, nothing to extract",
                upload_id,
            )
            return {"upload_id": upload_id, "extracted": 0, "written": 0, "skipped": False}

        # ── 6. Extract timeseries rows (pure function, no DB) ─────────────────
        domain = detected_table_type or None
        ts_rows = extract_timeseries(
            parsed_rows,
            field_mappings,
            factory_id=factory_id,
            template_key=_template_key,
            domain=domain,
            source_upload_id=upload_id,
            period_of=_period_of,
            field_kind=_field_kind,
        )

        extracted = len(ts_rows)

        if dry_run:
            # Report plan without writing
            periods = sorted({r.period for r in ts_rows if r.period})
            fields = sorted({r.canonical_field for r in ts_rows})
            logger.info(
                "[backfill] DRY-RUN upload=%d factory=%s domain=%s template=%s "
                "would_write=%d periods=%s fields=%s",
                upload_id, factory_id, domain, _template_key,
                extracted, periods[:5], fields[:10],
            )
            return {
                "upload_id": upload_id,
                "extracted": extracted,
                "written": 0,
                "skipped": False,
                "dry_run": True,
                "periods": periods,
            }

        # ── 7. Write (per-period replace, newest-wins guard) ──────────────────
        written = await write_timeseries(
            factory_id,
            _template_key,
            ts_rows,
            upload_id,
        )

        logger.info(
            "[backfill] upload=%d factory=%s domain=%s template=%s "
            "extracted=%d written=%d",
            upload_id, factory_id, domain, _template_key, extracted, written,
        )
        return {"upload_id": upload_id, "extracted": extracted, "written": written, "skipped": False}

    except Exception:
        logger.exception(
            "[backfill] failed for upload=%d factory=%s", upload_id, factory_id
        )
        return {"upload_id": upload_id, "extracted": 0, "written": 0, "skipped": True}


# ---------------------------------------------------------------------------
# Main backfill loop
# ---------------------------------------------------------------------------


async def run_backfill(
    *,
    factory_id: Optional[str],
    domain: Optional[str],
    dry_run: bool,
    postgres_url: str,
) -> None:
    """Iterate over COMPLETED uploads in ascending id order and backfill each."""
    from smartbi.tenant_ctx import set_pg_connection_tenant

    pool = await asyncpg.create_pool(
        postgres_url,
        min_size=1,
        max_size=3,
        setup=set_pg_connection_tenant,
    )

    total_uploads = 0
    total_extracted = 0
    total_written = 0
    total_skipped = 0

    try:
        async with pool.acquire() as conn:
            # Build upload query: COMPLETED, optional factory + domain filter,
            # ascending id order (oldest first → newest-wins guard works correctly:
            # each later upload can overwrite an earlier one for the same period)
            query_parts = ["upload_status = 'COMPLETED'"]
            params: List[Any] = []

            if factory_id:
                params.append(factory_id)
                query_parts.append(f"factory_id = ${len(params)}")

            if domain:
                params.append(domain)
                query_parts.append(f"detected_table_type = ${len(params)}")

            where_clause = " AND ".join(query_parts)
            query = (
                f"SELECT id, factory_id, field_mappings, detected_table_type"
                f"  FROM smart_bi_pg_excel_uploads"
                f" WHERE {where_clause}"
                f" ORDER BY id ASC"
            )

            uploads = await conn.fetch(query, *params)

        logger.info(
            "[backfill] found %d COMPLETED uploads to process%s%s",
            len(uploads),
            f" factory={factory_id}" if factory_id else "",
            f" domain={domain}" if domain else "",
        )

        for upload_row in uploads:
            upload_id = upload_row["id"]
            upload_factory_id = upload_row["factory_id"]

            # Each upload needs its own connection with the correct tenant GUC.
            # GUC is scoped to transaction; write_timeseries sets it internally
            # inside its own transaction. For the field_defs + dynamic_data read
            # we don't need RLS (those tables use upload_id not factory_id as the
            # primary isolation key), so a plain connection is fine.
            async with pool.acquire() as conn:
                result = await backfill_one_upload(
                    conn,
                    upload_id=upload_id,
                    factory_id=upload_factory_id,
                    field_mappings_raw=upload_row["field_mappings"],
                    detected_table_type=upload_row["detected_table_type"],
                    dry_run=dry_run,
                )

            total_uploads += 1
            total_extracted += result.get("extracted", 0)
            total_written += result.get("written", 0)
            if result.get("skipped"):
                total_skipped += 1

    finally:
        await pool.close()

    print(f"\n{'DRY-RUN ' if dry_run else ''}Backfill complete:")
    print(f"  uploads processed : {total_uploads}")
    print(f"  uploads skipped   : {total_skipped}")
    print(f"  timeseries rows extracted : {total_extracted}")
    if not dry_run:
        print(f"  timeseries rows written   : {total_written}")
    else:
        print(f"  timeseries rows (would write): {total_extracted}")


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Backfill smart_bi_timeseries from existing COMPLETED uploads. "
            "Processes uploads in ascending id order (oldest first) so the "
            "newest-wins guard in write_timeseries works correctly. Idempotent."
        )
    )
    parser.add_argument(
        "--factory",
        metavar="FACTORY_ID",
        default=None,
        help="Restrict to a single factory (e.g. F001). Omit to backfill all.",
    )
    parser.add_argument(
        "--domain",
        metavar="DOMAIN",
        default=None,
        help=(
            "Restrict to uploads with this detected_table_type "
            "(e.g. 'production', 'finance'). Omit to process all domains."
        ),
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help=(
            "Print what would be written without touching smart_bi_timeseries. "
            "Reads dynamic_data + field_defs and runs extract_timeseries, "
            "but skips write_timeseries."
        ),
    )
    return parser


async def _main_cli() -> int:
    parser = _build_arg_parser()
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    # Standalone sys.path bootstrap: when run as `python -m smartbi.scripts.backfill_timeseries`
    # from backend/python/ the package is on the path automatically. When run as
    # `python smartbi/scripts/backfill_timeseries.py` directly we need to add the
    # project root. Mirror the pattern used in backfill_silver.py.
    import os as _os
    _here = _os.path.dirname(_os.path.abspath(__file__))
    _project_root = _os.path.abspath(_os.path.join(_here, "..", "..", ".."))
    if _project_root not in sys.path:
        sys.path.insert(0, _project_root)

    try:
        from smartbi.config import get_settings
    except ImportError as exc:
        print(
            f"ERROR: could not import smartbi.config: {exc}\n"
            f"Run from backend/python/ or set PYTHONPATH=backend/python",
            file=sys.stderr,
        )
        return 1

    settings = get_settings()
    if not settings.postgres_url:
        print("ERROR: No Postgres URL configured (SMARTBI_DATABASE_URL / postgres_url)", file=sys.stderr)
        return 1

    await run_backfill(
        factory_id=args.factory,
        domain=args.domain,
        dry_run=args.dry_run,
        postgres_url=settings.postgres_url,
    )
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(_main_cli()))
