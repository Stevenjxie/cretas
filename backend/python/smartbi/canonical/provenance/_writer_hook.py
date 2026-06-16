"""Dual-write hook for B Silver writers.

Per spec 04-C v1.3 §5.2: each B writer, after its main INSERT, optionally
calls write_provenance_for_fields() to record cell-level lineage. Gated by
SMARTBI_ENABLE_PROVENANCE env flag (default OFF until prod observation
confirms safety).

The hook uses resolve_conflict() so concurrent uploads of the same product/
field with different values either supersede correctly (priority-based) or
queue for admin review (30% diff). Writers always pass their canonical
source_type ('product_summary' / 'review' / 'bill_flow' / 'inventory') so the
priority chain works correctly.
"""
from __future__ import annotations

import functools
import logging
import os
from datetime import date
from typing import TYPE_CHECKING, Any, Dict, Optional

if TYPE_CHECKING:
    import asyncpg


logger = logging.getLogger(__name__)


@functools.lru_cache(maxsize=None)
def _read_provenance_env() -> bool:
    """Read SMARTBI_ENABLE_PROVENANCE once and cache the result.

    Cached because is_provenance_enabled() is called inside every Silver
    writer's per-row hook path; ``os.environ.get`` per call is wasteful and
    — more importantly — lets a mid-run flag flip cause a single upload to
    emit partial provenance (rows before the flip have none, rows after do).
    Caching forces "process restart to change behaviour", matching the
    systemd discipline used for all other env-driven config.
    """
    val = os.environ.get("SMARTBI_ENABLE_PROVENANCE", "0").strip().lower()
    return val in ("1", "true", "yes", "on")


def is_provenance_enabled() -> bool:
    """Env flag SMARTBI_ENABLE_PROVENANCE (default OFF; opt-in via 1/true/yes/on).

    The first call reads ``os.environ`` and caches the result for the lifetime
    of the process. Subsequent calls return the cached value. To pick up an
    env-var change (test fixtures, ops manually flipping the flag), call
    :func:`invalidate_provenance_flag_cache` and then re-check; in production,
    a process restart is required.
    """
    return _read_provenance_env()


def invalidate_provenance_flag_cache() -> None:
    """Drop the cached SMARTBI_ENABLE_PROVENANCE value.

    Tests typically use this after ``monkeypatch.setenv`` so the next
    ``is_provenance_enabled()`` call re-reads the patched environment.
    Production code should NOT call this — flag flips must go through a
    process restart so behaviour is consistent for every in-flight upload.
    """
    _read_provenance_env.cache_clear()


# I5 (Day 13+): narrow exception swallowed by the per-field hook. These four
# are recoverable / well-understood failure modes that should not abort the
# Silver write but also should not propagate. Anything else (RLS violations,
# pool exhaustion, OS errors, KeyboardInterrupt, programming bugs) escapes
# the hook so the caller learns about it instead of getting a silent log line.
def _swallowable_exception_types() -> tuple:
    """Return the asyncpg exceptions we treat as recoverable inside the hook.

    Wrapped in a function so the import is lazy — asyncpg is in
    ``requirements.txt`` but TYPE_CHECKING-only at module top.
    """
    import asyncpg

    return (
        asyncpg.UniqueViolationError,
        asyncpg.ForeignKeyViolationError,
        # Note: asyncpg exposes this as ``SerializationError`` (not
        # ``SerializationFailureError`` despite the PG SQLSTATE name). Verified
        # via ``[x for x in dir(asyncpg) if 'Serial' in x]``.
        asyncpg.SerializationError,
        asyncpg.DeadlockDetectedError,
    )


async def write_provenance_for_fields(
    conn: "asyncpg.Connection",
    factory_id: str,
    entity_type: str,
    entity_id: int,
    fields: Dict[str, Any],
    source_type: str,
    mapper_method: str,
    confidence: float,
    source_upload_id: Optional[int] = None,
    valid_from: Optional[date] = None,
    valid_to: Optional[date] = None,
) -> Dict[str, int]:
    """Write provenance rows for a batch of cells on the same entity.

    Args:
        fields: Mapping of field_name -> field_value. NULL/None values skipped.
        Other args mirror resolve_conflict() / write_provenance().

    Returns: counter dict ``{"written": N, "queued": M, "no_change": K,
    "skipped_null": P, "error_swallowed": E}``.

    Caller MUST be inside conn.transaction(). Each ``resolve_conflict`` call
    is additionally wrapped in its own ``async with conn.transaction()``,
    which asyncpg auto-promotes to a SAVEPOINT (I7 fix). That way a single
    field's mid-flow failure (e.g. swallowed UniqueViolation after the
    supersede sentinel UPDATE already ran) rolls back to a clean state,
    leaving sibling fields' work intact.

    Errors from the swallowable list (UniqueViolation / FK / Serialization /
    Deadlock) are logged at WARNING level and counted under
    ``error_swallowed``. Anything else propagates so misconfiguration (RLS,
    OS, pool exhaustion) surfaces immediately. (I5 fix.)

    Day 6+ blocker mitigation: env-flag default OFF means most uploads skip
    this entirely until we're confident.
    """
    from smartbi.canonical.provenance.conflict_resolver import resolve_conflict

    swallowable = _swallowable_exception_types()
    counts: Dict[str, int] = {
        "written": 0,
        "queued": 0,
        "no_change": 0,
        "skipped_null": 0,
        "error_swallowed": 0,
    }
    for field_name, value in fields.items():
        if value is None:
            counts["skipped_null"] += 1
            continue
        try:
            # I7 (Day 13+): per-field SAVEPOINT. asyncpg auto-promotes a
            # nested ``async with conn.transaction()`` to SAVEPOINT, so a
            # mid-loop failure on this field rolls back its partial state
            # (e.g. a supersede-sentinel UPDATE that ran before the INSERT
            # failed) without aborting the outer transaction or the work
            # already committed for prior fields in this batch.
            async with conn.transaction():
                result = await resolve_conflict(
                    conn,
                    factory_id=factory_id,
                    entity_type=entity_type,
                    entity_id=entity_id,
                    field_name=field_name,
                    new_value=value,
                    confidence=confidence,
                    source_type=source_type,
                    mapper_method=mapper_method,
                    source_upload_id=source_upload_id,
                    valid_from=valid_from,
                    valid_to=valid_to,
                )
            action = result.get("action", "unknown")
            counts[action] = counts.get(action, 0) + 1
        except swallowable as exc:
            logger.warning(
                "provenance write failed (recoverable): "
                "factory=%s entity=%s:%s field=%s err=%s",
                factory_id,
                entity_type,
                entity_id,
                field_name,
                exc,
            )
            counts["error_swallowed"] += 1
            # Fall through to next field; the savepoint already rolled back
            # this field's partial state.
    return counts
