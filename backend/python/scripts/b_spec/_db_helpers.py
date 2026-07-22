"""Shared DB helpers for B-spec labeling CLI tools."""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from typing import AsyncIterator

import asyncpg

DEFAULT_DSN = os.environ.get(
    "PG_DSN",
    "postgresql://smartbi_user:smartbi_secure_password_2025@localhost:5432/smartbi_db",
)


def _validate_factory_id(factory_id: str) -> None:
    if not factory_id or not factory_id.replace("_", "").replace("-", "").isalnum():
        raise ValueError(f"invalid factory_id: {factory_id!r}")


@asynccontextmanager
async def acquire_factory_conn(
    dsn: str, factory_id: str
) -> AsyncIterator[asyncpg.Connection]:
    """Open a connection, BEGIN tx, SET LOCAL app.factory_id, yield."""
    _validate_factory_id(factory_id)
    conn = await asyncpg.connect(dsn)
    tx = conn.transaction()
    await tx.start()
    try:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        yield conn
        await tx.commit()
    except Exception:
        await tx.rollback()
        raise
    finally:
        await conn.close()
