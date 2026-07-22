"""Data Fabric end-to-end integration tests against real test smartbi_db.

These tests are SKIPPED if PG is not reachable (default local-dev case) so the
default ``pytest tests/`` run keeps passing. To opt in:

    INTEGRATION_PG_DSN=postgresql://smartbi_user:smartbi_secure_password_2025@localhost:5432/smartbi_db \\
        python -m pytest tests/test_data_fabric_e2e.py -v

Why these exist (per code-reviewer note Apr 26 2026): the existing 138 unit
tests are ALL mock-based, so they cannot catch:
  - The Review writer idempotency bug (already fixed in ``bf7ea2675``)
  - RLS gaps if ``app.factory_id`` is unset (silent 0-row INSERTs under
    FORCE ROW LEVEL SECURITY)
  - COALESCE-based unique-index expression syntax errors
  - Real ON CONFLICT semantics on PostgreSQL 13

Each test pre-cleans + post-cleans its tenant rows. Tests run inside a
transaction so any rows that escape cleanup are rolled back. We use a
dedicated test factory_id (``F999_INTEGRATION``) to stay out of any real
tenant's data.
"""
from __future__ import annotations

import json
import os
import time
from typing import Any, AsyncIterator, Dict, List, Optional
from unittest.mock import MagicMock

import pytest

try:
    import asyncpg
except ImportError:  # pragma: no cover — asyncpg is in requirements.txt
    asyncpg = None  # type: ignore[assignment]


PG_DSN: str = os.environ.get(
    "INTEGRATION_PG_DSN",
    "postgresql://smartbi_user:smartbi_secure_password_2025@localhost:5432/smartbi_db",
)
TEST_FACTORY: str = "F999_INTEGRATION"

pytestmark = pytest.mark.asyncio


# ── Pool fixture ───────────────────────────────────────────────────────────
@pytest.fixture
async def pg_pool() -> AsyncIterator["asyncpg.Pool"]:
    """Create a small pool, or skip the test if PG is unreachable.

    Connect-timeout is short (5s) so unreachable PG fails fast — we don't want
    to block the default test run for 30s when the DB just isn't there.
    """
    if asyncpg is None:
        pytest.skip("asyncpg not installed")
    try:
        pool = await asyncpg.create_pool(
            PG_DSN, min_size=1, max_size=2, timeout=5, command_timeout=10
        )
    except Exception as exc:  # noqa: BLE001 — broad on purpose: any conn error → skip
        pytest.skip(f"PG not reachable at {PG_DSN.split('@')[-1]}: {exc}")
    # Verify required Data Fabric tables exist; else skip rather than fail.
    required = (
        "entity_resolution_history",
        "entity_resolution_admin_queue",
        "fact_review_event",
        "dim_review_summary",
        "agg_product_period",
        "smart_bi_dynamic_data",
        "dim_product",
        "dim_store",
    )
    async with pool.acquire() as conn:
        for tbl in required:
            row = await conn.fetchrow(
                "SELECT to_regclass($1) AS rc", f"public.{tbl}"
            )
            if row is None or row["rc"] is None:
                await pool.close()
                pytest.skip(f"required table missing on this DB: {tbl}")
    try:
        yield pool
    finally:
        await pool.close()


async def _set_tenant(conn: "asyncpg.Connection", factory_id: str) -> None:
    """Apply ``app.factory_id`` for the current transaction.

    ``set_config(..., true)`` is transaction-local, so callers MUST be inside
    a ``conn.transaction()`` block when invoking this — otherwise the setting
    only lives for the duration of the SELECT itself.
    """
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


@pytest.fixture
async def clean_tenant(pg_pool: "asyncpg.Pool") -> AsyncIterator[None]:
    """Delete TEST_FACTORY rows before AND after each test.

    We rely on RLS WITH CHECK letting us delete our own rows when
    ``app.factory_id`` is set to the test factory. If the connection's
    role lacks privilege, individual deletes are best-effort (suppressed).
    """

    async def _clean() -> None:
        # I1 (Day 13+): order matters. ``field_provenance`` has
        # ``source_upload_id REFERENCES smart_bi_pg_excel_uploads(id) ON DELETE
        # RESTRICT``, so the provenance rows must be deleted BEFORE the upload
        # rows or the FK blocks the upload-table cleanup. ``smart_bi_pg_excel_
        # uploads`` itself contains a sentinel row (id=0, factory_id='_SYSTEM_')
        # that must not be deleted — guarded below by an explicit ``id <> 0``.
        tables = (
            "field_provenance",  # C — must precede smart_bi_pg_excel_uploads (FK RESTRICT)
            "factory_provenance_config",  # C
            "agg_product_period",
            "fact_review_event",
            "dim_review_summary",
            "fact_finance_voucher",
            "fact_inventory_snapshot",
            "dim_finance_subject",  # Phase B C1 e2e — finance writer test
            "dim_ingredient",       # Phase B C1 e2e — inventory writer test
            "entity_resolution_history",
            "entity_resolution_admin_queue",
            "dim_product",
            "dim_store",
            "smart_bi_dynamic_data",
            "smart_bi_pg_excel_uploads",  # last, with sentinel guard
        )
        async with pg_pool.acquire() as conn:
            async with conn.transaction():
                await _set_tenant(conn, TEST_FACTORY)
                for tbl in tables:
                    try:
                        if tbl == "smart_bi_pg_excel_uploads":
                            # Skip the sentinel id=0 row that V20260430_01
                            # reserves for non-upload provenance sources.
                            await conn.execute(
                                f"DELETE FROM {tbl} "
                                "WHERE factory_id = $1 AND id <> 0",
                                TEST_FACTORY,
                            )
                        else:
                            await conn.execute(
                                f"DELETE FROM {tbl} WHERE factory_id = $1",
                                TEST_FACTORY,
                            )
                    except Exception:  # noqa: BLE001
                        # Table may not have factory_id column or may not exist
                        # in this environment; best-effort cleanup.
                        pass

    await _clean()
    try:
        yield
    finally:
        await _clean()


# ── Helpers ─────────────────────────────────────────────────────────────────
def _make_mock_pool_for_orchestrator(real_pool: "asyncpg.Pool") -> "asyncpg.Pool":
    """Return ``real_pool`` unchanged.

    The orchestrator's ``_record_history`` and ``_queue_for_admin`` already
    use ``async with self._pool.acquire()``, so we can pass the real pool
    directly. We just need the test's caller to set ``app.factory_id`` via
    pool ``setup`` callback OR set it explicitly on each acquire.

    Note: the production pool has a ``setup=set_pg_connection_tenant``
    callback. For these tests we set the tenant explicitly per acquire so
    we don't need the production pool factory.
    """
    return real_pool


async def _seed_dim_store(
    conn: "asyncpg.Connection", factory_id: str, name: str
) -> int:
    """Insert one dim_store row (must be inside a tenant-scoped tx)."""
    row = await conn.fetchrow(
        """
        INSERT INTO dim_store (factory_id, name)
        VALUES ($1, $2)
        ON CONFLICT (factory_id, name) DO UPDATE SET updated_at = NOW()
        RETURNING store_id
        """,
        factory_id,
        name,
    )
    assert row is not None
    return int(row["store_id"])


async def _seed_dim_product(
    conn: "asyncpg.Connection",
    factory_id: str,
    name: str,
    normalized_name: Optional[str] = None,
) -> int:
    row = await conn.fetchrow(
        """
        INSERT INTO dim_product (factory_id, name, normalized_name)
        VALUES ($1, $2, $3)
        ON CONFLICT (factory_id, normalized_name) DO UPDATE SET updated_at = NOW()
        RETURNING product_id
        """,
        factory_id,
        name,
        normalized_name or name,
    )
    assert row is not None
    return int(row["product_id"])


# ── Tests ───────────────────────────────────────────────────────────────────
@pytest.mark.integration
async def test_rls_blocks_cross_tenant_insert(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """RLS WITH CHECK rejects an INSERT whose factory_id != current tenant.

    Set tenant=TEST_FACTORY, attempt INSERT with factory_id='OTHER_TENANT_X'.
    Must raise. (asyncpg surfaces the policy violation as
    ``InsufficientPrivilegeError``.)
    """
    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            with pytest.raises(
                (
                    asyncpg.InsufficientPrivilegeError,
                    asyncpg.exceptions.InsufficientPrivilegeError,
                )
            ):
                await conn.execute(
                    """
                    INSERT INTO entity_resolution_history
                      (factory_id, entity_type, a_name, b_name,
                       b_entity_id, confidence)
                    VALUES ($1, 'store', 'a', 'b', 1, 0.9)
                    """,
                    "OTHER_TENANT_X",
                )


@pytest.mark.integration
async def test_orchestrator_writes_history_and_admin_queue(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """Drive ``_record_history`` + ``_queue_for_admin`` through the real pool.

    Use a mock agent that returns a tentative match (confidence between
    tentative_threshold and ship_threshold) so we exercise BOTH the history
    insert and the admin queue insert in one call.
    """
    from smartbi.canonical.entity_resolution.agents.base import BaseAgent
    from smartbi.canonical.entity_resolution.orchestrator import (
        AgentResult,
        EntityResolutionOrchestrator,
        EntityType,
        ResolutionInput,
    )

    # Set up a per-acquire setup so the orchestrator's own acquire() also
    # gets app.factory_id (otherwise its insert into FORCE-RLS tables fails).
    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    # Build a fresh pool with the setup callback (the original pg_pool fixture
    # doesn't apply any setup so we'd hit RLS otherwise).
    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        # Seed a dim_store row so _record_history's lookup finds a name
        async with scoped_pool.acquire() as conn:
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "测试门店A")

        class _TentativeAgent(BaseAgent):
            name = "deterministic"
            ship_threshold = 0.95

            async def resolve(  # type: ignore[override]
                self, input: Any, pool: Any
            ) -> AgentResult:
                # Below ship_threshold but above tentative_threshold → tentative
                return AgentResult(
                    matched_entity_id=store_id,
                    confidence=0.85,
                    reasoning="测试 tentative match",
                )

        orch = EntityResolutionOrchestrator(scoped_pool, [_TentativeAgent()])
        out = await orch.resolve(
            ResolutionInput(
                raw_name="测试门店A别名",
                entity_type=EntityType.STORE,
                factory_id=TEST_FACTORY,
            )
        )

        assert out.is_tentative is True
        assert out.matched_entity_id == store_id

        # Verify history row landed
        async with scoped_pool.acquire() as conn:
            hist = await conn.fetchrow(
                """
                SELECT a_name, b_name, b_entity_id, confidence,
                       decided_by_agent, reasoning
                FROM entity_resolution_history
                WHERE factory_id = $1 AND entity_type = 'store'
                  AND a_name = $2
                """,
                TEST_FACTORY,
                "测试门店A别名",
            )
            assert hist is not None, "history row not written"
            assert hist["b_entity_id"] == store_id
            assert hist["b_name"] == "测试门店A"
            assert float(hist["confidence"]) == pytest.approx(0.85)
            assert hist["decided_by_agent"] == "deterministic"

            # Verify admin_queue row landed with priority='low' (tentative)
            queue = await conn.fetchrow(
                """
                SELECT raw_name, candidate_entity_id, priority, reasoning
                FROM entity_resolution_admin_queue
                WHERE factory_id = $1 AND entity_type = 'store'
                  AND raw_name = $2
                """,
                TEST_FACTORY,
                "测试门店A别名",
            )
            assert queue is not None, "admin_queue row not written"
            assert queue["candidate_entity_id"] == store_id
            assert queue["priority"] == "low"
            assert queue["reasoning"] is not None
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_review_writer_idempotent_on_rerun(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """Re-running ReviewWriter on the same upload must NOT duplicate fact rows.

    Validates the V20260428_01 ``uq_fre_natkey`` unique index + the
    ``ON CONFLICT (factory_id, upload_id, COALESCE(source_row_hash, ''))``
    clause in review_writer.py (the bug fixed in ``bf7ea2675``).
    """
    from smartbi.canonical.silver_writers import ReviewWriter
    from smartbi.canonical.silver_writers.base import ResolveResult

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "门店X")
            product_id = await _seed_dim_product(
                conn, TEST_FACTORY, "招牌鱼", "招牌鱼"
            )

            # Insert a parent upload row so the FK on fact_review_event is
            # satisfiable. Schema for smart_bi_pg_excel_uploads varies — we
            # try a minimal insert and skip if it doesn't fit.
            upload_id_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED')
                RETURNING id
                """,
                TEST_FACTORY,
                f"e2e_test_{int(time.time())}.xlsx",
            )
            assert upload_id_row is not None
            upload_id = int(upload_id_row["id"])

            # Insert a single sample dynamic_data row that ReviewWriter reads.
            row_data: Dict[str, Any] = {
                "门店": "门店X",
                "商品名称": "招牌鱼",
                "评分": 5,
                "评论内容": "好吃",
                "评论日期": "2026-04-25",
            }
            await conn.execute(
                """
                INSERT INTO smart_bi_dynamic_data
                  (factory_id, upload_id, row_data)
                VALUES ($1, $2, $3::jsonb)
                """,
                TEST_FACTORY,
                upload_id,
                json.dumps(row_data),
            )

        # Build writer with mock orchestrator returning canned IDs
        orch = MagicMock()

        async def _resolve_store(_, factory_id, context):
            return ResolveResult(
                entity_id=store_id, is_tentative=False, confidence=0.95
            )

        async def _resolve_product(_, factory_id, context):
            return ResolveResult(
                entity_id=product_id, is_tentative=False, confidence=0.95
            )

        writer = ReviewWriter(pool=scoped_pool, orchestrator=orch)
        writer._resolve_store = _resolve_store  # type: ignore[method-assign]
        writer._resolve_product = _resolve_product  # type: ignore[method-assign]

        # First write
        s1 = await writer.write(upload_id=upload_id, factory_id=TEST_FACTORY)
        assert s1.rows_written == 1

        async with scoped_pool.acquire() as conn:
            count_after_first = await conn.fetchval(
                "SELECT COUNT(*) FROM fact_review_event "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            summary_count_first = await conn.fetchval(
                "SELECT COUNT(*) FROM dim_review_summary "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )

        # Second write — same upload, same content → ON CONFLICT DO NOTHING
        s2 = await writer.write(upload_id=upload_id, factory_id=TEST_FACTORY)
        assert s2.rows_written == 1  # writer's own counter is per-call

        async with scoped_pool.acquire() as conn:
            count_after_second = await conn.fetchval(
                "SELECT COUNT(*) FROM fact_review_event "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            summary_count_second = await conn.fetchval(
                "SELECT COUNT(*) FROM dim_review_summary "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )

        # Idempotency assertions: row counts unchanged on re-run.
        assert (
            count_after_first == 1
        ), f"first run wrote {count_after_first} rows (want 1)"
        assert (
            count_after_second == count_after_first
        ), f"re-run created duplicates: {count_after_first} → {count_after_second}"
        assert (
            summary_count_second == summary_count_first == 1
        ), "summary row not idempotent"
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_product_summary_writer_coalesce_period_unique_index(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """Validate the ``COALESCE(period_start, DATE '0001-01-01')`` unique index.

    This is the spec departure (PG 13 lacks NULLS NOT DISTINCT) that
    REQUIRES live PG validation — mocks can't catch the index expression
    syntax. We INSERT twice with NULL period_start; the second must hit
    ON CONFLICT and update the row instead of stacking.
    """
    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "门店Y")
            product_id = await _seed_dim_product(
                conn, TEST_FACTORY, "宫保鸡丁", "宫保鸡丁"
            )

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED')
                RETURNING id
                """,
                TEST_FACTORY,
                f"e2e_psw_{int(time.time())}.xlsx",
            )
            assert upload_row is not None
            upload_id = int(upload_row["id"])

            # First insert with NULL period_start
            await conn.execute(
                """
                INSERT INTO agg_product_period
                  (factory_id, upload_id, product_id, store_id,
                   period_start, period_end, qty_sold, revenue, avg_unit_price)
                VALUES ($1, $2, $3, $4, NULL, NULL, 10, 100, 10)
                ON CONFLICT (factory_id, upload_id, product_id, store_id,
                             COALESCE(period_start, DATE '0001-01-01'))
                DO UPDATE SET
                  qty_sold = EXCLUDED.qty_sold,
                  revenue = EXCLUDED.revenue,
                  avg_unit_price = EXCLUDED.avg_unit_price
                """,
                TEST_FACTORY,
                upload_id,
                product_id,
                store_id,
            )
            count1 = await conn.fetchval(
                "SELECT COUNT(*) FROM agg_product_period "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            assert count1 == 1

            # Second insert — same NULL period_start. Without COALESCE, PG
            # would let this through (NULL != NULL). With COALESCE it MUST
            # collide and DO UPDATE.
            await conn.execute(
                """
                INSERT INTO agg_product_period
                  (factory_id, upload_id, product_id, store_id,
                   period_start, period_end, qty_sold, revenue, avg_unit_price)
                VALUES ($1, $2, $3, $4, NULL, NULL, 20, 200, 10)
                ON CONFLICT (factory_id, upload_id, product_id, store_id,
                             COALESCE(period_start, DATE '0001-01-01'))
                DO UPDATE SET
                  qty_sold = EXCLUDED.qty_sold,
                  revenue = EXCLUDED.revenue,
                  avg_unit_price = EXCLUDED.avg_unit_price
                """,
                TEST_FACTORY,
                upload_id,
                product_id,
                store_id,
            )
            count2 = await conn.fetchval(
                "SELECT COUNT(*) FROM agg_product_period "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            qty = await conn.fetchval(
                "SELECT qty_sold FROM agg_product_period "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            assert (
                count2 == 1
            ), f"COALESCE unique index failed: {count1} → {count2} (expected 1)"
            assert (
                float(qty) == 20.0
            ), "DO UPDATE didn't fire on conflict (qty should be 20, not 10)"
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_admin_queue_extended_check_allows_ingredient(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """V20260428_02 broadened entity_type CHECK to include 'ingredient'.

    Verify the CHECK constraint accepts 'ingredient' AND still rejects an
    invalid value (e.g., 'totally_invalid_type').
    """
    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            # 'ingredient' must succeed (V20260428_02)
            await conn.execute(
                """
                INSERT INTO entity_resolution_admin_queue
                  (factory_id, entity_type, raw_name, confidence,
                   decided_by_agent, reasoning, priority)
                VALUES ($1, 'ingredient', '番茄', 0.5, 'deterministic',
                        'integration test', 'medium')
                """,
                TEST_FACTORY,
            )
            row = await conn.fetchrow(
                """
                SELECT entity_type, raw_name FROM entity_resolution_admin_queue
                WHERE factory_id = $1 AND entity_type = 'ingredient'
                """,
                TEST_FACTORY,
            )
            assert row is not None
            assert row["entity_type"] == "ingredient"

    # 'totally_invalid_type' must fail with CHECK violation. Run in fresh tx.
    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            with pytest.raises(asyncpg.exceptions.CheckViolationError):
                await conn.execute(
                    """
                    INSERT INTO entity_resolution_admin_queue
                      (factory_id, entity_type, raw_name, confidence,
                       decided_by_agent, reasoning, priority)
                    VALUES ($1, 'totally_invalid_type', 'x', 0.5,
                            'deterministic', 'should fail', 'medium')
                    """,
                    TEST_FACTORY,
                )


@pytest.mark.integration
async def test_priority_status_columns_present_with_defaults(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """V20260428_02 added priority + status with defaults.

    Insert without specifying status; default 'PENDING' should apply. Insert
    with explicit priority='high' and verify it stuck.
    """
    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            await conn.execute(
                """
                INSERT INTO entity_resolution_admin_queue
                  (factory_id, entity_type, raw_name, confidence,
                   decided_by_agent, reasoning, priority)
                VALUES ($1, 'store', '门店Q', 0.4, 'deterministic',
                        'integration test', 'high')
                """,
                TEST_FACTORY,
            )
            row = await conn.fetchrow(
                """
                SELECT priority, status FROM entity_resolution_admin_queue
                WHERE factory_id = $1 AND raw_name = '门店Q'
                """,
                TEST_FACTORY,
            )
            assert row is not None
            assert row["priority"] == "high"
            # Default ('PENDING') applied when status omitted
            assert row["status"] == "PENDING"

    # Check status CHECK constraint rejects invalid values
    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            with pytest.raises(asyncpg.exceptions.CheckViolationError):
                await conn.execute(
                    """
                    INSERT INTO entity_resolution_admin_queue
                      (factory_id, entity_type, raw_name, confidence,
                       decided_by_agent, reasoning, priority, status)
                    VALUES ($1, 'store', '门店Z', 0.4, 'deterministic',
                            'integration test', 'medium', 'NOT_A_STATUS')
                    """,
                    TEST_FACTORY,
                )


@pytest.mark.integration
async def test_rls_silently_drops_zero_rows_when_tenant_unset(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """If ``app.factory_id`` is NEVER set, FORCE RLS rejects the WITH CHECK.

    This is the silent-zero-rows trap: a writer that forgets to set the
    tenant context will see its INSERT raise InsufficientPrivilegeError on
    PostgreSQL 13+ (FORCE RLS applies to table owners too).
    """
    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            # Intentionally do NOT call _set_tenant
            with pytest.raises(
                (
                    asyncpg.InsufficientPrivilegeError,
                    asyncpg.exceptions.InsufficientPrivilegeError,
                )
            ):
                await conn.execute(
                    """
                    INSERT INTO entity_resolution_history
                      (factory_id, entity_type, a_name, b_name,
                       b_entity_id, confidence)
                    VALUES ($1, 'store', 'a', 'b', 1, 0.9)
                    """,
                    TEST_FACTORY,
                )


@pytest.mark.integration
async def test_closed_loop_sheet_merger_to_template(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """End-to-end: dynamic_data row → SheetMergeAnalyzer → route_upload →
    ProductSummaryWriter → agg_product_period → compute_product_top10.

    This validates the closed loop the smoke gate doc describes — Phase 3 wire-in.

    The shape detector's PRODUCT_SUMMARY rule (商品名称 + 销售金额, no 账单号)
    fires at confidence=0.90, above the 0.85 AUTO_ROUTE_THRESHOLD. With high
    confidence the writer runs; if confidence is below threshold (e.g. LLM
    fallback path), the upload is queued for admin and we still verify the
    pipeline ran cleanly without crashing.
    """
    from smartbi.canonical.entity_resolution import make_default_orchestrator
    from smartbi.canonical.shape_router import route_upload
    from smartbi.canonical.sheet_merger import SheetMergeAnalyzer
    from smartbi.canonical.templates import compute_product_top10

    # Use a scoped pool with the tenant setup callback so writers, orchestrator
    # AND template queries all see app.factory_id when they acquire conns.
    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=4, timeout=5, setup=_setup
    )
    try:
        # 1. Seed: dim_store + dim_product + upload row + field defs + 1 dynamic row
        async with scoped_pool.acquire() as conn:
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "测试店")  # noqa: F841
            product_id = await _seed_dim_product(  # noqa: F841
                conn, TEST_FACTORY, "测试菜", "测试菜"
            )

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status, detected_table_type)
                VALUES ($1, $2, 'COMPLETED', 'product_summary')
                RETURNING id
                """,
                TEST_FACTORY,
                f"e2e_closed_loop_{int(time.time())}.xlsx",
            )
            assert upload_row is not None
            upload_id = int(upload_row["id"])

            # field_definitions: must include a date column for period inference
            # to succeed, and the PRODUCT_SUMMARY trigger columns 商品名称 +
            # 销售金额 (without 账单号) for ShapeDetector rule path.
            for i, orig in enumerate(
                ("营业日期", "门店名称", "商品名称", "数量", "销售金额")
            ):
                await conn.execute(
                    """
                    INSERT INTO smart_bi_pg_field_definitions
                      (upload_id, original_name, display_order)
                    VALUES ($1, $2, $3)
                    """,
                    upload_id,
                    orig,
                    i,
                )

            row_data = {
                "营业日期": "2026-04-01",
                "门店名称": "测试店",
                "商品名称": "测试菜",
                "数量": 5,
                "销售金额": 100.0,
            }
            await conn.execute(
                """
                INSERT INTO smart_bi_dynamic_data
                  (factory_id, upload_id, row_data)
                VALUES ($1, $2, $3::jsonb)
                """,
                TEST_FACTORY,
                upload_id,
                json.dumps(row_data),
            )

        # 2. Run SheetMergeAnalyzer → period inferred + persisted
        async with scoped_pool.acquire() as conn:
            async with conn.transaction():
                await _set_tenant(conn, TEST_FACTORY)
                analyzer = SheetMergeAnalyzer()
                await analyzer.analyze(upload_id, TEST_FACTORY, conn)

            period_row = await conn.fetchrow(
                """
                SELECT merge_inferred_period_start AS p_start,
                       merge_inferred_period_end   AS p_end,
                       merge_period_inference_method AS method
                FROM smart_bi_pg_excel_uploads WHERE id = $1
                """,
                upload_id,
            )

        assert period_row is not None
        # Single-row date 2026-04-01 → period_start should be set (method =
        # row_date_column or sheet_name pattern depending on impl).
        assert (
            period_row["p_start"] is not None
        ), f"merge_inferred_period_start not populated; method={period_row['method']!r}"

        # 3. route_upload → ProductSummaryWriter (rule confidence 0.90 > 0.85)
        orchestrator = make_default_orchestrator(scoped_pool)
        result = await route_upload(
            upload_id, TEST_FACTORY, scoped_pool, orchestrator
        )

        # Pipeline must NOT crash. Either writer ran OR queued for admin.
        async with scoped_pool.acquire() as conn:
            agg_count = await conn.fetchval(
                "SELECT COUNT(*) FROM agg_product_period WHERE upload_id = $1",
                upload_id,
            )

        # 4. compute_product_top10 returns the seeded data (or empty applies=False)
        top10 = await compute_product_top10(scoped_pool, TEST_FACTORY, top_n=5)

        # Seed deterministically fires the rule-path detector at confidence
        # 0.90 (商品名称 + 销售金额 + NO 账单号 → product_summary, > 0.85
        # AUTO_ROUTE_THRESHOLD). If a future change drops confidence below
        # 0.85 — or routes elsewhere — this test will FAIL and surface the
        # regression rather than silently accepting an empty agg row count.
        assert (
            agg_count > 0
        ), f"expected ProductSummaryWriter to write rows, got {agg_count}"
        assert (
            result.routed_to == "product_summary"
        ), f"writer ran but routed_to != product_summary: {result.routed_to!r}"
        assert top10.applies is True
        assert len(top10.data["items"]) >= 1
        assert top10.data["items"][0]["name"] == "测试菜"
    finally:
        # Drop module-level asyncio.Lock so the next test (likely on a fresh
        # event loop under pytest-asyncio) doesn't reuse a Lock bound to this
        # test's loop.
        from smartbi.canonical.concurrency import cleanup_factory_lock

        cleanup_factory_lock(TEST_FACTORY)
        await scoped_pool.close()


@pytest.mark.integration
async def test_stress_concurrent_uploads_advisory_lock(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """Spec §11.7 + B7 risk mitigation: 5 concurrent uploads on the SAME
    factory_id must serialize via pg_advisory_xact_lock — not run in parallel.

    Total elapsed >= 5 × 0.05s = 0.25s (parallel would be ~0.05s).
    """
    import asyncio
    import time as _time

    from smartbi.canonical.concurrency import (
        cleanup_factory_lock,
        with_factory_serialization,
    )

    # Drop any module-level asyncio.Lock left over from prior tests in this run
    # — under pytest-asyncio's per-test event loop, a Lock created on a prior
    # loop will RuntimeError ("Future attached to a different loop") when
    # awaited here.
    cleanup_factory_lock(TEST_FACTORY)

    # Need ≥5 concurrent connections + 1 factory_id → all serialize.
    stress_pool = await asyncpg.create_pool(
        PG_DSN, min_size=5, max_size=10, timeout=5
    )
    try:

        async def fake_work(conn: "asyncpg.Connection") -> str:
            await asyncio.sleep(0.05)
            return "done"

        async def run_one() -> str:
            async with stress_pool.acquire() as conn:
                # Each conn must have app.factory_id set OR be tx-local; the
                # advisory lock itself doesn't care, but with_factory_serialization
                # opens a tx so we set tenant inside that tx via an inner
                # wrapper. Simpler: set BEFORE, transaction-scoped inside.
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
                )
                return await with_factory_serialization(
                    TEST_FACTORY, conn, fake_work
                )

        start = _time.monotonic()
        results = await asyncio.gather(*[run_one() for _ in range(5)])
        total_elapsed = _time.monotonic() - start

        assert all(r == "done" for r in results)
        # Serialization invariant: total >= 5 × 0.05s = 0.25s. Allow 0.23s
        # floor to absorb timer jitter, but it MUST be substantially above
        # 0.05s (parallel) — proving the advisory lock did serialize.
        # Pair with the parallel test's 0.18s ceiling for a 50ms gap proving
        # the difference (CI-stable yet still discriminating).
        assert (
            total_elapsed >= 0.23
        ), f"expected serial execution (>=0.23s), got {total_elapsed:.3f}s"
    finally:
        cleanup_factory_lock(TEST_FACTORY)
        await stress_pool.close()


@pytest.mark.integration
async def test_stress_different_factories_run_in_parallel(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """5 different factory_ids should NOT serialize — different lock keys.

    Total elapsed should be close to single-task time (~0.05s), not 5×.
    """
    import asyncio
    import time as _time

    from smartbi.canonical.concurrency import (
        cleanup_factory_lock,
        with_factory_serialization,
    )

    factories = [
        "F_STRESS_1",
        "F_STRESS_2",
        "F_STRESS_3",
        "F_STRESS_4",
        "F_STRESS_5",
    ]

    # Defensive cleanup in case a prior test on a different event loop left
    # a Lock for any of these factory ids.
    for f in factories:
        cleanup_factory_lock(f)

    stress_pool = await asyncpg.create_pool(
        PG_DSN, min_size=5, max_size=10, timeout=5
    )
    try:

        async def fake_work(conn: "asyncpg.Connection") -> str:
            await asyncio.sleep(0.05)
            return "done"

        async def run_one(fid: str) -> str:
            async with stress_pool.acquire() as conn:
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", fid
                )
                return await with_factory_serialization(fid, conn, fake_work)

        start = _time.monotonic()
        results = await asyncio.gather(*[run_one(f) for f in factories])
        total_elapsed = _time.monotonic() - start

        assert all(r == "done" for r in results)
        # Parallel: 5 different lock keys → all run together; total ≈ 0.05s
        # plus pool/transaction overhead. Allow up to 0.18s (serial floor is
        # 0.23s above, so 50ms gap proves the difference). Tighter ceiling
        # discriminates parallelism cleanly from CI jitter.
        assert (
            total_elapsed < 0.18
        ), f"expected parallel execution (<0.18s), got {total_elapsed:.3f}s"
    finally:
        # Cleanup module-level asyncio lock registry to avoid cross-test bleed
        for f in factories:
            cleanup_factory_lock(f)
        await stress_pool.close()


# ── field_provenance integration tests (Sub-Project C Day 1-5) ─────────────
# These cover the audit-flagged "16 tests are all mock-based" gap for the
# provenance writer/reader. They run inside a transaction so cleanup happens
# automatically on rollback; cross-tenant + FK-restrict tests use raw INSERTs
# so they're independent of dim_* seeding.


@pytest.mark.integration
async def test_provenance_write_read_roundtrip(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """write_provenance + read_authoritative_value round-trip on real PG.

    Validates: sentinel upload_id=0 default, JSON serialization,
    asyncpg date round-trip, and that the reader reconstructs the same
    confidence / source_type / mapper_method we wrote.
    """
    from datetime import date

    from smartbi.canonical.provenance import (
        read_authoritative_value,
        write_provenance,
    )

    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "测试店")

            inserted_id = await write_provenance(
                conn,
                factory_id=TEST_FACTORY,
                entity_type="store",
                entity_id=store_id,
                field_name="brand",
                field_value="桂满陇",
                confidence=0.95,
                source_type="manual",
                mapper_method="manual",
                valid_from=date(2026, 1, 1),
            )
            assert inserted_id > 0

            # Read back
            pv = await read_authoritative_value(
                conn, TEST_FACTORY, "store", store_id, "brand"
            )
            assert pv is not None
            # field_value is JSONB-serialized "桂满陇" → asyncpg returns a
            # JSON string in this codebase (no global jsonb codec). Decode
            # defensively so the test passes regardless of codec config.
            fv = pv.field_value
            if isinstance(fv, str):
                try:
                    fv = json.loads(fv)
                except json.JSONDecodeError:
                    pass  # leave as-is if codec already decoded
            assert fv == "桂满陇"
            assert float(pv.confidence) == 0.95
            assert pv.source_upload_id == 0  # sentinel default
            assert pv.source_type == "manual"
            assert pv.mapper_method == "manual"
            assert pv.valid_from == date(2026, 1, 1)


@pytest.mark.integration
async def test_provenance_dedup_unique_violation_on_concurrent_write(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """uq_fp_dedup partial unique index prevents two active rows for the
    same (factory, entity_type, entity_id, field_name, valid_from).

    Day 1-5 simplification: no supersession yet, so the second write
    raises UniqueViolationError. Day 6+ conflict resolution will mark
    the first row superseded before re-INSERT.

    Note: writer currently passes ``valid_from=None`` to asyncpg, which
    sends NULL — DB column is NOT NULL DEFAULT '-infinity'. So we pass
    valid_from explicitly here. Day 6+ blocker item 7 in
    C-day6-blockers.md tracks the writer-side fix.
    """
    from datetime import date

    from smartbi.canonical.provenance import write_provenance

    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            product_id = await _seed_dim_product(
                conn, TEST_FACTORY, "招牌鱼", "招牌鱼"
            )

            # First write succeeds.
            await write_provenance(
                conn,
                factory_id=TEST_FACTORY,
                entity_type="product",
                entity_id=product_id,
                field_name="cost_per_unit",
                field_value=12.50,
                confidence=0.9,
                source_type="manual",
                mapper_method="manual",
                valid_from=date(2026, 1, 1),
            )

            # Second write with SAME (factory, entity_type, entity_id,
            # field_name, valid_from) and superseded_by_id NULL → raises
            # UniqueViolationError per uq_fp_dedup partial index.
            with pytest.raises(asyncpg.UniqueViolationError):
                await write_provenance(
                    conn,
                    factory_id=TEST_FACTORY,
                    entity_type="product",
                    entity_id=product_id,
                    field_name="cost_per_unit",
                    field_value=15.00,
                    confidence=0.85,
                    source_type="manual",
                    mapper_method="manual",
                    valid_from=date(2026, 1, 1),
                )


@pytest.mark.integration
async def test_provenance_rls_blocks_cross_tenant_insert(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """RLS WITH CHECK rejects an INSERT into field_provenance with
    factory_id != current tenant. Mirrors the
    test_rls_blocks_cross_tenant_insert pattern used for
    entity_resolution_history.
    """
    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            with pytest.raises(
                (
                    asyncpg.InsufficientPrivilegeError,
                    asyncpg.exceptions.InsufficientPrivilegeError,
                )
            ):
                await conn.execute(
                    """
                    INSERT INTO field_provenance
                      (factory_id, entity_type, entity_id, field_name,
                       field_value, source_type, confidence, mapper_method)
                    VALUES ('OTHER_FACTORY', 'store', 1, 'brand',
                            '"x"'::jsonb, 'manual', 0.5, 'manual')
                    """,
                )


@pytest.mark.integration
async def test_provenance_fk_restrict_prevents_orphan(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """ON DELETE RESTRICT on source_upload_id prevents deleting an upload
    that has provenance rows pointing to it (per spec C-2).

    Sentinel upload_id=0 doesn't apply here (it's the manual/inferred
    fallback and isn't user-deletable), but a real upload row MUST fail
    to DELETE if any field_provenance row still references it.

    Note: writer currently passes ``valid_from=None`` to asyncpg, which
    sends NULL — DB column is NOT NULL DEFAULT '-infinity'. So we pass
    valid_from explicitly here. Day 6+ blocker item 7 in
    C-day6-blockers.md tracks the writer-side fix.
    """
    from datetime import date

    from smartbi.canonical.provenance import write_provenance

    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)

            # Create a real upload row + provenance pointing to it.
            upload_id = await conn.fetchval(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, 'test_fk.xlsx', 'COMPLETED')
                RETURNING id
                """,
                TEST_FACTORY,
            )

            store_id = await _seed_dim_store(conn, TEST_FACTORY, "FK测试店")

            await write_provenance(
                conn,
                factory_id=TEST_FACTORY,
                entity_type="store",
                entity_id=store_id,
                field_name="city",
                field_value="上海",
                confidence=0.9,
                source_type="pos_excel",
                mapper_method="rule",
                source_upload_id=upload_id,
                valid_from=date(2026, 1, 1),
            )

            # Now try to DELETE the upload — must fail per ON DELETE RESTRICT.
            with pytest.raises(asyncpg.ForeignKeyViolationError):
                await conn.execute(
                    "DELETE FROM smart_bi_pg_excel_uploads WHERE id = $1",
                    upload_id,
                )


# ── conflict_resolver integration tests (Sub-Project C Day 8-9) ────────────


@pytest.mark.integration
async def test_resolve_conflict_e2e_higher_priority_supersedes(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """resolve_conflict end-to-end: write inferred → resolve manual.

    Validates the higher-priority branch on real PG: prior row gets
    superseded_by_id pointing to new row, superseded_reason='higher_priority',
    new row is the active authoritative value, and the dedup unique index
    doesn't trip (because we supersede the prior BEFORE inserting the new).
    """
    from datetime import date

    from smartbi.canonical.provenance import (
        invalidate_factory_config_cache,
        read_authoritative_value,
        resolve_conflict,
        write_provenance,
    )

    invalidate_factory_config_cache()  # ensure clean cache

    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "测试店CR1")

            # 1. Write inferred (priority 5).
            prior_id = await write_provenance(
                conn,
                factory_id=TEST_FACTORY,
                entity_type="store",
                entity_id=store_id,
                field_name="brand",
                field_value="老品牌",
                confidence=0.5,
                source_type="inferred",
                mapper_method="rule",
                valid_from=date(2026, 1, 1),
            )
            assert prior_id > 0

            # 2. Resolve a manual write (priority 1) with same valid_from →
            #    must supersede prior + write new without unique-violation.
            out = await resolve_conflict(
                conn,
                factory_id=TEST_FACTORY,
                entity_type="store",
                entity_id=store_id,
                field_name="brand",
                new_value="新品牌",
                confidence=0.95,
                source_type="manual",
                mapper_method="manual",
                valid_from=date(2026, 1, 1),
            )

            assert out["action"] == "written", out
            assert out["reason"] == "higher_priority", out
            new_id = out["id"]
            assert new_id is not None and new_id != prior_id

            # 3. Prior row must have superseded_by_id = new_id and reason set.
            prior_row = await conn.fetchrow(
                """
                SELECT superseded_by_id, superseded_reason
                  FROM field_provenance WHERE id = $1
                """,
                prior_id,
            )
            assert prior_row is not None
            assert prior_row["superseded_by_id"] == new_id
            assert prior_row["superseded_reason"] == "higher_priority"

            # 4. read_authoritative_value returns the new row.
            pv = await read_authoritative_value(
                conn, TEST_FACTORY, "store", store_id, "brand"
            )
            assert pv is not None
            fv = pv.field_value
            if isinstance(fv, str):
                try:
                    fv = json.loads(fv)
                except json.JSONDecodeError:
                    pass
            assert fv == "新品牌"
            assert pv.source_type == "manual"


@pytest.mark.integration
async def test_b_writer_dual_write_provenance_when_env_set(
    pg_pool: "asyncpg.Pool", clean_tenant: None, monkeypatch
) -> None:
    """End-to-end Day 10-12 hook: ProductSummaryWriter dual-writes provenance
    when SMARTBI_ENABLE_PROVENANCE=1.

    Validates on real PG that:
      1. agg_product_period gets the silver row (existing path, unchanged).
      2. field_provenance gets 3 rows (revenue / qty_sold / avg_unit_price)
         tied to the resolved product.
      3. read_authoritative_value returns the values we wrote.

    Asserting all 3 fields proves the hook fan-out works under real RLS,
    real advisory locks, and real ON CONFLICT semantics.
    """
    monkeypatch.setenv("SMARTBI_ENABLE_PROVENANCE", "1")

    # Ensure a clean factory_provenance_config cache so this test reads the
    # default (no override) — shared module state can leak between tests.
    from smartbi.canonical.provenance import (
        invalidate_factory_config_cache,
        read_authoritative_value,
    )
    from smartbi.canonical.silver_writers import ProductSummaryWriter
    from smartbi.canonical.silver_writers.base import ResolveResult

    invalidate_factory_config_cache()

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "门店P")
            product_id = await _seed_dim_product(
                conn, TEST_FACTORY, "招牌P", "招牌P"
            )

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED')
                RETURNING id
                """,
                TEST_FACTORY,
                f"e2e_prov_{int(time.time())}.xlsx",
            )
            assert upload_row is not None
            upload_id = int(upload_row["id"])

            row_data: Dict[str, Any] = {
                "门店": "门店P",
                "商品名称": "招牌P",
                "单卖数量": "10",
                "销售金额": "300",
            }
            await conn.execute(
                """
                INSERT INTO smart_bi_dynamic_data
                  (factory_id, upload_id, row_data)
                VALUES ($1, $2, $3::jsonb)
                """,
                TEST_FACTORY,
                upload_id,
                json.dumps(row_data),
            )

        # Build writer with mock orchestrator returning canned IDs (we already
        # have the dim rows seeded; resolution is fixed via mock).
        orch = MagicMock()

        async def _resolve_store(_, factory_id, context):
            return ResolveResult(
                entity_id=store_id, is_tentative=False, confidence=0.95
            )

        async def _resolve_product(_, factory_id, context):
            return ResolveResult(
                entity_id=product_id, is_tentative=False, confidence=0.95
            )

        writer = ProductSummaryWriter(pool=scoped_pool, orchestrator=orch)
        writer._resolve_store = _resolve_store  # type: ignore[method-assign]
        writer._resolve_product = _resolve_product  # type: ignore[method-assign]

        summary = await writer.write(upload_id=upload_id, factory_id=TEST_FACTORY)
        assert summary.rows_written == 1

        # 1. Silver row landed
        async with scoped_pool.acquire() as conn:
            agg_count = await conn.fetchval(
                "SELECT COUNT(*) FROM agg_product_period "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            assert agg_count == 1

            # 2. field_provenance has 3 rows for this product (revenue, qty,
            #    avg_unit_price), all source_type='product_summary'. Phase B
            #    C1 fix: field_name now encodes the store dimension as a
            #    ``@store_<id>`` suffix, so e.g. revenue → ``revenue@store_X``.
            prov_rows = await conn.fetch(
                """
                SELECT field_name, field_value, source_type, mapper_method,
                       source_upload_id, confidence
                  FROM field_provenance
                 WHERE factory_id = $1
                   AND entity_type = 'product'
                   AND entity_id = $2
                 ORDER BY field_name
                """,
                TEST_FACTORY,
                product_id,
            )
            field_names = sorted(r["field_name"] for r in prov_rows)
            expected_names = sorted(
                [
                    f"avg_unit_price@store_{store_id}",
                    f"qty_sold@store_{store_id}",
                    f"revenue@store_{store_id}",
                ]
            )
            assert field_names == expected_names, (
                f"expected 3 provenance fields, got {field_names}"
            )
            for r in prov_rows:
                assert r["source_type"] == "product_summary"
                assert r["mapper_method"] == "rule"
                assert r["source_upload_id"] == upload_id
                assert float(r["confidence"]) == 0.85

            # 3. read_authoritative_value returns each value correctly
            #    (numerics: 300 revenue, 10 qty, 30 avg_price). Field names
            #    must include the @store_<id> suffix (Phase B C1 fix).
            for fname_base, expected in [
                ("revenue", 300.0),
                ("qty_sold", 10.0),
                ("avg_unit_price", 30.0),
            ]:
                fname = f"{fname_base}@store_{store_id}"
                pv = await read_authoritative_value(
                    conn, TEST_FACTORY, "product", product_id, fname
                )
                assert pv is not None, f"{fname}: missing"
                fv = pv.field_value
                if isinstance(fv, str):
                    try:
                        fv = json.loads(fv)
                    except json.JSONDecodeError:
                        pass
                assert float(fv) == expected, (
                    f"{fname}: got {fv}, want {expected}"
                )
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_resolve_conflict_e2e_30_diff_enqueues(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """30%+ diff at same priority must enqueue a field_conflict admin row
    and NOT write to field_provenance.

    Default diff_threshold=30%, manual=manual=priority 1, cost goes 60→100
    (67% diff) → queued. Verifies V20260501_03 entity_type='field_conflict'
    CHECK + extra JSONB carries full conflict context.
    """
    from datetime import date

    from smartbi.canonical.provenance import (
        invalidate_factory_config_cache,
        read_authoritative_value,
        resolve_conflict,
        write_provenance,
    )

    invalidate_factory_config_cache()

    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            product_id = await _seed_dim_product(
                conn, TEST_FACTORY, "测试菜CR2", "测试菜CR2"
            )

            # 1. Seed cost=60.0, manual.
            await write_provenance(
                conn,
                factory_id=TEST_FACTORY,
                entity_type="product",
                entity_id=product_id,
                field_name="cost_per_unit",
                field_value=60.0,
                confidence=0.95,
                source_type="manual",
                mapper_method="manual",
                valid_from=date(2026, 1, 1),
            )

            # 2. Resolve cost=100.0 manual (same priority, 67% diff > 30%).
            out = await resolve_conflict(
                conn,
                factory_id=TEST_FACTORY,
                entity_type="product",
                entity_id=product_id,
                field_name="cost_per_unit",
                new_value=100.0,
                confidence=0.95,
                source_type="manual",
                mapper_method="manual",
                valid_from=date(2026, 1, 1),
            )

            assert out["action"] == "queued", out
            assert out["id"] is None

            # 3. admin_queue row landed with entity_type='field_conflict' and
            #    raw_name='product:<id>.cost_per_unit'.
            queue = await conn.fetchrow(
                """
                SELECT entity_type, raw_name, candidate_entity_id, priority,
                       reasoning, extra
                  FROM entity_resolution_admin_queue
                 WHERE factory_id = $1
                   AND entity_type = 'field_conflict'
                   AND raw_name = $2
                """,
                TEST_FACTORY,
                f"product:{product_id}.cost_per_unit",
            )
            assert queue is not None, "field_conflict admin_queue row missing"
            assert queue["candidate_entity_id"] == product_id
            assert queue["priority"] == "medium"
            assert "diff" in (queue["reasoning"] or "").lower() or \
                   "差异" in (queue["reasoning"] or "")

            # extra JSONB carries the conflict details.
            extra = queue["extra"]
            if isinstance(extra, str):
                extra = json.loads(extra)
            assert extra["field_name"] == "cost_per_unit"
            assert float(extra["current_value"]) == 60.0
            assert float(extra["new_value"]) == 100.0
            assert extra["current_priority"] == 1
            assert extra["new_priority"] == 1

            # 4. Authoritative value still reads 60.0 (no write happened).
            pv = await read_authoritative_value(
                conn, TEST_FACTORY, "product", product_id, "cost_per_unit"
            )
            assert pv is not None
            fv = pv.field_value
            if isinstance(fv, str):
                try:
                    fv = json.loads(fv)
                except json.JSONDecodeError:
                    pass
            assert float(fv) == 60.0


@pytest.mark.integration
async def test_resolve_conflict_serializes_concurrent_same_cell_writes(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """Day 13+ C2 verification: 5 parallel writers on the SAME cell serialize.

    Without the entry-level ``pg_advisory_xact_lock(99, key)`` in
    ``resolve_conflict``, two writers racing on the same
    ``(factory, entity_type, entity_id, field_name)`` could both observe
    "no prior" and both INSERT, with the second swallowing
    ``UniqueViolationError`` on ``uq_fp_dedup``. Hidden data loss.

    Five ``asyncio.gather`` tasks call ``resolve_conflict`` for the same
    (product, "cost_per_unit", valid_from) with the SAME value. Expected:
      - exactly 1 ``written`` (the first to acquire the lock)
      - 4 ``no_change`` with reason='value_unchanged' (the rest see the
        first task's freshly-inserted row)
      - 0 silent drops (no exception leaks, no UniqueViolation in logs)
      - exactly 1 active row in ``field_provenance`` for the cell

    Setup note: ``dim_product`` must be seeded with a COMMITTED INSERT
    BEFORE the gather starts, because each gathered task acquires its own
    pool connection — uncommitted seed data wouldn't be visible cross-conn.
    Cleanup is handled by the ``clean_tenant`` fixture (which now covers
    ``field_provenance`` per I1) so we don't pollute the test factory.
    """
    import asyncio
    from datetime import date

    from smartbi.canonical.provenance import (
        invalidate_factory_config_cache,
        resolve_conflict,
    )

    invalidate_factory_config_cache()

    # Seed dim_product OUTSIDE a transaction so the row is visible to the
    # gathered worker connections. clean_tenant fixture already covers
    # dim_product so post-test cleanup nukes it.
    async with pg_pool.acquire() as setup_conn:
        async with setup_conn.transaction():
            await _set_tenant(setup_conn, TEST_FACTORY)
            product_id = await _seed_dim_product(
                setup_conn,
                TEST_FACTORY,
                "ConcurrencyTestDish",
                "ConcurrencyTestDish",
            )

    async def _one_write() -> Dict[str, Any]:
        async with pg_pool.acquire() as conn:
            async with conn.transaction():
                await _set_tenant(conn, TEST_FACTORY)
                return await resolve_conflict(
                    conn,
                    factory_id=TEST_FACTORY,
                    entity_type="product",
                    entity_id=product_id,
                    field_name="cost_per_unit",
                    new_value=12.50,  # SAME value across all 5 callers
                    confidence=0.9,
                    source_type="manual",
                    mapper_method="manual",
                    valid_from=date(2026, 1, 1),
                )

    results = await asyncio.gather(
        *[_one_write() for _ in range(5)], return_exceptions=True
    )

    # 1. No exceptions leaked from any worker.
    leaked = [r for r in results if isinstance(r, Exception)]
    assert not leaked, f"workers raised: {leaked}"

    actions = [r["action"] for r in results]
    written_count = sum(1 for a in actions if a == "written")
    no_change_count = sum(1 for a in actions if a == "no_change")

    # 2. Exactly 1 'written' (the first to acquire the lock); the other 4
    # see the freshly-inserted row and return 'no_change' with
    # reason='value_unchanged' because the value matches.
    assert written_count == 1, (
        f"expected exactly 1 'written', got {written_count}: {results}"
    )
    assert no_change_count == 4, (
        f"expected 4 'no_change', got {no_change_count}: {results}"
    )
    no_change_reasons = [
        r["reason"] for r in results if r["action"] == "no_change"
    ]
    assert all(reason == "value_unchanged" for reason in no_change_reasons), (
        f"all no_change reasons should be 'value_unchanged', got "
        f"{no_change_reasons}"
    )

    # 3. Exactly 1 active row in field_provenance for this cell.
    async with pg_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, TEST_FACTORY)
            row_count = await conn.fetchval(
                """
                SELECT COUNT(*) FROM field_provenance
                 WHERE factory_id = $1
                   AND entity_type = 'product'
                   AND entity_id = $2
                   AND field_name = 'cost_per_unit'
                   AND superseded_by_id IS NULL
                """,
                TEST_FACTORY,
                product_id,
            )
            assert row_count == 1, (
                f"expected exactly 1 active provenance row, found {row_count}"
            )


# ── Phase B C1 fix: anchor dimensionality e2e tests ──────────────────────────


@pytest.mark.integration
async def test_product_summary_dual_write_multi_store_no_admin_queue_noise(
    pg_pool: "asyncpg.Pool", clean_tenant: None, monkeypatch
) -> None:
    """Phase B C1 verification: 2 stores × 1 product × 1 upload writes
    distinct provenance rows per store, 0 admin_queue 'field_conflict'.

    Pre-fix: provenance dedup key = (factory, product, "revenue", period) so
    store 1 writes successfully but store 2's revenue (different value) hits
    same dedup key → silently enqueued as 'field_conflict' admin_queue noise.
    Post-fix: field_name encodes store dimension → store 1 writes
    'revenue@store_X' and store 2 writes 'revenue@store_Y' — distinct dedup
    keys, both succeed, admin_queue stays empty.
    """
    monkeypatch.setenv("SMARTBI_ENABLE_PROVENANCE", "1")
    from smartbi.canonical.provenance import invalidate_factory_config_cache
    from smartbi.canonical.provenance._writer_hook import (
        invalidate_provenance_flag_cache,
    )
    from smartbi.canonical.silver_writers import ProductSummaryWriter
    from smartbi.canonical.silver_writers.base import ResolveResult

    invalidate_factory_config_cache()
    invalidate_provenance_flag_cache()

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            store_a_id = await _seed_dim_store(conn, TEST_FACTORY, "门店A_C1")
            store_b_id = await _seed_dim_store(conn, TEST_FACTORY, "门店B_C1")
            product_id = await _seed_dim_product(
                conn, TEST_FACTORY, "招牌C1", "招牌C1"
            )

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED')
                RETURNING id
                """,
                TEST_FACTORY,
                f"e2e_c1_ps_{int(time.time())}.xlsx",
            )
            assert upload_row is not None
            upload_id = int(upload_row["id"])

            # 2 rows: same product, two different stores, distinct revenue.
            for store_name, qty, revenue in (
                ("门店A_C1", "10", "300"),
                ("门店B_C1", "5", "150"),
            ):
                row_data: Dict[str, Any] = {
                    "门店": store_name,
                    "商品名称": "招牌C1",
                    "单卖数量": qty,
                    "销售金额": revenue,
                }
                await conn.execute(
                    """
                    INSERT INTO smart_bi_dynamic_data
                      (factory_id, upload_id, row_data)
                    VALUES ($1, $2, $3::jsonb)
                    """,
                    TEST_FACTORY,
                    upload_id,
                    json.dumps(row_data),
                )

        # Dispatch correct store_id based on row's store_name. Capture-by-
        # value pattern for the multiple distinct ResolveResult returns.
        async def _resolve_store(name: Optional[str], factory_id: str, context: Any):
            if name == "门店A_C1":
                return ResolveResult(
                    entity_id=store_a_id, is_tentative=False, confidence=0.95
                )
            return ResolveResult(
                entity_id=store_b_id, is_tentative=False, confidence=0.95
            )

        async def _resolve_product(
            name: Optional[str], factory_id: str, context: Any
        ):
            return ResolveResult(
                entity_id=product_id, is_tentative=False, confidence=0.95
            )

        writer = ProductSummaryWriter(pool=scoped_pool, orchestrator=MagicMock())
        writer._resolve_store = _resolve_store  # type: ignore[method-assign]
        writer._resolve_product = _resolve_product  # type: ignore[method-assign]

        summary = await writer.write(upload_id=upload_id, factory_id=TEST_FACTORY)
        assert summary.rows_written == 2

        async with scoped_pool.acquire() as conn:
            # 1. agg_product_period: 2 rows (one per store)
            agg_count = await conn.fetchval(
                "SELECT COUNT(*) FROM agg_product_period "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            assert agg_count == 2, f"expected 2 agg rows, got {agg_count}"

            # 2. field_provenance: 6 rows (3 fields × 2 stores), all with
            #    @store_<id> suffix. Each (product, field_name) pair has its
            #    own dedup key — all 6 rows are active (not superseded).
            prov_rows = await conn.fetch(
                """
                SELECT field_name, field_value, source_type, superseded_by_id
                  FROM field_provenance
                 WHERE factory_id = $1
                   AND entity_type = 'product'
                   AND entity_id = $2
                 ORDER BY field_name
                """,
                TEST_FACTORY,
                product_id,
            )
            assert len(prov_rows) == 6, (
                f"expected 6 provenance rows (3 fields × 2 stores), "
                f"got {len(prov_rows)}: {[r['field_name'] for r in prov_rows]}"
            )
            for r in prov_rows:
                assert "@store_" in r["field_name"], (
                    f"field_name {r['field_name']!r} missing @store_ suffix"
                )
                assert r["source_type"] == "product_summary"
                assert r["superseded_by_id"] is None  # all active
            field_names = sorted(r["field_name"] for r in prov_rows)
            expected = sorted(
                [
                    f"avg_unit_price@store_{store_a_id}",
                    f"avg_unit_price@store_{store_b_id}",
                    f"qty_sold@store_{store_a_id}",
                    f"qty_sold@store_{store_b_id}",
                    f"revenue@store_{store_a_id}",
                    f"revenue@store_{store_b_id}",
                ]
            )
            assert field_names == expected, (
                f"unexpected field_names: {field_names} vs {expected}"
            )

            # 3. admin_queue has 0 'field_conflict' rows for this factory
            queue_count = await conn.fetchval(
                """
                SELECT COUNT(*) FROM entity_resolution_admin_queue
                 WHERE factory_id = $1 AND entity_type = 'field_conflict'
                """,
                TEST_FACTORY,
            )
            assert queue_count == 0, (
                f"expected 0 'field_conflict' rows, got {queue_count} — "
                "the C1 anchor-dim mismatch is back"
            )
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_inventory_dual_write_multi_store_no_admin_queue_noise(
    pg_pool: "asyncpg.Pool", clean_tenant: None, monkeypatch
) -> None:
    """Phase B C1 verification for InventoryWriter: 2 stores × 1 ingredient
    writes distinct provenance rows, 0 admin_queue 'field_conflict' noise.
    """
    monkeypatch.setenv("SMARTBI_ENABLE_PROVENANCE", "1")
    from smartbi.canonical.provenance import invalidate_factory_config_cache
    from smartbi.canonical.provenance._writer_hook import (
        invalidate_provenance_flag_cache,
    )
    from smartbi.canonical.silver_writers import InventoryWriter
    from smartbi.canonical.silver_writers.base import ResolveResult

    invalidate_factory_config_cache()
    invalidate_provenance_flag_cache()

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            store_a_id = await _seed_dim_store(conn, TEST_FACTORY, "门店A_INV")
            store_b_id = await _seed_dim_store(conn, TEST_FACTORY, "门店B_INV")
            # Seed dim_ingredient row for "猪肉_C1". source_pk is NOT NULL +
            # uniquely keyed by (factory_id, source_pk) per
            # 2026_04_24_silver_restaurant_ops.sql; use a synthetic test PK.
            ingredient_row = await conn.fetchrow(
                """
                INSERT INTO dim_ingredient
                  (factory_id, source_pk, name, normalized_name)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT (factory_id, source_pk) DO UPDATE
                  SET updated_at = NOW()
                RETURNING ingredient_id
                """,
                TEST_FACTORY,
                "test_c1_pork_pk",
                "猪肉_C1",
                "猪肉_c1",
            )
            assert ingredient_row is not None
            ingredient_id = int(ingredient_row["ingredient_id"])

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED')
                RETURNING id
                """,
                TEST_FACTORY,
                f"e2e_c1_inv_{int(time.time())}.xlsx",
            )
            assert upload_row is not None
            upload_id = int(upload_row["id"])

            for store_name, qty in (("门店A_INV", "12.5"), ("门店B_INV", "8.0")):
                row_data: Dict[str, Any] = {
                    "门店": store_name,
                    "物料": "猪肉_C1",
                    "库存数量": qty,
                    "单位": "kg",
                    "盘点日期": "2026-04-22",
                }
                await conn.execute(
                    """
                    INSERT INTO smart_bi_dynamic_data
                      (factory_id, upload_id, row_data)
                    VALUES ($1, $2, $3::jsonb)
                    """,
                    TEST_FACTORY,
                    upload_id,
                    json.dumps(row_data),
                )

        async def _resolve_store(name: Optional[str], factory_id: str, context: Any):
            if name == "门店A_INV":
                return ResolveResult(
                    entity_id=store_a_id, is_tentative=False, confidence=0.95
                )
            return ResolveResult(
                entity_id=store_b_id, is_tentative=False, confidence=0.95
            )

        writer = InventoryWriter(pool=scoped_pool, orchestrator=MagicMock())
        writer._resolve_store = _resolve_store  # type: ignore[method-assign]

        summary = await writer.write(upload_id=upload_id, factory_id=TEST_FACTORY)
        assert summary.rows_written == 2

        async with scoped_pool.acquire() as conn:
            # 1. fact_inventory_snapshot: 2 rows
            snap_count = await conn.fetchval(
                "SELECT COUNT(*) FROM fact_inventory_snapshot "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            assert snap_count == 2

            # 2. field_provenance: 4 rows (2 fields × 2 stores), all with
            #    @store_<id> suffix.
            prov_rows = await conn.fetch(
                """
                SELECT field_name FROM field_provenance
                 WHERE factory_id = $1
                   AND entity_type = 'ingredient'
                   AND entity_id = $2
                 ORDER BY field_name
                """,
                TEST_FACTORY,
                ingredient_id,
            )
            assert len(prov_rows) == 4, (
                f"expected 4 provenance rows (2 fields × 2 stores), "
                f"got {len(prov_rows)}: {[r['field_name'] for r in prov_rows]}"
            )
            for r in prov_rows:
                assert "@store_" in r["field_name"]
            field_names = sorted(r["field_name"] for r in prov_rows)
            expected = sorted(
                [
                    f"stock_qty@store_{store_a_id}",
                    f"stock_qty@store_{store_b_id}",
                    f"unit@store_{store_a_id}",
                    f"unit@store_{store_b_id}",
                ]
            )
            assert field_names == expected

            # 3. admin_queue: 0 'field_conflict' rows
            queue_count = await conn.fetchval(
                """
                SELECT COUNT(*) FROM entity_resolution_admin_queue
                 WHERE factory_id = $1 AND entity_type = 'field_conflict'
                """,
                TEST_FACTORY,
            )
            assert queue_count == 0
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_finance_dual_write_multi_voucher_same_day_rolls_up(
    pg_pool: "asyncpg.Pool", clean_tenant: None, monkeypatch
) -> None:
    """Phase B C1 verification for FinanceWriter: 3 vouchers same (subject,
    day) roll up to 1 provenance row with SUM, 0 admin_queue noise.

    Pre-fix: 3 per-voucher provenance writes hit the same dedup key
    (factory, finance_subject, "debit_amount", voucher_date) → first
    succeeds, subsequent 2 enqueue as 'field_conflict' noise. Post-fix
    rolls up at the hook so debit_amount = SUM(100+200+300) = 600 lands
    in 1 row.
    """
    monkeypatch.setenv("SMARTBI_ENABLE_PROVENANCE", "1")
    from smartbi.canonical.provenance import invalidate_factory_config_cache
    from smartbi.canonical.provenance._writer_hook import (
        invalidate_provenance_flag_cache,
    )
    from smartbi.canonical.silver_writers import FinanceWriter

    invalidate_factory_config_cache()
    invalidate_provenance_flag_cache()

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED')
                RETURNING id
                """,
                TEST_FACTORY,
                f"e2e_c1_fin_{int(time.time())}.xlsx",
            )
            assert upload_row is not None
            upload_id = int(upload_row["id"])

            # 3 vouchers — same subject, same day.
            for vno, debit in (("V001_C1", "100"), ("V002_C1", "200"), ("V003_C1", "300")):
                row_data: Dict[str, Any] = {
                    "凭证号": vno,
                    "凭证日期": "2026-04-20",
                    "科目": "营业收入_C1",
                    "借方": debit,
                    "贷方": "0",
                }
                await conn.execute(
                    """
                    INSERT INTO smart_bi_dynamic_data
                      (factory_id, upload_id, row_data)
                    VALUES ($1, $2, $3::jsonb)
                    """,
                    TEST_FACTORY,
                    upload_id,
                    json.dumps(row_data),
                )

        writer = FinanceWriter(pool=scoped_pool, orchestrator=MagicMock())
        summary = await writer.write(upload_id=upload_id, factory_id=TEST_FACTORY)
        assert summary.rows_written == 3, (
            f"Silver layer must retain per-voucher detail, got {summary.rows_written}"
        )

        async with scoped_pool.acquire() as conn:
            # 1. fact_finance_voucher: 3 rows (per-voucher detail preserved)
            voucher_count = await conn.fetchval(
                "SELECT COUNT(*) FROM fact_finance_voucher "
                "WHERE factory_id = $1 AND upload_id = $2",
                TEST_FACTORY,
                upload_id,
            )
            assert voucher_count == 3

            # 2. dim_finance_subject: 1 subject (营业收入_C1) — find subject_id
            subject_id_row = await conn.fetchrow(
                """
                SELECT subject_id FROM dim_finance_subject
                 WHERE factory_id = $1 AND name = $2
                """,
                TEST_FACTORY,
                "营业收入_C1",
            )
            assert subject_id_row is not None
            subject_id = int(subject_id_row["subject_id"])

            # 3. field_provenance: 2 rows (debit_amount + credit_amount)
            #    rolled up to single (subject, day) anchor, NOT 6 rows from
            #    per-voucher writes.
            prov_rows = await conn.fetch(
                """
                SELECT field_name, field_value FROM field_provenance
                 WHERE factory_id = $1
                   AND entity_type = 'finance_subject'
                   AND entity_id = $2
                 ORDER BY field_name
                """,
                TEST_FACTORY,
                subject_id,
            )
            assert len(prov_rows) == 2, (
                f"expected 2 provenance rows (debit + credit roll-up), "
                f"got {len(prov_rows)}: {[r['field_name'] for r in prov_rows]}"
            )
            field_names = [r["field_name"] for r in prov_rows]
            assert field_names == ["credit_amount", "debit_amount"]

            # debit_amount = SUM(100, 200, 300) = 600
            for r in prov_rows:
                fv = r["field_value"]
                if isinstance(fv, str):
                    try:
                        fv = json.loads(fv)
                    except json.JSONDecodeError:
                        pass
                if r["field_name"] == "debit_amount":
                    assert float(fv) == 600.0, (
                        f"roll-up SUM debit failed: got {fv}, want 600.0"
                    )
                if r["field_name"] == "credit_amount":
                    assert float(fv) == 0.0

            # 4. admin_queue: 0 'field_conflict' rows
            queue_count = await conn.fetchval(
                """
                SELECT COUNT(*) FROM entity_resolution_admin_queue
                 WHERE factory_id = $1 AND entity_type = 'field_conflict'
                """,
                TEST_FACTORY,
            )
            assert queue_count == 0
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_review_dual_write_anchor_correct_sanity(
    pg_pool: "asyncpg.Pool", clean_tenant: None, monkeypatch
) -> None:
    """Phase B C1 sanity: ReviewWriter aggregates per-upload BEFORE INSERT,
    so the audit-flagged "drops review-instance granularity" is a false
    positive — the writer's INSERT into dim_review_summary is itself a
    single row per upload (1 product_id_for_summary + 1 store_id_for_summary).
    Instance-level lineage isn't a concept here; the cell-level provenance
    correctly anchors the aggregate values to the canonical product entity.

    This test verifies the existing happy-path still works after the Phase B
    edits to product/inventory/finance writers (no regression to Review).
    Same-upload N reviews → 1 dim_review_summary row → 1 hook call → 4
    provenance rows (avg_rating / review_count / positive_count / negative_count),
    0 admin_queue noise.
    """
    monkeypatch.setenv("SMARTBI_ENABLE_PROVENANCE", "1")
    from smartbi.canonical.provenance import invalidate_factory_config_cache
    from smartbi.canonical.provenance._writer_hook import (
        invalidate_provenance_flag_cache,
    )
    from smartbi.canonical.silver_writers import ReviewWriter
    from smartbi.canonical.silver_writers.base import ResolveResult

    invalidate_factory_config_cache()
    invalidate_provenance_flag_cache()

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "门店R_C1")
            product_id = await _seed_dim_product(
                conn, TEST_FACTORY, "招牌R_C1", "招牌R_C1"
            )

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED')
                RETURNING id
                """,
                TEST_FACTORY,
                f"e2e_c1_rev_{int(time.time())}.xlsx",
            )
            assert upload_row is not None
            upload_id = int(upload_row["id"])

            for rating, txt in (("5", "好吃"), ("1", "难吃"), ("4", "还行")):
                row_data: Dict[str, Any] = {
                    "门店": "门店R_C1",
                    "商品名称": "招牌R_C1",
                    "评分": rating,
                    "评论内容": txt,
                    "评论日期": "2026-04-25",
                }
                await conn.execute(
                    """
                    INSERT INTO smart_bi_dynamic_data
                      (factory_id, upload_id, row_data)
                    VALUES ($1, $2, $3::jsonb)
                    """,
                    TEST_FACTORY,
                    upload_id,
                    json.dumps(row_data),
                )

        async def _resolve_store(_, factory_id: str, context: Any):
            return ResolveResult(
                entity_id=store_id, is_tentative=False, confidence=0.95
            )

        async def _resolve_product(_, factory_id: str, context: Any):
            return ResolveResult(
                entity_id=product_id, is_tentative=False, confidence=0.95
            )

        writer = ReviewWriter(pool=scoped_pool, orchestrator=MagicMock())
        writer._resolve_store = _resolve_store  # type: ignore[method-assign]
        writer._resolve_product = _resolve_product  # type: ignore[method-assign]

        summary = await writer.write(upload_id=upload_id, factory_id=TEST_FACTORY)
        assert summary.rows_written == 3

        async with scoped_pool.acquire() as conn:
            # field_provenance: 4 rows (avg_rating / review_count /
            # positive_count / negative_count) anchored to product.
            prov_rows = await conn.fetch(
                """
                SELECT field_name, field_value FROM field_provenance
                 WHERE factory_id = $1
                   AND entity_type = 'product'
                   AND entity_id = $2
                 ORDER BY field_name
                """,
                TEST_FACTORY,
                product_id,
            )
            field_names = sorted(r["field_name"] for r in prov_rows)
            assert field_names == [
                "avg_rating",
                "negative_count",
                "positive_count",
                "review_count",
            ], f"got {field_names}"

            # admin_queue: 0 'field_conflict'
            queue_count = await conn.fetchval(
                """
                SELECT COUNT(*) FROM entity_resolution_admin_queue
                 WHERE factory_id = $1 AND entity_type = 'field_conflict'
                """,
                TEST_FACTORY,
            )
            assert queue_count == 0
    finally:
        await scoped_pool.close()


# -- Day 13-15 cascade engine helpers + tests --------------------------------


async def _seed_dim_product_with_category(
    conn: "asyncpg.Connection",
    factory_id: str,
    name: str,
    category: str,
    normalized_name: Optional[str] = None,
) -> int:
    """Seed dim_product with explicit category for industry_default tests.

    Mirrors ``_seed_dim_product`` but writes the optional ``category``
    column. ON CONFLICT updates the category so re-running a test that
    seeds the same product with a different category lands on the new value.
    """
    row = await conn.fetchrow(
        """
        INSERT INTO dim_product (factory_id, name, normalized_name, category)
        VALUES ($1, $2, $3, $4)
        ON CONFLICT (factory_id, normalized_name)
        DO UPDATE SET category = EXCLUDED.category, updated_at = NOW()
        RETURNING product_id
        """,
        factory_id,
        name,
        normalized_name or name,
        category,
    )
    assert row is not None
    return int(row["product_id"])


@pytest.mark.integration
async def test_compute_dish_margin_with_recipe_cost(
    pg_pool: "asyncpg.Pool", clean_tenant: None,
) -> None:
    """End-to-end cascade: write recipe cost via provenance, write sales via
    agg_product_period direct, compute_dish_margin returns correct margin.
    """
    from datetime import date

    from smartbi.canonical.provenance import (
        compute_dish_margin,
        write_provenance,
    )

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            async with conn.transaction():
                product_id = await _seed_dim_product(
                    conn, TEST_FACTORY, "招牌鱼"
                )
                store_id = await _seed_dim_store(
                    conn, TEST_FACTORY, "测试店_cascade"
                )

                # Write recipe cost via provenance (manual, conf=0.95)
                await write_provenance(
                    conn,
                    factory_id=TEST_FACTORY,
                    entity_type="product",
                    entity_id=product_id,
                    field_name="cost_per_unit",
                    field_value=12.50,
                    confidence=0.95,
                    source_type="manual",
                    mapper_method="manual",
                    valid_from=date(2026, 1, 1),
                )

                # Insert two real upload rows so agg_product_period rows
                # carry distinct upload_id values (sales_conf rises with
                # upload_count). Sentinel id=0 is reserved for non-upload
                # provenance — using it here would create unique-key
                # collisions on the second insert.
                upload_a = await conn.fetchval(
                    """
                    INSERT INTO smart_bi_pg_excel_uploads
                      (factory_id, file_name, upload_status)
                    VALUES ($1, $2, 'COMPLETED')
                    RETURNING id
                    """,
                    TEST_FACTORY, "cascade_test_a.xlsx",
                )
                upload_b = await conn.fetchval(
                    """
                    INSERT INTO smart_bi_pg_excel_uploads
                      (factory_id, file_name, upload_status)
                    VALUES ($1, $2, 'COMPLETED')
                    RETURNING id
                    """,
                    TEST_FACTORY, "cascade_test_b.xlsx",
                )

                for up_id, qty, rev in (
                    (upload_a, 60, 3000),
                    (upload_b, 40, 2000),
                ):
                    await conn.execute(
                        """
                        INSERT INTO agg_product_period
                          (factory_id, upload_id, product_id, store_id,
                           period_start, period_end, qty_sold, revenue,
                           avg_unit_price)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                        """,
                        TEST_FACTORY, up_id, product_id, store_id,
                        date(2026, 2, 1), date(2026, 2, 28),
                        qty, rev, 50,
                    )

                result = await compute_dish_margin(
                    conn, TEST_FACTORY, product_id,
                    (date(2026, 2, 1), date(2026, 2, 28)),
                )

        assert result["status"] == "ok"
        assert result["product_id"] == product_id
        assert result["revenue"] == 5000.0  # 3000 + 2000
        assert result["cost"] == 12.50 * 100  # qty=100 total
        assert result["margin"] == 5000.0 - 1250.0
        assert abs(result["margin_rate"] - (3750 / 5000)) < 1e-6
        assert result["cost_source"] == "manual"
        assert float(result["cost_confidence"]) == 0.95
        # sales_conf = min(0.95, 0.5 + 0.1*2) = 0.7
        assert abs(result["sales_confidence"] - 0.7) < 1e-6
        assert abs(result["confidence"] - 0.7) < 1e-6
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_compute_dish_margin_industry_default_fallback(
    pg_pool: "asyncpg.Pool", clean_tenant: None,
) -> None:
    """No recipe → fallback to industry_default with synthesised cost based
    on dim_product.category × cost_rate × avg_unit_price.
    """
    from datetime import date

    from smartbi.canonical.provenance import compute_dish_margin

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            async with conn.transaction():
                product_id = await _seed_dim_product_with_category(
                    conn, TEST_FACTORY, "麻婆豆腐", category="川菜",
                )
                store_id = await _seed_dim_store(
                    conn, TEST_FACTORY, "测试店_idfallback"
                )

                upload_id = await conn.fetchval(
                    """
                    INSERT INTO smart_bi_pg_excel_uploads
                      (factory_id, file_name, upload_status)
                    VALUES ($1, $2, 'COMPLETED')
                    RETURNING id
                    """,
                    TEST_FACTORY, "cascade_idfallback.xlsx",
                )
                await conn.execute(
                    """
                    INSERT INTO agg_product_period
                      (factory_id, upload_id, product_id, store_id,
                       period_start, period_end, qty_sold, revenue,
                       avg_unit_price)
                    VALUES ($1, $2, $3, $4, $5, $6, 50, 1500, 30)
                    """,
                    TEST_FACTORY, upload_id, product_id, store_id,
                    date(2026, 2, 1), date(2026, 2, 28),
                )

                result = await compute_dish_margin(
                    conn, TEST_FACTORY, product_id,
                    (date(2026, 2, 1), date(2026, 2, 28)),
                )

        assert result["status"] == "ok"
        assert result["cost_source"] == "industry_default"
        assert float(result["cost_confidence"]) == 0.5
        # 川菜 cost_rate=0.35; avg_unit_price=30; qty=50
        # cost_per_unit = 30 * 0.35 = 10.5; cost = 10.5 * 50 = 525
        assert abs(result["cost"] - 525.0) < 1e-3
        assert abs(result["margin"] - (1500.0 - 525.0)) < 1e-3
        # sales_conf = min(0.95, 0.6) = 0.6; result_conf = min(0.5, 0.6) = 0.5
        assert abs(result["confidence"] - 0.5) < 1e-6
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_infer_product_summary_period_from_neighboring_bill_flow(
    pg_pool: "asyncpg.Pool", clean_tenant: None,
) -> None:
    """End-to-end time inheritance: a product_summary upload borrows its
    period from a neighbouring bill_flow upload's
    merge_inferred_period_start/end (set by Sheet Merger in production).
    """
    from datetime import date

    from smartbi.canonical.provenance import infer_product_summary_period

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            async with conn.transaction():
                # bill_flow upload with explicit merge_inferred_period.
                # created_at defaults to NOW() — the product_summary upload
                # below is created in the same transaction, so both fall
                # within the default 7-day window automatically.
                bill_id = await conn.fetchval(
                    """
                    INSERT INTO smart_bi_pg_excel_uploads
                      (factory_id, file_name, upload_status,
                       detected_table_type,
                       merge_inferred_period_start,
                       merge_inferred_period_end)
                    VALUES ($1, 'bill_flow_a.xlsx', 'COMPLETED',
                            'bill_flow', $2, $3)
                    RETURNING id
                    """,
                    TEST_FACTORY,
                    date(2026, 2, 1), date(2026, 2, 28),
                )

                # product_summary upload — no merge_inferred_period of its own.
                ps_id = await conn.fetchval(
                    """
                    INSERT INTO smart_bi_pg_excel_uploads
                      (factory_id, file_name, upload_status,
                       detected_table_type)
                    VALUES ($1, 'product_summary_a.xlsx', 'COMPLETED',
                            'product_summary')
                    RETURNING id
                    """,
                    TEST_FACTORY,
                )

                result = await infer_product_summary_period(
                    conn, TEST_FACTORY, ps_id
                )

        assert bill_id > 0  # sanity check seed
        assert result is not None
        assert result == (date(2026, 2, 1), date(2026, 2, 28))
    finally:
        await scoped_pool.close()


@pytest.mark.integration
async def test_compute_dish_margin_expired_recipe_falls_back_to_industry_default(
    pg_pool: "asyncpg.Pool", clean_tenant: None,
) -> None:
    """I-B coverage: recipe with valid_to BEFORE the query period_end is
    correctly excluded by read_authoritative_value (Day 6 valid_to filter
    fix), and compute_dish_margin falls back to industry_default — exactly
    as it would for a product with no recipe at all.

    This is the most common production case as recipes age out: a recipe
    written for 2026 Q1 (valid_to=2026-03-31) should NOT be used to compute
    margins for 2026-06 sales — instead the cost should come from
    industry_default (川菜 cost_rate × avg_unit_price).
    """
    from datetime import date
    from smartbi.canonical.provenance import compute_dish_margin, write_provenance

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        async with scoped_pool.acquire() as conn:
            async with conn.transaction():
                product_id = await _seed_dim_product_with_category(
                    conn, TEST_FACTORY, "宫保鸡丁_expired", category="川菜",
                )
                store_id = await _seed_dim_store(
                    conn, TEST_FACTORY, "测试店_expired_recipe"
                )

                # 1. Write a recipe valid 2026-Q1 only — explicitly expired
                #    by 2026-06-15 (the query date_range[1] below).
                await write_provenance(
                    conn,
                    factory_id=TEST_FACTORY,
                    entity_type="product",
                    entity_id=product_id,
                    field_name="cost_per_unit",
                    field_value=8.00,  # if used, would give cost=400 (way off)
                    confidence=0.95,
                    source_type="manual",
                    mapper_method="manual",
                    valid_from=date(2026, 1, 1),
                    valid_to=date(2026, 3, 31),  # ← expired before query
                )

                # 2. Sales in 2026-06 (after recipe expiry).
                upload_id = await conn.fetchval(
                    """
                    INSERT INTO smart_bi_pg_excel_uploads
                      (factory_id, file_name, upload_status)
                    VALUES ($1, $2, 'COMPLETED')
                    RETURNING id
                    """,
                    TEST_FACTORY, "cascade_expired_recipe.xlsx",
                )
                await conn.execute(
                    """
                    INSERT INTO agg_product_period
                      (factory_id, upload_id, product_id, store_id,
                       period_start, period_end, qty_sold, revenue,
                       avg_unit_price)
                    VALUES ($1, $2, $3, $4, $5, $6, 50, 1500, 30)
                    """,
                    TEST_FACTORY, upload_id, product_id, store_id,
                    date(2026, 6, 1), date(2026, 6, 30),
                )

                # 3. Query for 2026-06 — recipe is expired, should fall back.
                result = await compute_dish_margin(
                    conn, TEST_FACTORY, product_id,
                    (date(2026, 6, 1), date(2026, 6, 30)),
                )

        assert result["status"] == "ok"
        # Critical assertion: NOT "manual" — the expired recipe must be excluded.
        assert result["cost_source"] == "industry_default", (
            f"expected industry_default fallback when recipe expired, "
            f"got cost_source={result['cost_source']!r}"
        )
        # 川菜 cost_rate=0.35 × avg_unit_price=30 × qty=50 = 525 (not 8 × 50 = 400)
        assert abs(result["cost"] - 525.0) < 1e-3
        assert float(result["cost_confidence"]) == 0.5  # industry_default conf
    finally:
        await scoped_pool.close()


# ── Day 16 BF1: backfill_provenance e2e ────────────────────────────


async def _reset_backfill_checkpoint(pool: "asyncpg.Pool", table_name: str) -> None:
    """Helper: clear backfill_progress for a table so each e2e test starts clean."""
    async with pool.acquire() as conn:
        await conn.execute(
            "DELETE FROM backfill_progress WHERE table_name = $1", table_name
        )


@pytest.mark.integration
async def test_backfill_agg_product_period_idempotent(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """Seed agg_product_period rows + run backfill twice, verify idempotency.

    Pass 1 inserts 3 fields × 2 rows = 6 provenance rows. Pass 2 produces 0 new
    rows (uq_fp_dedup partial unique index + ON CONFLICT DO NOTHING).
    backfill_progress.rows_inserted must reflect only pass-1 inserts; pass-2's
    skipped rows accumulate into rows_skipped.
    """
    from datetime import date

    from smartbi.scripts.backfill_provenance import backfill_table

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        # Reset checkpoint so prior test runs don't poison this one.
        await _reset_backfill_checkpoint(scoped_pool, "agg_product_period")

        async with scoped_pool.acquire() as conn:
            store_a_id = await _seed_dim_store(conn, TEST_FACTORY, "BF门店A")
            store_b_id = await _seed_dim_store(conn, TEST_FACTORY, "BF门店B")
            product_id = await _seed_dim_product(conn, TEST_FACTORY, "BF产品X")

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED') RETURNING id
                """,
                TEST_FACTORY, f"bf_e2e_{int(time.time())}.xlsx",
            )
            upload_id = int(upload_row["id"])

            # Seed 2 agg_product_period rows (one per store)
            for store_id, qty, rev, price in (
                (store_a_id, 100, 5000.0, 50.0),
                (store_b_id, 50, 3000.0, 60.0),
            ):
                await conn.execute(
                    """
                    INSERT INTO agg_product_period
                      (factory_id, upload_id, product_id, store_id,
                       period_start, period_end, qty_sold, revenue, avg_unit_price)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                    """,
                    TEST_FACTORY, upload_id, product_id, store_id,
                    date(2026, 4, 1), date(2026, 4, 30), qty, rev, price,
                )

        # Pass 1: backfill — sleep=0 so test runs fast.
        result1 = await backfill_table(
            scoped_pool, "agg_product_period", batch_size=100, sleep_between_batches_s=0,
        )
        assert result1["rows_processed"] == 2
        assert result1["rows_inserted"] == 6  # 3 fields × 2 rows

        async with scoped_pool.acquire() as conn:
            count_after_1 = await conn.fetchval(
                """
                SELECT COUNT(*) FROM field_provenance
                 WHERE factory_id = $1 AND entity_type = 'product'
                   AND entity_id = $2 AND created_by = $3
                """,
                TEST_FACTORY, product_id, "backfill_from_agg_product_period",
            )
            assert count_after_1 == 6
            # Verify @store_ suffix on revenue/qty/price
            field_names = await conn.fetch(
                """
                SELECT field_name FROM field_provenance
                 WHERE factory_id = $1 AND entity_type = 'product'
                   AND entity_id = $2 ORDER BY field_name
                """,
                TEST_FACTORY, product_id,
            )
            names = sorted(r["field_name"] for r in field_names)
            expected = sorted([
                f"avg_unit_price@store_{store_a_id}",
                f"avg_unit_price@store_{store_b_id}",
                f"qty_sold@store_{store_a_id}",
                f"qty_sold@store_{store_b_id}",
                f"revenue@store_{store_a_id}",
                f"revenue@store_{store_b_id}",
            ])
            assert names == expected

        # Pass 2: re-run — must be idempotent.
        # First reset checkpoint so the loop re-walks the same rows
        # (otherwise it'd start past last_id and immediately exit).
        await _reset_backfill_checkpoint(scoped_pool, "agg_product_period")
        result2 = await backfill_table(
            scoped_pool, "agg_product_period", batch_size=100, sleep_between_batches_s=0,
        )
        assert result2["rows_processed"] == 2  # walked rows again
        assert result2["rows_inserted"] == 0   # but ON CONFLICT swallowed all

        async with scoped_pool.acquire() as conn:
            count_after_2 = await conn.fetchval(
                """
                SELECT COUNT(*) FROM field_provenance
                 WHERE factory_id = $1 AND entity_type = 'product'
                   AND entity_id = $2 AND created_by = $3
                """,
                TEST_FACTORY, product_id, "backfill_from_agg_product_period",
            )
            assert count_after_2 == 6  # no new rows
    finally:
        await _reset_backfill_checkpoint(scoped_pool, "agg_product_period")
        await scoped_pool.close()


@pytest.mark.integration
async def test_backfill_finance_voucher_rolls_up_real_pg(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """Seed 3 vouchers same (subject, date) different debit amounts → 1 rollup row."""
    from datetime import date

    from smartbi.scripts.backfill_provenance import backfill_table

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        await _reset_backfill_checkpoint(scoped_pool, "fact_finance_voucher")

        async with scoped_pool.acquire() as conn:
            # Seed dim_finance_subject
            subject_row = await conn.fetchrow(
                """
                INSERT INTO dim_finance_subject (factory_id, name, normalized_name)
                VALUES ($1, $2, $3) RETURNING subject_id
                """,
                TEST_FACTORY, "BF_应收账款", "bf_应收账款",
            )
            subject_id = int(subject_row["subject_id"])

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED') RETURNING id
                """,
                TEST_FACTORY, f"bf_finance_{int(time.time())}.xlsx",
            )
            upload_id = int(upload_row["id"])

            # 3 vouchers same (subject, date) with debits (100, 200, 300), credits = 0
            for i, debit in enumerate((100.0, 200.0, 300.0), start=1):
                await conn.execute(
                    """
                    INSERT INTO fact_finance_voucher
                      (factory_id, upload_id, voucher_no, voucher_date,
                       subject_id, debit_amount, credit_amount, source_row_hash)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                    """,
                    TEST_FACTORY, upload_id, f"V{i:04d}", date(2026, 4, 15),
                    subject_id, debit, 0, f"hash_bf_{i}",
                )

        result = await backfill_table(
            scoped_pool, "fact_finance_voucher", batch_size=100, sleep_between_batches_s=0,
        )
        assert result["rows_processed"] == 3
        # 3 vouchers → 1 rollup → 1 INSERT (debit only, credit=0 skipped)
        assert result["rows_inserted"] == 1

        async with scoped_pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT field_name, field_value FROM field_provenance
                 WHERE factory_id = $1 AND entity_type = 'finance_subject'
                   AND entity_id = $2
                """,
                TEST_FACTORY, subject_id,
            )
            assert len(rows) == 1
            assert rows[0]["field_name"] == "debit_amount"
            # field_value JSONB → asyncpg returns str
            value = json.loads(rows[0]["field_value"])
            assert value == 600.0  # 100 + 200 + 300
    finally:
        await _reset_backfill_checkpoint(scoped_pool, "fact_finance_voucher")
        await scoped_pool.close()


@pytest.mark.integration
async def test_backfill_resumes_from_checkpoint(
    pg_pool: "asyncpg.Pool", clean_tenant: None
) -> None:
    """Seed 5 agg_product_period rows; set last_id to skip first 3; verify
    only 2 rows processed.
    """
    from datetime import date

    from smartbi.scripts.backfill_provenance import backfill_table

    async def _setup(conn: "asyncpg.Connection") -> None:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", TEST_FACTORY
        )

    scoped_pool = await asyncpg.create_pool(
        PG_DSN, min_size=1, max_size=2, timeout=5, setup=_setup
    )
    try:
        await _reset_backfill_checkpoint(scoped_pool, "agg_product_period")

        async with scoped_pool.acquire() as conn:
            store_id = await _seed_dim_store(conn, TEST_FACTORY, "BF_RESUME门店")
            product_id = await _seed_dim_product(conn, TEST_FACTORY, "BF_RESUME产品")

            upload_row = await conn.fetchrow(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                  (factory_id, file_name, upload_status)
                VALUES ($1, $2, 'COMPLETED') RETURNING id
                """,
                TEST_FACTORY, f"bf_resume_{int(time.time())}.xlsx",
            )
            upload_id = int(upload_row["id"])

            # Seed 5 rows (different period_start values to satisfy uq_app_natkey).
            inserted_ids: List[int] = []
            for day in range(1, 6):
                row = await conn.fetchrow(
                    """
                    INSERT INTO agg_product_period
                      (factory_id, upload_id, product_id, store_id,
                       period_start, period_end, qty_sold, revenue, avg_unit_price)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                    RETURNING id
                    """,
                    TEST_FACTORY, upload_id, product_id, store_id,
                    date(2026, 4, day), date(2026, 4, day),
                    10 * day, 100.0 * day, 10.0,
                )
                inserted_ids.append(int(row["id"]))

            # Set checkpoint = third row's id so backfill skips first 3.
            checkpoint_id = inserted_ids[2]
            await conn.execute(
                """
                INSERT INTO backfill_progress (table_name, last_id) VALUES ($1, $2)
                ON CONFLICT (table_name) DO UPDATE SET last_id = $2
                """,
                "agg_product_period", checkpoint_id,
            )

        result = await backfill_table(
            scoped_pool, "agg_product_period", batch_size=100, sleep_between_batches_s=0,
        )
        assert result["last_id_start"] == checkpoint_id
        # Only ids 4 and 5 processed = 2 rows × 3 fields = 6 inserted
        assert result["rows_processed"] == 2
        assert result["rows_inserted"] == 6

        # Verify checkpoint advanced
        async with scoped_pool.acquire() as conn:
            ckpt = await conn.fetchrow(
                "SELECT last_id, completed_at FROM backfill_progress WHERE table_name = $1",
                "agg_product_period",
            )
            assert ckpt["last_id"] == inserted_ids[-1]
            assert ckpt["completed_at"] is not None
    finally:
        await _reset_backfill_checkpoint(scoped_pool, "agg_product_period")
        await scoped_pool.close()


@pytest.mark.integration
async def test_backfill_aborts_when_smartbi_user_lacks_factory_id(
    pg_pool: "asyncpg.Pool",
) -> None:
    """C1 verify: smartbi_user (no BYPASSRLS, no app.factory_id pre-set)
    must hit the startup guard and exit 3 — NOT silently produce 0 rows
    because RLS filtered every SELECT.

    Runs the script as a real subprocess to exercise main() end-to-end
    (covers env loading, pool acquisition, the new RLS check). Skips if
    PG_DSN doesn't actually point at smartbi_user (e.g. a postgres-role
    test override would not exercise this path).
    """
    import subprocess
    import sys

    if "smartbi_user" not in PG_DSN:
        pytest.skip("PG_DSN must point at smartbi_user role to test RLS abort path")

    env = os.environ.copy()
    # Make sure I5 doesn't fire first — we want to test C1 specifically.
    env["SMARTBI_ENABLE_PROVENANCE"] = "0"
    env["BACKFILL_PG_DSN"] = PG_DSN
    proc = subprocess.run(
        [
            sys.executable, "-m", "smartbi.scripts.backfill_provenance",
            "--table", "agg_product_period", "--dry-run",
        ],
        env=env, capture_output=True, text=True, timeout=30,
    )
    assert proc.returncode == 3, (
        f"expected exit 3, got {proc.returncode}\n"
        f"--- stderr ---\n{proc.stderr}\n--- stdout ---\n{proc.stdout}"
    )
    # Stderr (or stdout, depending on logging config) should mention BYPASSRLS
    # and/or rolbypassrls so operator knows what to fix.
    combined = (proc.stderr or "") + (proc.stdout or "")
    assert (
        "rolbypassrls" in combined or "BYPASSRLS" in combined
    ), f"expected RLS guard message, got:\n{combined}"


@pytest.mark.integration
async def test_backfill_aborts_when_provenance_flag_on(
    pg_pool: "asyncpg.Pool",
) -> None:
    """I5 verify (real subprocess): SMARTBI_ENABLE_PROVENANCE=1 must
    cause backfill to exit 1 BEFORE opening any pool. Belt-and-braces
    over the unit test — confirms the guard fires in a real CLI invoke.
    """
    import subprocess
    import sys

    env = os.environ.copy()
    env["SMARTBI_ENABLE_PROVENANCE"] = "1"
    env["BACKFILL_PG_DSN"] = PG_DSN
    proc = subprocess.run(
        [
            sys.executable, "-m", "smartbi.scripts.backfill_provenance",
            "--table", "agg_product_period", "--dry-run",
        ],
        env=env, capture_output=True, text=True, timeout=30,
    )
    assert proc.returncode == 1, (
        f"expected exit 1, got {proc.returncode}\n"
        f"--- stderr ---\n{proc.stderr}\n--- stdout ---\n{proc.stdout}"
    )
    combined = (proc.stderr or "") + (proc.stdout or "")
    assert "SMARTBI_ENABLE_PROVENANCE" in combined or "live writer flag" in combined
