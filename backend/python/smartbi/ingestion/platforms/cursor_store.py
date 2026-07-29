"""游标读写。RLS 表, 必须在同一连接上先设 app.factory_id。"""
from __future__ import annotations


async def read_cursor(pool, factory_id: str, platform: str) -> str:
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            row = await conn.fetchrow(
                "SELECT cursor_value FROM platform_sync_cursor "
                "WHERE factory_id = $1 AND platform = $2",
                factory_id, platform,
            )
    return row["cursor_value"] if row else "0"


async def write_cursor(pool, factory_id: str, platform: str, cursor: str) -> None:
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            await conn.execute(
                "INSERT INTO platform_sync_cursor(factory_id, platform, cursor_value) "
                "VALUES ($1, $2, $3) "
                "ON CONFLICT (factory_id, platform) DO UPDATE "
                "SET cursor_value = EXCLUDED.cursor_value, updated_at = NOW()",
                factory_id, platform, cursor,
            )
