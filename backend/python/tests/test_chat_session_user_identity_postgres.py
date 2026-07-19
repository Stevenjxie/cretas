from __future__ import annotations

import os
import uuid
from pathlib import Path
from urllib.parse import urlsplit

import asyncpg
import pytest

from smartbi.services.chat_session_service import ChatSessionService


PYTHON_ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = PYTHON_ROOT / "smartbi" / "database" / "migrations"
DEFAULT_LOCAL_TEST_DSN = (
    "postgresql://smartbi_user:smartbi_pass@localhost:5432/smartbi_db"
)


def _local_test_dsn() -> str:
    dsn = os.getenv("SMARTBI_PG_DSN", DEFAULT_LOCAL_TEST_DSN)
    parsed = urlsplit(dsn)
    database = parsed.path.lstrip("/").lower()
    if parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        pytest.fail("migration integration test refuses non-local PostgreSQL hosts")
    if database != "smartbi_db" and "test" not in database:
        pytest.fail("migration integration test refuses non-test databases")
    return dsn


def _is_local_postgres_absent(exc: BaseException) -> bool:
    current: BaseException | None = exc
    seen: set[int] = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, ConnectionRefusedError):
            return True
        if isinstance(current, OSError) and (
            getattr(current, "winerror", None) == 10061
            or getattr(current, "errno", None) in {61, 111}
        ):
            return True
        current = current.__cause__ or current.__context__
    return False


async def _apply_in_transaction(conn: asyncpg.Connection, sql: str) -> None:
    async with conn.transaction():
        await conn.execute(sql)


class _BorrowConnection:
    def __init__(self, conn: asyncpg.Connection):
        self._conn = conn

    async def __aenter__(self) -> asyncpg.Connection:
        return self._conn

    async def __aexit__(self, exc_type, exc, tb) -> None:
        return None


class _SingleConnectionPool:
    def __init__(self, conn: asyncpg.Connection):
        self._conn = conn

    def acquire(self) -> _BorrowConnection:
        return _BorrowConnection(self._conn)


@pytest.mark.asyncio
async def test_chat_session_identity_migration_executes_and_is_idempotent():
    dsn = _local_test_dsn()
    try:
        conn = await asyncpg.connect(dsn)
    except Exception as exc:
        if _is_local_postgres_absent(exc):
            pytest.skip(
                f"Local PostgreSQL is not accepting connections: {type(exc).__name__}"
            )
        raise

    schema = f"test_chat_session_identity_{uuid.uuid4().hex}"
    original_schema = (
        MIGRATIONS / "V20260426_02__chat_session.sql"
    ).read_text(encoding="utf-8")
    turns_history = (
        MIGRATIONS / "V20260427_01__chat_session_v3_history.sql"
    ).read_text(encoding="utf-8")
    identity_migration = (
        MIGRATIONS / "V20261028_02__chat_session_user_identity.sql"
    ).read_text(encoding="utf-8")

    try:
        database = await conn.fetchval("SELECT current_database()")
        if database != "smartbi_db" and "test" not in str(database).lower():
            pytest.fail("connected database failed the local test safety gate")

        await conn.execute(f'CREATE SCHEMA "{schema}"')
        await conn.execute(f'SET search_path TO "{schema}", public')
        await conn.execute(original_schema)
        await conn.execute(turns_history)

        service = ChatSessionService(_SingleConnectionPool(conn))
        await service.upsert(
            "writer-expand-sid",
            "FACTORY_A",
            "first query",
            "first answer",
            user_id=101,
        )
        await service.upsert(
            "writer-expand-sid",
            "FACTORY_A",
            "same user update",
            "same user answer",
            user_id=101,
        )
        await service.upsert(
            "writer-expand-sid",
            "FACTORY_A",
            "other user must not overwrite",
            "other user answer",
            user_id=202,
        )
        legacy_writer_rows = await conn.fetch(
            """
            SELECT user_id, parent_query, turn_count
            FROM smart_bi_chat_session
            WHERE session_id = 'writer-expand-sid'
            """
        )
        assert [dict(row) for row in legacy_writer_rows] == [
            {
                "user_id": 101,
                "parent_query": "same user update",
                "turn_count": 2,
            }
        ]

        await conn.execute(
            """
            INSERT INTO smart_bi_chat_session
                (session_id, factory_id, user_id, parent_query)
            VALUES
                ('legacy-anon', 'FACTORY_A', NULL, 'anonymous'),
                ('legacy-owned', 'FACTORY_A', 101, 'owned')
            """
        )

        await _apply_in_transaction(conn, identity_migration)

        assert await conn.fetchval(
            "SELECT COUNT(*) FROM smart_bi_chat_session WHERE user_id IS NULL"
        ) == 0
        assert await conn.fetchval(
            """
            SELECT attnotnull
            FROM pg_attribute
            WHERE attrelid = 'smart_bi_chat_session'::regclass
              AND attname = 'user_id'
              AND NOT attisdropped
            """
        ) is True

        unique_defs = [
            row["definition"]
            for row in await conn.fetch(
                """
                SELECT pg_get_constraintdef(oid) AS definition
                FROM pg_constraint
                WHERE conrelid = 'smart_bi_chat_session'::regclass
                  AND contype = 'u'
                """
            )
        ]
        assert "UNIQUE (session_id)" not in unique_defs
        assert "UNIQUE (factory_id, user_id, session_id)" in unique_defs

        await service.upsert(
            "dual-user-sid",
            "FACTORY_A",
            "user one",
            "answer one",
            user_id=101,
        )
        await service.upsert(
            "dual-user-sid",
            "FACTORY_A",
            "user two",
            "answer two",
            user_id=202,
        )
        assert await conn.fetchval(
            """
            SELECT COUNT(*)
            FROM smart_bi_chat_session
            WHERE factory_id = 'FACTORY_A'
              AND session_id = 'dual-user-sid'
            """
        ) == 2

        with pytest.raises(asyncpg.UniqueViolationError):
            await conn.execute(
                """
                INSERT INTO smart_bi_chat_session
                    (session_id, factory_id, user_id)
                VALUES ('dual-user-sid', 'FACTORY_A', 101)
                """
            )

        # A direct second execution must be harmless even though the migration
        # runner normally records each file only once.
        await _apply_in_transaction(conn, identity_migration)
        assert await conn.fetchval(
            """
            SELECT COUNT(*)
            FROM pg_constraint
            WHERE conrelid = 'smart_bi_chat_session'::regclass
              AND contype = 'u'
            """
        ) == 1
    finally:
        try:
            await conn.execute("RESET search_path")
            await conn.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
        finally:
            await conn.close()
