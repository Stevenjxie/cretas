"""G3 human spot-check sampler for the global self-distillation corpus.

Produces human-reviewable files from quality≥4 corpus rows so that a human
reviewer can verify that the LLM judge (G2) is not systematically passing
low-quality data.

G3 protocol summary
-------------------
1. SAMPLE  -- pick N rows per (source, business_type) bucket from quality≥4 rows.
2. EXPORT  -- write /tmp/g3_review_<bucket>.md (or JSONL) with input + output +
              blank verdict field for the reviewer.
3. RECORD  -- after human review, call --record mode to persist pass-rate into
              the corpus_g3_results table so the ≥90% threshold can be tracked.

Pass-rate gate: spec requires ≥90% pass rate per bucket.  If a bucket falls
below 90%, its quality=4 rows should be re-audited or the teacher prompt revised.

Usage:
    # Sample 30 rows across all buckets and write review files:
    cd backend/python
    python -m scripts.corpus_g3_sample --sample --n 30

    # Sample for a specific source:
    python -m scripts.corpus_g3_sample --sample --n 20 --source chat_qa

    # Record a human spot-check result after reviewing the file:
    python -m scripts.corpus_g3_sample --record --bucket chat_qa --pass-count 28 --total 30

    # Dry-run: show what would be sampled, no file writes:
    python -m scripts.corpus_g3_sample --sample --n 30 --dry-run

Environment variables:
    SMARTBI_PROD_DSN   -- asyncpg DSN.  Falls back to settings.postgres_url.

Output format (markdown):
    Each bucket file: /tmp/g3_review_<source>_<business_type>.md
    Contains rows with input_text, teacher_output, and a blank ## Verdict section.
    The reviewer fills in PASS/FAIL + comments for each item.

corpus_g3_results table (created on first --record call if absent):
    id          SERIAL PRIMARY KEY
    bucket      TEXT NOT NULL  -- "<source>_<business_type>" or "<source>_all"
    pass_count  INT  NOT NULL
    total       INT  NOT NULL
    pass_rate   NUMERIC(5,2) NOT NULL  -- pass_count / total * 100
    reviewed_at TIMESTAMP DEFAULT NOW()
    notes       TEXT
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from typing import Any, Dict, List, Optional

# ---------------------------------------------------------------------------
# Path bootstrap — mirrors corpus_judge.py pattern.
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logger = logging.getLogger("corpus_g3_sample")

# ---------------------------------------------------------------------------
# SQL
# ---------------------------------------------------------------------------

_SAMPLE_SQL = """
    SELECT id, input_text, teacher_output, metadata, source,
           COALESCE(metadata->>'business_type', 'all') AS business_type
    FROM smart_bi_distillation_samples
    WHERE quality >= 4
    {source_clause}
    ORDER BY RANDOM()
    LIMIT $1
"""

# Grouped sample: N per (source, business_type) combination via LATERAL
_SAMPLE_GROUPED_SQL = """
    SELECT s.id, s.input_text, s.teacher_output, s.metadata, s.source,
           COALESCE(s.metadata->>'business_type', 'all') AS business_type
    FROM (
        SELECT DISTINCT
               source,
               COALESCE(metadata->>'business_type', 'all') AS bt
        FROM smart_bi_distillation_samples
        WHERE quality >= 4
        {source_clause}
    ) buckets
    JOIN LATERAL (
        SELECT id, input_text, teacher_output, metadata, source
        FROM smart_bi_distillation_samples
        WHERE quality >= 4
          AND source = buckets.source
          AND COALESCE(metadata->>'business_type', 'all') = buckets.bt
          {source_inner_clause}
        ORDER BY RANDOM()
        LIMIT $1
    ) s ON TRUE
"""

_CREATE_G3_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS corpus_g3_results (
        id          SERIAL PRIMARY KEY,
        bucket      TEXT        NOT NULL,
        pass_count  INT         NOT NULL,
        total       INT         NOT NULL,
        pass_rate   NUMERIC(5,2) NOT NULL,
        reviewed_at TIMESTAMP   NOT NULL DEFAULT NOW(),
        notes       TEXT
    );
"""

_INSERT_G3_SQL = """
    INSERT INTO corpus_g3_results (bucket, pass_count, total, pass_rate, notes)
    VALUES ($1, $2, $3, $4, $5)
    RETURNING id, reviewed_at
"""

# ---------------------------------------------------------------------------
# DB helpers
# ---------------------------------------------------------------------------


async def _make_pool(dsn: Optional[str]):
    """Create asyncpg pool. Returns None if no DB creds available."""
    import asyncpg  # noqa: PLC0415

    effective_dsn = dsn
    if not effective_dsn:
        try:
            from smartbi.config import get_settings  # noqa: PLC0415
            effective_dsn = get_settings().postgres_url
        except Exception as exc:
            logger.warning("Could not load settings for pool DSN: %s", exc)
            return None

    if not effective_dsn:
        logger.warning("No DSN available — pool will be None.")
        return None

    try:
        pool = await asyncpg.create_pool(effective_dsn, min_size=1, max_size=3)
        logger.info("asyncpg pool created (DSN prefix: %s...)", effective_dsn[:30])
        return pool
    except Exception as exc:
        logger.error("Failed to create asyncpg pool: %s", exc)
        return None


async def fetch_sample_rows(
    pool,
    n: int,
    source: Optional[str] = None,
    per_bucket: bool = True,
) -> List[Dict[str, Any]]:
    """Fetch up to N quality>=4 rows per (source, business_type) bucket.

    Args:
        pool: asyncpg pool.  If None, returns [].
        n: Number of rows per bucket (per_bucket=True) or total (per_bucket=False).
        source: Optional filter — if set, only rows from this source.
        per_bucket: If True, draw N rows from EACH (source, business_type) bucket.
                    If False, draw N rows total (random across all buckets).
    """
    if pool is None:
        return []

    if per_bucket:
        source_clause = "AND source = $2" if source else ""
        source_inner_clause = "AND source = $2" if source else ""
        sql = _SAMPLE_GROUPED_SQL.format(
            source_clause=source_clause,
            source_inner_clause=source_inner_clause,
        )
        async with pool.acquire() as conn:
            params = [n, source] if source else [n]
            rows = await conn.fetch(sql, *params)
    else:
        source_clause = "AND source = $2" if source else ""
        sql = _SAMPLE_SQL.format(source_clause=source_clause)
        async with pool.acquire() as conn:
            params = [n, source] if source else [n]
            rows = await conn.fetch(sql, *params)

    return [
        {
            "id": row["id"],
            "input_text": row["input_text"] or "",
            "teacher_output": row["teacher_output"] or "",
            "metadata": row["metadata"],
            "source": row["source"] or "unknown",
            "business_type": row["business_type"] or "all",
        }
        for row in rows
    ]


# ---------------------------------------------------------------------------
# File generation
# ---------------------------------------------------------------------------


def _bucket_key(source: str, business_type: str) -> str:
    """Build a filesystem-safe bucket key."""
    bt = business_type.strip() or "all"
    # Replace spaces/slashes with underscores
    safe_source = source.replace("/", "_").replace(" ", "_")
    safe_bt = bt.replace("/", "_").replace(" ", "_")
    return f"{safe_source}_{safe_bt}"


def group_rows_by_bucket(
    rows: List[Dict[str, Any]]
) -> Dict[str, List[Dict[str, Any]]]:
    """Group sample rows by (source, business_type) bucket key."""
    buckets: Dict[str, List[Dict[str, Any]]] = {}
    for row in rows:
        key = _bucket_key(row["source"], row.get("business_type", "all"))
        buckets.setdefault(key, []).append(row)
    return buckets


def write_markdown_review_file(
    bucket_key: str,
    rows: List[Dict[str, Any]],
    output_dir: str = "/tmp",
) -> str:
    """Write a human-reviewable markdown file for a bucket.

    The file contains each row's input + teacher output and a blank ## Verdict
    section for the human reviewer to fill in (PASS/FAIL + comments).

    Returns the absolute path to the written file.
    """
    output_path = os.path.join(output_dir, f"g3_review_{bucket_key}.md")
    lines: List[str] = [
        f"# G3 Human Spot-Check — Bucket: `{bucket_key}`\n",
        f"**Row count**: {len(rows)}\n",
        "**Instructions**: For each item below, fill in the ## Verdict section.\n",
        "  - Mark `PASS` if the answer is factually grounded, actionable, and clear.\n",
        "  - Mark `FAIL` if the answer fabricates data, is misleading, or is unusable.\n",
        "  - Add brief notes explaining FAIL verdicts.\n",
        "\n---\n",
    ]

    for i, row in enumerate(rows, start=1):
        input_text = row["input_text"][:2000]
        teacher_output = row["teacher_output"][:2000]
        meta_display = ""
        if row.get("metadata"):
            try:
                meta_obj = row["metadata"] if isinstance(row["metadata"], dict) else json.loads(row["metadata"])
                # Only show a small subset of metadata fields
                interesting = {k: v for k, v in meta_obj.items() if k in (
                    "source", "business_type", "factory_id", "intent_code")}
                if interesting:
                    meta_display = f"\n**Metadata**: {json.dumps(interesting, ensure_ascii=False)}"
            except Exception:
                pass

        lines.append(f"## Item {i}  (id={row['id']})\n")
        lines.append(
            f"**Source**: `{row['source']}`  |  **Business type**: `{row.get('business_type', 'all')}`{meta_display}\n")
        lines.append("\n### Input Context\n")
        lines.append("```\n")
        lines.append(input_text)
        if len(row["input_text"]) > 2000:
            lines.append("\n... [truncated]")
        lines.append("\n```\n")
        lines.append("\n### Teacher Output\n")
        lines.append("```\n")
        lines.append(teacher_output)
        if len(row["teacher_output"]) > 2000:
            lines.append("\n... [truncated]")
        lines.append("\n```\n")
        lines.append("\n## Verdict\n")
        lines.append("<!-- Fill in: PASS or FAIL -->\n")
        lines.append("**Result**: \n")
        lines.append("\n**Notes**: \n")
        lines.append("\n---\n")

    content = "".join(lines)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(content)

    return output_path


def write_jsonl_review_file(
    bucket_key: str,
    rows: List[Dict[str, Any]],
    output_dir: str = "/tmp",
) -> str:
    """Write a JSONL review file (alternative to markdown) for a bucket.

    Each line is a JSON object with id, source, business_type, input_text,
    teacher_output, and a blank 'verdict' field for human review.

    Returns the absolute path to the written file.
    """
    output_path = os.path.join(output_dir, f"g3_review_{bucket_key}.jsonl")
    with open(output_path, "w", encoding="utf-8") as f:
        for row in rows:
            record = {
                "id": row["id"],
                "source": row["source"],
                "business_type": row.get("business_type", "all"),
                "input_text": row["input_text"][:3000],
                "teacher_output": row["teacher_output"][:2000],
                "verdict": "",   # human fills this in
                "notes": "",
            }
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    return output_path


# ---------------------------------------------------------------------------
# G3 record persistence
# ---------------------------------------------------------------------------


async def ensure_g3_table(pool) -> None:
    """Create corpus_g3_results table if it does not exist."""
    if pool is None:
        return
    async with pool.acquire() as conn:
        await conn.execute(_CREATE_G3_TABLE_SQL)


async def record_g3_result(
    pool,
    bucket: str,
    pass_count: int,
    total: int,
    notes: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    """Insert a human spot-check result into corpus_g3_results.

    Args:
        pool: asyncpg pool.
        bucket: Bucket key (e.g. 'chat_qa_all').
        pass_count: Number of rows the human reviewer marked PASS.
        total: Total rows reviewed.
        notes: Optional reviewer notes.

    Returns:
        Dict with {id, bucket, pass_count, total, pass_rate, reviewed_at} or None.
    """
    if pool is None:
        logger.error("record_g3_result: no DB pool — cannot persist result")
        return None
    if total <= 0:
        logger.error("record_g3_result: total must be > 0 (got %d)", total)
        return None
    if pass_count < 0 or pass_count > total:
        logger.error(
            "record_g3_result: pass_count=%d out of range [0, %d]",
            pass_count, total,
        )
        return None

    from decimal import Decimal, ROUND_HALF_UP
    pass_rate = float(
        (Decimal(pass_count) / Decimal(total) * 100).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP
        )
    )

    await ensure_g3_table(pool)

    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            _INSERT_G3_SQL,
            bucket,
            pass_count,
            total,
            pass_rate,
            notes,
        )

    result = {
        "id": row["id"],
        "bucket": bucket,
        "pass_count": pass_count,
        "total": total,
        "pass_rate": pass_rate,
        "reviewed_at": str(row["reviewed_at"]),
    }
    logger.info(
        "G3 result recorded: bucket=%r pass_rate=%.2f%% (%d/%d) id=%d",
        bucket, pass_rate, pass_count, total, result["id"],
    )

    # Warn if below the spec threshold
    if pass_rate < 90.0:
        logger.warning(
            "G3 BELOW THRESHOLD: bucket=%r pass_rate=%.2f%% < 90%%. "
            "Consider re-auditing quality=4 rows for this bucket or revising the teacher prompt.",
            bucket, pass_rate,
        )
    return result


# ---------------------------------------------------------------------------
# Main runners
# ---------------------------------------------------------------------------


async def run_sample(
    n: int,
    source: Optional[str] = None,
    output_dir: str = "/tmp",
    fmt: str = "md",
    dry_run: bool = False,
    dsn: Optional[str] = None,
) -> None:
    """Sample N rows per bucket and write review files.

    Args:
        n: Rows per (source, business_type) bucket.
        source: Optional source filter.
        output_dir: Directory to write review files into.
        fmt: 'md' for markdown, 'jsonl' for JSONL.
        dry_run: Print counts only, no file writes.
        dsn: asyncpg DSN override.
    """
    pool = await _make_pool(dsn) if not dry_run else None
    rows = await fetch_sample_rows(pool or None, n, source, per_bucket=True)

    if dry_run:
        buckets = group_rows_by_bucket(rows)
        print(
            f"\nG3 SAMPLE DRY-RUN: would sample from {len(buckets)} bucket(s), "
            f"{len(rows)} rows total\n"
            f"  n={n} per bucket  source={source!r}  fmt={fmt!r}\n"
        )
        for bk, brows in sorted(buckets.items()):
            print(f"  bucket={bk!r}: {len(brows)} rows")
        print("\nNo files written.")
        if pool is not None:
            await pool.close()
        return

    buckets = group_rows_by_bucket(rows)
    if not buckets:
        print("\nG3 SAMPLE: no quality>=4 rows found matching the filter.")
        if pool is not None:
            await pool.close()
        return

    written_files: List[str] = []
    for bucket_key, bucket_rows in sorted(buckets.items()):
        if fmt == "jsonl":
            path = write_jsonl_review_file(bucket_key, bucket_rows, output_dir)
        else:
            path = write_markdown_review_file(bucket_key, bucket_rows, output_dir)
        written_files.append(path)
        logger.info("G3 sample written: bucket=%r rows=%d file=%s", bucket_key, len(bucket_rows), path)

    print(
        f"\nG3 SAMPLE COMPLETE\n"
        f"  buckets : {len(buckets)}\n"
        f"  rows    : {len(rows)}\n"
        f"  files   :\n"
    )
    for p in written_files:
        print(f"    {p}")

    if pool is not None:
        try:
            await pool.close()
        except Exception:
            pass


async def run_record(
    bucket: str,
    pass_count: int,
    total: int,
    notes: Optional[str] = None,
    dsn: Optional[str] = None,
) -> None:
    """Record a human spot-check result into corpus_g3_results.

    Args:
        bucket: Bucket key (e.g. 'chat_qa_all').
        pass_count: Number of rows marked PASS by human reviewer.
        total: Total rows reviewed.
        notes: Optional reviewer notes.
        dsn: asyncpg DSN override.
    """
    pool = await _make_pool(dsn)
    if pool is None:
        print("ERROR: No DB pool available — cannot record G3 result.")
        return

    result = await record_g3_result(pool, bucket, pass_count, total, notes)
    if result:
        threshold_msg = "(PASS ✓)" if result["pass_rate"] >= 90.0 else "(BELOW 90% THRESHOLD ⚠️)"
        print(
            f"\nG3 RESULT RECORDED\n"
            f"  id         : {result['id']}\n"
            f"  bucket     : {result['bucket']}\n"
            f"  pass_count : {result['pass_count']}\n"
            f"  total      : {result['total']}\n"
            f"  pass_rate  : {result['pass_rate']:.2f}%  {threshold_msg}\n"
            f"  reviewed_at: {result['reviewed_at']}\n"
        )
    else:
        print("ERROR: Failed to record G3 result — check logs.")

    try:
        await pool.close()
    except Exception:
        pass


# ---------------------------------------------------------------------------
# CLI entry-point
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(
        description="G3 human spot-check sampler for the distillation corpus.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    subparsers = parser.add_subparsers(dest="command", help="Sub-command")

    # ── sample sub-command ──
    sample_parser = subparsers.add_parser(
        "sample", help="Sample N rows per bucket and write review files."
    )
    sample_parser.add_argument(
        "--n", type=int, default=30,
        help="Rows per (source, business_type) bucket (default: 30).",
    )
    sample_parser.add_argument(
        "--source", default=None,
        help="Filter to one source (e.g. 'chat_qa'). Default: all sources.",
    )
    sample_parser.add_argument(
        "--output-dir", default="/tmp",
        help="Directory for review files (default: /tmp).",
    )
    sample_parser.add_argument(
        "--format", dest="fmt", default="md", choices=["md", "jsonl"],
        help="Output format: 'md' (markdown, default) or 'jsonl'.",
    )
    sample_parser.add_argument(
        "--dry-run", action="store_true",
        help="Print counts only; no file writes.",
    )
    sample_parser.add_argument(
        "--dsn", default=os.environ.get("SMARTBI_PROD_DSN", ""),
        help="asyncpg DSN (overrides SMARTBI_PROD_DSN env var).",
    )

    # ── record sub-command ──
    record_parser = subparsers.add_parser(
        "record", help="Record human spot-check pass-rate into corpus_g3_results."
    )
    record_parser.add_argument(
        "--bucket", required=True,
        help="Bucket key to record for (e.g. 'chat_qa_all').",
    )
    record_parser.add_argument(
        "--pass-count", type=int, required=True,
        help="Number of rows human reviewer marked PASS.",
    )
    record_parser.add_argument(
        "--total", type=int, required=True,
        help="Total rows reviewed.",
    )
    record_parser.add_argument(
        "--notes", default=None,
        help="Optional reviewer notes.",
    )
    record_parser.add_argument(
        "--dsn", default=os.environ.get("SMARTBI_PROD_DSN", ""),
        help="asyncpg DSN (overrides SMARTBI_PROD_DSN env var).",
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

    if args.command == "sample":
        asyncio.run(run_sample(
            n=args.n,
            source=args.source or None,
            output_dir=args.output_dir,
            fmt=args.fmt,
            dry_run=args.dry_run,
            dsn=args.dsn or None,
        ))
    elif args.command == "record":
        asyncio.run(run_record(
            bucket=args.bucket,
            pass_count=args.pass_count,
            total=args.total,
            notes=args.notes,
            dsn=args.dsn or None,
        ))
    else:
        # Legacy flat-arg support: --sample / --n / --record etc.
        # Re-parse with legacy flags for backward compatibility.
        parser2 = argparse.ArgumentParser(add_help=False)
        parser2.add_argument("--sample", action="store_true")
        parser2.add_argument("--n", type=int, default=30)
        parser2.add_argument("--source", default=None)
        parser2.add_argument("--output-dir", default="/tmp")
        parser2.add_argument("--format", dest="fmt", default="md")
        parser2.add_argument("--dry-run", action="store_true")
        parser2.add_argument("--dsn", default=os.environ.get("SMARTBI_PROD_DSN", ""))
        parser2.add_argument("--record", action="store_true")
        parser2.add_argument("--bucket", default=None)
        parser2.add_argument("--pass-count", type=int, default=None)
        parser2.add_argument("--total", type=int, default=None)
        parser2.add_argument("--notes", default=None)
        parser2.add_argument("--log-level", default="INFO")

        args2, _ = parser2.parse_known_args()
        if args2.record:
            asyncio.run(run_record(
                bucket=args2.bucket or "unknown",
                pass_count=args2.pass_count or 0,
                total=args2.total or 0,
                notes=args2.notes,
                dsn=args2.dsn or None,
            ))
        else:
            asyncio.run(run_sample(
                n=args2.n,
                source=args2.source or None,
                output_dir=args2.output_dir,
                fmt=args2.fmt,
                dry_run=args2.dry_run,
                dsn=args2.dsn or None,
            ))


if __name__ == "__main__":
    main()
